package com.mototelemetry.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MotoColors = darkColorScheme(
    primary = Color(0xFF4DE1ED), onPrimary = Color(0xFF00363B),
    secondary = Color(0xFFA9C7D3), background = Color(0xFF090F18),
    surface = Color(0xFF101B29), surfaceVariant = Color(0xFF1B2A3A),
    onBackground = Color(0xFFECF3FA), onSurface = Color(0xFFECF3FA),
)
@Composable
fun MotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MotoColors, content = content)
}
