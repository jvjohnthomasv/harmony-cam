package com.harmonycam.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Hc {
    val Ink = Color(0xFF0B0B14)
    val Panel = Color(0xE6141420)
    val PanelSoft = Color(0x99101018)
    val Line = Color(0x33FFFFFF)
    val Text = Color(0xFFF4F2FA)
    val TextDim = Color(0xB3F4F2FA)
    val Accent = Color(0xFF22E6FF)
    val Accent2 = Color(0xFFFF4FD8)
    val Record = Color(0xFFFF3B4E)
    val Warn = Color(0xFFFFC94A)
    val Ok = Color(0xFF6CFFA8)
    val Voice = listOf(Color(0xFF22E6FF), Color(0xFFFF4FD8), Color(0xFF9E73FF), Color(0xFF80FF73))
}

@Composable
fun HarmonyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hc.Accent,
            secondary = Hc.Accent2,
            background = Hc.Ink,
            surface = Color(0xFF15151F),
            surfaceVariant = Color(0xFF1E1E2B),
            onPrimary = Color.Black,
            onSurface = Hc.Text,
            error = Hc.Record,
        ),
        typography = Typography(
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
            bodyMedium = TextStyle(fontSize = 15.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.3.sp),
        ),
        content = content
    )
}
