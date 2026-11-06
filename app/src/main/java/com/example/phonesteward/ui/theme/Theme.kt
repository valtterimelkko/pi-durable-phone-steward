package com.example.phonesteward.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The 8 dp grid. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Screen edge padding. */
    val screen = 16.dp

    /** Smallest comfortable touch target. */
    val touch = 48.dp
}

/** Colours the Material scheme has no slot for: success and warning. */
@Immutable
data class SemanticColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val cardBorder: Color,
)

private val LightSemantic = SemanticColors(
    success = StewardPalette.Success,
    successContainer = StewardPalette.SuccessContainer,
    onSuccessContainer = StewardPalette.OnSuccessContainer,
    warning = StewardPalette.Warning,
    warningContainer = StewardPalette.WarningContainer,
    onWarningContainer = StewardPalette.OnWarningContainer,
    cardBorder = StewardPalette.OutlineDefault,
)

private val DarkSemantic = SemanticColors(
    success = StewardPalette.SuccessDark,
    successContainer = StewardPalette.SuccessContainerDark,
    onSuccessContainer = StewardPalette.OnSuccessContainerDark,
    warning = StewardPalette.WarningDark,
    warningContainer = StewardPalette.WarningContainerDark,
    onWarningContainer = StewardPalette.OnWarningContainerDark,
    cardBorder = StewardPalette.OutlineDefaultDark,
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

object StewardTheme {
    val semantic: SemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = StewardPalette.Blue,
    onPrimary = Color.White,
    primaryContainer = StewardPalette.BlueContainer,
    onPrimaryContainer = StewardPalette.OnBlueContainer,
    secondary = StewardPalette.ContentSecondary,
    onSecondary = Color.White,
    secondaryContainer = StewardPalette.SurfaceSunken,
    onSecondaryContainer = StewardPalette.ContentPrimary,
    tertiary = StewardPalette.Success,
    background = StewardPalette.Canvas,
    onBackground = StewardPalette.ContentPrimary,
    surface = StewardPalette.Canvas,
    onSurface = StewardPalette.ContentPrimary,
    surfaceVariant = StewardPalette.SurfaceSunken,
    onSurfaceVariant = StewardPalette.ContentSecondary,
    surfaceContainerLowest = StewardPalette.Surface,
    surfaceContainerLow = StewardPalette.Surface,
    surfaceContainer = StewardPalette.SurfaceSubtle,
    surfaceContainerHigh = StewardPalette.SurfaceSunken,
    surfaceContainerHighest = StewardPalette.SurfaceSunken,
    outline = StewardPalette.ContentMuted,
    outlineVariant = StewardPalette.OutlineDefault,
    error = StewardPalette.Error,
    onError = Color.White,
    errorContainer = StewardPalette.ErrorContainer,
    onErrorContainer = StewardPalette.OnErrorContainer,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = StewardPalette.BlueDark,
    onPrimary = Color(0xFF0B1B3A),
    primaryContainer = StewardPalette.BlueContainerDark,
    onPrimaryContainer = StewardPalette.OnBlueContainerDark,
    secondary = StewardPalette.ContentSecondaryDark,
    onSecondary = StewardPalette.CanvasDark,
    secondaryContainer = StewardPalette.SurfaceSubtleDark,
    onSecondaryContainer = StewardPalette.ContentPrimaryDark,
    tertiary = StewardPalette.SuccessDark,
    background = StewardPalette.CanvasDark,
    onBackground = StewardPalette.ContentPrimaryDark,
    surface = StewardPalette.CanvasDark,
    onSurface = StewardPalette.ContentPrimaryDark,
    surfaceVariant = StewardPalette.SurfaceSubtleDark,
    onSurfaceVariant = StewardPalette.ContentSecondaryDark,
    surfaceContainerLowest = StewardPalette.SurfaceSunkenDark,
    surfaceContainerLow = StewardPalette.SurfaceDark,
    surfaceContainer = StewardPalette.SurfaceDark,
    surfaceContainerHigh = StewardPalette.SurfaceSubtleDark,
    surfaceContainerHighest = StewardPalette.SurfaceSubtleDark,
    outline = StewardPalette.ContentMutedDark,
    outlineVariant = StewardPalette.OutlineDefaultDark,
    error = StewardPalette.ErrorDark,
    onError = Color(0xFF450A0A),
    errorContainer = StewardPalette.ErrorContainerDark,
    onErrorContainer = StewardPalette.OnErrorContainerDark,
)

private val StewardShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Material 3, light and dark, from the Pi Web UI palette. Follows the system setting. */
@Composable
fun PhoneStewardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSemanticColors provides (if (darkTheme) DarkSemantic else LightSemantic)) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = StewardTypography,
            shapes = StewardShapes,
            content = content,
        )
    }
}
