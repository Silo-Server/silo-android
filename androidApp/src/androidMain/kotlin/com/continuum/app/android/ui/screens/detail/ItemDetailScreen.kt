package com.continuum.app.android.ui.screens.detail

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.continuum.app.android.downloads.LEGACY_PUBLIC_DOWNLOAD_PERMISSION
import com.continuum.app.android.downloads.hasLegacyPublicDownloadPermission
import com.continuum.app.android.ui.components.DetailLoadingSkeleton
import com.continuum.app.android.ui.components.ErrorView
import com.continuum.app.android.ui.screens.downloads.openDownloadTargetInExternalApp
import com.continuum.app.android.ui.util.playbackResumePosition
import com.continuum.app.common.downloads.DownloadEnqueuer
import com.continuum.app.common.downloads.DownloadOpenTarget
import com.continuum.app.common.downloads.DownloadStorage
import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.model.catalog.isAudiobookItemType
import com.continuum.app.model.catalog.isBookLikeItemType
import com.continuum.app.model.ebook.chooseEbookVersion
import com.continuum.app.model.ebook.isInAppReadableEbookVersion
import com.continuum.app.model.ebook.isSupportedEbookVersion
import com.continuum.app.network.ServerRegistry
import org.koin.compose.koinInject

/**
 * Item detail dispatcher. Routes to [MovieDetailContent] or
 * [SeriesDetailContent] based on the item type, with the back button
 * floating over the hero so the artwork extends edge-to-edge.
 *
 * Mirrors `ItemDetailView.swift`'s phone path:
 *   - hero ignores top safe area
 *   - transparent back button overlay tinted onto the artwork
 *   - loading / error / content states
 */
