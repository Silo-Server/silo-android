package org.siloserver.silo.common.images

import coil3.ImageLoader
import coil3.Uri
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import kotlinx.coroutines.CompletableDeferred

/**
 * Lets concurrent requests for the same remote image share one download.
 *
 * Coil runs each request on its own, so a detail page whose tap-time prefetch,
 * hero and blurred page surface all ask for the backdrop downloads that file
 * three times at once. Wrapping the network fetcher, the first fetch for a URL
 * goes ahead; later ones wait for it, then fetch from the disk cache it
 * filled. If the first fetch fails or is cancelled, the waiters fetch for
 * themselves.
 *
 * Coil consults its memory cache before it creates a fetcher, so a request
 * memory can serve never waits here, and a waiter waits only for the download,
 * not for the first request's decode.
 */
internal class SharedDownloadFetcherFactory(
    private val network: Fetcher.Factory<Uri>,
) : Fetcher.Factory<Uri> {
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val fetcher = network.create(data, options, imageLoader) ?: return null
        // Waiting only pays off when the first download lands in the disk cache.
        if (!options.diskCachePolicy.readEnabled || !options.diskCachePolicy.writeEnabled) return fetcher
        val key = options.diskCacheKey ?: data.toString()
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? = sharedFetch(key, fetcher)
        }
    }

    private suspend fun sharedFetch(key: String, fetcher: Fetcher): FetchResult? {
        val ticket = CompletableDeferred<Unit>()
        val leader = synchronized(inFlight) {
            inFlight[key] ?: run {
                inFlight[key] = ticket
                null
            }
        }
        if (leader != null) {
            leader.await()
            return fetcher.fetch()
        }
        try {
            return fetcher.fetch()
        } finally {
            synchronized(inFlight) {
                if (inFlight[key] === ticket) inFlight.remove(key)
            }
            ticket.complete(Unit)
        }
    }
}
