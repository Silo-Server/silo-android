package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.playback.isBitmapSubtitleCodecFamily
import org.siloserver.silo.playback.subtitleMediaIdentityOrNull
import org.siloserver.silo.playback.SubtitleTimingActions

internal data class TvSubtitleHudOption(
    val stableId: String,
    val identity: SubtitleIdentity,
    val label: String,
    /** No route can show this server track: listed, never selectable. */
    val unplayable: Boolean = false,
)

internal data class TvSubtitleHudRow(
    val stableId: String,
    val identity: SubtitleIdentity,
    val label: String,
    val checked: Boolean,
    val applying: Boolean,
    val focused: Boolean,
    /** Shown disabled with an "Unsupported format" line; Select ignores it. */
    val unplayable: Boolean = false,
) {
    val status: String?
        get() = if (applying) "Applying…" else null
}

/**
 * What the HUD's Subtitles pane shows about subtitle sync: the selected
 * track's timing actions, and each track's status line, by ordinal for
 * server tracks and by stored ID for rows merged from the stored list.
 * Built by the view model, so the player screen passes one value through.
 */
internal data class TvHudSubtitleSync(
    val timing: SubtitleTimingActions? = null,
    val statusByServerIndex: Map<Int, String> = emptyMap(),
    val statusByDownloadId: Map<Int, String> = emptyMap(),
) {
    fun statusFor(identity: SubtitleIdentity): String? = when (identity) {
        is SubtitleIdentity.ServerSidecar -> statusByServerIndex[identity.serverIndex]
        is SubtitleIdentity.Downloaded -> statusByDownloadId[identity.downloadId]
        else -> null
    }
}

internal data class TvSubtitleHudPresentation(
    val rows: List<TvSubtitleHudRow>,
    val hudOpen: Boolean,
    val focusedStableId: String?,
    val focusTrapActive: Boolean,
    val onSelect: (SubtitleIdentity) -> Unit = {},
    val onFocused: (String) -> Unit = {},
)

/**
 * Which subtitle-appearance controls actually reach the picture for the
 * currently selected track.
 *
 * Image (PGS/DVB) captions are pre-rendered pixels: Media3's `SubtitlePainter`
 * draws the cue's own bitmap and reads none of the caption style, so Font,
 * Background, Opacity, Outline and the colour swatches are inert. Position and
 * Size still work, because Silo rewrites the cue's geometry before handing it to
 * the `SubtitleView` (see `remapBitmapCue` in android-shared).
 *
 * A server burn-in track has already been composited into the video frames, so
 * nothing the client does can change it.
 */
internal data class TvSubtitleAppearanceApplicability(
    /** Position and Size — the cue-geometry presets. */
    val geometryApplies: Boolean,
    /** Font, Background, Opacity, Outline and the colour swatches. */
    val stylingApplies: Boolean,
    /** One-line explanation for the pane, or null when everything applies. */
    val note: String?,
)

internal fun tvSubtitleAppearanceApplicability(
    identity: SubtitleIdentity?,
): TvSubtitleAppearanceApplicability = when {
    identity is SubtitleIdentity.ServerBurnIn -> TvSubtitleAppearanceApplicability(
        geometryApplies = false,
        stylingApplies = false,
        note = "Burned-in subtitles are part of the video and keep the server's styling.",
    )
    isBitmapSubtitleCodecFamily(identity?.subtitleMediaIdentityOrNull()?.codecFamily) ->
        TvSubtitleAppearanceApplicability(
            geometryApplies = true,
            stylingApplies = false,
            note = "Image subtitles keep their own styling — only Position and Size apply.",
        )
    else -> TvSubtitleAppearanceApplicability(
        geometryApplies = true,
        stylingApplies = true,
        note = null,
    )
}

internal fun tvSubtitleOptionStableId(identity: SubtitleIdentity): String =
    encodeSubtitleIdentityPreference(identity)

internal fun buildTvSubtitleHudPresentation(
    options: List<TvSubtitleHudOption>,
    committedIdentity: SubtitleIdentity,
    pendingIdentity: SubtitleIdentity?,
    hudOpen: Boolean,
    focusedStableId: String?,
    onSelect: (SubtitleIdentity) -> Unit = {},
    onFocused: (String) -> Unit = {},
): TvSubtitleHudPresentation {
    // A disabled row cannot take focus, so it never anchors the pane.
    val optionIds = options.filterNot(TvSubtitleHudOption::unplayable)
        .mapTo(mutableSetOf(), TvSubtitleHudOption::stableId)
    val resolvedFocus = focusedStableId
        ?.takeIf(optionIds::contains)
        ?: options.firstOrNull { it.identity == committedIdentity && !it.unplayable }?.stableId
        ?: options.firstOrNull { !it.unplayable }?.stableId
    return TvSubtitleHudPresentation(
        rows = options.map { option ->
            TvSubtitleHudRow(
                stableId = option.stableId,
                identity = option.identity,
                label = option.label,
                checked = option.identity == committedIdentity,
                applying = option.identity == pendingIdentity &&
                    pendingIdentity != committedIdentity,
                focused = option.stableId == resolvedFocus,
                unplayable = option.unplayable,
            )
        },
        hudOpen = hudOpen,
        focusedStableId = resolvedFocus,
        focusTrapActive = hudOpen,
        onSelect = onSelect,
        onFocused = onFocused,
    )
}

internal fun buildTvSubtitleHudOptions(
    subtitleUrls: List<PlayerSubtitleInfo>,
    subtitleTracks: List<PlayerTrackEntry>,
): List<TvSubtitleHudOption> {
    val mountedTrackIndexes = resolvedMountedSubtitleTrackIndexes(subtitleTracks, subtitleUrls)
    val playerOnlyTracks = if (subtitleUrls.isEmpty()) {
        subtitleTracks
    } else {
        subtitleTracks.filterNot { it.index in mountedTrackIndexes }
    }
    return buildList {
        add(tvSubtitleHudOption(SubtitleIdentity.Off, "Off"))
        subtitleUrls.forEachIndexed { position, row ->
            add(
                tvSubtitleHudOption(
                    tvSubtitleIdentity(row),
                    subtitleChoiceLabel(row, position),
                    unplayable = row.unplayable,
                ),
            )
        }
        playerOnlyTracks.forEachIndexed { position, track ->
            add(
                tvSubtitleHudOption(
                    tvSubtitleIdentity(track),
                    track.displayLabel.ifBlank { "Track ${position + 1}" },
                ),
            )
        }
    }.distinctBy(TvSubtitleHudOption::stableId)
}

private fun tvSubtitleHudOption(
    identity: SubtitleIdentity,
    label: String,
    unplayable: Boolean = false,
): TvSubtitleHudOption = TvSubtitleHudOption(
    stableId = tvSubtitleOptionStableId(identity),
    identity = identity,
    label = label,
    unplayable = unplayable,
)
