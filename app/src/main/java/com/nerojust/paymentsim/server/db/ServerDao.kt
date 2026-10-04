package com.nerojust.paymentsim.server.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    /** INSERT ... ON CONFLICT DO NOTHING. Returns -1 when the key was already claimed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(row: RememberedPayment): Long

    @Query("SELECT * FROM processed_payments WHERE idempotencyKey = :key")
    suspend fun find(key: String): RememberedPayment?

    @Query("UPDATE processed_payments SET status = :status, resultJson = :resultJson WHERE idempotencyKey = :key")
    suspend fun complete(key: String, status: String, resultJson: String)

    @Query("DELETE FROM processed_payments WHERE status = 'processing'")
    suspend fun releaseProcessingClaims(): Int

    @Insert
    suspend fun insertLedger(entry: Charge): Long

    @Query("SELECT * FROM ledger ORDER BY chargeId DESC")
    fun observeLedger(): Flow<List<Charge>>

    @Query("SELECT * FROM ledger ORDER BY chargeId DESC")
    suspend fun ledger(): List<Charge>

    @Query("DELETE FROM processed_payments")
    suspend fun clearProcessed()

    @Query("DELETE FROM ledger")
    suspend fun clearLedger()
}
