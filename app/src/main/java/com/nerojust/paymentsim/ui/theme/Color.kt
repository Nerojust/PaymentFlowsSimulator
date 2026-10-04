package com.nerojust.paymentsim.ui.theme

import androidx.compose.ui.graphics.Color

/** The deck's palette. These accents stay the same in light and dark; only surfaces change. */
object DeckColors {
    val Failure = Color(0xFFB91C1C)       // FAILED, duplicate banner
    val Fix = Color(0xFF0F766E)           // SUCCESS, confirmed
    val Warning = Color(0xFFB45309)       // RETRYING, warnings
    val Neutral = Color(0xFF374151)       // Pending / Confirming / neutral
    val Link = Color(0xFF1E40AF)          // links, secondary accent
    val LogBackground = Color(0xFF0F172A)
    val LogText = Color(0xFFE2E8F0)
    val LogComment = Color(0xFF94A3B8)

    // Lighter tints of the same hues: the deck accents are too dark to read on the log background.
    val LogNet = Color(0xFFFBBF24)
    val LogServer = Color(0xFF5EEAD4)
    val LogWorker = Color(0xFF93C5FD)
}
