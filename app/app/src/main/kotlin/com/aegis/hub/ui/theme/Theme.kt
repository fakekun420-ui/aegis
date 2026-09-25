package com.aegis.hub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Claude-inspired warm dark palette:
// Neutral warm charcoal/espresso tones, subtle borders, terracotta clay accent.
val ClaudeBackground = Color(0xFF181816)
val ClaudeSurface = Color(0xFF22211F)
val ClaudeSurfaceVariant = Color(0xFF2B2926)
val ClaudeSurfaceContainer = Color(0xFF252422)
val ClaudeSurfaceContainerHigh = Color(0xFF2F2D2A)

val ClaudePrimary = Color(0xFFD97757) // Signature terracotta clay
val ClaudeOnPrimary = Color(0xFFFFFFFF)
val ClaudePrimaryContainer = Color(0xFF3E271F)
val ClaudeOnPrimaryContainer = Color(0xFFFFDBCF)

val ClaudeSecondary = Color(0xFFC78565)
val ClaudeSecondaryContainer = Color(0xFF38312C)
val ClaudeOnSecondaryContainer = Color(0xFFEADBCE)

val ClaudeTertiary = Color(0xFF7CA69E) // Warm sage / teal
val ClaudeTertiaryContainer = Color(0xFF253533)
val ClaudeOnTertiaryContainer = Color(0xFFD0E2E0)

val ClaudeOnSurface = Color(0xFFEDEDEC) // Crisp warm off-white
val ClaudeOnSurfaceVariant = Color(0xFFA5A39F) // Warm muted stone grey
val ClaudeOutline = Color(0xFF403D39) // Crisp subtle border
val ClaudeOutlineVariant = Color(0xFF2E2C29) // Very subtle divider / card edge

val ClaudeError = Color(0xFFE57373)
val ClaudeErrorContainer = Color(0xFF3E1F24)
val ClaudeOnErrorContainer = Color(0xFFFFB4AB)

// Provider brand badges (refined Claude harmony)
val OpenCodeBadgeBg = Color(0xFF352B25)
val OpenCodeBadgeFg = Color(0xFFE4A485)
val OpenCodeBadgeBorder = Color(0xFF523E34)

val AgyBadgeBg = Color(0xFF26263B)
val AgyBadgeFg = Color(0xFFA6A8F8)
val AgyBadgeBorder = Color(0xFF3E3E5E)

val ClaudeDarkColorScheme = darkColorScheme(
    primary = ClaudePrimary,
    onPrimary = ClaudeOnPrimary,
    primaryContainer = ClaudePrimaryContainer,
    onPrimaryContainer = ClaudeOnPrimaryContainer,
    secondary = ClaudeSecondary,
    onSecondaryContainer = ClaudeOnSecondaryContainer,
    secondaryContainer = ClaudeSecondaryContainer,
    tertiary = ClaudeTertiary,
    tertiaryContainer = ClaudeTertiaryContainer,
    onTertiaryContainer = ClaudeOnTertiaryContainer,
    background = ClaudeBackground,
    onBackground = ClaudeOnSurface,
    surface = ClaudeSurface,
    onSurface = ClaudeOnSurface,
    surfaceVariant = ClaudeSurfaceVariant,
    onSurfaceVariant = ClaudeOnSurfaceVariant,
    surfaceContainer = ClaudeSurfaceContainer,
    surfaceContainerHigh = ClaudeSurfaceContainerHigh,
    outline = ClaudeOutline,
    outlineVariant = ClaudeOutlineVariant,
    error = ClaudeError,
    errorContainer = ClaudeErrorContainer,
    onErrorContainer = ClaudeOnErrorContainer
)

@Composable
fun OpenCodeCompanionTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ClaudeDarkColorScheme,
        content = content
    )
}
