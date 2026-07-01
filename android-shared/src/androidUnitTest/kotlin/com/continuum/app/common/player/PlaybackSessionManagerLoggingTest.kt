package com.continuum.app.common.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSessionManagerLoggingTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/common/player/PlaybackSessionManager.kt",
    ).readText()

    @Test
    fun startSessionSuccessLogDoesNotIncludeRawStreamUrl() {
        val successLog = source
            .substringAfter("is ApiResult.Success -> Log.i")
            .substringBefore("is ApiResult.Error")

        assertTrue(
            successLog.contains("playMethod="),
            "startSession success logs should keep non-sensitive playback metadata",
        )
        assertTrue(
            source.contains("qualityPreference=${'$'}qualityPreference"),
            "startSession request logs should include the non-sensitive quality preference",
        )
        assertFalse(
            successLog.contains("streamUrl="),
            "startSession success logs must not label raw stream URLs",
        )
        assertFalse(
            successLog.contains("result.data.streamUrl"),
            "startSession success logs must not read raw stream URLs",
        )
    }

    @Test
    fun transcodeFallbackLogsNonSensitiveRequestShape() {
        val fallbackBody = source
            .substringAfter("suspend fun startTranscodeFallback(")
            .substringBefore("return when")

        assertTrue(
            fallbackBody.contains("startTranscodeFallback session="),
            "fallback logs should identify the session and mode being requested",
        )
        assertTrue(
            fallbackBody.contains("targetResolution=${'$'}{request.targetResolution}"),
            "fallback logs should include target resolution for 4K guard debugging",
        )
        assertTrue(
            fallbackBody.contains("targetCodecVideo=${'$'}{request.targetCodecVideo}"),
            "fallback logs should include the video codec mode",
        )
        assertTrue(
            fallbackBody.contains("targetBitrateKbps=${'$'}{request.targetBitrateKbps}"),
            "fallback logs should include requested bitrate",
        )
    }
}
