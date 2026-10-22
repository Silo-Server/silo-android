package org.siloserver.silo.model.playback

import org.siloserver.silo.model.settings.QualityChoices

/**
 * The quality preference that hands the choice back to the server's planner,
 * which plays the source whenever the device and connection can.
 */
const val PLAYBACK_QUALITY_AUTO = "auto"

/** Retired spellings of "play the source", which Auto now does whenever it can. */
private val SOURCE_QUALITY_ALIASES = setOf(QUALITY_ORIGINAL_V3, "source", "max")

/**
 * [preference] as a video `quality_preference`: trimmed and lowercased, with
 * "original" and its aliases read as Auto. Older servers still take
 * "original" to mean never convert, so video requests must not send it. Null
 * for a missing or blank preference.
 */
fun videoQualityPreference(preference: String?): String? {
    val normalized = preference?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return if (normalized in SOURCE_QUALITY_ALIASES) PLAYBACK_QUALITY_AUTO else normalized
}

/**
 * The source entry ("original", preserves_source) that servers before the
 * Original removal still list. Auto is the way back to the source, so the
 * client drops it.
 */
fun PlaybackAvailableQualityV3.isSourceQualityEntry(): Boolean =
    preservesSource || label.equals(QUALITY_ORIGINAL_V3, ignoreCase = true)

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
)

/**
 * The plan's Quality menu: Auto, then the entries of
 * [PlaybackPlanV3.availableQualities] in server order. The server owns the
 * entries; the client only drops the source entry older servers still send
 * ([isSourceQualityEntry]), because Auto plays the source. Auto is added here
 * because it is a preference, not an entry. A plan with a single entry still
 * gets Auto + that entry, as the Apple clients show; a plan with no entries
 * left gets no menu.
 */
fun playbackQualityMenu(available: List<PlaybackAvailableQualityV3>): List<PlaybackQualityOption> {
    val entries = available.filterNot { it.isSourceQualityEntry() }.map { quality ->
        PlaybackQualityOption(
            id = quality.label,
            name = playbackQualityName(quality),
            bitrateLabel = formatPlaybackQualityBitrate(quality.bitrateKbps),
            height = quality.height,
            bitrateKbps = quality.bitrateKbps,
        )
    }
    if (entries.isEmpty()) return entries
    return listOf(PlaybackQualityOption(id = PLAYBACK_QUALITY_AUTO, name = "Auto")) + entries
}

/** The entry's display name, else its label. */
fun playbackQualityName(quality: PlaybackAvailableQualityV3): String =
    quality.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: quality.label

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
 * The Quality control's value: the lit row's name, with Auto naming what it
 * plays ([playbackAutoQualityLabel] of [autoResolution]), or with none lit (a
 * saved resolution the source plays under) that resolution, such as "1080p".
 */
fun playbackQualityValueLabel(
    options: List<PlaybackQualityOption>,
    activeId: String?,
    preference: String?,
    autoResolution: String? = null,
): String {
    val active = options.firstOrNull { it.id == activeId }
    return playbackQualityValueLabel(active?.id, active?.name, preference, autoResolution)
}

/** [playbackQualityValueLabel] from the lit row's [activeId] and [activeName], both null with none lit. */
fun playbackQualityValueLabel(
    activeId: String?,
    activeName: String?,
    preference: String?,
    autoResolution: String?,
): String = when {
    activeId == null || activeName == null -> QualityChoices.resolutionLabel(videoQualityPreference(preference))
    activeId == PLAYBACK_QUALITY_AUTO -> playbackAutoQualityLabel(autoResolution)
    else -> activeName
}

/**
 * The server's report of the menu entry a plan delivers: [label] is
 * [PlaybackPlanV3.activeQuality], null when the plan delivers none.
 */
data class ReportedPlaybackQuality(val label: String?)

/** The plan's [ReportedPlaybackQuality], or null when its server did not report one. */
val PlaybackPlanV3.reportedQuality: ReportedPlaybackQuality?
    get() = if (activeQualityReported) ReportedPlaybackQuality(activeQuality) else null

/** The plan's [ReportedPlaybackQuality], or null when its server did not report one. */
val PlaybackExecutionPlan.reportedQuality: ReportedPlaybackQuality?
    get() = if (activeQualityReported) ReportedPlaybackQuality(activeQuality) else null

/**
 * The id of the [options] row that [preference], the session's preference,
 * selects, or null when none does. Auto lights only for an "auto" preference
 * (also a missing one, "original" and its aliases). Otherwise a [reported]
 * entry lights its row, and a reported plan that delivers no entry lights
 * none.
 *
 * Without a report (servers before [PLAN_ACTIVE_QUALITY_V1_FEATURE]) the row
 * is inferred: a picked rung lights its own row. A resolution-only
 * preference such as "1080p" resolves the way the planner applies it: to no
 * row when the [source] fits under it and nothing reduced it, since the
 * source plays as-is and the viewer can still choose Auto, otherwise to the
 * delivered resolution class's Medium entry. [delivered] shows a reduction
 * as a lower bitrate or a shorter frame than the source's.
 */
fun activePlaybackQualityId(
    options: List<PlaybackQualityOption>,
    preference: String?,
    delivered: PlaybackEffectiveRecipeV3? = null,
    source: PlaybackSourceDescriptorV3? = null,
    reported: ReportedPlaybackQuality? = null,
): String? {
    if (options.isEmpty()) return null
    val normalized = videoQualityPreference(preference) ?: PLAYBACK_QUALITY_AUTO
    if (reported != null) {
        val id = if (normalized == PLAYBACK_QUALITY_AUTO) {
            PLAYBACK_QUALITY_AUTO
        } else {
            reported.label?.takeUnless { it == PLAYBACK_QUALITY_AUTO }
        }
        return options.firstOrNull { it.id == id }?.id
    }
    options.firstOrNull { it.id.lowercase() == normalized }?.let { return it.id }
    val aliasHeight = qualityPreferenceHeight(normalized) ?: return null

    val sourceHeight = source?.height?.takeIf { it > 0 }
    val sourceKbps = source?.bitrateKbps?.takeIf { it > 0 }
    val deliveredHeight = delivered?.height?.takeIf { it > 0 }
    val deliveredKbps = delivered?.bitrateKbps?.takeIf { it > 0 }
    // A shorter delivered frame shows a reduction even when the bitrate does not.
    val reduced = (deliveredKbps != null && sourceKbps != null && deliveredKbps < sourceKbps) ||
        (deliveredHeight != null && sourceHeight != null && deliveredHeight < sourceHeight)
    if (sourceHeight != null && !reduced && sourceHeight <= aliasHeight) return null

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
