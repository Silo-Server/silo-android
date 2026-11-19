package org.siloserver.silo.common.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SidecarSubtitleLoadErrorHandlingPolicyTest {

    @Test
    fun `a timeout is retried once and a second timeout gives up`() {
        val timeout = SocketTimeoutException("timeout")
        assertEquals(1_000L, sidecarSubtitleRetryDelayMs(cause = timeout, failures = SidecarSubtitleFailures(1, timeouts = 1)))
        assertEquals(C.TIME_UNSET, sidecarSubtitleRetryDelayMs(cause = timeout, failures = SidecarSubtitleFailures(2, timeouts = 2)))
    }

    @Test
    fun `failures that cost an extraction share a budget of two retries`() {
        // A cut-off response restarts the whole extraction, like a timeout.
        val cutOff = IOException(ProtocolException("unexpected end of stream"))
        assertEquals(1_000L, sidecarSubtitleRetryDelayMs(cause = cutOff, failures = SidecarSubtitleFailures(1, cutOffs = 1)))
        assertEquals(2_000L, sidecarSubtitleRetryDelayMs(cause = cutOff, failures = SidecarSubtitleFailures(2, timeouts = 1, cutOffs = 1)))
        assertEquals(C.TIME_UNSET, sidecarSubtitleRetryDelayMs(cause = cutOff, failures = SidecarSubtitleFailures(3, timeouts = 1, cutOffs = 2)))
    }

    @Test
    fun `failures that cost no extraction keep the media window`() {
        // Until its response arrives a sidecar holds back the whole item, so a
        // server restart must not fail playback sooner than the media would.
        for (count in 1..5) {
            assertEquals(
                siloMediaLoadRetryDelayMs(responseCode = 503, errorCount = count),
                sidecarSubtitleRetryDelayMs(responseCode = 503, failures = SidecarSubtitleFailures(count)),
            )
        }
        assertEquals(
            C.TIME_UNSET,
            sidecarSubtitleRetryDelayMs(
                responseCode = 503,
                failures = SidecarSubtitleFailures(2),
                observedElapsedMs = MEDIA_LOAD_RETRY_WINDOW_MS,
            ),
        )
        assertEquals(C.TIME_UNSET, sidecarSubtitleRetryDelayMs(responseCode = 403, failures = SidecarSubtitleFailures(1)))
        assertEquals(C.TIME_UNSET, sidecarSubtitleRetryDelayMs(cause = IOException("generic"), failures = SidecarSubtitleFailures(1)))
    }

    @Test
    fun `the budget survives Media3 resetting errorCount after progress`() {
        val policy = SidecarSubtitleLoadErrorHandlingPolicy(nowMs = { 0L })
        val timeout = IOException(SocketTimeoutException("timeout"))

        // Each attempt extracted cues before stalling, so Media3 reports errorCount 1 every time.
        assertEquals(1_000L, policy.getRetryDelayMsFor(errorInfo(loadTaskId = 7L, timeout, errorCount = 1)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(loadTaskId = 7L, timeout, errorCount = 1)))
    }

    @Test
    fun `a deferred retry under a new load task shares the budget until the sidecar starts fresh`() {
        val policy = SidecarSubtitleLoadErrorHandlingPolicy(nowMs = { 0L })
        val timeout = IOException(SocketTimeoutException("timeout"))

        // Media3 declines the retry of a response of unknown length, concludes
        // the task, and restarts the stream from byte zero under a new one.
        assertEquals(1_000L, policy.getRetryDelayMsFor(errorInfo(loadTaskId = 7L, timeout, errorCount = 1)))
        policy.onLoadTaskConcluded(7L)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(errorInfo(loadTaskId = 8L, timeout, errorCount = 1)))

        // A seek or a new selection starts the load over on purpose.
        policy.startFresh()
        assertEquals(1_000L, policy.getRetryDelayMsFor(errorInfo(loadTaskId = 9L, timeout, errorCount = 1)))
    }

    @Test
    fun `a sidecar load restarts the server's extraction at most twice`() {
        val policy = SidecarSubtitleLoadErrorHandlingPolicy(nowMs = { 0L })
        val cutOff = IOException(ProtocolException("unexpected end of stream"))
        val delays = (1..5).map { policy.getRetryDelayMsFor(errorInfo(loadTaskId = 3L, cutOff, errorCount = it)) }

        assertEquals(listOf(1_000L, 2_000L, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET), delays)
        assertEquals(Int.MAX_VALUE, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))

        val unavailable = HttpDataSource.InvalidResponseCodeException(
            503, "Service Unavailable", null, emptyMap(), DataSpec(Uri.parse(URL)), ByteArray(0),
        )
        val fresh = SidecarSubtitleLoadErrorHandlingPolicy(nowMs = { 0L })
        val restart = (1..5).map { fresh.getRetryDelayMsFor(errorInfo(loadTaskId = 4L, unavailable, errorCount = it)) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), restart)
    }

    @Test
    fun `a body cut off by a reset or a closed connection counts against the extraction budget`() {
        // A reset is retryable without end inside the media window, and a
        // chunked body closed between chunks ends in EOFException, which the
        // media policy retries only for direct play.
        listOf(
            IOException(SocketException("Connection reset")),
            IOException(EOFException("\n not found: limit=0 content=…")),
        ).forEach { cutOff ->
            val policy = SidecarSubtitleLoadErrorHandlingPolicy(nowMs = { 0L })
            val delays = (1..4).map { attempt ->
                policy.getRetryDelayMsFor(
                    errorInfo(loadTaskId = 5L, cutOff, errorCount = attempt, bytesLoaded = attempt * 1_000L),
                )
            }
            assertEquals(listOf(1_000L, 2_000L, C.TIME_UNSET, C.TIME_UNSET), delays, cutOff.cause.toString())
        }

        // Reset before any byte arrived: no extraction is known to have started.
        val beforeBody = SidecarSubtitleRetryBudget().record(IOException(SocketException("Connection reset")))
        assertEquals(0, beforeBody.extractions)
    }

    @Test
    fun `a timeout before the connection opened costs no extraction`() {
        val policy = SidecarSubtitleLoadErrorHandlingPolicy(nowMs = { 0L })
        // What Android's socket reports when the connect timeout fires, as
        // Media3's OkHttp data source wraps it.
        val connect = HttpDataSource.HttpDataSourceException(
            SocketTimeoutException(
                "failed to connect to /192.0.2.10 (port 8096) from /192.0.2.20 (port 51234) after 20000ms",
            ),
            DataSpec(Uri.parse(URL)),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )
        val delays = (1..4).map { policy.getRetryDelayMsFor(errorInfo(loadTaskId = 9L, connect, errorCount = it)) }

        // The media window, not the two-timeout budget.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)
        listOf("connect timed out", "SSL handshake timed out").forEach { message ->
            val before = SidecarSubtitleRetryBudget().record(IOException(SocketTimeoutException(message)))
            assertEquals(0, before.timeouts, message)
        }
        val read = SidecarSubtitleRetryBudget().record(IOException(SocketTimeoutException("timeout")))
        assertEquals(1, read.timeouts)
    }

    @Test
    fun `sidecar transport waits longer for data on the same pool`() {
        val media = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).build()
        val sidecar = media.withSidecarSubtitleTimeouts()

        assertEquals(TimeUnit.SECONDS.toMillis(SIDECAR_SUBTITLE_READ_TIMEOUT_SECONDS).toInt(), sidecar.readTimeoutMillis)
        assertEquals(media.connectTimeoutMillis, sidecar.connectTimeoutMillis)
        assertSame(media.connectionPool, sidecar.connectionPool)
        assertTrue(sidecar.readTimeoutMillis > media.readTimeoutMillis)
    }

    private fun errorInfo(loadTaskId: Long, error: IOException, errorCount: Int, bytesLoaded: Long = 0L) =
        LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(
                loadTaskId,
                DataSpec(Uri.parse(URL)),
                Uri.parse(URL),
                emptyMap(),
                /* elapsedRealtimeMs= */ 0L,
                /* loadDurationMs= */ 0L,
                bytesLoaded,
            ),
            MediaLoadData(C.DATA_TYPE_MEDIA),
            error,
            errorCount,
        )

    private companion object {
        const val URL = "https://silo.example/api/v2/stream/session/subtitles/3.vtt"
    }
}
