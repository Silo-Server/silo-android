package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.player.PlayerStatsSnapshot

@Composable
fun PlaybackStatsSheet(
    isVisible: Boolean,
    stats: PlayerStatsSnapshot,
    onDismiss: () -> Unit,
    // Back affordance: hands over to the settings menu (wired in PlayerOverlay).
    onBack: (() -> Unit)? = null,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val controller = rememberPlayerMenuController(onDismiss)

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        PlayerPanelHeader(
            title = stringResource(CommonR.string.player_stats_title),
            onClose = { controller.dismiss() },
            onBack = onBack?.let { back -> { controller.dismiss(then = back) } },
            backDescription = stringResource(CommonR.string.player_menu_backToSettings),
        )
        PlayerMenuScrollColumn(horizontalPadding = 12.dp) {
            PlayerSectionHeader(stringResource(CommonR.string.player_stats_currentStream))
            val rows = stats.mobileStatsRows()
            PlayerGroup {
                if (rows.isEmpty()) {
                    StatsRow(label = stringResource(CommonR.string.player_stats_waiting), value = "", separator = false)
                } else {
                    rows.forEachIndexed { index, (label, value) ->
                        StatsRow(label = label, value = value, separator = index > 0)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String, separator: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .playerGroupSeparator(separator)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = PlayerType.RowDetail.copy(fontSize = PlayerType.Value.fontSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(modifier = Modifier.weight(1.4f)) {
            Text(
                text = value,
                style = PlayerType.Value.copy(color = PlayerChrome.Paper),
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun PlayerStatsSnapshot.mobileStatsRows(): List<Pair<String, String>> = buildList {
    backendDisplayName?.let { add(stringResource(CommonR.string.player_stats_backend) to it) }
    backendRoute?.let { add(stringResource(CommonR.string.player_hud_route) to it) }
    subtitleRendering?.let { add(stringResource(CommonR.string.player_stats_subtitles) to it) }
    // The view model stores "Yes" or "No"; only the shown word is translated.
    hardContainers?.let {
        val value = when (it) {
            "Yes" -> stringResource(CommonR.string.player_stats_yes)
            "No" -> stringResource(CommonR.string.player_stats_no)
            else -> it
        }
        add(stringResource(CommonR.string.player_stats_hardContainers) to value)
    }
    videoCodec?.let { add(stringResource(CommonR.string.player_stats_videoCodec) to it) }
    resolution?.let { add(stringResource(CommonR.string.player_stats_resolution) to it) }
    frameRate?.let {
        add(stringResource(CommonR.string.player_stats_frameRate) to String.format(java.util.Locale.US, "%.3f fps", it))
    }
    hdrMode?.let { add(stringResource(CommonR.string.player_stats_hdrMode) to it) }
    videoDecoderName?.let { add(stringResource(CommonR.string.player_stats_videoDecoder) to it) }
    audioCodec?.let { add(stringResource(CommonR.string.player_stats_audioCodec) to it) }
    audioDecoderName?.let { add(stringResource(CommonR.string.player_stats_audioDecoder) to it) }
    // Media3's onBandwidthEstimate value — measured network throughput, not the
    // media bitrate. Labelling it "Bitrate" reads as a ~19 Mbps stream claiming
    // 151 Mbps on a fast LAN.
    bitrateBps?.let { add(stringResource(CommonR.string.player_stats_estimatedBandwidth) to formatStatsBitrate(it)) }
    if (droppedFrames > 0) add(stringResource(CommonR.string.player_stats_droppedFrames) to droppedFrames.toString())
    if (audioUnderruns > 0) add(stringResource(CommonR.string.player_stats_audioUnderruns) to audioUnderruns.toString())
}

internal fun formatStatsBitrate(bps: Long): String = when {
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> String.format(java.util.Locale.US, "%.0f Kbps", bps / 1_000.0)
    else -> "$bps bps"
}
