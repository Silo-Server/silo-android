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
                    "attempt=${loadErrorInfo.errorCount} retry_delay_ms=$delayMs",
            )
        } else {
            SiloLog.w(
                DiagnosticsLogCategory.PLAYBACK,
                TAG,
                "progressive direct-play retry exhausted error=$errorDescription " +
                    "bytes_loaded=$bytesLoaded resume_offset=$resumeOffset " +
                    "attempt=${loadErrorInfo.errorCount}",
            )
        }
    }

    private companion object {
        const val TAG = "MediaLoadRetry"
    }
}

/**
 * Retries for sidecar subtitle loads, kept apart from the media policy above
 * (#458).
 *
 * A sidecar request that times out or is cut off mid-body has made the server
 * extract the track, which on a cold network mount means reading the whole
 * source file, and its retry starts that read again. The media policy's
 * open-ended retries inside [MEDIA_LOAD_RETRY_WINDOW_MS] turned one slow
 * extraction into five or six concurrent whole-file reads. Here a load task
 * retries at most [SIDECAR_SUBTITLE_MAX_EXTRACTION_RETRIES] such failures, of
 * which at most [SIDECAR_SUBTITLE_MAX_TIMEOUT_RETRIES] timeouts. A slow first
 * byte is not a failure at all: sidecar requests wait
 * [SIDECAR_SUBTITLE_READ_TIMEOUT_SECONDS] for data
 * ([withSidecarSubtitleTimeouts]) where media waits 30 s.
 *
 * Failures that cost no extraction keep the media policy and its window, so a
 * subtitle rides out a server restart or a network drop as the video does: an
 * error status, a refused connection, and a timeout before the connection
 * opened (a connect or TLS handshake timeout, when no request reached the
 * server).
 *
 * The budget is counted here rather than taken from `errorCount`, which
 * Media3 resets after an attempt that extracted samples: a sidecar that keeps
 * stalling after its first cues would otherwise retry without end. Once the
 * budget is spent the load fails and only the subtitle is lost: the sidecar's
 * period reports itself prepared from its known track
 * ([org.siloserver.silo.common.player.subtitle.NonGatingSidecarPeriod]), so the
 * failure reaches the text renderer, and Media3 disables the text track while
 * the video keeps playing. A seek, a new selection or a new start begins a new
 * load task with a fresh budget.
 */
@UnstableApi
internal class SidecarSubtitleLoadErrorHandlingPolicy(
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : DefaultLoadErrorHandlingPolicy() {
    private val budget = SidecarSubtitleRetryBudget()
    private val firstErrorAtByLoadTask = ConcurrentHashMap<Long, Long>()

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val loadTaskId = loadErrorInfo.loadEventInfo.loadTaskId
        val error = loadErrorInfo.exception
        val invalidResponse = error as? HttpDataSource.InvalidResponseCodeException
        val firstErrorAt = firstErrorAtByLoadTask.computeIfAbsent(loadTaskId) { nowMs() }
        val failures = budget.record(loadTaskId, error)
        val delayMs = sidecarSubtitleRetryDelayMs(
            responseCode = invalidResponse?.responseCode,
            retryAfterHeaders = invalidResponse?.retryAfterHeaders().orEmpty(),
            cause = error,
            failures = failures,
            observedElapsedMs = (nowMs() - firstErrorAt).coerceAtLeast(0L),
        )
        val description = "error=${generateSequence<Throwable>(error) { it.cause }.last().javaClass.simpleName} " +
            "status=${invalidResponse?.responseCode} failures=${failures.count} " +
            "timeouts=${failures.timeouts} cut_offs=${failures.cutOffs}"
        if (delayMs == C.TIME_UNSET) {
            SiloLog.w(DiagnosticsLogCategory.PLAYBACK, TAG, "sidecar subtitle load gave up $description")
        } else {
            SiloLog.i(DiagnosticsLogCategory.PLAYBACK, TAG, "sidecar subtitle load retry $description retry_delay_ms=$delayMs")
        }
        return delayMs
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        budget.onLoadTaskConcluded(loadTaskId)
        firstErrorAtByLoadTask.remove(loadTaskId)
        super.onLoadTaskConcluded(loadTaskId)
    }

    /** Failures that are still being retried never surface; a spent budget does, as a fatal load error. */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        Int.MAX_VALUE

    private companion object {
        const val TAG = "SidecarSubtitleRetry"
    }
}

