package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.tv.ui.components.TvAnchoredSelectorMenu
import org.siloserver.silo.tv.ui.components.TvSelectorOption

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

@Composable
fun TvPlaybackSelectorRow(
    versions: List<FileVersion>,
    currentVersion: FileVersion?,
    selectedVersionFileId: Int?,
    selectedAudioTrackIndex: Int?,
    selectedSubtitleTrackIndex: Int?,
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

    // fillMaxWidth + a focus container so a Down press from any top-row control
    // (including the far-right circle toggles) lands on the nearest selector
    // rather than skipping the row — the Compose analogue of Apple's
    // full-width `.focusSection()`.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editions.size > 1) {
            TvAnchoredSelectorMenu(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Layers,
                label = "Edition",
                value = currentEdition?.label ?: "Standard",
                options = editionOptions,
                interactive = selectorIsInteractive(editions.size),
            )
        }

        // Version — tvOS uses the `tv` display glyph, not an HQ badge.
        TvAnchoredSelectorMenu(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Tv,
            label = "Version",
            value = TvPlaybackFormatting.versionShortLabel(currentVersion),
            options = versionOptions,
            interactive = selectorIsInteractive(scopedVersions.size),
        )

        // Audio
        TvAnchoredSelectorMenu(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            label = "Audio",
            value = TvPlaybackFormatting.audioValueLabel(currentVersion, selectedAudioTrackIndex),
            options = audioSelectorOptions,
            interactive = selectorIsInteractive(formattedAudioOptions.size),
        )

        // Subtitles — tvOS uses `captions.bubble`; Chat (bubble with text
        // lines) is the closest Material glyph, and reads as subtitles rather
        // than the narrower CC (closed captions).
        TvAnchoredSelectorMenu(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.Chat,
            label = "Subtitles",
            value = TvPlaybackFormatting.subtitleValueLabel(
                currentVersion,
                selectedSubtitleTrackIndex,
                autoContext = TvPlaybackFormatting.SubtitleAutoContext(
                    preferredLanguage = preferredSubtitleLanguage,
                    mode = subtitleMode,
                    showForced = showForcedSubtitles,
                    audioLanguage = TvPlaybackFormatting.resolvedAudioLanguage(
                        currentVersion,
                        selectedAudioTrackIndex,
                    ),
                ),
            ),
            options = subtitleSelectorOptions,
            interactive = selectorIsInteractive(formattedSubtitleOptions.size),
        )
    }
}
