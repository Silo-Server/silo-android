package org.siloserver.silo.common.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackLogger
import org.siloserver.silo.common.diagnostics.SiloLog

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlaybackErrorDiagnosticsTest {
    private val lines = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        SiloLog.installSink(null)
    }

    @Test
    fun http410PlayerErrorCarriesCodeAndStatusButNoUrl() {
        SiloLog.installSink { lines += it }
        val error = ExoPlaybackException.createForSource(
            gone(),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )

        DiagnosticsPlaybackLogger.playerError(error.toDiagnosticsDetail())

        val line = lines.single()
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals(
            "player error (ERROR_CODE_IO_BAD_HTTP_STATUS, http 410, InvalidResponseCodeException, IOException)",
            json.message(),
        )
        val attrs = json.attrs()
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS.toLong(), attrs.getValue("error_code").jsonPrimitive.long)
        assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS", attrs.text("error_name"))
        assertEquals(410L, attrs.getValue("http_status").jsonPrimitive.long)
        assertEquals("InvalidResponseCodeException", attrs.text("error_class"))
        assertNoPrivateStreamDetail(line)
    }

    @Test
    fun decoderRendererErrorNamesRendererDecoderFormatAndSupport() {
        SiloLog.installSink { lines += it }
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        val cause = MediaCodecRenderer.DecoderInitializationException(
            format,
            IllegalStateException("codec failed for $STREAM_URL"),
            false,
            0,
        )
        val error = ExoPlaybackException.createForRenderer(
            cause,
            "MediaCodecVideoRenderer",
            0,
            format,
            C.FORMAT_EXCEEDS_CAPABILITIES,
            null,
            false,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )

        DiagnosticsPlaybackLogger.playerError(error.toDiagnosticsDetail())

        val line = lines.single()
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals(
            "player error (ERROR_CODE_DECODER_INIT_FAILED, MediaCodecVideoRenderer, " +
                "DecoderInitializationException, IllegalStateException)",
            json.message(),
        )
        val attrs = json.attrs()
        assertEquals("MediaCodecVideoRenderer", attrs.text("renderer"))
        assertEquals(MimeTypes.VIDEO_H265, attrs.text("fmt"))
        assertEquals("NO_EXCEEDS_CAPABILITIES", attrs.text("format_support"))
        assertEquals("IllegalStateException", attrs.text("root_error_class"))
        assertNoPrivateStreamDetail(line)
    }

    @Test
    fun loadErrorCarriesDataTypeTrackTypeStatusAndCancellation() {
        SiloLog.installSink { lines += it }
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, null, 0, null, 0, 6_000)

        DiagnosticsPlaybackLogger.loadError(mediaLoadErrorDiagnosticsDetail(mediaLoadData, gone(), wasCanceled = false))

        val line = lines.single()
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals("media load error (media video, http 410, InvalidResponseCodeException, IOException)", json.message())
        val attrs = json.attrs()
        assertEquals("media", attrs.text("data_type"))
        assertEquals("video", attrs.text("track_type"))
        assertEquals(410L, attrs.getValue("http_status").jsonPrimitive.long)
        assertEquals("InvalidResponseCodeException", attrs.text("error_class"))
        assertFalse(attrs.getValue("was_canceled").jsonPrimitive.boolean)
        assertNoPrivateStreamDetail(line)
    }

    @Test
    fun transportLoadErrorNamesTheRootCauseWithoutItsMessage() {
        SiloLog.installSink { lines += it }
        val error = HttpDataSource.HttpDataSourceException(
            SocketTimeoutException("timeout reading $STREAM_URL"),
            DataSpec(Uri.parse(STREAM_URL)),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            HttpDataSource.HttpDataSourceException.TYPE_READ,
        )
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN, null, 0, null, 0, 0)

        DiagnosticsPlaybackLogger.loadError(mediaLoadErrorDiagnosticsDetail(mediaLoadData, error, wasCanceled = true))

        val line = lines.single()
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals(
            "media load error (manifest unknown, HttpDataSourceException, SocketTimeoutException, canceled)",
            json.message(),
        )
        assertEquals(false, json.attrs().containsKey("http_status"))
        assertNoPrivateStreamDetail(line)
    }

    @Test
    fun progressiveRetryLineKeepsByteCountsOutOfTheMessage() {
        SiloLog.installSink { lines += it }
        val policy = SiloMediaLoadErrorHandlingPolicy(
            isResumableProgressiveDirectPlay = { true },
            nowMs = { 0L },
        )
        val dataSpec = DataSpec.Builder().setUri(Uri.parse(STREAM_URL)).setPosition(2_097_152L).build()
        val loadEventInfo = LoadEventInfo.Builder(7L, dataSpec, 0L)
            .setBytesLoaded(1_048_576L)
            .build()

        policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                loadEventInfo,
                MediaLoadData(C.DATA_TYPE_MEDIA),
                StreamResetException(ErrorCode.CANCEL),
                1,
            ),
        )

        val line = lines.single()
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals(
            "progressive direct-play retry (StreamResetException CANCEL, try 1, retry_delay_ms=1000)",
            json.message(),
        )
        val attrs = json.attrs()
        assertEquals(1_048_576L, attrs.getValue("bytes_loaded").jsonPrimitive.long)
        assertEquals(3_145_728L, attrs.getValue("resume_offset").jsonPrimitive.long)
        assertEquals(1L, attrs.getValue("attempt").jsonPrimitive.long)
        assertEquals(1_000L, attrs.getValue("retry_delay_ms").jsonPrimitive.long)
        assertNoPrivateStreamDetail(line)
    }

    private fun gone(): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            410,
            "Gone",
            IOException("stream denied for $STREAM_URL"),
            mapOf("Location" to listOf(STREAM_URL)),
            DataSpec(Uri.parse(STREAM_URL)),
            """{"error":"playback_session_ended","url":"$STREAM_URL"}""".encodeToByteArray(),
        )

    private fun assertNoPrivateStreamDetail(line: String) {
        listOf("media.example.test", "stream/", "token", "secret-token", "private-item", "Gone", "denied")
            .forEach { fragment -> assertFalse(line.contains(fragment), "line leaked \"$fragment\": $line") }
    }

    private fun JsonObject.message(): String = getValue("msg").jsonPrimitive.content

    private fun JsonObject.attrs(): JsonObject = getValue("attrs").jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val STREAM_URL = "https://media.example.test/api/v1/stream/private-item?token=secret-token"
    }
}
