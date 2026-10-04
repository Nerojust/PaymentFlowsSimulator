package com.nerojust.paymentsim.server

import androidx.room.withTransaction
import com.nerojust.paymentsim.client.api.dto.LedgerEntryDto
import com.nerojust.paymentsim.client.api.dto.PaymentRequest
import com.nerojust.paymentsim.client.api.dto.PaymentResponse
import com.nerojust.paymentsim.log.EventLog
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.log.shortKey
import com.nerojust.paymentsim.model.formatMinor
import com.nerojust.paymentsim.network.NetworkSimulator
import com.nerojust.paymentsim.server.db.LedgerEntryEntity
import com.nerojust.paymentsim.server.db.ProcessedPaymentEntity
import com.nerojust.paymentsim.server.db.ServerDatabase
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** The payment backend, in process. Only [FakeBackendInterceptor] talks to it. */
class FakePaymentServer(
    private val db: ServerDatabase,
    private val simulator: NetworkSimulator,
    private val log: EventLog,
    private val json: Json,
    private val declineLimitMinor: Long = DEFAULT_DECLINE_LIMIT_MINOR,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Reply(val code: Int, val body: String)

    private val dao = db.serverDao()

    // Slide 14
    suspend fun createPayment(idempotencyKey: String?, request: PaymentRequest): Reply {
        val settings = simulator.settings.value

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
                ProcessedPaymentEntity(
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
                    log.log(LogSource.SERVER, "422: key $key reused with a different request, no charge")
                    Reply(422, encode(PaymentResponse(error = "key_reused_with_different_request")))
                }
                existing.status == STATUS_PROCESSING || existing.resultJson == null -> {
                    log.log(LogSource.SERVER, "409: key $key is still processing")
                    Reply(409, encode(PaymentResponse(status = STATUS_PROCESSING)))
                }
                else -> {
                    // Never charge again.
                    log.log(LogSource.SERVER, "key $key already ${existing.status}: returning stored result, no new charge")
                    Reply(201, existing.resultJson)
                }
            }
        }

        log.log(LogSource.SERVER, "claimed key ${idempotencyKey.shortKey()}, processing")
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
            LedgerEntryDto(it.chargeId, it.idempotencyKey, it.amountMinor, it.recipient, it.createdAt)
        }
        return Reply(200, json.encodeToString(ListSerializer(LedgerEntryDto.serializer()), entries))
    }

    /**
     * "Kill app" also kills this in-process server mid-request, which a real backend would survive.
     * ponytail: drop the half-made claims on start so the client's same-key resend goes through;
     * a real server would expire claims with a lease/TTL instead.
     */
    suspend fun releaseStuckClaims() {
        val released = dao.releaseProcessingClaims()
        if (released > 0) log.log(LogSource.SERVER, "released $released claim(s) interrupted by the restart")
    }

    suspend fun reset() {
        dao.clearProcessed()
        dao.clearLedger()
    }

    /** Decline rule, then exactly one ledger row on success. */
    private suspend fun charge(ledgerKey: String?, responseKey: String?, request: PaymentRequest): PaymentResponse {
        val label = ledgerKey?.shortKey() ?: "no key"
        if (request.amountMinor > declineLimitMinor) {
            log.log(LogSource.SERVER, "declined ${formatMinor(request.amountMinor)} ($label): $REASON_INSUFFICIENT_FUNDS")
            return PaymentResponse(key = responseKey, status = STATUS_FAILED, reason = REASON_INSUFFICIENT_FUNDS)
        }
        val chargeId = dao.insertLedger(
            LedgerEntryEntity(
                idempotencyKey = ledgerKey,
                amountMinor = request.amountMinor,
                recipient = request.recipient,
                createdAt = clock(),
            ),
        )
        log.log(LogSource.SERVER, "charged ${formatMinor(request.amountMinor)} -> charge #$chargeId ($label)")
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
