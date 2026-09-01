package org.siloserver.silo.android.ui.screens.detail

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.downloads.LEGACY_PUBLIC_DOWNLOAD_PERMISSION
import org.siloserver.silo.android.downloads.hasLegacyPublicDownloadPermission
import org.siloserver.silo.android.ui.components.DetailLoadingSkeleton
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.rememberSwipeDownDismissState
import org.siloserver.silo.android.ui.components.swipeBackToDismiss
import org.siloserver.silo.android.ui.components.swipeDownToDismiss
import org.siloserver.silo.android.ui.theme.SiloDetailActionControlActive
import org.siloserver.silo.android.ui.screens.cast.SiloCastTargetPickerSheet
import org.siloserver.silo.android.ui.screens.downloads.openDownloadTargetInExternalApp
import org.siloserver.silo.android.ui.screens.watchtogether.SuggestToRoomViewModel
import org.siloserver.silo.android.ui.util.playbackResumePosition
import org.siloserver.silo.cast.SiloCastLaunchRequest
import org.siloserver.silo.cast.SiloCastPlaybackRequest
import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.common.downloads.DownloadOpenTarget
import org.siloserver.silo.common.downloads.DownloadStorage
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.catalog.isBookLikeItemType
import org.siloserver.silo.model.ebook.chooseEbookVersion
import org.siloserver.silo.model.ebook.isInAppReadableEbookVersion
import org.siloserver.silo.model.ebook.isSupportedEbookVersion
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.feature.CLIENT_WATCH_TOGETHER_SURFACE_ENABLED
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.playback.selectPlaybackVersion
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.metadata.DescriptionTranslationPhase
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.metadata.MetadataAiOnView

