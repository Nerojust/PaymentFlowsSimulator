package com.nerojust.paymentsim.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nerojust.paymentsim.client.db.PendingPaymentEntity
import com.nerojust.paymentsim.di.ServiceLocator
import com.nerojust.paymentsim.log.LogEvent
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.log.shortKey
import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.PaymentStatus
import com.nerojust.paymentsim.model.formatMinor
import com.nerojust.paymentsim.model.label
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.network.NetworkSettings
import com.nerojust.paymentsim.server.db.LedgerEntryEntity
import com.nerojust.paymentsim.ui.theme.DeckColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SimulatorScreen(viewModel: SimulatorViewModel = viewModel { SimulatorViewModel(ServiceLocator.instance) }) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val paymentState by viewModel.paymentState.collectAsStateWithLifecycle()
    val naiveLoading by viewModel.naiveLoading.collectAsStateWithLifecycle()
    val naiveError by viewModel.naiveError.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val ledger by viewModel.ledger.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()

    var amountText by rememberSaveable { mutableStateOf("1250.00") }
    var recipient by rememberSaveable { mutableStateOf("Ada Lovelace") }

    val payEnabled = if (settings.safeClient) {
        // The state machine, not a boolean, decides whether a second tap is possible.
        paymentState !is PaymentState.Pending &&
            paymentState !is PaymentState.Confirming &&
            paymentState !is PaymentState.Retrying
    } else {
        !naiveLoading
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ControlsCard(
                amountText = amountText,
                onAmountChange = { amountText = it },
                recipient = recipient,
                onRecipientChange = { recipient = it },
                settings = settings,
                payEnabled = payEnabled,
                onScenario = {
                    amountText = it.amount
                    viewModel.selectScenario(it)
                },
                viewModel = viewModel,
            )
            if (settings.safeClient) {
                StateBadge(paymentState.label, stateColor(paymentState))
            } else {
                StateBadge(
                    label = when {
                        naiveLoading -> "NAIVE: isLoading = true"
                        naiveError != null -> "NAIVE: $naiveError"
                        else -> "NAIVE: isLoading = false"
                    },
                    color = if (naiveError != null && !naiveLoading) DeckColors.Failure else DeckColors.Neutral,
                )
            }
            Verdict(ledger)
            BoxWithConstraints {
                if (maxWidth >= 600.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ClientQueuePanel(queue, Modifier.weight(1f))
                        ServerLedgerPanel(ledger, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ClientQueuePanel(queue, Modifier.fillMaxWidth())
                        ServerLedgerPanel(ledger, Modifier.fillMaxWidth())
                    }
                }
            }
            EventLogPanel(events)
        }
    }

    queue.firstOrNull { it.status == PaymentStatus.AWAITING_USER_CONFIRMATION }?.let { stale ->
        StalePaymentDialog(
            payment = stale,
            onSend = { viewModel.sendStalePayment(stale.id) },
            onCancel = { viewModel.cancelStalePayment(stale.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ControlsCard(
    amountText: String,
    onAmountChange: (String) -> Unit,
    recipient: String,
    onRecipientChange: (String) -> Unit,
    settings: NetworkSettings,
    payEnabled: Boolean,
    onScenario: (Scenario) -> Unit,
    viewModel: SimulatorViewModel,
) {
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    val scenario = scenarios.firstOrNull { it.number == settings.scenario }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pick a scenario", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scenarios.forEach {
                    FilterChip(
                        selected = it == scenario,
                        onClick = { onScenario(it) },
                        label = { Text("${it.number} · ${it.title}") },
                    )
                }
            }
            if (scenario != null) Text(scenario.hint, fontWeight = FontWeight.Medium)
            // The whole setup in one line, so the audience never has to read the toggles.
            Text(
                text = (if (settings.safeClient) "Safe client" else "Naive client") +
                    " · server idempotency ${if (settings.serverIdempotencyEnabled) "ON" else "OFF"}" +
                    " · network: ${settings.mode.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountChange,
                    label = { Text("Amount (₦)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = recipient,
                    onValueChange = onRecipientChange,
                    label = { Text("Recipient") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Button(
                onClick = { viewModel.pay(amountText, recipient) },
                enabled = payEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Pay", style = MaterialTheme.typography.titleMedium) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.mode != NetworkMode.ONLINE) {
                    Button(onClick = { viewModel.setMode(NetworkMode.ONLINE) }) { Text("Go online") }
                }
                if (settings.scenario == KEY_REUSE_SCENARIO || advancedOpen) {
                    OutlinedButton(onClick = viewModel::debugReuseKeyWithDifferentAmount) {
                        Text("Reuse key, different amount")
                    }
                }
                OutlinedButton(
                    onClick = viewModel::killApp,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckColors.Failure),
                ) { Text("Kill app") }
                OutlinedButton(onClick = viewModel::resetAll) { Text("Reset all data") }
                TextButton(onClick = { advancedOpen = !advancedOpen }) {
                    Text(if (advancedOpen) "Advanced ▴" else "Advanced ▾")
                }
            }

            if (advancedOpen) AdvancedControls(settings, viewModel)
        }
    }
}

/** The raw toggles, for going off script. The scenario chips set all of these in one tap. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AdvancedControls(settings: NetworkSettings, viewModel: SimulatorViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !settings.safeClient,
            onClick = { viewModel.setSafeClient(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Naive") }
        SegmentedButton(
            selected = settings.safeClient,
            onClick = { viewModel.setSafeClient(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Safe") }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Server idempotency", Modifier.weight(1f))
        Switch(
            checked = settings.serverIdempotencyEnabled,
            onCheckedChange = viewModel::setServerIdempotency,
        )
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NetworkMode.entries.forEach { mode ->
            FilterChip(
                selected = settings.mode == mode,
                onClick = { viewModel.setMode(mode) },
                label = { Text(mode.label) },
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = settings.dropOnce, onCheckedChange = viewModel::setDropOnce)
        Text("Lose only one response, then back to Online")
    }
}

private const val KEY_REUSE_SCENARIO = 7

private val NetworkMode.label: String
    get() = when (this) {
        NetworkMode.ONLINE -> "Online"
        NetworkMode.OFFLINE -> "Offline"
        NetworkMode.DROP_AFTER_PROCESSING -> "Lose response"
        NetworkMode.SLOW -> "Slow"
        NetworkMode.SERVER_ERROR -> "Server error"
    }

/** The one line the audience should read: did the customer pay once? */
@Composable
private fun Verdict(ledger: List<LedgerEntryEntity>) {
    val duplicate = remember(ledger) { duplicateChargeIds(ledger).isNotEmpty() }
    val (text, color) = when {
        duplicate -> "Duplicate charge detected: the customer paid more than once" to DeckColors.Failure
        ledger.isEmpty() -> "No charges yet" to DeckColors.Neutral
        else -> "${ledger.size} charge${if (ledger.size == 1) "" else "s"}, no duplicates" to DeckColors.Fix
    }
    Text(
        text = text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

private fun stateColor(state: PaymentState): Color = when (state) {
    PaymentState.Success -> DeckColors.Fix
    is PaymentState.Failed -> DeckColors.Failure
    PaymentState.Retrying -> DeckColors.Warning
    PaymentState.Idle, PaymentState.Pending, PaymentState.Confirming -> DeckColors.Neutral
}

@Composable
private fun StateBadge(label: String, color: Color) {
    val animatedColor by animateColorAsState(color, label = "stateColor")
    Surface(color = animatedColor, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        AnimatedContent(targetState = label, label = "stateLabel") { text ->
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// ponytail: plain Columns, not LazyColumns. Demo lists hold a handful of rows; go lazy if that changes.
@Composable
private fun ClientQueuePanel(queue: List<PendingPaymentEntity>, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Client queue (pending_payments)", style = MaterialTheme.typography.titleMedium)
            if (queue.isEmpty()) Text("empty", color = DeckColors.LogComment)
            queue.forEach { payment ->
                Text(
                    text = "${payment.id.shortKey()}  ${formatMinor(payment.amountMinor)}  " +
                        "${payment.status}  retries ${payment.retryCount}",
                    color = statusColor(payment.status),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    PaymentStatus.CONFIRMED -> DeckColors.Fix
    PaymentStatus.FAILED -> DeckColors.Failure
    PaymentStatus.NEEDS_RECONCILE, PaymentStatus.AWAITING_USER_CONFIRMATION -> DeckColors.Warning
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun ServerLedgerPanel(ledger: List<LedgerEntryEntity>, modifier: Modifier = Modifier) {
    val duplicates = remember(ledger) { duplicateChargeIds(ledger) }
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Server ledger (charges: ${ledger.size})", style = MaterialTheme.typography.titleMedium)
            if (ledger.isEmpty()) Text("no charges", color = DeckColors.LogComment)
            ledger.forEach { entry ->
                val isDuplicate = entry.chargeId in duplicates
                Text(
                    text = "#${entry.chargeId}  ${entry.idempotencyKey?.shortKey() ?: "no key"}  " +
                        formatMinor(entry.amountMinor),
                    color = if (isDuplicate) DeckColors.Failure else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isDuplicate) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun EventLogPanel(events: List<LogEvent>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Surface(color = DeckColors.LogBackground, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("// event log, newest first", color = DeckColors.LogComment, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            events.forEach { event ->
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = DeckColors.LogComment)) {
                            append(timeFormat.format(Date(event.timeMillis)))
                        }
                        withStyle(SpanStyle(color = sourceColor(event.source), fontWeight = FontWeight.Bold)) {
                            append(" ${event.source}: ")
                        }
                        append(event.message)
                    },
                    color = DeckColors.LogText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun sourceColor(source: LogSource): Color = when (source) {
    LogSource.CLIENT -> DeckColors.LogText
    LogSource.NET -> DeckColors.LogNet
    LogSource.SERVER -> DeckColors.LogServer
    LogSource.WORKER -> DeckColors.LogWorker
}

@Composable
private fun StalePaymentDialog(payment: PendingPaymentEntity, onSend: () -> Unit, onCancel: () -> Unit) {
    val minutesAgo = (System.currentTimeMillis() - payment.timestamp) / 60_000
    AlertDialog(
        onDismissRequest = {}, // a decision is required: send or cancel
        title = { Text("Unfinished payment") },
        text = {
            Text(
                "This payment from $minutesAgo min ago never completed " +
                    "(${formatMinor(payment.amountMinor)} to ${payment.recipient}). Send it now or cancel?",
            )
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
