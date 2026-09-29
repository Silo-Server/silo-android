package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.DownloadSizeEstimate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualityPickerSheet(
    onQualitySelected: (DownloadQuality) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    estimate: DownloadSizeEstimate? = null,
    availableBytes: Long = 0,
    // Presets to offer, already gated by server capability + media type (issue
    // #20 GAP 4). Empty falls back to Original so the sheet is never blank.
    allowedQualities: List<DownloadQuality> = DownloadQuality.entries,
    // Row title per preset; the caller passes the server capability's label,
    // which adds the preset's resolution ceiling ("10 Mbps · up to 1080p").
    qualityLabel: (DownloadQuality) -> String = { it.label },
) {
    val qualities = allowedQualities.ifEmpty { listOf(DownloadQuality.Original) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Download Quality",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                text = "Choose the source file or a smaller bitrate for this download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            estimate?.let { est ->
                Text(
                    text = downloadEstimateSizeLabel(est),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
                downloadEstimateWarning(est, availableBytes)?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF9F0A),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            qualities.forEach { quality ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = qualityLabel(quality),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = downloadQualityDescription(quality),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onQualitySelected(quality) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("Cancel")
            }
        }
    }
}

/** "2.1 GB–5.4 GB" for a range, "5.4 GB" exact — mirrors iOS sizeLabel. */
private fun downloadEstimateSizeLabel(estimate: DownloadSizeEstimate): String {
    val max = formatBytes(estimate.maxBytes)
    val label = if (estimate.isRange) "${formatBytes(estimate.minBytes)}\u2013$max" else max
    return if (estimate.isRange) {
        "Estimated size: $label depending on server choice"
    } else {
        "Source file size: $label"
    }
}

/**
 * Mirrors iOS DownloadSizeEstimate.warningMessage — a low-space or
 * large-download caveat (never blocks the download). Free space of 0 means
 * the lookup failed, which never warns.
 */
internal fun downloadEstimateWarning(
    estimate: DownloadSizeEstimate,
    availableBytes: Long,
): String? {
    val size = formatBytes(estimate.maxBytes)
    if (estimate.exceedsAvailableSpace(availableBytes)) {
        val free = formatBytes(availableBytes)
        return if (estimate.isRange) {
            "This download may be up to $size, but only $free is free on this device."
        } else {
            "This download is $size, but only $free is free on this device."
        }
    }
    if (estimate.exceedsLargeThreshold) {
        return if (estimate.isRange) "This download may be up to $size." else "This download is $size."
    }
    return null
}

private fun downloadQualityDescription(quality: DownloadQuality): String =
    when (quality) {
        DownloadQuality.Original -> "Source quality, with compatibility fallback if needed."
        else -> "Prepared on the server when the original is larger."
    }
