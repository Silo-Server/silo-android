package org.siloserver.silo.android.ui.screens.reader

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.screens.downloads.openDownloadTargetInExternalApp
import org.siloserver.silo.android.ui.screens.reader.reflow.ReflowableReader
import org.siloserver.silo.common.downloads.DownloadOpenTarget
import org.siloserver.silo.common.ebook.ReaderEngineKind
import org.siloserver.silo.common.ebook.ReaderSection
import org.siloserver.silo.model.ebook.EbookReadMode

@Composable
fun ReaderEngineHost(
    state: ReaderUiState,
    onPageChanged: (Int) -> Unit,
    onPageCountKnown: (Int) -> Unit,
    onLocatorChanged: (String, Double) -> Unit,
    onSectionsKnown: (List<ReaderSection>) -> Unit,
    onTextScaleNudge: (Float) -> Unit,
    onJumpConsumed: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    val engineKind = state.capabilities.engineKind
    val fileUrl = state.fileUrl
    val showExternalReadingPanel = shouldShowExternalReadingPanel(state)

    when {
        state.isLoading -> CenteredReaderMessage("Loading book...", onToggleChrome = onToggleChrome)
        showExternalReadingPanel -> ExternalReadingPanel(state = state, onToggleChrome = onToggleChrome)
        state.error != null -> CenteredReaderMessage(state.error, onToggleChrome = onToggleChrome)
        fileUrl.isNullOrBlank() -> CenteredReaderMessage(
            "No file available for this book.",
            onToggleChrome = onToggleChrome,
        )
        else -> when (engineKind) {
            ReaderEngineKind.Reflowable -> ReflowableReader(
                format = state.format,
                fileUrl = fileUrl,
                fileAuthority = state.fileAuthority,
                settings = state.displaySettings,
                initialLocator = state.progressLocation,
                onLocatorChanged = onLocatorChanged,
                onSectionsKnown = onSectionsKnown,
                onTextScaleNudge = onTextScaleNudge,
                jumpToLocation = state.pendingJumpLocation,
                onJumpConsumed = onJumpConsumed,
                onToggleChrome = onToggleChrome,
            )
            ReaderEngineKind.FixedDocument -> PdfReader(
                fileUrl = fileUrl,
                fileAuthority = state.fileAuthority,
                title = state.title,
                initialPage = state.currentPage,
                onPageChanged = onPageChanged,
                onPageCountKnown = onPageCountKnown,
                onToggleChrome = onToggleChrome,
            )
            ReaderEngineKind.ComicManga -> ComicReader(
                fileUrl = fileUrl,
                fileAuthority = state.fileAuthority,
                title = state.title,
                initialPage = state.currentPage,
                onPageChanged = onPageChanged,
                onPageCountKnown = onPageCountKnown,
                onToggleChrome = onToggleChrome,
            )
            ReaderEngineKind.External -> CenteredReaderMessage("Reader unavailable.", onToggleChrome = onToggleChrome)
        }
    }
}

internal fun shouldShowExternalReadingPanel(state: ReaderUiState): Boolean =
    state.capabilities.engineKind == ReaderEngineKind.External &&
        state.readMode == EbookReadMode.ExternalOnly

@Composable
private fun ExternalReadingPanel(
    state: ReaderUiState,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val target = remember(state.localUri, state.localDisplayName, state.format) {
        DownloadOpenTarget.from(
            isComplete = !state.localUri.isNullOrBlank(),
            localUri = state.localUri,
            displayName = state.localDisplayName,
            container = state.format.wire,
        )
    }
    val formatLabel = state.formatDisplayName.ifBlank { state.format.displayName }
    val titleLabel = state.title.ifBlank { target?.displayName ?: "This file" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .toggleChromeOnTap(onToggleChrome)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = titleLabel,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = formatLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = state.error ?: "Open this original file with a dedicated reader app.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        Button(
            enabled = target != null,
            onClick = {
                val openTarget = target ?: return@Button
                if (!openDownloadTargetInExternalApp(context, openTarget)) {
                    Toast.makeText(context, "No app found to open this file.", Toast.LENGTH_LONG).show()
                }
            },
        ) {
            Text("Open with another reader")
        }
    }
}

@Composable
private fun CenteredReaderMessage(
    message: String?,
    onToggleChrome: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .toggleChromeOnTap(onToggleChrome),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message ?: "Reader unavailable.",
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(32.dp),
        )
    }
}

private fun Modifier.toggleChromeOnTap(onToggleChrome: () -> Unit): Modifier =
    pointerInput(onToggleChrome) {
        detectTapGestures(onTap = { onToggleChrome() })
    }
