@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.continuum.app.android.di

import com.continuum.app.common.downloads.DownloadEnqueuer
import com.continuum.app.common.downloads.OfflineMediaResolver
import com.continuum.app.common.downloads.DownloadStorage
import com.continuum.app.common.downloads.DownloadWorker
import com.continuum.app.common.player.AudioCapabilityManager
import com.continuum.app.common.player.AudioTrackManager
import com.continuum.app.common.player.ContinuumPlayerFactory
import com.continuum.app.common.player.PlaybackCapabilityDetector
import com.continuum.app.common.player.PlaybackSessionManager
import com.continuum.app.common.player.SubtitleManager
import com.continuum.app.common.player.backend.VideoPlaybackBackendFactory
import com.continuum.app.common.player.video.VideoPlaybackSessionCoordinator
import com.continuum.app.common.player.video.VideoPlaybackStarter
import com.continuum.app.common.network.AndroidDeviceMetadataProvider
import com.continuum.app.common.settings.AndroidServerSettingsCache
import android.content.SharedPreferences
import com.continuum.app.network.AndroidServerRegistry
import com.continuum.app.network.EncryptedTokenManagerImpl
import com.continuum.app.network.ServerRegistry
import com.continuum.app.network.TokenManager
import com.continuum.app.network.createSecureSharedPrefs
import com.continuum.app.android.ui.screens.admin.AdminEntryViewModel
import com.continuum.app.android.ui.screens.admin.AdminLogsViewModel
import com.continuum.app.android.ui.screens.admin.AdminScansViewModel
import com.continuum.app.android.ui.screens.admin.AdminSessionsViewModel
import com.continuum.app.android.ui.screens.browse.BrowseViewModel
import com.continuum.app.android.ui.screens.collections.CollectionDetailViewModel
import com.continuum.app.android.ui.screens.collections.LibraryCollectionsViewModel
import com.continuum.app.viewmodel.AdminStatsViewModel
import com.continuum.app.viewmodel.AdminUserEditViewModel
import com.continuum.app.viewmodel.AdminUsersViewModel
import com.continuum.app.viewmodel.CalendarViewModel
import com.continuum.app.viewmodel.CollectionsViewModel
import com.continuum.app.android.ui.screens.detail.ItemDetailViewModel
import com.continuum.app.android.ui.screens.people.PersonDetailViewModel
import com.continuum.app.android.ui.screens.auth.LoginViewModel
import com.continuum.app.android.ui.screens.auth.ServerSetupViewModel
import com.continuum.app.android.ui.screens.auth.SetupViewModel
import com.continuum.app.android.ui.screens.auth.SignupViewModel
import com.continuum.app.android.ui.screens.MainHeaderViewModel
import com.continuum.app.viewmodel.DevicePairingViewModel
import com.continuum.app.android.ui.screens.profiles.CreateProfileViewModel
import com.continuum.app.android.ui.screens.profiles.EditProfileViewModel
import com.continuum.app.android.ui.screens.profiles.ProfileSelectionViewModel
import com.continuum.app.android.ui.screens.servers.ServerListViewModel
import com.continuum.app.android.ui.screens.downloads.DownloadsViewModel
import com.continuum.app.viewmodel.HomeViewModel
import com.continuum.app.android.ui.screens.libraries.LibrariesViewModel
import com.continuum.app.viewmodel.FavoritesViewModel
import com.continuum.app.viewmodel.HistoryViewModel
import com.continuum.app.viewmodel.MyRequestsViewModel
import com.continuum.app.viewmodel.RecommendationsViewModel
import com.continuum.app.viewmodel.RequestDetailViewModel
import com.continuum.app.viewmodel.RequestSearchViewModel
import com.continuum.app.viewmodel.RequestsViewModel
import com.continuum.app.viewmodel.WatchlistViewModel
import com.continuum.app.android.ui.screens.player.MobileVideoPlaybackStarter
import com.continuum.app.android.ui.screens.player.PlayerViewModel
import com.continuum.app.android.ui.screens.reading.ReadingHubViewModel
import com.continuum.app.android.ui.screens.search.SearchViewModel
import com.continuum.app.android.ui.screens.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Android-specific Koin module.
 *
 * Provides player infrastructure (ExoPlayer factory, session manager, managers)
 * and all Android ViewModels. Player components are singletons; ViewModels use
 * the viewModel DSL for proper lifecycle integration.
 */
