package org.siloserver.silo.common.player.seek

import androidx.media3.common.PlaybackException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Media3SeekRecoveryPolicyTest {
    @Test
    fun acceptsParserRangeAndTimelineBoundaryFailures() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        ).forEach { errorCode ->
            assertTrue(isSameRouteSeekReanchorError(errorCode), "errorCode=$errorCode")
        }
    }

    @Test
    fun acceptsOnlySeekRelevantHttpStatuses() {
        listOf(404, 410, 416).forEach { status ->
            assertTrue(
                isSameRouteSeekReanchorError(
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    status,
                ),
                "status=$status",
            )
        }
        listOf(null, 401, 403, 429, 500, 503).forEach { status ->
            assertFalse(
                isSameRouteSeekReanchorError(
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    status,
                ),
                "status=$status",
            )
        }
    }

    @Test
    fun rejectsDecoderAudioAndOrdinaryTransportFailures() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ).forEach { errorCode ->
            assertFalse(isSameRouteSeekReanchorError(errorCode), "errorCode=$errorCode")
        }
    }
}
