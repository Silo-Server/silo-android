package org.siloserver.silo.common.downloads

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.siloserver.silo.model.download.DownloadMediaType
import org.siloserver.silo.model.download.OFFLINE_ARTWORK_POSTER
import org.siloserver.silo.model.download.OFFLINE_ARTWORK_SERIES_POSTER
import org.siloserver.silo.model.download.OfflineArtworkFiles
import org.siloserver.silo.model.download.OfflineManifestArtwork
import org.siloserver.silo.model.download.OfflineManifestSubtitle
import org.siloserver.silo.model.download.OfflineSubtitleFile
import org.siloserver.silo.model.download.OfflineTrackInfo
import org.siloserver.silo.model.download.decodeOfflineManifestArtwork
import org.siloserver.silo.model.download.decodeOfflineManifestTracks
import org.siloserver.silo.model.download.isOfflineArtworkFetchUrl
import org.siloserver.silo.model.download.isOfflineSubtitleFetchUrl
import org.siloserver.silo.model.download.offlineSubtitleExtension
import org.siloserver.silo.model.download.offlineSubtitleFormat
import org.siloserver.silo.model.download.toOfflineTrackInfo
import org.siloserver.silo.playback.orNullIfBlank
import java.io.File
import java.io.IOException

/** What one offline-manifest capture produced for a completed download. */
internal data class OfflineAssets(
    val tracks: OfflineTrackInfo,
    val artwork: OfflineArtworkFiles,
)

/**
 * Captures what offline video playback needs once the media bytes are down:
 * the offline manifest's audio tracks (positions inside the delivered file),
 * the subtitle sidecars it lists, and the poster (plus the series poster for
 * episodes), each fetched once into private storage.
 *
 * Everything here is best effort. A missing manifest, an older server, or a
 * sidecar that fails to fetch never fails the video download; the download
 * then plays with whatever was captured (or the legacy offline behaviour).
 */
