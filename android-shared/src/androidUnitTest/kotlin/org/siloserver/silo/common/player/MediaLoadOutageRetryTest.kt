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
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A progressive load (the direct-play file, a PGS sidecar) is one load task
 * for the whole film, so it can meet several server restarts. Each restart
 * must get its own retry window (#586).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaLoadOutageRetryTest {
    private val directPlayUri = Uri.parse("http://silo.test/api/v2/stream/original")
    private val pgsSidecarUri = Uri.parse("http://silo.test/api/v2/subtitles/3.sup")
    private var nowMs = 0L
    private val policy = SiloMediaLoadErrorHandlingPolicy(
        isResumableProgressiveDirectPlay = { it == directPlayUri },
        nowMs = { nowMs },
    )

    @Test
    fun `a restart after the server came back gets a fresh window and backoff`() {
        listOf(directPlayUri to 1L, pgsSidecarUri to 2L).forEach { (uri, loadTaskId) ->
            // First restart: the stream drops 50 MiB in, and the server is still
            // down 70 s later.
            assertEquals(
                1_000L,
                policy.retryDelay(loadTaskId, uri, atMs = 0, position = 0, cumulativeBytes = 50 * MIB, errorCount = 1),
            )
            assertEquals(
                2_000L,
                policy.retryDelay(
                    loadTaskId, uri, atMs = 70_000, position = 50 * MIB, cumulativeBytes = 50 * MIB, errorCount = 2,
                    error = ConnectException("Connection refused"),
                ),
            )
            // The server comes back and streams another 50 MiB; 40 s after that
            // last failure a second restart drops it again. Media3 has reset its
            // own count to 1 and incremented it to 2.
            assertEquals(
                1_000L,
                policy.retryDelay(
                    loadTaskId, uri, atMs = 110_000, position = 50 * MIB, cumulativeBytes = 100 * MIB, errorCount = 2,
                ),
                "$uri second restart",
            )
            // The server is still down for the next attempt: same outage, next backoff step.
            assertEquals(
                2_000L,
                policy.retryDelay(
                    loadTaskId, uri, atMs = 111_000, position = 100 * MIB, cumulativeBytes = 100 * MIB, errorCount = 3,
                    error = ConnectException("Connection refused"),
                ),
            )
            // And this outage still ends 90 s after it began.
            assertEquals(
                C.TIME_UNSET,
                policy.retryDelay(
                    loadTaskId, uri, atMs = 110_000 + MEDIA_LOAD_RETRY_WINDOW_MS, position = 100 * MIB,
                    cumulativeBytes = 100 * MIB, errorCount = 4, error = ConnectException("Connection refused"),
                ),
            )
        }
    }

    @Test
    fun `partial bodies stay in one outage`() {
        // A server that answers a few KiB and then fails is still down.
        val delays = assertWindowExhausts(bytesPerAttempt = 2 * KIB)
        // Media3 restarts its count after that extraction progress, so backoff
        // stays at its second step instead of climbing.
        assertEquals(listOf(2_000L), delays.drop(1).distinct())
    }

    @Test
    fun `responses cut every few seconds stay in one outage`() {
        // A proxy that cuts every response 2 MiB in, seconds apart, lets
        // playback advance, but it may never keep up; running out the window
        // hands the player to a route that might.
        assertWindowExhausts(bytesPerAttempt = 2 * MIB)
    }

    @Test
    fun `a response cut at the same offset stays in one outage`() {
        // Each attempt resumes at 50 MiB (nothing it read was committed, as
        // inside an atom or sample larger than the cut) and is cut 2 MiB later,
        // a minute apart, with a 503 in between. Those bytes never take
        // playback past the cut.
        assertEquals(1_000L, policy.retryDelay(atMs = 0, position = 50 * MIB, cumulativeBytes = 2 * MIB, errorCount = 1))
        assertEquals(
            2_000L,
            policy.retryDelay(
                atMs = 60_000,
                position = 50 * MIB,
                cumulativeBytes = 2 * MIB,
                errorCount = 2,
                error = serviceUnavailable(directPlayUri),
            ),
        )
        assertEquals(
            C.TIME_UNSET,
            policy.retryDelay(atMs = 120_000, position = 50 * MIB, cumulativeBytes = 4 * MIB, errorCount = 3),
        )
    }

    /**
     * Fails every 10 s, each attempt resuming where the previous one stopped and
     * delivering [bytesPerAttempt] before it is cut. Returns the retry delays
     * before the window ran out.
     */
    private fun assertWindowExhausts(bytesPerAttempt: Long): List<Long> {
        var position = 50 * MIB
        var cumulativeBytes = 0L
        fun failAt(atMs: Long): Long {
            cumulativeBytes += bytesPerAttempt
            val delay = policy.retryDelay(atMs = atMs, position = position, cumulativeBytes = cumulativeBytes, errorCount = 2)
            position += bytesPerAttempt
            return delay
        }

        val delays = (0L until MEDIA_LOAD_RETRY_WINDOW_MS step 10_000L).map(::failAt)
        assertTrue(delays.all { it > 0L }, "retries before the window ran out: $delays")
        assertEquals(C.TIME_UNSET, failAt(MEDIA_LOAD_RETRY_WINDOW_MS))
        return delays
    }

    private fun SiloMediaLoadErrorHandlingPolicy.retryDelay(
        loadTaskId: Long = 1L,
        uri: Uri = directPlayUri,
        atMs: Long,
        position: Long,
        cumulativeBytes: Long,
        errorCount: Int,
        error: IOException = unexpectedEndOfStream(uri),
    ): Long {
        nowMs = atMs
        val dataSpec = DataSpec.Builder().setUri(uri).setPosition(position).build()
        return getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(loadTaskId, dataSpec, uri, emptyMap(), atMs, atMs, cumulativeBytes),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                error,
                errorCount,
            ),
        )
    }

    private fun unexpectedEndOfStream(uri: Uri): IOException =
        HttpDataSource.HttpDataSourceException(
            ProtocolException("unexpected end of stream"),
            DataSpec(uri),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            HttpDataSource.HttpDataSourceException.TYPE_READ,
        )

    private fun serviceUnavailable(uri: Uri): IOException =
        HttpDataSource.InvalidResponseCodeException(
            503,
            "Service Unavailable",
            null,
            emptyMap(),
            DataSpec(uri),
            ByteArray(0),
        )

    private companion object {
        const val KIB = 1L shl 10
        const val MIB = 1L shl 20
    }
}
