package com.zeeai.companion.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class LuxuryAccentPreset(
    val label: String,
    val primaryHex: Color,
    val secondaryHex: Color,
    val glowHex: Color
) {
    OBSIDIAN_CYAN(
        label = "Obsidian Cyan",
        primaryHex = Color(0xFF00E5FF),
        secondaryHex = Color(0xFF6366F1),
        glowHex = Color(0xFF38BDF8)
    ),
    ROYAL_SAPPHIRE(
        label = "Royal Sapphire",
        primaryHex = Color(0xFF818CF8),
        secondaryHex = Color(0xFF4F46E5),
        glowHex = Color(0xFF3B82F6)
    ),
    SOLAR_GOLD(
        label = "24K Solar Gold",
        primaryHex = Color(0xFFFFB300),
        secondaryHex = Color(0xFFD97706),
        glowHex = Color(0xFFFFD54F)
    ),
    IMPERIAL_AMETHYST(
        label = "Imperial Violet",
        primaryHex = Color(0xFFC084FC),
        secondaryHex = Color(0xFF7C3AED),
        glowHex = Color(0xFFBA68C8)
    )
}

/**
 * Deeper, Richer, Expensive Liquid Glass Design Tokens
 */
object LuxuryPalette {
    val ObsidianVoid = Color(0xFF02040A)
    val PureHudBlack = Color(0xFF000000)
    val DeepSapphire = Color(0xFF060B16)
    val SmokedCrystal = Color(0xFF0B1221)
    val ElevatedCrystal = Color(0xFF121B30)

    val ElectricCyan = Color(0xFF00E5FF)
    val SoftCyan = Color(0xFF67E8F9)
    val RoyalIndigo = Color(0xFF6366F1)
    val IndigoLight = Color(0xFF818CF8)
    val DeepIndigo = Color(0xFF4F46E5)
    val SkyAzure = Color(0xFF38BDF8)

    val SolarGold = Color(0xFFFFB300)
    val ChampagneGold = Color(0xFFFFD54F)
    val QuantumViolet = Color(0xFFBA68C8)
    val EmeraldNeon = Color(0xFF00E676)
    val SuccessGreen = Color(0xFF22C55E)
    val CrimsonAlert = Color(0xFFFF1744)
    val DangerRed = Color(0xFFEF4444)
    val WarningAmber = Color(0xFFF59E0B)

    val PlatinumText = Color(0xFFF8FAFC)
    val SilverText = Color(0xFFCBD5E1)
    val MutedSlate = Color(0xFF94A3B8)
    val HairlineOutline = Color(0xFF1E293B)
}

@Composable
fun ZeeLuxuryTheme(
    accent: LuxuryAccentPreset = LuxuryAccentPreset.OBSIDIAN_CYAN,
    isHudMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = accent.primaryHex,
        onPrimary = LuxuryPalette.ObsidianVoid,
        secondary = accent.secondaryHex,
        onSecondary = LuxuryPalette.PlatinumText,
        tertiary = LuxuryPalette.SolarGold,
        background = if (isHudMode) LuxuryPalette.PureHudBlack else LuxuryPalette.ObsidianVoid,
        surface = if (isHudMode) Color(0xFF03060E) else LuxuryPalette.DeepSapphire,
        surfaceVariant = LuxuryPalette.SmokedCrystal,
        onBackground = LuxuryPalette.PlatinumText,
        onSurface = LuxuryPalette.PlatinumText,
        onSurfaceVariant = LuxuryPalette.MutedSlate,
        outline = LuxuryPalette.HairlineOutline,
        error = LuxuryPalette.DangerRed
    )

    val typography = Typography(
        headlineSmall = TextStyle(
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            letterSpacing = (-0.4).sp,
            color = LuxuryPalette.PlatinumText
        ),
        titleMedium = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 0.2.sp,
            color = LuxuryPalette.PlatinumText
        ),
        bodyMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = LuxuryPalette.SilverText
        ),
        bodySmall = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = LuxuryPalette.MutedSlate
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.1.sp
        )
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
