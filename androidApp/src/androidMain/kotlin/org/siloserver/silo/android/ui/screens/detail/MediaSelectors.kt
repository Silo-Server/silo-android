package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.localizedResources
import android.content.res.Resources
import org.siloserver.silo.model.playback.matchesSignature
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.DarkOutline
import org.siloserver.silo.android.ui.theme.DarkSurface
import org.siloserver.silo.android.ui.theme.DarkSurfaceVariant
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.editionLabel
import org.siloserver.silo.model.catalog.playbackEditions
import org.siloserver.silo.model.catalog.hasEditionChoices
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.isUnplayableSubtitle
import org.siloserver.silo.player.DolbyVisionDetection
import org.siloserver.silo.player.trackLanguageDisplayName
import org.siloserver.silo.player.trackLanguageEnglishName
import org.siloserver.silo.playback.selectPlaybackVersion

/**
 * One iOS-style row inside [PlaybackSelectorCard]. Icon + group label lead,
 * while the current value and disclosure chevron trail. Rows deliberately do
 * not draw their own boxes; the three controls share one rounded container.
 *
 * [interactive] = false when the group holds a single real choice (one
 * version, one audio track, one subtitle track), mirroring Apple's
 * `DetailPlaybackFormatting.shouldEnable*Selector`. The row then keeps its
 * box and its value but drops the chevron and the tap target: a picker whose
 * only outcome is the value already printed is a dead end, not a choice. The
 * "Auto"/"Off" rows the sheets prepend are pseudo-entries and do not count.
 */
@Composable
fun TrackSelectorRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (interactive) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.CenterStart) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = Color.White.copy(alpha = 0.55f),
            )
        }
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.72f),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        if (interactive) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(CommonR.string.detail_picker_select),
                modifier = Modifier.size(11.dp),
                tint = Color.White.copy(alpha = 0.35f),
            )
        }
    }
}

/**
 * The common Version / Audio / Subtitles control: one 12dp rounded card with
 * shared hairline dividers, matching `PhonePlaybackSelectorRow` on iOS.
 */
@Composable
fun PlaybackSelectorCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 133.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
        content = content,
    )
}

@Composable
fun PlaybackSelectorDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 30.dp),
        thickness = 0.5.dp,
        color = Color.White.copy(alpha = 0.08f),
    )
}

/**
 * Loading state with the exact same three 44dp rows as [PlaybackSelectorCard].
 * Keeping the complete card mounted while a newly centred episode hydrates
 * prevents the season controls and episode rail from moving vertically.
 */
@Composable
fun PlaybackSelectorSkeleton(modifier: Modifier = Modifier) {
    val labelWidths = listOf(52.dp, 42.dp, 64.dp)
    val valueWidths = listOf(128.dp, 154.dp, 48.dp)

    PlaybackSelectorCard(modifier = modifier) {
        repeat(3) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SelectorSkeletonShape(width = 13.dp, height = 13.dp)
                SelectorSkeletonShape(width = labelWidths[index], height = 12.dp)
                Spacer(modifier = Modifier.weight(1f))
                SelectorSkeletonShape(width = valueWidths[index], height = 12.dp)
            }
            if (index < 2) PlaybackSelectorDivider()
        }
    }
}

@Composable
private fun SelectorSkeletonShape(width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.10f)),
    )
}

