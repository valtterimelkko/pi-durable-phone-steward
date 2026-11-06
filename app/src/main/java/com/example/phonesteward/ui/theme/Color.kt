package com.example.phonesteward.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette taken from the Pi Web UI client (`client/tailwind.config.js`), which
 * itself follows the Claude Code web UI: a warm paper canvas, charcoal text,
 * hairline borders and one blue accent (`pi.primary` #2563EB).
 */
object StewardPalette {
    // Light
    val Canvas = Color(0xFFFCFCFB)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSubtle = Color(0xFFFAFAF9)
    val SurfaceSunken = Color(0xFFF3F3F1)
    val ContentPrimary = Color(0xFF0B0B0B)
    val ContentSecondary = Color(0xFF52514E)
    val ContentMuted = Color(0xFF898781)
    val OutlineDefault = Color(0xFFDEDEDC) // rgba(11,11,11,.12) over the canvas
    val OutlineSubtle = Color(0xFFEBEBE9) // rgba(11,11,11,.08) over the canvas
    val Blue = Color(0xFF2563EB)
    val BlueHover = Color(0xFF1D4ED8)
    val BlueLight = Color(0xFFEFF6FF)
    val BlueContainer = Color(0xFFDBEAFE)
    val OnBlueContainer = Color(0xFF1E3A8A)

    // Dark
    val CanvasDark = Color(0xFF18181B)
    val SurfaceDark = Color(0xFF27272A)
    val SurfaceSubtleDark = Color(0xFF202023)
    val SurfaceSunkenDark = Color(0xFF111113)
    val ContentPrimaryDark = Color(0xFFF4F4F5)
    val ContentSecondaryDark = Color(0xFFA1A1AA)
    val ContentMutedDark = Color(0xFF71717A)
    val OutlineDefaultDark = Color(0xFF3B3B3F) // rgba(255,255,255,.12) over the dark surface
    val OutlineSubtleDark = Color(0xFF303033)
    val BlueDark = Color(0xFF60A5FA)
    val BlueContainerDark = Color(0xFF1E3A8A)
    val OnBlueContainerDark = Color(0xFFDBEAFE)

    // Semantic
    val Success = Color(0xFF16A34A)
    val SuccessContainer = Color(0xFFDCFCE7)
    val OnSuccessContainer = Color(0xFF14532D)
    val SuccessDark = Color(0xFF4ADE80)
    val SuccessContainerDark = Color(0xFF14391F)
    val OnSuccessContainerDark = Color(0xFFBBF7D0)
    val Warning = Color(0xFFD97706)
    val WarningContainer = Color(0xFFFEF3C7)
    val OnWarningContainer = Color(0xFF78350F)
    val WarningDark = Color(0xFFFBBF24)
    val WarningContainerDark = Color(0xFF3F2E0B)
    val OnWarningContainerDark = Color(0xFFFDE68A)
    val Error = Color(0xFFDC2626)
    val ErrorContainer = Color(0xFFFEE2E2)
    val OnErrorContainer = Color(0xFF7F1D1D)
    val ErrorDark = Color(0xFFF87171)
    val ErrorContainerDark = Color(0xFF451A1A)
    val OnErrorContainerDark = Color(0xFFFECACA)
}
