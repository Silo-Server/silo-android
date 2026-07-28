package org.siloserver.silo.model.settings

/**
 * Bridge between the granular `subtitle.*` fields Android stores on the device
 * and the one composite `playback.subtitle_appearance` object the contract
 * carries.
 *
 * The contract has no definitions for the granular fields — the resolver would
 * refuse them as `unknown_setting` — so they stay client-local. That does not
 * make them private: a per-field edit has to reach the server, and it does so
 * by being projected into the composite before the flush. [project] merges the
 * granular slots over a base appearance (sparse: an absent or unparseable field
 * leaves the base value alone, matching the schema's "a stored value is a
 * sparse override" rule), and [flatten] writes the composite back out so the
 * two representations never drift after a server refresh.
 */
object SubtitleAppearanceProjection {

    /** The granular keys, in the order [flatten] emits them. */
    val GRANULAR_KEYS: List<String> = listOf(
        PlaybackSettingsKeys.SubtitleFontSize,
        PlaybackSettingsKeys.SubtitleFontFamily,
        PlaybackSettingsKeys.SubtitleTextColor,
        PlaybackSettingsKeys.SubtitleBackgroundColor,
        PlaybackSettingsKeys.SubtitleBackgroundStyle,
        PlaybackSettingsKeys.SubtitleBackgroundOpacity,
        PlaybackSettingsKeys.SubtitleTextOutline,
        PlaybackSettingsKeys.SubtitleTextOutlineColor,
        PlaybackSettingsKeys.SubtitlePosition,
    )

    /**
     * Merges the granular [fields] over [base]. Values are the store's plain
     * strings; a field that is absent, blank, or not a member of its enum is
     * skipped rather than reset to a default, because the granular slots are a
     * sparse overlay and a bad value must not erase a good one.
     */
    fun project(
        fields: Map<String, String?>,
        base: SubtitleAppearance = SubtitleAppearance.DEFAULT,
    ): SubtitleAppearance {
        var out = base
        fields[PlaybackSettingsKeys.SubtitleFontSize]?.let { raw ->
            fontSize(raw)?.let { out = out.copy(fontSize = it) }
        }
        fields[PlaybackSettingsKeys.SubtitleFontFamily]?.let { raw ->
            raw.trim().takeIf { it.isNotEmpty() }?.let { out = out.copy(fontFamily = it) }
        }
        fields[PlaybackSettingsKeys.SubtitleTextColor]?.let { raw ->
            hexColor(raw)?.let { out = out.copy(fontColor = it) }
        }
        fields[PlaybackSettingsKeys.SubtitleBackgroundColor]?.let { raw ->
            hexColor(raw)?.let { out = out.copy(backgroundColor = it) }
        }
        fields[PlaybackSettingsKeys.SubtitleBackgroundStyle]?.let { raw ->
            backgroundStyle(raw)?.let { out = out.copy(backgroundStyle = it) }
        }
        fields[PlaybackSettingsKeys.SubtitleBackgroundOpacity]?.let { raw ->
            raw.trim().toIntOrNull()?.let { out = out.copy(backgroundOpacity = it.coerceIn(0, 100)) }
        }
        fields[PlaybackSettingsKeys.SubtitleTextOutline]?.let { raw ->
            raw.trim().lowercase().toBooleanStrictOrNull()?.let { out = out.copy(textOutline = it) }
        }
        fields[PlaybackSettingsKeys.SubtitleTextOutlineColor]?.let { raw ->
            hexColor(raw)?.let { out = out.copy(textOutlineColor = it) }
        }
        fields[PlaybackSettingsKeys.SubtitlePosition]?.let { raw ->
            position(raw)?.let { out = out.copy(position = it) }
        }
        return out.sanitized()
    }

    /**
     * The granular spelling of [appearance] — every key present, so writing
     * this back over the local slots leaves nothing stale behind.
     */
    fun flatten(appearance: SubtitleAppearance): Map<String, String> {
        val safe = appearance.sanitized()
        return mapOf(
            PlaybackSettingsKeys.SubtitleFontSize to safe.fontSize.wire,
            PlaybackSettingsKeys.SubtitleFontFamily to safe.fontFamily,
            PlaybackSettingsKeys.SubtitleTextColor to safe.fontColor,
            PlaybackSettingsKeys.SubtitleBackgroundColor to safe.backgroundColor,
            PlaybackSettingsKeys.SubtitleBackgroundStyle to safe.backgroundStyle.wire,
            PlaybackSettingsKeys.SubtitleBackgroundOpacity to safe.backgroundOpacity.toString(),
            PlaybackSettingsKeys.SubtitleTextOutline to safe.textOutline.toString(),
            PlaybackSettingsKeys.SubtitleTextOutlineColor to safe.textOutlineColor,
            PlaybackSettingsKeys.SubtitlePosition to safe.position.wire,
        )
    }

    private fun fontSize(raw: String): SubtitleFontSizePreset? {
        val v = raw.trim().lowercase()
        return SubtitleFontSizePreset.entries.firstOrNull { it.wire == v }
    }

    private fun backgroundStyle(raw: String): SubtitleBackgroundStylePreset? {
        val v = raw.trim().lowercase()
        return SubtitleBackgroundStylePreset.entries.firstOrNull { it.wire == v }
    }

    private fun position(raw: String): SubtitlePositionPreset? {
        val v = raw.trim().lowercase()
        SubtitlePositionPreset.entries.firstOrNull { it.wire == v }?.let { return it }
        // Older Android builds stored the numeric cue-line position.
        return when (v.toIntOrNull()) {
            null -> null
            in Int.MIN_VALUE..34 -> SubtitlePositionPreset.Top
            in 35..84 -> SubtitlePositionPreset.LowerThird
            else -> SubtitlePositionPreset.Bottom
        }
    }

    private fun hexColor(raw: String): String? {
        val trimmed = raw.trim()
        val body = if (trimmed.startsWith("#")) trimmed.drop(1) else trimmed
        if (body.length != 6) return null
        if (!body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return "#" + body.lowercase()
    }
}
