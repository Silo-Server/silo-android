package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.player.formatSubtitleTrackDisplayLabel

/** Combined audio and subtitle picker with adaptive phone and foldable layouts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksSheet(
    isVisible: Boolean,
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    subtitles: List<PlayerSubtitleInfo>,
    selectedSubtitleIndex: Int,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onDismiss: () -> Unit,
    showSearchAction: Boolean = false,
    showTranslateAction: Boolean = false,
    onSearchSubtitles: () -> Unit = {},
    onTranslateWithAi: () -> Unit = {},
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSheet = { scope.dismissPlayerSheet(sheetState, onDismiss) }
    val selectAudioAndDismiss: (Int) -> Unit = { index ->
        onSelectAudio(index)
        dismissSheet()
    }
    val selectSubtitleAndDismiss: (Int) -> Unit = { index ->
        onSelectSubtitle(index)
        dismissSheet()
    }
    val openSubtitleSearch = {
        scope.dismissPlayerSheet(sheetState, onDismiss, onSearchSubtitles)
    }
    val openAiTranslate = {
        scope.dismissPlayerSheet(sheetState, onDismiss, onTranslateWithAi)
    }

    LaunchedEffect(isVisible) {
        if (isVisible) sheetState.show()
    }

    PlayerModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard),
        ) {
            PlayerSheetHeader(
                title = "Audio & Subtitles",
                subtitle = "Choose language and accessibility tracks",
                onDismiss = dismissSheet,
            )

            if (tabletopPaneHeight != null && audioTracks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AudioTrackCard(
                        audioTracks = audioTracks,
                        selectedAudioIndex = selectedAudioIndex,
                        onSelect = selectAudioAndDismiss,
                        scrollContent = true,
                        modifier = Modifier
                            .weight(0.44f)
                            .fillMaxHeight(),
                    )
                    SubtitleTrackCard(
                        subtitles = subtitles,
                        selectedSubtitleIndex = selectedSubtitleIndex,
                        onSelect = selectSubtitleAndDismiss,
                        showSearchAction = showSearchAction,
                        showTranslateAction = showTranslateAction,
                        onSearchSubtitles = openSubtitleSearch,
                        onTranslateWithAi = openAiTranslate,
                        scrollContent = true,
                        modifier = Modifier
                            .weight(0.56f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = playerSheetHorizontalPadding(tabletopPaneHeight),
                            end = playerSheetHorizontalPadding(tabletopPaneHeight),
                            top = 8.dp,
                            bottom = 24.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (audioTracks.isNotEmpty()) {
                        AudioTrackCard(
                            audioTracks = audioTracks,
                            selectedAudioIndex = selectedAudioIndex,
                            onSelect = selectAudioAndDismiss,
                            scrollContent = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SubtitleTrackCard(
                        subtitles = subtitles,
                        selectedSubtitleIndex = selectedSubtitleIndex,
                        onSelect = selectSubtitleAndDismiss,
                        showSearchAction = showSearchAction,
                        showTranslateAction = showTranslateAction,
                        onSearchSubtitles = openSubtitleSearch,
                        onTranslateWithAi = openAiTranslate,
                        scrollContent = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioTrackCard(
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    onSelect: (Int) -> Unit,
    scrollContent: Boolean,
    modifier: Modifier = Modifier,
) {
    TrackSectionCard(
        title = "Audio",
        detail = trackCountLabel(audioTracks.size),
        icon = Icons.Filled.GraphicEq,
        scrollContent = scrollContent,
        modifier = modifier,
    ) {
        audioTracks.forEachIndexed { index, track ->
            TrackRow(
                label = audioTrackName(track, index),
                attributes = audioTrackAttributes(track),
                isSelected = index == selectedAudioIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun SubtitleTrackCard(
    subtitles: List<PlayerSubtitleInfo>,
    selectedSubtitleIndex: Int,
    onSelect: (Int) -> Unit,
    showSearchAction: Boolean,
    showTranslateAction: Boolean,
    onSearchSubtitles: () -> Unit,
    onTranslateWithAi: () -> Unit,
    scrollContent: Boolean,
    modifier: Modifier = Modifier,
) {
    TrackSectionCard(
        title = "Subtitles",
        detail = trackCountLabel(subtitles.size + 1),
        icon = Icons.Filled.Subtitles,
        scrollContent = scrollContent,
        modifier = modifier,
    ) {
        TrackRow(
            label = "Off",
            attributes = "No subtitles",
            isSelected = selectedSubtitleIndex == -1,
            onClick = { onSelect(-1) },
        )
        subtitles.forEachIndexed { index, subtitle ->
            TrackRow(
                label = subtitleTrackLabel(subtitle, index),
                isSelected = index == selectedSubtitleIndex,
                onClick = { onSelect(index) },
            )
        }
        if (showSearchAction || showTranslateAction) {
            PlayerSheetDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        if (showSearchAction) {
            ActionRow(
                icon = Icons.Filled.Search,
                label = "Find subtitles",
                onClick = onSearchSubtitles,
            )
        }
        if (showTranslateAction) {
            ActionRow(
                icon = Icons.Filled.Translate,
                label = "Translate with AI",
                onClick = onTranslateWithAi,
            )
        }
    }
}

@Composable
private fun TrackSectionCard(
    title: String,
    detail: String,
    icon: ImageVector,
    scrollContent: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PlayerSheetCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.48f),
                    fontSize = 12.sp,
                )
            }
        }
        PlayerSheetDivider()
        Column(
            modifier = if (scrollContent) {
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
            } else {
                Modifier.padding(vertical = 6.dp)
            },
            content = { content() },
        )
    }
}

@Composable
private fun TrackRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    attributes: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) PlayerSheetSelectedColor else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!attributes.isNullOrBlank()) {
                Text(
                    text = attributes,
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun trackCountLabel(count: Int): String = if (count == 1) "1 option" else "$count options"

private fun audioTrackName(track: AudioTrack, index: Int): String =
    listOfNotNull(
        track.title?.takeIf { it.isNotBlank() },
        track.language?.takeIf { it.isNotBlank() }?.uppercase(),
    ).joinToString(" · ").ifBlank { "Audio ${index + 1}" }

private fun audioTrackAttributes(track: AudioTrack): String =
    listOfNotNull(
        track.codec?.takeIf { it.isNotBlank() }?.uppercase(),
        track.channels?.let { "${it}ch" },
    ).joinToString(" · ")

internal fun subtitleTrackLabel(sub: PlayerSubtitleInfo, index: Int): String =
    formatSubtitleTrackDisplayLabel(
        rawLabel = sub.label,
        language = sub.language,
        codecOrMime = sub.codec,
        isForced = sub.forced == true,
        index = index,
    )
