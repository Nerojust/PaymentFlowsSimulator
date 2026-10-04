package com.nerojust.paymentsim.client.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class PaymentRequest(
    val amountMinor: Long,
    val currency: String,
    val recipient: String,
)

/** One shape for every /payments answer: 201, 200, 404, 409 and 422 each fill the fields they need. */
@Serializable
data class PaymentResponse(
    val key: String? = null,
    val status: String? = null,
    val chargeId: Long? = null,
    val reason: String? = null,
    val error: String? = null,
)

@Serializable
data class LedgerEntryDto(
    val chargeId: Long,
    val idempotencyKey: String? = null,
    val amountMinor: Long,
    val recipient: String,
    val createdAt: Long,
)
