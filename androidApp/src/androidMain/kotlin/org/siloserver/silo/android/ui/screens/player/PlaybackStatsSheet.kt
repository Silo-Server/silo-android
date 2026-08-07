package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.player.PlayerStatsSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackStatsSheet(
    isVisible: Boolean,
    stats: PlayerStatsSnapshot,
    onDismiss: () -> Unit,
    // Gear-submenu back affordance: dismisses this sheet and reopens the
    // parent settings sheet (wired in PlayerOverlay).
    onBack: (() -> Unit)? = null,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSheet = { scope.dismissPlayerSheet(sheetState, onDismiss) }

    LaunchedEffect(isVisible) {
        if (isVisible) sheetState.show()
    }

    PlayerModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Cap below the top edge + keep content flings from
                // dismissing the sheet — see PlayerSheetSupport.
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF111827).copy(alpha = 0.97f),
                            Color.Black.copy(alpha = 0.94f),
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            ) {
                PlayerSheetHeader(
                    title = "Playback Stats",
                    onBack = onBack?.let { back ->
                        { scope.dismissPlayerSheet(sheetState, back) }
                    },
                    onDismiss = dismissSheet,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Current stream",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(18.dp))

                val rows = stats.mobileStatsRows()
                if (rows.isEmpty()) {
                    Text(
                        text = "Waiting for player data",
                        color = Color.White.copy(alpha = 0.66f),
                        fontSize = 15.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEach { (label, value) ->
                            StatsRow(label = label, value = value)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun PlayerStatsSnapshot.mobileStatsRows(): List<Pair<String, String>> = buildList {
    backendDisplayName?.let { add("Backend" to it) }
    backendRoute?.let { add("Route" to it) }
    subtitleRendering?.let { add("Subtitles" to it) }
    hardContainers?.let { add("Hard containers" to it) }
    videoCodec?.let { add("Video codec" to it) }
    resolution?.let { add("Resolution" to it) }
    frameRate?.let { add("Frame rate" to String.format(java.util.Locale.US, "%.3f fps", it)) }
    hdrMode?.let { add("HDR mode" to it) }
    videoDecoderName?.let { add("Video decoder" to it) }
    audioCodec?.let { add("Audio codec" to it) }
    audioDecoderName?.let { add("Audio decoder" to it) }
    // Media3's onBandwidthEstimate value — measured network throughput, not the
    // media bitrate. Labelling it "Bitrate" reads as a ~19 Mbps stream claiming
    // 151 Mbps on a fast LAN.
    bitrateBps?.let { add("Estimated bandwidth" to formatStatsBitrate(it)) }
    if (droppedFrames > 0) add("Dropped frames" to droppedFrames.toString())
    if (audioUnderruns > 0) add("Audio underruns" to audioUnderruns.toString())
}

internal fun formatStatsBitrate(bps: Long): String = when {
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> String.format(java.util.Locale.US, "%.0f Kbps", bps / 1_000.0)
    else -> "$bps bps"
}
