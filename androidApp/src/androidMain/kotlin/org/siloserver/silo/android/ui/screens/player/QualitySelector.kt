package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.android.ui.util.formatBytes
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.catalog.FileVersion

/**
 * Bottom sheet for selecting a file version (quality/resolution).
 * Shows resolution, codec, HDR badge, and file size for each version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelector(
    versions: List<FileVersion>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
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
                // Cap below the top edge + keep content flings from
                // dismissing the sheet — see PlayerSheetSupport.
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard)
                .padding(bottom = 32.dp),
        ) {
            PlayerSheetHeader(
                title = "Quality",
                subtitle = "Choose a source version",
                onDismiss = onDismiss,
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn {
                itemsIndexed(
                    versions,
                    contentType = { _, _ -> "quality-version" },
                ) { index, version ->
                    val label = buildString {
                        version.resolution?.let { append(it) } ?: append("Unknown")
                        if (version.hdr) append(" HDR")
                    }

                    val detail = buildString {
                        version.codecVideo?.uppercase()?.let { append(it) }
                        version.codecAudio?.uppercase()?.let {
                            if (isNotEmpty()) append(" + ")
                            append(it)
                        }
                        if (version.fileSize > 0) {
                            if (isNotEmpty()) append(" - ")
                            append(formatBytes(version.fileSize))
                        }
                    }.ifEmpty { null }

                    QualityOptionRow(
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
private fun QualityOptionRow(
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
                contentDescription = "Selected",
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