val androidModule = module {
    // Single encrypted prefs handle shared between the server registry and the
    // token manager — opening it twice means two MasterKey lookups + decryption
    // passes on cold start.
    single<SharedPreferences> { createSecureSharedPrefs(androidContext()) }

    // Multi-server registry. Loaded synchronously in init so MainActivity's
    // `runBlocking { resolveStartDestination() }` reads consistent state.
    single<ServerRegistry> { AndroidServerRegistry(androidContext(), get()) }

    // Persistent (EncryptedSharedPreferences-backed) replacement for the
    // commonMain in-memory TokenManager. Koin 3.1+ replaces same-key bindings
    // when the redefining module is loaded after the original — sharedModules()
    // is registered first in ContinuumApplication, so this wins.
    single<TokenManager> { EncryptedTokenManagerImpl(get(), get()) }

    // Offline-first Room store (Track B). Bound after sharedModules() so the
    // commonMain PersonalDataRepository's `getOrNull<UserItemStatePort>()` picks
    // up the Room-backed port and writes optimistic projection + outbox rows.
    single { com.continuum.app.common.data.db.SiloDatabase.build(androidContext()) }
    single<com.continuum.app.common.data.sync.OutboxSyncScheduler> {
        val appContext = androidContext().applicationContext
        com.continuum.app.common.data.sync.OutboxSyncScheduler {
            com.continuum.app.common.data.sync.SyncWorker.enqueue(appContext)
        }
    }
    single<com.continuum.app.repository.port.UserItemStatePort> {
        val tokenManager: TokenManager = get()
        com.continuum.app.common.data.repository.RoomUserItemStateRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
            // Drain is requested only when a write is left pending (resolve RETRIABLE).
            syncScheduler = get(),
        )
    }
    single<com.continuum.app.repository.port.HomeCachePort> {
        val tokenManager: TokenManager = get()
        com.continuum.app.common.data.repository.RoomHomeCacheRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
        )
    }
    single<com.continuum.app.repository.port.CatalogCachePort> {
        val tokenManager: TokenManager = get()
        com.continuum.app.common.data.repository.RoomCatalogCacheRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
        )
    }
    single<com.continuum.app.repository.port.DownloadDeletionPort> {
        com.continuum.app.common.data.repository.RoomDownloadDeletionStore(db = get())
    }
    single {
        val tokenManager: TokenManager = get()
        com.continuum.app.common.data.sync.SyncEngine(
            db = get(),
            personalDataApi = get(),
            ebookReaderApi = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
        )
    }

    // App-wide services
    single<AndroidServerSettingsCache> { AndroidServerSettingsCache(androidContext()) }
    single<com.continuum.app.network.DeviceMetadataProvider> {
        AndroidDeviceMetadataProvider(androidContext(), platform = "android")
    }

    // Player infrastructure
    single { SubtitleManager() }
    single { AudioTrackManager() }
    single {
        VideoPlaybackBackendFactory(
            playerFactory = get(),
            audioTrackManager = get(),
            subtitleManager = get(),
        )
    }
    single { AudioCapabilityManager(androidContext()) }
    single { PlaybackCapabilityDetector(androidContext(), get()) }
    single {
        ContinuumPlayerFactory(
            context = androidContext(),
            tokenManager = get(),
            subtitleManager = get(),
            okHttpClient = get(com.continuum.app.common.di.PLAYER_OKHTTP_QUALIFIER),
            delayProcessor = get(),
            subtitleOffsetHolder = get(),
        )
    }
    single { PlaybackSessionManager(get(), get()) }
    factory<VideoPlaybackStarter>(named("mobileVideoPlaybackStarter")) {
        MobileVideoPlaybackStarter(
            catalogRepository = get(),
            playbackSessionManager = get(),
            profileRepository = get(),
            capabilityDetector = get(),
            playerSettingsStore = get(),
            sessionLifecycle = get(),
        )
    }
    factory {
        VideoPlaybackSessionCoordinator(
            starter = get(named("mobileVideoPlaybackStarter")),
        )
    }

    // Offline downloads — public MediaStore bytes plus private sidecars.
    // Media files keep original names so other Android readers/players can
    // discover them under Downloads/Silo.
    single { DownloadStorage(androidContext()) }
    // Download metadata now lives in Room (replaces the .record.json sidecars).
    single { com.continuum.app.common.downloads.DownloadMetadataStore(get()) }
    // One-time import of the legacy .record.json sidecar tree into Room.
    single { com.continuum.app.common.downloads.LegacyDownloadImporter(androidContext().filesDir, get()) }
    single { OfflineMediaResolver(get(), get(), get()) }
    single { DownloadEnqueuer(androidContext(), get(), get(), get(), get(), get(), get(), get()) }
    // CoroutineWorker constructed by Koin's WorkerFactory — see
    // ContinuumApplication.onCreate `workManagerFactory()` call.
    worker {
        DownloadWorker(
            appContext = androidContext(),
            params = get(),
            repository = get(),
            storage = get(),
            metadataStore = get(),
            httpClient = get(),
        )
    }
    // Kept for consistency, but DEAD AT RUNTIME: Koin's WorkManager factory
    // returns null on WM 2.10 + Koin 4.1.0, so AppWorkerFactory does the real
    // injection (see AppWorkerFactory). Update both if SyncWorker's deps change.
    worker {
        com.continuum.app.common.data.sync.SyncWorker(
            appContext = androidContext(),
            params = get(),
            syncEngine = get(),
        )
    }

    // ViewModels
    factory {
        PlayerViewModel(
            videoPlaybackCoordinator = get(),
            catalogRepository = get(),
            playbackSessionManager = get(),
            profileRepository = get(),
            personalDataRepository = get(),
            capabilityDetector = get(),
            offlineMediaResolver = get(),
            serverRegistry = get(),
            playerSettingsStore = get(),
            introAutoSkipController = get(),
            sessionLifecycle = get(),
            sleepTimer = get(),
            subtitlesRepository = get(),
            userItemStatePort = get(),
            outboxSyncScheduler = get(),
        )
    }
    viewModel { HomeViewModel(get(), get(), get(), get()) }
    viewModel { MainHeaderViewModel(get()) }
    viewModel {
        LibrariesViewModel(
            get(), get(), get(),
            getOrNull<com.continuum.app.repository.port.UserItemStatePort>() ?: com.continuum.app.repository.port.NoOpUserItemStatePort,
        )
    }
    viewModel { ReadingHubViewModel(get(), get(), get()) }
    viewModel { RecommendationsViewModel(get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { params ->
        BrowseViewModel(
            get(),
            params.get(),
            getOrNull<com.continuum.app.repository.port.UserItemStatePort>() ?: com.continuum.app.repository.port.NoOpUserItemStatePort,
        )
    }
    viewModel { params ->
        ItemDetailViewModel(
            get(), get(), get(), get(), get(), params.get(),
            getOrNull<com.continuum.app.repository.port.UserItemStatePort>() ?: com.continuum.app.repository.port.NoOpUserItemStatePort,
        )
    }
    viewModel { params -> PersonDetailViewModel(get(), params.get()) }
    viewModel { params -> LibraryCollectionsViewModel(get(), params.get()) }
    viewModel { FavoritesViewModel(get()) }
    viewModel { WatchlistViewModel(get()) }
    viewModel { HistoryViewModel(get()) }
    viewModel { CollectionsViewModel(get()) }
    viewModel { params -> CollectionDetailViewModel(get(), get(), params.get()) }
    viewModel { RequestsViewModel(get()) }
    viewModel { RequestSearchViewModel(get()) }
    viewModel { MyRequestsViewModel(get()) }
    // Platform supplies "today" and the IANA timezone; the shared ViewModel's
    // week math stays deterministic in commonTest (no Clock.System default).
    viewModel {
        CalendarViewModel(
            repository = get(),
            timezoneId = java.util.TimeZone.getDefault().id,
            todayProvider = { java.time.LocalDate.now().toString() },
        )
    }
    viewModel { params ->
        val args = params.get<Pair<String, Int>>()
        RequestDetailViewModel(
            repository = get(),
            mediaType = args.first,
            tmdbId = args.second,
        )
    }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { AdminEntryViewModel(get(), get()) }
    viewModel { AdminStatsViewModel(get()) }
    viewModel { AdminUsersViewModel(get()) }
    viewModel { AdminUserEditViewModel(get()) }
    viewModel { AdminSessionsViewModel(get()) }
    viewModel { AdminLogsViewModel(get()) }
    viewModel { AdminScansViewModel(get(), get()) }
    viewModel { DownloadsViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ServerSetupViewModel(get()) }
    viewModel { LoginViewModel(get()) }
    viewModel { SetupViewModel(get()) }
    viewModel { SignupViewModel(get()) }
    viewModel { ProfileSelectionViewModel(get()) }
    viewModel { CreateProfileViewModel(get()) }
    viewModel { EditProfileViewModel(get()) }
    viewModel { ServerListViewModel(get(), get()) }
    viewModel { params ->
        val args = params.get<Pair<String?, String?>>()
        DevicePairingViewModel(
            repository = get(),
            initialToken = args.first,
            initialCode = args.second,
        )
    }

    // Audiobook bookmarks store — per-(server, profile, contentId) JSON
    // files under filesDir/audiobook_bookmarks. Local-only for v1.
    single {
        com.continuum.app.common.audiobook.AudiobookBookmarksStore(androidContext().filesDir)
    }
    // Audiobook position now flows through the Track B outbox (UserItemStatePort)
    // — the old AudiobookPositionStore + AudiobookProgressSyncer were removed.
    // Still owns ebook bookmarks + display settings; reading POSITION now flows
    // through the Track B outbox (EbookProgressSyncer was removed).
    single {
        com.continuum.app.common.ebook.EbookLocalStateStore(androidContext().filesDir)
    }

    // Audiobook + book readers. SavedStateHandle is auto-injected via Koin's
    // viewModel scope wiring so the contentId nav arg flows through.
    viewModel {
        com.continuum.app.common.player.AudiobookPlayerViewModel(
            catalogRepository = get(),
            playbackSessionManager = get(),
            capabilityDetector = get(),
            bookmarksStore = get(),
            userItemStatePort = get(),
            outboxSyncScheduler = get(),
            serverRegistry = get(),
            profileRepository = get(),
            offlineMediaResolver = get(),
            audiobookSettings = get(),
            savedStateHandle = get(),
        )
    }
    viewModel {
        com.continuum.app.android.ui.screens.reader.ReaderViewModel(
            catalogRepository = get(),
            ebookReaderRepository = get(),
            offlineMediaResolver = get(),
            localStateStore = get(),
            userItemStatePort = get(),
            outboxSyncScheduler = get(),
            serverRegistry = get(),
            profileRepository = get(),
            savedStateHandle = get(),
        )
    }
    viewModel { com.continuum.app.android.ui.screens.watchtogether.WatchTogetherEntryViewModel(get()) }
    viewModel { params ->
        com.continuum.app.android.ui.screens.watchtogether.WatchTogetherLobbyViewModel(
            roomId = params.get(),
            repository = get(),
        )
    }
}
