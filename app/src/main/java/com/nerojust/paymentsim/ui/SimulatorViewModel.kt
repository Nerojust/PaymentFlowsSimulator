package com.nerojust.paymentsim.ui

import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nerojust.paymentsim.client.db.PendingPaymentEntity
import com.nerojust.paymentsim.di.ServiceLocator
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.model.parseAmountMinor
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.network.NetworkSettings
import com.nerojust.paymentsim.server.db.LedgerEntryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SimulatorViewModel(private val locator: ServiceLocator) : ViewModel() {

    val settings: StateFlow<NetworkSettings> = locator.simulator.settings
    val paymentState = locator.repository.state
    val naiveLoading = locator.naiveClient.isLoading
    val naiveError = locator.naiveClient.lastError
    val events = locator.log.events

    /** Client panel: the offline-first queue, live from Room. */
    val queue: StateFlow<List<PendingPaymentEntity>> = locator.clientDb.pendingPaymentDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Server panel: this is the audience looking inside the server, not the client reading server.db.
     * The payment clients only ever reach the server through Retrofit.
     */
    val ledger: StateFlow<List<LedgerEntryEntity>> = locator.serverDb.serverDao().observeLedger()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun pay(amountText: String, recipient: String) {
        val amountMinor = parseAmountMinor(amountText)
        if (amountMinor == null || recipient.isBlank()) {
            locator.log.log(LogSource.CLIENT, "enter a positive amount (max 2 decimals) and a recipient")
            return
        }
        locator.scope.launch {
            if (settings.value.safeClient) {
                locator.repository.initiatePayment(amountMinor, recipient.trim())
            } else {
                locator.naiveClient.pay(amountMinor, recipient.trim())
            }
        }
    }

    fun setMode(mode: NetworkMode) {
        locator.simulator.update { it.copy(mode = mode) }
        // Stands in for a connectivity callback: a network change is a good moment to settle the queue.
        if (mode != NetworkMode.OFFLINE) locator.scope.launch { locator.repository.reconcilePendingPayments() }
    }

    /** Starts a scenario from a clean slate: wipes both databases, then applies its toggles. */
    fun selectScenario(scenario: Scenario) {
        viewModelScope.launch {
            locator.resetAll()
            locator.simulator.update { scenario.settings }
            locator.log.log(LogSource.CLIENT, "Scenario ${scenario.number}: ${scenario.title}")
        }
    }

    // Changing a toggle by hand leaves the scripted scenario, so its caption is dropped.
    fun setSafeClient(safe: Boolean) = locator.simulator.update { it.copy(safeClient = safe, scenario = 0) }

    fun setServerIdempotency(enabled: Boolean) =
        locator.simulator.update { it.copy(serverIdempotencyEnabled = enabled, scenario = 0) }

    fun setDropOnce(dropOnce: Boolean) = locator.simulator.update { it.copy(dropOnce = dropOnce, scenario = 0) }

    /** Dialog "Send": the user confirmed the stale payment, so settle it (status check first, same key). */
    fun sendStalePayment(id: String) {
        locator.scope.launch { locator.repository.reconcilePayment(id, userConfirmed = true) }
    }

    fun cancelStalePayment(id: String) {
        locator.scope.launch { locator.repository.cancelPayment(id) }
    }

    fun debugReuseKeyWithDifferentAmount() {
        locator.scope.launch { locator.repository.debugReuseKeyWithDifferentAmount() }
    }

    fun resetAll() {
        viewModelScope.launch { locator.resetAll() }
    }

    /** Simulates a crash. Both databases and the simulator settings are on disk, the event log is not. */
    fun killApp() {
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Charge ids of ledger rows that share amount and recipient with another row within 60 seconds. */
fun duplicateChargeIds(ledger: List<LedgerEntryEntity>, windowMs: Long = 60_000): Set<Long> =
    ledger.groupBy { it.amountMinor to it.recipient }.values.flatMap { sameIntent ->
        sameIntent.sortedBy { it.createdAt }
            .zipWithNext()
            .filter { (earlier, later) -> later.createdAt - earlier.createdAt <= windowMs }
            .flatMap { (earlier, later) -> listOf(earlier.chargeId, later.chargeId) }
    }.toSet()
