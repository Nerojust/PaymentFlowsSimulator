package com.nerojust.paymentsim

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nerojust.paymentsim.client.api.dto.PaymentRequest
import com.nerojust.paymentsim.client.db.ClientDatabase
import com.nerojust.paymentsim.client.db.PendingPayment
import com.nerojust.paymentsim.di.AppDependencies
import com.nerojust.paymentsim.network.DemoSettings
import com.nerojust.paymentsim.server.db.ServerDatabase

/**
 * The real object graph (Retrofit -> interceptor -> network -> fake server) on in-memory databases,
 * with a controllable clock and a recording stand-in for WorkManager.
 */
class TestHarness {
    val context: Context = ApplicationProvider.getApplicationContext()

    var now = 1_700_000_000_000L
    val enqueuedWorkers = mutableListOf<String>()

    val clientDb = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
    val serverDb = Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java).build()
    private val prefs = context.getSharedPreferences("test_network_simulator", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    var deps = newDependencies()
        private set

    val safeClient get() = deps.safeClient
    val api get() = deps.api

    init {
        configure { it.copy(processingDelayMs = 0, slowLatencyMs = 0) }
    }

    fun configure(transform: (DemoSettings) -> DemoSettings) = deps.network.update(transform)

    /** What "Kill app" + relaunch does: a new graph over the same databases and settings. */
    fun relaunch(): AppDependencies = newDependencies().also { deps = it }

    suspend fun payment(id: String): PendingPayment = clientDb.pendingPaymentDao().find(id)!!

    suspend fun ledger() = api.ledger()

    fun logMessages(): List<String> = deps.log.events.value.reversed().map { it.message }

    fun close() {
        clientDb.close()
        serverDb.close()
    }

    private fun newDependencies() = AppDependencies(
        context = context,
        clientDb = clientDb,
        serverDb = serverDb,
        prefs = prefs,
        enqueueRetryWorker = { enqueuedWorkers += it },
        cancelAllWork = {},
        clock = { now },
    )

    companion object {
        const val AMOUNT = 125_000L
        const val RECIPIENT = "Ada Lovelace"
        val REQUEST = PaymentRequest(AMOUNT, "NGN", RECIPIENT)
    }
}
