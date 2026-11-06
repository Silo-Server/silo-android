package org.siloserver.silo.android.ui.components

import org.siloserver.silo.common.R as CommonR
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Download icon with optional circular progress indicator overlay.
 *
 * @param isDownloaded Whether the download is complete.
 * @param progress Download progress from 0f to 1f, or null when not downloading.
 * @param onClick Called when the user taps the button.
 * @param modifier Compose modifier.
 */
@Composable
fun DownloadButton(
    isDownloaded: Boolean,
    progress: Float? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            if (progress != null && !isDownloaded) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 2.dp,
                )
            }

            Icon(
                imageVector = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                contentDescription = stringResource(
                    if (isDownloaded) CommonR.string.shell_downloadButton_downloaded else CommonR.string.shell_downloadButton_download,
                ),
                tint = if (isDownloaded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
