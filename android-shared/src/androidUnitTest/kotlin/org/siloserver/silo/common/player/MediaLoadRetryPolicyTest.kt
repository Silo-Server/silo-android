package org.siloserver.silo.common.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaLoadRetryPolicyTest {
    private val mediaUri = Uri.parse("https://silo.test/stream/original")
    private var nowMs = 1_000_000L
    private val policy = SiloMediaLoadErrorHandlingPolicy(
        isResumableProgressiveDirectPlay = { it == mediaUri },
        nowMs = { nowMs },
    )

    /**
     * One connection reset of an attempt that opened at [position], with
     * [cumulativeBytes] loaded by the task in all. The policy keeps its own
     * count, so Media3's [errorCount] only matters where a test shows it is
     * ignored.
     */
    private fun failDirectPlay(
        cumulativeBytes: Long,
        position: Long = 0L,
        errorCount: Int = 1,
        loadTaskId: Long = 1L,
    ): Long =
        policy.getRetryDelayMsFor(
            loadErrorInfo(
                uri = mediaUri,
                loadTaskId = loadTaskId,
                position = position,
                error = HttpDataSource.HttpDataSourceException(
                    SocketException("Connection reset"),
                    DataSpec(mediaUri),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_READ,
                ),
                errorCount = errorCount,
                bytesLoaded = cumulativeBytes,
            ),
        )

    @Test
    fun `a direct-play cut long after a resumed one gets a fresh window and count`() {
        // #981. Media3 resets its own count only after a retry that extracted
        // samples, so a load that rode out a long outage reports a high count
        // when the next one starts.
        repeat(13) {
            assertTrue(failDirectPlay(cumulativeBytes = 4L shl 20) > 0L)
            nowMs += 5_000L
        }
        nowMs += 11 * 60_000L

        assertEquals(1_000L, failDirectPlay(cumulativeBytes = 900L shl 20, errorCount = 14))
        nowMs += 1_000L
        assertEquals(2_000L, failDirectPlay(cumulativeBytes = 900L shl 20))
        nowMs += 2_000L
        assertEquals(4_000L, failDirectPlay(cumulativeBytes = 900L shl 20))
    }

    @Test
    fun `only a meaningful recovery starts a new direct-play outage`() {
        // Each case: the first cut ends at 4 MiB, then a second attempt opens
        // at `position`, loads `attemptBytes` and is cut `gapMs` later. A new
        // outage retries after 1 s; the same one is out of window at 89 s, or
        // on its second backoff step sooner.
        data class Case(val name: String, val gapMs: Long, val position: Long, val attemptBytes: Long, val expected: Long)
        val mib = 1L shl 20
        val cases = listOf(
            Case("recovered", gapMs = 89_000L, position = 4 * mib, attemptBytes = mib, expected = 1_000L),
            Case("a low-bitrate recovery", gapMs = 89_000L, position = 4 * mib, attemptBytes = 600L shl 10, expected = 1_000L),
            Case("a few kilobytes", gapMs = 89_000L, position = 4 * mib, attemptBytes = 8_192L, expected = C.TIME_UNSET),
            Case("cut at the same byte", gapMs = 89_000L, position = mib, attemptBytes = 3 * mib, expected = C.TIME_UNSET),
            Case("cut again within 20 s", gapMs = 10_000L, position = 4 * mib, attemptBytes = mib, expected = 2_000L),
        )
        cases.forEachIndexed { index, case ->
            val task = 100L + index
            assertEquals(1_000L, failDirectPlay(cumulativeBytes = 4 * mib, loadTaskId = task), case.name)
            nowMs += case.gapMs
            assertEquals(
                case.expected,
                failDirectPlay(
                    cumulativeBytes = 4 * mib + case.attemptBytes,
                    position = case.position,
                    loadTaskId = task,
                ),
                case.name,
            )
        }
    }

    @Test
    fun `attempt progress is derived from cumulative load task bytes`() {
        val tracker = MediaLoadAttemptBytesTracker()

        assertEquals(
            1_024L,
            tracker.bytesLoadedForAttempt(loadTaskId = 7L, cumulativeBytesLoaded = 1_024L),
        )
        assertEquals(
            0L,
            tracker.bytesLoadedForAttempt(loadTaskId = 7L, cumulativeBytesLoaded = 1_024L),
        )
        assertEquals(
            2_048L,
            tracker.bytesLoadedForAttempt(loadTaskId = 7L, cumulativeBytesLoaded = 3_072L),
        )

        tracker.onLoadTaskConcluded(loadTaskId = 7L)
        assertEquals(
            3_072L,
            tracker.bytesLoadedForAttempt(loadTaskId = 7L, cumulativeBytesLoaded = 3_072L),
        )
    }

    @Test
    fun `gateway and missing-session statuses retry with exponential backoff`() {
        assertEquals(1_000L, siloMediaLoadRetryDelayMs(responseCode = 503, errorCount = 1))
        assertEquals(2_000L, siloMediaLoadRetryDelayMs(responseCode = 503, errorCount = 2))
        assertEquals(4_000L, siloMediaLoadRetryDelayMs(responseCode = 503, errorCount = 3))
        assertEquals(8_000L, siloMediaLoadRetryDelayMs(responseCode = 503, errorCount = 4))
        assertEquals(8_000L, siloMediaLoadRetryDelayMs(responseCode = 404, errorCount = 5))
        assertEquals(8_000L, siloMediaLoadRetryDelayMs(responseCode = 530, errorCount = 5))
    }

    @Test
    fun `retry-after header wins when it fits retry window`() {
        assertEquals(
            3_000L,
            siloMediaLoadRetryDelayMs(
                responseCode = 503,
                retryAfterHeaders = listOf("3"),
                errorCount = 1,
            ),
        )
    }

    @Test
    fun `retry-after beyond outage window stops retrying`() {
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                responseCode = 503,
                retryAfterHeaders = listOf("120"),
                errorCount = 1,
            ),
        )
    }

    @Test
    fun `non transient response codes do not retry`() {
        assertEquals(C.TIME_UNSET, siloMediaLoadRetryDelayMs(responseCode = 401, errorCount = 1))
        assertEquals(C.TIME_UNSET, siloMediaLoadRetryDelayMs(responseCode = 403, errorCount = 1))
        assertEquals(C.TIME_UNSET, siloMediaLoadRetryDelayMs(responseCode = 416, errorCount = 1))
        assertEquals(C.TIME_UNSET, siloMediaLoadRetryDelayMs(responseCode = 418, errorCount = 1))
    }

    @Test
    fun `connection failures retry but generic IO does not`() {
        assertEquals(
            1_000L,
            siloMediaLoadRetryDelayMs(
                cause = SocketTimeoutException("timeout"),
                errorCount = 1,
            ),
        )
        assertEquals(
            1_000L,
            siloMediaLoadRetryDelayMs(
                cause = ConnectException("connection refused"),
                errorCount = 1,
            ),
        )
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                cause = IOException("parser failed"),
                errorCount = 1,
            ),
        )
    }

    @Test
    fun `wrapped premature response end retries from the progressive load position`() {
        assertEquals(
            1_000L,
            siloMediaLoadRetryDelayMs(
                cause = IOException(
                    "Media3 data source read failed",
                    ProtocolException("unexpected end of stream"),
                ),
                errorCount = 1,
            ),
        )
        assertEquals(
            1_000L,
            siloMediaLoadRetryDelayMs(
                cause = ProtocolException("unexpected end of stream"),
                errorCount = 1,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 4_096L,
            ),
        )
        assertEquals(
            1_000L,
            siloMediaLoadRetryDelayMs(
                cause = ProtocolException("unexpected end of stream"),
                errorCount = 1,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 0L,
            ),
        )
        assertEquals(
            2_000L,
            siloMediaLoadRetryDelayMs(
                cause = EOFException("response body ended early"),
                errorCount = 2,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 8_192L,
            ),
        )
    }

    @Test
    fun `mid-body transport failures retry only for resumable direct play after byte progress`() {
        val failures = listOf(
            StreamResetException(ErrorCode.INTERNAL_ERROR),
            // How Android 10 reports a peer that reset a TLS connection under a read (#981).
            SSLException("Read error: ssl=0x7a: I/O error during system call, Connection reset by peer"),
        )
        for (failure in failures) {
            assertEquals(
                1_000L,
                siloMediaLoadRetryDelayMs(
                    cause = failure,
                    errorCount = 1,
                    isResumableProgressiveDirectPlay = true,
                    bytesLoaded = 32_768L,
                ),
                "$failure",
            )
            assertEquals(
                C.TIME_UNSET,
                siloMediaLoadRetryDelayMs(
                    cause = failure,
                    errorCount = 1,
                    isResumableProgressiveDirectPlay = true,
                    bytesLoaded = 0L,
                ),
                "$failure",
            )
            assertEquals(
                C.TIME_UNSET,
                siloMediaLoadRetryDelayMs(
                    cause = failure,
                    errorCount = 1,
                    isResumableProgressiveDirectPlay = false,
                    bytesLoaded = 32_768L,
                ),
                "$failure",
            )
        }
        // A certificate the device rejects stays fatal.
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                cause = SSLHandshakeException("Trust anchor for certification path not found."),
                errorCount = 1,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 32_768L,
            ),
        )
    }

    @Test
    fun `entity changes never retry`() {
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                cause = EntityChangedException(),
                errorCount = 1,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 32_768L,
            ),
        )
    }

    @Test
    fun `parser failures still retry when wrapping a socket failure`() {
        assertEquals(
            1_000L,
            siloMediaLoadRetryDelayMs(
                cause = ParserException.createForMalformedContainer(
                    "parser observed transport failure",
                    SocketException("connection reset"),
                ),
                errorCount = 1,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 32_768L,
            ),
        )
    }

    @Test
    fun `parser failures do not become retries through a nested eof`() {
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                cause = ParserException.createForMalformedContainer(
                    "truncated container metadata",
                    EOFException("parser reached end of input"),
                ),
                errorCount = 1,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 32_768L,
            ),
        )
    }

    @Test
    fun `unrelated protocol errors remain fatal`() {
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                cause = IOException(
                    "Media3 data source read failed",
                    ProtocolException("unexpected status line"),
                ),
                errorCount = 1,
            ),
        )
    }

    @Test
    fun `retry window eventually expires`() {
        assertEquals(C.TIME_UNSET, siloMediaLoadRetryDelayMs(responseCode = 503, errorCount = 20))
    }

    @Test
    fun `time spent inside failed attempts counts against the window`() {
        // The count-derived estimate only sums the delays BETWEEN attempts, so
        // on the second error it believes ~1s has passed. A 30s-socket-timeout
        // server burns far more than that per attempt, and the window silently
        // stretched to minutes of spinner before an error surfaced.
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                responseCode = 503,
                errorCount = 2,
                observedElapsedMs = MEDIA_LOAD_RETRY_WINDOW_MS,
            ),
        )
        // Unmeasured callers keep the previous derived behaviour.
        assertTrue(siloMediaLoadRetryDelayMs(responseCode = 503, errorCount = 2) > 0L)
    }
}

/**
 * One failed load of [uri] for the policies under test; [bytesLoaded] is the
 * task's total and [position] where the failed attempt opened.
 */
@OptIn(UnstableApi::class)
internal fun loadErrorInfo(
    uri: Uri,
    loadTaskId: Long,
    error: IOException,
    errorCount: Int,
    bytesLoaded: Long = 0L,
    position: Long = 0L,
) = LoadErrorHandlingPolicy.LoadErrorInfo(
    LoadEventInfo(
        loadTaskId,
        DataSpec.Builder().setUri(uri).setPosition(position).build(),
        uri,
        emptyMap(),
        /* elapsedRealtimeMs= */ 0L,
        /* loadDurationMs= */ 0L,
        bytesLoaded,
    ),
    MediaLoadData(C.DATA_TYPE_MEDIA),
    error,
    errorCount,
)
