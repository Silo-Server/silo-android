package org.siloserver.silo.common.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.siloserver.silo.common.player.subtitle.normalizeSubripPayloadIfNeeded
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * DataSource.Factory that resolves relative stream URLs against the server
 * base URL and wraps an arbitrary [HttpDataSource.Factory] with Silo's shared
 * auth/refresh contract. The backend is currently OkHttp, but neither routing
 * nor credentials depend on it; HttpEngine or Cronet can be selected later
 * without duplicating token refresh behavior.
 *
 * For offline playback the `streamUrl` is `file://…` (the downloaded media
 * file on disk); the routed source below delegates those to a [FileDataSource]
 * instead of OkHttp.
 */
@UnstableApi
class AuthenticatedDataSourceFactory(
    private val context: Context,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val authSession: MediaAuthSession,
    private val serverUrlProvider: () -> String,
    private val requestHeadersProvider: (Uri) -> Map<String, String> = { emptyMap() },
    private val isResumableDirectPlayUri: (Uri) -> Boolean = { false },
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val http = RefreshingHttpDataSource(
            factory = httpDataSourceFactory,
            authSession = authSession,
            isResumableDirectPlayUri = isResumableDirectPlayUri,
        )
        val file = FileDataSource()
        val content = ContentDataSource(context)
        return RoutedDataSource(
            http = http,
            file = file,
            content = content,
            serverUrl = serverUrlProvider(),
            requestHeadersProvider = requestHeadersProvider,
        )
    }
}

/**
 * Applies fresh credentials to every HTTP open and retries one 401 after the
 * process-wide single-flight refresh. This is deliberately implemented at the
 * Media3 boundary instead of in an OkHttp interceptor so all HTTP backends get
 * identical long-session behavior.
 */
@UnstableApi
internal class RefreshingHttpDataSource(
    private val factory: HttpDataSource.Factory,
    private val authSession: MediaAuthSession,
    private val isResumableDirectPlayUri: (Uri) -> Boolean = { false },
) : HttpDataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private val requestProperties = linkedMapOf<String, String>()
    private var active: HttpDataSource? = null
    private var entityUri: Uri? = null
    private var entityEtag: String? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val guardEnabled = isResumableDirectPlayUri(dataSpec.uri)
        if (guardEnabled) {
            prepareEntityGuard(dataSpec.uri)
        }
        val failedSnapshot = runBlocking { authSession.snapshot() }
        val first = newDataSource()
        active = first
        return try {
            first.openWithGuards(dataSpec, failedSnapshot, guardEnabled)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode != 401 || !runBlocking { authSession.refreshIfStale(failedSnapshot) }) {
                throw error
            }
            first.close()
            val retry = newDataSource()
            active = retry
            retry.openWithGuards(dataSpec, runBlocking { authSession.snapshot() }, guardEnabled)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseCode(): Int = active?.responseCode ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders.orEmpty()

    override fun setRequestProperty(name: String, value: String) {
        requestProperties[name] = value
        active?.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
        active?.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
        active?.clearAllRequestProperties()
    }

    override fun close() {
        active?.close()
        active = null
    }

    private fun newDataSource(): HttpDataSource = factory.createDataSource().also { dataSource ->
        requestProperties.forEach(dataSource::setRequestProperty)
        transferListeners.forEach(dataSource::addTransferListener)
    }

    private fun prepareEntityGuard(openedUri: Uri) {
        if (openedUri != entityUri) {
            entityUri = openedUri
            entityEtag = null
        }
    }

    private fun HttpDataSource.openWithGuards(
        dataSpec: DataSpec,
        snapshot: MediaAuthSnapshot,
        guardEnabled: Boolean,
    ): Long {
        if (!guardEnabled) {
            return open(dataSpec.withAuthHeaders(snapshot))
        }

        val previousEtag = entityEtag
        val guardedDataSpec = dataSpec
            .withIfRangeHeader()
            .withAuthHeaders(snapshot)
        val length = open(guardedDataSpec)
        val responseEtag = responseHeaders.headerValue("ETag")
        if (previousEtag != null && dataSpec.position > 0L) {
            if (responseEtag != null && responseEtag != previousEtag) {
                failEntityChanged()
            }
            if (responseCode == 200 && responseEtag != previousEtag) {
                failEntityChanged()
            }
        }
        if (responseEtag != null) {
            entityEtag = responseEtag
        }
        return length
    }

    private fun DataSpec.withIfRangeHeader(): DataSpec {
        val etag = entityEtag
        if (position <= 0L || etag == null || !etag.isStrongEtagValidator()) return this
        return buildUpon()
            .setHttpRequestHeaders(httpRequestHeaders.withHeader("If-Range", etag))
            .build()
    }

    private fun HttpDataSource.failEntityChanged(): Nothing {
        runCatching(::close)
        active = null
        throw EntityChangedException()
    }

    private fun DataSpec.withAuthHeaders(snapshot: MediaAuthSnapshot): DataSpec = buildUpon()
        // A V3 plan may carry a stream-scoped credential (for example, a
        // signed CDN Authorization value). Treat those explicit DataSpec
        // headers as authoritative while filling only missing auth/profile
        // headers from the refreshable Silo session.
        .setHttpRequestHeaders(
            mergeSessionAuthHeaders(snapshot.asRequestHeaders(), httpRequestHeaders),
        )
        .build()
}

