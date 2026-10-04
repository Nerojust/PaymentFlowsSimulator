package com.nerojust.paymentsim.client

import com.nerojust.paymentsim.client.api.PaymentApi
import com.nerojust.paymentsim.client.api.dto.PaymentRequest
import com.nerojust.paymentsim.client.api.dto.PaymentResponse
import com.nerojust.paymentsim.client.db.PendingPaymentDao
import com.nerojust.paymentsim.client.db.PendingPaymentEntity
import com.nerojust.paymentsim.log.EventLog
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.log.shortKey
import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.PaymentStatus
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
class PaymentRepository(
    private val dao: PendingPaymentDao,
    private val api: PaymentApi,
    private val log: EventLog,
    private val enqueueRetryWorker: (paymentId: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val reuseWindowMs: Long = DEFAULT_REUSE_WINDOW_MS,
) {
    // One payment operation at a time: the UI, the app-start reconciliation and the worker all queue here.
    private val mutex = Mutex()

    private val _state = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val state: StateFlow<PaymentState> = _state.asStateFlow()

    // Slides 9 and 13
    suspend fun initiatePayment(amountMinor: Long, recipient: String): String = exclusive {
        val now = clock()
        // Same user intent still in flight? Reuse its key instead of making a new one.
        val inFlight = dao.findInFlight(amountMinor, recipient, since = now - reuseWindowMs)
        val payment = inFlight ?: PendingPaymentEntity(
            id = UUID.randomUUID().toString(), // generated once per intent
            amountMinor = amountMinor,
            recipient = recipient,
            timestamp = now,
        )

        enterPending()

        if (inFlight == null) {
            dao.insert(payment) // persisted BEFORE any network call
            log.log(LogSource.CLIENT, "queued ${formatMinor(amountMinor)} to $recipient, key ${payment.id.shortKey()}")
        } else {
            log.log(LogSource.CLIENT, "same intent already in flight: reusing key ${payment.id.shortKey()}")
        }

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
        payment.id
    }

    // Slide 10
    suspend fun retryWithBackoff(payment: PendingPaymentEntity) {
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
                "retry ${attempt + 1}/$MAX_RETRIES failed, waiting ${backoffMs / 1000}s (+jitter), key ${payment.id.shortKey()}",
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
            (userConfirmed && payment.status == PaymentStatus.AWAITING_USER_CONFIRMATION)
        if (reconcilable) reconcile(payment, userConfirmed)
    }

    /** The dialog's "Cancel": the stale payment is dropped and never sent. */
    suspend fun cancelPayment(id: String) = exclusive {
        dao.updateStatus(id, PaymentStatus.FAILED, CANCELLED_BY_USER)
        log.log(LogSource.CLIENT, "user cancelled stale payment ${id.shortKey()}, nothing was sent")
        if (_state.value is PaymentState.Retrying) {
            move(PaymentState.Pending)
            move(PaymentState.Failed(CANCELLED_BY_USER))
        }
    }

    private suspend fun reconcile(payment: PendingPaymentEntity, userConfirmed: Boolean) {
        val key = payment.id.shortKey()
        val ageMs = clock() - payment.timestamp
        if (!userConfirmed && ageMs > ttlMs) {
            // Never fire a stale payment silently: ask the user.
            dao.updateStatus(payment.id, PaymentStatus.AWAITING_USER_CONFIRMATION, payment.lastError)
            log.log(LogSource.CLIENT, "key $key is ${ageMs / 60_000} min old (past TTL): asking the user")
            return
        }
        try {
            enterConfirming()
            log.log(LogSource.CLIENT, "reconcile: GET /payments/$key")
            val response = api.getPayment(payment.id)
            when {
                settleFrom(payment, response.body()) -> Unit
                response.code() == 404 -> {
                    log.log(LogSource.CLIENT, "server never saw key $key: resending with the SAME key")
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
    private suspend fun send(payment: PendingPaymentEntity): Boolean {
        enterConfirming()
        log.log(LogSource.CLIENT, "POST /payments key ${payment.id.shortKey()}")
        val response = api.createPayment(
            payment.id,
            PaymentRequest(payment.amountMinor, CURRENCY, payment.recipient),
        )
        val code = response.code()
        return when {
            code == 201 -> settleFrom(payment, response.body())
            code == 409 || code >= 500 -> {
                dao.setLastError(payment.id, "HTTP $code")
                log.log(LogSource.CLIENT, "HTTP $code for key ${payment.id.shortKey()}: will retry")
                false
            }
            code == 422 -> {
                log.log(LogSource.CLIENT, "!!! 422 REQUEST MISMATCH: key ${payment.id.shortKey()} was reused with a different request !!!")
                fail(payment, REQUEST_MISMATCH)
            }
            else -> fail(payment, "HTTP $code")
        }
    }

    // ---- settling: what the client does with the server's answer ----

    /** The server's final answer settles the payment. Returns false when it has no final answer yet. */
    private suspend fun settleFrom(payment: PendingPaymentEntity, answer: PaymentResponse?): Boolean =
        when (answer?.status) {
            STATUS_SUCCEEDED -> {
                dao.updateStatus(payment.id, PaymentStatus.CONFIRMED, null)
                move(PaymentState.Success)
                true
            }
            // A real decline. Do not retry.
            STATUS_FAILED -> fail(payment, answer.reason ?: "declined")
            else -> false
        }

    private suspend fun fail(payment: PendingPaymentEntity, reason: String): Boolean {
        dao.updateStatus(payment.id, PaymentStatus.FAILED, reason)
        move(PaymentState.Failed(reason))
        return true
    }

    private suspend fun markNeedsReconcile(payment: PendingPaymentEntity) {
        dao.updateStatus(payment.id, PaymentStatus.NEEDS_RECONCILE, dao.find(payment.id)?.lastError)
        log.log(LogSource.CLIENT, "key ${payment.id.shortKey()} -> needs_reconcile, handing over to WorkManager")
    }

    private suspend fun scheduleStatusCheck(payment: PendingPaymentEntity) {
        markNeedsReconcile(payment)
        move(PaymentState.Retrying)
        enqueueRetryWorker(payment.id)
    }

    private suspend fun noteNetworkError(payment: PendingPaymentEntity, e: IOException) {
        dao.setLastError(payment.id, e.message)
        log.log(LogSource.CLIENT, "network error for key ${payment.id.shortKey()}: ${e.message}")
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
        log.log(LogSource.CLIENT, "state -> ${to.label}")
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

    suspend fun isUnsettled(id: String): Boolean = dao.find(id)?.status in UNSETTLED

    /** Scenario 7: same key, different amount. The server must answer 422 and not charge. */
    suspend fun debugReuseKeyWithDifferentAmount() {
        val last = dao.latest()
        if (last == null) {
            log.log(LogSource.CLIENT, "DEBUG: make a Safe payment first, then reuse its key")
            return
        }
        try {
            val request = PaymentRequest(last.amountMinor + 100, CURRENCY, last.recipient)
            val response = api.createPayment(last.id, request)
            log.log(
                LogSource.CLIENT,
                "DEBUG: reused key ${last.id.shortKey()} with ${formatMinor(request.amountMinor)} -> HTTP ${response.code()}",
            )
        } catch (e: IOException) {
            log.log(LogSource.CLIENT, "DEBUG: resend failed: ${e.message}")
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
        const val REQUEST_MISMATCH = "Request mismatch"
        const val CANCELLED_BY_USER = "Cancelled by user"

        private const val STATUS_SUCCEEDED = "succeeded"
        private const val STATUS_FAILED = "failed"
        private val UNSETTLED = setOf(PaymentStatus.PENDING, PaymentStatus.NEEDS_RECONCILE)
    }
}
