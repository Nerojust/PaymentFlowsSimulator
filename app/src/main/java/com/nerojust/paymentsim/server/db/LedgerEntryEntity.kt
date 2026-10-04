package com.nerojust.paymentsim.server.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Slide 14
@Entity(tableName = "ledger")
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val chargeId: Long = 0,
    val idempotencyKey: String?, // null when idempotency is off
    val amountMinor: Long,
    val recipient: String,
    val createdAt: Long,
)
