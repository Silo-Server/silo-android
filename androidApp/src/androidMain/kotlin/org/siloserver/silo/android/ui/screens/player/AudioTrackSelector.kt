package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.common.R as CommonR

/**
 * Bottom sheet for selecting an audio track.
 * Displays language, codec, channel layout, and marks the currently selected track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackSelector(
    audioTracks: List<AudioTrack>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val defaultTag = stringResource(CommonR.string.detail_trackDefault)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(CommonR.string.player_tracks_audio),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn {
                itemsIndexed(
                    audioTracks,
                    contentType = { _, _ -> "audio-track" },
                ) { index, track ->
                    val label = track.title
                        ?: track.language?.uppercase()
                        ?: stringResource(CommonR.string.player_tracks_audioNumbered, index + 1)

                    val detail = buildString {
                        track.codec?.uppercase()?.let { append(it) }
                        track.channelLayout?.let {
                            if (isNotEmpty()) append(" - ")
                            append(it)
                        } ?: track.channels?.let { ch ->
                            if (isNotEmpty()) append(" - ")
                            append("${ch}ch")
                        }
                        if (track.isDefault) {
                            if (isNotEmpty()) append(" - ")
                            append(defaultTag)
                        }
                    }.ifEmpty { null }

                    AudioOptionRow(
                        label = label,
                        detail = detail,
                        isSelected = selectedIndex == index,
                        onClick = {
                            onSelect(index)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioOptionRow(
    label: String,
    detail: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(CommonR.string.player_menu_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(modifier = Modifier.width(24.dp))
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
