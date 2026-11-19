package org.siloserver.silo.playback.trickplay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.network.ApiResult

/** The item and file whose previews a player shows: the file it is streaming from the server. */
data class TrickplayTarget(val contentId: String, val fileId: Int)

/** Reads the seek-preview manifest of a target. */
fun interface TrickplayManifestSource {
    suspend fun manifest(target: TrickplayTarget): ApiResult<TrickplayManifest>
}

/** A sheet download's outcome. */
sealed interface TrickplaySheet {
    class Loaded(val bytes: ByteArray) : TrickplaySheet
    /** The server refused the URL (expired or revoked signature); a fresh manifest may fix it. */
    data object Refused : TrickplaySheet
    data object Failed : TrickplaySheet
}

/** Downloads one sheet. The URL carries its own authorization, so no Silo credentials go with it. */
fun interface TrickplaySheetSource {
    suspend fun sheet(url: String): TrickplaySheet
}

/** Cuts one thumbnail out of a sheet's encoded bytes; null when the bytes do not decode. */
fun interface TrickplayTileDecoder<T : Any> {
    suspend fun decode(sheet: ByteArray, tile: TrickplayTile, manifest: TrickplayManifest): T?
}

/**
 * The seek-bar thumbnail for the playing file, shared by the phone and TV
 * players. [bind] names the file streaming from the server (null for offline
 * or local playback); [show] names the time being scrubbed to (null when the
 * scrub ends). [thumbnail] is null whenever there is no picture to show, and
 * the players then keep their text-only preview.
 */
