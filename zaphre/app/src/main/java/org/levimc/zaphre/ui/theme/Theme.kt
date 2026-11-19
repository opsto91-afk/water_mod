package org.levimc.zaphre.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ZaphBg = Color(0xFF0D0F14)
val ZaphSurface = Color(0xFF151922)
val ZaphSurfaceHigh = Color(0xFF1D2330)
val ZaphBorder = Color(0xFF283042)
val ZaphPrimary = Color(0xFF4F8CFF)
val ZaphAccent = Color(0xFF38BDF8)
val ZaphGreen = Color(0xFF22C55E)
val ZaphAmber = Color(0xFFF59E0B)
val ZaphRed = Color(0xFFEF4444)
val ZaphText = Color(0xFFECEFF4)
val ZaphSubtext = Color(0xFF94A3B8)
val ZaphCodeBg = Color(0xFF090B10)

private val ZaphDarkColors = darkColorScheme(
    primary = ZaphPrimary,
    onPrimary = Color(0xFF071120),
    primaryContainer = Color(0xFF1A2E52),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = ZaphAccent,
    onSecondary = Color(0xFF061622),
    secondaryContainer = Color(0xFF162C3D),
    onSecondaryContainer = Color(0xFFC9ECFF),
    tertiary = ZaphGreen,
    background = ZaphBg,
    onBackground = ZaphText,
    surface = ZaphSurface,
    onSurface = ZaphText,
    surfaceVariant = ZaphSurfaceHigh,
    onSurfaceVariant = ZaphSubtext,
    outline = ZaphBorder,
    error = ZaphRed
)

@Composable
fun ZaphReTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZaphDarkColors,
        content = content
    )
}
