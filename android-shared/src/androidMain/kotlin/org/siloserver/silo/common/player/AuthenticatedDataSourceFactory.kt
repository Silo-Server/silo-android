package org.siloserver.silo.common.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import org.siloserver.silo.common.player.subtitle.normalizeSubripPayloadIfNeeded
import org.siloserver.silo.network.TokenManager
import java.io.ByteArrayOutputStream
import okhttp3.OkHttpClient

/**
 * DataSource.Factory that resolves relative stream URLs against the server
 * base URL and lets [MediaAuthInterceptor] on the shared [OkHttpClient] inject
 * `Authorization: Bearer` + handle 401-refresh. Explicit header injection here
 * would shadow the interceptor and leave long HLS sessions stranded on stale
 * tokens.
 *
 * For offline playback the `streamUrl` is `file://…` (the downloaded media
 * file on disk); the routed source below delegates those to a [FileDataSource]
 * instead of OkHttp.
 */
@UnstableApi
class AuthenticatedDataSourceFactory(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    @Suppress("unused") private val tokenManager: TokenManager,
    private val serverUrlProvider: () -> String,
    private val requestHeadersProvider: (Uri) -> Map<String, String> = { emptyMap() },
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val http = OkHttpDataSource.Factory(okHttpClient).createDataSource()
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
