package com.nerojust.paymentsim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = DeckColors.Link,
    onPrimary = Color.White,
    tertiary = DeckColors.Fix,
    error = DeckColors.Failure,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = DeckColors.Link,
    onPrimary = Color.White,
    tertiary = DeckColors.Fix,
    error = DeckColors.Failure,
    onError = Color.White,
)

@Composable
fun PaymentSimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