internal class EntityChangedException :
    IOException("HTTP entity changed during ranged resume")

private fun Map<String, List<String>>.headerValue(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun String.isStrongEtagValidator(): Boolean =
    !startsWith("W/", ignoreCase = true)

private fun Map<String, String>.withHeader(name: String, value: String): Map<String, String> =
    buildMap {
        putAll(this@withHeader)
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
        put(name, value)
    }

internal fun mergeSessionAuthHeaders(
    sessionHeaders: Map<String, String>,
    explicitHeaders: Map<String, String>,
): Map<String, String> = buildMap {
    putAll(sessionHeaders)
    explicitHeaders.forEach { (name, value) ->
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
        put(name, value)
    }
}

/**
 * Picks between a [FileDataSource] (offline media playback) and the shared
 * [OkHttpDataSource] (every other scheme) based on the DataSpec's URI. Also
 * folds in the relative-URL resolution that used to live in
 * `RelativeUrlDataSource`: a URI with no scheme is prefixed with the server
 * base URL and routed via OkHttp.
 */
@UnstableApi
private class RoutedDataSource(
    private val http: DataSource,
    private val file: DataSource,
    private val content: DataSource,
    private val serverUrl: String,
    private val requestHeadersProvider: (Uri) -> Map<String, String>,
) : DataSource {

    /** Which downstream the most recent [open] call delegated to. Both
     *  [read]/[getUri]/[close] need to hit the same one to avoid double-
     *  closing or pulling from a stale stream. */
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
        file.addTransferListener(transferListener)
        content.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val resolved = resolveDataSpec(dataSpec)
        val downstream = when {
            resolved.uri.scheme.equals("file", ignoreCase = true) -> file
            resolved.uri.scheme.equals("content", ignoreCase = true) -> content
            else -> http
        }
        val routed = if (shouldNormalizeSubripDataSpec(resolved)) {
            SubripNormalizingDataSource(downstream)
        } else {
            downstream
        }
        active = routed
        return routed.open(resolved)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: http).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        active?.close()
        active = null
    }

    private fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        val resolved = if (uri.scheme == null || uri.scheme!!.isEmpty()) {
            val absoluteUrl = resolveRoutedDataSourceUrl(serverUrl, uri.toString())
            dataSpec.buildUpon()
                .setUri(Uri.parse(absoluteUrl))
                .build()
        } else {
            dataSpec
        }
        val isHttp = resolved.uri.scheme.equals("http", ignoreCase = true) ||
            resolved.uri.scheme.equals("https", ignoreCase = true)
        if (!isHttp) return resolved

        val headers = requestHeadersProvider(resolved.uri)
        return if (headers.isEmpty()) {
            resolved
        } else {
            resolved.buildUpon()
                .setHttpRequestHeaders(resolved.httpRequestHeaders + headers)
                .build()
        }
    }
}

internal fun resolveRoutedDataSourceUrl(serverUrl: String, rawUri: String): String {
    val trimmed = rawUri.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) -> trimmed
        trimmed.startsWith("/") -> resolvePlaybackStreamUrl(serverUrl, trimmed)
        else -> "${serverUrl.trimEnd('/')}/${trimmed.trimStart('/')}"
    }
}

@UnstableApi
internal class SubripNormalizingDataSource(
    private val upstream: DataSource,
) : DataSource {
    private var normalizedData: ByteArray? = null
    private var normalizedPosition: Int = 0
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        normalizedData = null
        normalizedPosition = 0

        if (!shouldNormalizeSubripDataSpec(dataSpec)) {
            return upstream.open(dataSpec)
        }

        upstream.open(dataSpec)
        uri = upstream.uri ?: dataSpec.uri
        val raw = try {
            readAllFromUpstream()
        } finally {
            upstream.close()
        }
        val normalized = normalizeSubripDataIfNeeded(raw)
        normalizedData = normalized
        return normalized.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = normalizedData ?: return upstream.read(buffer, offset, length)
        if (normalizedPosition >= data.size) return C.RESULT_END_OF_INPUT

        val count = minOf(length, data.size - normalizedPosition)
        data.copyInto(buffer, offset, normalizedPosition, normalizedPosition + count)
        normalizedPosition += count
        return count
    }

    override fun getUri(): Uri? = normalizedData?.let { uri } ?: upstream.uri

    override fun close() {
        if (normalizedData == null) {
            upstream.close()
        }
        normalizedData = null
        normalizedPosition = 0
        uri = null
    }

    private fun readAllFromUpstream(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_SUBRIP_READ_BUFFER_SIZE)
        while (true) {
            val read = upstream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            if (read > 0) out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

internal fun shouldNormalizeSubripDataSpec(dataSpec: DataSpec): Boolean =
    shouldNormalizeSubripPath(dataSpec.uri.path, dataSpec.position)

internal fun shouldNormalizeSubripPath(path: String?, position: Long): Boolean =
    position == 0L && path.orEmpty().endsWith(".srt", ignoreCase = true)

internal fun normalizeSubripDataIfNeeded(raw: ByteArray): ByteArray =
    normalizeSubripPayloadIfNeeded(raw, 0, raw.size) ?: raw

private const val DEFAULT_SUBRIP_READ_BUFFER_SIZE = 16 * 1024
