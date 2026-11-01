package org.siloserver.silo.common.player

import android.media.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.AudioPassthroughEntry
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.LAYOUT_AWARE_PASSTHROUGH_FEATURE
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.playbackClientFeaturesV3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UnstableApi
class HdmiPassthroughEntriesTest {
    private val dts = AudioCapabilityManager.EncodingSupport("dts", AudioFormat.ENCODING_DTS, maxChannels = 6)
    private val dtsHd = AudioCapabilityManager.EncodingSupport("dts_hd", AudioFormat.ENCODING_DTS_HD)
    private val truehd = AudioCapabilityManager.EncodingSupport("truehd", AudioFormat.ENCODING_DOLBY_TRUEHD)

    private fun preQEntries(sink: HdmiAudioSinkReport?, sdkInt: Int = 28) = buildPassthroughEntries(
        sdkInt = sdkInt,
        encodings = listOf(dts, dtsHd, truehd),
        hdmiSinkReport = { sink },
        supportsLayout = { _, _ -> error("Pre-Q must never call the direct playback API") },
    )

    private fun features(entries: List<AudioPassthroughEntry>) = playbackClientFeaturesV3(
        ClientPlaybackContext(
            formFactor = "tv",
            appVersion = "test",
            output = PlaybackOutputContext(
                audioPassthrough = AudioPassthroughCapabilities(
                    passthroughCodecs = listOf("dts", "truehd"),
                    entries = entries,
                ),
            ),
        ),
    )

    @Test
    fun preQSixChannelDtsSinkEarnsSideLayoutAndFeature() {
        for (sdkInt in 24..28) {
            val entries = preQEntries(HdmiAudioSinkReport(setOf(dts.encoding), 6), sdkInt)
            assertEquals(
                listOf(AudioPassthroughEntry("dts", listOf(2, 6), listOf("5.1", "5.1(side)", "stereo"))),
                entries,
            )
            assertTrue(LAYOUT_AWARE_PASSTHROUGH_FEATURE in features(entries))
        }
    }

    @Test
    fun preQEightChannelDtsHdSinkCoversDtsMaAndDtsXInTheDtsFamily() {
        val entries = preQEntries(HdmiAudioSinkReport(setOf(dts.encoding, dtsHd.encoding), 8))
        val family = entries.first { it.codec == "dts" }
        // The server labels all four library variants "dts": DTS 5.1,
        // DTS-HD MA 5.1, DTS-HD MA 7.1 and DTS-HD MA + DTS:X 7.1.
        assertEquals(listOf(2, 6, 8), family.channelCounts)
        assertTrue("5.1(side)" in family.layouts)
        assertTrue("7.1" in family.layouts)
        assertEquals(listOf(2, 6, 8), entries.first { it.codec == "dts_hd" }.channelCounts)
    }

    @Test
    fun preQSixChannelDtsHdSinkCannotValidateSevenPointOneDtsMaOrDtsX() {
        val entries = preQEntries(HdmiAudioSinkReport(setOf(dts.encoding, dtsHd.encoding), 6))
        assertEquals(setOf("dts", "dts_hd"), entries.map { it.codec }.toSet())
        entries.forEach {
            assertEquals(listOf(2, 6), it.channelCounts)
            assertTrue("5.1(side)" in it.layouts)
            assertFalse(8 in it.channelCounts)
            assertFalse("7.1" in it.layouts)
        }
    }

    @Test
    fun preQCoreOnlyDtsReportDoesNotAdvertiseDtsHdOrEightChannels() {
        val entries = preQEntries(HdmiAudioSinkReport(setOf(dts.encoding), 8))
        assertEquals(listOf("dts"), entries.map { it.codec })
        assertEquals(listOf(2, 6), entries.single().channelCounts)
        assertFalse("7.1" in entries.single().layouts)
    }

    @Test
    fun preQDtsHdSinkCannotBypassTheExistingEncodingFilters() {
        val entries = buildPassthroughEntries(
            sdkInt = 28,
            encodings = listOf(dts),
            hdmiSinkReport = { HdmiAudioSinkReport(setOf(dts.encoding, dtsHd.encoding), 8) },
            supportsLayout = { _, _ -> error("Pre-Q must never call the direct playback API") },
        )
        assertEquals(listOf(2, 6), entries.single().channelCounts)
    }

    @Test
    fun preQCodecsFromDefaultsOrSettingsAloneCannotEarnEntriesOrFeature() {
        val entries = preQEntries(null)
        assertTrue(entries.isEmpty())
        assertFalse(LAYOUT_AWARE_PASSTHROUGH_FEATURE in features(entries))
    }

    @Test
    fun preQStereoSinkDoesNotClaimMultichannelLayouts() {
        val entries = preQEntries(HdmiAudioSinkReport(setOf(dts.encoding, truehd.encoding), 2))
        assertEquals(listOf("dts", "truehd"), entries.map { it.codec })
        entries.forEach {
            assertEquals(listOf(2), it.channelCounts)
            assertEquals(listOf("stereo"), it.layouts)
        }
    }

    @Test
    fun preQNeverRoundsTheSinkCeilingUpToAProbedLayout() {
        for (ceiling in listOf(-1, 0, 1, 5, 7)) {
            val entries = preQEntries(HdmiAudioSinkReport(setOf(truehd.encoding), ceiling))
            assertTrue(entries.flatMap { it.channelCounts }.all { it <= ceiling })
            assertFalse(entries.flatMap { it.layouts }.contains("7.1"))
        }
    }

    @Test
    fun preQCodecCeilingStillBoundsAWiderSink() {
        val entries = preQEntries(HdmiAudioSinkReport(setOf(dts.encoding, truehd.encoding), 8))
        assertEquals(listOf(2, 6), entries.first { it.codec == "dts" }.channelCounts)
        assertEquals(listOf(2, 6, 8), entries.first { it.codec == "truehd" }.channelCounts)
        assertTrue("7.1" in entries.first { it.codec == "truehd" }.layouts)
    }

    @Test
    fun qAndLaterUseOnlyPerEncodingDirectPlaybackResults() {
        for (sdkInt in listOf(29, 32, 33, 36)) {
            val probes = mutableListOf<Pair<String, Int>>()
            val entries = buildPassthroughEntries(
                sdkInt = sdkInt,
                encodings = listOf(dts, dtsHd, truehd),
                hdmiSinkReport = { error("Q+ must not consult the pre-Q HDMI fallback") },
                supportsLayout = { support, candidate ->
                    probes += support.codec to candidate.channelCount
                    support.codec in setOf("dts_hd", "truehd") && candidate.channelCount == 8
                },
            )
            val expectedProbes = listOf("dts", "dts_hd", "truehd")
                .flatMap { codec -> listOf(2, 6, 8).map { codec to it } }
            assertEquals(expectedProbes.size, probes.size)
            assertEquals(expectedProbes.toSet(), probes.toSet())
            assertEquals(
                listOf("dts_hd", "truehd").map { AudioPassthroughEntry(it, listOf(8), listOf("7.1")) },
                entries,
            )
        }
    }
}
