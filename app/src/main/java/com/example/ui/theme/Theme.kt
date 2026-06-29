package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = SparkAccent,
    onPrimary = Color.White,
    secondary = SparkPurpleLight,
    onSecondary = Color.White,
    tertiary = SparkPurpleLight,
    background = SparkDarkBg,
    onBackground = SparkTextColor,
    surface = SparkCardBg,
    onSurface = SparkTextColor,
    error = RedError,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
