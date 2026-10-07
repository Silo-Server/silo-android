package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.model.playback.PlaybackQualityOption
import org.siloserver.silo.model.playback.activePlaybackQualityId

/**
 * The [menu] row the committed preference selects, judged against what the
 * plan actually delivers, so a stored "1080p" that a bitrate cap held to
 * 720p marks the 720p entry.
 */
internal fun PlayerViewModel.PlayerUiState.activeQualityId(menu: List<PlaybackQualityOption>): String? =
    activePlaybackQualityId(menu, committedQualityPreference, playbackPlan?.effectiveRecipe)

/**
 * Bottom sheet listing the plan's Quality menu: Auto, then the server's
 * entries in its order, each with its bitrate and a check on the active one.
 * A pick re-plans the same title on the server. The title's separate files
 * are the Version sheet, [VersionSelector].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQualitySheet(
    options: List<PlaybackQualityOption>,
    activeId: String?,
    // A Watch Party keeps every member on the room's file; quality still
    // applies to this device's stream.
    inRoom: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    tabletopPaneHeight: Dp? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    PlayerModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard)
                .padding(bottom = 24.dp),
        ) {
            PlayerSheetHeader(title = "Quality", onDismiss = onDismiss)
            PlayerSheetDivider()
            if (inRoom) {
                Text(
                    text = "Watch Party keeps everyone on the same version. " +
                        "You can adjust your streaming quality below.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            LazyColumn {
                items(options, key = { it.id }, contentType = { "quality-option" }) { option ->
                    PlaybackQualityRow(
                        option = option,
                        isSelected = option.id == activeId,
                        onClick = {
                            onSelect(option.id)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** Name on the left; bitrate and the selected check on the right. */
@Composable
private fun PlaybackQualityRow(
    option: PlaybackQualityOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { selected = isSelected }
            .heightIn(min = 46.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = option.name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        option.bitrateLabel?.let { bitrate ->
            Text(
                text = bitrate,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(modifier = Modifier.size(20.dp))
        }
    }
}
