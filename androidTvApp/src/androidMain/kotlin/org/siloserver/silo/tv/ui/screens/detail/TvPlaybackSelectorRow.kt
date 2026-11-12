package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.catalog.PlaybackVariant
import org.siloserver.silo.model.catalog.playbackEditions
import org.siloserver.silo.model.catalog.hasEditionChoices
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.playback.selectPlaybackVersion
import org.siloserver.silo.tv.ui.components.TvAnchoredSelectorMenu
import org.siloserver.silo.tv.ui.components.TvSelectorOption
import org.siloserver.silo.tv.ui.components.TvSelectorTriggerStyle

// ---------------------------------------------------------------------------
// Inline playback-selection row — Compose-for-TV port of silo-apple's
// `TVPlaybackSelectorRow.swift`. Renders Edition (only when >1 edition group) ·
// Version · Audio · Subtitles as squared `.compact` secondary pills that each
// open an anchored dropdown (`TvAnchoredSelectorMenu`). Sits below the hero
// action row inside the action cluster.
//
// Rendered only when an effective playable [currentVersion] is resolved
// (Apple's `hasAnySelector = currentVersion != nil`). For series/season detail
// the caller passes the next-up episode's playback version; until that data is
// available `currentVersion` is null and the row simply does not show.
//
// Selection semantics (preserved from the existing VM contract):
// - Version: fileId; Auto = null.
// - Audio: zero-based ordinal into the version's audio tracks; Auto = null.
// - Subtitles: combined subtitle selection index shared with playback; Auto =
//   null; Off = -1. Visible sorting does not change that selection identity.
// ---------------------------------------------------------------------------

internal fun isAudioSelectorOptionSelected(
    optionIndex: Int?,
    selectedAudioTrackIndex: Int?,
): Boolean = optionIndex == selectedAudioTrackIndex

/**
 * Apple's `DetailPlaybackFormatting.shouldEnable*Selector`: a selector opens a
 * menu only when there is more than one REAL choice — scoped versions, audio
 * tracks, subtitle tracks or editions.
 *
 * The "Auto" and "Off" rows the menus prepend are pseudo-entries, not choices,
 * so they are deliberately NOT counted. Counting them (the previous rule, which
 * counted enabled menu rows) made every single-track file's Audio pill and every
 * single-version file's Version pill open a dropdown whose only real outcome was
 * the value already printed on the pill.
 */
internal fun selectorIsInteractive(realChoiceCount: Int): Boolean = realChoiceCount > 1

/**
 * Compact tvOS-style playback controls used in every video detail action row.
 * Version, Audio and Subtitles are circular peers of the utility actions.
 * Audio starts in Auto (the Playback preference) and can be overridden for the
 * current title/episode without changing that global preference.
 */
