package org.siloserver.silo.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.image.ImageSize
import org.siloserver.silo.network.api.ImagesApi

/**
 * The image variant this app build wants baked into the image URLs the server
 * returns. Registered once by the app-level Koin module — the TV app asks for
 * [ImageSize.LARGE] because it draws posters and backdrops at full-screen
 * sizes; the phone app registers nothing and keeps the server's default.
 */
data class PreferredImageSize(val value: String)

/**
 * Resolves the `image_size` query parameter for catalog-shaped requests.
 *
 * Two things have to be true before the parameter is sent: this build asked for
 * a variant at all ([PreferredImageSize] is registered), and the connected
 * server advertises that variant on `/api/v1/images/capability`. The probe runs
 * lazily on the first request that would use it and is cached for the lifetime
 * of this singleton, matching how `EbookReaderRepository` caches its own
 * capability. Any failure — 404 from a server that predates the feature,
 * offline, an unparseable body — resolves to "send nothing", which is exactly
 * the pre-feature behaviour.
 *
 * Keeping the decision here rather than in the ViewModels is deliberate: no
 * screen has to know the feature exists, and the phone/TV split is one DI line.
 */
class ImageSizeSelector(
    private val api: ImagesApi,
    preferred: PreferredImageSize? = null,
) {
    private val preferred: String? = preferred?.value

    private class Resolution(val size: String?)

    private val mutex = Mutex()
    private var cached: Resolution? = null

    /** The size to send, or null to omit the parameter entirely. */
    suspend fun current(): String? {
        if (preferred == null) return null
        cached?.let { return it.size }
        return mutex.withLock {
            (cached ?: probe().also { cached = it }).size
        }
    }

    /** Drops the cached probe result, e.g. after switching servers. */
    fun reset() {
        cached = null
    }

    private suspend fun probe(): Resolution {
        val size = when (val result = api.getCapability()) {
            is ApiResult.Success -> preferred?.takeIf { result.data.supports(it) }
            else -> null
        }
        return Resolution(size)
    }
}

/**
 * Appends `image_size` when one applies. Call sites resolve the value before
 * building the request (the selector is suspending) and pass it here so the
 * parameter name has exactly one definition.
 */
internal fun HttpRequestBuilder.imageSizeParameter(size: String?) {
    size?.let { parameter(ImageSize.PARAM, it) }
}