@Composable
fun ItemDetailScreen(
    onBackClick: () -> Unit,
    onPlayClick: (String, Int?, Int?, Int?, Double?) -> Unit,
    onItemDetailClick: (String) -> Unit,
    onPersonClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onSeasonClick: (String, Int) -> Unit,
    onAudiobookPlayClick: (contentId: String, fileId: Int?, fromStart: Boolean) -> Unit = { _, _, _ -> },
    onBookReadClick: (String, Int?) -> Unit = { _, _ -> },
    onWatchTogether: (String, Int?) -> Unit = { _, _ -> },
    viewModel: ItemDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val downloadStorage: DownloadStorage = koinInject()
    val serverRegistry: ServerRegistry = koinInject()
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingDownloadAction
        pendingDownloadAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(
                context,
                "Storage permission is required to save public downloads on this Android version.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun runDownloadAction(requirePermission: Boolean = true, action: () -> Unit) {
        if (!requirePermission || hasLegacyPublicDownloadPermission(context)) {
            action()
            return
        }
        pendingDownloadAction = action
        legacyStoragePermissionLauncher.launch(LEGACY_PUBLIC_DOWNLOAD_PERMISSION)
    }

    fun localDownloadFor(fileId: Int) =
        downloadStorage.locateLocalMedia(
            serverId = serverRegistry.activeServerId.value ?: DownloadEnqueuer.DEFAULT_SERVER_ID,
            profileId = serverRegistry.activeEntry.value?.profileId ?: DownloadEnqueuer.DEFAULT_PROFILE_ID,
            fileId = fileId,
        )

    fun openExternalDownload(version: FileVersion, displayTitle: String) {
        val local = localDownloadFor(version.fileId)
        val target = DownloadOpenTarget.from(
            isComplete = local != null,
            localUri = local?.uriString,
            displayName = local?.displayName ?: version.fileName ?: displayTitle,
            container = version.container,
        )
        if (target == null || !openDownloadTargetInExternalApp(context, target)) {
            Toast.makeText(context, "No app found to open this file.", Toast.LENGTH_LONG).show()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading && state.detail == null -> {
                DetailLoadingSkeleton()
            }

            state.error != null && state.detail == null -> {
                ErrorView(
                    message = state.error ?: "Something went wrong",
                    onRetry = { viewModel.loadDetail() },
                )
            }

            state.detail != null -> {
                val detail = state.detail!!
                val effectiveSelectedVersionIndex = if (state.hasExplicitVersionSelection) {
                    state.selectedVersionIndex
                } else {
                    detail.userData?.lastFileId
                        ?.let { lastFileId ->
                            detail.versions.indexOfFirst { it.fileId == lastFileId }
                                .takeIf { it >= 0 }
                        }
                        ?: state.selectedVersionIndex
                }
                val explicitFileId = detail.versions
                    .getOrNull(effectiveSelectedVersionIndex)
                    ?.fileId
                    ?.takeIf { state.hasExplicitVersionSelection }
                val explicitAudioIndex = state.selectedAudioIndex
                    .takeIf { state.hasExplicitAudioSelection }
                val explicitSubtitleIndex = state.selectedSubtitleIndex
                    .takeIf { state.hasExplicitSubtitleSelection }
                val effectiveAudiobookFileId = detail.versions
                    .getOrNull(effectiveSelectedVersionIndex)
                    ?.fileId

                when {
                    isAudiobookItemType(detail.type) -> {
                        val downloadRecords by viewModel.downloads.collectAsState()
                        val audiobookVersion = effectiveAudiobookFileId
                            ?.let { fileId -> detail.versions.firstOrNull { it.fileId == fileId } }
                            ?: detail.versions.firstOrNull()
                        val audiobookLocalDownload = audiobookVersion?.let { version ->
                            localDownloadFor(version.fileId)
                        }
                        val downloadState = detailDownloadStateFor(
                            version = audiobookVersion,
                            records = downloadRecords,
                            hasLocalMedia = audiobookVersion?.let { audiobookLocalDownload != null },
                        )

                        com.continuum.app.android.ui.screens.audiobook.AudiobookDetailContent(
                            detail = detail,
                            isFavorite = state.isFavorite,
                            isInWatchlist = state.isInWatchlist,
                            selectedFileId = effectiveAudiobookFileId,
                            isDownloaded = downloadState.isDownloaded,
                            downloadProgress = downloadState.progress,
                            onPlayClick = { fileId ->
                                onAudiobookPlayClick(detail.contentId, fileId, false)
                            },
                            onPlayFromStartClick = { fileId ->
                                onAudiobookPlayClick(detail.contentId, fileId, true)
                            },
                            onChapterClick = { _ ->
                                onAudiobookPlayClick(detail.contentId, effectiveAudiobookFileId, false)
                            },
                            onFavoriteClick = { viewModel.toggleFavorite() },
                            onWatchlistClick = { viewModel.toggleWatchlist() },
                            onDownloadClick = audiobookVersion?.let { version ->
                                {
                                    runDownloadAction(
                                        requirePermission = !downloadState.isDownloaded && downloadState.progress == null,
                                    ) {
                                        viewModel.onDownloadTapped(
                                            version,
                                            detail.title,
                                            forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                        )
                                    }
                                }
                            },
                        )
                    }

                    isBookLikeItemType(detail.type) -> {
                        val selectedBookVersion = if (state.hasExplicitVersionSelection) {
                            detail.versions.getOrNull(state.selectedVersionIndex)
                        } else {
                            chooseEbookVersion(detail.versions, requestedFileId = detail.userData?.lastFileId)
                                ?: detail.versions.firstOrNull { it.isSupportedEbookVersion() }
                                ?: detail.versions.firstOrNull()
                        }
                        val selectedBookVersionIndex = selectedBookVersion
                            ?.let { version -> detail.versions.indexOfFirst { it.fileId == version.fileId } }
                            ?.takeIf { it >= 0 }
                            ?: 0
                        val selectedBookLocalDownload = selectedBookVersion?.let { version ->
                            localDownloadFor(version.fileId)
                        }
                        val downloadRecords by viewModel.downloads.collectAsState()
                        val downloadState = detailDownloadStateFor(
                            version = selectedBookVersion,
                            records = downloadRecords,
                            hasLocalMedia = selectedBookVersion?.let { selectedBookLocalDownload != null },
                        )

                        com.continuum.app.android.ui.screens.book.BookDetailContent(
                            detail = detail,
                            isFavorite = state.isFavorite,
                            isInWatchlist = state.isInWatchlist,
                            selectedVersionIndex = selectedBookVersionIndex,
                            onVersionSelected = { viewModel.selectVersion(it) },
                            canReadSelectedVersion = selectedBookVersion
                                ?.isInAppReadableEbookVersion(state.kindleConversionAvailable) == true,
                            isDownloaded = downloadState.isDownloaded,
                            downloadProgress = downloadState.progress,
                            onReadClick = { fileId -> onBookReadClick(detail.contentId, fileId) },
                            onFavoriteClick = { viewModel.toggleFavorite() },
                            onWatchlistClick = { viewModel.toggleWatchlist() },
                            onDownloadClick = selectedBookVersion?.takeIf { it.isSupportedEbookVersion() }?.let { version ->
                                {
                                    runDownloadAction(
                                        requirePermission = !downloadState.isDownloaded && downloadState.progress == null,
                                    ) {
                                        viewModel.onDownloadTapped(
                                            version,
                                            detail.title,
                                            forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                        )
                                    }
                                }
                            },
                            onOpenExternalClick = selectedBookVersion
                                ?.takeIf {
                                    !it.isInAppReadableEbookVersion(state.kindleConversionAvailable) &&
                                        downloadState.isDownloaded &&
                                        selectedBookLocalDownload != null
                                }
                                ?.let { version ->
                                    { openExternalDownload(version, detail.title) }
                                },
                        )
                    }

                    detail.type == "series" -> {
                        val nextEpisode = state.episodes.firstOrNull { ep ->
                            ep.userData?.played != true
                        } ?: state.episodes.firstOrNull()
                        val nextEpisodeLabel = nextEpisode?.let { ep ->
                            "S${ep.seasonNumber}·E${ep.episodeNumber}"
                        }
                        val episodeDownloadRecords by viewModel.downloads.collectAsState()
                        // Series-level roll-up across ALL seasons (state.allEpisodeFileIds
                        // is loaded in the background): ✓ only when every episode is
                        // downloaded; a partial fraction otherwise.
                        val seriesDownloadState = remember(
                            episodeDownloadRecords,
                            state.allEpisodeFileIds,
                            state.allEpisodeIdsComplete,
                        ) {
                            val ids = state.allEpisodeFileIds
                            if (ids.isEmpty()) {
                                DetailDownloadState()
                            } else {
                                val downloaded = ids.count {
                                    detailDownloadStateForFile(it, episodeDownloadRecords).isDownloaded
                                }
                                // ✓ only when every season loaded AND every episode is
                                // downloaded; otherwise show a partial fraction.
                                val allDone = state.allEpisodeIdsComplete && downloaded == ids.size
                                DetailDownloadState(
                                    isDownloaded = allDone,
                                    progress = if (!allDone && downloaded > 0) {
                                        downloaded.toFloat() / ids.size
                                    } else {
                                        null
                                    },
                                )
                            }
                        }

                        SeriesDetailContent(
                            detail = detail,
                            seasons = state.seasons,
                            selectedSeasonNumber = state.selectedSeasonNumber,
                            episodes = state.episodes,
                            isLoadingEpisodes = state.isLoadingEpisodes,
                            isFavorite = state.isFavorite,
                            isInWatchlist = state.isInWatchlist,
                            nextEpisodeLabel = nextEpisodeLabel,
                            onPlayClick = {
                                nextEpisode?.let {
                                    onPlayClick(it.contentId, null, null, null, playbackResumePosition(it))
                                } ?: onPlayClick(
                                    detail.contentId,
                                    null,
                                    null,
                                    null,
                                    playbackResumePosition(detail.userData),
                                )
                            },
                            onEpisodePlayClick = { contentId, resumePositionSeconds ->
                                onPlayClick(contentId, null, null, null, resumePositionSeconds)
                            },
                            onEpisodeDetailClick = onItemDetailClick,
                            onSeasonSelected = { viewModel.selectSeason(it) },
                            onFavoriteClick = { viewModel.toggleFavorite() },
                            onWatchlistClick = { viewModel.toggleWatchlist() },
                            userRating = state.userRating,
                            onSetRating = { viewModel.setRating(it) },
                            onClearRating = { viewModel.clearRating() },
                            onPersonClick = onPersonClick,
                            onItemDetailClick = onItemDetailClick,
                            onSeriesDownloadClick = { runDownloadAction { viewModel.onSeriesDownloadTapped() } },
                            onSeasonDownloadClick = { season ->
                                runDownloadAction { viewModel.onSeasonDownloadTapped(season) }
                            },
                            onEpisodeDownloadClick = { ep ->
                                runDownloadAction { viewModel.onEpisodeDownloadTapped(ep) }
                            },
                            episodeDownloadState = { ep ->
                                detailDownloadStateForFile(
                                    fileId = ep.files.firstOrNull()?.fileId,
                                    records = episodeDownloadRecords,
                                )
                            },
                            seriesDownloadState = seriesDownloadState,
                            onWatchTogether = {
                                onWatchTogether(nextEpisode?.contentId ?: detail.contentId, null)
                            },
                        )
                    }

                    else -> {
                        val seriesId = detail.seriesId
                        val seasonNumber = detail.seasonNumber
                        // Derive download state for the currently-selected
                        // version. Re-reads on every UI emission so the
                        // worker's upsertLocal progress + status transitions
                        // flow through to the DownloadButton.
                        val downloadRecords by viewModel.downloads.collectAsState()
                        val selectedVersion = detail.versions.getOrNull(effectiveSelectedVersionIndex)
                        val selectedLocalDownload = selectedVersion?.let { version ->
                            localDownloadFor(version.fileId)
                        }
                        val downloadState = detailDownloadStateFor(
                            version = selectedVersion,
                            records = downloadRecords,
                            hasLocalMedia = selectedVersion?.let { selectedLocalDownload != null },
                        )

                        MovieDetailContent(
                            detail = detail,
                            isFavorite = state.isFavorite,
                            isInWatchlist = state.isInWatchlist,
                            selectedVersionIndex = effectiveSelectedVersionIndex,
                            selectedAudioIndex = state.selectedAudioIndex,
                            selectedSubtitleIndex = state.selectedSubtitleIndex,
                            onPlayClick = {
                                onPlayClick(
                                    detail.contentId,
                                    explicitFileId,
                                    explicitAudioIndex,
                                    explicitSubtitleIndex,
                                    playbackResumePosition(detail.userData),
                                )
                            },
                            onFavoriteClick = { viewModel.toggleFavorite() },
                            onWatchlistClick = { viewModel.toggleWatchlist() },
                            userRating = state.userRating,
                            onSetRating = { viewModel.setRating(it) },
                            onClearRating = { viewModel.clearRating() },
                            onVersionSelected = { viewModel.selectVersion(it) },
                            onAudioSelected = { viewModel.selectAudioTrack(it) },
                            onSubtitleSelected = { viewModel.selectSubtitle(it) },
                            onPersonClick = onPersonClick,
                            onItemDetailClick = onItemDetailClick,
                            onSeriesClick = seriesId?.let { resolvedSeriesId ->
                                { onSeriesClick(resolvedSeriesId) }
                            },
                            onSeasonClick = if (seriesId != null && seasonNumber != null) {
                                { onSeasonClick(seriesId, seasonNumber) }
                            } else {
                                null
                            },
                            isDownloaded = downloadState.isDownloaded,
                            downloadProgress = downloadState.progress,
                            onDownloadTapped = selectedVersion?.let { v ->
                                {
                                    runDownloadAction(
                                        requirePermission = !downloadState.isDownloaded && downloadState.progress == null,
                                    ) {
                                        viewModel.onDownloadTapped(
                                            v,
                                            detail.title,
                                            forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                        )
                                    }
                                }
                            },
                            onWatchTogether = { onWatchTogether(detail.contentId, explicitFileId) },
                        )
                    }
                }
            }
        }

        // Floating back button — sits on the hero artwork without
        // pushing content down, mirroring iOS's transparent nav bar.
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
    }
}