/**
 * What one sidecar load task has failed with so far, this failure included.
 * [timeouts] and [cutOffs] are the failures that each cost the server an
 * extraction.
 */
internal data class SidecarSubtitleFailures(
    val count: Int,
    val timeouts: Int = 0,
    val cutOffs: Int = 0,
) {
    val extractions: Int get() = timeouts + cutOffs
}

internal class SidecarSubtitleRetryBudget {
    private val failuresByLoadTask = ConcurrentHashMap<Long, SidecarSubtitleFailures>()

    fun record(loadTaskId: Long, error: Throwable?): SidecarSubtitleFailures {
        val chain = generateSequence(error) { it.cause }.toList()
        val timedOut = chain.any { it is SocketTimeoutException && !it.isBeforeRequest() }
        val cutOff = !timedOut && chain.any { it is ProtocolException && it.isUnexpectedEndOfStream() }
        return failuresByLoadTask.compute(loadTaskId) { _, previous ->
            val before = previous ?: SidecarSubtitleFailures(count = 0)
            SidecarSubtitleFailures(
                count = before.count + 1,
                timeouts = before.timeouts + if (timedOut) 1 else 0,
                cutOffs = before.cutOffs + if (cutOff) 1 else 0,
            )
        }!!
    }

    fun onLoadTaskConcluded(loadTaskId: Long) {
        failuresByLoadTask.remove(loadTaskId)
    }
}

/**
 * A timeout while the connection was still being set up: no request reached
 * the server, so no extraction started. Android reports a connect timeout as
 * "failed to connect to … after 20000ms", the JVM as "connect timed out", and a
 * stalled TLS handshake as "SSL handshake timed out".
 */
private fun SocketTimeoutException.isBeforeRequest(): Boolean {
    val text = message?.lowercase() ?: return false
    return "failed to connect" in text || "connect timed out" in text || "handshake" in text
}

internal fun sidecarSubtitleRetryDelayMs(
    responseCode: Int? = null,
    retryAfterHeaders: List<String> = emptyList(),
    cause: Throwable? = null,
    failures: SidecarSubtitleFailures,
    observedElapsedMs: Long = 0L,
): Long {
    if (failures.timeouts > SIDECAR_SUBTITLE_MAX_TIMEOUT_RETRIES) return C.TIME_UNSET
    if (failures.extractions > SIDECAR_SUBTITLE_MAX_EXTRACTION_RETRIES) return C.TIME_UNSET
    return siloMediaLoadRetryDelayMs(
        responseCode = responseCode,
        retryAfterHeaders = retryAfterHeaders,
        cause = cause,
        errorCount = failures.count,
        observedElapsedMs = observedElapsedMs,
    )
}

/** Retries a sidecar load task gets after failures that each cost the server an extraction. */
internal const val SIDECAR_SUBTITLE_MAX_EXTRACTION_RETRIES = 2

/** Of those, retries after a request that timed out waiting for data. */
internal const val SIDECAR_SUBTITLE_MAX_TIMEOUT_RETRIES = 1

/**
 * How long a sidecar request waits for its next bytes. A cold extraction sends
 * the WebVTT header and then nothing until it reaches the first cue, which on a
 * network mount can take minutes for a sparse track.
 */
internal const val SIDECAR_SUBTITLE_READ_TIMEOUT_SECONDS = 180L

/** The player transport, waiting [SIDECAR_SUBTITLE_READ_TIMEOUT_SECONDS] for each read. */
internal fun okhttp3.OkHttpClient.withSidecarSubtitleTimeouts(): okhttp3.OkHttpClient =
    newBuilder()
        .readTimeout(SIDECAR_SUBTITLE_READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()

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