class TrickplayPreviews<T : Any>(
    private val manifests: TrickplayManifestSource,
    private val sheets: TrickplaySheetSource,
    private val decoder: TrickplayTileDecoder<T>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** Bound on the encoded sheets held; the least recently used go first. */
    private val sheetCacheBytes: Long = DEFAULT_SHEET_CACHE_BYTES,
) {
    private val _thumbnail = MutableStateFlow<T?>(null)
    val thumbnail: StateFlow<T?> = _thumbnail.asStateFlow()

    private var target: TrickplayTarget? = null
    private var manifest: TrickplayManifest? = null
    private var manifestJob: Job? = null
    /** The server said this file has no previews it will serve; asked again only for another file. */
    private var refused = false
    /** When a manifest read last failed transiently; a later scrub asks again after [RETRY_BACKOFF_MS]. */
    private var manifestFailedAt: Long? = null
    /** The time being scrubbed to, so a manifest that lands mid-scrub can answer it. */
    private var shownSeconds: Double? = null
    /** A manifest read for fresh sheet URLs; concurrent downloads share it. */
    private var renewal: Deferred<Unit>? = null
    private var renewedAt: Long? = null
    /**
     * Bumped whenever the held sheets stop matching the manifest: another
     * file, or a renewal that laid the thumbnails out again. Work started
     * before is dropped.
     */
    private var layout = 0L
    /** Encoded sheets held, by index, least recently used first. */
    private val held = LinkedHashMap<Int, ByteArray>()
    private var heldBytes = 0L
    /** Downloads in flight, by sheet index; a scrub and a prefetch share one. */
    private val fetches = mutableMapOf<Int, Deferred<ByteArray?>>()
    /** When each failing sheet last failed; it is not requested again for [RETRY_BACKOFF_MS]. */
    private val sheetFailedAt = mutableMapOf<Int, Long>()
    /** Numbers each [show], so work that finishes after a later one does not answer it. */
    private var request = 0L
    /** The thumbnail the latest [show] asked for, under the [layout] it was cut for. */
    private var wanted: Pair<Long, TrickplayTile>? = null
    private var wantedJob: Job? = null
    /** Numbers each [wantedJob], so an earlier one's stale timer cannot clear a later answer for the same thumbnail. */
    private var wantedGeneration = 0L
    /** Since when the thumbnail shown has stood in for a later target; null while it matches one. */
    private var staleSince: Long? = null

    fun bind(target: TrickplayTarget?) {
        if (target == this.target) return
        this.target = target
        manifest = null
        refused = false
        manifestFailedAt = null
        manifestJob?.cancel()
        manifestJob = null
        renewal?.cancel()
        renewal = null
        renewedAt = null
        dropSheets()
        wanted = null
        setThumbnail(null)
        if (target != null) readManifest(target)
    }

    fun show(seconds: Double?) {
        shownSeconds = seconds
        val request = ++request
        val manifest = manifest
        if (manifest == null) {
            wanted = null
            setThumbnail(null)
            val target = target
            val failedAt = manifestFailedAt
            if (seconds != null && target != null && failedAt != null && now() - failedAt >= RETRY_BACKOFF_MS) {
                readManifest(target)
            }
            return
        }
        val tile = seconds?.let(manifest::tileAt)
        if (tile == null) {
            wanted = null
            setThumbnail(null)
            return
        }
        if (tile.sheet !in held && manifest.expiresSoon() && mayRenew()) {
            markStale()
            // A sheet an earlier target is still waiting for no longer answers this one.
            wanted = null
            // Fresh URLs first; the answer may also lay the thumbnails out again.
            scope.launch {
                if (withTimeoutOrNull(stalePatience()) { renewManifest() } == null &&
                    request == this@TrickplayPreviews.request
                ) {
                    setThumbnail(null)
                    renewManifest()
                }
                if (request == this@TrickplayPreviews.request) show(shownSeconds)
            }
            return
        }
        val key = layout to tile
        // A scrub within one thumbnail keeps the one shown or on its way.
        if (key == wanted && (wantedJob?.isActive == true || _thumbnail.value != null)) return
        wanted = key
        markStale()
        val fetch = sheet(tile.sheet)
        // Load the sheet after it too, so a forward scrub rarely waits.
        if (tile.sheet + 1 < manifest.sheetUrls.size) sheet(tile.sheet + 1)
        // Until it is cut the previous thumbnail stays, so the picture does
        // not flash; [STALE_PREVIEW_MS] after it stopped matching the target
        // (however many targets came since) it no longer stands for the time,
        // whether the sheet or the cut is the slow part.
        val generation = ++wantedGeneration
        wantedJob = scope.launch {
            val dropStale = launch {
                delay(stalePatience())
                if (generation == wantedGeneration && key == wanted) setThumbnail(null)
            }
            val image = fetch.await()?.takeIf { key == wanted }?.let { decoder.decode(it, tile, manifest) }
            dropStale.cancel()
            // A sheet that failed shows no picture rather than the last one.
            if (key == wanted) setThumbnail(image)
        }
    }

    private fun readManifest(target: TrickplayTarget) {
        if (refused || manifestJob?.isActive == true) return
        manifestFailedAt = null
        manifestJob = scope.launch {
            val result = manifests.manifest(target)
            if (target != this@TrickplayPreviews.target) return@launch
            when {
                result is ApiResult.Success -> manifest = result.data
                result.isTransient() -> manifestFailedAt = now()
                else -> refused = true
            }
            if (manifest != null && shownSeconds != null) show(shownSeconds)
        }
    }

    /** The sheet's bytes, held or downloading; a download starts when neither. */
    private fun sheet(index: Int): Deferred<ByteArray?> {
        held.remove(index)?.let { bytes ->
            held[index] = bytes
            return CompletableDeferred(bytes)
        }
        fetches[index]?.let { return it }
        sheetFailedAt[index]?.let { if (now() - it < RETRY_BACKOFF_MS) return CompletableDeferred(null) }
        val layout = layout
        // Registered before it runs: on an immediate dispatcher a download
        // that completes without suspending must still find itself to remove.
        return scope.async(start = CoroutineStart.LAZY) {
            val bytes = download(index)
            if (layout != this@TrickplayPreviews.layout) return@async null
            fetches.remove(index)
            if (bytes != null) {
                hold(index, bytes)
                sheetFailedAt.remove(index)
            } else {
                sheetFailedAt[index] = now()
            }
            bytes
        }.also {
            fetches[index] = it
            it.start()
        }
    }

    /** Downloads a sheet, reading the manifest again when the server refuses its URL. */
    private suspend fun download(index: Int): ByteArray? {
        var result = get(index)
        if (result == TrickplaySheet.Refused) {
            renewManifest()
            result = get(index)
        }
        return (result as? TrickplaySheet.Loaded)?.bytes
    }

    /** The current manifest's URL for a sheet, fetched; null once the manifest no longer has it. */
    private suspend fun get(index: Int): TrickplaySheet? {
        val url = manifest?.sheetUrls?.getOrNull(index) ?: return null
        return sheets.sheet(url)
    }

    /**
     * Reads the manifest again for fresh sheet URLs. Concurrent callers share
     * one read, and a read is not repeated within the backoff. Sheets already
     * held stay unless the thumbnails were laid out again; a refusal
     * withdraws the previews.
     */
    private suspend fun renewManifest() {
        renewal?.let { return it.await() }
        renewedAt?.let { if (now() - it < RETRY_BACKOFF_MS) return }
        val target = target ?: return
        renewedAt = now()
        scope.async(start = CoroutineStart.LAZY) {
            val result = manifests.manifest(target)
            // A rebind may have replaced this renewal meanwhile; leave the new one alone.
            if (renewal === currentCoroutineContext()[Job]) renewal = null
            if (target != this@TrickplayPreviews.target) return@async
            when {
                result is ApiResult.Success -> install(result.data)
                !result.isTransient() -> {
                    refused = true
                    manifest = null
                    dropSheets()
                    setThumbnail(null)
                }
            }
        }.also {
            renewal = it
            it.start()
        }.await()
    }

    /** Starts the stale clock when a picture is shown for an earlier target. */
    private fun markStale() {
        if (_thumbnail.value != null && staleSince == null) staleSince = now()
    }

    /** How much longer the picture on screen may stand in for the current target. */
    private fun stalePatience(): Long =
        (staleSince?.let { STALE_PREVIEW_MS - (now() - it) } ?: STALE_PREVIEW_MS).coerceAtLeast(0)

    /** Shows [image] (or nothing) for the current target, so nothing stale is on screen. */
    private fun setThumbnail(image: T?) {
        _thumbnail.value = image
        staleSince = null
    }

    private fun TrickplayManifest.expiresSoon(): Boolean =
        expiresAtEpochMs?.let { it - now() < RENEWAL_MARGIN_MS } == true

    private fun mayRenew(): Boolean =
        renewal != null || renewedAt.let { it == null || now() - it >= RETRY_BACKOFF_MS }

    private fun install(renewed: TrickplayManifest) {
        val current = manifest
        manifest = renewed
        if (current == null || !renewed.hasSameLayout(current)) {
            dropSheets()
            // The work for the time being scrubbed to was cut for the old
            // layout and is gone; ask for it again under the new one.
            wanted = null
            shownSeconds?.let(::show)
        }
    }

    private fun dropSheets() {
        layout++
        fetches.values.forEach { it.cancel() }
        fetches.clear()
        held.clear()
        heldBytes = 0
        sheetFailedAt.clear()
    }

    private fun hold(index: Int, bytes: ByteArray) {
        held.remove(index)?.let { heldBytes -= it.size }
        held[index] = bytes
        heldBytes += bytes.size
        // The newest sheet stays even when it alone is over the bound.
        while (heldBytes > sheetCacheBytes && held.size > 1) {
            val eldest = held.keys.first()
            heldBytes -= held.remove(eldest)!!.size
        }
    }

    private companion object {
        /** About 16 sheets of 3000 x 1344, over three hours of a film at the server's default interval. */
        const val DEFAULT_SHEET_CACHE_BYTES = 12L * 1024 * 1024

        /** How long a previous thumbnail stands in while the next one's sheet downloads. */
        const val STALE_PREVIEW_MS = 1_000L

        /** Sheet URLs are renewed this long before the manifest says they expire. */
        const val RENEWAL_MARGIN_MS = 60_000L

        /** How long a failed manifest read or sheet download is not repeated. */
        const val RETRY_BACKOFF_MS = 30_000L

        /** A dropped connection, rate limiting, or a server error may pass; anything else is the answer. */
        fun ApiResult<*>.isTransient(): Boolean = when (this) {
            is ApiResult.NetworkError -> true
            is ApiResult.Error -> code == 429 || code >= 500
            is ApiResult.Success -> false
        }
    }
}
