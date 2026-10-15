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
            // First restart: the stream drops 50 MiB in and the loader resumes.
            assertEquals(
                1_000L,
                policy.retryDelay(loadTaskId, uri, atMs = 0, position = 0, cumulativeBytes = 50 * MIB, errorCount = 1),
            )
            // 130 s later, after another 50 MiB streamed, the second restart drops
            // it again. Media3 has reset its own count to 1 and incremented it to 2.
            assertEquals(
                1_000L,
                policy.retryDelay(
                    loadTaskId, uri, atMs = 130_000, position = 50 * MIB, cumulativeBytes = 100 * MIB, errorCount = 2,
                ),
                "$uri second restart",
            )
            // The server is still down for the next attempt: same outage, next backoff step.
            assertEquals(
                2_000L,
                policy.retryDelay(
                    loadTaskId, uri, atMs = 131_000, position = 100 * MIB, cumulativeBytes = 100 * MIB, errorCount = 3,
                    error = ConnectException("Connection refused"),
                ),
            )
            // And this outage still ends 90 s after it began.
            assertEquals(
                C.TIME_UNSET,
                policy.retryDelay(
                    loadTaskId, uri, atMs = 130_000 + MEDIA_LOAD_RETRY_WINDOW_MS, position = 100 * MIB,
                    cumulativeBytes = 100 * MIB, errorCount = 4, error = ConnectException("Connection refused"),
                ),
            )
        }
    }

    @Test
    fun `partial bodies stay in one outage`() {
        // A server that answers a few KiB and then fails is still down, even
        // though each attempt resumes where the previous one stopped.
        val delays = assertWindowExhausts(resumesWherePreviousStopped = true) { 2 * KIB }
        // Media3 restarts its count after that extraction progress, so backoff
        // stays at its second step instead of climbing.
        assertEquals(listOf(2_000L), delays.drop(1).distinct())
    }

    @Test
    fun `a response cut before playback can advance stays in one outage`() {
        // Each attempt resumes at the same position (nothing it read was
        // committed, as inside an atom or sample larger than the cut). Every
        // other one is cut 2 MiB later and the rest get a 503. Those bytes
        // never take playback past the cut.
        assertWindowExhausts(resumesWherePreviousStopped = false) { attempt ->
            if (attempt % 2 == 0) 2 * MIB else 0L
        }
    }

    /**
     * Fails every 10 s, attempt n delivering [bytesForAttempt] of n before it is
     * cut (a 503 when that is nothing), and returns the retry delays before the
     * window ran out.
     */
    private fun assertWindowExhausts(
        resumesWherePreviousStopped: Boolean,
        bytesForAttempt: (Int) -> Long,
    ): List<Long> {
        var position = 50 * MIB
        var cumulativeBytes = 0L
        var attempt = 0
        fun failAt(atMs: Long): Long {
            val bytes = bytesForAttempt(attempt++)
            cumulativeBytes += bytes
            val delay = policy.retryDelay(
                atMs = atMs,
                position = position,
                cumulativeBytes = cumulativeBytes,
                errorCount = 2,
                error = if (bytes == 0L) serviceUnavailable(directPlayUri) else unexpectedEndOfStream(directPlayUri),
            )
            if (resumesWherePreviousStopped) position += bytes
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
