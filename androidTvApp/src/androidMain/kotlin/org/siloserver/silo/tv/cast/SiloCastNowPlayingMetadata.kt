package org.siloserver.silo.tv.cast

import java.net.URI
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.isEpisodeItemType
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository

/**
 * Poster and synopsis a TV adds to its control state, so a controller with no
 * catalog access of its own (Home Assistant) can show real artwork. The TV
 * resolves them with its own sign-in; the poster URL is absolute and signed,
 * so the controller downloads it from Silo or the CDN without credentials.
 * Mirrors silo-apple's `SiloControlNowPlayingMetadata`.
 */
data class SiloCastNowPlayingMetadata(
    val posterUrl: String?,
    val overview: String?,
) {
    companion object {
        /**
         * 2:3 artwork: an episode's own poster is its 16:9 still, which spoiler
         * protection may hide, so episodes send only the series poster. The
         * synopsis falls back to the series when the episode has none.
         */
        fun from(item: ItemDetail, series: ItemDetail?): SiloCastNowPlayingMetadata =
            SiloCastNowPlayingMetadata(
                posterUrl = if (isEpisodeItemType(item.type)) series?.posterUrl.nonBlank() else item.posterUrl.nonBlank(),
                overview = item.overview.nonBlank() ?: series?.overview.nonBlank(),
            )

        private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** When a signed artwork URL stops working. */
object SiloCastArtworkExpiry {
    /** How long before expiry the TV fetches a re-signed URL. */
    const val REFRESH_MARGIN_MS: Long = 10 * 60 * 1000L
    /**
     * Re-check interval for a URL whose expiry can't be read (Cloudflare token
     * URLs). Public URLs never expire but cost one cheap request.
     */
    const val UNKNOWN_EXPIRY_REFRESH_MS: Long = 30 * 60 * 1000L
    /** Floor between fetches, so a URL that is already stale can't spin. */
    const val MINIMUM_REFRESH_MS: Long = 60 * 1000L

    /** Silo's `exp` (Unix seconds) or S3's `X-Amz-Date` + `X-Amz-Expires`, in epoch ms. */
    fun expiryEpochMs(url: String): Long? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        val parameters = query.split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=')
            if (name.isEmpty() || '=' !in pair) null
            else name to runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull()
        }
        fun value(name: String): String? =
            parameters.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        value("exp")?.toLongOrNull()?.let { return it * 1000 }
        val signedAt = value("X-Amz-Date")?.let(::amzDateEpochMs) ?: return null
        val lifetime = value("X-Amz-Expires")?.toLongOrNull() ?: return null
        return signedAt + lifetime * 1000
    }

    /** Whether [url] still has more than [REFRESH_MARGIN_MS] left. */
    fun isFresh(url: String?, nowEpochMs: Long): Boolean {
        val expiry = url?.let(::expiryEpochMs) ?: return true
        return expiry - nowEpochMs > REFRESH_MARGIN_MS
    }

    /** Milliseconds until the TV should fetch a re-signed poster URL, or null with no poster. */
    fun refreshDelayMs(url: String?, nowEpochMs: Long): Long? {
        url ?: return null
        val expiry = expiryEpochMs(url) ?: return UNKNOWN_EXPIRY_REFRESH_MS
        return maxOf(expiry - nowEpochMs - REFRESH_MARGIN_MS, MINIMUM_REFRESH_MS)
    }

    private val amzDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private fun amzDateEpochMs(value: String): Long? = runCatching {
        LocalDateTime.parse(value, amzDateFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
}

/**
 * Keeps [SiloCastNowPlayingMetadata] for the title the TV is playing: resolves
 * it when the title changes, re-fetches before the poster URL expires, and
 * drops it when playback stops or moves to another title.
 *
 * [load] receives `allowCached = false` on a refresh, when the cached copy's
 * signed URL is the one about to expire. [onChange] runs when metadata for the
 * tracked title arrives or is re-signed.
 */
class SiloCastNowPlayingMetadataResolver(
    private val scope: CoroutineScope,
    private val load: suspend (contentId: String, allowCached: Boolean) -> SiloCastNowPlayingMetadata?,
    private val nowEpochMs: () -> Long,
    private val onChange: () -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private var contentId: String? = null
    private var resolved: SiloCastNowPlayingMetadata? = null
    private var job: Job? = null

    /** Metadata for [contentId], or null while it loads or for another title. Expired posters are withheld. */
    @Synchronized
    fun metadata(contentId: String?): SiloCastNowPlayingMetadata? {
        if (contentId == null || contentId != this.contentId) return null
        val resolved = resolved ?: return null
        val expiry = resolved.posterUrl?.let(SiloCastArtworkExpiry::expiryEpochMs)
        return if (expiry != null && expiry <= nowEpochMs()) resolved.copy(posterUrl = null) else resolved
    }

    /**
     * Follows the playing title. Safe to call on every state tick. Dropping the
     * old title's metadata does not call [onChange]: the caller is already
     * building the state that leaves it out.
     */
    @Synchronized
    fun track(contentId: String?) {
        val newId = contentId?.takeIf { it.isNotEmpty() }
        if (newId == this.contentId) return
        job?.cancel()
        job = null
        this.contentId = newId
        resolved = null
        if (newId != null) job = scope.launch { run(newId) }
    }

    private suspend fun run(contentId: String) {
        var allowCached = true
        val context = currentCoroutineContext()
        while (context.isActive) {
            val fetched = load(contentId, allowCached)
            val changed: Boolean
            val delayMs: Long?
            synchronized(this) {
                // track() cancels under this lock, so a load that ignored
                // cancellation can't publish after the title changed, even
                // if it changed back to this one.
                if (!context.isActive || this.contentId != contentId) return
                // A failed refresh keeps the last URL; it may still have time left.
                changed = fetched != null && fetched != resolved
                if (changed) resolved = fetched
                // Nothing yet: retry soon, so one failed request doesn't leave
                // the title without artwork.
                delayMs = if (resolved == null) {
                    SiloCastArtworkExpiry.MINIMUM_REFRESH_MS
                } else {
                    SiloCastArtworkExpiry.refreshDelayMs(resolved?.posterUrl, nowEpochMs())
                }
            }
            if (changed) onChange()
            sleep(delayMs ?: return)
            allowCached = false
        }
    }

    companion object {
        /** Item detail, then the series detail for an episode, through [loadDetail]. */
        suspend fun loadFromCatalog(
            contentId: String,
            loadDetail: suspend (String) -> ItemDetail?,
        ): SiloCastNowPlayingMetadata? {
            val item = loadDetail(contentId) ?: return null
            val seriesId = item.seriesId?.takeIf { isEpisodeItemType(item.type) && it.isNotEmpty() }
            // A failed series lookup counts as a failed load, so the resolver
            // retries it (or keeps the last result) instead of settling for an
            // episode without its poster.
            val series = seriesId?.let { loadDetail(it) ?: return null }
            return SiloCastNowPlayingMetadata.from(item, series)
        }

        /**
         * Cache first, so the TV usually adds no request; the network when the
         * cached copy is missing or its poster URL is close to expiring.
         */
        fun catalogLoader(
            catalog: CatalogRepository,
            nowEpochMs: () -> Long = System::currentTimeMillis,
        ): suspend (String, Boolean) -> SiloCastNowPlayingMetadata? = { contentId, allowCached ->
            loadFromCatalog(contentId) { id ->
                val cached = if (allowCached) catalog.getCachedItemDetail(id) else null
                cached?.takeIf { SiloCastArtworkExpiry.isFresh(it.posterUrl, nowEpochMs()) }
                    ?: (catalog.getItemDetail(id, joinWarmup = allowCached) as? ApiResult.Success)?.data
            }
        }
    }
}
