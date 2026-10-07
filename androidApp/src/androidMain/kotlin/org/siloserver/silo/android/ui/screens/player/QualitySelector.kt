package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.editionLabel

/**
 * Picks a file version (quality/resolution): resolution and HDR as the title,
 * codecs and size as the detail.
 */
@Composable
fun QualitySelector(
    versions: List<FileVersion>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    tabletopPaneHeight: Dp? = null,
) {
    val controller = rememberPlayerMenuController(onDismiss)

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        PlayerPanelHeader(title = "Quality", onClose = { controller.dismiss() })
        PlayerMenuScrollColumn {
            Spacer(Modifier.height(4.dp))
            versions.forEachIndexed { index, version ->
                PlayerTrackRow(
                    title = buildString {
                        version.editionLabel?.let { append(it).append(" · ") }
                        append(version.resolution ?: "Unknown")
                    },
                    chips = listOfNotNull("HDR".takeIf { version.hdr }),
                    detail = buildList {
                        version.codecVideo?.uppercase()?.let(::add)
                        version.codecAudio?.let { audioCodecDisplayName(it) }?.let(::add)
                        if (version.fileSize > 0) add(formatBytes(version.fileSize))
                    }.joinToString(" · ").ifBlank { null },
                    selected = selectedIndex == index,
                    onClick = {
                        onSelect(index)
                        controller.dismiss()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
