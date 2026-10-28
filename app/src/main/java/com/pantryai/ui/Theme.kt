package com.pantryai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF2F5D3A), onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE6CF), onPrimaryContainer = Color(0xFF0E2614),
    secondary = Color(0xFF8A5A2B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6DFC3), onSecondaryContainer = Color(0xFF2E1800),
    tertiary = Color(0xFFB3541E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0CC), onTertiaryContainer = Color(0xFF3A1300),
    background = Color(0xFFFBF7F0), onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFBF7F0), onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFEDE5D8), onSurfaceVariant = Color(0xFF4D463C),
    surfaceContainer = Color(0xFFF4EEE4), surfaceContainerHigh = Color(0xFFEEE7DC),
    surfaceContainerLow = Color(0xFFF8F3EB), surfaceContainerHighest = Color(0xFFE8E0D4),
    outline = Color(0xFF8B8275), outlineVariant = Color(0xFFD7CEC0),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DD3A0), onPrimary = Color(0xFF0E3818),
    primaryContainer = Color(0xFF244A2D), onPrimaryContainer = Color(0xFFCFE6CF),
    secondary = Color(0xFFE6BE91), onSecondary = Color(0xFF462A05),
    secondaryContainer = Color(0xFF5F4019), onSecondaryContainer = Color(0xFFF6DFC3),
    tertiary = Color(0xFFFFB68F), onTertiary = Color(0xFF552100),
    tertiaryContainer = Color(0xFF773512), onTertiaryContainer = Color(0xFFFFE0CC),
    background = Color(0xFF16130F), onBackground = Color(0xFFEAE1D6),
    surface = Color(0xFF16130F), onSurface = Color(0xFFEAE1D6),
    surfaceVariant = Color(0xFF4D463C), onSurfaceVariant = Color(0xFFD1C5B4),
    surfaceContainer = Color(0xFF231F1A), surfaceContainerHigh = Color(0xFF2D2924),
    surfaceContainerLow = Color(0xFF1E1A16), surfaceContainerHighest = Color(0xFF38332D),
    outline = Color(0xFF9A8F80), outlineVariant = Color(0xFF4D463C),
)

private val Serif = FontFamily.Serif

private val Type = Typography().let { t ->
    t.copy(
        headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    )
}

@Composable
fun PantryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
