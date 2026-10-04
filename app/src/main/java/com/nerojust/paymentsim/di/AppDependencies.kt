package com.nerojust.paymentsim.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.work.WorkManager
import com.nerojust.paymentsim.client.NaivePaymentClient
import com.nerojust.paymentsim.client.SafePaymentClient
import com.nerojust.paymentsim.client.api.PaymentApi
import com.nerojust.paymentsim.client.db.ClientDatabase
import com.nerojust.paymentsim.client.work.BackgroundRetryWorker
import com.nerojust.paymentsim.log.EventLog
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.network.DemoSettings
import com.nerojust.paymentsim.network.FakeNetwork
import com.nerojust.paymentsim.server.FakeServerInterceptor
import com.nerojust.paymentsim.server.FakePaymentServer
import com.nerojust.paymentsim.server.db.ServerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Manual wiring, no Hilt, so the whole object graph fits on one slide.
 * Tests pass in-memory databases and a recording [enqueueRetryWorker] through the default arguments.
 */
class AppDependencies(
    context: Context,
    val clientDb: ClientDatabase =
        Room.databaseBuilder(context, ClientDatabase::class.java, "client.db").build(),
    val serverDb: ServerDatabase =
        Room.databaseBuilder(context, ServerDatabase::class.java, "server.db").build(),
    prefs: SharedPreferences = context.getSharedPreferences("network_simulator", Context.MODE_PRIVATE),
    enqueueRetryWorker: (paymentId: String) -> Unit = { BackgroundRetryWorker.enqueue(context, it) },
    private val cancelAllWork: () -> Unit = { WorkManager.getInstance(context).cancelAllWork() },
    clock: () -> Long = System::currentTimeMillis,
) {
    /** Payments outlive the Activity, so they run here and not in viewModelScope. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val log = EventLog()
    val network = FakeNetwork(prefs, log)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ---- server side ----
    val server = FakePaymentServer(serverDb, network, log, json, clock = clock)

    // ---- client side: reaches the server only through Retrofit -> interceptor -> network ----
    val api: PaymentApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(OkHttpClient.Builder().addInterceptor(FakeServerInterceptor(server, network, json)).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PaymentApi::class.java)

    val safeClient = SafePaymentClient(clientDb.pendingPaymentDao(), api, log, enqueueRetryWorker, clock)
    val naiveClient = NaivePaymentClient(api, log)

    /** Must not be called from [scope], it cancels everything running there. */
    suspend fun resetAll() {
        // An interceptor already inside the fake server finishes on its OkHttp thread, like a real backend would.
        scope.coroutineContext.cancelChildren()
        cancelAllWork()
        safeClient.reset()
        naiveClient.reset()
        server.reset()
        network.update { DemoSettings() }
        log.clear()
        log.log(LogSource.CLIENT, "Everything cleared")
    }

    companion object {
        const val BASE_URL = "https://fake.payments.local/"

        @Volatile
        lateinit var instance: AppDependencies
    }
}
