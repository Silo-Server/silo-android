package org.siloserver.silo.common.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.siloserver.silo.network.TokenManagerImpl

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class ProgressiveDirectPlayResumeIntegrationTest {
    private val fixture = seekableWavFixture()
    private val players = mutableListOf<ExoPlayer>()
    private val clients = mutableListOf<OkHttpClient>()
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest
    fun tearDown() {
        players.forEach(ExoPlayer::release)
        clients.forEach { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        servers.forEach(MockWebServer::shutdown)
    }

    @Test
    fun `progressive source resumes exact byte range and ends without player error`() {
        val dispatcher = RangeFixtureDispatcher(fixture, ResumeResponse.COMPLETE)
        val harness = prepareHarness(dispatcher)

        awaitResumeRequest(harness.player, dispatcher)
        TestPlayerRunHelper.play(harness.player).untilState(Player.STATE_ENDED)

        assertNull(harness.player.playerError)
        assertEquals(2, dispatcher.requests.size)
        assertEquals(1, harness.loadErrors.size)
        assertFalse(harness.loadErrors.single().wasCanceled)
        assertNull(dispatcher.requests[0].getHeader("Range"))
        assertEquals(
            "bytes=${fixture.size / 2}-",
            dispatcher.requests[1].getHeader("Range"),
        )
        assertEquals("\"fixture-v1\"", dispatcher.requests[1].getHeader("If-Range"))
    }

    @Test
    fun `zero progress unexpected end on ranged reopen retries again`() {
        val dispatcher = RangeFixtureDispatcher(fixture, ResumeResponse.NO_PROGRESS)
        val harness = prepareHarness(dispatcher)

        awaitResumeRequest(harness.player, dispatcher)
        try {
            RobolectricUtil.runMainLooperUntil { dispatcher.requests.size >= 3 }
        } catch (error: TimeoutException) {
            val retryLogs = ShadowLog.getLogsForTag("MediaLoadRetry")
                .joinToString(separator = " | ") { it.msg }
            throw AssertionError(
                "third retry request not observed: requests=${dispatcher.requests.size}, " +
                    "loadErrors=${harness.loadErrors.size}, state=${harness.player.playbackState}, " +
                    "isLoading=${harness.player.isLoading}, playerError=${harness.player.playerError}, " +
                    "retryLogs=[$retryLogs]",
                error,
            )
        }

        assertEquals(
            "bytes=${fixture.size / 2}-",
            dispatcher.requests[1].getHeader("Range"),
        )
        assertEquals(
            "bytes=${fixture.size / 2}-",
            dispatcher.requests[2].getHeader("Range"),
        )
    }

    @Test
    fun `changed etag aborts progressive resume`() {
        val dispatcher = RangeFixtureDispatcher(fixture, ResumeResponse.ENTITY_CHANGED)
        val harness = prepareHarness(dispatcher)

        awaitResumeRequest(harness.player, dispatcher)
        val terminal = awaitTerminalLoadError(harness, dispatcher)

        assertTrue(terminal.error.hasCause<EntityChangedException>())
        assertTrue(terminal.wasCanceled)
        assertEquals(2, dispatcher.requests.size)
    }

    @Test
    fun `range not satisfiable aborts progressive resume`() {
        val dispatcher = RangeFixtureDispatcher(fixture, ResumeResponse.RANGE_NOT_SATISFIABLE)
        val harness = prepareHarness(dispatcher)

        awaitResumeRequest(harness.player, dispatcher)
        val terminal = awaitTerminalLoadError(harness, dispatcher)

        val invalidResponse = terminal.error.findCause<HttpDataSource.InvalidResponseCodeException>()
        assertEquals(416, invalidResponse?.responseCode)
        assertTrue(terminal.wasCanceled)
        assertEquals(2, dispatcher.requests.size)
    }

    private fun awaitResumeRequest(
        player: ExoPlayer,
        dispatcher: RangeFixtureDispatcher,
    ) {
        try {
            RobolectricUtil.runMainLooperUntil { dispatcher.requests.size >= 2 }
        } catch (error: TimeoutException) {
            val retryLogs = ShadowLog.getLogsForTag("MediaLoadRetry")
                .joinToString(separator = " | ") { it.msg }
            throw AssertionError(
                "resume request not observed: requests=${dispatcher.requests.size}, " +
                    "state=${player.playbackState}, isLoading=${player.isLoading}, " +
                    "playerError=${player.playerError}, retryLogs=[$retryLogs]",
                error,
            )
        }
    }

    private fun awaitTerminalLoadError(
        harness: ProgressiveHarness,
        dispatcher: RangeFixtureDispatcher,
    ): ObservedLoadError {
        try {
            RobolectricUtil.runMainLooperUntil { harness.loadErrors.size >= 2 }
            RobolectricUtil.runMainLooperUntil {
                !harness.player.isLoading || dispatcher.requests.size > 2
            }
        } catch (error: TimeoutException) {
            val retryLogs = ShadowLog.getLogsForTag("MediaLoadRetry")
                .joinToString(separator = " | ") { it.msg }
            throw AssertionError(
                "terminal load error not observed: requests=${dispatcher.requests.size}, " +
                    "loadErrors=${harness.loadErrors.size}, state=${harness.player.playbackState}, " +
                    "isLoading=${harness.player.isLoading}, playerError=${harness.player.playerError}, " +
                    "retryLogs=[$retryLogs]",
                error,
            )
        }
        assertEquals(2, dispatcher.requests.size, "Terminal failures must not issue a third request.")
        return harness.loadErrors[1]
    }

    private fun prepareHarness(dispatcher: Dispatcher): ProgressiveHarness {
        val server = MockWebServer().also {
            it.dispatcher = dispatcher
            it.start()
            servers += it
        }
        val mediaClient = OkHttpClient().also {
            clients += it
        }
        val refreshClient = OkHttpClient().also {
            clients += it
        }
        val authSession = MediaAuthSession(TokenManagerImpl(), refreshClient)
        val upstreamFactory = OkHttpDataSource.Factory(mediaClient)
        val mediaUri = Uri.parse(server.url("/fixture.wav").toString())
        val guardedFactory = DataSource.Factory {
            RefreshingHttpDataSource(
                factory = upstreamFactory,
                authSession = authSession,
                isResumableDirectPlayUri = { it == mediaUri },
            )
        }
        val mediaSourceFactory = ProgressiveMediaSource.Factory(guardedFactory)
            .setLoadErrorHandlingPolicy(
                SiloMediaLoadErrorHandlingPolicy(
                    isResumableProgressiveDirectPlay = { it == mediaUri },
                ),
            )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mediaSource = mediaSourceFactory.createMediaSource(
            MediaItem.fromUri(mediaUri),
        )
        val loadErrors = CopyOnWriteArrayList<ObservedLoadError>()
        mediaSource.addEventListener(
            Handler(Looper.getMainLooper()),
            object : MediaSourceEventListener {
                override fun onLoadError(
                    windowIndex: Int,
                    mediaPeriodId: MediaSource.MediaPeriodId?,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean,
                ) {
                    loadErrors += ObservedLoadError(
                        error = error,
                        cumulativeBytesLoaded = loadEventInfo.bytesLoaded,
                        wasCanceled = wasCanceled,
                    )
                }
            },
        )
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setClock(FakeClock(/* isAutoAdvancing = */ true))
            .build()
            .also { player ->
                players += player
                player.setMediaSource(mediaSource)
                player.prepare()
            }
        return ProgressiveHarness(player, loadErrors)
    }

    private data class ProgressiveHarness(
        val player: ExoPlayer,
        val loadErrors: CopyOnWriteArrayList<ObservedLoadError>,
    )

    private data class ObservedLoadError(
        val error: IOException,
        val cumulativeBytesLoaded: Long,
        val wasCanceled: Boolean,
    )

    private enum class ResumeResponse {
        COMPLETE,
        NO_PROGRESS,
        ENTITY_CHANGED,
        RANGE_NOT_SATISFIABLE,
    }

    private class RangeFixtureDispatcher(
        private val fixture: ByteArray,
        private val resumeResponse: ResumeResponse,
    ) : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            return if (requests.size == 1) {
                partialResponse(start = 0, etag = "\"fixture-v1\"")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else {
                val start = request.getHeader("Range")
                    ?.let(RANGE_HEADER::matchEntire)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: return MockResponse().setResponseCode(400)
                when (resumeResponse) {
                    ResumeResponse.COMPLETE ->
                        partialResponse(start, etag = "\"fixture-v1\"")
                    ResumeResponse.NO_PROGRESS ->
                        partialResponse(start, etag = "\"fixture-v1\"", body = byteArrayOf())
                            .setHeader("Content-Length", fixture.size - start)
                            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    ResumeResponse.ENTITY_CHANGED ->
                        partialResponse(start, etag = "\"fixture-v2\"")
                    ResumeResponse.RANGE_NOT_SATISFIABLE ->
                        MockResponse()
                            .setResponseCode(416)
                            .setHeader("Content-Range", "bytes */${fixture.size}")
                            .setHeader("ETag", "\"fixture-v1\"")
                }
            }
        }

        private fun partialResponse(
            start: Int,
            etag: String,
            body: ByteArray = fixture.copyOfRange(start, fixture.size),
        ): MockResponse =
            MockResponse()
                .setResponseCode(206)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Type", "audio/wav")
                .setHeader("Content-Range", "bytes $start-${fixture.lastIndex}/${fixture.size}")
                .setHeader("ETag", etag)
                .setBody(Buffer().write(body))

        private companion object {
            val RANGE_HEADER = Regex("""bytes=(\d+)-""")
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        findCause<T>() != null

    private inline fun <reified T : Throwable> Throwable.findCause(): T? =
        generateSequence(this as Throwable?) { it.cause }.filterIsInstance<T>().firstOrNull()

    private companion object {
        fun seekableWavFixture(): ByteArray {
            val sampleRate = 8_000
            val pcm = ByteArray(sampleRate / 4) { index ->
                (128 + (index % 64) - 32).toByte()
            }
            return ByteArrayOutputStream(44 + pcm.size).apply {
                writeAscii("RIFF")
                writeLittleEndian(36 + pcm.size, 4)
                writeAscii("WAVE")
                writeAscii("fmt ")
                writeLittleEndian(16, 4)
                writeLittleEndian(1, 2)
                writeLittleEndian(1, 2)
                writeLittleEndian(sampleRate, 4)
                writeLittleEndian(sampleRate, 4)
                writeLittleEndian(1, 2)
                writeLittleEndian(8, 2)
                writeAscii("data")
                writeLittleEndian(pcm.size, 4)
                write(pcm)
            }.toByteArray()
        }

        fun ByteArrayOutputStream.writeAscii(value: String) {
            write(value.encodeToByteArray())
        }

        fun ByteArrayOutputStream.writeLittleEndian(value: Int, byteCount: Int) {
            repeat(byteCount) { shift ->
                write(value ushr (shift * 8) and 0xFF)
            }
        }
    }
}
