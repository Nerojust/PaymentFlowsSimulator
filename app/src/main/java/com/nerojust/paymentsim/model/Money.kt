package com.nerojust.paymentsim.model

import java.util.Locale

// Money is always Long minor units (kobo). These two functions are the only place it meets text.

/** "1250" or "1250.50" -> 125000 / 125050. Null for anything that is not a positive amount with at most 2 decimals. */
fun parseAmountMinor(text: String): Long? {
    val amount = text.trim().toBigDecimalOrNull() ?: return null
    if (amount.signum() <= 0 || amount.scale() > 2) return null
    return runCatching { amount.movePointRight(2).longValueExact() }.getOrNull()
}

/** 125000 -> "₦1,250.00". Display only. */
fun formatMinor(amountMinor: Long): String =
    String.format(Locale.US, "₦%,d.%02d", amountMinor / 100, amountMinor % 100)
