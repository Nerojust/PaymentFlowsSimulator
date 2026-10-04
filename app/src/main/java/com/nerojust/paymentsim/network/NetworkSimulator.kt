package com.nerojust.paymentsim.network

import android.content.SharedPreferences
import androidx.core.content.edit
import com.nerojust.paymentsim.log.EventLog
import com.nerojust.paymentsim.log.LogSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.io.IOException

data class NetworkSettings(
    val mode: NetworkMode = NetworkMode.ONLINE,
    val serverIdempotencyEnabled: Boolean = true,
    val processingDelayMs: Long = 1500,
    val slowLatencyMs: Long = 4000,
    val dropOnce: Boolean = true,
    val safeClient: Boolean = true,
    val scenario: Int = 0, // the preset picked on screen, 0 = none
)

/** Persisted demo settings plus the failure injection the interceptor applies around the fake server. */
class NetworkSimulator(private val prefs: SharedPreferences, private val log: EventLog) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<NetworkSettings> = _settings.asStateFlow()

    fun isSimulatedOffline(): Boolean = settings.value.mode == NetworkMode.OFFLINE

    fun update(transform: (NetworkSettings) -> NetworkSettings) {
        val updated = _settings.updateAndGet(transform)
        // commit, not apply: "Kill app" can follow a toggle within milliseconds and the setting must survive it.
        prefs.edit(commit = true) {
            putString(KEY_MODE, updated.mode.name)
            putBoolean(KEY_IDEMPOTENCY, updated.serverIdempotencyEnabled)
            putLong(KEY_PROCESSING_DELAY, updated.processingDelayMs)
            putLong(KEY_SLOW_LATENCY, updated.slowLatencyMs)
            putBoolean(KEY_DROP_ONCE, updated.dropOnce)
            putBoolean(KEY_SAFE_CLIENT, updated.safeClient)
        }
    }

    /**
     * Runs before the server sees the request.
     * Throws when offline, returns false when the request must be answered with a 500 without processing.
     */
    fun beforeServer(what: String): Boolean {
        val current = settings.value
        when (current.mode) {
            NetworkMode.OFFLINE -> {
                log.log(LogSource.NET, "offline: $what never reached the server")
                throw IOException("Simulated offline")
            }
            NetworkMode.SERVER_ERROR -> {
                log.log(LogSource.NET, "500 for $what, server did not process it")
                return false
            }
            NetworkMode.SLOW -> {
                log.log(LogSource.NET, "slow network: holding $what for ${current.slowLatencyMs}ms")
                Thread.sleep(current.slowLatencyMs)
            }
            NetworkMode.ONLINE, NetworkMode.DROP_AFTER_PROCESSING -> Unit
        }
        return true
    }

    /** Runs after the server fully processed the request, before the response is returned. */
    fun afterServer(what: String, code: Int) {
        val current = settings.value
        if (current.mode != NetworkMode.DROP_AFTER_PROCESSING) {
            log.log(LogSource.NET, "delivered $what -> $code")
            return
        }
        if (current.dropOnce) update { it.copy(mode = NetworkMode.ONLINE) }
        log.log(LogSource.NET, "dropped response after server processed $what (it answered $code)")
        throw IOException("Simulated: response lost")
    }

    private fun load() = NetworkSettings().let { defaults ->
        NetworkSettings(
            mode = prefs.getString(KEY_MODE, null)
                ?.let { name -> NetworkMode.entries.firstOrNull { it.name == name } }
                ?: defaults.mode,
            serverIdempotencyEnabled = prefs.getBoolean(KEY_IDEMPOTENCY, defaults.serverIdempotencyEnabled),
            processingDelayMs = prefs.getLong(KEY_PROCESSING_DELAY, defaults.processingDelayMs),
            slowLatencyMs = prefs.getLong(KEY_SLOW_LATENCY, defaults.slowLatencyMs),
            dropOnce = prefs.getBoolean(KEY_DROP_ONCE, defaults.dropOnce),
            safeClient = prefs.getBoolean(KEY_SAFE_CLIENT, defaults.safeClient),
            scenario = prefs.getInt(KEY_SCENARIO, defaults.scenario),
        )
    }

    private companion object {
        const val KEY_MODE = "networkMode"
        const val KEY_IDEMPOTENCY = "serverIdempotencyEnabled"
        const val KEY_PROCESSING_DELAY = "processingDelayMs"
        const val KEY_SLOW_LATENCY = "slowLatencyMs"
        const val KEY_DROP_ONCE = "dropOnce"
        const val KEY_SAFE_CLIENT = "safeClient"
        const val KEY_SCENARIO = "scenario"
    }
}
