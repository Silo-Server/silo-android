package org.siloserver.silo.di

import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.createSiloClient
import org.siloserver.silo.network.api.*
import org.koin.dsl.module

val networkModule = module {
    single<IdentityTransitionBarrier> { DefaultIdentityTransitionBarrier() }
    single<TokenManager> { TokenManagerImpl(get()) }
    single { createSiloClient(get(), getOrNull(), getOrNull(), getOrNull()) }
    single { AuthApi(get()) }
    single { OnboardingApi(get()) }
    single<DeviceLoginApi> { DefaultDeviceLoginApi(get()) }
    single { CatalogApi(get()) }
    single { PlaybackApi(get()) }
    single { PersonalDataApi(get()) }
    single { CollectionApi(get()) }
    single { ProfileApi(get()) }
    single { SectionApi(get()) }
    single { RecommendationApi(get()) }
    single<RequestsApi> { DefaultRequestsApi(get()) }
    single<org.siloserver.silo.network.api.MetadataAiApi> { org.siloserver.silo.network.api.DefaultMetadataAiApi(get()) }
    single<org.siloserver.silo.network.HomeRealtimeClient> { org.siloserver.silo.network.DefaultHomeRealtimeClient(get(), get()) }
    single<CalendarApi> { DefaultCalendarApi(get()) }
    single { HealthApi(get()) }
    single { BrandingApi(get()) }
    single { SettingsApi(get()) }
    single { LibraryPlaybackPrefsApi(get()) }
    single { DownloadsApi(get()) }
    single { EbookReaderApi(get()) }
    single<SubtitlesApi> { DefaultSubtitlesApi(get()) }
    single<NotificationsApi> { DefaultNotificationsApi(get()) }
    single<PushRegistrationApi> { DefaultPushRegistrationApi(get()) }
    single<WatchTogetherApi> { DefaultWatchTogetherApi(get()) }
    single<DiagnosticsApi> { DefaultDiagnosticsApi(get()) }
}
