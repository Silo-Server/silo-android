package org.siloserver.silo.android.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.downloads.OfflineMedia
import org.siloserver.silo.common.downloads.OfflineMediaResolver
import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.common.ebook.EbookLocalStateStore
import org.siloserver.silo.common.ebook.ReaderCapabilities
import org.siloserver.silo.common.ebook.ReaderDisplaySettings
import org.siloserver.silo.common.ebook.ReaderSection
import org.siloserver.silo.model.book.BookFormat
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.ebook.EbookAnnotation
import org.siloserver.silo.model.ebook.EbookReadMode
import org.siloserver.silo.model.ebook.SaveEbookProgressRequest
import org.siloserver.silo.model.ebook.chooseReaderVersion
import org.siloserver.silo.model.ebook.ebookPageNumberFromProgressLocation
import org.siloserver.silo.model.ebook.ebookProgressPercentForPage
import org.siloserver.silo.model.ebook.isKindleConvertibleExternal
import org.siloserver.silo.model.ebook.localBookmarkAnnotation
import org.siloserver.silo.model.ebook.promotedForKindleConversion
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.EbookReaderRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared state for the book reader. The [ReaderScreen] reads [format]
 * and dispatches to the right renderer (EPUB, PDF, CBZ/CBR). All
 * renderers share the same VM so position-persistence, font/theme
 * settings, and library back-navigation work the same regardless of
 * format.
 */
data class ReaderUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val author: String? = null,
    val format: BookFormat = BookFormat.Unknown,
    val readMode: EbookReadMode = EbookReadMode.Unsupported,
    val formatDisplayName: String = "",
    val fileUrl: String? = null,
    val fileAuthority: org.siloserver.silo.network.DurableLoginAuthority? = null,
    val localUri: String? = null,
    val localDisplayName: String? = null,
    val fileId: Int? = null,
    val pageCount: Int? = null,
    /** 0-based current page for fixed-layout (PDF / CBZ / CBR). For
     *  EPUBs the unit is renderer-defined (CFI or chapter index). */
    val currentPage: Int = 0,
    val progressLocation: String? = null,
    val progressPercent: Double = 0.0,
    val bookmarks: List<EbookAnnotation> = emptyList(),
    val capabilities: ReaderCapabilities = ReaderCapabilities.forFormat(BookFormat.Unknown),
    val displaySettings: ReaderDisplaySettings = ReaderDisplaySettings(),
    val sections: List<ReaderSection> = emptyList(),
    /** Opaque reflow-locator string the reflowable reader should seek to, or
     *  null when there is no pending jump. Consumed via [consumeJump]. */
    val pendingJumpLocation: String? = null,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val error: String? = null,
)

private data class InitialReaderState(
    val currentPage: Int? = null,
    val progressLocation: String? = null,
    val progressPercent: Double? = null,
    val bookmarks: List<EbookAnnotation>? = null,
)

