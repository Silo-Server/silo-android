package org.siloserver.silo.di

import org.siloserver.silo.domain.GetHomeDataUseCase
import org.siloserver.silo.domain.ManagePlaybackUseCase
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.repository.AdminRepository
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.CalendarRepository
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.CollectionRepository
import org.siloserver.silo.repository.DownloadsRepository
import org.siloserver.silo.repository.EbookReaderRepository
import org.siloserver.silo.repository.SubtitlesRepository
import org.siloserver.silo.repository.LibraryPlaybackPrefsRepository
import org.siloserver.silo.repository.NotificationsRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.PushRegistrationRepository
import org.siloserver.silo.repository.RecommendationRepository
import org.siloserver.silo.repository.RequestsRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.SettingsRepository
import org.siloserver.silo.repository.WatchTogetherRepository
import org.koin.dsl.module

/**
 * Koin module providing all repository and domain use case instances.
 *
 * Dependencies:
 * - API classes (AuthApi, CatalogApi, etc.) from networkModule (Agent 2)
 * - TokenManager from networkModule (Agent 2)
 *
 * All repositories are singletons; they are stateless wrappers around API classes
 * and TokenManager, so sharing instances is safe and efficient.
 */
val repositoryModule = module {
    // Repositories — `getOrNull()` for ServerRegistry / HealthApi keeps these
    // working when the multi-server platform binding isn't installed
    // (commonMain tests, hypothetical iOS reuse). Both repos no-op the
    // multi-server side effects when the registry is null.
    single { AuthRepository(get(), get(), getOrNull(), getOrNull(), getOrNull()) }
    single { DeviceLoginRepository(get()) }
    single { CatalogRepository(get(), getOrNull<org.siloserver.silo.repository.port.CatalogCachePort>() ?: org.siloserver.silo.repository.port.NoOpCatalogCachePort) }
    single { CalendarRepository(get()) }
    single { PlaybackRepository(get()) }
    // `getOrNull()` picks up the Room-backed ports when the Android platform
    // module binds them (Track B local-first writes + offline read cache); falls
    // back to the network-only no-op ports in commonMain tests / when unbound.
    single {
        PersonalDataRepository(
            get(),
            getOrNull<org.siloserver.silo.repository.port.UserItemStatePort>() ?: org.siloserver.silo.repository.port.NoOpUserItemStatePort,
            getOrNull<org.siloserver.silo.repository.port.CatalogCachePort>() ?: org.siloserver.silo.repository.port.NoOpCatalogCachePort,
        )
    }
    single { ProfileRepository(get(), get(), getOrNull(), get(), get()) }
    single { CollectionRepository(get()) }
    single { SectionRepository(get(), getOrNull<org.siloserver.silo.repository.port.CatalogCachePort>() ?: org.siloserver.silo.repository.port.NoOpCatalogCachePort) }
    single { RecommendationRepository(get()) }
    single { RequestsRepository(get()) }
    single { RequestsFeatureStore(get()) }
    single { org.siloserver.silo.repository.DiagnosticsRepository(get()) }
    single { org.siloserver.silo.model.feature.DiagnosticsFeatureStore(get()) }
    single { org.siloserver.silo.repository.MetadataAiRepository(get()) }
    single { org.siloserver.silo.model.feature.MetadataAiFeatureStore(get()) }
    single { org.siloserver.silo.repository.HomeRealtimeCoordinator(get(), get()) }
    single { SettingsRepository(get()) }
    single { LibraryPlaybackPrefsRepository(get()) }
    single { DownloadsRepository(get(), getOrNull<org.siloserver.silo.repository.port.DownloadDeletionPort>() ?: org.siloserver.silo.repository.port.NoOpDownloadDeletionPort) }
    single { EbookReaderRepository(get()) }
    single { SubtitlesRepository(get()) }
    single { AdminRepository(get()) }
    single { PushRegistrationRepository(get()) }

    // REST-backed inbox state plus a realtime factory that builds the default
    // websocket client from the shared HttpClient + NotificationsApi. The
    // factory is lazy so a connection is only minted when connectRealtime() runs.
    single {
        NotificationsRepository(
            api = get(),
            realtimeFactory = {
                org.siloserver.silo.network.DefaultNotificationsRealtimeClient(
                    client = get(),
                    api = get(),
                )
            },
        )
    }

    // One room's snapshot/suggestions state + WS lifecycle. The realtime factory
    // builds the per-room socket client from the shared HttpClient + TokenManager
    // (query-param auth). Lazy so a socket is only minted when connect() runs.
    single {
        WatchTogetherRepository(
            api = get(),
            realtimeFactory = {
                org.siloserver.silo.network.DefaultWatchTogetherRealtimeClient(
                    client = get(),
                    tokenManager = get(),
                )
            },
        )
    }

    // Per-session playback control socket (admin remote control). Parallel to
    // the watch-together realtime client — same HttpClient + query-param auth.
    // FACTORY, not single: the client holds one mutable socket session, so each
    // player-screen controller must get its own instance (mirrors how the WT
    // repository mints a fresh client per connect) — a shared singleton would
    // let a second player clobber the first's socket.
    factory<org.siloserver.silo.network.PlaybackRealtimeClient> {
        org.siloserver.silo.network.DefaultPlaybackRealtimeClient(
            client = get(),
            tokenManager = get(),
        )
    }

    // Domain use cases
    single { GetHomeDataUseCase(get(), get()) }
    single { ManagePlaybackUseCase(get(), get()) }
    single { MediaActionsCoordinator(get()) }
}
