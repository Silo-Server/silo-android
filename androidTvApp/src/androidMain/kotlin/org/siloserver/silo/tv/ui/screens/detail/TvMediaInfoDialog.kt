package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import java.util.Locale
import kotlinx.coroutines.launch

data class TvMediaInfoTrackSummary(
    val primary: String,
    val secondary: String? = null,
)

internal fun tvMediaInfoAudioSummary(track: AudioTrack): TvMediaInfoTrackSummary {
    val primary = normalizedHumanTitle(track.title)
        ?: languageDisplayName(track.language)
        ?: "Audio Track ${track.index}"
    val secondary = listOfNotNull(
        languageDisplayName(track.language)?.takeUnless { it.equals(primary, ignoreCase = true) },
        track.channelLayout?.trim()?.takeIf { it.isNotBlank() } ?: channelLabel(track.channels),
        codecLabel(track.codec),
        if (track.isDefault) "Default" else null,
    ).distinct().joinToString(" · ").ifBlank { null }
    return TvMediaInfoTrackSummary(primary = primary, secondary = secondary)
}

internal fun tvMediaInfoSubtitleSummary(track: SubtitleTrack): TvMediaInfoTrackSummary {
    val rawTitle = track.title?.trim()
    val primary = normalizedHumanTitle(rawTitle)
        ?: languageDisplayName(track.language)
        ?: "Subtitle Track ${track.index}"
    val secondary = listOfNotNull(
        codecLabel(track.codec),
        if (track.isDefault) "Default" else null,
        if (track.forced) "Forced" else null,
        if (subtitleLabelIndicatesHearingImpaired(rawTitle)) "SDH" else null,
        if (track.external) "External" else null,
    ).distinct().joinToString(" · ").ifBlank { null }
    return TvMediaInfoTrackSummary(primary = primary, secondary = secondary)
}

/**
 * Read-only Media Info panel for TV — the 10-foot equivalent of the phone
 * MediaInfoSheet. Shows resolution / codecs / HDR / container / size plus the
 * audio and subtitle track lists for each [FileVersion]. Opened from the detail
 * "More" menu. Focusable so Back/Escape dismisses it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvMediaInfoDialog(
    versions: List<FileVersion>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    val canScrollBackward = scrollState.value > 0
    val canScrollForward = scrollState.value < scrollState.maxValue
    // Window-level Popup so it overlays the whole screen (not the hero action
    // slot it's called from) and Back is intercepted via dismissOnBackPress
    // before the detail screen's parent BackHandler.
    Popup(
        alignment = Alignment.CenterEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true),
    ) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.36f)
            .fillMaxHeight(0.88f)
            .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(0.6.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
            .onPreviewKeyEvent { ev ->
                when {
                    ev.type == KeyEventType.KeyUp && (ev.key == Key.Back || ev.key == Key.Escape) -> {
                        onDismiss()
                        true
                    }
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown -> {
                        scrollScope.launch {
                            scrollState.animateScrollTo((scrollState.value + 180).coerceAtMost(scrollState.maxValue))
                        }
                        true
                    }
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp -> {
                        scrollScope.launch {
                            scrollState.animateScrollTo((scrollState.value - 180).coerceAtLeast(0))
                        }
                        true
                    }
                    else -> false
                }
            }
            .then(rememberTvDialogInitialFocus(focus)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(1f)
                .verticalScroll(scrollState)
                .focusRequester(focus)
                .focusable()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = "Media info",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (versions.isEmpty()) {
                Text(
                    text = "No media details available.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            versions.forEachIndexed { index, version ->
                if (versions.size > 1) {
                    Text(
                        text = version.fileName ?: "Version ${index + 1}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = if (index > 0) 12.dp else 0.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InfoRow("Resolution", version.resolution ?: "Unknown")
                InfoRow("Video codec", version.codecVideo ?: "Unknown")
                InfoRow("Audio codec", version.codecAudio ?: "Unknown")
                if (version.hdr) InfoRow("HDR", "Yes")
                version.container?.let { InfoRow("Container", it) }
                if (version.fileSize > 0) InfoRow("Size", formatBytes(version.fileSize))
                if (version.bitrate > 0) InfoRow("Bitrate", "${version.bitrate / 1000} kbps")

                version.audioTracks?.takeIf { it.isNotEmpty() }?.let { tracks ->
                    SectionLabel("Audio tracks")
                    tracks.forEach { t ->
                        TrackSummaryRow(tvMediaInfoAudioSummary(t))
                    }
                }
                version.subtitleTracks?.takeIf { it.isNotEmpty() }?.let { tracks ->
                    SectionLabel("Subtitle tracks")
                    tracks.forEach { t ->
                        TrackSummaryRow(tvMediaInfoSubtitleSummary(t))
                    }
                }
            }
        }
        MediaInfoScrollAffordance(
            canScrollBackward = canScrollBackward,
            canScrollForward = canScrollForward,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
        )
    }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 14.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrackSummaryRow(summary: TvMediaInfoTrackSummary) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = "• ${summary.primary}",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        summary.secondary?.takeIf { it.isNotBlank() }?.let { secondary ->
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun MediaInfoScrollAffordance(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.52f))
                .alpha(if (canScrollBackward) 1f else 0.20f),
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.52f))
                .alpha(if (canScrollForward) 1f else 0.20f),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "%.2f GB".format(gb)
    val mb = bytes / 1_000_000.0
    return "%.0f MB".format(mb)
}

private fun normalizedHumanTitle(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (looksLikeReleaseFileName(trimmed)) return null
    return trimmed
        .replace(Regex("\\s*\\((sdh|forced|default)\\)\\s*", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun looksLikeReleaseFileName(value: String): Boolean {
    val lower = value.lowercase(Locale.ROOT)
    if (lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass") ||
        lower.endsWith(".ssa") || lower.endsWith(".mkv") || lower.endsWith(".mp4")
    ) {
        return true
    }
    return listOf("bluray", "webrip", "web-dl", "hdtv", "remux", "x264", "x265", "1080p", "2160p")
        .any { it in lower } && ('[' in value || '-' in value || '.' in value)
}

private fun languageDisplayName(value: String?): String? {
    val normalized = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it != "und" }
        ?: return null
    val iso2 = when (normalized) {
        "eng" -> "en"
        "fre", "fra" -> "fr"
        "ger", "deu" -> "de"
        "spa" -> "es"
        "jpn" -> "ja"
        "ara" -> "ar"
        "por" -> "pt"
        "ita" -> "it"
        "kor" -> "ko"
        "chi", "zho" -> "zh"
        "rus" -> "ru"
        else -> normalized
    }
    val display = Locale.forLanguageTag(iso2).getDisplayLanguage(Locale.ENGLISH)
        .takeIf { it.isNotBlank() && !it.equals(iso2, ignoreCase = true) }
        ?: iso2.uppercase(Locale.ROOT)
    return display.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private fun codecLabel(value: String?): String? {
    val codec = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
    return when (codec) {
        "hdmv_pgs_subtitle", "pgs" -> "PGS"
        "subrip", "srt" -> "SRT"
        "webvtt", "vtt" -> "VTT"
        "ass", "ssa" -> codec.uppercase(Locale.ROOT)
        "eac3" -> "EAC3"
        "aac" -> "AAC"
        "dts", "dca" -> "DTS"
        "truehd" -> "TRUEHD"
        "ac3" -> "AC3"
        "h264" -> "H.264"
        "hevc", "h265" -> "HEVC"
        else -> codec.uppercase(Locale.ROOT)
    }
}

private fun channelLabel(channels: Int?): String? = when (channels) {
    null, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${channels}ch"
}
