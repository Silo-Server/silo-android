package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.FileVersion
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
    currentVersion: FileVersion?,
    selectedVersionFileId: Int?,
    selectedAudioTrackIndex: Int?,
    selectedSubtitleTrackIndex: Int?,
    automaticAudioTrackOrdinal: Int?,
    automaticAudioResolutionKnown: Boolean,
    preferredSubtitleLanguage: String?,
    subtitleMode: String?,
    showForcedSubtitles: Boolean,
    onSelectVersion: (Int?) -> Unit,
    onSelectAudioTrack: (Int?) -> Unit,
    onSelectSubtitleTrack: (Int?) -> Unit,
    versionFocusRequester: FocusRequester,
) {
    val versionOptions = buildList {
        add(
            TvSelectorOption(
                key = "version:auto",
                title = "Auto",
                detail = "Best match for this device",
                selected = selectedVersionFileId == null,
                onSelect = { onSelectVersion(null) },
            ),
        )
        versions.forEach { version ->
            add(
                TvSelectorOption(
                    key = "version:${version.fileId}",
                    title = TvPlaybackFormatting.versionShortLabel(version),
                    detail = TvPlaybackFormatting.versionDetailLabel(version),
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
    )
    val formattedAudioOptions =
        TvPlaybackFormatting.audioOptions(currentVersion, selectedAudioTrackIndex)
    val audioOptions = buildList {
        add(
            TvSelectorOption(
                key = "audio:auto",
                title = "Auto",
                detail = "Use your Playback audio preference",
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
    val subtitleOptions = buildList {
        add(
            TvSelectorOption(
                key = "subtitle:auto",
                title = "Auto",
                detail = "Use your subtitle preferences",
                selected = selectedSubtitleTrackIndex == null,
                onSelect = { onSelectSubtitleTrack(null) },
            ),
        )
        add(
            TvSelectorOption(
                key = "subtitle:off",
                title = "Off",
                detail = "Start without subtitles",
                selected = selectedSubtitleTrackIndex == -1,
                onSelect = { onSelectSubtitleTrack(-1) },
            ),
        )
        formattedSubtitleOptions.forEach { option ->
            add(
                TvSelectorOption(
                    key = "subtitle:${option.stableId}",
                    title = option.title,
                    detail = option.detail,
                    selected = option.isSelected,
                    onSelect = { onSelectSubtitleTrack(option.selectionIndex) },
                ),
            )
        }
    }
    val versionValue = currentVersion?.let {
        TvPlaybackFormatting.versionValueLabel(it, selectedVersionFileId)
    }.orEmpty()
    val audioValue = currentVersion?.let {
        if (selectedAudioTrackIndex == null && !automaticAudioResolutionKnown) {
            "Auto"
        } else {
            TvPlaybackFormatting.audioValueLabel(
                it,
                selectedAudioTrackIndex,
                automaticAudioTrackOrdinal,
            )
        }
    }.orEmpty()
    val subtitleValue = currentVersion?.let { version ->
        TvPlaybackFormatting.subtitleValueLabel(
            version = version,
            selectedSubtitleTrackIndex = selectedSubtitleTrackIndex,
            autoContext = if (selectedAudioTrackIndex != null || automaticAudioResolutionKnown) {
                TvPlaybackFormatting.SubtitleAutoContext(
                    preferredLanguage = preferredSubtitleLanguage,
                    mode = subtitleMode,
                    showForced = showForcedSubtitles,
                    audioLanguage = TvPlaybackFormatting.resolvedAudioLanguage(
                        version,
                        selectedAudioTrackIndex,
                        automaticAudioTrackOrdinal,
                    ),
                )
            } else {
                null
            },
        )
    }.orEmpty()

    TvAnchoredSelectorMenu(
        icon = Icons.Filled.Movie,
        label = "Version",
        value = versionValue,
        options = versionOptions,
        triggerFocusRequester = versionFocusRequester,
        interactive = currentVersion != null && versions.isNotEmpty(),
        triggerStyle = TvSelectorTriggerStyle.CircularAction,
    )
    TvAnchoredSelectorMenu(
        icon = Icons.AutoMirrored.Filled.VolumeUp,
        label = "Audio",
        value = audioValue,
        options = audioOptions,
        interactive = currentVersion != null && formattedAudioOptions.isNotEmpty(),
        triggerStyle = TvSelectorTriggerStyle.CircularAction,
    )
    TvAnchoredSelectorMenu(
        icon = Icons.AutoMirrored.Filled.Chat,
        label = "Subtitles",
        value = subtitleValue,
        options = subtitleOptions,
        interactive = currentVersion != null,
        triggerStyle = TvSelectorTriggerStyle.CircularAction,
    )
}

@Composable
fun TvPlaybackSelectorRow(
    versions: List<FileVersion>,
    currentVersion: FileVersion?,
    selectedVersionFileId: Int?,
    selectedAudioTrackIndex: Int?,
    selectedSubtitleTrackIndex: Int?,
    automaticAudioTrackOrdinal: Int? = null,
    automaticAudioResolutionKnown: Boolean = false,
    // Cascaded subtitle prefs (profile-sourced) that let the Subtitles pill
    // preview the concrete track Auto will resolve to — the same rules the
    // player runs at launch.
    preferredSubtitleLanguage: String?,
    subtitleMode: String?,
    showForcedSubtitles: Boolean,
    onSelectVersion: (Int?) -> Unit,
    onSelectAudioTrack: (Int?) -> Unit,
    onSelectSubtitleTrack: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    /** Give every visible segment an equal, fixed share of the supplied width. */
    stretchSegments: Boolean = false,
) {
    // hasAnySelector — only show once an effective playable version is known.
    if (currentVersion == null) return

    val editions = TvPlaybackFormatting.editions(versions)
    val currentEdition = TvPlaybackFormatting.currentEdition(versions, currentVersion)
    // Scope the version list to the current edition when editions are present
    // (mirrors Apple's `scopedVersions`); Android has a single "Standard" group
    // today so this is the full list.
    val scopedVersions = if (editions.size > 1 && currentEdition != null) {
        currentEdition.versions
    } else {
        versions
    }
    val editionOptions = editions.map { edition ->
        val count = edition.versions.size
        TvSelectorOption(
            key = "edition:${edition.id}",
            title = edition.label,
            detail = "$count version${if (count == 1) "" else "s"}",
            selected = currentEdition?.id == edition.id,
            onSelect = { onSelectVersion(edition.versions.firstOrNull()?.fileId) },
        )
    }
    val versionOptions = buildList {
        add(
            TvSelectorOption(
                key = "version:auto",
                title = "Auto",
                detail = "Best match for this device",
                selected = selectedVersionFileId == null,
                onSelect = { onSelectVersion(null) },
            ),
        )
        scopedVersions.forEach { version ->
            add(
                TvSelectorOption(
                    key = "version:${version.fileId}",
                    title = TvPlaybackFormatting.versionShortLabel(version),
                    detail = TvPlaybackFormatting.versionDetailLabel(version),
                    selected = selectedVersionFileId == version.fileId,
                    onSelect = { onSelectVersion(version.fileId) },
                ),
            )
        }
    }
    // Hoisted out of the buildList blocks below: these are the REAL choices, and
    // their counts — not the assembled menu row counts, which carry Auto/Off —
    // decide whether each pill is interactive.
    val formattedAudioOptions =
        TvPlaybackFormatting.audioOptions(currentVersion, selectedAudioTrackIndex)
    val formattedSubtitleOptions = TvPlaybackFormatting.subtitleOptions(
        currentVersion,
        selectedSubtitleTrackIndex,
        preferredLanguage = preferredSubtitleLanguage,
    )
    val audioSelectorOptions = buildList {
        add(
            TvSelectorOption(
                key = "audio:auto",
                title = "Auto",
                detail = "Use the file default track",
                selected = isAudioSelectorOptionSelected(null, selectedAudioTrackIndex),
                onSelect = { onSelectAudioTrack(null) },
            ),
        )
        if (formattedAudioOptions.isEmpty()) {
            add(
                TvSelectorOption(
                    key = "audio:unknown",
                    title = "Unknown",
                    detail = "",
                    selected = false,
                    onSelect = {},
                    enabled = false,
                ),
            )
        } else {
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
    }
    val subtitleSelectorOptions = buildList {
        add(
            TvSelectorOption(
                key = "subtitle:auto",
                title = "Auto",
                detail = "Use your subtitle preferences",
                selected = selectedSubtitleTrackIndex == null,
                onSelect = { onSelectSubtitleTrack(null) },
            ),
        )
        add(
            TvSelectorOption(
                key = "subtitle:off",
                title = "Off",
                detail = "Start without subtitles",
                selected = selectedSubtitleTrackIndex == -1,
                onSelect = { onSelectSubtitleTrack(-1) },
            ),
        )
        formattedSubtitleOptions.forEach { option ->
            add(
                TvSelectorOption(
                    key = "subtitle:${option.stableId}",
                    title = option.title,
                    detail = option.detail,
                    selected = option.isSelected,
                    onSelect = { onSelectSubtitleTrack(option.selectionIndex) },
                ),
            )
        }
    }

    val audioValue = if (selectedAudioTrackIndex == null && !automaticAudioResolutionKnown) {
        "Auto"
    } else {
        TvPlaybackFormatting.audioValueLabel(
            currentVersion,
            selectedAudioTrackIndex,
            automaticAudioTrackOrdinal,
        )
    }
    val subtitleValue = TvPlaybackFormatting.subtitleValueLabel(
        currentVersion,
        selectedSubtitleTrackIndex,
        autoContext = if (selectedAudioTrackIndex != null || automaticAudioResolutionKnown) {
            TvPlaybackFormatting.SubtitleAutoContext(
                preferredLanguage = preferredSubtitleLanguage,
                mode = subtitleMode,
                showForced = showForcedSubtitles,
                audioLanguage = TvPlaybackFormatting.resolvedAudioLanguage(
                    currentVersion,
                    selectedAudioTrackIndex,
                    automaticAudioTrackOrdinal,
                ),
            )
        } else {
            null
        },
    )
    var groupExpanded by remember { mutableStateOf(false) }

    // Series supplies the measured action-row width and stretches its segments
    // across that stable footprint. Other detail pages retain the compact,
    // leading capsule. In fixed mode the values do not expand on focus, so the
    // selector never changes shape while the viewer moves through it.
    PlaybackSelectorCapsule(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { groupExpanded = it.hasFocus }
            .focusGroup(),
        stretchContent = stretchSegments,
    ) {
        val segmentModifier = if (stretchSegments) Modifier.weight(1f) else Modifier
        if (editions.size > 1) {
            TvAnchoredSelectorMenu(
                modifier = segmentModifier,
                icon = Icons.Filled.Layers,
                label = "Edition",
                value = currentEdition?.label ?: "Standard",
                compactValue = currentEdition?.label ?: "Standard",
                options = editionOptions,
                interactive = selectorIsInteractive(editions.size),
                triggerStyle = TvSelectorTriggerStyle.ConnectedSegment,
                groupExpanded = groupExpanded,
                connectedFillWidth = stretchSegments,
                connectedExpandValue = !stretchSegments,
            )
            SelectorDivider()
        }

        TvAnchoredSelectorMenu(
            modifier = segmentModifier,
            icon = Icons.Filled.Tv,
            label = "Version",
            value = TvPlaybackFormatting.versionValueLabel(currentVersion, selectedVersionFileId),
            compactValue = TvPlaybackFormatting.versionCompactLabel(currentVersion),
            options = versionOptions,
            interactive = selectorIsInteractive(scopedVersions.size),
            triggerStyle = TvSelectorTriggerStyle.ConnectedSegment,
            groupExpanded = groupExpanded,
            connectedFillWidth = stretchSegments,
            connectedExpandValue = !stretchSegments,
        )

        if (formattedAudioOptions.isNotEmpty()) {
            SelectorDivider()
            TvAnchoredSelectorMenu(
                modifier = segmentModifier,
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = "Audio",
                value = audioValue,
                compactValue = audioValue.removePrefix("Auto: "),
                options = audioSelectorOptions,
                interactive = selectorIsInteractive(formattedAudioOptions.size),
                triggerStyle = TvSelectorTriggerStyle.ConnectedSegment,
                groupExpanded = groupExpanded,
                connectedFillWidth = stretchSegments,
                connectedExpandValue = !stretchSegments,
            )
        }

        if (formattedSubtitleOptions.isNotEmpty()) {
            SelectorDivider()
            TvAnchoredSelectorMenu(
                modifier = segmentModifier,
                icon = Icons.AutoMirrored.Filled.Chat,
                label = "Subtitles",
                value = "Subtitles $subtitleValue",
                compactValue = compactSubtitleSelectorValue(subtitleValue),
                options = subtitleSelectorOptions,
                interactive = selectorIsInteractive(formattedSubtitleOptions.size),
                triggerStyle = TvSelectorTriggerStyle.ConnectedSegment,
                groupExpanded = groupExpanded,
                connectedFillWidth = stretchSegments,
                connectedExpandValue = !stretchSegments,
            )
        }
    }
}

/** The one chrome shell shared by both the live selector and its loading state. */
@Composable
private fun PlaybackSelectorCapsule(
    modifier: Modifier = Modifier,
    stretchContent: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = (if (stretchContent) Modifier.fillMaxWidth() else Modifier)
                .height(25.dp)
                .background(Color.Black.copy(alpha = 0.26f), CircleShape)
                .border(0.75.dp, Color.White.copy(alpha = 0.42f), CircleShape)
                .padding(1.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * Non-focusable loading state shown while a newly focused episode's playback
 * detail resolves. It deliberately uses [PlaybackSelectorCapsule], so moving
 * across episodes can change the value but never flash a rectangular control.
 */
@Composable
internal fun TvVersionPillPlaceholder(modifier: Modifier = Modifier) {
    PlaybackSelectorCapsule(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .height(23.dp)
                .clip(CircleShape)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Tv,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Version",
                color = Color.White.copy(alpha = 0.75f),
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SelectorDivider() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .width(0.5.dp)
            .height(17.dp)
            .background(Color.White.copy(alpha = 0.22f)),
    )
}

internal fun compactSubtitleSelectorValue(value: String): String = value
    .removePrefix("Auto: ")
    .removePrefix("Auto - ")
    .substringBefore(" · ")
