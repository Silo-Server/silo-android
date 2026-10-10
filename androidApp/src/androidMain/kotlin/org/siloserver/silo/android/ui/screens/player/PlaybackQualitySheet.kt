package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * The plan's Quality menu: Auto, then the server's entries in its order, each
 * with its bitrate and a check on the active one. A pick re-plans the same
 * title on the server. The title's separate files are the Version menu,
 * [VersionSelector].
 */
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
    val controller = rememberPlayerMenuController(onDismiss)

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        PlayerPanelHeader(title = "Quality", onClose = { controller.dismiss() })
        PlayerMenuScrollColumn(horizontalPadding = 12.dp) {
            if (inRoom) {
                PlayerFootnote(
                    "Watch Party keeps everyone on the same version. " +
                        "You can adjust your streaming quality below.",
                )
            }
            Spacer(Modifier.height(8.dp))
            PlayerGroup {
                options.forEachIndexed { index, option ->
                    PlayerCheckRow(
                        label = option.name,
                        detail = option.bitrateLabel,
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
