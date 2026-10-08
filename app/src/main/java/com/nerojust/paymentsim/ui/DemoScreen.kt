package com.nerojust.paymentsim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import com.nerojust.paymentsim.client.db.PendingPayment
import com.nerojust.paymentsim.di.AppDependencies
import com.nerojust.paymentsim.model.PendingPaymentStatus
import com.nerojust.paymentsim.model.formatMinor
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.network.DemoSettings

private enum class Tab(val title: String, val glyph: String) {
    PAY("Pay", "₦"),
    PAYMENTS("Payments", "≡"),
    LOG("What happened", "…"),
}

/** The shell: three tabs, a Menu for the presenter's tools, and the "old payment" question. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(viewModel: DemoViewModel = viewModel { DemoViewModel(AppDependencies.instance) }) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val paymentState by viewModel.paymentState.collectAsStateWithLifecycle()
    val naiveLoading by viewModel.naiveLoading.collectAsStateWithLifecycle()
    val naiveError by viewModel.naiveError.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val ledger by viewModel.ledger.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.PAY) }
    // After a crash the picked demo comes back from disk, so its amount has to come back with it.
    var amountText by rememberSaveable {
        mutableStateOf(scenarios.firstOrNull { it.number == settings.scenario }?.amount ?: "1250.00")
    }
    var recipient by rememberSaveable { mutableStateOf("Pizza place") }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val waiting = queue.count { it.status != PendingPaymentStatus.CONFIRMED && it.status != PendingPaymentStatus.FAILED }

    // One note at a time: a newer one replaces the one on screen.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) }
    }
    LaunchedEffect(Unit) { snackbar.showSnackbar(viewModel.awaitRestartNote(), duration = SnackbarDuration.Long) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment demo") },
                actions = {
                    TextButton(onClick = { menuOpen = true }) { Text("Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Crash the app (pretend)") },
                            onClick = viewModel::killApp,
                        )
                        DropdownMenuItem(
                            text = { Text("Clear everything") },
                            onClick = {
                                menuOpen = false
                                viewModel.resetAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                menuOpen = false
                                settingsOpen = true
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = {
                            BadgedBox(
                                badge = {
                                    // Pushed out to the corner so it does not sit on top of the glyph.
                                    if (it == Tab.PAYMENTS && waiting > 0) {
                                        Badge(Modifier.offset(x = 10.dp, y = (-4).dp)) { Text("$waiting") }
                                    }
                                },
                            ) {
                                // Icon-sized box: the text glyph alone is too small for the badge to anchor on.
                                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                    Text(it.glyph, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                            }
                        },
                        label = { Text(it.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding).padding(horizontal = 12.dp)
        when (tab) {
            Tab.PAY -> PayTab(
                amountText = amountText,
                onAmountChange = { amountText = it },
                recipient = recipient,
                onRecipientChange = { recipient = it },
                settings = settings,
                paymentState = paymentState,
                naiveLoading = naiveLoading,
                naiveError = naiveError,
                waiting = waiting,
                chargedTwice = remember(ledger) { duplicateChargeIds(ledger).isNotEmpty() },
                onScenario = {
                    amountText = it.amount
                    viewModel.selectScenario(it)
                },
                onOpenPayments = { tab = Tab.PAYMENTS },
                viewModel = viewModel,
                modifier = modifier,
            )
            Tab.PAYMENTS -> PaymentsTab(queue, ledger, modifier)
            Tab.LOG -> LogTab(events, modifier.padding(bottom = 12.dp))
        }
        // On Pay the note goes at the top: at the bottom it sits on the Crash button and the status for the
        // very seconds they are needed. On Payments it is the other way round, the verdict line is at the top.
        val noteAt = if (tab == Tab.PAYMENTS) Alignment.BottomCenter else Alignment.TopCenter
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = noteAt) {
            SnackbarHost(snackbar)
        }
    }

    if (settingsOpen) SettingsDialog(settings, viewModel, onClose = { settingsOpen = false })

    queue.firstOrNull { it.status == PendingPaymentStatus.AWAITING_USER_CONFIRMATION }?.let { stale ->
        StalePaymentDialog(
            payment = stale,
            onSend = { viewModel.sendStalePayment(stale.id) },
            onCancel = { viewModel.cancelStalePayment(stale.id) },
        )
    }
}

/** The raw toggles, for going off script. The demo chips set all of these in one tap. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsDialog(settings: DemoSettings, viewModel: DemoViewModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Which app sends the payment?")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !settings.useSafeClient,
                        onClick = { viewModel.setUseSafeClient(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Careless") }
                    SegmentedButton(
                        selected = settings.useSafeClient,
                        onClick = { viewModel.setUseSafeClient(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Careful") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("The bank remembers keys (idempotency)", Modifier.weight(1f))
                    Switch(
                        checked = settings.serverIdempotencyEnabled,
                        onCheckedChange = viewModel::setServerIdempotency,
                    )
                }

                Text("Internet")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.mode == mode,
                            onClick = { viewModel.setMode(mode) },
                            label = { Text(mode.shortLabel) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = settings.dropOnce, onCheckedChange = viewModel::setDropOnce)
                    Text("Lose only one answer, then go back to working")
                }

                TextButton(onClick = viewModel::debugReuseKeyWithDifferentAmount) {
                    Text("Reuse the last key with a different amount")
                }
            }
        },
    )
}

private val NetworkMode.shortLabel: String
    get() = when (this) {
        NetworkMode.ONLINE -> "Working"
        NetworkMode.OFFLINE -> "No internet"
        NetworkMode.DROP_AFTER_PROCESSING -> "Lose the answer"
        NetworkMode.SLOW -> "Slow"
        NetworkMode.SERVER_ERROR -> "Bank broken"
    }

@Composable
private fun StalePaymentDialog(payment: PendingPayment, onSend: () -> Unit, onCancel: () -> Unit) {
    val minutesAgo = (System.currentTimeMillis() - payment.timestamp) / 60_000
    AlertDialog(
        onDismissRequest = {}, // a decision is required: send or cancel
        title = { Text("Forgotten payment found") },
        text = {
            Text(
                "You started this payment $minutesAgo min ago and it never finished: " +
                    "${formatMinor(payment.amountMinor)} to ${payment.recipient}. Send it now, or cancel it?",
            )
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send it") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel it") } },
    )
}
