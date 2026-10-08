package com.nerojust.paymentsim.client.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nerojust.paymentsim.model.PendingPaymentStatus

// Slide 10
@Entity(tableName = "pending_payments")
data class PendingPayment(
    @PrimaryKey val id: String,      // also the idempotency key
    val amountMinor: Long,           // kobo/cents, never Double
    val recipient: String,
    val timestamp: Long,
    val status: String = PendingPaymentStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
)