/** Edition and quality selections retain the original detail-list file identity. */
@Composable
fun EditionVersionSelectorRows(
    detail: ItemDetail,
    selectedVersionIndex: Int,
    isAutoVersion: Boolean,
    onVersionSelected: (Int?) -> Unit,
    /** The playback quality preference; picking an edition pins its Auto file. */
    preferredQuality: String? = null,
) {
    var showEditionPicker by remember(detail.contentId) { mutableStateOf(false) }
    var showVersionPicker by remember(detail.contentId) { mutableStateOf(false) }
    val selectedVersion = detail.versions.getOrNull(selectedVersionIndex)
    val editions = playbackEditions(detail.versions, detail.playbackVariants)
    val showEditions = editions.hasEditionChoices()
    val currentEdition = editions.firstOrNull { selectedVersion?.fileId in it.fileIds }
        ?: editions.firstOrNull()
    val versions = if (showEditions) currentEdition?.versions.orEmpty() else detail.versions
    val resources = localizedResources()

    if (showEditions) {
        TrackSelectorRow(
            icon = Icons.Outlined.Layers,
            label = stringResource(CommonR.string.detail_edition),
            value = currentEdition?.label.orEmpty(),
            onClick = { showEditionPicker = true },
        )
        PlaybackSelectorDivider()
    }
    TrackSelectorRow(
        icon = Icons.Outlined.HighQuality,
        label = stringResource(CommonR.string.detail_version),
        value = formatVersionValueLabel(resources, selectedVersion, isAutoVersion),
        onClick = { showVersionPicker = true },
        interactive = versions.size > 1,
    )
    if (showEditionPicker) {
        PickerSheetScaffold(
            title = stringResource(CommonR.string.detail_picker_selectEdition),
            onDismiss = { showEditionPicker = false },
        ) {
            itemsIndexed(editions) { index, edition ->
                // Picking an edition turns Auto off and pins the file Auto
                // would choose within it, as on Apple and Roku.
                val editionVersion = selectPlaybackVersion(edition.versions, null, preferredQuality)
                PickerItem(
                    title = edition.label,
                    subtitle = formatVersionMenuLabel(resources, editionVersion),
                    badges = emptyList(),
                    isSelected = edition.id == currentEdition?.id,
                    onClick = {
                        detail.versions.indexOfFirst { it.fileId == editionVersion.fileId }
                            .takeIf { it >= 0 }?.let(onVersionSelected)
                        showEditionPicker = false
                    },
                )
                if (index < editions.lastIndex) HorizontalDivider(color = DarkOutline)
            }
        }
    }
    if (showVersionPicker) {
        VersionPickerSheet(
            versions = versions,
            selectedIndex = versions.indexOfFirst { it.fileId == selectedVersion?.fileId }
                .takeIf { it >= 0 && !isAutoVersion },
            onSelect = { index ->
                if (index == null) {
                    onVersionSelected(null)
                } else {
                    detail.versions.indexOfFirst { it.fileId == versions[index].fileId }
                        .takeIf { it >= 0 }?.let(onVersionSelected)
                }
                showVersionPicker = false
            },
            onDismiss = { showVersionPicker = false },
        )
    }
}