private const val PLAY_ON_DEVICE_LABEL = "Play on device"

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
    openingArtworkUrl: String? = null,
    openingArtworkThumbhash: String? = null,
    onBackClick: () -> Unit,
    onPlayClick: (String, Int?, Int?, Int?, Double?) -> Unit,
    onItemDetailClick: (String) -> Unit,
    onPersonClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onSeasonClick: (String, Int) -> Unit,
    onAudiobookPlayClick: (contentId: String, fileId: Int?, fromStart: Boolean, startPosition: Double?) -> Unit = { _, _, _, _ -> },
    onBookReadClick: (String, Int?) -> Unit = { _, _ -> },
    onWatchTogether: (String, Int?) -> Unit = { _, _ -> },
    // Auto-presents the cast remote after "Play on device" launches, mirroring
    // Apple's playOnTV: the connect/handoff handshake renders in the remote.
    onOpenCastRemote: () -> Unit = {},
    viewModel: ItemDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val swipeDownDismissState = rememberSwipeDownDismissState()
    val requestDismiss: () -> Unit = {
        swipeDownDismissState.dismiss(onBackClick)
    }
    BackHandler { requestDismiss() }

    LaunchedEffect(state.selectedEpisodeContentId) {
        if (state.selectedEpisodeContentId != null) viewModel.ensureSelectedEpisodeDetailLoaded()
    }
    val suggestViewModel: SuggestToRoomViewModel = koinViewModel()
    val suggestRoom by suggestViewModel.room.collectAsState()
    val suggestState by suggestViewModel.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(suggestState.notice, suggestState.error) {
        val message = suggestState.notice ?: suggestState.error
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            suggestViewModel.consumeNotice()
            suggestViewModel.clearError()
        }
    }

    // Refresh on return (e.g. backing out of the player): the ViewModel loads
    // once in init, so without this the Play button keeps the resume label
    // computed before playback. Fires on every ON_RESUME; no first-entry
    // guard — the composable is recreated on back-stack pop, so any
    // effect-local "skip the first" flag would reset and swallow exactly the
    // resume we care about. refreshOnReturn() no-ops while detail is still
    // null, which covers the initial load.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.onRouteResumed()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.onRoutePaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // iOS parity haptic: success/error sensory feedback when a download
    // starts or fails to register (the only haptic iOS mobile has).
    val hapticView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        viewModel.downloadStartEvents.collect { started ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                hapticView.performHapticFeedback(
                    if (started) {
                        android.view.HapticFeedbackConstants.CONFIRM
                    } else {
                        android.view.HapticFeedbackConstants.REJECT
                    },
                )
            } else {
                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    val downloadStorage: DownloadStorage = koinInject()
    val serverRegistry: ServerRegistry = koinInject()
    // The auto-version PREVIEW must name the version playback will actually
    // pick, which depends on the user's preferred quality (see
    // MobileVideoPlaybackStarter). Read it so the Video/Audio/Subtitle rows
    // describe the real file rather than defaulting to versions[0].
    val playerSettingsStore: PlayerSettingsStore = koinInject()
    val preferredQuality by playerSettingsStore.preferredQualityFlow.collectAsState(initial = null)
    // Server capability gates which quality presets the picker may offer
    // (issue #20 GAP 4). Until it loads (or if the fetch fails) we optimistically
    // offer every preset; the server still rejects a disallowed quality.
    val downloadCapability by viewModel.downloadCapability.collectAsState()
    val allowedVideoQualities = downloadCapability?.allowedQualities()
        ?: DownloadQuality.entries.toList()
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingCancelDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingDownloadQualityAction by remember { mutableStateOf<((DownloadQuality) -> Unit)?>(null) }
    // Presets the currently-open picker should show (video → capability-gated;
    // books → Original only). Set when a quality action is armed.
    var pendingDownloadAllowedQualities by remember {
        mutableStateOf<List<DownloadQuality>>(emptyList())
    }
    var pendingDownloadEstimate by remember {
        mutableStateOf<org.siloserver.silo.model.download.DownloadSizeEstimate?>(null)
    }
    var showDownloadQualityPicker by remember { mutableStateOf(false) }
    var pendingSiloCastLaunchRequest by remember { mutableStateOf<SiloCastLaunchRequest?>(null) }
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

    fun runDownloadQualityAction(
        requirePermission: Boolean = true,
        estimate: org.siloserver.silo.model.download.DownloadSizeEstimate? = null,
        allowedQualities: List<DownloadQuality> = allowedVideoQualities,
        action: (DownloadQuality) -> Unit,
    ) {
        runDownloadAction(requirePermission = requirePermission) {
            val presets = allowedQualities.ifEmpty { listOf(DownloadQuality.Original) }
            if (presets.size <= 1) {
                // A single choice (books, or a transcode-disabled account) needs
                // no picker — download it directly at the only allowed quality.
                action(presets.first())
            } else {
                pendingDownloadQualityAction = action
                pendingDownloadEstimate = estimate
                pendingDownloadAllowedQualities = presets
                showDownloadQualityPicker = true
            }
        }
    }

    fun runDownloadTap(
        downloadState: DetailDownloadState,
        directAction: () -> Unit,
        qualityAction: (DownloadQuality) -> Unit,
        estimate: org.siloserver.silo.model.download.DownloadSizeEstimate? = null,
        allowedQualities: List<DownloadQuality> = allowedVideoQualities,
    ) {
        when {
            !downloadState.isDownloaded && downloadState.progress == null -> {
                runDownloadQualityAction(
                    requirePermission = true,
                    estimate = estimate,
                    allowedQualities = allowedQualities,
                    action = qualityAction,
                )
            }
            // In flight — the tap cancels, so confirm before discarding
            // the partial download (iOS parity).
            !downloadState.isDownloaded -> {
                pendingCancelDownloadAction = {
                    runDownloadAction(requirePermission = false, action = directAction)
                }
            }
            else -> runDownloadAction(requirePermission = false, action = directAction)
        }
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

    // Launch shape mirrors Apple's SiloControlLaunchRequest: serverId +
    // nested playback request. A missing active server produces no request —
    // the receiver would reject it as a server mismatch anyway.
    fun videoCastRequest(
        contentId: String,
        title: String,
        subtitle: String? = null,
        fileId: Int? = null,
        audioTrackIndex: Int? = null,
        subtitleTrackIndex: Int? = null,
        resumePositionSeconds: Double? = null,
    ): SiloCastLaunchRequest? {
        val serverId = serverRegistry.activeServerId.value ?: return null
        return SiloCastLaunchRequest(
            serverId = serverId,
            playback = SiloCastPlaybackRequest(
                contentId = contentId,
                fileId = fileId,
                audioTrackIndex = audioTrackIndex,
                subtitleTrackIndex = subtitleTrackIndex,
                startFromBeginning = resumePositionSeconds == null,
                resumePosition = resumePositionSeconds,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Match iOS's large detail sheet: the rounded card begins below
            // the status/camera safe area instead of painting behind it.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = 4.dp)
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                clip = false,
            )
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            // Swipe right on the page to go back (iOS interactive pop) — a
            // lighter alternative to reaching for the back arrow on a tall
            // detail page.
            .swipeBackToDismiss(onDismiss = onBackClick)
            .swipeDownToDismiss(
                state = swipeDownDismissState,
                onDismiss = onBackClick,
            )
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading && state.detail == null -> {
                DetailLoadingSkeleton(
                    artworkUrl = openingArtworkUrl,
                    artworkThumbhash = openingArtworkThumbhash,
                )
            }

            state.error != null && state.detail == null -> {
                ErrorView(
                    message = state.error ?: "Something went wrong",
                    onRetry = { viewModel.loadDetail() },
                )
            }

            state.detail != null -> {
                val detail = state.detail!!
                val metadataAiStore: MetadataAiFeatureStore = koinInject()
                val metadataAiStatus by metadataAiStore.status.collectAsState()
                val translationPhase by viewModel.translationPhase.collectAsState()
                val translationEligible = metadataAiStatus.onView != MetadataAiOnView.Off &&
                    detail.pendingTranslationLanguage != null
                // `auto` on-view mode fires once per (content, language); the
                // controller latches so recompositions can't re-queue jobs.
                LaunchedEffect(detail.contentId, detail.pendingTranslationLanguage, metadataAiStatus.onView) {
                    if (metadataAiStatus.onView == MetadataAiOnView.Auto &&
                        detail.pendingTranslationLanguage != null
                    ) {
                        viewModel.translateDescription(auto = true)
                    }
                }
                val translationSlot: (@Composable () -> Unit)? = if (translationEligible &&
                    (metadataAiStatus.onView == MetadataAiOnView.Button ||
                        translationPhase != DescriptionTranslationPhase.Idle)
                ) {
                    {
                        DescriptionTranslationSection(
                            phase = translationPhase,
                            onTranslate = { viewModel.translateDescription() },
                        )
                    }
                } else {
                    null
                }
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
                val playbackFileId = explicitFileId ?: detail.versions
                    .getOrNull(effectiveSelectedVersionIndex)
                    ?.fileId
                    ?.takeIf { state.hasExplicitAudioSelection || state.hasExplicitSubtitleSelection }
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

                        org.siloserver.silo.android.ui.screens.audiobook.AudiobookDetailContent(
                            detail = detail,
                            isFavorite = state.isFavorite,
                            isInWatchlist = state.isInWatchlist,
                            selectedFileId = effectiveAudiobookFileId,
                            isDownloaded = downloadState.isDownloaded,
                            downloadProgress = downloadState.progress,
                            // Resume/Play: no fileId — the player VM resolves
                            // the part from the stored whole-book position.
                            onPlayClick = {
                                onAudiobookPlayClick(detail.contentId, null, false, null)
                            },
                            onPlayFromStartClick = {
                                onAudiobookPlayClick(detail.contentId, null, true, null)
                            },
                            // Parts play from a whole-book (global) offset.
                            onPlayFromPositionClick = { startPosition ->
                                onAudiobookPlayClick(detail.contentId, null, false, startPosition)
                            },
                            // Chapters jump to their global start offset.
                            onChapterClick = { chapter ->
                                onAudiobookPlayClick(detail.contentId, null, false, chapter.startSeconds)
                            },
                            onFavoriteClick = { viewModel.toggleFavorite() },
                            onWatchlistClick = { viewModel.toggleWatchlist() },
                            onDownloadClick = audiobookVersion?.let { version ->
                                {
                                    runDownloadTap(
                                        downloadState = downloadState,
                                        directAction = {
                                            viewModel.onDownloadTapped(
                                                version,
                                                detail.title,
                                                forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                            )
                                        },
                                        qualityAction = { quality ->
                                            viewModel.onDownloadTapped(
                                                version,
                                                detail.title,
                                                forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                                downloadQuality = quality,
                                            )
                                        },
                                        estimate = org.siloserver.silo.model.download.DownloadSizeEstimate
                                            .estimate(versions = listOf(version), fileId = version.fileId),
                                        // Bitrate presets are video-only; audiobooks
                                        // download at Original (no picker). GAP 4.
                                        allowedQualities = listOf(DownloadQuality.Original),
                                    )
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

                        org.siloserver.silo.android.ui.screens.book.BookDetailContent(
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
                                    runDownloadTap(
                                        downloadState = downloadState,
                                        directAction = {
                                            viewModel.onDownloadTapped(
                                                version,
                                                detail.title,
                                                forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                            )
                                        },
                                        qualityAction = { quality ->
                                            viewModel.onDownloadTapped(
                                                version,
                                                detail.title,
                                                forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                                downloadQuality = quality,
                                            )
                                        },
                                        estimate = org.siloserver.silo.model.download.DownloadSizeEstimate
                                            .estimate(versions = listOf(version), fileId = version.fileId),
                                        // eBooks have no bitrate variants — download
                                        // at Original with no picker. GAP 4.
                                        allowedQualities = listOf(DownloadQuality.Original),
                                    )
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

                        // Key the dialog off the SAME source the Resume action uses
                        // below: whenever nextEpisode exists, Resume launches it with
                        // its own progress. Don't fall back to series-level progress
                        // when nextEpisode has none, or the dialog would show a
                        // "stopped at" time that Resume won't honor (both buttons
                        // would start the episode at 0).
                        val seriesResume = if (nextEpisode != null) {
                            playbackResumePosition(nextEpisode)
                        } else {
                            playbackResumePosition(detail.userData)
                        }
                        val selectedEpisode = state.episodes.firstOrNull {
                            it.contentId == state.selectedEpisodeContentId
                        }
                        val selectedEpisodeDetail = state.selectedEpisodeDetail
                        val selectedEpisodeVersionIndex = state.selectedVersionIndex
                            .coerceIn(0, (selectedEpisodeDetail?.versions?.lastIndex ?: 0).coerceAtLeast(0))
                        val selectedEpisodeFileId = selectedEpisodeDetail?.versions
                            ?.getOrNull(selectedEpisodeVersionIndex)
                            ?.fileId
                            ?.takeIf {
                                state.hasExplicitVersionSelection ||
                                    state.hasExplicitAudioSelection ||
                                    state.hasExplicitSubtitleSelection
                            }
                        val selectedEpisodeResume = selectedEpisode?.let(::playbackResumePosition)
                        val activeSeriesResume = if (selectedEpisode != null) {
                            selectedEpisodeResume
                        } else {
                            seriesResume
                        }
                        SeriesDetailContent(
                            translation = translationSlot,
                            detail = detail,
                            similarItems = state.similarItems,
                            seasons = state.seasons,
                            selectedSeasonNumber = state.selectedSeasonNumber,
                            episodes = state.episodes,
                            episodesBySeason = state.episodesBySeason,
                            isLoadingEpisodes = state.isLoadingEpisodes,
                            isFavorite = state.isFavorite,
                            isInWatchlist = state.isInWatchlist,
                            nextEpisodeLabel = nextEpisodeLabel,
                            selectedEpisodeContentId = state.selectedEpisodeContentId,
                            selectedEpisodeDetail = selectedEpisodeDetail,
                            isLoadingSelectedEpisodeDetail = state.isLoadingSelectedEpisodeDetail,
                            selectedVersionIndex = selectedEpisodeVersionIndex,
                            isAutoVersion = !state.hasExplicitVersionSelection,
                            selectedAudioIndex = state.selectedAudioIndex.takeIf { state.hasExplicitAudioSelection },
                            selectedSubtitleIndex = state.selectedSubtitleIndex.takeIf { state.hasExplicitSubtitleSelection },
                            onVersionSelected = { index ->
                                if (index == null) viewModel.selectAutoVersion() else viewModel.selectVersion(index)
                            },
                            onAudioSelected = { index ->
                                if (index == null) viewModel.selectAutoAudioTrack() else viewModel.selectAudioTrack(index)
                            },
                            onSubtitleSelected = { index ->
                                if (index == null) viewModel.selectAutoSubtitle() else viewModel.selectSubtitle(index)
                            },
                            onPlayClick = {
                                selectedEpisode?.let {
                                    onPlayClick(
                                        it.contentId,
                                        selectedEpisodeFileId,
                                        state.selectedAudioIndex.takeIf { state.hasExplicitAudioSelection },
                                        state.selectedSubtitleIndex.takeIf { state.hasExplicitSubtitleSelection },
                                        selectedEpisodeResume,
                                    )
                                } ?: nextEpisode?.let {
                                    onPlayClick(it.contentId, null, null, null, playbackResumePosition(it))
                                } ?: onPlayClick(
                                    detail.contentId,
                                    null,
                                    null,
                                    null,
                                    playbackResumePosition(detail.userData),
                                )
                            },
                            onPlayFromBeginning = activeSeriesResume?.let {
                                {
                                    selectedEpisode?.let { ep ->
                                        onPlayClick(ep.contentId, selectedEpisodeFileId, null, null, 0.0)
                                    } ?: nextEpisode?.let { ep ->
                                        onPlayClick(ep.contentId, null, null, null, 0.0)
                                    } ?: onPlayClick(detail.contentId, null, null, null, 0.0)
                                }
                            },
                            resumeStoppedAtLabel = activeSeriesResume?.let { formatResumeStoppedAt(it) },
                            onEpisodePlayClick = { contentId, resumePositionSeconds ->
                                onPlayClick(contentId, null, null, null, resumePositionSeconds)
                            },
                            onEpisodeDetailClick = { viewModel.selectSeriesEpisode(it) },
                            onEpisodeWatchedChange = { episodeContentId, watched ->
                                viewModel.setEpisodeWatched(episodeContentId, watched)
                            },
                            onSeasonSelected = { viewModel.selectSeason(it) },
                            onFavoriteClick = { viewModel.toggleFavorite() },
                            onWatchlistClick = { viewModel.toggleWatchlist() },
                            onToggleWatched = { viewModel.toggleWatched() },
                            onPersonClick = onPersonClick,
                            onItemDetailClick = onItemDetailClick,
                            onSeriesDownloadClick = {
                                // Series/season batches are Original-only server-side
                                // (501 bulk_quality_unavailable otherwise), so no
                                // quality picker — just start the batch. GAP 3.
                                if (seriesDownloadState.isDownloaded) {
                                    runDownloadAction(requirePermission = false) {
                                        viewModel.onSeriesDownloadTapped()
                                    }
                                } else {
                                    runDownloadAction {
                                        viewModel.onSeriesDownloadTapped()
                                    }
                                }
                            },
                            seriesDownloadState = seriesDownloadState,
                            playOnDeviceLabel = PLAY_ON_DEVICE_LABEL,
                            onPlayOnDevice = {
                                val castContentId = nextEpisode?.contentId ?: detail.contentId
                                pendingSiloCastLaunchRequest = videoCastRequest(
                                    contentId = castContentId,
                                    title = nextEpisode?.title ?: detail.title,
                                    subtitle = nextEpisodeLabel,
                                    resumePositionSeconds = nextEpisode
                                        ?.let { playbackResumePosition(it) }
                                        ?: playbackResumePosition(detail.userData),
                                )
                            },
                            onSuggestToRoom = if (
                                CLIENT_WATCH_TOGETHER_SURFACE_ENABLED &&
                                suggestRoom != null &&
                                nextEpisode != null
                            ) {
                                {
                                    suggestViewModel.suggest(
                                        contentId = nextEpisode.contentId,
                                        contentType = "episode",
                                        title = nextEpisode.title ?: detail.title,
                                        subtitle = detail.title,
                                        posterUrl = nextEpisode.stillUrl ?: detail.posterUrl,
                                    )
                                }
                            } else {
                                null
                            },
                            onWatchTogether = if (CLIENT_WATCH_TOGETHER_SURFACE_ENABLED) {
                                { onWatchTogether(nextEpisode?.contentId ?: detail.contentId, null) }
                            } else {
                                null
                            },
                        )
                    }

                    else -> {
                        val seriesId = detail.seriesId
                        val seasonNumber = detail.seasonNumber
                        val episodeSeason = if (detail.type == "episode") {
                            state.seasons.firstOrNull { it.seasonNumber == seasonNumber }
                        } else {
                            null
                        }
                        val seasonPosterUrl = episodeSeason?.posterUrl?.takeIf { it.isNotBlank() }
                        val portraitArtwork = if (detail.type == "episode") {
                            DetailPortraitArtwork(
                                url = seasonPosterUrl ?: state.episodeSeriesPosterUrl,
                                thumbhash = if (seasonPosterUrl != null) {
                                    episodeSeason?.posterThumbhash
                                } else {
                                    state.episodeSeriesPosterThumbhash
                                },
                                reserveSpace = true,
                            )
                        } else {
                            DetailPortraitArtwork(
                                url = detail.posterUrl,
                                thumbhash = detail.posterThumbhash,
                            )
                        }
                        // Derive download state for the currently-selected
                        // version. Re-reads on every UI emission so the
                        // worker's upsertLocal progress + status transitions
                        // flow through to the DownloadButton.
                        val downloadRecords by viewModel.downloads.collectAsState()
                        // Auto preview must resolve through the SAME shared
                        // selector as playback (lastFileId → preferred-quality
                        // rank → bestAvailable), not just lastFileId-else-[0] —
                        // otherwise the previewed version (and the audio/subtitle
                        // lists derived from it) can describe a file playback
                        // won't use. An explicit user pick still wins. TV parity:
                        // selectTvDetailDisplayVersion does the same.
                        val videoDisplayVersionIndex = if (
                            state.hasExplicitVersionSelection || detail.versions.isEmpty()
                        ) {
                            effectiveSelectedVersionIndex
                        } else if (preferredQuality == null) {
                            // The quality pref hasn't emitted from DataStore yet
                            // (a frame or two): don't auto-resolve against a
                            // missing pref — it would name a version the arriving
                            // pref immediately contradicts (first-frame flash).
                            // lastFileId is pref-independent and always wins in
                            // selectPlaybackVersion, so it can be shown at once;
                            // otherwise hold the bare "Auto" placeholder (-1 →
                            // no resolved version) until the pref lands.
                            detail.userData?.lastFileId
                                ?.let { lastFileId ->
                                    detail.versions.indexOfFirst { it.fileId == lastFileId }
                                        .takeIf { it >= 0 }
                                }
                                ?: -1
                        } else {
                            val resolvedFileId = selectPlaybackVersion(
                                detail.versions,
                                detail.userData?.lastFileId,
                                preferredQuality,
                            ).fileId
                            detail.versions.indexOfFirst { it.fileId == resolvedFileId }
                                .takeIf { it >= 0 }
                                ?: effectiveSelectedVersionIndex
                        }
                        val selectedVersion = detail.versions.getOrNull(videoDisplayVersionIndex)
                        val selectedLocalDownload = selectedVersion?.let { version ->
                            localDownloadFor(version.fileId)
                        }
                        val downloadState = detailDownloadStateFor(
                            version = selectedVersion,
                            records = downloadRecords,
                            hasLocalMedia = selectedVersion?.let { selectedLocalDownload != null },
                        )

                        val movieResume = playbackResumePosition(detail.userData)
                        MovieDetailContent(
                            translation = translationSlot,
                            detail = detail,
                            similarItems = state.similarItems,
                            portraitArtwork = portraitArtwork,
                            isFavorite = state.isFavorite,
                            isInWatchlist = state.isInWatchlist,
                            selectedVersionIndex = videoDisplayVersionIndex,
                            isAutoVersion = !state.hasExplicitVersionSelection,
                            selectedAudioIndex = explicitAudioIndex,
                            selectedSubtitleIndex = explicitSubtitleIndex,
                            onPlayClick = {
                                onPlayClick(
                                    detail.contentId,
                                    playbackFileId,
                                    explicitAudioIndex,
                                    explicitSubtitleIndex,
                                    movieResume,
                                )
                            },
                            onPlayFromBeginning = movieResume?.let {
                                {
                                    onPlayClick(
                                        detail.contentId,
                                        playbackFileId,
                                        explicitAudioIndex,
                                        explicitSubtitleIndex,
                                        0.0,
                                    )
                                }
                            },
                            resumeStoppedAtLabel = movieResume?.let { formatResumeStoppedAt(it) },
                            onFavoriteClick = { viewModel.toggleFavorite() },
                            onWatchlistClick = { viewModel.toggleWatchlist() },
                            onToggleWatched = { viewModel.toggleWatched() },
                            onVersionSelected = { index ->
                                if (index != null) viewModel.selectVersion(index) else viewModel.selectAutoVersion()
                            },
                            onAudioSelected = { index ->
                                if (index != null) viewModel.selectAudioTrack(index) else viewModel.selectAutoAudioTrack()
                            },
                            onSubtitleSelected = { index ->
                                if (index != null) viewModel.selectSubtitle(index) else viewModel.selectAutoSubtitle()
                            },
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
                            seasons = state.seasons,
                            selectedSeasonNumber = state.selectedSeasonNumber,
                            episodes = state.episodes,
                            episodesBySeason = state.episodesBySeason,
                            isLoadingEpisodes = state.isLoadingEpisodes,
                            onSeasonSelected = { viewModel.selectSeason(it) },
                            onEpisodeDetailClick = onItemDetailClick,
                            onEpisodeWatchedChange = { episodeContentId, watched ->
                                viewModel.setEpisodeWatched(episodeContentId, watched)
                            },
                            isDownloaded = downloadState.isDownloaded,
                            downloadProgress = downloadState.progress,
                            playOnDeviceLabel = PLAY_ON_DEVICE_LABEL,
                            onDownloadTapped = selectedVersion?.let { v ->
                                {
                                    runDownloadTap(
                                        downloadState = downloadState,
                                        directAction = {
                                            viewModel.onDownloadTapped(
                                                v,
                                                detail.title,
                                                forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                            )
                                        },
                                        qualityAction = { quality ->
                                            viewModel.onDownloadTapped(
                                                v,
                                                detail.title,
                                                forceRedownloadMissingLocal = downloadState.needsLocalRecovery,
                                                downloadQuality = quality,
                                            )
                                        },
                                        estimate = org.siloserver.silo.model.download.DownloadSizeEstimate
                                            .estimate(versions = listOf(v), fileId = v.fileId),
                                    )
                                }
                            },
                            onPlayOnDevice = {
                                pendingSiloCastLaunchRequest = videoCastRequest(
                                    contentId = detail.contentId,
                                    title = detail.title,
                                    fileId = playbackFileId,
                                    audioTrackIndex = explicitAudioIndex,
                                    subtitleTrackIndex = explicitSubtitleIndex,
                                    resumePositionSeconds = playbackResumePosition(detail.userData),
                                )
                            },
                            onSuggestToRoom = if (
                                CLIENT_WATCH_TOGETHER_SURFACE_ENABLED && suggestRoom != null
                            ) {
                                {
                                    suggestViewModel.suggest(
                                        contentId = detail.contentId,
                                        contentType = detail.type,
                                        title = detail.title,
                                        subtitle = detail.seriesTitle?.takeIf { value -> value.isNotBlank() },
                                        posterUrl = detail.posterUrl,
                                    )
                                }
                            } else {
                                null
                            },
                            onWatchTogether = if (CLIENT_WATCH_TOGETHER_SURFACE_ENABLED) {
                                { onWatchTogether(detail.contentId, explicitFileId) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        if (showDownloadQualityPicker) {
            DownloadQualityPickerSheet(
                onQualitySelected = { quality ->
                    pendingDownloadQualityAction?.let { action ->
                        val runSelectedQuality: (DownloadQuality) -> Unit = { quality -> action(quality) }
                        runSelectedQuality(quality)
                    }
                    pendingDownloadQualityAction = null
                    pendingDownloadEstimate = null
                    pendingDownloadAllowedQualities = emptyList()
                    showDownloadQualityPicker = false
                },
                onDismiss = {
                    pendingDownloadQualityAction = null
                    pendingDownloadEstimate = null
                    pendingDownloadAllowedQualities = emptyList()
                    showDownloadQualityPicker = false
                },
                estimate = pendingDownloadEstimate,
                availableBytes = remember { downloadStorage.usableSpaceBytes() },
                allowedQualities = pendingDownloadAllowedQualities,
            )
        }

        pendingSiloCastLaunchRequest?.let { request ->
            SiloCastTargetPickerSheet(
                launchRequest = request,
                onDismiss = { pendingSiloCastLaunchRequest = null },
                onLaunched = onOpenCastRemote,
            )
        }

        pendingCancelDownloadAction?.let { confirmAction ->
            AlertDialog(
                onDismissRequest = { pendingCancelDownloadAction = null },
                title = { Text("Cancel download?") },
                text = { Text("The partially downloaded data will be discarded.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingCancelDownloadAction = null
                            confirmAction()
                        },
                    ) {
                        Text("Discard Download")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCancelDownloadAction = null }) {
                        Text("Keep Download")
                    }
                },
            )
        }

        // Android's non-glass counterpart to iOS's detail controls: the same
        // faint artwork-tinted wash and white glyphs, without live blur.
        IconButton(
            onClick = requestDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 28.dp, vertical = 18.dp)
                .size(42.dp)
                .clip(CircleShape)
                .background(SiloDetailActionControlActive.copy(alpha = 0.38f))
                .border(1.dp, Color.White.copy(alpha = 0.36f), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White,
            )
        }
        IconButton(
            onClick = onOpenCastRemote,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = 28.dp, vertical = 18.dp)
                .size(42.dp)
                .clip(CircleShape)
                .background(SiloDetailActionControlActive.copy(alpha = 0.38f))
                .border(1.dp, Color.White.copy(alpha = 0.36f), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Outlined.SettingsRemote,
                contentDescription = "Remote Control",
                tint = Color.White,
            )
        }
    }
}
