package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.CLIENT_DV7_TO_DV81
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DolbyVisionTransformQuarantineTest {

    private class MemoryStore : DolbyVisionTransformQuarantine.Store {
        val values = mutableMapOf<String, Long>()
        override fun get(key: String): Long? = values[key]
        override fun put(key: String, value: Long) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    @Test
    fun deviceFaultWithdrawsTheActiveTransformation() {
        val store = MemoryStore()
        val quarantine = DolbyVisionTransformQuarantine(store, fingerprint = "build-a", nowMs = { 1_000L })

        val withdrawn = quarantine.noteFailure("dv7_transform_stall", listOf(CLIENT_DV7_TO_DV81))

        assertEquals(listOf(CLIENT_DV7_TO_DV81), withdrawn)
        assertTrue(quarantine.isQuarantined(CLIENT_DV7_TO_DV81))
        assertEquals(setOf(CLIENT_DV7_TO_DV81), quarantine.quarantined())
        assertFalse(quarantine.isQuarantined(CLIENT_DV7_TO_HDR10))
    }

    @Test
    fun sourceAndTransportFaultsDoNotBlameTheDevice() {
        val quarantine = DolbyVisionTransformQuarantine(MemoryStore(), fingerprint = "build-a", nowMs = { 1_000L })

        listOf(
            "dv7_transform_source_mismatch",
            "dv7_transform_encrypted",
            "dv7_transform_malformed",
            "dv7_transform_supplemental_unsupported",
            "transport_stall",
            "http_failure",
            "audio_track_changed",
        ).forEach { classification ->
            assertTrue(
                quarantine.noteFailure(classification, listOf(CLIENT_DV7_TO_DV81)).isEmpty(),
                "$classification must not quarantine the recipe",
            )
        }
        assertFalse(quarantine.isQuarantined(CLIENT_DV7_TO_DV81))
    }

    @Test
    fun genericDecoderFailureCountsOnlyWhenTheVideoRendererRaisedIt() {
        val store = MemoryStore()
        val quarantine = DolbyVisionTransformQuarantine(store, fingerprint = "build-a", nowMs = { 1_000L })

        assertTrue(
            quarantine.noteFailure("decoder_failure", listOf(CLIENT_DV7_TO_DV81), failedTrackType = 1).isEmpty(),
            "an audio renderer failing under a transformed plan says nothing about the video recipe",
        )
        assertTrue(
            quarantine.noteFailure("decoder_failure", listOf(CLIENT_DV7_TO_DV81), failedTrackType = null).isEmpty(),
            "a decoder failure with no renderer attribution must not quarantine",
        )
        assertFalse(quarantine.isQuarantined(CLIENT_DV7_TO_DV81))

        assertEquals(
            listOf(CLIENT_DV7_TO_DV81),
            quarantine.noteFailure(
                "decoder_failure",
                listOf(CLIENT_DV7_TO_DV81),
                failedTrackType = DolbyVisionTransformQuarantine.TRACK_TYPE_VIDEO,
            ),
        )
        assertTrue(quarantine.isQuarantined(CLIENT_DV7_TO_DV81))
    }

    @Test
    fun videoStallClassificationsNeedNoRendererAttribution() {
        val quarantine = DolbyVisionTransformQuarantine(MemoryStore(), fingerprint = "build-a", nowMs = { 1_000L })

        assertEquals(
            listOf(CLIENT_DV7_TO_DV81),
            quarantine.noteFailure("decoder_no_output", listOf(CLIENT_DV7_TO_DV81)),
            "the stall detector watches the video decoder counters, so its verdict is already video-scoped",
        )
    }

    @Test
    fun aFailureWithoutAnActiveTransformationIsIgnored() {
        val quarantine = DolbyVisionTransformQuarantine(MemoryStore(), fingerprint = "build-a", nowMs = { 1_000L })

        assertTrue(quarantine.noteFailure("decoder_no_output", emptyList()).isEmpty())
        assertFalse(quarantine.isQuarantined(CLIENT_DV7_TO_DV81))
    }

    @Test
    fun quarantineIsScopedToTheBuildFingerprintAndExpires() {
        val store = MemoryStore()
        var now = 1_000L
        val onBuildA = DolbyVisionTransformQuarantine(store, fingerprint = "build-a", nowMs = { now })
        val onBuildB = DolbyVisionTransformQuarantine(store, fingerprint = "build-b", nowMs = { now })

        onBuildA.noteFailure("decoder_no_output", listOf(CLIENT_DV7_TO_DV81))

        assertTrue(onBuildA.isQuarantined(CLIENT_DV7_TO_DV81))
        assertFalse(onBuildB.isQuarantined(CLIENT_DV7_TO_DV81), "an OS update earns a fresh attempt")

        now += DolbyVisionTransformQuarantine.TTL_MS + 1
        assertFalse(onBuildA.isQuarantined(CLIENT_DV7_TO_DV81), "the quarantine expires")
        assertTrue(store.values.isEmpty(), "an expired entry is dropped from the store")
    }
}
