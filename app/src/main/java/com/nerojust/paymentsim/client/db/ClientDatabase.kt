package com.nerojust.paymentsim.client.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** "client.db": the offline-first queue. */
@Database(entities = [PendingPayment::class], version = 1, exportSchema = false)
abstract class ClientDatabase : RoomDatabase() {
    abstract fun pendingPaymentDao(): PendingPaymentDao
}
