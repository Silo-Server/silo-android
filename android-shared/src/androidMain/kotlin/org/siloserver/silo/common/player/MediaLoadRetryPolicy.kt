package org.siloserver.silo.common.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.common.diagnostics.SiloLogAttribute
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import java.io.EOFException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.StreamResetException

/**
 * Keeps transient media loads alive and resumes eligible progressive direct
 * play after mid-body transport failures.
 *
 * Eligibility is classified per load URI because the source factories are
 * shared by HLS, progressive, and sidecar subtitle loads. HLS retains its
 * existing server-restart retries, including zero-progress unexpected-end
 * failures. Original-file progressive loads additionally reopen stream resets
 * and EOF-style transport failures only after real byte progress, allowing
 * Media3 to continue at its current extraction position without surfacing a
 * player error.
 */
@UnstableApi
internal class SiloMediaLoadErrorHandlingPolicy(
    private val isResumableProgressiveDirectPlay: (Uri) -> Boolean = { false },
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : DefaultLoadErrorHandlingPolicy() {
    private val attemptBytesTracker = MediaLoadAttemptBytesTracker()

    /**
     * When this load task first failed, so the retry window can be measured
     * rather than inferred.
     *
     * `mediaLoadElapsedBeforeRetryMs` reconstructs elapsed time from the retry
     * count alone — it counts the delays *between* attempts and nothing spent
     * inside them. With a 30s socket timeout per attempt the real elapsed time
     * runs several times the 90s window, so a dead server kept a spinner up far
     * longer than intended before surfacing an error.
     */
    private val firstErrorAtByLoadTask = ConcurrentHashMap<Long, Long>()

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val invalidResponse = loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException
        val cumulativeBytesLoaded = loadErrorInfo.loadEventInfo.bytesLoaded
        val bytesLoaded = attemptBytesTracker.bytesLoadedForAttempt(
            loadTaskId = loadErrorInfo.loadEventInfo.loadTaskId,
            cumulativeBytesLoaded = cumulativeBytesLoaded,
        )
        val resumableDirectPlay = isResumableProgressiveDirectPlay(
            loadErrorInfo.loadEventInfo.dataSpec.uri,
        )
        val firstErrorAt = firstErrorAtByLoadTask.computeIfAbsent(
            loadErrorInfo.loadEventInfo.loadTaskId,
        ) { nowMs() }
        val delayMs = siloMediaLoadRetryDelayMs(
            responseCode = invalidResponse?.responseCode,
            retryAfterHeaders = invalidResponse?.retryAfterHeaders().orEmpty(),
            cause = loadErrorInfo.exception,
            errorCount = loadErrorInfo.errorCount,
            isResumableProgressiveDirectPlay = resumableDirectPlay,
            bytesLoaded = bytesLoaded,
            observedElapsedMs = (nowMs() - firstErrorAt).coerceAtLeast(0L),
        )
        logProgressiveResumeDecision(
            loadErrorInfo = loadErrorInfo,
            resumableDirectPlay = resumableDirectPlay,
            bytesLoaded = bytesLoaded,
            delayMs = delayMs,
        )
        return delayMs
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        attemptBytesTracker.onLoadTaskConcluded(loadTaskId)
        firstErrorAtByLoadTask.remove(loadTaskId)
        super.onLoadTaskConcluded(loadTaskId)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        Int.MAX_VALUE

    private fun logProgressiveResumeDecision(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        resumableDirectPlay: Boolean,
        bytesLoaded: Long,
        delayMs: Long,
    ) {
        if (!resumableDirectPlay) return
        val transportFailure = loadErrorInfo.exception.findResumableTransportFailure() ?: return
        val resumeOffset = loadErrorInfo.loadEventInfo.dataSpec.position.coerceAtLeast(0L) +
            bytesLoaded.coerceAtLeast(0L)
        // Byte counts and offsets ride as attributes, not message text: the
        // hosted collector reads any bare 7-10 digit number as a possible
        // integer-encoded IPv4 address and withholds the whole message, so a
        // 1 MB `bytes_loaded` used to turn this line into a lone redaction
        // marker. "attempt" is likewise a redacted key there, hence "try".
        // The failure is named by class (and HTTP/2 reset code), never by its
        // message, which can carry the stream URL.
        val failure = buildString {
            append(transportFailure.javaClass.simpleName)
            (transportFailure as? StreamResetException)?.let {
                append(' ')
                append(it.errorCode.name)
            }
        }
        val attributes = buildMap {
            put("error_class", SiloLogAttribute.Text(transportFailure.javaClass.simpleName))
            put("bytes_loaded", SiloLogAttribute.Integer(bytesLoaded.coerceAtLeast(0L)))
            put("resume_offset", SiloLogAttribute.Integer(resumeOffset))
            put("attempt", SiloLogAttribute.Integer(loadErrorInfo.errorCount.toLong()))
            if (delayMs != C.TIME_UNSET) put("retry_delay_ms", SiloLogAttribute.Integer(delayMs))
        }
        if (delayMs != C.TIME_UNSET) {
            SiloLog.i(
                DiagnosticsLogCategory.PLAYBACK,
                TAG,
                "progressive direct-play retry ($failure, try ${loadErrorInfo.errorCount}, " +
                    "retry_delay_ms=$delayMs)",
                attributes,
            )
        } else {
            SiloLog.w(
                DiagnosticsLogCategory.PLAYBACK,
                TAG,
                "progressive direct-play retry exhausted ($failure, try ${loadErrorInfo.errorCount})",
                attributes,
            )
        }
    }

    private companion object {
        const val TAG = "MediaLoadRetry"
    }
}

internal class MediaLoadAttemptBytesTracker {
    private val cumulativeBytesByLoadTask = ConcurrentHashMap<Long, Long>()

    fun bytesLoadedForAttempt(
        loadTaskId: Long,
        cumulativeBytesLoaded: Long,
    ): Long {
        val sanitizedCumulativeBytes = cumulativeBytesLoaded.coerceAtLeast(0L)
        val previousCumulativeBytes = cumulativeBytesByLoadTask.put(
            loadTaskId,
            sanitizedCumulativeBytes,
        ) ?: 0L
        return (sanitizedCumulativeBytes - previousCumulativeBytes).coerceAtLeast(0L)
    }

    fun onLoadTaskConcluded(loadTaskId: Long) {
        cumulativeBytesByLoadTask.remove(loadTaskId)
    }
}

internal fun siloMediaLoadRetryDelayMs(
    responseCode: Int? = null,
    retryAfterHeaders: List<String> = emptyList(),
    cause: Throwable? = null,
    errorCount: Int,
    isResumableProgressiveDirectPlay: Boolean = false,
    bytesLoaded: Long = 0L,
    retryWindowMs: Long = MEDIA_LOAD_RETRY_WINDOW_MS,
    observedElapsedMs: Long = 0L,
): Long {
    if (
        !isRetryableMediaLoadFailure(
            responseCode = responseCode,
            cause = cause,
            isResumableProgressiveDirectPlay = isResumableProgressiveDirectPlay,
            bytesLoaded = bytesLoaded,
        )
    ) {
        return C.TIME_UNSET
    }

    val delayMs = retryAfterHeaders.firstNotNullOfOrNull(::parseRetryAfterDelayMs)
        ?: mediaLoadBackoffDelayMs(errorCount)
    // Whichever is larger: the time we can prove has passed, or the sum of the
    // delays we intended between attempts. The measured value includes time
    // spent inside failed attempts (socket timeouts dominate here); the derived
    // one keeps the bound meaningful for callers that cannot measure.
    val elapsedBeforeRetry = maxOf(observedElapsedMs, mediaLoadElapsedBeforeRetryMs(errorCount))
    return if (elapsedBeforeRetry + delayMs <= retryWindowMs) delayMs else C.TIME_UNSET
}

private fun isRetryableMediaLoadFailure(
    responseCode: Int?,
    cause: Throwable?,
    isResumableProgressiveDirectPlay: Boolean,
    bytesLoaded: Long,
): Boolean {
    val chain = generateSequence(cause) { it.cause }.toList()
    if (chain.any { it is EntityChangedException }) return false
    if (responseCode != null) return responseCode.isRetryableMediaStatus()

    if (
        chain.any { error ->
            error is SocketTimeoutException ||
                error is ConnectException ||
                error is SocketException ||
                error is UnknownHostException
        }
    ) {
        return true
    }

    if (chain.any { it is ParserException }) return false

    return chain.any { error ->
        error.isRetryablePrematureEnd(
            isResumableProgressiveDirectPlay = isResumableProgressiveDirectPlay,
            bytesLoaded = bytesLoaded,
        )
    }
}

private fun Throwable.isRetryablePrematureEnd(
    isResumableProgressiveDirectPlay: Boolean,
    bytesLoaded: Long,
): Boolean {
    val madeProgress = bytesLoaded > 0L
    return when {
        this is ProtocolException && isUnexpectedEndOfStream() ->
            true
        isResumableTransportFailure() ->
            isResumableProgressiveDirectPlay && madeProgress
        else -> false
    }
}

/**
 * OkHttp reports a response that closes before its declared Content-Length as
 * a ProtocolException wrapped by Media3's HttpDataSourceException. Progressive
 * extractors can safely reopen their current byte range after real byte
 * progress. HTTP/2 stream and connection shutdowns are similarly resumable,
 * but only for original-file progressive delivery.
 */
private fun Throwable.isResumableTransportFailure(): Boolean =
    this is StreamResetException ||
        this is ConnectionShutdownException ||
        this is EOFException ||
        (this is ProtocolException && isUnexpectedEndOfStream())

private fun ProtocolException.isUnexpectedEndOfStream(): Boolean =
    message?.contains("unexpected end of stream", ignoreCase = true) == true

private fun Throwable?.findResumableTransportFailure(): Throwable? =
    generateSequence(this) { it.cause }.firstOrNull(Throwable::isResumableTransportFailure)

private fun Int.isRetryableMediaStatus(): Boolean =
    this == 404 ||
        this == 500 ||
        this == 502 ||
        this == 503 ||
        this == 504 ||
        this in 520..527 ||
        this == 530

private fun parseRetryAfterDelayMs(value: String): Long? {
    val seconds = value.trim().toLongOrNull() ?: return null
    if (seconds < 0) return null
    return seconds * 1_000L
}

private fun mediaLoadBackoffDelayMs(errorCount: Int): Long =
    when (errorCount.coerceAtLeast(1)) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        else -> 8_000L
    }

private fun mediaLoadElapsedBeforeRetryMs(errorCount: Int): Long {
    var elapsed = 0L
    for (count in 1 until errorCount.coerceAtLeast(1)) {
        elapsed += mediaLoadBackoffDelayMs(count)
    }
    return elapsed
}

private fun HttpDataSource.InvalidResponseCodeException.retryAfterHeaders(): List<String> =
    headerFields.entries
        .firstOrNull { (key, _) -> key.equals("Retry-After", ignoreCase = true) }
        ?.value
        .orEmpty()

internal const val MEDIA_LOAD_RETRY_WINDOW_MS: Long = 90_000L
