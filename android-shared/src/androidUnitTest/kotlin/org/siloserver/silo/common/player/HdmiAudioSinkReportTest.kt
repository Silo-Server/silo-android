package org.siloserver.silo.common.player

import android.content.Context
import android.content.Intent
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
class HdmiAudioSinkReportTest {
    private fun pluggedIntent(channels: Int = 6) = Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
        .putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf(AudioFormat.ENCODING_AC3))
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
    fun hdmiChangesRefreshEntriesEvenWhenMedia3ForcedCapabilitiesAreUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forceSurround(context)
        context.sendStickyBroadcast(pluggedIntent())
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        val media3Before = AudioCapabilities.getCapabilities(context)
        assertEquals(listOf(2, 6), manager.capabilities.value.entries.single().channelCounts)
        assertEquals(6, manager.capabilities.value.maxChannels)
        val generationBefore = manager.outputRouteGeneration.value

        context.sendStickyBroadcast(pluggedIntent(2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(media3Before, AudioCapabilities.getCapabilities(context))
        assertEquals(listOf(2), manager.capabilities.value.entries.single().channelCounts)
        assertEquals(2, manager.capabilities.value.maxChannels)
        assertTrue(manager.outputRouteGeneration.value > generationBefore)

        context.sendStickyBroadcast(pluggedIntent().putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(media3Before, AudioCapabilities.getCapabilities(context))
        assertTrue(manager.capabilities.value.entries.isEmpty())
    }

    private fun forceSurround(context: Context) {
        Settings.Global.putInt(context.contentResolver, "use_external_surround_sound_flag", 1)
        Settings.Global.putInt(context.contentResolver, "external_surround_sound_enabled", 1)
    }
}
