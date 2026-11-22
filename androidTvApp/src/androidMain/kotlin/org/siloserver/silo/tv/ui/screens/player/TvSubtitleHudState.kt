package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.i18n.UiText
import android.content.res.Resources
import androidx.annotation.StringRes
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.player.trackIdDenotes
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_SIDECAR
import org.siloserver.silo.model.playback.playsAs
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
    /** The file's track Media3 lists for this server row, which the menu does not list again. */
    val sourceTrackId: String? = null,
) {
    /**
     * Whether this row is what [committed] plays, including the file's track
     * it stands for, mounted as a player track (a choice saved from before
     * that track stopped being listed apart from its row).
     */
    fun plays(committed: SubtitleIdentity): Boolean =
        identity.playsAs(committed) ||
            sourceTrackId != null &&
            committed is SubtitleIdentity.LocalMedia3 &&
            trackIdDenotes(committed.media.trackId, sourceTrackId)
}

internal data class TvSubtitleHudRow(
    val stableId: String,
    val identity: SubtitleIdentity,
    val label: String,
    val checked: Boolean,
    val applying: Boolean,
    val focused: Boolean,
    /** Shown disabled with an "Unsupported format" line; Select ignores it. */
    val unplayable: Boolean = false,
    /** The row's status line, "Applying…" while [applying]; set by [buildTvSubtitleHudPresentation]. */
    val status: String? = null,
)

/**
 * What the HUD's Subtitles pane shows about subtitle sync: the selected
 * track's timing actions, and each track's status line, by ordinal for
 * server tracks and by stored ID for rows merged from the stored list.
 * Built by the view model, so the player screen passes one value through.
 */
internal data class TvHudSubtitleSync(
    val timing: SubtitleTimingActions? = null,
    val statusByServerIndex: Map<Int, UiText> = emptyMap(),
    val statusByDownloadId: Map<Int, UiText> = emptyMap(),
) {
    fun statusFor(identity: SubtitleIdentity): UiText? = when (identity) {
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
    /**
     * What plays. The checked row is the viewer's pick, which differs when the
     * server burned a sidecar pick in.
     */
    val committedIdentity: SubtitleIdentity,
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
    @StringRes val note: Int?,
)

internal fun tvSubtitleAppearanceApplicability(
    identity: SubtitleIdentity?,
): TvSubtitleAppearanceApplicability = when {
    identity is SubtitleIdentity.ServerBurnIn -> TvSubtitleAppearanceApplicability(
        geometryApplies = false,
        stylingApplies = false,
        note = CommonR.string.player_subtitles_burnedInNote,
    )
    isBitmapSubtitleCodecFamily(identity?.subtitleMediaIdentityOrNull()?.codecFamily) ->
        TvSubtitleAppearanceApplicability(
            geometryApplies = true,
            stylingApplies = false,
            note = CommonR.string.player_subtitles_imageStylingNote,
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
    resources: Resources,
    onSelect: (SubtitleIdentity) -> Unit = {},
    onFocused: (String) -> Unit = {},
): TvSubtitleHudPresentation {
    val applyingLabel = resources.getString(CommonR.string.player_hud_applying)
    // A disabled row cannot take focus, so it never anchors the pane.
    val optionIds = options.filterNot(TvSubtitleHudOption::unplayable)
        .mapTo(mutableSetOf(), TvSubtitleHudOption::stableId)
    val resolvedFocus = focusedStableId
        ?.takeIf(optionIds::contains)
        ?: options.firstOrNull { it.plays(committedIdentity) && !it.unplayable }?.stableId
        ?: options.firstOrNull { !it.unplayable }?.stableId
    return TvSubtitleHudPresentation(
        rows = options.map { option ->
            val applying = pendingIdentity != null &&
                option.plays(pendingIdentity) &&
                !pendingIdentity.playsAs(committedIdentity)
            TvSubtitleHudRow(
                stableId = option.stableId,
                identity = option.identity,
                label = option.label,
                checked = option.plays(committedIdentity),
                applying = applying,
                focused = option.stableId == resolvedFocus,
                unplayable = option.unplayable,
                status = applyingLabel.takeIf { applying },
            )
        },
        hudOpen = hudOpen,
        focusedStableId = resolvedFocus,
        focusTrapActive = hudOpen,
        committedIdentity = committedIdentity,
        onSelect = { identity ->
            // The row stands for the player track that already plays: picking
            // it keeps that track rather than switching it to the server's copy.
            val keepsPlayerTrack = committedIdentity is SubtitleIdentity.LocalMedia3 &&
                options.any { it.identity == identity && it.plays(committedIdentity) }
            onSelect(if (keepsPlayerTrack) committedIdentity else identity)
        },
        onFocused = onFocused,
    )
}

internal fun buildTvSubtitleHudOptions(
    subtitleUrls: List<PlayerSubtitleInfo>,
    subtitleTracks: List<PlayerTrackEntry>,
    resources: Resources,
): List<TvSubtitleHudOption> {
    val mountedTrackIndexes = resolvedMountedSubtitleTrackIndexes(subtitleTracks, subtitleUrls)
    // A server row the player cannot mount locally (a sidecar row for an
    // embedded track) still names the original file's track that Media3 lists,
    // so that track is the row, not another choice.
    val sourceTrackIds = subtitleUrls.mapNotNull(PlayerSubtitleInfo::menuSourceTrackId)
    val playerOnlyTracks = if (subtitleUrls.isEmpty()) {
        subtitleTracks
    } else {
        subtitleTracks.filterNot { track ->
            track.index in mountedTrackIndexes ||
                sourceTrackIds.any { trackIdDenotes(track.trackId, it) }
        }
    }
    return buildList {
        add(tvSubtitleHudOption(SubtitleIdentity.Off, resources.getString(CommonR.string.player_tracks_subtitlesOff)))
        subtitleUrls.forEachIndexed { position, row ->
            add(
                tvSubtitleHudOption(
                    tvSubtitleIdentity(row),
                    subtitleChoiceLabel(row, position, resources),
                    unplayable = row.unplayable,
                    sourceTrackId = row.menuSourceTrackId(),
                ),
            )
        }
        playerOnlyTracks.forEachIndexed { position, track ->
            add(
                tvSubtitleHudOption(
                    tvSubtitleIdentity(track),
                    track.displayLabel.ifBlank {
                        resources.getString(CommonR.string.options_track, LocaleFormat.integer(position + 1))
                    },
                ),
            )
        }
    }.distinctBy(TvSubtitleHudOption::stableId)
}

/**
 * The file's track this row stands for in the menu: only a sidecar row, which
 * plays the same track from the server's copy. A burn-in-only row's route is a
 * transcode, and an unplayable row cannot be picked, so their track stays
 * listed on its own as the way to show it on the device.
 */
private fun PlayerSubtitleInfo.menuSourceTrackId(): String? =
    sourceContainerTrackId.takeIf { serverDelivery == SUBTITLE_DELIVERY_SIDECAR && !unplayable }

private fun tvSubtitleHudOption(
    identity: SubtitleIdentity,
    label: String,
    unplayable: Boolean = false,
    sourceTrackId: String? = null,
): TvSubtitleHudOption = TvSubtitleHudOption(
    stableId = tvSubtitleOptionStableId(identity),
    identity = identity,
    label = label,
    unplayable = unplayable,
    sourceTrackId = sourceTrackId,
)