@Composable
internal fun TvPlaybackActionSelectors(
    versions: List<FileVersion>,
    playbackVariants: List<PlaybackVariant>,
    currentVersion: FileVersion?,
    selectedVersionFileId: Int?,
    selectedAudioTrackIndex: Int?,
    selectedSubtitleTrackIndex: Int?,
    automaticAudioTrackOrdinal: Int?,
    automaticAudioResolutionKnown: Boolean,
    preferredSubtitleLanguage: String?,
    subtitleMode: String?,
    showForcedSubtitles: Boolean,
    /**
     * The Auto context the detail page previews and starts from (the series
     * choice over the profile's preferences), for the given audio language.
     * The button's value is also its accessibility label, so it must say
     * what the preview says.
     */
    subtitleAutoContextFor: ((audioLanguage: String?) -> TvPlaybackFormatting.SubtitleAutoContext)? = null,
    /** The playback quality preference; picking an edition pins its Auto file. */
    preferredQuality: String? = null,
    onSelectVersion: (Int?) -> Unit,
    onSelectAudioTrack: (Int?) -> Unit,
    onSelectSubtitleTrack: (Int?) -> Unit,
    versionFocusRequester: FocusRequester,
) {
    val text = rememberTvPlaybackText()
    val autoVersionDetail = stringResource(CommonR.string.detail_autoVersion)
    val autoAudioDetail = stringResource(CommonR.string.detail_autoAudio)
    val autoSubtitlesDetail = stringResource(CommonR.string.detail_autoSubtitles)
    val offDetail = stringResource(CommonR.string.detail_offDetail)
    val editions = playbackEditions(versions, playbackVariants)
    val showEditions = editions.hasEditionChoices()
    val currentEdition = editions.firstOrNull { currentVersion?.fileId in it.fileIds }
        ?: editions.firstOrNull()
    val scopedVersions = if (showEditions) currentEdition?.versions.orEmpty() else versions
    val versionOptions = buildList {
        // Auto chooses across every edition, as on Apple and Roku.
        add(
            TvSelectorOption(
                key = "version:auto",
                title = text.auto,
                detail = autoVersionDetail,
                selected = selectedVersionFileId == null,
                onSelect = { onSelectVersion(null) },
            ),
        )
        scopedVersions.forEach { version ->
            add(
                TvSelectorOption(
                    key = "version:${version.fileId}",
                    title = TvPlaybackFormatting.versionShortLabel(version, text),
                    detail = TvPlaybackFormatting.versionDetailLabel(version, text),
                    selected = selectedVersionFileId == version.fileId,
                    onSelect = { onSelectVersion(version.fileId) },
                ),
            )
        }
    }
    val formattedSubtitleOptions = TvPlaybackFormatting.subtitleOptions(
        version = currentVersion,
        selectedSubtitleTrackIndex = selectedSubtitleTrackIndex,
        preferredLanguage = preferredSubtitleLanguage,
        text = text,
    )
    val formattedAudioOptions =
        TvPlaybackFormatting.audioOptions(currentVersion, selectedAudioTrackIndex, text)
    val audioOptions = buildList {
        add(
            TvSelectorOption(
                key = "audio:auto",
                title = text.auto,
                detail = autoAudioDetail,
                selected = selectedAudioTrackIndex == null,
                onSelect = { onSelectAudioTrack(null) },
            ),
        )
        formattedAudioOptions.forEach { option ->
            add(
                TvSelectorOption(
                    key = "audio:${option.ordinal}",
                    title = option.title,
                    detail = option.detail,
                    selected = option.isSelected,
                    onSelect = { onSelectAudioTrack(option.ordinal) },
                ),
            )
        }
    }
    val unsupportedFormat = stringResource(CommonR.string.subtitle_trackUnsupportedFormat)
    val subtitleOptions = buildList {
        add(
            TvSelectorOption(
                key = "subtitle:auto",
                title = text.auto,
                detail = autoSubtitlesDetail,
                selected = selectedSubtitleTrackIndex == null,
                onSelect = { onSelectSubtitleTrack(null) },
            ),
        )
        add(
            TvSelectorOption(
                key = "subtitle:off",
                title = text.off,
                detail = offDetail,
                selected = selectedSubtitleTrackIndex == -1,
                onSelect = { onSelectSubtitleTrack(-1) },
            ),
        )
        formattedSubtitleOptions.forEach { option ->
            add(
                TvSelectorOption(
                    key = "subtitle:${option.stableId}",
                    title = option.title,
                    detail = if (option.unplayable) unsupportedFormat else option.detail,
                    selected = option.isSelected,
                    onSelect = { onSelectSubtitleTrack(option.selectionIndex) },
                    enabled = !option.unplayable,
                ),
            )
        }
    }
    val versionValue = currentVersion?.let {
        TvPlaybackFormatting.versionValueLabel(it, selectedVersionFileId, text)
    }.orEmpty()
    val audioValue = currentVersion?.let {
        if (selectedAudioTrackIndex == null && !automaticAudioResolutionKnown) {
            text.auto
        } else {
            TvPlaybackFormatting.audioValueLabel(
                it,
                selectedAudioTrackIndex,
                automaticAudioTrackOrdinal,
                text,
            )
        }
    }.orEmpty()
    val subtitleValue = currentVersion?.let { version ->
        TvPlaybackFormatting.subtitleValueLabel(
            version = version,
            selectedSubtitleTrackIndex = selectedSubtitleTrackIndex,
            autoContext = if (selectedAudioTrackIndex != null || automaticAudioResolutionKnown) {
                val audioLanguage = TvPlaybackFormatting.resolvedAudioLanguage(
                    version,
                    selectedAudioTrackIndex,
                    automaticAudioTrackOrdinal,
                )
                subtitleAutoContextFor?.invoke(audioLanguage) ?: TvPlaybackFormatting.SubtitleAutoContext(
                    preferredLanguage = preferredSubtitleLanguage,
                    mode = subtitleMode,
                    showForced = showForcedSubtitles,
                    audioLanguage = audioLanguage,
                )
            } else {
                null
            },
            text = text,
        )
    }.orEmpty()

    if (showEditions) {
        TvAnchoredSelectorMenu(
            icon = Icons.Filled.Layers,
            label = stringResource(CommonR.string.detail_edition),
            value = currentEdition?.label.orEmpty(),
            options = editions.map { edition ->
                // Picking an edition turns Auto off and pins the file Auto
                // would choose within it, as on Apple and Roku.
                val editionVersion = selectPlaybackVersion(edition.versions, null, preferredQuality)
                TvSelectorOption(
                    key = "edition:${edition.id}",
                    title = edition.label,
                    detail = TvPlaybackFormatting.versionShortLabel(editionVersion, text),
                    selected = edition.id == currentEdition?.id,
                    onSelect = { onSelectVersion(editionVersion.fileId) },
                )
            },
            triggerFocusRequester = versionFocusRequester,
            interactive = currentVersion != null,
            triggerStyle = TvSelectorTriggerStyle.CircularAction,
        )
    }
    TvAnchoredSelectorMenu(
        icon = Icons.Filled.Movie,
        label = stringResource(CommonR.string.detail_version),
        value = versionValue,
        options = versionOptions,
        triggerFocusRequester = versionFocusRequester.takeUnless { showEditions },
        interactive = currentVersion != null && selectorIsInteractive(scopedVersions.size),
        triggerStyle = TvSelectorTriggerStyle.CircularAction,
    )
    TvAnchoredSelectorMenu(
        icon = Icons.AutoMirrored.Filled.VolumeUp,
        label = stringResource(CommonR.string.detail_audio),
        value = audioValue,
        options = audioOptions,
        interactive = currentVersion != null && formattedAudioOptions.isNotEmpty(),
        triggerStyle = TvSelectorTriggerStyle.CircularAction,
    )
    TvAnchoredSelectorMenu(
        icon = Icons.AutoMirrored.Filled.Chat,
        label = stringResource(CommonR.string.detail_subtitles),
        value = subtitleValue,
        options = subtitleOptions,
        interactive = currentVersion != null,
        triggerStyle = TvSelectorTriggerStyle.CircularAction,
    )
}