internal class OfflineTrackAssetFetcher(
    private val httpClient: HttpClient,
    private val storage: DownloadStorage,
    private val manifestRetryDelayMs: Long = 2_000,
) {
    // The server streams artwork itself, so a redirect is never legitimate;
    // refusing it keeps the fetch on the URL isOfflineArtworkFetchUrl vetted.
    private val artworkHttpClient = httpClient.config { followRedirects = false }

    suspend fun fetch(
        downloadId: String,
        serverId: String,
        profileId: String,
        fileId: Int,
        configure: HttpRequestBuilder.() -> Unit,
    ): OfflineAssets? {
        // One manifest request feeds both the track and the artwork capture.
        val body = fetchManifestBody(downloadId, configure) ?: return null
        val manifest = decodeOfflineManifestTracks(body) ?: return null

        val directory = storage.offlineSubtitleDirectory(serverId, profileId, fileId)
        // A replaced download (new revision) must not keep the previous
        // revision's sidecars around under the same file slot.
        directory?.deleteRecursively()
        val saved = if (directory == null || manifest.subtitles.isEmpty()) {
            emptyList()
        } else {
            manifest.subtitles.mapIndexedNotNull { ordinal, subtitle ->
                fetchSubtitle(downloadId, ordinal, subtitle, directory, configure)
            }
        }
        val artwork = fetchArtwork(downloadId, serverId, profileId, fileId, decodeOfflineManifestArtwork(body), configure)
        return OfflineAssets(tracks = manifest.toOfflineTrackInfo(saved), artwork = artwork)
    }

    /**
     * The capture runs once, right after the download completes, so a network
     * blip or a transient server error is retried briefly here; a missing
     * manifest (older server) is not.
     */
    private suspend fun fetchManifestBody(
        downloadId: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): String? {
        repeat(MANIFEST_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(manifestRetryDelayMs * attempt)
            try {
                val response = httpClient.get("/api/v2/downloads/${downloadId.encodeURLPathPart()}/manifest") {
                    configure()
                }
                val status = response.status
                if (status == HttpStatusCode.OK) return response.bodyAsText()
                Log.i(TAG, "manifest unavailable id=$downloadId status=${status.value}")
                if (status.value < 500 && status != HttpStatusCode.TooManyRequests) return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "manifest fetch failed id=$downloadId attempt=${attempt + 1}", e)
            }
        }
        return null
    }

    /**
     * Saves the manifest's `poster` and, when listed, `series_poster` into the
     * download's artwork directory. Each image is independent: a failure leaves
     * that path null and never affects the download or the other image.
     */
    private suspend fun fetchArtwork(
        downloadId: String,
        serverId: String,
        profileId: String,
        fileId: Int,
        manifest: OfflineManifestArtwork?,
        configure: HttpRequestBuilder.() -> Unit,
    ): OfflineArtworkFiles {
        val directory = storage.offlineArtworkDirectory(serverId, profileId, fileId)
        // Same slot, new revision: drop the previous revision's images first.
        directory?.deleteRecursively()
        if (manifest == null) return OfflineArtworkFiles()
        val urls = manifest.artworkUrls
        suspend fun save(kind: String, url: String?): String? =
            if (directory == null || url.isNullOrBlank()) null
            else fetchArtworkImage(downloadId, kind, url, directory, configure)
        return OfflineArtworkFiles(
            posterPath = save(OFFLINE_ARTWORK_POSTER, urls.poster),
            seriesPosterPath = save(OFFLINE_ARTWORK_SERIES_POSTER, urls.seriesPoster),
            posterThumbhash = manifest.posterThumbhash.orNullIfBlank(),
            seriesPosterThumbhash = manifest.seriesPosterThumbhash.orNullIfBlank(),
        )
    }

    private suspend fun fetchArtworkImage(
        downloadId: String,
        kind: String,
        url: String,
        directory: File,
        configure: HttpRequestBuilder.() -> Unit,
    ): String? {
        if (!isOfflineArtworkFetchUrl(url, kind)) {
            Log.w(TAG, "skipping artwork with unexpected reference id=$downloadId kind=$kind")
            return null
        }
        val target = File(directory, kind)
        val partial = File(directory, "$kind.part")
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not create $directory")
            artworkHttpClient.prepareGet(url.trim()) {
                configure()
                timeout {
                    requestTimeoutMillis = ARTWORK_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = ARTWORK_IDLE_TIMEOUT_MS
                }
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    throw IOException("HTTP ${response.status.value}")
                }
                val declared = response.contentLength()
                if (declared != null && declared > MAX_ARTWORK_BYTES) {
                    throw IOException("artwork exceeds $MAX_ARTWORK_BYTES bytes")
                }
                val written = copyCapped(response, partial, MAX_ARTWORK_BYTES, "artwork")
                if (written == 0L) throw IOException("empty artwork")
            }
            if (!partial.renameTo(target)) throw IOException("could not publish $target")
            target.absolutePath
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "artwork fetch failed id=$downloadId kind=$kind", e)
            partial.delete()
            target.delete()
            null
        }
    }

    /** Streams [response]'s body into [partial], failing once it passes [maxBytes]. */
    private suspend fun copyCapped(response: HttpResponse, partial: File, maxBytes: Long, what: String): Long {
        var written = 0L
        response.bodyAsChannel().toInputStream().use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > maxBytes) throw IOException("$what exceeds $maxBytes bytes")
                    output.write(buffer, 0, read)
                }
            }
        }
        return written
    }

    private suspend fun fetchSubtitle(
        downloadId: String,
        ordinal: Int,
        subtitle: OfflineManifestSubtitle,
        directory: File,
        configure: HttpRequestBuilder.() -> Unit,
    ): OfflineSubtitleFile? {
        val format = offlineSubtitleFormat(subtitle.format) ?: return null
        if (!isOfflineSubtitleFetchUrl(subtitle.fetchUrl)) {
            Log.w(TAG, "skipping subtitle with unexpected reference id=$downloadId ordinal=$ordinal")
            return null
        }
        val target = File(directory, "$ordinal.${offlineSubtitleExtension(format)}")
        val partial = File(directory, "$ordinal.part")
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not create $directory")
            httpClient.prepareGet(subtitle.fetchUrl.trim()) {
                configure()
                // Embedded ASS/PGS sidecars are extracted from the source on
                // demand, so the first byte can take a while; keep only an idle
                // timeout, like the media transfer itself.
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = SUBTITLE_IDLE_TIMEOUT_MS
                }
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    throw IOException("HTTP ${response.status.value}")
                }
                val written = copyCapped(response, partial, MAX_SUBTITLE_BYTES, "subtitle")
                if (written == 0L) throw IOException("empty subtitle")
            }
            if (!partial.renameTo(target)) throw IOException("could not publish $target")
            OfflineSubtitleFile(
                path = target.absolutePath,
                format = format,
                language = subtitle.language.orNullIfBlank(),
                title = subtitle.title.orNullIfBlank(),
                forced = subtitle.forced,
                hearingImpaired = subtitle.hearingImpaired,
            )
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "subtitle fetch failed id=$downloadId ordinal=$ordinal format=$format", e)
            partial.delete()
            target.delete()
            null
        }
    }

    companion object {
        private const val TAG = "OfflineTrackAssets"
        private const val MANIFEST_ATTEMPTS = 3
        private const val BUFFER_BYTES = 64 * 1024
        private const val SUBTITLE_IDLE_TIMEOUT_MS = 120_000L

        /** PGS tracks for a feature run to tens of MB; text is far smaller. */
        private const val MAX_SUBTITLE_BYTES = 256L * 1024 * 1024

        /** A poster is a few hundred KB; anything past this is not a poster. */
        internal const val MAX_ARTWORK_BYTES = 10L * 1024 * 1024
        private const val ARTWORK_REQUEST_TIMEOUT_MS = 60_000L
        private const val ARTWORK_IDLE_TIMEOUT_MS = 30_000L

        /** Only video downloads have tracks and artwork to capture. */
        fun appliesTo(mediaType: String?): Boolean =
            when (DownloadMediaType.fromWire(mediaType)) {
                DownloadMediaType.Movie, DownloadMediaType.TvShow, DownloadMediaType.Unknown -> true
                DownloadMediaType.Audiobook, DownloadMediaType.Ebook -> false
            }
    }
}
