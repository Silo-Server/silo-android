package org.siloserver.silo.model.playback

/** The quality preference that hands the choice back to the server's planner. */
const val PLAYBACK_QUALITY_AUTO = "auto"

/**
 * One row of the player's Quality menu. [id] is the `quality_preference` a
 * pick sends back verbatim: a plan entry's own label, or
 * [PLAYBACK_QUALITY_AUTO].
 */
data class PlaybackQualityOption(
    val id: String,
    val name: String,
    /** The entry's bitrate as the row's secondary text, or null when unknown. */
    val bitrateLabel: String? = null,
    val height: Int = 0,
    val bitrateKbps: Int = 0,
    val preservesSource: Boolean = false,
)

/**
 * The plan's Quality menu: Auto, then every entry of
 * [PlaybackPlanV3.availableQualities] in server order. The server owns the
 * entries and the client never derives or filters them. Auto is added here
 * because it is a preference, not an entry. A plan with a single entry still
 * gets Auto + that entry, as the Apple clients show; only a plan with no
 * entries (no plan at all) gets no menu.
 */
fun playbackQualityMenu(available: List<PlaybackAvailableQualityV3>): List<PlaybackQualityOption> {
    val entries = available.map { quality ->
        PlaybackQualityOption(
            id = quality.label,
            name = playbackQualityName(quality),
            bitrateLabel = formatPlaybackQualityBitrate(quality.bitrateKbps),
            height = quality.height,
            bitrateKbps = quality.bitrateKbps,
            preservesSource = quality.preservesSource,
        )
    }
    if (entries.isEmpty()) return entries
    return listOf(PlaybackQualityOption(id = PLAYBACK_QUALITY_AUTO, name = "Auto")) + entries
}

/** The entry's display name, else "Original" for the source entry, else its label. */
fun playbackQualityName(quality: PlaybackAvailableQualityV3): String =
    quality.displayName?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (quality.preservesSource) "Original" else quality.label

/**
 * A bitrate in Mbps to one decimal, dropping a zero decimal: "40 Mbps",
 * "1.5 Mbps". Below 1 Mbps it stays in kbps. Null when the bitrate is unknown.
 */
fun formatPlaybackQualityBitrate(kbps: Int): String? {
    if (kbps <= 0) return null
    if (kbps < 1_000) return "$kbps kbps"
    val tenths = (kbps + 50) / 100
    val fraction = tenths % 10
    return if (fraction == 0) "${tenths / 10} Mbps" else "${tenths / 10}.$fraction Mbps"
}

/**
 * The id of the [options] row that [preference] selects, or null when none
 * does. A missing preference is Auto. When the plan has a sole server entry,
 * any other preference selects it, since it is what plays. A stored
 * resolution-only preference such as "1080p" resolves the way the planner
 * applies it: to the source entry when the source fits under it and no
 * bitrate cap reduced it ([delivered] shows that), otherwise to that
 * resolution class's Medium entry.
 */
fun activePlaybackQualityId(
    options: List<PlaybackQualityOption>,
    preference: String?,
    delivered: PlaybackEffectiveRecipeV3? = null,
): String? {
    val normalized = preference?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: PLAYBACK_QUALITY_AUTO
    options.firstOrNull { it.id.lowercase() == normalized }?.let { return it.id }
    options.singleOrNull { it.id != PLAYBACK_QUALITY_AUTO }?.let { return it.id }

    val original = options.firstOrNull { it.preservesSource }
    val aliasHeight = qualityPreferenceHeight(normalized)
    if (aliasHeight == null) {
        return if (normalized == "source" || normalized == "max") original?.id else null
    }

    val deliveredKbps = delivered?.bitrateKbps?.takeIf { it > 0 }
    val capped = deliveredKbps != null && deliveredKbps < (original?.bitrateKbps ?: 0)
    if (original != null && !capped && original.height <= aliasHeight) return original.id

    val classHeight = ladderClassHeight(delivered?.width, delivered?.height) ?: aliasHeight
    val rungId = when (classHeight) {
        480 -> "480p"
        // A low bitrate cap can choose the 540p class, which has no menu entry.
        540 -> null
        else -> "${classHeight}p-medium"
    }
    return options.firstOrNull { it.id == rungId }?.id
}

private fun qualityPreferenceHeight(preference: String): Int? = when (preference) {
    "2160p", "4k", "uhd" -> 2160
    "1080p", "fhd" -> 1080
    "720p", "hd" -> 720
    "480p", "sd" -> 480
    else -> null
}

/** The server's bitrate ladder classes as (width, height) boxes, smallest first. */
private val LADDER_CLASS_BOXES = listOf(854 to 480, 960 to 540, 1280 to 720, 1920 to 1080, 3840 to 2160)

/**
 * The ladder class an encoded frame belongs to: the smallest box that holds
 * it, so a 1280x688 encode is 720p. A frame larger than every box is 2160p.
 */
private fun ladderClassHeight(width: Int?, height: Int?): Int? {
    if (height == null || height <= 0) return null
    val knownWidth = width?.takeIf { it > 0 }
    return LADDER_CLASS_BOXES.firstOrNull { (boxWidth, boxHeight) ->
        height <= boxHeight && (knownWidth == null || knownWidth <= boxWidth)
    }?.second ?: 2160
}
