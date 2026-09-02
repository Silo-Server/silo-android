package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.StubExoPlayer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class FailedRendererTrackTypeTest {

    private fun rendererError(
        mime: String,
        errorCode: Int = PlaybackException.ERROR_CODE_DECODING_FAILED,
    ): ExoPlaybackException =
        ExoPlaybackException.createForRenderer(
            RuntimeException("decode failed"),
            "renderer",
            0,
            Format.Builder().setSampleMimeType(mime).build(),
            C.FORMAT_HANDLED,
            false,
            errorCode,
        )

    /**
     * What MediaSession delivers to the controller. The session bundles the
     * error and the controller rebuilds a base exception from it, so the
     * renderer attribution does not survive the crossing.
     */
    private fun controllerCopy(source: PlaybackException): PlaybackException =
        PlaybackException.fromBundle(source.toBundle())

    private fun serviceWithError(error: ExoPlaybackException): ExoPlayer = object : StubExoPlayer() {
        override fun getPlayerError(): ExoPlaybackException = error
    }

    @Test
    fun typedRendererErrorReportsItsOwnTrackType() {
        assertEquals(C.TRACK_TYPE_VIDEO, rendererError(MimeTypes.VIDEO_DOLBY_VISION).failedRendererTrackType())
        assertEquals(C.TRACK_TYPE_AUDIO, rendererError(MimeTypes.AUDIO_TRUEHD).failedRendererTrackType())
    }

    @Test
    fun controllerCopyRecoversTheTrackTypeFromTheServicePlayer() {
        val typed = rendererError(MimeTypes.VIDEO_DOLBY_VISION)
        val delivered = controllerCopy(typed)

        assertNull(delivered.failedRendererTrackType(), "the controller-side copy carries no renderer attribution")
        assertEquals(
            C.TRACK_TYPE_VIDEO,
            delivered.failedRendererTrackType(serviceWithError(typed)),
            "the in-process player still holds the typed exception for the same failure",
        )
    }

    @Test
    fun aStaleServiceErrorIsNotAttributedToANewFailure() {
        // Robolectric's clock is frozen, so the timestamps match; the error
        // code is the other half of the identity check and differs here.
        val earlier = rendererError(MimeTypes.VIDEO_DOLBY_VISION, PlaybackException.ERROR_CODE_DECODING_FAILED)
        val later = controllerCopy(
            rendererError(MimeTypes.AUDIO_AAC, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
        )

        assertNull(
            later.failedRendererTrackType(serviceWithError(earlier)),
            "a service error that does not match the delivered failure must not be reused",
        )
    }

    @Test
    fun nonRendererErrorsHaveNoTrackType() {
        val source = ExoPlaybackException.createForSource(
            java.io.IOException("gone"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        assertNull(source.failedRendererTrackType())
        assertNull(controllerCopy(source).failedRendererTrackType(serviceWithError(source)))
        assertNull(controllerCopy(source).failedRendererTrackType(null))
    }
}
