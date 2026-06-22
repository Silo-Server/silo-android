package com.continuum.app.common.player.mpv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvPlayerSourceTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/common/player/mpv/MpvPlayer.kt",
    )

    @Test
    fun mpvPlayerWrapsLibmpvAsMedia3Player() {
        val text = source.readText()

        assertTrue(text.contains("class MpvPlayer"))
        assertTrue(text.contains("BasePlayer()"))
        assertTrue(text.contains("MPVLib.create(context)"))
        assertTrue(text.contains("setOptionString(\"gpu-context\", \"android\")"))
        assertTrue(text.contains("setOptionString(\"opengl-es\", \"yes\")"))
        assertTrue(text.contains("setOptionString(\"cache\", \"yes\")"))
        assertTrue(text.contains("setOptionString(\"cache-pause-initial\", \"yes\")"))
        assertTrue(text.contains("setOptionString(\"sub-scale-with-window\", \"yes\")"))
        assertTrue(text.contains("arrayOf(\"sub-add\""))
        assertTrue(text.contains("attachSurface(surface)"))
        assertTrue(text.contains("detachSurface()"))
        assertTrue(text.contains("override fun setTrackSelectionParameters"))
        assertTrue(text.contains("override fun getBufferedPosition()"))
        assertTrue(text.contains("override fun release()"))
    }

    @Test
    fun mpvPlayerAdvertisesMediaSessionMountCommands() {
        val text = source.readText()

        assertTrue(text.contains("COMMAND_SET_MEDIA_ITEM"))
        assertTrue(text.contains("COMMAND_PREPARE"))
        assertTrue(text.contains("COMMAND_SET_PLAYLIST_METADATA"))
    }

    @Test
    fun mpvPlayerAppliesHttpHeadersBeforeLoadingMedia() {
        val text = source.readText()

        assertTrue(text.contains("httpHeaderFieldsProvider"))
        assertTrue(text.contains("setPropertyString(\"http-header-fields\""))
        assertTrue(text.contains("joinToString(\"\\r\\n\")"))
        assertTrue(text.indexOf("applyHttpHeaderFields()") < text.indexOf("arrayOf(\n                    \"loadfile\""))
    }

    @Test
    fun mpvPlayerKeepsTlsVerificationEnabledAndConfiguresPassthroughExplicitly() {
        val text = source.readText()

        assertFalse(text.contains("setOptionString(\"tls-verify\", \"no\")"))
        assertTrue(text.contains("setOptionString(\"tls-verify\", \"yes\")"))
        assertTrue(text.contains("setOptionString(\"msg-level\", \"all=info\")"))
        assertTrue(text.contains("android-ca-bundle.pem"))
        assertTrue(text.contains("TrustManagerFactory.getDefaultAlgorithm()"))
        assertTrue(text.contains("setOptionString(\"tls-ca-file\", caBundle.path)"))
        assertTrue(text.contains("fun setAudioPassthroughCodecs("))
        assertTrue(text.contains("setOption(\"audio-spdif\", value)"))
        assertTrue(text.contains("Blocking MPV speed="))
    }

    @Test
    fun mpvPlayerConvertsInitialLoadFailureToMedia3Error() {
        val text = source.readText()
        val eventBody = text.substringAfter("override fun event(eventId: Int)")
            .substringBefore("private fun setPlayerStateAndNotifyIfChanged")

        assertTrue(text.contains("private var currentPlayerError: PlaybackException?"))
        assertTrue(text.contains("override fun getPlayerError(): PlaybackException? = currentPlayerError"))
        assertTrue(eventBody.contains("MPVLib.MpvEvent.MPV_EVENT_END_FILE"))
        assertTrue(eventBody.contains("failPlaybackOpen()"))
        assertTrue(text.contains("listener.onPlayerError(error)"))
        assertTrue(text.contains("PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
    }

    @Test
    fun mpvPlayerAttachesEveryMedia3VideoSurfacePath() {
        val text = source.readText()

        assertTrue(text.contains("private fun attachVideoSurface(surface: Surface?)"))
        assertTrue(text.contains("private fun detachVideoSurface(surface: Surface?)"))
        assertTrue(text.contains("mpv.attachSurface(surface)"))
        assertTrue(text.contains("mpv.detachSurface()"))
        assertTrue(text.contains("setPropertyString(\"android-surface-size\""))
        assertTrue(text.contains("override fun setVideoSurface(surface: Surface?) {\n        attachVideoSurface(surface)"))
        assertTrue(text.contains("override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?)"))
        assertTrue(text.contains("surfaceHolder?.addCallback(surfaceCallback)"))
        assertTrue(text.contains("setVideoSurfaceHolder(surfaceView?.holder)"))
    }

    @Test
    fun mpvPlayerAttachesTextureViewSurfacesForTvPlayerView() {
        val text = source.readText()
        val setTextureBody = text.substringAfter("override fun setVideoTextureView(textureView: TextureView?)")
            .substringBefore("override fun clearVideoTextureView")
        val clearTextureBody = text.substringAfter("override fun clearVideoTextureView(textureView: TextureView?)")
            .substringBefore("override fun getVideoSize()")

        assertFalse(
            setTextureBody.trim().startsWith("{}"),
            "TV PlayerView uses surface_type=texture_view; MPV must attach that surface path",
        )
        assertFalse(clearTextureBody.trim().startsWith("{}"))
        assertTrue(text.contains("TextureView.SurfaceTextureListener"))
        assertTrue(setTextureBody.contains("attachTextureSurface("))
        assertTrue(clearTextureBody.contains("releaseTextureSurface("))
        assertTrue(text.contains("Surface(surfaceTexture)"))
    }

    @Test
    fun mpvPlayerKeepsVideoOutputAliveAcrossSurfaceTransitions() {
        val text = source.readText()
        val attachBody = text.substringAfter("private fun attachVideoSurface(surface: Surface?)")
            .substringBefore("private fun detachVideoSurface")
        val detachBody = text.substringAfter("private fun detachVideoSurface(surface: Surface?)")
            .substringBefore("private fun updateVideoSurfaceSize")

        assertTrue(attachBody.contains("mpv.attachSurface(surface)"))
        assertTrue(attachBody.contains("setOptionString(\"force-window\", \"yes\")"))
        assertTrue(!attachBody.contains("setOptionString(\"vo\", videoOutput)"))
        assertTrue(detachBody.contains("mpv.detachSurface()"))
        assertTrue(!detachBody.contains("setOptionString(\"vid\", \"no\")"))
        assertTrue(!detachBody.contains("setOptionString(\"vo\", \"null\")"))
        assertTrue(!detachBody.contains("setOptionString(\"force-window\", \"no\")"))
    }

    @Test
    fun mpvPlayerDisablesBuiltInOsdControls() {
        val text = source.readText()

        assertTrue(text.contains("setOptionString(\"osc\", \"no\")"))
        assertTrue(text.contains("setOptionString(\"osd-level\", \"0\")"))
        assertTrue(text.contains("setOptionString(\"osd-bar\", \"no\")"))
    }

    @Test
    fun mpvPlayerReportsStablePositionBeforeTimePosArrives() {
        val text = source.readText()

        assertTrue(text.contains("currentPositionMs = initialSeekTo"))
        assertTrue(text.contains("override fun getCurrentPosition(): Long =\n        (currentPositionMs ?: initialSeekTo).coerceAtLeast(0L)"))
        assertTrue(!text.contains("override fun getCurrentPosition(): Long = currentPositionMs ?: C.TIME_UNSET"))
    }

    @Test
    fun mpvPlayerObservesPlaybackPositionAndDurationAsDoubles() {
        val text = source.readText()
        val longPropertyBody = text.substringAfter("override fun eventProperty(property: String, value: Long)")
            .substringBefore("private fun notifyVideoSizeChangedIfReady")
        val doublePropertyBody = text.substringAfter("override fun eventProperty(property: String, value: Double)")
            .substringBefore("override fun event(eventId: Int)")

        assertTrue(text.contains("Property(\"time-pos\", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)"))
        assertTrue(text.contains("Property(\"duration\", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)"))
        assertTrue(doublePropertyBody.contains("\"time-pos\" ->"))
        assertTrue(doublePropertyBody.contains("currentPositionMs = secondsToMillis(value)"))
        assertTrue(doublePropertyBody.contains("\"duration\" ->"))
        assertTrue(doublePropertyBody.contains("val durationMs = secondsToMillis(value)"))
        assertTrue(doublePropertyBody.contains("currentDurationMs = durationMs"))
        assertTrue(!longPropertyBody.contains("\"time-pos\" ->"))
        assertTrue(!longPropertyBody.contains("\"duration\" ->"))
    }

    @Test
    fun mpvPlayerPublishesVodTimelineWithKnownDefaultPosition() {
        val text = source.readText()
        val windowBody = text.substringAfter("override fun getWindow(")
            .substringBefore("override fun getPeriodCount()")
        val periodBody = text.substringAfter("override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period")
            .substringBefore("override fun getIndexOfPeriod")

        assertTrue(windowBody.contains("val durationUs = Util.msToUs(currentDurationMs ?: C.TIME_UNSET)"))
        assertTrue(windowBody.contains("0L,\n                    durationUs"))
        assertTrue(windowBody.contains("0L,\n                )"))
        assertTrue(periodBody.contains("val durationUs = Util.msToUs(currentDurationMs ?: C.TIME_UNSET)"))
        assertTrue(periodBody.contains("period.set(null, null, periodIndex, durationUs, 0L)"))
    }

    @Test
    fun mpvPlayerPublishesVodTimelineAsNonLiveAndNonDynamic() {
        val text = source.readText()
        val windowBody = text.substringAfter("override fun getWindow(")
            .substringBefore("override fun getPeriodCount()")

        assertTrue(
            windowBody.contains("isSeekable,\n                    false,\n                    null,"),
            "MPV movie streams are VOD; exporting a liveConfiguration makes MediaSession report position=-1",
        )
        assertTrue(
            !windowBody.contains("currentMediaItem.liveConfiguration"),
            "VOD timeline must not inherit MediaItem.liveConfiguration defaults",
        )
        assertTrue(
            !windowBody.contains("!isSeekable,\n                    currentMediaItem.liveConfiguration"),
            "lack of seekability must not make the window dynamic/live-like",
        )
    }

    @Test
    fun mpvPlayerAppliesInitialSeekWithoutDefaultSeekLoop() {
        val text = source.readText()

        assertTrue(text.contains("private fun applyPendingInitialSeek()"))
        assertTrue(text.contains("if (initialSeekTo <= 0L) return"))
        assertTrue(text.contains("performSeek(targetMs)"))
        assertTrue(!text.contains("seekTo(C.TIME_UNSET)"))
    }

    @Test
    fun mpvPlayerPreservesPlayIntentAcrossPrepareReset() {
        val text = source.readText()
        val resetBody = text.substringAfter("private fun resetInternalState()")
            .substringBefore("override fun onAudioFocusChange")

        assertTrue(!resetBody.contains("currentPlayWhenReady = false"))
        assertTrue(text.contains("setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)"))
    }

    @Test
    fun mpvPlayerStopDoesNotDestroyMediaSessionPlayer() {
        val text = source.readText()
        val stopBody = text.substringAfter("override fun stop()")
            .substringBefore("override fun release()")
        val releaseBody = text.substringAfter("override fun release()")
            .substringBefore("override fun getCurrentTracks()")

        assertFalse(
            stopBody.contains("release()") || stopBody.contains("destroy()"),
            "Player.stop() must leave the MediaSession player reusable; only release() may destroy libmpv",
        )
        assertTrue(stopBody.contains("arrayOf(\"stop\")"))
        assertTrue(releaseBody.contains("mpv.destroy()"))
    }

    @Test
    fun mpvPlayerRemoveMediaItemsBacksMediaSessionClear() {
        val text = source.readText()
        val removeBody = text.substringAfter("override fun removeMediaItems(")
            .substringBefore("override fun prepare()")

        assertFalse(
            removeBody.trim().startsWith("fromIndex: Int, toIndex: Int) {}"),
            "MediaSession clearMediaItems() delegates to removeMediaItems(); MPV must not leave it as a no-op",
        )
        assertTrue(removeBody.contains("internalMediaItems.clear()"))
        assertTrue(removeBody.contains("arrayOf(\"playlist-clear\")"))
        assertTrue(removeBody.contains("EVENT_TIMELINE_CHANGED"))
    }
}
