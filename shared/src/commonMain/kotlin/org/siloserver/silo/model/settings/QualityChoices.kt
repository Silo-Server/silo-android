package org.siloserver.silo.model.settings

import org.siloserver.silo.model.playback.formatPlaybackQualityBitrate

/** One entry of a settings picker: the value it stores and its label. */
data class QualityChoice<T>(val value: T, val label: String)

/**
 * The main quality settings as two independent pickers, as the web app shows
 * them: a resolution ceiling (`playback.preferred_quality`) and a bitrate
 * ceiling (`playback.max_bitrate_kbps`, null for no limit). They are separate
 * because neither implies the other: someone on a fast link may still want
 * 1080p only, and some 1080p files carry a higher bitrate than some 4K ones.
 */
object QualityChoices {

    val RESOLUTIONS: List<QualityChoice<String>> = listOf(
        QualityChoice(QualityPresets.RESOLUTION_AUTO, "Auto"),
        QualityChoice("2160p", "4K"),
        QualityChoice("1080p", "1080p"),
        QualityChoice("720p", "720p"),
        QualityChoice("480p", "480p"),
    )

    /**
     * The bitrate ceilings offered, highest first. The same values as the web
     * app's `BITRATE_CHOICES_KBPS`, so a choice made there reads back as the
     * same entry here.
     */
    val BITRATES_KBPS: List<Int> = listOf(
        200_000, 100_000, 60_000, 40_000, 20_000, 15_000, 10_000, 6_000, 4_000, 2_000, 1_500,
    )

    const val NO_BITRATE_LIMIT_LABEL = "No limit"

    const val MAX_BITRATE_DESCRIPTION = "Files above this bitrate are converted to fit."

    /** The label of a stored resolution; legacy spellings read as their enum member. */
    fun resolutionLabel(value: String?): String {
        val normalized = QualityPresets.normalizeResolution(value)
        return RESOLUTIONS.firstOrNull { it.value == normalized }?.label ?: normalized
    }

    /** What the stored resolution does, shown under the picker. */
    fun resolutionDescription(value: String?): String {
        val normalized = QualityPresets.normalizeResolution(value)
        if (normalized == QualityPresets.RESOLUTION_AUTO) {
            return "Plays the original file whenever your device and connection can handle it."
        }
        return "Plays the original file up to ${resolutionLabel(normalized)}. Larger files are converted."
    }

    /** What both choices do together, shown under them. */
    fun description(resolution: String?, maxBitrateKbps: Int?): String {
        val resolutionText = resolutionDescription(resolution)
        val capped = maxBitrateKbps != null && maxBitrateKbps > 0
        return if (capped) "$resolutionText $MAX_BITRATE_DESCRIPTION" else resolutionText
    }

    /** The pair in one line, for a row that leads to the pickers: "1080p, up to 6 Mbps". */
    fun summary(resolution: String?, maxBitrateKbps: Int?): String {
        val capped = maxBitrateKbps?.takeIf { it > 0 } ?: return resolutionLabel(resolution)
        return "${resolutionLabel(resolution)}, up to ${bitrateLabel(capped)}"
    }

    /**
     * "No limit", "40 Mbps", "1.5 Mbps". A cap that one decimal of Mbps
     * cannot show exactly (set through the API) reads in kbps, so it never
     * shares a label with a listed choice.
     */
    fun bitrateLabel(kbps: Int?): String {
        val capped = kbps?.takeIf { it > 0 } ?: return NO_BITRATE_LIMIT_LABEL
        if (capped % 100 != 0) return "$capped kbps"
        return formatPlaybackQualityBitrate(capped) ?: NO_BITRATE_LIMIT_LABEL
    }

    /**
     * "No limit", then [BITRATES_KBPS]. A stored cap that is not one of them
     * (set through the API or another client) is listed in its place by size,
     * so it stays visible and selected rather than reading as no limit.
     */
    fun bitrateChoices(current: Int?): List<QualityChoice<Int?>> {
        val stored = current?.takeIf { it > 0 }
        val values = if (stored == null || stored in BITRATES_KBPS) {
            BITRATES_KBPS
        } else {
            (BITRATES_KBPS + stored).sortedDescending()
        }
        return listOf(QualityChoice<Int?>(null, NO_BITRATE_LIMIT_LABEL)) +
            values.map { QualityChoice(it, bitrateLabel(it)) }
    }
}

/**
 * The phone's cellular quality: the pair it sends instead of the main one on
 * mobile data or a metered network (a phone hotspot, metered Wi-Fi), stored as
 * `playback.cellular_preferred_quality` + `playback.cellular_max_bitrate_kbps`
 * (settings contract revision 20). It is picked as one preset, since bitrate
 * is what matters on a data plan. Android TV neither shows nor applies it.
 */
object CellularQuality {
    /** Contract revision that introduced both keys. */
    const val MIN_CONTRACT_REVISION = 20

    val KEYS: List<String> = listOf(
        SettingKeys.PLAYBACK_CELLULAR_PREFERRED_QUALITY,
        SettingKeys.PLAYBACK_CELLULAR_MAX_BITRATE_KBPS,
    )

    /** The contract defaults: the 720p preset. */
    const val DEFAULT_RESOLUTION = "720p"
    const val DEFAULT_MAX_BITRATE_KBPS = 2_000

    const val FOOTER = "Used on mobile data and metered networks, such as a phone hotspot."

    /**
     * The picker's label for a stored pair: its preset's, or, for a pair no
     * preset covers (the two keys set separately elsewhere), one naming both
     * axes, such as "1080p, no limit", so it never reads as a preset.
     */
    fun label(resolution: String?, maxBitrateKbps: Int?): String {
        QualityPresets.presetFor(resolution, maxBitrateKbps)?.let { return it.label }
        val cap = maxBitrateKbps?.takeIf { it > 0 }
            ?.let { "up to ${QualityChoices.bitrateLabel(it)}" }
            ?: QualityChoices.NO_BITRATE_LIMIT_LABEL.lowercase()
        return "${QualityChoices.resolutionLabel(resolution)}, $cap"
    }

    /** The contract revision [key] needs, or null for a key this object does not gate. */
    fun minimumRevision(key: String): Int? = MIN_CONTRACT_REVISION.takeIf { key in KEYS }
}
