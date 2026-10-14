package org.siloserver.silo.common.images

import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ImageResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Lets concurrent requests for the same remote image share one download.
 *
 * Coil runs each request on its own, so a detail page whose tap-time prefetch,
 * hero and blurred page surface all ask for the backdrop downloads that file
 * three times at once. Here the first request for a URL goes ahead; later ones
 * wait for it, then proceed and resolve from the disk (or memory) cache it
 * filled. A request the memory cache can already answer never waits, and if
 * the first request fails or is cancelled, the waiters make their own
 * requests.
 */
internal class SharedDownloadInterceptor(
    private val memoryCache: () -> MemoryCache?,
) : Interceptor {
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = (request.data as? String)
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        // Waiting only pays off when the first download lands in the disk cache.
        if (url == null || !request.diskCachePolicy.readEnabled || !request.diskCachePolicy.writeEnabled) {
            return chain.proceed()
        }
        if (request.memoryCachePolicy.readEnabled) {
            val memoryKey = MemoryCache.Key(request.memoryCacheKey ?: url, request.memoryCacheKeyExtras)
            if (memoryCache()?.get(memoryKey) != null) return chain.proceed()
        }

        val key = request.diskCacheKey ?: url
        val ticket = CompletableDeferred<Unit>()
        val leader = synchronized(inFlight) {
            inFlight[key] ?: run {
                inFlight[key] = ticket
                null
            }
        }
        if (leader != null) {
            leader.await()
            return chain.proceed()
        }
        try {
            return chain.proceed()
        } finally {
            synchronized(inFlight) {
                if (inFlight[key] === ticket) inFlight.remove(key)
            }
            ticket.complete(Unit)
        }
    }
}
