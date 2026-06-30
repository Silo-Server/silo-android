package com.continuum.app.tv.data.preferences

/**
 * Presentation-layer enums for the TV settings UI. These wrap the wire
 * values used by the server's player-settings API in TV-friendly labels.
 *
 * They previously lived alongside the legacy `TvPreferences` DataStore
 * class; that class was removed once all live persistence moved to
 * `PlayerSettingsStore` (server-flushed device settings) and
 * `TvLibrarySelectionStore` (per-profile UI continuity). The enums
 * remained because they're the canonical TV-side representation used by
 * the settings ViewModel and screen.
 */

/**
 * Playback quality ladder. Mirrors what the server's transcode pipeline accepts
 * and what tvOS exposes in Settings. "Auto" defers to the server.
 */
enum class PlaybackQuality(val label: String, val wireValue: String) {
    Auto("Auto", "auto"),
    Original("Original", "original"),
    P4K("4K", "2160p"),
    P1080("1080p", "1080p"),
    P720("720p", "720p"),
    P480("480p", "480p");

    companion object {
        fun fromWire(value: String?): PlaybackQuality {
            val normalized = value?.lowercase()
            return when (normalized) {
                "4k" -> P4K
                else -> values().firstOrNull { it.wireValue == normalized } ?: Auto
            }
        }
    }
}

/**
 * Subtitle rendering mode. Off hides subtitles entirely; Auto uses the user's
 * preferred language when available; Always forces subtitles on every track.
 */
enum class SubtitleMode(val label: String, val wireValue: String) {
    Off("Off", "off"),
    Auto("Auto", "auto"),
    Always("Always", "always");

    companion object {
        fun fromWire(value: String?): SubtitleMode =
            values().firstOrNull { it.wireValue == value } ?: Auto
    }
}

/**
 * Subtitle text size, rendered via the player cue style.
 */
enum class SubtitleSize(val label: String, val scale: Float) {
    Small("Small", 0.85f),
    Medium("Medium", 1.0f),
    Large("Large", 1.25f);

    companion object {
        fun fromLabel(value: String?): SubtitleSize =
            values().firstOrNull { it.label == value } ?: Medium
    }
}
