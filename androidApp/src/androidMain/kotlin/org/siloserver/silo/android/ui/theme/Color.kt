package org.siloserver.silo.android.ui.theme

import androidx.compose.ui.graphics.Color

// Plezy OLED Dark palette — mirrors iosApp/iosApp/Theme/Colors.swift exactly.
// Pure-black backgrounds, EDEDED primary text, white-at-opacity for everything else.

val SiloBackground = Color(0xFF000000)

/**
 * The signed-in page canvas. iOS paints `ContinuumPageBackdrop` (#111111) under
 * every signed-in phone surface instead of pure black (silo-apple PR #222);
 * `SiloBackground` stays black for the ink and player roles that mirror
 * `continuumBackground`. Settings keeps `SiloSettingsBackground`.
 */
val SiloPageBackground = Color(0xFF111111)
val SiloSurface = Color(0xFF0A0A0A)
val SiloSurfaceVariant = Color(0xFF0E0F12)
val SiloSurfaceElevated = Color(0xFF15171C)
val SiloPrimary = Color(0xFFEDEDED)
val SiloOnSurface = Color(0xFFEDEDED)
val SiloSecondaryText = Color(0xFFEDEDED).copy(alpha = 0.60f)
val SiloDisabled = Color(0xFF4B5563)

val SiloOutline = Color.White.copy(alpha = 0.12f)
val SiloDivider = Color.White.copy(alpha = 0.12f)
val SiloOverlay = Color.Black.copy(alpha = 0.60f)

// Restrained Android substitute for iOS Liquid Glass. These shared washes keep
// every migrated circle/capsule in one light material family without a live
// blur. They remain inexpensive on
// Android (no live backdrop blur), while allowing the title-derived surface
// underneath to tint them instead of reading as flat white plastic.
val SiloOpaqueControl = Color.White.copy(alpha = 0.58f)
val SiloOpaqueControlSelected = Color.White.copy(alpha = 0.70f)
val SiloOpaqueControlSelectionOverlay = Color.White.copy(alpha = 0.18f)
// PR #212 PhoneLabeledAction values. These read as the same adaptive material
// family as the Home tab bar over a dark detail surface without becoming the
// pale, nearly solid circles shown in the Android comparison screenshot.
val SiloDetailActionControl = Color.White.copy(alpha = 0.10f)
val SiloDetailActionControlActive = Color.White.copy(alpha = 0.30f)
val SiloOnOpaqueControl = Color(0xFF17171A)
val SiloOnOpaqueControlMuted = Color(0xFF4F4F55)
val SiloOpaqueControlBorder = Color.White.copy(alpha = 0.46f)

// --- Grouped-surface palette (Silo web client parity) ---
//
// The OLED values above are the app's chrome: pure black grounds with
// white-at-opacity on top. That reads well over artwork and badly over a long
// form, where a card has to separate from its page without a border and a
// hairline has to be visible without glowing. These are the web client's
// settings values, and they fill M3's `surfaceContainer*` ladder — which
// `darkColorScheme` otherwise leaves on its purple-tinted baseline.

/** Lifted page ground for form-shaped screens. Web `--background`. */
val SiloSettingsBackground = Color(0xFF141417)

/** Grouped card surface. Web `--card`. */
val SiloSurfaceContainer = Color(0xFF1C1C20)

val SiloSurfaceContainerLowest = Color(0xFF060608)
val SiloSurfaceContainerLow = Color(0xFF141417)
val SiloSurfaceContainerHigh = Color(0xFF24242A)
val SiloSurfaceContainerHighest = Color(0xFF2C2C33)
val SiloSurfaceDim = Color(0xFF000000)
val SiloSurfaceBright = Color(0xFF2C2C33)

/** Hairline between rows and around inset controls. Web `--border`. */
val SiloBorder = Color(0xFF28282E)

/** Secondary copy on a grouped surface. Web `--muted-foreground`. */
val SiloMutedText = Color(0xFF9696A0)

/** Primary copy on a grouped surface. Web `--foreground`. */
val SiloForeground = Color(0xFFE8E8EC)

/**
 * The single destructive tint.
 *
 * Settings previously carried two: `colorScheme.error` (0xFFB00020, an M3
 * *light*-theme red that fails contrast on a dark card) for Sign Out and Reset
 * Playback Overrides, and an iOS system red (0xFFFF453A) for Remove All
 * Downloads. Web `--destructive`.
 */
val SiloDestructive = Color(0xFFEF4444)

val SiloError = Color(0xFFB00020)
val SiloOnError = Color(0xFFFFFFFF)
val SiloSuccess = Color(0xFF34C759) // SwiftUI .green on dark
val SiloWarning = Color(0xFFFFC107)

// Backwards-compatible aliases preserved so downstream code keeps compiling.
// They now resolve to the iOS Plezy values rather than the legacy cream palette.
val SiloWhite = SiloOnSurface
val SiloWhiteSoft = SiloOnSurface
val SiloWhiteMuted = SiloSecondaryText
val SiloBlack = SiloBackground

val DarkBackground = SiloPageBackground
val DarkSurface = SiloSurface
val DarkSurfaceVariant = SiloSurfaceVariant
val DarkSurfaceHigh = SiloSurfaceElevated

val DarkOnBackground = SiloOnSurface
val DarkOnSurface = SiloOnSurface
val DarkOnSurfaceVariant = SiloSecondaryText
val DarkOnPrimary = SiloBackground

val ErrorRed = SiloError
val ErrorRedDark = Color(0xFF93000A)
val ErrorRedContainer = Color(0xFF8C1D18)
val OnErrorContainer = Color(0xFFFFDAD6)
val SuccessGreen = SiloSuccess
val WarningAmber = SiloWarning

val DarkOutline = SiloOutline
val DarkOutlineVariant = SiloOutline

val DarkInverseSurface = SiloOnSurface
val DarkInverseOnSurface = SiloBackground
val DarkInversePrimary = SiloBackground

val Scrim = SiloOverlay
