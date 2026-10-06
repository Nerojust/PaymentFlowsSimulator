package com.nerojust.paymentsim.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nerojust.paymentsim.model.PaymentState
import com.nerojust.paymentsim.model.label
import com.nerojust.paymentsim.network.NetworkMode
import com.nerojust.paymentsim.network.DemoSettings
import com.nerojust.paymentsim.ui.theme.DeckColors

/** What a customer would see: pick a demo, type an amount, tap Pay, watch the state. */
@Composable
fun PayTab(
    amountText: String,
    onAmountChange: (String) -> Unit,
    recipient: String,
    onRecipientChange: (String) -> Unit,
    settings: DemoSettings,
    paymentState: PaymentState,
    naiveLoading: Boolean,
    naiveError: String?,
    waiting: Int,
    chargedTwice: Boolean,
    onScenario: (Scenario) -> Unit,
    onOpenPayments: () -> Unit,
    viewModel: DemoViewModel,
    modifier: Modifier = Modifier,
) {
    val scenario = scenarios.firstOrNull { it.number == settings.scenario }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick a demo", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    scenarios.forEach {
                        FilterChip(
                            selected = it == scenario,
                            onClick = { onScenario(it) },
                            label = { Text("${it.number} · ${it.title}") },
                        )
                    }
                }
                Text(scenario?.hint ?: "Pick a demo above, or just tap Pay.", fontWeight = FontWeight.Medium)
                scenario?.let { Text("Why it matters: ${it.why}") }
                Text(
                    text = (if (settings.useSafeClient) "Careful app" else "Careless app") + " · " +
                        (if (settings.serverIdempotencyEnabled) "the bank remembers keys" else "the bank forgets keys") +
                        "\nA key is a name tag the phone puts on each payment, so the bank can spot a repeat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        NetworkStrip(settings.mode, onGoOnline = { viewModel.setMode(NetworkMode.ONLINE) })

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
                label = { Text("Pay to") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Button(
            onClick = { viewModel.pay(amountText, recipient) },
            // The Safe app saves every tap, so Pay stays on and payments can stack up.
            // The Naive app only has its loading flag.
            enabled = settings.useSafeClient || !naiveLoading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Pay", style = MaterialTheme.typography.titleMedium) }

        // Only the buttons the picked demo needs. Everything else is under Menu.
        if (settings.scenario in CRASH_SCENARIOS) {
            OutlinedButton(
                onClick = viewModel::killApp,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckColors.Failure),
            ) { Text("Crash the app") }
        }
        if (settings.scenario == KEY_REUSE_SCENARIO) {
            OutlinedButton(onClick = viewModel::debugReuseKeyWithDifferentAmount) {
                Text("Reuse key with a different amount")
            }
        }

        if (settings.useSafeClient) {
            StateBadge(
                title = paymentState.inPlainWords(),
                detail = if (paymentState is PaymentState.Failed) "FAILED" else paymentState.label,
                color = stateColor(paymentState),
            )
        } else {
            StateBadge(
                title = when {
                    naiveLoading -> "Loading…"
                    naiveError != null -> plainReason(naiveError)
                    else -> "Ready"
                },
                detail = "CARELESS APP",
                color = if (naiveError != null && !naiveLoading) DeckColors.Failure else DeckColors.Neutral,
            )
        }

        if (chargedTwice) {
            Banner(CHARGED_TWICE, DeckColors.Failure)
        }
        TextButton(onClick = onOpenPayments) {
            Text(if (waiting > 0) "$waiting not finished yet. See all payments" else "See all payments")
        }
    }
}

private val CRASH_SCENARIOS = setOf(4, 5)
private const val KEY_REUSE_SCENARIO = 7

@Composable
private fun NetworkStrip(mode: NetworkMode, onGoOnline: () -> Unit) {
    val online = mode == NetworkMode.ONLINE
    Surface(
        color = if (online) DeckColors.Fix else DeckColors.Warning,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mode.inPlainWords, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (online) {
                // Same height as the strip with its button, so the screen does not jump.
                Text("", Modifier.padding(vertical = 14.dp))
            } else {
                TextButton(onClick = onGoOnline, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                    Text("Go online")
                }
            }
        }
    }
}

private val NetworkMode.inPlainWords: String
    get() = when (this) {
        NetworkMode.ONLINE -> "Internet is working"
        NetworkMode.OFFLINE -> "No internet"
        NetworkMode.DROP_AFTER_PROCESSING -> "The bank's answer will get lost"
        NetworkMode.SLOW -> "Internet is slow"
        NetworkMode.SERVER_ERROR -> "The bank is broken"
    }

private fun PaymentState.inPlainWords(): String = when (this) {
    PaymentState.Idle -> "Ready for a payment"
    PaymentState.Pending -> "Saved on this phone"
    PaymentState.Confirming -> "Asking the bank…"
    PaymentState.Retrying -> "No answer yet. Trying again"
    PaymentState.Success -> "Paid"
    is PaymentState.Failed -> "Not paid: ${plainReason(error)}"
}

private fun stateColor(state: PaymentState): Color = when (state) {
    PaymentState.Success -> DeckColors.Fix
    is PaymentState.Failed -> DeckColors.Failure
    PaymentState.Retrying -> DeckColors.Warning
    PaymentState.Idle, PaymentState.Pending, PaymentState.Confirming -> DeckColors.Neutral
}

@Composable
private fun StateBadge(title: String, detail: String, color: Color) {
    val animatedColor by animateColorAsState(color, label = "stateColor")
    Surface(color = animatedColor, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        AnimatedContent(targetState = title to detail, label = "stateLabel") { (title, detail) ->
            Column(Modifier.padding(16.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(detail, color = Color.White)
            }
        }
    }
}

/** A full-width coloured line with one short message. */
@Composable
fun Banner(text: String, color: Color) {
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
