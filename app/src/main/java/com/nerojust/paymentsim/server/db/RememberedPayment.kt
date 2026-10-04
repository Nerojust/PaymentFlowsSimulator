package com.nerojust.paymentsim.server.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Slide 14
@Entity(tableName = "processed_payments")
data class RememberedPayment(
    @PrimaryKey val idempotencyKey: String,
    val requestHash: String,     // sha256 of amountMinor|currency|recipient
    val status: String,          // processing | succeeded | failed
    val resultJson: String?,
    val createdAt: Long,
)
