package org.siloserver.silo.common.di

import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import org.siloserver.silo.common.player.MediaAuthInterceptor
import org.siloserver.silo.common.player.MediaAuthSession
import org.siloserver.silo.common.player.PlaybackAnalyticsListener
import org.siloserver.silo.common.player.PlaybackNetworkEvidenceProvider
import org.siloserver.silo.common.player.PlaybackNetworkMonitor
import org.siloserver.silo.common.player.audio.DelayAudioProcessor
import org.siloserver.silo.common.player.buildPlayerOkHttpClient
import org.siloserver.silo.common.player.buildPlayerRefreshOkHttpClient
import org.siloserver.silo.common.player.subtitle.SubtitleOffsetHolder
import org.siloserver.silo.libass.LibassBridge
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Shared player DI module. Both `androidApp` and `androidTvApp` must include
 * this via `playerModule` in their Koin startup. Named singletons keep the
 * pooled media OkHttpClient distinct from anything Ktor may expose later.
 */
val PLAYER_OKHTTP_QUALIFIER = named("player-okhttp")
val PLAYER_TRANSPORT_OKHTTP_QUALIFIER = named("player-transport-okhttp")
val PLAYER_HTTP_DATA_SOURCE_FACTORY_QUALIFIER = named("player-http-data-source-factory")

val playerModule = module {
    // Lightweight bootstrap client for the refresh RPC — no interceptors so a
    // 401 on the refresh call can't loop back through MediaAuthInterceptor.
    single(named("player-refresh-okhttp")) { buildPlayerRefreshOkHttpClient() }

    single { MediaAuthSession(tokenManager = get(), refreshClient = get(named("player-refresh-okhttp"))) }
    single { MediaAuthInterceptor(authSession = get()) }

    single<OkHttpClient>(PLAYER_TRANSPORT_OKHTTP_QUALIFIER) { buildPlayerOkHttpClient() }

    // Reader/download callers still consume the authenticated OkHttp client.
    // Media3 itself uses the raw pooled transport below and applies auth in a
    // transport-neutral data-source wrapper.
    single<OkHttpClient>(PLAYER_OKHTTP_QUALIFIER) {
        get<OkHttpClient>(PLAYER_TRANSPORT_OKHTTP_QUALIFIER)
            .newBuilder()
            .addInterceptor(get<MediaAuthInterceptor>())
            .build()
    }
    single<OkHttpClient> { get(PLAYER_OKHTTP_QUALIFIER) }

    @OptIn(UnstableApi::class)
    single<HttpDataSource.Factory>(PLAYER_HTTP_DATA_SOURCE_FACTORY_QUALIFIER) {
        OkHttpDataSource.Factory(get<OkHttpClient>(PLAYER_TRANSPORT_OKHTTP_QUALIFIER))
    }

    @OptIn(UnstableApi::class)
    single { DefaultBandwidthMeter.Builder(androidContext()).build() }

    @OptIn(UnstableApi::class)
    single<PlaybackNetworkEvidenceProvider> {
        PlaybackNetworkMonitor(androidContext(), get())
    }

    @OptIn(UnstableApi::class)
    single { PlaybackAnalyticsListener() }

    @OptIn(UnstableApi::class)
    single { DelayAudioProcessor() }

    single { SubtitleOffsetHolder() }

    @OptIn(UnstableApi::class)
    single { LibassBridge(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) }
}
