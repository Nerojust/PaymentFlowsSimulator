package com.nerojust.paymentsim.client.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingPaymentDao {

    @Insert
    suspend fun insert(payment: PendingPayment)

    @Query("SELECT * FROM pending_payments ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<PendingPayment>>

    @Query("SELECT * FROM pending_payments WHERE id = :id")
    suspend fun find(id: String): PendingPayment?

    @Query("SELECT * FROM pending_payments ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): PendingPayment?

    @Query(
        "SELECT * FROM pending_payments WHERE status IN ('pending', 'needs_reconcile') ORDER BY timestamp",
    )
    suspend fun unsettled(): List<PendingPayment>

    /** The same user intent that is still in flight, if any. */
    @Query(
        "SELECT * FROM pending_payments WHERE amountMinor = :amountMinor AND recipient = :recipient " +
            "AND status IN ('pending', 'needs_reconcile') AND timestamp >= :since " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    suspend fun findInFlight(amountMinor: Long, recipient: String, since: Long): PendingPayment?

    @Query("UPDATE pending_payments SET status = :status, lastError = :lastError WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, lastError: String?)

    @Query("UPDATE pending_payments SET lastError = :lastError WHERE id = :id")
    suspend fun setLastError(id: String, lastError: String?)

    @Query("UPDATE pending_payments SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: String)

    @Query("DELETE FROM pending_payments")
    suspend fun clear()
}
