package com.nerojust.paymentsim.server

import androidx.room.withTransaction
import com.nerojust.paymentsim.client.api.dto.ChargeDto
import com.nerojust.paymentsim.client.api.dto.PaymentRequest
import com.nerojust.paymentsim.client.api.dto.PaymentResponse
import com.nerojust.paymentsim.log.EventLog
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.log.shortKey
import com.nerojust.paymentsim.model.formatMinor
import com.nerojust.paymentsim.network.FakeNetwork
import com.nerojust.paymentsim.server.db.Charge
import com.nerojust.paymentsim.server.db.RememberedPayment
import com.nerojust.paymentsim.server.db.ServerDatabase
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** The payment backend, in process. Only [FakeServerInterceptor] talks to it. */
class FakePaymentServer(
    private val db: ServerDatabase,
    private val network: FakeNetwork,
    private val log: EventLog,
    private val json: Json,
    private val declineLimitMinor: Long = DEFAULT_DECLINE_LIMIT_MINOR,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Reply(val code: Int, val body: String)

    private val dao = db.serverDao()

    // Slide 14
    suspend fun createPayment(idempotencyKey: String?, request: PaymentRequest): Reply {
        val settings = network.settings.value

        if (!settings.serverIdempotencyEnabled) {
            // No idempotency: every request that arrives is processed and charged again.
            delay(settings.processingDelayMs)
            return Reply(201, encode(charge(ledgerKey = null, responseKey = idempotencyKey, request)))
        }

        if (idempotencyKey.isNullOrBlank()) {
            return Reply(400, encode(PaymentResponse(error = "missing_idempotency_key")))
        }
        val requestHash = sha256("${request.amountMinor}|${request.currency}|${request.recipient}")

        // Claim the key first: INSERT ... ON CONFLICT DO NOTHING
        val existing = db.withTransaction {
            val rowId = dao.claim(
                RememberedPayment(
                    idempotencyKey = idempotencyKey,
                    requestHash = requestHash,
                    status = STATUS_PROCESSING,
                    resultJson = null,
                    createdAt = clock(),
                ),
            )
            if (rowId == -1L) dao.find(idempotencyKey) else null
        }

        if (existing != null) {
            val key = idempotencyKey.shortKey()
            return when {
                existing.requestHash != requestHash -> {
                    log.log(LogSource.SERVER, "Key $key was already used for a different payment. Refused, no charge")
                    Reply(422, encode(PaymentResponse(error = "key_reused_with_different_request")))
                }
                existing.status == STATUS_PROCESSING || existing.resultJson == null -> {
                    log.log(LogSource.SERVER, "Still busy with payment $key. Try later")
                    Reply(409, encode(PaymentResponse(status = STATUS_PROCESSING)))
                }
                else -> {
                    // Never charge again.
                    log.log(LogSource.SERVER, "Already saw payment $key. Sending back the saved answer. No new charge")
                    Reply(201, existing.resultJson)
                }
            }
        }

        log.log(LogSource.SERVER, "New payment ${idempotencyKey.shortKey()}. Working on it")
        delay(settings.processingDelayMs)
        // The charge and the stored result commit together, so a `processing` row always means "not charged".
        val resultJson = db.withTransaction {
            val result = charge(ledgerKey = idempotencyKey, responseKey = idempotencyKey, request)
            encode(result).also { dao.complete(idempotencyKey, result.status.orEmpty(), it) }
        }
        return Reply(201, resultJson)
    }

    suspend fun getPayment(key: String): Reply {
        val row = dao.find(key) ?: return Reply(404, encode(PaymentResponse(status = STATUS_NOT_FOUND)))
        return Reply(200, row.resultJson ?: encode(PaymentResponse(key = key, status = row.status)))
    }

    suspend fun ledger(): Reply {
        val entries = dao.ledger().map {
            ChargeDto(it.chargeId, it.idempotencyKey, it.amountMinor, it.recipient, it.createdAt)
        }
        return Reply(200, json.encodeToString(ListSerializer(ChargeDto.serializer()), entries))
    }

    /**
     * "Kill app" also kills this in-process server mid-request, which a real backend would survive.
     * ponytail: drop the half-made claims on start so the client's same-key resend goes through;
     * a real server would expire claims with a lease/TTL instead.
     */
    suspend fun releaseStuckClaims() {
        val released = dao.releaseProcessingClaims()
        if (released > 0) log.log(LogSource.SERVER, "Cleared $released unfinished payment(s) left by the restart")
    }

    suspend fun reset() {
        dao.clearProcessed()
        dao.clearLedger()
    }

    /** Decline rule, then exactly one ledger row on success. */
    private suspend fun charge(ledgerKey: String?, responseKey: String?, request: PaymentRequest): PaymentResponse {
        val label = ledgerKey?.let { "key ${it.shortKey()}" } ?: "no key"
        if (request.amountMinor > declineLimitMinor) {
            log.log(LogSource.SERVER, "Said no to ${formatMinor(request.amountMinor)} ($label): not enough money")
            return PaymentResponse(key = responseKey, status = STATUS_FAILED, reason = REASON_INSUFFICIENT_FUNDS)
        }
        val chargeId = dao.insertLedger(
            Charge(
                idempotencyKey = ledgerKey,
                amountMinor = request.amountMinor,
                recipient = request.recipient,
                createdAt = clock(),
            ),
        )
        log.log(LogSource.SERVER, "Charged ${formatMinor(request.amountMinor)}. This is charge #$chargeId ($label)")
        return PaymentResponse(key = responseKey, status = STATUS_SUCCEEDED, chargeId = chargeId)
    }

    private fun encode(response: PaymentResponse): String =
        json.encodeToString(PaymentResponse.serializer(), response)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_DECLINE_LIMIT_MINOR = 5_000_000L // ₦50,000.00
        const val STATUS_PROCESSING = "processing"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"
        const val STATUS_NOT_FOUND = "not_found"
        const val REASON_INSUFFICIENT_FUNDS = "insufficient_funds"
    }
}
