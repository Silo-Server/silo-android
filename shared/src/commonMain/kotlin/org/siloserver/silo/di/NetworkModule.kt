package org.siloserver.silo.di

import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.createSiloClient
import org.siloserver.silo.network.api.*
import org.koin.dsl.module

val networkModule = module {
    single<TokenManager> { TokenManagerImpl() }
    single { createSiloClient(get(), getOrNull()) }
    single { AuthApi(get()) }
    single<DeviceLoginApi> { DefaultDeviceLoginApi(get()) }
    single { CatalogApi(get()) }
    single { PlaybackApi(get()) }
    single { PersonalDataApi(get()) }
    single { CollectionApi(get()) }
    single { ProfileApi(get()) }
    single { SectionApi(get()) }
    single { RecommendationApi(get()) }
    single<RequestsApi> { DefaultRequestsApi(get()) }
    single<CalendarApi> { DefaultCalendarApi(get()) }
    single { HealthApi(get()) }
    single { SettingsApi(get()) }
    single { LibraryPlaybackPrefsApi(get()) }
    single { TrackPrefsApi(get()) }
    single { DownloadsApi(get()) }
    single { EbookReaderApi(get()) }
    single<SubtitlesApi> { DefaultSubtitlesApi(get()) }
    single<NotificationsApi> { DefaultNotificationsApi(get()) }
    single<AdminApi> { DefaultAdminApi(get()) }
    single<WatchTogetherApi> { DefaultWatchTogetherApi(get()) }
}
