package com.fishmate.ai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * FishMate's visual identity: deep water, bright surface, amber for warnings.
 *
 * Designed for a phone held at arm's length on a bright shore, so contrast is
 * pushed well past Material's defaults and type is a step larger than usual.
 * Dark is the primary scheme, not an afterthought — most fishing that matters
 * happens at dawn and dusk, and a white screen at 5 a.m. ruins night vision.
 */

// Deep water palette
private val DeepSea = Color(0xFF0B1D26)
private val DeepSeaElevated = Color(0xFF122C38)
private val DeepSeaCard = Color(0xFF17384A)
private val Shallow = Color(0xFF4FD1C5)
private val ShallowDim = Color(0xFF2C9A92)
private val Sand = Color(0xFFF4EFE6)
private val Amber = Color(0xFFF6C66B)
private val Coral = Color(0xFFE2725B)
private val Foam = Color(0xFFDCEEF2)

private val DarkScheme = darkColorScheme(
    primary = Shallow,
    onPrimary = DeepSea,
    primaryContainer = ShallowDim,
    onPrimaryContainer = Color.White,
    secondary = Amber,
    onSecondary = DeepSea,
    secondaryContainer = Color(0xFF4A3A18),
    onSecondaryContainer = Amber,
    tertiary = Foam,
    onTertiary = DeepSea,
    background = DeepSea,
    onBackground = Foam,
    surface = DeepSeaElevated,
    onSurface = Foam,
    surfaceVariant = DeepSeaCard,
    onSurfaceVariant = Color(0xFFB9D4DC),
    surfaceContainer = DeepSeaElevated,
    surfaceContainerHigh = DeepSeaCard,
    error = Coral,
    onError = Color.White,
    errorContainer = Color(0xFF5A241B),
    onErrorContainer = Color(0xFFFFD9D2),
    outline = Color(0xFF4A6B77),
    outlineVariant = Color(0xFF2A4A57),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF07626A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2EDE8),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF7A5A12),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3A8),
    onSecondaryContainer = Color(0xFF2A1C00),
    background = Sand,
    onBackground = Color(0xFF14262C),
    surface = Color.White,
    onSurface = Color(0xFF14262C),
    surfaceVariant = Color(0xFFE2EDF0),
    onSurfaceVariant = Color(0xFF3F565D),
    error = Color(0xFFA8301C),
    onError = Color.White,
    outline = Color(0xFF6F878E),
)

/**
 * Type scale deliberately larger than Material defaults.
 *
 * Readability with wet hands, salt spray and low sun matters more than fitting
 * more on screen.
 */
private val FishMateTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.3.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Minimum touch target for gloved, cold hands. */
val GloveFriendlyTouchTarget = 56.dp

@Composable
fun FishMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FishMateTypography,
        content = content,
    )
}
