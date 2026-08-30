package org.siloserver.silo.common.images

import coil3.ImageLoader
import coil3.EventListener
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import org.siloserver.silo.network.SiloOkHttp
import org.siloserver.silo.common.diagnostics.DiagnosticsImageHealth
import org.siloserver.silo.common.diagnostics.DiagnosticsImageSource
import org.siloserver.silo.common.diagnostics.DiagnosticsImageStage
import java.io.File

/**
 * The one Coil image loader configuration for both the phone and TV apps
 * (each Application's `SingletonImageLoader.Factory` delegates here so the
 * two can't drift):
 *
 * - A generous on-disk artwork cache so posters/backdrops survive between
 *   sessions (Coil's default disk cap is small — 2% of free space, capped at
 *   250MB).
 * - An explicitly registered OkHttp fetcher on [SiloOkHttp.imageClient] so
 *   artwork shares the API clients' warm connection pool instead of a
 *   service-loader-built default client.
 * - Memory cache stays at Coil's heap-proportional default.
 */
fun buildSiloImageLoader(context: PlatformContext, cacheDir: File): ImageLoader =
    ImageLoader.Builder(context)
        .crossfade(true)
        .eventListenerFactory { DiagnosticsImageEventListener() }
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { SiloOkHttp.imageClient }))
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .build()

private class DiagnosticsImageEventListener : EventListener() {
    private var stage = DiagnosticsImageStage.UNKNOWN

    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
        stage = DiagnosticsImageStage.FETCH
    }

    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
        stage = DiagnosticsImageStage.DECODE
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val source = when (result.dataSource) {
            DataSource.MEMORY_CACHE, DataSource.MEMORY -> DiagnosticsImageSource.MEMORY
            DataSource.DISK -> DiagnosticsImageSource.DISK
            DataSource.NETWORK -> DiagnosticsImageSource.NETWORK
        }
        DiagnosticsImageHealth.success(source)
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        DiagnosticsImageHealth.failure(stage, result.throwable)
    }
}
