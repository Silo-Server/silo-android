package org.siloserver.silo.di

import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.createSiloClient
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.CatalogV2Api
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.ApiV2Probe
import org.koin.dsl.module

val networkModule = module {
    single<IdentityTransitionBarrier> { DefaultIdentityTransitionBarrier() }
    single<TokenManager> { TokenManagerImpl(get()) }
    single { createSiloClient(get(), getOrNull(), getOrNull(), getOrNull()) }
    single { ApiV2Gate(getOrNull()) }
    single { org.siloserver.silo.network.apiv2.MembershipV2Api(get(), get(), get()) }
    single { ApiV2Probe(get()) }
    single { AuthApi(get(), get()) }
    single { OnboardingApi(get(), get(), get()) }
    single<DeviceLoginApi> { DefaultDeviceLoginApi(get(), get()) }
    single { CatalogV2Api(get(), get(), get()) }
    single { CatalogApi(get(), get()) }
    single { PlaybackApi(get()) }
    single { PersonalDataApi(get(), get(), get()) }
    single { CollectionApi(get(), get(), get()) }
    single { ProfileApi(get(), get(), get()) }
    single { SectionApi(get(), get()) }
    single { RecommendationApi(get()) }
    single<RequestsApi> { DefaultRequestsApi(get(), get(), get()) }
    single<org.siloserver.silo.network.api.MetadataAiApi> { org.siloserver.silo.network.api.DefaultMetadataAiApi(get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.EventsSocketV2Api(get(), get(), get()) }
    single<org.siloserver.silo.network.HomeRealtimeClient> { org.siloserver.silo.network.DefaultHomeRealtimeClient(get()) }
    single<CalendarApi> { DefaultCalendarApi(get()) }
    single { HealthApi(get()) }
    single { BrandingApi(get()) }
    single { org.siloserver.silo.network.apiv2.SettingsReadsV2Api(get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.SettingsWritesV2Api(get(), get(), get()) }
    single { SettingsApi(get(), get(), get()) }
    single { LibraryPlaybackPrefsApi(get(), get()) }
    single { org.siloserver.silo.network.apiv2.DownloadRegistryV2Api(get(), get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.DownloadCreationV2Api(get(), get(), get(), get(), get()) }
    single { DownloadsApi(get(), get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.EbookReaderV2Api(get(), get(), get()) }
    single { EbookReaderApi(get(), get()) }
    single { org.siloserver.silo.network.apiv2.EbookAnnotationsV2Api(get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.SubtitleAiReadsV2Api(get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.SubtitleDownloadV2Api(get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.SubtitleReadsV2Api(get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.SubtitleAiCancelV2Api(get(), get(), get()) }
    single { org.siloserver.silo.network.apiv2.SubtitleAiCreateV2Api(get(), get(), get()) }
    single<SubtitlesApi> { DefaultSubtitlesApi(get(), get(), get(), get(), get(), get()) }
    single<NotificationsApi> { org.siloserver.silo.network.apiv2.NotificationsV2Api(get(), get(), get()) }
    single<PushRegistrationApi> { DefaultPushRegistrationApi(get()) }
    single<WatchTogetherApi> { DefaultWatchTogetherApi(get()) }
    single<DiagnosticsApi> { DefaultDiagnosticsApi(get(), gate = get()) }
}
