package com.nerojust.paymentsim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nerojust.paymentsim.client.db.PendingPayment
import com.nerojust.paymentsim.log.shortKey
import com.nerojust.paymentsim.model.PendingPaymentStatus
import com.nerojust.paymentsim.model.formatMinor
import com.nerojust.paymentsim.server.db.Charge
import com.nerojust.paymentsim.ui.theme.DeckColors

/**
 * Every payment and where it stands: what this phone has saved, and what the server really charged.
 * The two lists are kept apart on purpose. The phone only knows its own side, and the Naive app saves nothing.
 */
// ponytail: plain Column, not LazyColumn. Demo lists hold a handful of rows; go lazy if that changes.
@Composable
fun PaymentsTab(
    queue: List<PendingPayment>,
    ledger: List<Charge>,
    modifier: Modifier = Modifier,
) {
    val duplicates = remember(ledger) { duplicateChargeIds(ledger) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            duplicates.isNotEmpty() -> Banner(CHARGED_TWICE, DeckColors.Failure)
            ledger.isEmpty() -> Banner("No charges yet", DeckColors.Neutral)
            else -> Banner("Good: ${ledger.size} charge${if (ledger.size == 1) "" else "s"}, nothing charged twice", DeckColors.Fix)
        }

        Text("What your phone thinks (${queue.size})", style = MaterialTheme.typography.titleMedium)
        if (queue.isEmpty()) Text("Nothing saved. The Careless app never saves a payment.", color = DeckColors.LogComment)
        queue.forEach { payment ->
            val (label, color) = phoneStatus(payment.status)
            PaymentRow(
                title = "${formatMinor(payment.amountMinor)} to ${payment.recipient}",
                detail = listOfNotNull(
                    "key ${payment.id.shortKey()}",
                    "tried again ${payment.retryCount}×".takeIf { payment.retryCount > 0 },
                    payment.lastError?.takeIf { payment.status == PendingPaymentStatus.FAILED }?.let(::plainReason),
                ).joinToString(" · "),
                pill = label,
                pillColor = color,
            )
        }

        Text(
            "What the bank really took (${ledger.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (ledger.isEmpty()) Text("No money has been taken.", color = DeckColors.LogComment)
        ledger.forEach { charge ->
            val again = charge.chargeId in duplicates
            PaymentRow(
                title = "${formatMinor(charge.amountMinor)} to ${charge.recipient}",
                detail = "charge #${charge.chargeId} · " + (charge.idempotencyKey?.let { "key ${it.shortKey()}" } ?: "no key"),
                pill = if (again) "Charged twice" else "Charged",
                pillColor = if (again) DeckColors.Failure else DeckColors.Fix,
            )
        }
    }
}

private fun phoneStatus(status: String): Pair<String, Color> = when (status) {
    PendingPaymentStatus.PENDING -> "Saved on phone" to DeckColors.Neutral
    PendingPaymentStatus.NEEDS_RECONCILE -> "Will try again" to DeckColors.Warning
    PendingPaymentStatus.AWAITING_USER_CONFIRMATION -> "Needs your OK" to DeckColors.Warning
    PendingPaymentStatus.CONFIRMED -> "Paid" to DeckColors.Fix
    PendingPaymentStatus.FAILED -> "Failed" to DeckColors.Failure
    else -> status to DeckColors.Neutral
}

const val CHARGED_TWICE = "Problem: you were charged more than once for one payment"

/** Server reasons in plain words. Anything else is already written for people. */
fun plainReason(reason: String): String = when {
    reason == "insufficient_funds" -> "not enough money"
    reason.startsWith("HTTP") -> "the bank had a problem"
    else -> reason
}

@Composable
private fun PaymentRow(title: String, detail: String, pill: String, pillColor: Color) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = pill,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(pillColor, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