class ReaderViewModel(
    private val catalogRepository: CatalogRepository,
    private val ebookReaderRepository: EbookReaderRepository,
    private val offlineMediaResolver: OfflineMediaResolver,
    private val localStateStore: EbookLocalStateStore,
    // Track B: reading position via the unified outbox (replaces the progress half
    // of EbookLocalStateStore + EbookProgressSyncer; bookmarks/settings stay local).
    private val userItemStatePort: org.siloserver.silo.repository.port.UserItemStatePort,
    private val outboxSyncScheduler: org.siloserver.silo.common.data.sync.OutboxSyncScheduler,
    private val serverRegistry: ServerRegistry,
    private val profileRepository: ProfileRepository,
    savedStateHandle: SavedStateHandle,
    private val ebookAuthorities: org.siloserver.silo.network.DurableLoginAuthorityProvider? = null,
    private val ebookV2: org.siloserver.silo.network.apiv2.EbookReaderV2Api? = null,
    private val identityTransitions: org.siloserver.silo.network.IdentityTransitionBarrier? = null,
) : ViewModel() {

    private val contentId: String = savedStateHandle.get<String>("contentId") ?: ""
    private val requestedFileId: Int? = savedStateHandle.get<String>("fileId")?.toIntOrNull()
    private var shouldSuppressInitialPageChange = false
    private var readerAuthority: org.siloserver.silo.network.DurableLoginAuthority? = null
    private var configSession: org.siloserver.silo.repository.EbookConfigSession? = null
    private var displayRevision = 0L
    private var progressSaveJob: Job? = null
    private val progressPersistMutex = Mutex()
    private val annotationMutex = Mutex()

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        if (contentId.isNotBlank()) loadDetail()
    }

    private fun loadDetail() {
        viewModelScope.launch {
            readerAuthority = ebookAuthorities?.snapshotDurableLoginAuthority()
            when (val r = catalogRepository.getItemDetail(contentId)) {
                is ApiResult.Success -> {
                    val d = r.data
                    val chosen = chooseReaderVersion(d.versions, requestedFileId)
                    if (chosen == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                title = d.title,
                                author = d.ebook?.authorNames ?: d.book?.author,
                                error = "No supported ebook file is available.",
                            )
                        }
                        return@launch
                    }
                    // When the server advertises Kindle->EPUB conversion, a mobi/azw/azw3
                    // file is read in-app: /read returns EPUB bytes the reflowable reader
                    // renders. Only probe the capability for a Kindle external-only target
                    // (not for every epub/pdf open).
                    val conversionAvailable = chosen.isKindleConvertibleExternal() &&
                        ebookReaderRepository.isKindleConversionAvailable()
                    val target = chosen.promotedForKindleConversion(conversionAvailable)
                    val version = target.version
                    val (serverId, profileId) = resolveScope()
                    val offlineMedia = offlineMediaResolver.findLocalMedia(
                        serverId = serverId,
                        profileId = profileId,
                        contentId = d.contentId,
                        requestedFileId = version.fileId,
                        allowFallback = false,
                    )
                    val readerState = loadReaderState(version.fileId)
                    val displaySettings = loadDisplaySettings()
                    shouldSuppressInitialPageChange = readerState.progressLocation != null
                    val format = target.format
                    val fileUrl = when {
                        // Promoted Kindle must come from the server (which converts it to
                        // EPUB); a locally-downloaded original is raw MOBI, not EPUB.
                        conversionAvailable -> ebookReaderRepository.readPath(d.contentId, version.fileId)
                        target.support.canReadInApp ->
                            offlineMedia?.fileUrl ?: ebookReaderRepository.readPath(d.contentId, version.fileId)
                        else -> offlineMedia?.fileUrl
                    }
                    val error = if (
                        target.support.readMode == EbookReadMode.ExternalOnly &&
                        offlineMedia == null
                    ) {
                        "Download this original to open it with another reader."
                    } else {
                        null
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            title = d.title,
                            author = d.ebook?.authorNames ?: d.book?.author,
                            format = format,
                            readMode = target.support.readMode,
                            formatDisplayName = target.support.displayName,
                            fileUrl = fileUrl,
                            fileAuthority = readerAuthority,
                            localUri = offlineMedia?.uriString,
                            localDisplayName = offlineMedia?.displayName ?: version.fileName,
                            fileId = version.fileId,
                            pageCount = d.book?.pageCount,
                            capabilities = ReaderCapabilities.forFormat(format),
                            currentPage = readerState.currentPage ?: it.currentPage,
                            progressLocation = readerState.progressLocation,
                            progressPercent = readerState.progressPercent ?: it.progressPercent,
                            bookmarks = readerState.bookmarks ?: it.bookmarks,
                            displaySettings = displaySettings,
                            error = error,
                        )
                    }
                }
                is ApiResult.Error -> loadOfflineOnly(error = r.message)
                is ApiResult.NetworkError -> loadOfflineOnly(error = r.exception.message)
            }
        }
    }

    private suspend fun loadOfflineOnly(error: String?) {
        val (serverId, profileId) = resolveScope()
        val candidates = offlineMediaResolver.listLocalMedia(
            serverId = serverId,
            profileId = profileId,
            contentId = contentId,
        )
        val target = chooseReaderVersion(
            versions = candidates.map { media -> media.toFileVersion() },
            requestedFileId = requestedFileId,
        )
        if (target == null) {
            val message = if (candidates.isEmpty()) {
                error
            } else {
                "This file is not a supported reading format."
            }
            _uiState.update { it.copy(isLoading = false, error = message) }
            return
        }
        val media = candidates.firstOrNull { it.fileId == target.version.fileId }
        if (media == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = error,
                )
            }
            return
        }
        val readerState = loadLocalReaderState(media.fileId)
        val displaySettings = loadDisplaySettings()
        shouldSuppressInitialPageChange = readerState.progressLocation != null
        _uiState.update {
            it.copy(
                isLoading = false,
                title = media.sidecar.title,
                author = null,
                format = target.format,
                readMode = target.support.readMode,
                formatDisplayName = target.support.displayName,
                fileUrl = media.fileUrl,
                localUri = media.uriString,
                localDisplayName = media.displayName,
                fileId = media.fileId,
                pageCount = null,
                capabilities = ReaderCapabilities.forFormat(target.format),
                currentPage = readerState.currentPage ?: it.currentPage,
                progressLocation = readerState.progressLocation,
                progressPercent = readerState.progressPercent ?: it.progressPercent,
                bookmarks = readerState.bookmarks ?: it.bookmarks,
                displaySettings = displaySettings,
                error = null,
            )
        }
    }

    private suspend fun loadReaderState(fileId: Int): InitialReaderState {
        var currentPage: Int? = null
        var progressLocation: String? = null
        var progressPercent: Double? = null
        var bookmarks: List<EbookAnnotation>? = null

        val local = loadLocalReaderState(fileId)
        currentPage = local.currentPage
        progressLocation = local.progressLocation
        progressPercent = local.progressPercent
        bookmarks = local.bookmarks

        when (val progress = ebookReaderRepository.getProgress(contentId, readerAuthority?.scope)) {
            is ApiResult.Success -> {
                if (progress.data.fileId == fileId && local.progressLocation == null) {
                    progressLocation = progress.data.location
                    progressPercent = progress.data.progress.coerceIn(0.0, 1.0)
                    currentPage = ebookPageNumberFromProgressLocation(progress.data.location)
                }
            }
            else -> Unit
        }

        val authority = readerAuthority
        if (authority != null) annotationMutex.withLock {
            val receipts = mutableListOf<EbookAnnotation>()
            val (serverId, profileId) = resolveScope()
            val pending = withContext(Dispatchers.IO) { localStateStore.listBookmarks(serverId, profileId, contentId) }
            for (bookmark in pending.filter { it.loginId == authority.loginId && it.origin == authority.scope.serverUrl }) {
                if (bookmark.deleteETag != null) {
                    val deletion = ebookReaderRepository.deleteAnnotation(contentId,
                        bookmark.toAnnotation().copy(etag = bookmark.deleteETag), authority.scope)
                    if (deletion is ApiResult.Success || (deletion is ApiResult.Error && deletion.code == 404))
                        bookmarkWrite(authority) { localStateStore.removeBookmark(serverId, profileId, contentId, bookmark.id) }
                    else _uiState.update { it.copy(syncError = "Bookmark deletion needs a reload before retrying.") }
                    continue
                }
                when (val result = ebookReaderRepository.createBookmark(contentId, bookmark.id, bookmark.location, authority.scope)) {
                    is ApiResult.Success -> {
                        if (authority != ebookAuthorities?.snapshotDurableLoginAuthority()) return@withLock
                        receipts += result.data
                        bookmarkWrite(authority) { localStateStore.removeBookmark(serverId, profileId, contentId, bookmark.id) }
                    }
                    else -> _uiState.update { it.copy(syncError = "Some bookmarks are local. Reopen the reader to retry sync.") }
                }
            }
            val annotations = ebookReaderRepository.listAnnotations(contentId, authority.scope)
            if (authority != ebookAuthorities?.snapshotDurableLoginAuthority()) return@withLock
            val remaining = withContext(Dispatchers.IO) { localStateStore.listBookmarks(serverId, profileId, contentId) }
                .filter { it.loginId == null || (it.loginId == authority.loginId && it.origin == authority.scope.serverUrl) }
                .map { it.toAnnotation() }
            val remote = if (annotations is ApiResult.Success) annotations.data.items else receipts
            if (annotations !is ApiResult.Success)
                _uiState.update { it.copy(syncError = "Bookmarks could not load completely. Reopen the reader to retry.") }
            bookmarks = (remaining + remote.filter { it.kind == "bookmark" }).associateBy { it.id }.values.toList()
        }

        return InitialReaderState(
            currentPage = currentPage,
            progressLocation = progressLocation,
            progressPercent = progressPercent,
            bookmarks = bookmarks,
        )
    }

    private suspend fun loadLocalReaderState(fileId: Int): InitialReaderState {
        val (serverId, profileId) = resolveScope()
        // Reading position now comes from the Track B local projection; bookmarks
        // still live in EbookLocalStateStore.
        val progress = userItemStatePort.localEbookProgress(contentId, fileId)
        val bookmarks = withContext(Dispatchers.IO) {
            localStateStore.listBookmarks(serverId, profileId, contentId)
        }.filter { it.loginId == null || (it.loginId == readerAuthority?.loginId && it.origin == readerAuthority?.scope?.serverUrl) }
            .map { it.toAnnotation() }
        return InitialReaderState(
            currentPage = ebookPageNumberFromProgressLocation(progress?.location),
            progressLocation = progress?.location,
            progressPercent = progress?.progress,
            bookmarks = bookmarks.takeIf { it.isNotEmpty() },
        )
    }

    override fun onCleared() {
        super.onCleared()
        // Drain the reading-position outbox on exit (prompt sync when online;
        // offline waits for connectivity). requestSync just enqueues WorkManager.
        outboxSyncScheduler.requestSync()
    }

    private suspend fun loadDisplaySettings(): ReaderDisplaySettings {
        val revision = displayRevision
        val (serverId, profileId) = resolveScope()
        val local = withContext(Dispatchers.IO) { localStateStore.readDisplaySettings(serverId, profileId) }
            ?: ReaderDisplaySettings()
        val authority = readerAuthority ?: return local
        val api = ebookV2 ?: return local
        val session = org.siloserver.silo.repository.EbookConfigSession(api, contentId, authority.scope)
        val loaded = session.load()
        if (authority != ebookAuthorities?.snapshotDurableLoginAuthority()) return local
        configSession = session
        if (loaded is ApiResult.Success) {
            val remote = loaded.data["android_reader"]
            if (remote != null && displayRevision == revision) {
                return runCatching {
                    org.siloserver.silo.network.SiloJson.decodeFromJsonElement(ReaderDisplaySettings.serializer(), remote).normalized()
                }.getOrElse { local }
            }
        } else _uiState.update { it.copy(syncError = "Reader settings are local. Server settings could not be loaded.") }
        return if (displayRevision == revision) local else _uiState.value.displaySettings
    }

    fun onPageChanged(page: Int) {
        val state = _uiState.value
        val fileId = state.fileId ?: return
        val normalizedPage = page.coerceToKnownPageCount(state.pageCount)
        if (shouldSuppressInitialPageChange && normalizedPage == state.currentPage) {
            shouldSuppressInitialPageChange = false
            return
        }
        shouldSuppressInitialPageChange = false
        val location = "page:$normalizedPage"
        val progressPercent = ebookProgressPercentForPage(
            page = normalizedPage,
            pageCount = state.pageCount,
            currentProgress = state.progressPercent,
        )
        _uiState.update {
            it.copy(
                currentPage = normalizedPage,
                progressLocation = location,
                progressPercent = progressPercent,
            )
        }
        persistProgress(fileId = fileId, location = location, progressPercent = progressPercent)
    }

    fun onPageCountKnown(count: Int) {
        if (count <= 0) return
        val state = _uiState.value
        val normalizedPage = state.currentPage.coerceToKnownPageCount(count)
        val progressPercent = ebookProgressPercentForPage(
            page = normalizedPage,
            pageCount = count,
            currentProgress = state.progressPercent,
        )
        _uiState.update {
            it.copy(
                pageCount = count,
                currentPage = normalizedPage,
                progressLocation = if (normalizedPage != state.currentPage) {
                    "page:$normalizedPage"
                } else {
                    it.progressLocation
                },
                progressPercent = progressPercent,
            )
        }
        if (normalizedPage != state.currentPage) {
            state.fileId?.let { fileId ->
                persistProgress(fileId = fileId, location = "page:$normalizedPage", progressPercent = progressPercent)
            }
        }
    }

    /**
     * Persist a reflowable-reader locator. The reflowable engine reports an
     * opaque [locationJson] string and a 0..1 book-level [progress]; we record
     * both through the same Track B outbox path as page-based progress so the
     * sync engine carries it offline->online (monotonic-guarded).
     */
    fun onLocatorChanged(locationJson: String, progress: Double) {
        val state = _uiState.value
        val fileId = state.fileId ?: return
        val clampedProgress = progress.coerceIn(0.0, 1.0)
        shouldSuppressInitialPageChange = false
        _uiState.update {
            it.copy(
                progressLocation = locationJson,
                progressPercent = clampedProgress,
            )
        }
        persistProgress(fileId = fileId, location = locationJson, progressPercent = clampedProgress)
    }

    /**
     * Persist the given reading position locally and (lazily) to the server,
     * cancelling any in-flight save. Shared by page-based ([onPageChanged]) and
     * reflowable-locator ([onLocatorChanged]) progress reporting.
     */
    private fun persistProgress(fileId: Int, location: String, progressPercent: Double) {
        val eventTimeMs = System.currentTimeMillis()
        val authority = readerAuthority
        progressSaveJob?.cancel()
        // Optimistic + offline-first: the local Room projection is the resume
        // source and is written instantly, so progress is "saved" immediately;
        // the content-level outbox op syncs to the server (monotonic-guarded in
        // the drain) on reader exit / reconnect / launch. No inline PUT.
        _uiState.update { it.copy(isSyncing = false) }
        val saveJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            progressPersistMutex.withLock {
                // NonCancellable: the durable local write must complete even if the
                // VM is torn down right after the final page turn (back navigation
                // cancels viewModelScope) — otherwise the last position is lost.
                withContext(NonCancellable) {
                    if (authority != null) userItemStatePort.recordEbookProgress(authority,
                        contentId, fileId, location, progressPercent, eventTimeMs)
                    else if (ebookAuthorities == null) userItemStatePort.recordEbookProgress(contentId, fileId, location, progressPercent)
                    else _uiState.update { it.copy(syncError = "Reading progress needs a saved account and profile.") }
                }
            }
        }
        progressSaveJob = saveJob
        saveJob.start()
    }

    /**
     * Apply a pinch-zoom text-scale nudge from the reflowable reader, reusing
     * the existing display-settings persistence path.
     */
    fun nudgeTextScale(zoom: Float) {
        val cur = _uiState.value.displaySettings
        setDisplaySettings(cur.copy(textScale = (cur.textScale * zoom).coerceIn(0.6f, 3.0f)))
    }

    fun jumpToPage(page: Int) {
        onPageChanged(page.coerceAtLeast(0))
    }

    /**
     * Route a TOC section / bookmark jump to the reflowable reader by handing it
     * the target locator string; the reader decodes it and seeks. Used for
     * reflowable formats whose locations are opaque locator JSON rather than
     * page indices. Cleared once the reader reports [consumeJump].
     */
    fun jumpToLocation(location: String) {
        _uiState.update { it.copy(pendingJumpLocation = location) }
    }

    fun consumeJump() {
        _uiState.update { it.copy(pendingJumpLocation = null) }
    }

    fun setDisplaySettings(settings: ReaderDisplaySettings) {
        displayRevision++
        val authority = readerAuthority
        val session = configSession
        val normalized = settings.normalized()
        _uiState.update { it.copy(displaySettings = normalized) }
        viewModelScope.launch {
            val (serverId, profileId) = resolveScope()
            if (authority != null) {
                if (authority != ebookAuthorities?.snapshotDurableLoginAuthority()) return@launch
                val saved = identityTransitions?.withCurrentGeneration(authority.scope.identityGeneration) {
                    withContext(Dispatchers.IO) { localStateStore.writeDisplaySettings(serverId, profileId, normalized) }
                    true
                }
                if (saved != true) return@launch
            } else if (ebookAuthorities == null) withContext(Dispatchers.IO) {
                localStateStore.writeDisplaySettings(serverId, profileId, normalized)
            }
            if (session == null && ebookV2 != null) {
                _uiState.update { it.copy(syncError = "Reader settings are local. Reopen the reader to load server settings.") }
            }
            if (authority != null && authority == ebookAuthorities?.snapshotDurableLoginAuthority() && session != null) {
                val value = org.siloserver.silo.network.SiloJson.encodeToJsonElement(ReaderDisplaySettings.serializer(), normalized)
                val result = session.saveAndroidDisplay(value as kotlinx.serialization.json.JsonObject)
                if (authority == ebookAuthorities?.snapshotDurableLoginAuthority() && result !is ApiResult.Success)
                    _uiState.update { it.copy(syncError = "Reader settings are saved locally. Reopen the reader before retrying server sync.") }
            }
        }
    }

    fun setSections(sections: List<ReaderSection>) {
        _uiState.update { it.copy(sections = sections) }
    }

    private suspend fun <T : Any> bookmarkWrite(
        authority: org.siloserver.silo.network.DurableLoginAuthority, block: () -> T,
    ): T? {
        if (authority != ebookAuthorities?.snapshotDurableLoginAuthority()) return null
        return identityTransitions?.withCurrentGeneration(authority.scope.identityGeneration) {
            withContext(Dispatchers.IO) { block() }
        }
    }

    fun addBookmark() {
        val location = _uiState.value.progressLocation ?: "page:${_uiState.value.currentPage}"
        val authority = readerAuthority
        viewModelScope.launch {
            annotationMutex.withLock {
                if (authority == null || authority != ebookAuthorities?.snapshotDurableLoginAuthority()) {
                    _uiState.update { it.copy(syncError = "Bookmarks need a saved account and profile.") }
                    return@withLock
                }
                val (serverId, profileId) = resolveScope()
                val local = try {
                    bookmarkWrite(authority) { localStateStore.addBookmark(serverId, profileId, contentId, location,
                        loginId = authority.loginId, origin = authority.scope.serverUrl) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _uiState.update { it.copy(syncError = "Bookmark could not be saved locally.") }
                    null
                } ?: return@withLock
                _uiState.update { it.copy(bookmarks = it.bookmarks + local.toAnnotation(), syncError = null) }
                val result = ebookReaderRepository.createBookmark(contentId, local.id, location, authority.scope)
                if (authority != ebookAuthorities?.snapshotDurableLoginAuthority()) return@withLock
                if (result is ApiResult.Success) {
                    bookmarkWrite(authority) { localStateStore.removeBookmark(serverId, profileId, contentId, local.id) }
                    _uiState.update { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == local.id } + result.data) }
                } else _uiState.update { it.copy(syncError = "Bookmark is local. Reopen the reader to retry sync.") }
            }
        }
    }

    fun deleteBookmark(bookmark: EbookAnnotation) {
        val authority = readerAuthority
        viewModelScope.launch {
            annotationMutex.withLock {
                if (authority == null || authority != ebookAuthorities?.snapshotDurableLoginAuthority()) {
                    _uiState.update { it.copy(syncError = "Bookmark deletion needs the original account and profile.") }
                    return@withLock
                }
                val (serverId, profileId) = resolveScope()
                val pending = withContext(Dispatchers.IO) { localStateStore.listBookmarks(serverId, profileId, contentId) }
                    .find { it.id == bookmark.id }
                // An uncertain create may already exist remotely. Resolve the same ID before deleting it.
                val remote = if (pending?.loginId == authority.loginId && pending.origin == authority.scope.serverUrl && pending.deleteETag == null) {
                    val replay = ebookReaderRepository.createBookmark(contentId, pending.id, pending.location, authority.scope)
                    if (replay !is ApiResult.Success) {
                        _uiState.update { it.copy(syncError = "Bookmark could not be resolved for deletion. Reopen the reader to retry.") }
                        return@withLock
                    }
                    replay.data
                } else if (bookmark.etag == null && pending?.deleteETag != null) bookmark.copy(etag = pending.deleteETag)
                    else bookmark
                val localOnly = pending != null && pending.loginId == null && bookmark.etag == null
                if (!localOnly) {
                    val tag = remote.etag
                    if (tag.isNullOrBlank()) {
                        _uiState.update { it.copy(syncError = "Reopen the reader to load this bookmark before deleting it.") }
                        return@withLock
                    }
                    val saved = try {
                        bookmarkWrite(authority) { localStateStore.markBookmarkDelete(serverId, profileId, contentId,
                            remote.id, remote.location ?: bookmark.location.orEmpty(), authority.loginId, authority.scope.serverUrl, tag) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        _uiState.update { it.copy(syncError = "Bookmark deletion could not be saved locally.") }
                        null
                    } ?: return@withLock
                }
                val result = if (localOnly) ApiResult.Success(Unit)
                    else ebookReaderRepository.deleteAnnotation(contentId, remote, authority.scope)
                if (authority != ebookAuthorities?.snapshotDurableLoginAuthority()) return@withLock
                if (result is ApiResult.Success || (result is ApiResult.Error && result.code == 404)) {
                    bookmarkWrite(authority) { localStateStore.removeBookmark(serverId, profileId, contentId, bookmark.id) }
                    _uiState.update { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == bookmark.id }, syncError = null) }
                } else _uiState.update { it.copy(syncError = "Bookmark was kept. Reopen the reader to reload before deleting again.") }
            }
        }
    }

    private suspend fun resolveScope(): Pair<String, String> {
        readerAuthority?.let { return it.scope.serverId to it.scope.profileId.orEmpty() }
        val serverId = serverRegistry.activeServerId.value ?: DownloadEnqueuer.DEFAULT_SERVER_ID
        val profileId = profileRepository.getActiveProfileId() ?: DownloadEnqueuer.DEFAULT_PROFILE_ID
        return serverId to profileId
    }

    private fun OfflineMedia.toFileVersion(): FileVersion =
        FileVersion(
            fileId = fileId,
            fileName = sidecar.fileName ?: displayName,
            container = sidecar.container ?: displayName.substringAfterLast('.', missingDelimiterValue = ""),
        )

    private fun EbookLocalStateStore.BookmarkSnapshot.toAnnotation(): EbookAnnotation =
        localBookmarkAnnotation(
            id = id,
            contentId = contentId,
            location = location,
            createdAt = createdAtMs.toString(),
        )
}

private fun Int.coerceToKnownPageCount(pageCount: Int?): Int {
    val positivePage = coerceAtLeast(0)
    return if (pageCount != null && pageCount > 0) {
        positivePage.coerceAtMost(pageCount - 1)
    } else {
        positivePage
    }
}