// ── Bottom sheet pickers ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionPickerSheet(
    versions: List<FileVersion>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
    allowAuto: Boolean = true,
) {
    val resources = localizedResources()
    PickerSheetScaffold(title = stringResource(CommonR.string.detail_picker_selectVersion), onDismiss = onDismiss) {
        if (allowAuto) item {
            PickerItem(
                title = stringResource(CommonR.string.detail_auto),
                subtitle = stringResource(CommonR.string.detail_picker_bestVersion),
                badges = emptyList(),
                isSelected = selectedIndex == null,
                onClick = { onSelect(null) },
            )
            if (versions.isNotEmpty()) {
                HorizontalDivider(color = DarkOutline)
            }
        }

        itemsIndexed(versions) { index, version ->
            PickerItem(
                title = formatVersionTitle(resources, version),
                subtitle = formatVersionSubtitle(resources, version),
                badges = buildVersionBadges(version),
                isSelected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
            if (index < versions.lastIndex) {
                HorizontalDivider(color = DarkOutline)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPickerSheet(
    tracks: List<AudioTrack>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = localizedResources()
    PickerSheetScaffold(title = stringResource(CommonR.string.detail_picker_selectAudioTrack), onDismiss = onDismiss) {
        item {
            PickerItem(
                title = stringResource(CommonR.string.detail_auto),
                subtitle = stringResource(CommonR.string.detail_picker_fileDefaultTrack),
                badges = emptyList(),
                isSelected = selectedIndex == null,
                onClick = { onSelect(null) },
            )
            if (tracks.isNotEmpty()) {
                HorizontalDivider(color = DarkOutline)
            }
        }

        itemsIndexed(tracks) { index, track ->
            PickerItem(
                title = formatAudioTitle(resources, track, index),
                subtitle = formatAudioSubtitle(resources, track),
                badges = buildAudioBadges(resources, track),
                isSelected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
            if (index < tracks.lastIndex) {
                HorizontalDivider(color = DarkOutline)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlePickerSheet(
    tracks: List<SubtitleTrack>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = localizedResources()
    PickerSheetScaffold(title = stringResource(CommonR.string.detail_picker_selectSubtitles), onDismiss = onDismiss) {
        item {
            PickerItem(
                title = stringResource(CommonR.string.detail_auto),
                subtitle = stringResource(CommonR.string.detail_picker_fileDefaultTrack),
                badges = emptyList(),
                isSelected = selectedIndex == null,
                onClick = { onSelect(null) },
            )
            HorizontalDivider(color = DarkOutline)
        }

        // Off option
        item {
            PickerItem(
                title = stringResource(CommonR.string.detail_off),
                subtitle = stringResource(CommonR.string.detail_picker_noSubtitles),
                badges = emptyList(),
                isSelected = selectedIndex == -1,
                onClick = { onSelect(-1) },
            )
            if (tracks.isNotEmpty()) {
                HorizontalDivider(color = DarkOutline)
            }
        }

        itemsIndexed(tracks) { index, track ->
            // A track the server has no way to show stays listed, disabled.
            val unplayable = track.isUnplayableSubtitle()
            PickerItem(
                title = formatSubtitleTitle(resources, track, index),
                subtitle = if (unplayable) {
                    stringResource(CommonR.string.subtitle_trackUnsupportedFormat)
                } else {
                    formatSubtitleSubtitle(resources, track)
                },
                badges = buildSubtitleBadges(resources, track),
                isSelected = index == selectedIndex,
                enabled = !unplayable,
                onClick = { onSelect(index) },
            )
            if (index < tracks.lastIndex) {
                HorizontalDivider(color = DarkOutline)
            }
        }
    }
}

/**
 * Shared bottom-sheet chrome for the three playback pickers, matching the
 * detail page's card language: a plain header followed by a bordered,
 * rounded options card (same DarkSurfaceVariant @0.7 + 1dp DarkOutline
 * treatment as [TrackSelectorRow]) instead of a full-bleed M3 list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DarkSurface,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurfaceVariant.copy(alpha = 0.7f))
                .border(1.dp, DarkOutline, RoundedCornerShape(12.dp)),
        ) {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                content = content,
            )
        }

        // Insets first, then the fixed gap — the other order would clamp the
        // spacer to 16.dp and swallow the nav-bar inset entirely.
        Spacer(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(16.dp),
        )
    }
}

@Composable
private fun PickerItem(
    title: String,
    subtitle: String,
    badges: List<String>,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (isSelected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                badges.forEach { badge ->
                    BadgePill(text = badge)
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = stringResource(CommonR.string.detail_picker_selected),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun BadgePill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(
                Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

// ── Display version ───────────────────────────────────────────

/**
 * Index of the version a video detail page shows for [versions], which is
 * also the version its Download button saves. An explicit pick wins. Auto
 * resolves through the same shared selector as playback (lastFileId →
 * preferred-quality rank → bestAvailable), so what Auto previews is the file
 * playback and downloads use. While the quality pref is still loading, only
 * the pref-independent lastFileId is shown, otherwise -1 (the bare "Auto"
 * placeholder), so the page never names a version the arriving pref
 * contradicts. [fallbackIndex] covers an empty list or an unmatched version.
 */
internal fun detailDisplayVersionIndex(
    versions: List<FileVersion>,
    explicitIndex: Int?,
    lastFileId: Int?,
    preferredQuality: String?,
    fallbackIndex: Int,
): Int {
    if (versions.isEmpty()) return fallbackIndex
    // A stale pick (the list shrank after a refresh) names no version rather
    // than a different one, since playback would not use a clamped file.
    if (explicitIndex != null) return explicitIndex.takeIf { it in versions.indices } ?: -1
    if (preferredQuality == null) {
        return lastFileId
            ?.let { id -> versions.indexOfFirst { it.fileId == id }.takeIf { it >= 0 } }
            ?: -1
    }
    val resolvedFileId = selectPlaybackVersion(versions, lastFileId, preferredQuality).fileId
    return versions.indexOfFirst { it.fileId == resolvedFileId }.takeIf { it >= 0 } ?: fallbackIndex
}

// ── Label formatting ──────────────────────────────────────────

/**
 * Selector value for the Video row. Auto (no item-level override)
 * previews what it resolves to — "Auto - 1080p · HEVC" — mirroring the
 * TV client's `versionValueLabel` (QA 2026-07-08 / Apple parity).
 */
fun formatVersionValueLabel(resources: Resources, version: FileVersion?, isAuto: Boolean): String {
    if (version == null) return resources.getString(CommonR.string.detail_auto)
    val label = formatVersionMenuLabel(resources, version)
    return if (isAuto) resources.getString(CommonR.string.detail_autoWithValue, label) else label
}

/**
 * Selector value for the Audio row. Auto (no item-level override) previews
 * what it resolves to with an "Auto - <track>" prefix, mirroring the Video row
 * and the TV client's tvOS-style annotation (Jim 2026-07-09: apply "Auto -" to
 * all three rows for consistency). Auto resolves to the server's effective
 * track ([effectiveIndex] = `FileVersion.effectiveAudioTrackIndex`), else the
 * file's default track, else the first — selected → effective → default →
 * first. [selectedIndex] null = Auto; an explicit pick is named outright.
 */
fun formatAudioValueLabel(
    resources: Resources,
    tracks: List<AudioTrack>,
    selectedIndex: Int?,
    effectiveIndex: Int? = null,
): String {
    if (tracks.isEmpty()) return resources.getString(CommonR.string.detail_unknown)
    val resolved = selectedIndex?.takeIf { it in tracks.indices }
        ?: effectiveIndex?.takeIf { it in tracks.indices }
        ?: tracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 }
        ?: 0
    val label = formatAudioLabel(resources, tracks[resolved])
    return if (selectedIndex == null) resources.getString(CommonR.string.detail_autoWithValue, label) else label
}

/**
 * Selector value for the Subtitles row.
 *
 * [selectedIndex] null = Auto. The player's real subtitle resolver
 * (preferred-language / audio-match / forced / SDH rules in
 * `resolveMobileAutoSubtitleSelection`) decides the concrete track at playback,
 * and it needs profile prefs + the selected audio track that the detail page
 * doesn't have — so a multi-track Auto stays a bare "Auto" (previewing a
 * fabricated track could contradict what actually plays). When Auto can only
 * land on one track (a lone track), we DO know the result, so we show it in the
 * tvOS-style "Auto - <track>" form for consistency with the Video/Audio rows
 * (Jim 2026-07-09). -1 = Off.
 */
fun formatSubtitleValueLabel(
    resources: Resources,
    tracks: List<SubtitleTrack>,
    selectedIndex: Int?,
    seriesSignature: org.siloserver.silo.model.playback.SubtitleTrackSignature? = null,
    seriesOff: Boolean = false,
): String {
    if (selectedIndex == null) {
        // A remembered series choice is what Auto plays, so it can be named:
        // the track its signature finds in this file, or Off.
        if (seriesOff) {
            return resources.getString(CommonR.string.detail_autoWithValue, resources.getString(CommonR.string.detail_off))
        }
        seriesSignature?.let { signature ->
            val candidates = org.siloserver.silo.model.playback.catalogAutoSubtitleCandidates(tracks)
            candidates.indexOfFirst { it.matchesSignature(signature) }
                .takeIf { it >= 0 }
                ?.let {
                    return resources.getString(
                        CommonR.string.detail_autoWithValue,
                        formatSubtitleLabel(resources, tracks[it]),
                    )
                }
        }
        val single = tracks.singleOrNull() ?: return resources.getString(CommonR.string.detail_auto)
        return resources.getString(CommonR.string.detail_autoWithValue, formatSubtitleLabel(resources, single))
    }
    if (selectedIndex == -1) return resources.getString(CommonR.string.detail_off)
    // A stale explicit index (the track list shrank under the selection) still
    // means subtitles ARE requested — surface "On" rather than a bare track
    // label (which reads as an explicit pick) or "Auto" (mirrors iOS).
    val track = tracks.getOrNull(selectedIndex) ?: return resources.getString(CommonR.string.detail_subtitlesOn)
    return formatSubtitleLabel(resources, track)
}

fun formatVersionMenuLabel(resources: Resources, version: FileVersion): String {
    val parts = mutableListOf<String>()
    formatResolutionLabel(version.resolution)?.let { parts.add(it) }
    formatHdrLabel(version)?.let { parts.add(it) }
    formatVideoCodecLabel(version.codecVideo)?.let { parts.add(it) }
    formatPrimaryAudioSummary(resources, version)?.let { parts.add(it) }
    return parts.joinToString(" · ").ifBlank { resources.getString(CommonR.string.detail_defaultVersion) }
}

fun formatAudioLabel(resources: Resources, track: AudioTrack?): String {
    if (track == null) return resources.getString(CommonR.string.detail_auto)
    val parts = mutableListOf<String>()
    trackHeadline(track.language, track.title)?.let { parts.add(it) }
    track.channelLayout?.let { parts.add(it) }
        ?: track.channels?.let { parts.add(resources.getString(CommonR.string.detail_channels, LocaleFormat.integer(it))) }
    return parts.joinToString(" \u00B7 ").ifBlank { resources.getString(CommonR.string.detail_track, LocaleFormat.integer(1)) }
}

fun formatSubtitleLabel(resources: Resources, track: SubtitleTrack?): String {
    if (track == null) return resources.getString(CommonR.string.detail_off)
    val parts = mutableListOf<String>()
    trackHeadline(track.language, track.title)?.let { parts.add(it) }
    if (track.forced) parts.add(resources.getString(CommonR.string.detail_trackForced))
    return parts.joinToString(" \u00B7 ").ifBlank { resources.getString(CommonR.string.detail_trackFallback) }
}

/**
 * How a track is named in the pickers: its language, then what its title
 * adds ("SDH", "Director's Commentary"). Codec names are stripped from the
 * title ("AAC (Advanced Audio Coding)", "Commentary (AC3)", "SUBRIP"), as is
 * the language itself, and the title is dropped only when nothing else
 * remains — as on TV and Apple. Null when neither says anything.
 */
internal fun trackHeadline(language: String?, title: String?): String? {
    val code = language?.trim()?.takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) }
    val name = trackLanguageDisplayName(code, LocaleFormat.interfaceLocale()) ?: code?.uppercase()
    // Titles name the language in English ("English SDH"), whatever the interface shows.
    val extra = title?.let { meaningfulTrackTitle(it, listOfNotNull(name, trackLanguageEnglishName(code), code).distinct()) }
    return listOfNotNull(name, extra).joinToString(" \u00B7 ").takeIf { it.isNotBlank() }
}

/** [title] without codec names or the language's [languageWords], or null when nothing else remains. */
private fun meaningfulTrackTitle(title: String, languageWords: List<String>): String? {
    var text = TECHNICAL_TRACK_TITLE.replace(title, " ")
    languageWords.forEach { word ->
        text = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])").replace(text, " ")
    }
    // Brackets the strip emptied, then separators left at either end or doubled.
    repeat(2) { text = Regex("[(\\[{]\\s*[)\\]}]").replace(text, " ") }
    text = text.replace(Regex("\\s+"), " ").trim().trim('-', '/', '|', ',', ':', '\u00B7', ' ').trim()
    return text.takeIf { it.any(Char::isLetterOrDigit) }
}

private val TECHNICAL_TRACK_TITLE = Regex(
    "(?i)advanced audio coding|(?<![\\p{L}\\p{N}])(?:e-?ac-?3|ac-?3|aac|truehd|dts(?:-hd)?(?: ma)?|flac|opus|mp3|pcm|" +
        "subrip|srt|webvtt|vtt|ass|ssa|pgs|vobsub)(?![\\p{L}\\p{N}])",
)

private fun formatVersionTitle(resources: Resources, version: FileVersion): String {
    val parts = mutableListOf<String>()
    version.editionLabel?.let(parts::add)
    formatResolutionLabel(version.resolution)?.let { parts.add(it) }
    formatHdrLabel(version)?.let { parts.add(it) }
    return parts.joinToString(" · ").ifBlank { resources.getString(CommonR.string.detail_unknown) }
}

private fun formatVersionSubtitle(resources: Resources, version: FileVersion): String {
    val parts = mutableListOf<String>()
    version.codecVideo?.let { parts.add(resources.getString(CommonR.string.detail_versionVideoCodec, it.uppercase())) }
    version.codecAudio?.let { parts.add(resources.getString(CommonR.string.detail_versionAudioCodec, it.uppercase())) }
    if (version.fileSize > 0) {
        val sizeMB = version.fileSize / (1024.0 * 1024.0)
        val sizeStr = if (sizeMB >= 1024) {
            resources.getString(CommonR.string.detail_sizeGb, String.format(LocaleFormat.locale(), "%.1f", sizeMB / 1024.0))
        } else {
            resources.getString(CommonR.string.detail_sizeMb, String.format(LocaleFormat.locale(), "%.0f", sizeMB))
        }
        parts.add(sizeStr)
    }
    return parts.joinToString(" \u00B7 ")
}

private fun buildVersionBadges(version: FileVersion): List<String> {
    val badges = mutableListOf<String>()
    formatHdrBadge(version)?.let { badges.add(it) }
    return badges
}

private fun formatResolutionLabel(raw: String?): String? {
    val value = raw?.trim()?.lowercase() ?: return null
    return when {
        value.contains("2160") || value.contains("4k") -> "4K"
        value.contains("1440") -> "1440p"
        value.contains("1080") -> "1080p"
        value.contains("720") -> "720p"
        value.contains("480") -> "480p"
        else -> raw.uppercase()
    }
}

private fun formatVideoCodecLabel(raw: String?): String? {
    val value = raw?.trim()?.lowercase() ?: return null
    return when {
        value.contains("hevc") || value.contains("h265") -> "HEVC"
        value.contains("av1") -> "AV1"
        value.contains("avc") || value.contains("h264") -> "H.264"
        else -> raw.uppercase()
    }
}

private fun formatAudioCodecLabel(raw: String?): String? {
    val value = raw?.trim()?.lowercase() ?: return null
    return when {
        value.contains("truehd") -> "TrueHD"
        value.contains("dts-hd") && value.contains("ma") -> "DTS-HD MA"
        value.contains("dtshd") && value.contains("ma") -> "DTS-HD MA"
        value.contains("dts-hd") -> "DTS-HD"
        value.contains("dtshd") -> "DTS-HD"
        value.contains("dts:x") || value.contains("dtsx") -> "DTS:X"
        value.contains("eac3") || value.contains("ec-3") -> "EAC3"
        value.contains("ac3") || value.contains("ac-3") -> "AC3"
        value.contains("aac") -> "AAC"
        value.contains("flac") -> "FLAC"
        value.contains("opus") -> "Opus"
        value.contains("mp3") -> "MP3"
        else -> raw.uppercase()
    }
}

private fun formatHdrLabel(version: FileVersion): String? {
    if (!version.hdr) return null

    val hdrFormat = version.videoTracks
        ?.asSequence()
        ?.mapNotNull { it.hdrFormat?.trim() }
        ?.firstOrNull { it.isNotEmpty() }
        ?.lowercase()

    return when {
        hdrFormat == null -> "HDR"
        DolbyVisionDetection.hdrFormatIndicatesDolbyVision(hdrFormat) -> "HDR"
        "hdr10+" in hdrFormat || "hdr10plus" in hdrFormat -> "HDR10+"
        "hdr10" in hdrFormat || "pq" in hdrFormat -> "HDR10"
        "hlg" in hdrFormat -> "HLG"
        else -> "HDR"
    }
}

private fun formatHdrBadge(version: FileVersion): String? = formatHdrLabel(version)

private fun formatPrimaryAudioSummary(resources: Resources, version: FileVersion): String? {
    val track = version.audioTracks
        ?.firstOrNull { it.isDefault }
        ?: version.audioTracks?.firstOrNull()

    val codec = formatAudioCodecLabel(track?.codec ?: version.codecAudio)
    val layout = track?.let { formatCompactAudioLayout(resources, it) }
    val hasAtmos = track?.channelLayout
        ?.lowercase()
        ?.contains("atmos") == true

    return when {
        codec == null && hasAtmos -> null
        codec != null && hasAtmos -> codec
        codec != null && layout != null -> "$codec $layout"
        codec != null -> codec
        layout != null -> layout
        else -> null
    }
}

private fun formatCompactAudioLayout(resources: Resources, track: AudioTrack): String? {
    val layout = track.channelLayout?.trim()
    val lowered = layout?.lowercase()
    return when {
        lowered == null || lowered.isEmpty() -> when (track.channels) {
            1 -> resources.getString(CommonR.string.detail_mono)
            2 -> resources.getString(CommonR.string.detail_stereo)
            6 -> "5.1"
            8 -> "7.1"
            else -> track.channels?.let { resources.getString(CommonR.string.detail_channels, LocaleFormat.integer(it)) }
        }
        "7.1" in lowered -> "7.1"
        "5.1" in lowered -> "5.1"
        "stereo" in lowered || lowered == "2.0" -> resources.getString(CommonR.string.detail_stereo)
        "mono" in lowered || lowered == "1.0" -> resources.getString(CommonR.string.detail_mono)
        "atmos" in lowered -> null
        else -> layout
    }
}

private fun formatAudioTitle(resources: Resources, track: AudioTrack, index: Int): String {
    return trackHeadline(track.language, track.title) ?: track.title
        ?: resources.getString(CommonR.string.detail_audioTrackNumber, LocaleFormat.integer(index + 1))
}

private fun formatAudioSubtitle(resources: Resources, track: AudioTrack): String {
    val parts = mutableListOf<String>()
    track.codec?.uppercase()?.let { parts.add(it) }
    track.channelLayout?.let { parts.add(it) }
        ?: track.channels?.let { parts.add(resources.getString(CommonR.string.detail_channels, LocaleFormat.integer(it))) }
    track.bitrate?.let { br ->
        if (br > 0) parts.add(resources.getString(CommonR.string.detail_bitrateKbps, LocaleFormat.integer(br / 1000)))
    }
    return parts.joinToString(" \u00B7 ")
}

private fun buildAudioBadges(resources: Resources, track: AudioTrack): List<String> {
    val badges = mutableListOf<String>()
    if (track.isDefault) badges.add(resources.getString(CommonR.string.detail_trackDefault))
    return badges
}

private fun formatSubtitleTitle(resources: Resources, track: SubtitleTrack, index: Int): String {
    return trackHeadline(track.language, track.title) ?: track.title
        ?: resources.getString(CommonR.string.detail_track, LocaleFormat.integer(index + 1))
}

private fun formatSubtitleSubtitle(resources: Resources, track: SubtitleTrack): String {
    val parts = mutableListOf<String>()
    track.codec?.uppercase()?.let { parts.add(it) }
    if (track.external) parts.add(resources.getString(CommonR.string.detail_trackExternal))
    return parts.joinToString(" \u00B7 ")
}

private fun buildSubtitleBadges(resources: Resources, track: SubtitleTrack): List<String> {
    val badges = mutableListOf<String>()
    if (track.forced) badges.add(resources.getString(CommonR.string.detail_trackForced))
    if (track.isDefault) badges.add(resources.getString(CommonR.string.detail_trackDefault))
    return badges
}
