package com.nerojust.paymentsim.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nerojust.paymentsim.log.LogEvent
import com.nerojust.paymentsim.log.LogSource
import com.nerojust.paymentsim.ui.theme.DeckColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything that happened, step by step: who said what (phone, internet, server, background job). */
@Composable
fun LogTab(events: List<LogEvent>, modifier: Modifier = Modifier) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val scroll = rememberScrollState()
    // Like a terminal: oldest at the top, and the view follows the newest line at the bottom.
    // Keyed on maxValue because that only grows once the new line has been laid out.
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    Surface(color = DeckColors.LogBackground, shape = RoundedCornerShape(8.dp), modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(12.dp)) {
            if (events.isEmpty()) {
                Text("Nothing has happened yet.", color = DeckColors.LogComment, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            events.asReversed().forEach { event ->
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = DeckColors.LogComment)) {
                            append(timeFormat.format(Date(event.timeMillis)))
                        }
                        withStyle(SpanStyle(color = sourceColor(event.source), fontWeight = FontWeight.Bold)) {
                            append(" ${event.source.inPlainWords}: ")
                        }
                        append(event.message)
                    },
                    color = DeckColors.LogText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private val LogSource.inPlainWords: String
    get() = when (this) {
        LogSource.CLIENT -> "PHONE"
        LogSource.NET -> "INTERNET"
        LogSource.SERVER -> "SERVER"
        LogSource.WORKER -> "BACKGROUND"
    }

private fun sourceColor(source: LogSource): Color = when (source) {
    LogSource.CLIENT -> DeckColors.LogText
    LogSource.NET -> DeckColors.LogNet
    LogSource.SERVER -> DeckColors.LogServer
    LogSource.WORKER -> DeckColors.LogWorker
}
