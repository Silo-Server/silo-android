package org.siloserver.silo.common.player

import android.content.Context
import android.hardware.display.HdrConversionMode
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.Shadows.shadowOf
import org.siloserver.silo.common.network.SiloClientBuildIdentity
import org.siloserver.silo.libass.LibassBridge
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.siloserver.silo.model.playback.HdrCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ForceHdrPassthroughTest {
    private lateinit var context: Context
    private lateinit var detector: PlaybackCapabilityDetector

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setDisplayHdrCapabilities(display.displayId, 1000f, 500f, 0f,
            Display.HdrCapabilities.HDR_TYPE_HDR10)
        ShadowMediaCodecList.reset()
        addDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10HDR10,
            CodecProfileLevel.HEVCMainTierLevel51)
        addDecoder(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, CodecProfileLevel.DolbyVisionProfileDvheStn,
            CodecProfileLevel.DolbyVisionLevelUhd60)
        MediaCodecCapabilitiesProbe.resetCacheForTest()
        detector = PlaybackCapabilityDetector(context, AudioCapabilityManager(context), LibassBridge(false),
            buildIdentity = SiloClientBuildIdentity(buildNumber = "test", channel = "test"))
    }

    @After
    fun tearDown() {
        ShadowMediaCodecList.reset()
        MediaCodecCapabilitiesProbe.resetCacheForTest()
    }

    @Test
    fun overridePreservesDecoderBoundsAndDefaultStillUsesDisplay() {
        val ordinary = detector.detect(ffmpegAvailable = false)
        assertFalse(ordinary.hdrDetails!!.hlg)
        assertTrue(ordinary.hdrDetails!!.dolbyVisionProfiles.isEmpty())
        val forced = detector.detect(ffmpegAvailable = false, forceHdrPassthrough = true)
        assertTrue(forced.hdrDetails!!.hlg)
        assertFalse(forced.hdrDetails!!.hdr10Plus)
        assertEquals(listOf(5), forced.hdrDetails!!.dolbyVisionProfiles)
        assertEquals(MediaCodecCapabilitiesProbe.probe().hdr.dolbyVisionProfileLevels,
            forced.hdrDetails!!.dolbyVisionProfileLevels)
        assertFalse(detector.detect(ffmpegAvailable = false).hdrDetails!!.hlg)
    }

    @Test
    fun preflightAcceptsTheDolbyVisionRouteNegotiatedWithTheOverride() {
        val caps = detector.detect(ffmpegAvailable = false, forceHdrPassthrough = true)
        assertTrue(5 in caps.hdrDetails!!.dolbyVisionProfiles)
        assertEquals(Playability.Supported,
            detector.evaluateTracks(dolbyVisionTracks(), PlannedVideoRoute.NativeDolbyVision, forceHdrPassthrough = true))
    }

    @Test
    fun listenerUsesTheCurrentOverrideAndStillRejectsUnadvertisedProfiles() {
        var forced = true
        val failures = mutableListOf<Playability>()
        val listener = PlaybackPreflightListener(
            detector, failures::add,
            plannedRoute = { PlannedVideoRoute.NativeDolbyVision },
            forceHdrPassthrough = { forced },
        )
        listener.onTracksChanged(dolbyVisionTracks())
        assertTrue(failures.isEmpty())
        forced = false
        listener.onTracksChanged(dolbyVisionTracks())
        assertEquals(listOf<Playability>(Playability.UnsupportedDvProfile(5)), failures)
        forced = true
        listener.onTracksChanged(dolbyVisionTracks(profile = 7))
        assertEquals(Playability.UnsupportedDvProfile(7), failures.last())
    }

    @Test
    fun profile8HlgBaseLayerUsesTheOverrideInPreflight() {
        val tracks = dolbyVisionTracks(profile = 8)
        val route = PlannedVideoRoute.DolbyVisionProfile8BaseLayer("hlg")
        assertEquals(Playability.DvBaseLayerOutputMismatch(8, "hlg"), detector.evaluateTracks(tracks, route))
        assertEquals(Playability.Supported, detector.evaluateTracks(tracks, route, forceHdrPassthrough = true))
    }

    @Test
    fun forcedDisplayUsesDolbyVisionTrackPreference() {
        val ordinary = DisplayHdrProbe.probeDetailed(context).hdr
        val forced = DisplayHdrProbe.probeDetailed(context, forcePassthrough = true).hdr
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC,
            TrackSelectionPresets.buildTvVideoMimePreferences(ordinary).first())
        assertEquals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
            TrackSelectionPresets.buildTvVideoMimePreferences(forced).first())
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC,
            TrackSelectionPresets.buildTvVideoMimePreferences(forced, allowHdr = false).first())
    }

    @Test
    @Config(sdk = [34], shadows = [ForcedSdrDisplayManager::class])
    fun systemForcedSdrWinsOverTheOverride() {
        val display = DisplayHdrProbe.probeDetailed(context, forcePassthrough = true)
        assertTrue(display.isExact)
        assertEquals(HdrCapabilities(), display.hdr)
        assertEquals(HdrCapabilities(), detector.detect(ffmpegAvailable = false, forceHdrPassthrough = true).hdrDetails)
        assertEquals(Playability.UnsupportedDvProfile(5), detector.evaluateTracks(
            dolbyVisionTracks(), PlannedVideoRoute.NativeDolbyVision, forceHdrPassthrough = true))
    }

    @Implements(DisplayManager::class)
    class ForcedSdrDisplayManager : ShadowDisplayManager() {
        @Implementation(minSdk = 34)
        fun getHdrConversionMode(): HdrConversionMode = HdrConversionMode(
            HdrConversionMode.HDR_CONVERSION_FORCE, Display.HdrCapabilities.HDR_TYPE_INVALID,
        )
    }

    private fun dolbyVisionTracks(profile: Int = 5) = Tracks(listOf(Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.${profile.toString().padStart(2, '0')}.06").build()),
        false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
    )))

    private fun addDecoder(mime: String, profile: Int, level: Int) {
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
            .setName("c2.vendor.$mime.decoder")
            .setIsHardwareAccelerated(true).setIsSoftwareOnly(false)
            .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(mime, 3840, 2160))
                .setColorFormats(intArrayOf(CodecCapabilities.COLOR_FormatYUV420Flexible))
                .setProfileLevels(arrayOf(CodecProfileLevel().apply {
                    this.profile = profile
                    this.level = level
                })).build()).build())
    }
}
