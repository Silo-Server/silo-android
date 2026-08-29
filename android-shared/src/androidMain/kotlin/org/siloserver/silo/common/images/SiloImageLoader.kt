package org.siloserver.silo.common.images

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import org.siloserver.silo.network.SiloOkHttp
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
