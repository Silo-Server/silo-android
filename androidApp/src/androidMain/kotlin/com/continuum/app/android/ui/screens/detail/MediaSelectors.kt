package com.continuum.app.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.continuum.app.android.ui.theme.DarkOutline
import com.continuum.app.android.ui.theme.DarkSurfaceVariant
import com.continuum.app.model.catalog.AudioTrack
import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.model.catalog.SubtitleTrack

@Composable
fun VersionSelectorButton(
    version: FileVersion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectorChip(
        icon = Icons.Outlined.HighQuality,
        label = "Version",
        value = formatVersionMenuLabel(version),
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun SelectorChip(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, DarkOutline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Color.White,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = "Select",
            modifier = Modifier.size(14.dp),
            tint = Color.White.copy(alpha = 0.62f),
        )
    }
}

// ── Bottom sheet pickers ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionPickerSheet(
    versions: List<FileVersion>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PickerHeader(title = "Select Version")

        LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(versions) { index, version ->
                PickerItem(
                    title = formatVersionTitle(version),
                    subtitle = formatVersionSubtitle(version),
                    badges = buildVersionBadges(version),
                    isSelected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
                if (index < versions.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPickerSheet(
    tracks: List<AudioTrack>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PickerHeader(title = "Select Audio Track")

        LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(tracks) { index, track ->
                PickerItem(
                    title = formatAudioTitle(track, index),
                    subtitle = formatAudioSubtitle(track),
                    badges = buildAudioBadges(track),
                    isSelected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
                if (index < tracks.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlePickerSheet(
    tracks: List<SubtitleTrack>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PickerHeader(title = "Select Subtitles")

        LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // Off option
            item {
                PickerItem(
                    title = "Off",
                    subtitle = "No subtitles",
                    badges = emptyList(),
                    isSelected = selectedIndex == -1,
                    onClick = { onSelect(-1) },
                )
                if (tracks.isNotEmpty()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            itemsIndexed(tracks) { index, track ->
                PickerItem(
                    title = formatSubtitleTitle(track, index),
                    subtitle = formatSubtitleSubtitle(track),
                    badges = buildSubtitleBadges(track),
                    isSelected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
                if (index < tracks.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PickerItem(
    title: String,
    subtitle: String,
    badges: List<String>,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
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
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun BadgePill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
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

// ── Label formatting ──────────────────────────────────────────

fun formatVersionMenuLabel(version: FileVersion): String {
    val parts = mutableListOf<String>()
    formatResolutionLabel(version.resolution)?.let { parts.add(it) }
    formatHdrLabel(version)?.let { parts.add(it) }
    formatVideoCodecLabel(version.codecVideo)?.let { parts.add(it) }
    formatPrimaryAudioSummary(version)?.let { parts.add(it) }
    return parts.joinToString(" · ").ifBlank { "Default" }
}

fun formatAudioLabel(track: AudioTrack?): String {
    if (track == null) return "Auto"
    val parts = mutableListOf<String>()
    track.title?.let { parts.add(it) }
        ?: track.language?.uppercase()?.let { parts.add(it) }
    track.channelLayout?.let { parts.add(it) }
        ?: track.channels?.let { parts.add("${it}ch") }
    return parts.joinToString(" \u00B7 ").ifBlank { "Track 1" }
}

fun formatSubtitleLabel(track: SubtitleTrack?): String {
    if (track == null) return "Off"
    val parts = mutableListOf<String>()
    track.title?.let { parts.add(it) }
        ?: track.language?.uppercase()?.let { parts.add(it) }
    if (track.forced) parts.add("Forced")
    return parts.joinToString(" \u00B7 ").ifBlank { "Track" }
}

private fun formatVersionTitle(version: FileVersion): String {
    val parts = mutableListOf<String>()
    formatResolutionLabel(version.resolution)?.let { parts.add(it) }
    formatHdrLabel(version)?.let { parts.add(it) }
    return parts.joinToString(" · ").ifBlank { "Unknown" }
}

private fun formatVersionSubtitle(version: FileVersion): String {
    val parts = mutableListOf<String>()
    version.codecVideo?.let { parts.add("Video: ${it.uppercase()}") }
    version.codecAudio?.let { parts.add("Audio: ${it.uppercase()}") }
    if (version.fileSize > 0) {
        val sizeMB = version.fileSize / (1024.0 * 1024.0)
        val sizeStr = if (sizeMB >= 1024) "%.1f GB".format(sizeMB / 1024.0) else "%.0f MB".format(sizeMB)
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
        "dolby" in hdrFormat || "dovi" in hdrFormat || "dv" == hdrFormat -> "HDR"
        "hdr10+" in hdrFormat || "hdr10plus" in hdrFormat -> "HDR10+"
        "hdr10" in hdrFormat || "pq" in hdrFormat -> "HDR10"
        "hlg" in hdrFormat -> "HLG"
        else -> "HDR"
    }
}

private fun formatHdrBadge(version: FileVersion): String? = formatHdrLabel(version)

private fun formatPrimaryAudioSummary(version: FileVersion): String? {
    val track = version.audioTracks
        ?.firstOrNull { it.isDefault }
        ?: version.audioTracks?.firstOrNull()

    val codec = formatAudioCodecLabel(track?.codec ?: version.codecAudio)
    val layout = track?.let(::formatCompactAudioLayout)
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

private fun formatCompactAudioLayout(track: AudioTrack): String? {
    val layout = track.channelLayout?.trim()
    val lowered = layout?.lowercase()
    return when {
        lowered == null || lowered.isEmpty() -> when (track.channels) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> track.channels?.let { "${it}ch" }
        }
        "7.1" in lowered -> "7.1"
        "5.1" in lowered -> "5.1"
        "stereo" in lowered || lowered == "2.0" -> "Stereo"
        "mono" in lowered || lowered == "1.0" -> "Mono"
        "atmos" in lowered -> null
        else -> layout
    }
}

private fun formatAudioTitle(track: AudioTrack, index: Int): String {
    return track.title ?: track.language?.uppercase() ?: "Audio ${index + 1}"
}

private fun formatAudioSubtitle(track: AudioTrack): String {
    val parts = mutableListOf<String>()
    track.codec?.uppercase()?.let { parts.add(it) }
    track.channelLayout?.let { parts.add(it) }
        ?: track.channels?.let { parts.add("${it}ch") }
    track.bitrate?.let { br ->
        if (br > 0) parts.add("${br / 1000}kbps")
    }
    return parts.joinToString(" \u00B7 ")
}

private fun buildAudioBadges(track: AudioTrack): List<String> {
    val badges = mutableListOf<String>()
    if (track.isDefault) badges.add("Default")
    return badges
}

private fun formatSubtitleTitle(track: SubtitleTrack, index: Int): String {
    return track.title ?: track.language?.uppercase() ?: "Track ${index + 1}"
}

private fun formatSubtitleSubtitle(track: SubtitleTrack): String {
    val parts = mutableListOf<String>()
    track.codec?.uppercase()?.let { parts.add(it) }
    if (track.external) parts.add("External")
    return parts.joinToString(" \u00B7 ")
}

private fun buildSubtitleBadges(track: SubtitleTrack): List<String> {
    val badges = mutableListOf<String>()
    if (track.forced) badges.add("Forced")
    if (track.isDefault) badges.add("Default")
    return badges
}
