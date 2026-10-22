package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.R
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.PlaybackQualityOption
import org.siloserver.silo.model.playback.activePlaybackQualityId
import org.siloserver.silo.model.playback.reportedQuality

/**
 * The [menu] row the session's preference selects: the entry the server
 * reports the plan delivers, or on older servers the one inferred from what
 * the plan delivers, so a stored "1080p" that a bitrate cap held to 720p
 * marks the 720p entry, and one that plays the source marks no row.
 */
internal fun PlayerViewModel.PlayerUiState.activeQualityId(menu: List<PlaybackQualityOption>): String? =
    activePlaybackQualityId(
        menu,
        qualityPreference,
        playbackPlan?.effectiveRecipe,
        playbackPlan?.sourceFile,
        playbackPlan?.reportedQuality,
    )

/** A quality the viewer picked, else the one the session plays at. */
internal val PlayerViewModel.PlayerUiState.qualityPreference: String?
    get() = committedQualityPreference ?: sessionQualityPreference

/**
 * The plan's Quality menu: Auto, then the server's entries in its order, each
 * with its bitrate and a check on the active one. A pick re-plans the same
 * title on the server. The title's separate files are the Version menu,
 * [VersionSelector].
 */
@Composable
fun PlaybackQualitySheet(
    options: List<PlaybackQualityOption>,
    activeId: String?,
    // Auto plays under a limit learned from buffering; its row says so.
    autoLimitedAfterBuffering: Boolean = false,
    // What the plan plays ("Playing 1080p · 6 Mbps"), then why: in place of
    // the checked row's detail, or above the rows when none is checked. The
    // reason is null on servers that don't say; both are null mid-switch.
    playingLine: String? = null,
    reasonLine: String? = null,
    // A Watch Party keeps every member on the room's file; quality still
    // applies to this device's stream.
    inRoom: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    tabletopPaneHeight: Dp? = null,
) {
    val controller = rememberPlayerMenuController(onDismiss)
    val hasCheckedRow = options.any { it.id == activeId }

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        PlayerPanelHeader(title = "Quality", onClose = { controller.dismiss() })
        PlayerMenuScrollColumn(horizontalPadding = 12.dp) {
            if (inRoom) {
                PlayerFootnote(
                    "Watch Party keeps everyone on the same version. " +
                        "You can adjust your streaming quality below.",
                )
            }
            // No row checked (a saved resolution the source plays under). One
            // footnote, so TalkBack reads both lines together.
            if (!hasCheckedRow) {
                listOfNotNull(playingLine, reasonLine).takeIf { it.isNotEmpty() }
                    ?.let { PlayerFootnote(it.joinToString("\n")) }
            }
            Spacer(Modifier.height(8.dp))
            PlayerGroup {
                val autoLimitedLabel = stringResource(R.string.buffering_quality_auto_limited)
                options.forEachIndexed { index, option ->
                    PlayerCheckRow(
                        label = if (autoLimitedAfterBuffering && option.id == PLAYBACK_QUALITY_AUTO) {
                            autoLimitedLabel
                        } else {
                            option.name
                        },
                        detail = playingLine?.takeIf { option.id == activeId } ?: option.bitrateLabel,
                        note = reasonLine?.takeIf { option.id == activeId },
                        selected = option.id == activeId,
                        onClick = {
                            onSelect(option.id)
                            controller.dismiss()
                        },
                        separator = index > 0,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
