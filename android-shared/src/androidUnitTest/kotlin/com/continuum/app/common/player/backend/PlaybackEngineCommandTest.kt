package com.continuum.app.common.player.backend

import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind
import com.continuum.app.model.playback.PlaybackRouteFamily
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackEngineCommandTest {
    @Test
    fun requestRoundTripsThroughJson() {
        val request = VideoPlaybackBackendRequest(
            contentId = "abc",
            fileId = 7,
            playMethod = PlayMethod.DIRECT,
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            plannedEngine = PlaybackEngineKind.MPV_DIRECT,
            routeFamily = PlaybackRouteFamily.COMPATIBILITY_DIRECT,
            formFactor = VideoPlaybackFormFactor.Tv,
            preference = VideoPlaybackBackendPreference.Auto,
            hasHardContainer = true,
            hasStyledSubtitles = true,
            isCasting = false,
            isDrmProtected = false,
            isExternalDisplay = false,
            mpvSupportedOnDevice = true,
        )
        val restored = PlaybackEngineCommand.decode(PlaybackEngineCommand.encode(request))
        assertEquals(request, restored)
    }

    @Test
    fun commandActionAndArgKeyAreStable() {
        assertEquals("silo.SET_ENGINE", PlaybackEngineCommand.SET_ENGINE)
        assertEquals("request_json", PlaybackEngineCommand.ARG_REQUEST_JSON)
    }
}
