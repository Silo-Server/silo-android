package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException

class MediaLoadRetryPolicyTest {

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
    fun `stream reset retries only for resumable direct play after byte progress`() {
        val reset = StreamResetException(ErrorCode.INTERNAL_ERROR)

        assertEquals(
            1_000L,
            siloMediaLoadRetryDelayMs(
                cause = reset,
                errorCount = 1,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 32_768L,
            ),
        )
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                cause = reset,
                errorCount = 1,
                isResumableProgressiveDirectPlay = true,
                bytesLoaded = 0L,
            ),
        )
        assertEquals(
            C.TIME_UNSET,
            siloMediaLoadRetryDelayMs(
                cause = reset,
                errorCount = 1,
                isResumableProgressiveDirectPlay = false,
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
}
