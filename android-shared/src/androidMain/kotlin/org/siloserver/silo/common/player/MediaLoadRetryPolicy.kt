package org.siloserver.silo.common.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.siloserver.silo.common.diagnostics.SiloLog
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
    private val outageTracker = MediaLoadOutageTracker()

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val invalidResponse = loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException
        val loadTaskId = loadErrorInfo.loadEventInfo.loadTaskId
        val bytesLoaded = attemptBytesTracker.bytesLoadedForAttempt(
            loadTaskId = loadTaskId,
            cumulativeBytesLoaded = loadErrorInfo.loadEventInfo.bytesLoaded,
        )
        val resumableDirectPlay = isResumableProgressiveDirectPlay(
            loadErrorInfo.loadEventInfo.dataSpec.uri,
        )
        val resumeOffset = loadErrorInfo.loadEventInfo.dataSpec.position.coerceAtLeast(0L) + bytesLoaded
        val now = nowMs()
        val outage = outageTracker.onFailure(
            loadTaskId = loadTaskId,
            nowMs = now,
            bytesSincePreviousFailure = bytesLoaded,
            resumeOffset = resumeOffset,
        )
        val delayMs = siloMediaLoadRetryDelayMs(
            responseCode = invalidResponse?.responseCode,
            retryAfterHeaders = invalidResponse?.retryAfterHeaders().orEmpty(),
            cause = loadErrorInfo.exception,
            errorCount = minOf(loadErrorInfo.errorCount, outage.failureCount),
            isResumableProgressiveDirectPlay = resumableDirectPlay,
            bytesLoaded = bytesLoaded,
            observedElapsedMs = (now - outage.startedAtMs).coerceAtLeast(0L),
        )
        logProgressiveResumeDecision(
            loadErrorInfo = loadErrorInfo,
            resumableDirectPlay = resumableDirectPlay,
            bytesLoaded = bytesLoaded,
            resumeOffset = resumeOffset,
            outageFailureCount = outage.failureCount,
            delayMs = delayMs,
        )
        return delayMs
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        attemptBytesTracker.onLoadTaskConcluded(loadTaskId)
        outageTracker.onLoadTaskConcluded(loadTaskId)
        super.onLoadTaskConcluded(loadTaskId)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        Int.MAX_VALUE

    private fun logProgressiveResumeDecision(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        resumableDirectPlay: Boolean,
        bytesLoaded: Long,
        resumeOffset: Long,
        outageFailureCount: Int,
        delayMs: Long,
    ) {
        if (!resumableDirectPlay) return
        val transportFailure = loadErrorInfo.exception.findResumableTransportFailure() ?: return
        val errorDescription = buildString {
            append(transportFailure.javaClass.simpleName)
            transportFailure.message?.takeIf(String::isNotBlank)?.let {
                append(": ")
                append(it)
            }
        }
        if (delayMs != C.TIME_UNSET) {
            SiloLog.i(
                DiagnosticsLogCategory.PLAYBACK,
                TAG,
                "progressive direct-play retry error=$errorDescription " +
                    "bytes_loaded=$bytesLoaded resume_offset=$resumeOffset " +
                    "attempt=$outageFailureCount retry_delay_ms=$delayMs",
            )
        } else {
            SiloLog.w(
                DiagnosticsLogCategory.PLAYBACK,
                TAG,
                "progressive direct-play retry exhausted error=$errorDescription " +
                    "bytes_loaded=$bytesLoaded resume_offset=$resumeOffset " +
                    "attempt=$outageFailureCount",
            )
        }
    }

    private companion object {
        const val TAG = "MediaLoadRetry"
    }
}

/**
 * The failures one load task has seen since its current outage began.
 *
 * The retry window is measured from [startedAtMs] rather than inferred from a
 * retry count: `mediaLoadElapsedBeforeRetryMs` counts only the delays between
 * attempts, and with a 30s socket timeout per attempt a dead server kept a
 * spinner up for several times the 90s window. [failureCount] caps backoff:
 * Media3 resets its own count after extraction progress, but only after the
 * policy has already been asked for a delay, so the first failure of a new
 * outage arrives with the count the previous outage left behind.
 * [lastCutOffset] is where the last failure that delivered
 * [MEDIA_LOAD_OUTAGE_RECOVERY_BYTES] or more stopped: the position it opened
 * at plus those bytes. Smaller failures, such as a 503, leave it in place.
 * [lastFailureAtMs] is when the task last failed.
 */
internal data class MediaLoadOutage(
    val startedAtMs: Long,
    val failureCount: Int,
    val lastCutOffset: Long,
    val lastFailureAtMs: Long,
)

/**
 * Tracks one [MediaLoadOutage] per load task.
 *
 * A progressive load (direct play, or a PGS sidecar that reads a bounded
 * stretch ahead) is one load task for the whole file, so several server
 * outages can land on it. A failure starts a new outage, with its own window,
 * when at least [MEDIA_LOAD_OUTAGE_RECOVERY_MS] passed since the task's
 * previous failure and the server delivered [MEDIA_LOAD_OUTAGE_RECOVERY_BYTES]
 * in between: it came back and served the stream for a while. Anything else
 * stays in the current outage, so a server that keeps failing still exhausts
 * it. That covers partial bodies; responses cut every few seconds, even as they
 * advance, which may never keep up with playback (exhausting hands the player
 * to a route that might); and a response cut at the same offset as the last
 * large one, even with errors in between, which never gets playback past it.
 */
internal class MediaLoadOutageTracker {
    private val outagesByLoadTask = ConcurrentHashMap<Long, MediaLoadOutage>()

    fun onFailure(
        loadTaskId: Long,
        nowMs: Long,
        bytesSincePreviousFailure: Long,
        resumeOffset: Long,
    ): MediaLoadOutage = checkNotNull(
        outagesByLoadTask.compute(loadTaskId) { _, current ->
            val largeCut = bytesSincePreviousFailure >= MEDIA_LOAD_OUTAGE_RECOVERY_BYTES
            val serverCameBack = current != null && largeCut &&
                resumeOffset != current.lastCutOffset &&
                nowMs - current.lastFailureAtMs >= MEDIA_LOAD_OUTAGE_RECOVERY_MS
            when {
                current == null || serverCameBack -> MediaLoadOutage(
                    startedAtMs = nowMs,
                    failureCount = 1,
                    lastCutOffset = resumeOffset,
                    lastFailureAtMs = nowMs,
                )
                largeCut -> current.copy(
                    failureCount = current.failureCount + 1,
                    lastCutOffset = resumeOffset,
                    lastFailureAtMs = nowMs,
                )
                else -> current.copy(failureCount = current.failureCount + 1, lastFailureAtMs = nowMs)
            }
        },
    )

    fun onLoadTaskConcluded(loadTaskId: Long) {
        outagesByLoadTask.remove(loadTaskId)
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

/**
 * Bytes the server must deliver between two failures of one load task before
 * the second counts as a new outage. Well under the 1 MiB Media3 reads before
 * a full buffer parks the loader, and far above a few KiB of a partial body.
 */
internal const val MEDIA_LOAD_OUTAGE_RECOVERY_BYTES: Long = 512L shl 10

/**
 * Time since a load task's previous failure before a new failure can start a
 * new outage. A proxy or link that cuts every response fails again within
 * seconds. A server restarted again after it came back, or a proxy that ends
 * each response after a fixed time limit, has served the stream for longer.
 */
internal const val MEDIA_LOAD_OUTAGE_RECOVERY_MS: Long = 20_000L
