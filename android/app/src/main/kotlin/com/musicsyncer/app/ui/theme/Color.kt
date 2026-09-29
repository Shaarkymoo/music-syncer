package com.musicsyncer.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Music Sync palette — black + orange (user-approved direction).
// The app is dark-only; every role below is tuned for a near-black canvas with
// a single warm orange accent.
// ---------------------------------------------------------------------------

/** Brand orange — the one loud color in the app. */
val Orange = Color(0xFFFF8A00)

/** Lighter amber-orange, used for secondary accents. */
val OrangeLight = Color(0xFFFFB74D)

/** Deep burnt-orange container tone (primaryContainer / indicator pill). */
val OrangeDark = Color(0xFF4A2A00)

/** Near-black brown, readable as text on top of the orange. */
val OrangeDeep = Color(0xFF1A0F00)

/** App background — true black, not a tinted near-black. */
val Ink = Color(0xFF0A0A0A)

/** Base surface (cards, dialogs, nav bar). */
val Surface = Color(0xFF141414)

/** Raised surface (top app bar, containers, elevated elements). */
val SurfaceRaised = Color(0xFF1C1C1C)

/** Primary text on dark surfaces. */
val TextPrimary = Color(0xFFE8E8E8)

/** Secondary text (captions, meta, unselected nav). */
val TextSecondary = Color(0xFF9A9A9A)

/** M3 dark-theme error red. */
val ErrorRed = Color(0xFFFFB4AB)

/**
 * Full Material 3 dark color scheme derived from the orange seed.
 * Container / inverse / outline roles are hand-tuned tones of the same hue so
 * the whole scheme reads as one warm, low-light system rather than a default
 * purple-tinted M3 palette.
 */
val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = OrangeDeep,
    primaryContainer = OrangeDark,
    onPrimaryContainer = Color(0xFFFFDDB5),
    inversePrimary = Color(0xFF8A4A00),
    secondary = OrangeLight,
    onSecondary = Color(0xFF3A2600),
    secondaryContainer = Color(0xFF4A3200),
    onSecondaryContainer = Color(0xFFFFDDB5),
    tertiary = Color(0xFFE0B98C),
    onTertiary = Color(0xFF3E2A12),
    tertiaryContainer = Color(0xFF573F22),
    onTertiaryContainer = Color(0xFFFFDDB5),
    error = ErrorRed,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Orange,
    inverseSurface = TextPrimary,
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFF3A3A3A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A2A2A),
    surfaceContainer = SurfaceRaised,
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    surfaceContainerLow = Color(0xFF171717),
    surfaceContainerLowest = Ink,
    surfaceDim = Ink,
)