package org.siloserver.silo.android.ui.screens.requests

import org.siloserver.silo.android.ui.components.SiloDropdownMenuItem
import org.siloserver.silo.android.ui.components.SiloDropdownMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloBorder
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceContainer
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.common.requests.RequestColors
import org.siloserver.silo.common.requests.RequestRowCopy
import org.siloserver.silo.common.requests.RequestStageTrack
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.requestPosterUrl

/**
 * One request in a grouped list: poster, title, meta line, the stage track,
 * and a status line. The trailing slot is Open (in the library) or a
 * disclosure chevron. Swipe left or long-press to cancel while
 * the request waits for approval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyRequestRow(
    record: MediaRequest,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onOpenInLibrary: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    showDivider: Boolean = false,
) {
    val progress = RequestProgress.of(record)
    val latestCancel by rememberUpdatedState(onCancel)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) latestCancel?.invoke()
            // The row leaves when the list re-reads; it never dismisses itself.
            false
        },
    )
    var menuOpen by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = onCancel != null && !isBusy,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RequestColors.Rose)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Cancel", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        },
    ) {
        Column(modifier = Modifier.background(SiloSurfaceContainer)) {
            if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 74.dp), color = SiloBorder.copy(alpha = 0.55f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (isBusy) 0.45f else 1f)
                        .combinedClickable(
                            enabled = !isBusy,
                            onClick = onOpen,
                            onLongClick = if (onCancel != null) ({ menuOpen = true }) else null,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RequestRowPoster(record.posterPath)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SiloOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = RequestRowCopy.meta(record, progress),
                            fontSize = 12.sp,
                            color = SiloSecondaryText,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        RequestStageTrack(progress = progress, modifier = Modifier.padding(top = 9.dp))
                        RequestStatusLabel(
                            progress = progress,
                            text = RequestRowCopy.status(record, progress),
                            fontWeight = FontWeight.SemiBold,
                            color = SiloOnSurface,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                when {
                    onOpenInLibrary != null && progress.display == RequestDisplayState.InLibrary ->
                        RequestCapsuleButton(title = "Open", icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = onOpenInLibrary)
                    else -> Text(text = "›", fontSize = 20.sp, color = SiloSecondaryText.copy(alpha = 0.7f))
                }
            }
            SiloDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SiloDropdownMenuItem(
                    text = { Text("Cancel Request", color = RequestColors.Rose) },
                    leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = RequestColors.Rose) },
                    onClick = {
                        menuOpen = false
                        onCancel?.invoke()
                    },
                )
            }
        }
    }
}

private fun Modifier.rowClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    clip(RoundedCornerShape(8.dp)).combinedClickable(enabled = enabled, onClick = onClick)

@Composable
internal fun RequestRowPoster(path: String?) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 69.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SiloSurfaceElevated),
    ) {
        requestPosterUrl(path)?.let { url ->
            ThumbhashImage(
                url = url,
                thumbhash = null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
internal fun RequestCapsuleButton(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(RequestChromeSelectedFill)
            .rowClickable(enabled = true, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = SiloOnSurface, modifier = Modifier.size(14.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
    }
}
