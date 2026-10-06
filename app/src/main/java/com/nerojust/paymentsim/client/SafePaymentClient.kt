package com.nerojust.paymentsim.client

import com.nerojust.paymentsim.client.api.PaymentApi
import com.nerojust.paymentsim.client.api.dto.PaymentRequest
import com.nerojust.paymentsim.client.api.dto.PaymentResponse
import com.nerojust.paymentsim.client.db.PendingPaymentDao
import com.nerojust.paymentsim.client.db.PendingPayment
import com.nerojust.paymentsim.log.EventLog
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.log.shortKey
import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.PendingPaymentStatus
import com.nerojust.paymentsim.model.formatMinor
import com.nerojust.paymentsim.model.label
import com.nerojust.paymentsim.model.transition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/** The Safe client: state machine, offline-first queue, idempotency keys, reconciliation. */
class SafePaymentClient(
    private val dao: PendingPaymentDao,
    private val api: PaymentApi,
    private val log: EventLog,
    private val enqueueRetryWorker: (paymentId: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val reuseWindowMs: Long = DEFAULT_REUSE_WINDOW_MS,
) {
    // One payment is sent at a time: Pay taps, the app-start reconciliation and the worker all queue here.
    // Mutex is fair, so stacked payments go out in the order they were saved.
    private val mutex = Mutex()
    private val saveMutex = Mutex()

    private val _state = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val state: StateFlow<PaymentState> = _state.asStateFlow()

    // Slides 9 and 13
    suspend fun initiatePayment(amountMinor: Long, recipient: String): String {
        // Saved on the phone right away, so Pay can be tapped again for another payment while this one waits.
        val id = save(amountMinor, recipient)
        exclusive {
            // It may have been settled (or wiped by a reset) while it waited in line.
            val payment = dao.find(id)?.takeIf { it.status in UNSETTLED } ?: return@exclusive
            enterPending()
            val settled = try {
                send(payment)
            } catch (e: IOException) {
                noteNetworkError(payment, e)
                false
            }
            if (!settled) {
                move(PaymentState.Retrying)
                retryWithBackoff(payment)
            }
        }
        return id
    }

    private suspend fun save(amountMinor: Long, recipient: String): String = saveMutex.withLock {
        val now = clock()
        // Same user intent still in flight? Reuse its key instead of making a new one.
        val inFlight = dao.findInFlight(amountMinor, recipient, since = now - reuseWindowMs)
        if (inFlight != null) {
            log.log(LogSource.CLIENT, "This payment is already saved. Using the same key ${inFlight.id.shortKey()}, not a new one")
            return@withLock inFlight.id
        }
        val payment = PendingPayment(
            id = UUID.randomUUID().toString(), // generated once per intent
            amountMinor = amountMinor,
            recipient = recipient,
            timestamp = now,
        )
        dao.insert(payment) // persisted BEFORE any network call
        log.log(LogSource.CLIENT, "Saved ${formatMinor(amountMinor)} to $recipient on this phone. Key ${payment.id.shortKey()}")
        payment.id
    }

    // Slide 10
    suspend fun retryWithBackoff(payment: PendingPayment) {
        var backoffMs = 1000L
        repeat(MAX_RETRIES) { attempt ->
            try {
                dao.incrementRetryCount(payment.id)
                // same idempotency key every time
                if (send(payment)) return
            } catch (e: IOException) {
                // retry network errors only, never declines or 4xx
                noteNetworkError(payment, e)
            }
            move(PaymentState.Retrying)
            log.log(
                LogSource.CLIENT,
                "Try ${attempt + 1} of $MAX_RETRIES did not work, waiting ${backoffMs / 1000}s before the next try",
            )
            delay(backoffMs + Random.nextLong(0, 500))
            backoffMs *= 2
        }
        markNeedsReconcile(payment)
        enqueueRetryWorker(payment.id)
    }

    // Slide 17
    suspend fun reconcilePendingPayments() = exclusive {
        for (payment in dao.unsettled()) reconcile(payment, userConfirmed = false)
    }

    /** One payment: used by the worker, and by the dialog's "Send" with [userConfirmed] = true. */
    suspend fun reconcilePayment(id: String, userConfirmed: Boolean = false) = exclusive {
        val payment = dao.find(id) ?: return@exclusive
        val reconcilable = payment.status in UNSETTLED ||
            (userConfirmed && payment.status == PendingPaymentStatus.AWAITING_USER_CONFIRMATION)
        if (!reconcilable) return@exclusive
        // "Send it" is the user saying "I still want this" right now. Restart the clock, so a try that
        // fails (still offline) is not asked about a second time.
        if (userConfirmed) dao.setTimestamp(id, clock())
        reconcile(payment, userConfirmed)
    }

    /** The dialog's "Cancel": the stale payment is dropped and never sent. */
    suspend fun cancelPayment(id: String) = exclusive {
        dao.updateStatus(id, PendingPaymentStatus.FAILED, CANCELLED_BY_USER)
        log.log(LogSource.CLIENT, "You cancelled old payment ${id.shortKey()}. Nothing was sent")
        if (_state.value is PaymentState.Retrying) {
            move(PaymentState.Pending)
            move(PaymentState.Failed(CANCELLED_BY_USER))
        }
    }

    private suspend fun reconcile(payment: PendingPayment, userConfirmed: Boolean) {
        val key = payment.id.shortKey()
        val ageMs = clock() - payment.timestamp
        if (!userConfirmed && ageMs > ttlMs) {
            // Never fire a stale payment silently: ask the user.
            dao.updateStatus(payment.id, PendingPaymentStatus.AWAITING_USER_CONFIRMATION, payment.lastError)
            log.log(LogSource.CLIENT, "Payment $key is ${ageMs / 60_000} min old. Asking you before sending it")
            return
        }
        try {
            enterConfirming()
            log.log(LogSource.CLIENT, "Asking the bank: what happened to payment $key?")
            val response = api.getPayment(payment.id)
            when {
                settleFrom(payment, response.body()) -> Unit
                response.code() == 404 -> {
                    log.log(LogSource.CLIENT, "The bank never got payment $key. Sending it again with the SAME key")
                    if (!send(payment)) scheduleStatusCheck(payment)
                }
                // processing (or a 5xx): the server may still be charging. Do not resend.
                else -> scheduleStatusCheck(payment)
            }
        } catch (e: IOException) {
            noteNetworkError(payment, e)
            scheduleStatusCheck(payment)
        }
    }

    /**
     * One POST with the payment's own key. Returns true when the payment reached a final state,
     * false when the answer was 409 or 5xx and the caller should try again later. IOException propagates.
     */
    private suspend fun send(payment: PendingPayment): Boolean {
        enterConfirming()
        log.log(LogSource.CLIENT, "Sending payment ${payment.id.shortKey()} to the bank")
        val response = api.createPayment(
            payment.id,
            PaymentRequest(payment.amountMinor, CURRENCY, payment.recipient),
        )
        val code = response.code()
        return when {
            code == 201 -> settleFrom(payment, response.body())
            code == 409 || code >= 500 -> {
                dao.setLastError(payment.id, "HTTP $code")
                val why = if (code == 409) "The bank is still busy with" else "The bank had a problem with"
                log.log(LogSource.CLIENT, "$why payment ${payment.id.shortKey()}. Will try again")
                false
            }
            code == 422 -> {
                log.log(LogSource.CLIENT, "STOP: key ${payment.id.shortKey()} was already used for a different payment. Not sending it again")
                fail(payment, REQUEST_MISMATCH)
            }
            else -> fail(payment, "HTTP $code")
        }
    }

    // ---- settling: what the client does with the server's answer ----

    /** The server's final answer settles the payment. Returns false when it has no final answer yet. */
    private suspend fun settleFrom(payment: PendingPayment, answer: PaymentResponse?): Boolean =
        when (answer?.status) {
            STATUS_SUCCEEDED -> {
                dao.updateStatus(payment.id, PendingPaymentStatus.CONFIRMED, null)
                move(PaymentState.Success)
                true
            }
            // A real decline. Do not retry.
            STATUS_FAILED -> fail(payment, answer.reason ?: "declined")
            else -> false
        }

    private suspend fun fail(payment: PendingPayment, reason: String): Boolean {
        dao.updateStatus(payment.id, PendingPaymentStatus.FAILED, reason)
        move(PaymentState.Failed(reason))
        return true
    }

    private suspend fun markNeedsReconcile(payment: PendingPayment) {
        dao.updateStatus(payment.id, PendingPaymentStatus.NEEDS_RECONCILE, dao.find(payment.id)?.lastError)
        log.log(LogSource.CLIENT, "Still no answer for payment ${payment.id.shortKey()}. It will be checked again in the background")
    }

    private suspend fun scheduleStatusCheck(payment: PendingPayment) {
        markNeedsReconcile(payment)
        move(PaymentState.Retrying)
        enqueueRetryWorker(payment.id)
    }

    private suspend fun noteNetworkError(payment: PendingPayment, e: IOException) {
        dao.setLastError(payment.id, e.message)
        log.log(LogSource.CLIENT, "No answer for payment ${payment.id.shortKey()}: ${e.message}")
    }

    // ---- state machine bookkeeping: every change of the on-screen state goes through move() ----

    /** Walks the state machine to Pending from wherever a new attempt may legally start. */
    private fun enterPending() {
        if (_state.value is PaymentState.Success || _state.value is PaymentState.Failed) move(PaymentState.Idle)
        if (_state.value !is PaymentState.Pending) move(PaymentState.Pending)
    }

    private fun enterConfirming() {
        if (_state.value is PaymentState.Confirming) return
        enterPending()
        move(PaymentState.Confirming)
    }

    private fun move(to: PaymentState) {
        _state.value = transition(_state.value, to)
        log.log(LogSource.CLIENT, "State: ${to.label}")
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } catch (e: CancellationException) {
            // Abandoned mid-flight (reset, or WorkManager stopped the worker). The row stays in the queue
            // for reconciliation; the on-screen state starts over.
            _state.value = PaymentState.Idle
            throw e
        }
    }

    // ---- small helpers for the app start, the worker and the demo buttons ----

    suspend fun unsettledCount(): Int = dao.unsettled().size

    /** True when the same amount to the same person is already saved and not finished. */
    suspend fun isAlreadySaved(amountMinor: Long, recipient: String): Boolean =
        dao.findInFlight(amountMinor, recipient, since = clock() - reuseWindowMs) != null

    suspend fun isUnsettled(id: String): Boolean = dao.find(id)?.status in UNSETTLED

    /** Scenario 7: same key, different amount. The server must answer 422 and not charge. */
    suspend fun debugReuseKeyWithDifferentAmount() {
        val last = dao.latest()
        if (last == null) {
            log.log(LogSource.CLIENT, "Make a payment with the Careful app first, then try this")
            return
        }
        try {
            val request = PaymentRequest(last.amountMinor + 100, CURRENCY, last.recipient)
            val response = api.createPayment(last.id, request)
            log.log(
                LogSource.CLIENT,
                "Sent key ${last.id.shortKey()} again with a different amount (${formatMinor(request.amountMinor)}). " +
                    if (response.isSuccessful) "The bank took it as a new payment" else "The bank refused it",
            )
        } catch (e: IOException) {
            log.log(LogSource.CLIENT, "Could not send: ${e.message}")
        }
    }

    /** "Reset all data": a demo-only way out of the state machine. */
    suspend fun reset() = exclusive {
        dao.clear()
        _state.value = PaymentState.Idle
    }

    companion object {
        const val MAX_RETRIES = 5
        const val CURRENCY = "NGN"
        const val DEFAULT_TTL_MS = 2 * 60_000L          // demo value. Production: 24h.
        const val DEFAULT_REUSE_WINDOW_MS = 5 * 60_000L
        const val REQUEST_MISMATCH = "This key was used for a different payment"
        const val CANCELLED_BY_USER = "You cancelled it"

        private const val STATUS_SUCCEEDED = "succeeded"
        private const val STATUS_FAILED = "failed"
        private val UNSETTLED = setOf(PendingPaymentStatus.PENDING, PendingPaymentStatus.NEEDS_RECONCILE)
    }
}
