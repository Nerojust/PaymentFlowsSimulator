package com.nerojust.paymentsim.server.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** A separate "server.db" so the server's memory survives client "crashes". The client never opens it. */
@Database(
    entities = [RememberedPayment::class, Charge::class],
    version = 1,
    exportSchema = false,
)
abstract class ServerDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
}
