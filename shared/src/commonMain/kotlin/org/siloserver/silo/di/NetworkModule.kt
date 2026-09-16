package org.siloserver.silo.di

import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.createSiloClient
import org.siloserver.silo.network.HomeRealtimeClient
import org.siloserver.silo.network.DefaultHomeRealtimeClient
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.*
import org.koin.dsl.module

val networkModule = module {
    single<IdentityTransitionBarrier> { DefaultIdentityTransitionBarrier() }
    single<TokenManager> { TokenManagerImpl(get()) }
    single { createSiloClient(get(), getOrNull(), getOrNull(), getOrNull()) }
    single { ApiV2Gate(getOrNull()) }
    single { MembershipV2Api(get(), get(), get()) }
    single { ApiV2Probe(get()) }
    single { ImageCapabilitiesApi(get(), get(), get()) }
    single { org.siloserver.silo.repository.ImageCapabilitiesSession(get(), get(), get()) }
    single { AuthApi(get(), get()) }
    single { OnboardingApi(get(), get(), get()) }
    single<DeviceLoginApi> { DefaultDeviceLoginApi(get(), get()) }
    single { CatalogV2Api(get(), get(), get()) }
    single { PersonRefreshV2Api(get(), get(), get()) }
    single { WatchDetailV2Api(get(), get(), get()) }
    single { CatalogApi(get(), get(), get(), get()) }
    single { PersonalDataApi(get(), get(), get()) }
    single { CollectionApi(get(), get(), get()) }
    single { ProfileApi(get(), get(), get()) }
    single { LibrarySectionItemsV2Api(get(), get(), get()) }
    single { HomeSectionsV2Api(get(), get(), get()) }
    single { SectionApi(get(), get(), get(), get()) }
    single { SimilarCardsV2Api(get(), get(), get()) }
    single { TasteProfileV2Api(get(), get(), get()) }
    single { DiscoverV2Api(get(), get(), get()) }
    single { RecommendationApi(get(), get(), get(), get()) }
    single<RequestsApi> { DefaultRequestsApi(get(), get(), get()) }
    single<MetadataAiApi> { DefaultMetadataAiApi(get(), get(), get()) }
    single { EventsSocketV2Api(get(), get(), get()) }
    single<HomeRealtimeClient> { DefaultHomeRealtimeClient(get()) }
    single<CalendarApi> { DefaultCalendarApi(get(), get(), get()) }
    single { HealthApi(get()) }
    single { BrandingApi(get()) }
    single { SettingsV2Api(get(), get(), get()) }
    single { SettingsApi(get()) }
    single { LibraryPlaybackPrefsApi(get()) }
    single { DownloadRegistryV2Api(get(), get(), get(), get()) }
    single { DownloadCreationV2Api(get(), get(), get(), get(), get()) }
    single { DownloadsApi(get(), get(), get()) }
    single { EbookReaderV2Api(get(), get(), get()) }
    single { EbookReaderApi(get()) }
    single { SubtitleAiReadsV2Api(get(), get(), get()) }
    single { SubtitleDownloadV2Api(get(), get(), get()) }
    single { SubtitleReadsV2Api(get(), get(), get()) }
    single { SubtitleAiCreateV2Api(get(), get(), get()) }
    single<SubtitlesApi> { DefaultSubtitlesApi(get(), get(), get(), get()) }
    single<NotificationsApi> { NotificationsV2Api(get(), get(), get()) }
    single<PushRegistrationApi> { DefaultPushRegistrationApi(get(), get(), get()) }
    single<WatchTogetherApi> { DefaultWatchTogetherApi(get(), get()) }
    single<DiagnosticsApi> { DefaultDiagnosticsApi(get(), gate = get()) }
}
