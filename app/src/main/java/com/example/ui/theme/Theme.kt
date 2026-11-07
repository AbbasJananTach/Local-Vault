package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class VaultThemeMode(val displayName: String) {
    MIDNIGHT_VIOLET("Midnight Violet"),
    CYBER_EMERALD("Cyber Emerald"),
    CRIMSON_STEALTH("Crimson Stealth");

    companion object {
        fun fromName(name: String?): VaultThemeMode {
            return entries.find { it.name == name } ?: MIDNIGHT_VIOLET
        }
    }
}

private val MidnightVioletScheme = darkColorScheme(
    primary = VioletPrimary,
    onPrimary = Color.White,
    primaryContainer = VioletPrimaryContainer,
    onPrimaryContainer = VioletTertiary,
    secondary = CyanSecondary,
    onSecondary = ObsidianBackground,
    secondaryContainer = Color(0xFF0E3E42),
    onSecondaryContainer = CyanSecondary,
    tertiary = VioletTertiary,
    background = ObsidianBackground,
    onBackground = TextPrimaryLight,
    surface = MidnightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = ElevatedCardSurface,
    onSurfaceVariant = TextSecondaryMuted,
    surfaceContainerHigh = HighContainerSurface,
    outline = SubtleBorderColor,
    error = DangerRed,
    onError = Color.White
)

private val CyberEmeraldScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = ObsidianBackground,
    primaryContainer = EmeraldPrimaryContainer,
    onPrimaryContainer = MintTertiary,
    secondary = CyberBlueSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF0E2F56),
    onSecondaryContainer = MintTertiary,
    tertiary = MintTertiary,
    background = ObsidianBackground,
    onBackground = TextPrimaryLight,
    surface = MidnightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = ElevatedCardSurface,
    onSurfaceVariant = TextSecondaryMuted,
    surfaceContainerHigh = HighContainerSurface,
    outline = SubtleBorderColor,
    error = DangerRed,
    onError = Color.White
)

private val CrimsonStealthScheme = darkColorScheme(
    primary = CrimsonPrimary,
    onPrimary = Color.White,
    primaryContainer = CrimsonPrimaryContainer,
    onPrimaryContainer = RoseTertiary,
    secondary = AmberSecondary,
    onSecondary = ObsidianBackground,
    secondaryContainer = Color(0xFF4A3208),
    onSecondaryContainer = AmberSecondary,
    tertiary = RoseTertiary,
    background = ObsidianBackground,
    onBackground = TextPrimaryLight,
    surface = MidnightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = ElevatedCardSurface,
    onSurfaceVariant = TextSecondaryMuted,
    surfaceContainerHigh = HighContainerSurface,
    outline = SubtleBorderColor,
    error = DangerRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    themeMode: VaultThemeMode = VaultThemeMode.MIDNIGHT_VIOLET,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeMode) {
        VaultThemeMode.MIDNIGHT_VIOLET -> MidnightVioletScheme
        VaultThemeMode.CYBER_EMERALD -> CyberEmeraldScheme
        VaultThemeMode.CRIMSON_STEALTH -> CrimsonStealthScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
