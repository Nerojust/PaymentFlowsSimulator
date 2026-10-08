package com.nerojust.paymentsim.ui

import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nerojust.paymentsim.client.db.PendingPayment
import com.nerojust.paymentsim.di.AppDependencies
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.model.PendingPaymentStatus
import com.nerojust.paymentsim.model.formatMinor
import com.nerojust.paymentsim.model.parseAmountMinor
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.network.DemoSettings
import com.nerojust.paymentsim.server.db.Charge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DemoViewModel(private val deps: AppDependencies) : ViewModel() {

    val settings: StateFlow<DemoSettings> = deps.network.settings
    val paymentState = deps.safeClient.state
    val naiveLoading = deps.naiveClient.isLoading
    val naiveError = deps.naiveClient.lastError
    val events = deps.log.events

    /** Short notes for the snackbar: where a payment is going right now, and how it ended. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Client panel: the offline-first queue, live from Room. */
    val queue: StateFlow<List<PendingPayment>> = deps.clientDb.pendingPaymentDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Server panel: this is the audience looking inside the server, not the client reading server.db.
     * The payment clients only ever reach the server through Retrofit.
     */
    val ledger: StateFlow<List<Charge>> = deps.serverDb.serverDao().observeLedger()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun pay(amountText: String, recipient: String) {
        val amountMinor = parseAmountMinor(amountText)
        if (amountMinor == null || recipient.isBlank()) {
            deps.log.log(LogSource.CLIENT, "Type an amount and who to pay")
            return
        }
        deps.scope.launch {
            if (settings.value.useSafeClient) {
                _messages.tryEmit(
                    if (deps.safeClient.isAlreadySaved(amountMinor, recipient.trim())) {
                        "This payment is already saved. It will not be sent twice."
                    } else {
                        "Saved on this phone. " + whatHappensNext(settings.value.mode, deps.safeClient.unsettledCount())
                    },
                )
                val id = deps.safeClient.initiatePayment(amountMinor, recipient.trim())
                deps.clientDb.pendingPaymentDao().find(id)?.let { howItEnded(it) }?.let(_messages::tryEmit)
            } else {
                _messages.tryEmit("Sending it to the bank. The Careless app does not save it on this phone.")
                deps.naiveClient.pay(amountMinor, recipient.trim())
                _messages.tryEmit(
                    if (deps.naiveClient.lastError.value == null) {
                        "The Careless app says: paid."
                    } else {
                        "The Careless app got no answer, so it thinks the payment failed. It cannot check."
                    },
                )
            }
        }
    }

    private fun whatHappensNext(mode: NetworkMode, alreadyWaiting: Int): String = when {
        alreadyWaiting > 0 -> "$alreadyWaiting other payment${if (alreadyWaiting == 1) " is" else "s are"} ahead of it. It will be sent after."
        mode == NetworkMode.OFFLINE -> "No internet, so it will keep trying and send it when you are back online."
        mode == NetworkMode.SLOW -> "Internet is slow, so the answer will take a few seconds."
        else -> "Sending it to the bank now."
    }

    private fun howItEnded(payment: PendingPayment): String? = when (payment.status) {
        PendingPaymentStatus.CONFIRMED -> "Paid: ${formatMinor(payment.amountMinor)} to ${payment.recipient}."
        PendingPaymentStatus.FAILED -> "Not paid: ${plainReason(payment.lastError.orEmpty())}."
        PendingPaymentStatus.NEEDS_RECONCILE ->
            "Still no answer. The payment is safe on this phone and will be checked again in the background."
        else -> null // still saved, or the old-payment question is on screen
    }

    fun setMode(mode: NetworkMode) {
        deps.network.update { it.copy(mode = mode) }
        // Stands in for a connectivity callback: a network change is a good moment to settle the queue.
        if (mode != NetworkMode.OFFLINE) {
            deps.scope.launch {
                val unfinished = deps.safeClient.unsettledCount()
                if (unfinished > 0) _messages.tryEmit("Checking $unfinished unfinished payment${if (unfinished == 1) "" else "s"} with the bank.")
                deps.safeClient.reconcilePendingPayments()
            }
        }
    }

    /** Starts a scenario from a clean slate: wipes both databases, then applies its toggles. */
    fun selectScenario(scenario: Scenario) {
        viewModelScope.launch {
            deps.resetAll()
            deps.network.update { scenario.settings }
            deps.log.log(LogSource.CLIENT, "Demo ${scenario.number}: ${scenario.title}")
        }
    }

    // Changing a toggle by hand leaves the scripted scenario, so its caption is dropped.
    fun setUseSafeClient(safe: Boolean) = deps.network.update { it.copy(useSafeClient = safe, scenario = 0) }

    fun setServerIdempotency(enabled: Boolean) =
        deps.network.update { it.copy(serverIdempotencyEnabled = enabled, scenario = 0) }

    fun setDropOnce(dropOnce: Boolean) = deps.network.update { it.copy(dropOnce = dropOnce, scenario = 0) }

    /** Dialog "Send": the user confirmed the stale payment, so settle it (status check first, same key). */
    fun sendStalePayment(id: String) {
        deps.scope.launch { deps.safeClient.reconcilePayment(id, userConfirmed = true) }
    }

    fun cancelStalePayment(id: String) {
        deps.scope.launch { deps.safeClient.cancelPayment(id) }
    }

    fun debugReuseKeyWithDifferentAmount() {
        deps.scope.launch { deps.safeClient.debugReuseKeyWithDifferentAmount() }
    }

    fun resetAll() {
        viewModelScope.launch { deps.resetAll() }
    }

    /** Simulates a crash. Both databases, the network settings and the event log are on disk. */
    fun killApp() {
        deps.log.log(LogSource.CLIENT, "———— The app crashed here ————")
        deps.network.update { it.copy(crashedOnPurpose = true) }
        Process.killProcess(Process.myPid())
    }

    /** Waits for the note the app start leaves after a crash, and hands it out once. */
    suspend fun awaitRestartNote(): String =
        deps.restartNote.filterNotNull().first().also { deps.restartNote.value = null }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Charge ids of ledger rows that share amount and recipient with another row within 60 seconds. */
fun duplicateChargeIds(ledger: List<Charge>, windowMs: Long = 60_000): Set<Long> =
    ledger.groupBy { it.amountMinor to it.recipient }.values.flatMap { sameIntent ->
        sameIntent.sortedBy { it.createdAt }
            .zipWithNext()
            .filter { (earlier, later) -> later.createdAt - earlier.createdAt <= windowMs }
            .flatMap { (earlier, later) -> listOf(earlier.chargeId, later.chargeId) }
    }.toSet()
