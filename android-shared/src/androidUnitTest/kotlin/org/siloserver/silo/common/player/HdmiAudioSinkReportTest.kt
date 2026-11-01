package org.siloserver.silo.common.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowBuild
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.LAYOUT_AWARE_PASSTHROUGH_FEATURE
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.playbackClientFeaturesV3
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
class HdmiAudioSinkReportTest {
    private fun pluggedIntent(
        channels: Int = 6,
        vararg encodings: Int = intArrayOf(AudioFormat.ENCODING_AC3),
    ) = Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
        .putExtra(AudioManager.EXTRA_ENCODINGS, encodings)
        .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, channels)

    @Test
    fun reportRequiresPluggedHdmiWithExplicitEncodingsAndChannelCeiling() {
        assertEquals(HdmiAudioSinkReport(setOf(AudioFormat.ENCODING_AC3), 6), hdmiAudioSinkReport(pluggedIntent()))
        assertNull(hdmiAudioSinkReport(null))
        assertNull(hdmiAudioSinkReport(Intent("other.action")))
        assertNull(hdmiAudioSinkReport(pluggedIntent().putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0)))
        assertNull(hdmiAudioSinkReport(pluggedIntent().apply { removeExtra(AudioManager.EXTRA_ENCODINGS) }))
        assertNull(hdmiAudioSinkReport(pluggedIntent().putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf())))
        assertNull(hdmiAudioSinkReport(pluggedIntent().apply { removeExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT) }))
        assertNull(hdmiAudioSinkReport(pluggedIntent(0)))
        assertNull(hdmiAudioSinkReport(pluggedIntent(-1)))
    }

    @Test
    fun sinkEncodingsKeepTheExistingSdkFilters() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.sendStickyBroadcast(
            pluggedIntent().putExtra(
                AudioManager.EXTRA_ENCODINGS,
                intArrayOf(
                    AudioFormat.ENCODING_DTS,
                    AudioFormat.ENCODING_DTS_HD,
                    AudioFormat.ENCODING_DOLBY_TRUEHD,
                    AudioFormat.ENCODING_E_AC3_JOC,
                    AudioFormat.ENCODING_AC4,
                ),
            ),
        )
        val entries = AudioCapabilityManager(context).capabilities.value.entries
        assertEquals(listOf(2, 6), entries.first { it.codec == "dts" }.channelCounts)
        assertTrue("5.1(side)" in entries.first { it.codec == "dts" }.layouts)
        assertTrue(entries.any { it.codec == "dts_hd" })
        assertEquals(Build.VERSION.SDK_INT >= 25, entries.any { it.codec == "truehd" })
        assertEquals(Build.VERSION.SDK_INT >= 28, entries.any { it.codec == "eac3_joc" })
        assertEquals(Build.VERSION.SDK_INT >= 28, entries.any { it.codec == "ac4" })
    }

    @Test
    fun forcedSurroundWithoutSinkDoesNotEarnEntries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forceSurround(context)
        val manager = AudioCapabilityManager(context)
        assertTrue("ac3" in manager.capabilities.value.passthroughCodecs)
        assertTrue(manager.capabilities.value.entries.isEmpty())
    }

    @Test
    fun hdmiChangesRefreshRawCeilingEvenWhenMedia3ForcedCapabilitiesAreUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forceSurround(context)
        context.sendStickyBroadcast(pluggedIntent())
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        val media3Before = AudioCapabilities.getCapabilities(context)
        assertEntriesAndFeature(manager, expected = false)
        assertEquals(6, manager.capabilities.value.maxChannels)
        val generationBefore = manager.outputRouteGeneration.value

        context.sendStickyBroadcast(pluggedIntent(2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(media3Before, AudioCapabilities.getCapabilities(context))
        assertEntriesAndFeature(manager, expected = false)
        assertEquals(2, manager.capabilities.value.maxChannels)
        assertTrue(manager.outputRouteGeneration.value > generationBefore)

        context.sendStickyBroadcast(pluggedIntent().putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(media3Before, AudioCapabilities.getCapabilities(context))
        assertTrue(manager.capabilities.value.entries.isEmpty())
    }

    @Test
    fun usbOutputsSuppressHdmiEntriesAndRemovalRestoresThem() {
        val types = mutableListOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        // Android 7 has no USB_HEADSET mapping in AudioDeviceInfo.getType().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) types += AudioDeviceInfo.TYPE_USB_HEADSET
        for (type in types) competingOutputSuppressesEntriesUntilRemoved(type)
    }

    @Test
    fun wiredHeadsetSuppressesHdmiEntriesAndRemovalRestoresThem() {
        competingOutputSuppressesEntriesUntilRemoved(AudioDeviceInfo.TYPE_WIRED_HEADSET)
    }

    @Test
    fun headphonesLineAndBluetoothOutputsAlsoSuppressHdmiEntries() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )) {
            competingOutputSuppressesEntriesUntilRemoved(type)
        }
    }

    private fun competingOutputSuppressesEntriesUntilRemoved(type: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.sendStickyBroadcast(pluggedIntent())
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowAudio = shadowOf(audioManager)
        val hdmi = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI).build()
        val competing = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        shadowAudio.setOutputDevices(listOf(hdmi, competing))
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        assertEntriesAndFeature(manager, expected = false)
        val generation = manager.outputRouteGeneration.value

        // No HDMI broadcast: the manager's pre-Q device callback must refresh.
        shadowAudio.removeOutputDevice(competing, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEntriesAndFeature(manager, expected = true)
        assertTrue(manager.outputRouteGeneration.value > generation)

        shadowAudio.addOutputDevice(competing, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEntriesAndFeature(manager, expected = false)
    }

    @Test
    fun absentSurroundPolicyAllowsExplicitHdmiEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(Settings.Global.getString(context.contentResolver, "encoded_surround_output"))
        context.sendStickyBroadcast(pluggedIntent())
        assertEntriesAndFeature(AudioCapabilityManager(context), expected = true)
    }

    @Test
    fun unknownOrMalformedSurroundPolicyFailsClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.sendStickyBroadcast(pluggedIntent())
        for (value in listOf("4", "invalid")) {
            Settings.Global.putString(context.contentResolver, "encoded_surround_output", value)
            assertEntriesAndFeature(AudioCapabilityManager(context), expected = false)
        }
    }

    @Test
    fun autoSurroundPolicyAllowsExplicitHdmiEvidence() = assertSurroundPolicy(0, expected = true)

    @Test
    fun neverSurroundPolicySuppressesExplicitHdmiEvidence() = assertSurroundPolicy(1, expected = false)

    @Test
    fun alwaysSurroundPolicyCannotTurnForcedHdmiFormatsIntoEvidence() = assertSurroundPolicy(2, expected = false)

    @Test
    fun manualSurroundPolicyCannotTurnForcedHdmiFormatsIntoEvidence() = assertSurroundPolicy(3, expected = false)

    private fun assertSurroundPolicy(value: Int, expected: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric keeps int/string values separate; production Settings
        // stores strings, which the evidence check reads to reject malformed data.
        Settings.Global.putString(context.contentResolver, "encoded_surround_output", value.toString())
        context.sendStickyBroadcast(pluggedIntent())
        assertEntriesAndFeature(AudioCapabilityManager(context), expected)
    }

    @Test
    fun policyChangesRefreshEntriesWithoutAnHdmiOrMedia3CapabilityChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.sendStickyBroadcast(pluggedIntent())
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        assertEntriesAndFeature(manager, expected = true)
        val before = AudioCapabilities.getCapabilities(context)
        Settings.Global.putString(context.contentResolver, "encoded_surround_output", "2")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, AudioCapabilities.getCapabilities(context))
        assertEntriesAndFeature(manager, expected = false)
        Settings.Global.putString(context.contentResolver, "encoded_surround_output", "0")
        shadowOf(Looper.getMainLooper()).idle()
        assertEntriesAndFeature(manager, expected = true)
    }

    @Test
    fun amazonAndXiaomiExplicitStereoSuppressesHdmiEvidence() {
        assertExternalSurroundPolicy(0, expected = false)
    }

    @Test
    fun amazonAndXiaomiForcedSurroundCannotEarnEntriesEvenWithHdmi() {
        assertExternalSurroundPolicy(1, expected = false)
    }

    @Test
    fun amazonAndXiaomiAbsentExternalSettingAllowsExplicitHdmiEvidence() {
        assertExternalSurroundPolicy(null, expected = true)
    }

    private fun assertExternalSurroundPolicy(value: Int?, expected: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putString(context.contentResolver, "external_surround_sound_enabled", value?.toString())
        context.sendStickyBroadcast(pluggedIntent())
        for (manufacturer in listOf("Amazon", "Xiaomi")) {
            ShadowBuild.setManufacturer(manufacturer)
            assertEntriesAndFeature(AudioCapabilityManager(context), expected)
        }
    }

    @Test
    fun trueHdCanUseEightChannelReportButEac3Cannot() {
        val caps = capabilitiesForEightChannelSink(AudioFormat.ENCODING_DOLBY_TRUEHD, AudioFormat.ENCODING_E_AC3)
        if (Build.VERSION.SDK_INT >= 25) {
            val truehd = caps.entries.first { it.codec == "truehd" }
            assertEquals(listOf(2, 6, 8), truehd.channelCounts)
            assertTrue("7.1" in truehd.layouts)
        } else {
            assertFalse(caps.entries.any { it.codec == "truehd" })
        }
        val eac3 = caps.entries.first { it.codec == "eac3" }
        assertEquals(listOf(2, 6), eac3.channelCounts)
        assertFalse("7.1" in eac3.layouts)
    }

    @Test
    fun pcmCeilingStaysEightWhileProductionAc3AndDtsEntriesStayAtSix() {
        val caps = capabilitiesForEightChannelSink(
            AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_DTS,
        )
        assertEquals(8, caps.maxChannels)
        assertEquals(setOf("ac3", "dts"), caps.entries.map { it.codec }.toSet())
        caps.entries.forEach {
            assertEquals(listOf(2, 6), it.channelCounts)
            assertFalse("7.1" in it.layouts)
        }
    }

    @Test
    fun otherLossyCodecsCannotUseEightChannelReport() {
        val caps = capabilitiesForEightChannelSink(AudioFormat.ENCODING_E_AC3_JOC, AudioFormat.ENCODING_AC4)
        assertEquals(
            if (Build.VERSION.SDK_INT >= 28) setOf("eac3_joc", "ac4") else emptySet(),
            caps.entries.map { it.codec }.toSet(),
        )
        caps.entries.forEach {
            assertEquals(listOf(2, 6), it.channelCounts)
            assertFalse("7.1" in it.layouts)
        }
    }

    @Test
    fun dtsHdOnlyReportEarnsDtsFamilyCodecAndEntry() {
        val caps = capabilitiesForEightChannelSink(AudioFormat.ENCODING_DTS_HD)
        assertEquals(setOf("dts", "dts_hd"), caps.passthroughCodecs.toSet())
        assertEquals(setOf("dts", "dts_hd"), caps.entries.map { it.codec }.toSet())
        caps.entries.forEach {
            assertEquals(listOf(2, 6, 8), it.channelCounts)
            assertTrue("7.1" in it.layouts)
        }
    }

    @Test
    fun missingSinkUsesMedia3AggregateCeilingForDecodedAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forceSurround(context)
        val caps = AudioCapabilities.getCapabilities(context)
        val actual = AudioCapabilityManager(context).capabilities.value
        assertEquals(caps.maxChannelCount.coerceAtLeast(2), actual.maxChannels)
        assertTrue(actual.entries.isEmpty())
    }

    private fun capabilitiesForEightChannelSink(vararg encodings: Int) =
        ApplicationProvider.getApplicationContext<Context>().let { context ->
            context.sendStickyBroadcast(pluggedIntent(8, *encodings))
            AudioCapabilityManager(context).capabilities.value
        }

    private fun assertEntriesAndFeature(manager: AudioCapabilityManager, expected: Boolean) {
        val caps = manager.capabilities.value
        assertEquals(expected, caps.entries.isNotEmpty())
        val features = playbackClientFeaturesV3(
            ClientPlaybackContext(
                formFactor = "tv",
                appVersion = "test",
                output = PlaybackOutputContext(audioPassthrough = caps),
            ),
        )
        assertEquals(expected, LAYOUT_AWARE_PASSTHROUGH_FEATURE in features)
    }

    private fun forceSurround(context: Context) {
        Settings.Global.putInt(context.contentResolver, "use_external_surround_sound_flag", 1)
        Settings.Global.putInt(context.contentResolver, "external_surround_sound_enabled", 1)
    }
}
