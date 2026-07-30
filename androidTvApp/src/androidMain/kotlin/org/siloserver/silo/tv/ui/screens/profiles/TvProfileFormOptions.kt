package org.siloserver.silo.tv.ui.screens.profiles

/**
 * Form option lists for TV profile create/edit. These mirror the phone app's
 * constants in `CreateProfileViewModel.kt` (androidApp); duplicated here because
 * the androidApp package is not on the TV classpath.
 */

/** Available content ratings, ordered from least to most restrictive. */
val TV_CONTENT_RATINGS = listOf("G", "PG", "PG-13", "R", "NC-17")

/** Available quality preferences. "Auto" maps to a null preference. */
val TV_QUALITY_OPTIONS = listOf("Auto", "4K", "1080p", "720p", "480p")

/** Available subtitle modes. "Off" maps to a null mode. */
val TV_SUBTITLE_MODES = listOf("Off", "Default", "Always", "Forced Only")

/** Display label for a stored subtitle-mode value (e.g. "forced_only" -> "Forced Only"). */
fun subtitleModeLabel(stored: String?): String =
    stored?.replace("_", " ")?.split(" ")
        ?.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
        ?: "Off"
