@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.android.di

import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.common.downloads.DownloadSubscriptionEvaluatorFactory
import org.siloserver.silo.common.downloads.DownloadSubscriptionWorker
import org.siloserver.silo.common.downloads.OfflineMediaResolver
import org.siloserver.silo.common.downloads.DownloadStorage
import org.siloserver.silo.common.downloads.DownloadWorker
import org.siloserver.silo.common.cast.SiloCastNsdBrowser
import org.siloserver.silo.common.pairing.PairingDeviceId
import org.siloserver.silo.common.pairing.CompanionDeviceLoginApprover
import org.siloserver.silo.common.pairing.CompanionPairingCoordinator
import org.siloserver.silo.common.pairing.CompanionPairingNsdBrowser
import org.siloserver.silo.common.pairing.CompanionPairingServerStore
import org.siloserver.silo.common.pairing.CompanionPairingTransportFactory
import org.siloserver.silo.common.pairing.RegistryCompanionPairingServerStore
import org.siloserver.silo.common.pairing.RepositoryCompanionDeviceLoginApprover
import org.siloserver.silo.common.pairing.TlsPskPairingClientTransport
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.AudioTrackManager
import org.siloserver.silo.common.player.AndroidSubtitlePresentation
import org.siloserver.silo.common.player.SiloPlayerFactory
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.audio.PassthroughSuppressionScope
import org.siloserver.silo.common.di.AUDIOBOOK_PLAYBACK_SESSION_MANAGER_QUALIFIER
import org.siloserver.silo.common.di.AUDIOBOOK_PLAYBACK_SESSION_LIFECYCLE_QUALIFIER
import org.siloserver.silo.common.player.SubtitleManager
import org.siloserver.silo.common.player.cast.CastPlaybackPreparer
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendFactory
import org.siloserver.silo.common.player.video.VideoPlaybackSessionCoordinator
import org.siloserver.silo.common.player.video.VideoPlaybackStarter
import org.siloserver.silo.android.BuildConfig
import org.siloserver.silo.common.network.AndroidDeviceMetadataProvider
import org.siloserver.silo.common.network.SiloClientBuildIdentity
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.common.network.DataStoreCleartextConsentStore
import org.siloserver.silo.common.settings.AndroidServerSettingsCache
import android.content.SharedPreferences
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.createSecureSharedPrefs
import org.siloserver.silo.android.push.AndroidPushRegistrar
import org.siloserver.silo.android.push.AndroidPushTokenProvider
import org.siloserver.silo.android.push.FirebaseAndroidPushTokenProvider
import org.siloserver.silo.android.push.PushMessageHandler
import org.siloserver.silo.android.push.PushNotificationPresenter
import org.siloserver.silo.android.ui.screens.browse.BrowseViewModel
import org.siloserver.silo.android.ui.screens.collections.CollectionDetailViewModel
import org.siloserver.silo.android.ui.screens.collections.LibraryCollectionsViewModel
import org.siloserver.silo.viewmodel.CalendarViewModel
import org.siloserver.silo.viewmodel.CollectionsViewModel
import org.siloserver.silo.android.ui.screens.detail.ItemDetailViewModel
import org.siloserver.silo.android.ui.screens.people.PersonDetailViewModel
import org.siloserver.silo.android.ui.screens.auth.LoginViewModel
import org.siloserver.silo.android.ui.screens.auth.ServerSetupViewModel
import org.siloserver.silo.android.ui.screens.auth.SetupViewModel
import org.siloserver.silo.android.ui.screens.auth.InviteClaimViewModel
import org.siloserver.silo.android.ui.screens.auth.SignupViewModel
import org.siloserver.silo.android.ui.screens.onboarding.OnboardingTourViewModel
import org.siloserver.silo.android.ui.screens.MainHeaderViewModel
import org.siloserver.silo.viewmodel.DevicePairingViewModel
import org.siloserver.silo.android.ui.screens.profiles.CreateProfileViewModel
import org.siloserver.silo.android.ui.screens.profiles.EditProfileViewModel
import org.siloserver.silo.android.ui.screens.profiles.ProfileSelectionViewModel
import org.siloserver.silo.android.ui.screens.servers.ServerListViewModel
import org.siloserver.silo.android.ui.screens.downloads.DownloadsViewModel
import org.siloserver.silo.viewmodel.HomeViewModel
import org.siloserver.silo.android.ui.screens.libraries.LibrariesViewModel
import org.siloserver.silo.viewmodel.FavoritesViewModel
import org.siloserver.silo.viewmodel.HistoryViewModel
import org.siloserver.silo.viewmodel.MyRequestsViewModel
import org.siloserver.silo.viewmodel.RecommendationsViewModel
import org.siloserver.silo.viewmodel.RequestDetailViewModel
import org.siloserver.silo.viewmodel.RequestSearchViewModel
import org.siloserver.silo.viewmodel.RequestsViewModel
import org.siloserver.silo.viewmodel.WatchlistViewModel
import org.siloserver.silo.android.ui.screens.player.MobileVideoPlaybackStarter
import org.siloserver.silo.android.ui.screens.player.PlayerViewModel
import org.siloserver.silo.android.ui.screens.reading.ReadingHubViewModel
import org.siloserver.silo.android.ui.screens.search.SearchViewModel
import org.siloserver.silo.android.ui.screens.settings.SettingsViewModel
import org.siloserver.silo.android.ui.screens.settings.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.android.cast.SharedPrefsSiloCastLastTargetStore
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.android.cast.SiloCastLastTargetStore
import org.siloserver.silo.android.cast.SiloCastSessionManager
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
    single<CleartextConsentStore> { DataStoreCleartextConsentStore(androidContext()) }
    single<CleartextOriginConsent> { get<CleartextConsentStore>() }
    // Single encrypted prefs handle shared between the server registry and the
    // token manager — opening it twice means two MasterKey lookups + decryption
    // passes on cold start.
    single<SharedPreferences> { createSecureSharedPrefs(androidContext()) }

    // Multi-server registry. Loaded synchronously in init so MainActivity's
    // `runBlocking { resolveStartDestination() }` reads consistent state.
    single<ServerRegistry> { AndroidServerRegistry(get(), get()) }

    // Persistent (EncryptedSharedPreferences-backed) replacement for the
    // commonMain in-memory TokenManager. Koin 3.1+ replaces same-key bindings
    // when the redefining module is loaded after the original — sharedModules()
    // is registered first in SiloApplication, so this wins.
    single<TokenManager> { EncryptedTokenManagerImpl(get(), get(), get()) }
    single { org.siloserver.silo.android.ui.screens.onboarding.OnboardingTourLocalCache(androidContext()) }

    // Offline-first Room store (Track B). Bound after sharedModules() so the
    // commonMain PersonalDataRepository's `getOrNull<UserItemStatePort>()` picks
    // up the Room-backed port and writes optimistic projection + outbox rows.
    single { org.siloserver.silo.common.data.db.SiloDatabase.build(androidContext()) }
    single<org.siloserver.silo.common.data.sync.OutboxSyncScheduler> {
        val appContext = androidContext().applicationContext
        org.siloserver.silo.common.data.sync.OutboxSyncScheduler {
            org.siloserver.silo.common.data.sync.SyncWorker.enqueue(appContext)
        }
    }
    single<org.siloserver.silo.repository.port.UserItemStatePort> {
        val tokenManager: TokenManager = get()
        org.siloserver.silo.common.data.repository.RoomUserItemStateRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
            // Drain is requested only when a write is left pending (resolve RETRIABLE).
            syncScheduler = get(),
        )
    }
    single<org.siloserver.silo.repository.port.HomeCachePort> {
        val tokenManager: TokenManager = get()
        org.siloserver.silo.common.data.repository.RoomHomeCacheRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
            identityTransitions = get(),
        )
    }
    single<org.siloserver.silo.repository.port.CatalogCachePort> {
        val tokenManager: TokenManager = get()
        org.siloserver.silo.common.data.repository.RoomCatalogCacheRepository(
            db = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
            identityTransitions = get(),
        )
    }
    single<org.siloserver.silo.repository.port.DownloadDeletionPort> {
        org.siloserver.silo.common.data.repository.RoomDownloadDeletionStore(db = get())
    }
    single<org.siloserver.silo.repository.DownloadSubscriptionRepository> {
        org.siloserver.silo.common.data.repository.RoomDownloadSubscriptionRepository(db = get())
    }
    single {
        val tokenManager: TokenManager = get()
        org.siloserver.silo.common.data.sync.SyncEngine(
            db = get(),
            personalDataApi = get(),
            ebookReaderApi = get(),
            snapshotProvider = { tokenManager.snapshotCurrentScope() },
        )
    }

    // App-wide services
    single<AndroidServerSettingsCache> { AndroidServerSettingsCache(androidContext()) }
    // The one place the phone app's BuildConfig crosses into android-shared:
    // every collaborator that reports client identity (headers, playback
    // context, diagnostics) resolves this rather than deriving its own answer.
    single { SiloClientBuildIdentity(BuildConfig.BUILD_NUMBER, BuildConfig.RELEASE_CHANNEL) }
    single<org.siloserver.silo.network.DeviceMetadataProvider> {
        AndroidDeviceMetadataProvider(
            androidContext(),
            platform = "android",
            buildIdentity = get(),
        )
    }
    single { SiloCastNsdBrowser(androidContext()) }
    single { CompanionPairingNsdBrowser(androidContext()) }
    single<CompanionPairingServerStore> { RegistryCompanionPairingServerStore(get(), get()) }
    single<CompanionDeviceLoginApprover> { RepositoryCompanionDeviceLoginApprover(get(), get()) }
    single<CompanionPairingTransportFactory> {
        CompanionPairingTransportFactory { target ->
            TlsPskPairingClientTransport.connect(target.host, target.port)
        }
    }
    single { CompanionPairingCoordinator(get(), get(), get()) }
    single<SiloCastLastTargetStore> { SharedPrefsSiloCastLastTargetStore(androidContext()) }
    single {
        SiloCastController(
            browser = get(),
            serverRegistry = get(),
            tokenManager = get(),
            deviceLoginApi = get(),
            lastTargetStore = get(),
            deviceNameProvider = {
                android.os.Build.MODEL?.trim()?.ifBlank { null } ?: "Android Phone"
            },
            deviceIdProvider = { PairingDeviceId.stable(androidContext()) },
        )
    }
    single<AndroidPushTokenProvider> { FirebaseAndroidPushTokenProvider(androidContext()) }
    single {
        AndroidPushRegistrar(
            tokenProvider = get(),
            repository = get(),
            deviceIdProvider = { PairingDeviceId.stable(androidContext()) },
        )
    }
    single {
        PushNotificationPresenter(
            context = androidContext(),
            notificationsRepository = get(),
            tokenManager = get(),
        )
    }
    single { PushMessageHandler(presenter = get()) }

    // Player infrastructure
    single {
        SubtitleManager(
            libassBridge = get(),
            presentation = AndroidSubtitlePresentation.Phone,
        )
    }
    single { AudioTrackManager() }
    single {
        VideoPlaybackBackendFactory(
            playerFactory = get(),
            audioTrackManager = get(),
            subtitleManager = get(),
        )
    }
    single { AudioCapabilityManager(androidContext()) }
    single { PlaybackCapabilityDetector(androidContext(), get(), get(), get()) }
    single {
        SiloPlayerFactory(
            context = androidContext(),
            tokenManager = get(),
            subtitleManager = get(),
            httpDataSourceFactory = get(org.siloserver.silo.common.di.PLAYER_HTTP_DATA_SOURCE_FACTORY_QUALIFIER),
            mediaAuthSession = get(),
            bandwidthMeter = get(),
            delayProcessor = get(),
            subtitleOffsetHolder = get(),
            libassBridge = get(),
        )
    }
    single { PlaybackSessionManager(get(), get(), get()) }
    single(AUDIOBOOK_PLAYBACK_SESSION_MANAGER_QUALIFIER) {
        PlaybackSessionManager(
            playbackRepository = get(),
            tokenManager = get(),
            networkEvidenceProvider = get(),
            passthroughSuppression = PassthroughSuppressionScope.None,
        )
    }
    // Google Cast (Chromecast) — phone only. The session manager owns the Cast
    // SDK lifecycle; the preparer opens the separate Tier-2 cast-capability
    // playback session so the raw phone stream is never cast.
    single { SiloCastSessionManager(androidContext()) }
    single {
        CastPlaybackPreparer(
            playbackRepository = get(),
            tokenManager = get(),
            networkEvidenceProvider = get(),
        )
    }
    factory<VideoPlaybackStarter>(named("mobileVideoPlaybackStarter")) {
        MobileVideoPlaybackStarter(
            catalogRepository = get(),
            playbackSessionManager = get(),
            profileRepository = get(),
            capabilityDetector = get(),
            playerSettingsStore = get(),
            sessionLifecycle = get(),
            reachabilityMonitor = get(),
            userItemStatePort = get(),
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
    single { org.siloserver.silo.common.downloads.DownloadMetadataStore(get()) }
    // One-time import of the legacy .record.json sidecar tree into Room.
    single { org.siloserver.silo.common.downloads.LegacyDownloadImporter(androidContext().filesDir, get()) }
    single { OfflineMediaResolver(get(), get(), get()) }
    single { DownloadEnqueuer(androidContext(), get(), get(), get(), get(), get(), get(), get()) }
    single { DownloadSubscriptionEvaluatorFactory(get(), get(), get()) }
    // CoroutineWorker constructed by Koin's WorkerFactory — see
    // SiloApplication.onCreate `workManagerFactory()` call.
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
    worker {
        DownloadSubscriptionWorker(
            appContext = androidContext(),
            params = get(),
            repository = get(),
            evaluatorFactory = get(),
            serverRegistry = get(),
            profileRepository = get(),
        )
    }
    // Kept for consistency, but DEAD AT RUNTIME: Koin's WorkManager factory
    // returns null on WM 2.10 + Koin 4.1.0, so AppWorkerFactory does the real
    // injection (see AppWorkerFactory). Update both if SyncWorker's deps change.
    worker {
        org.siloserver.silo.common.data.sync.SyncWorker(
            appContext = androidContext(),
            params = get(),
            syncEngine = get(),
        )
    }

    // ViewModels
    viewModel {
        PlayerViewModel(
            videoPlaybackCoordinator = get(),
            catalogRepository = get(),
            playbackSessionManager = get(),
            playbackAnalytics = get(),
            profileRepository = get(),
            personalDataRepository = get(),
            capabilityDetector = get(),
            offlineMediaResolver = get(),
            serverRegistry = get(),
            serverReachabilityMonitor = get(),
            playerSettingsStore = get(),
            introAutoSkipController = get(),
            sessionLifecycle = get(),
            sleepTimer = get(),
            subtitlesRepository = get(),
            userItemStatePort = get(),
            finalPlaybackPositionWriter = get(),
            sectionRepository = get(),
            castPlaybackPreparer = get(),
        )
    }
    viewModel { HomeViewModel(get(), get(), get(), get(), getOrNull(), get(), get()) }
    viewModel { MainHeaderViewModel(get()) }
    viewModel {
        LibrariesViewModel(
            get(), get(), get(),
            getOrNull<org.siloserver.silo.repository.port.UserItemStatePort>() ?: org.siloserver.silo.repository.port.NoOpUserItemStatePort,
            get(),
            get<org.siloserver.silo.android.ui.screens.browse.BrowsePrefsStore>(),
        )
    }
    viewModel { ReadingHubViewModel(get(), get(), get()) }
    viewModel { RecommendationsViewModel(get()) }
    viewModel { SearchViewModel(get()) }
    single {
        org.siloserver.silo.android.ui.screens.browse.BrowsePrefsStore(
            context = get(),
            serverRegistry = get(),
        )
    }
    single {
        org.siloserver.silo.android.ui.screens.home.HomeSectionPreferencesStore(
            context = get(),
            serverRegistry = get(),
        )
    }
    viewModel { params ->
        BrowseViewModel(
            get(),
            params.get(),
            getOrNull<org.siloserver.silo.repository.port.UserItemStatePort>() ?: org.siloserver.silo.repository.port.NoOpUserItemStatePort,
            get(),
        )
    }
    viewModel { params ->
        ItemDetailViewModel(
            get(), get(), get(), get(), get(), get(), get(), params.get(),
            getOrNull<org.siloserver.silo.repository.port.UserItemStatePort>() ?: org.siloserver.silo.repository.port.NoOpUserItemStatePort,
        )
    }
    viewModel { params -> PersonDetailViewModel(get(), params.get()) }
    viewModel { params -> LibraryCollectionsViewModel(get(), params.get()) }
    viewModel { FavoritesViewModel(get(), get()) }
    viewModel { WatchlistViewModel(get(), get()) }
    // Sort/filter for one saved list; callers scope it to the Activity keyed
    // by source so the For You grid and the standalone screens share it.
    viewModel { params ->
        org.siloserver.silo.android.ui.screens.personal.PersonalListControlsViewModel(
            source = params.get(),
            catalogRepository = get(),
        )
    }
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
            filterStore = org.siloserver.silo.android.ui.screens.calendar.CalendarPrefsStore(androidContext()),
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
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { DiagnosticsViewModel(get()) }
    viewModel { DownloadsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { org.siloserver.silo.android.ui.screens.pairing.CompanionPairingViewModel(get(), get()) }
    viewModel { ServerSetupViewModel(get(), get()) }
    viewModel { LoginViewModel(get()) }
    viewModel { SetupViewModel(get()) }
    viewModel { SignupViewModel(get()) }
    viewModel { InviteClaimViewModel(get(), get()) }
    viewModel { OnboardingTourViewModel(get(), get(), get(), get(), get()) }
    viewModel { ProfileSelectionViewModel(get(), get()) }
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
        org.siloserver.silo.common.audiobook.AudiobookBookmarksStore(androidContext().filesDir)
    }
    // Audiobook position now flows through the Track B outbox (UserItemStatePort)
    // — the old AudiobookPositionStore + AudiobookProgressSyncer were removed.
    // Still owns ebook bookmarks + display settings; reading POSITION now flows
    // through the Track B outbox (EbookProgressSyncer was removed).
    single {
        org.siloserver.silo.common.ebook.EbookLocalStateStore(androidContext().filesDir)
    }

    // Audiobook + book readers. SavedStateHandle is auto-injected via Koin's
    // viewModel scope wiring so the contentId nav arg flows through.
    viewModel {
        org.siloserver.silo.common.player.AudiobookPlayerViewModel(
            catalogRepository = get(),
            playbackSessionManager = get(AUDIOBOOK_PLAYBACK_SESSION_MANAGER_QUALIFIER),
            playbackSessionLifecycle = get(AUDIOBOOK_PLAYBACK_SESSION_LIFECYCLE_QUALIFIER),
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
        org.siloserver.silo.android.ui.screens.reader.ReaderViewModel(
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
    viewModel { org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherEntryViewModel(get()) }
    viewModel { org.siloserver.silo.android.ui.screens.watchtogether.SuggestToRoomViewModel(get()) }
    viewModel { params ->
        org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherLobbyViewModel(
            roomId = params.get(),
            repository = get(),
            roomSession = get(),
        )
    }
}
