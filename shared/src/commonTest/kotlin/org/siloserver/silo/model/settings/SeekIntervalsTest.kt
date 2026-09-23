package org.siloserver.silo.model.settings

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekIntervalsTest {

    private val supportedCaps = SettingsContractCapabilities(
        apiVersion = 1,
        manifestRevision = 9,
        supportsBatchedEffective = true,
    )

    @Test
    fun decodeAcceptsOnlyAllowedWholeNumbers() {
        for (seconds in SeekIntervals.CHOICES) {
            assertEquals(seconds, SeekIntervals.decodeOrNull(JsonPrimitive(seconds)))
        }
        assertEquals(45, SeekIntervals.decodeOrNull(JsonPrimitive(45.0)))
        assertNull(SeekIntervals.decodeOrNull(JsonPrimitive(20)))
        assertNull(SeekIntervals.decodeOrNull(JsonPrimitive(0)))
        assertNull(SeekIntervals.decodeOrNull(JsonPrimitive(-10)))
        assertNull(SeekIntervals.decodeOrNull(JsonPrimitive(10.5)))
        assertNull(SeekIntervals.decodeOrNull(JsonPrimitive("10")))
        assertNull(SeekIntervals.decodeOrNull(JsonPrimitive(true)))
        assertNull(SeekIntervals.decodeOrNull(JsonNull))
        assertNull(SeekIntervals.decodeOrNull(null))
    }

    @Test
    fun invalidValuesFallBackToTheContractDefaultNotANeighbour() {
        assertEquals(10, SeekIntervals.decode(JsonPrimitive(20), SeekDirection.Back))
        assertEquals(30, SeekIntervals.decode(JsonPrimitive(20), SeekDirection.Forward))
        assertEquals(10, SeekIntervals.decode(null, SeekDirection.Back))
        assertEquals(30, SeekIntervals.decode(JsonPrimitive("abc"), SeekDirection.Forward))
    }

    @Test
    fun resolveReadsEachMediaTypeIndependently() {
        val effective = mapOf(
            SettingKeys.PLAYER_VIDEO_SKIP_BACK_SECONDS to value(SettingKeys.PLAYER_VIDEO_SKIP_BACK_SECONDS, 5),
            SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS to value(SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS, 90),
            SettingKeys.PLAYER_AUDIOBOOK_SKIP_BACK_SECONDS to value(SettingKeys.PLAYER_AUDIOBOOK_SKIP_BACK_SECONDS, 15),
        )
        assertEquals(SeekIntervalPair(5, 90), SeekIntervals.resolve(effective, SeekMedia.Video))
        // The absent audiobook forward key resolves to the contract default.
        assertEquals(SeekIntervalPair(15, 30), SeekIntervals.resolve(effective, SeekMedia.Audiobook))
        assertEquals(SeekIntervals.DEFAULTS, SeekIntervals.resolve(emptyMap(), SeekMedia.Video))
    }

    @Test
    fun supportNeedsProtocolRevisionAndBatchedReads() {
        assertTrue(SeekIntervals.isSupported(supportedCaps))
        assertTrue(SeekIntervals.isSupported(supportedCaps.copy(manifestRevision = 12)))
        assertFalse(SeekIntervals.isSupported(supportedCaps.copy(manifestRevision = 8)))
        assertFalse(SeekIntervals.isSupported(supportedCaps.copy(apiVersion = 2)))
        assertFalse(SeekIntervals.isSupported(supportedCaps.copy(supportsBatchedEffective = false)))
    }

    @Test
    fun stateUsesTheLegacyPairUntilSupportIsConfirmed() {
        val legacy = SeekIntervalPair(10, 10)
        val server = SeekIntervalPair(45, 60)
        for (support in listOf(
            SeekIntervalSupport.Unknown,
            SeekIntervalSupport.Unsupported,
            SeekIntervalSupport.Unavailable,
        )) {
            val state = SeekIntervalState(support, videoIntervals = server, audiobookIntervals = server)
            assertEquals(legacy, state.video(legacy))
            assertEquals(legacy, state.audiobook(legacy))
        }
        val supported = SeekIntervalState(SeekIntervalSupport.Supported, server, SeekIntervalPair(5, 15))
        assertEquals(server, supported.video(legacy))
        assertEquals(SeekIntervalPair(5, 15), supported.audiobook(legacy))
    }

    @Test
    fun legacyAudiobookEditingOnceTheServerIsKnownOlderOrTheCheckFailed() {
        assertTrue(SeekIntervalState(SeekIntervalSupport.Unsupported).allowsLegacyAudiobookEditing)
        assertTrue(SeekIntervalState(SeekIntervalSupport.Unavailable).allowsLegacyAudiobookEditing)
        assertFalse(SeekIntervalState(SeekIntervalSupport.Unknown).allowsLegacyAudiobookEditing)
        assertFalse(SeekIntervalState(SeekIntervalSupport.Supported).allowsLegacyAudiobookEditing)
    }

    @Test
    fun onlyExplicitAllowedLegacyValuesAreImportable() {
        assertEquals(emptyMap(), LegacyAudiobookIntervals().importable)
        assertEquals(
            mapOf(SeekDirection.Forward to 60),
            LegacyAudiobookIntervals(backSeconds = null, forwardSeconds = 60).importable,
        )
        assertEquals(
            mapOf(SeekDirection.Back to 15),
            LegacyAudiobookIntervals(backSeconds = 15, forwardSeconds = 20).importable,
        )
    }

    @Test
    fun describeReportsEachDirectionSeparately() {
        val result = SeekImportResult(
            back = SeekImportOutcome.Imported(15),
            forward = SeekImportOutcome.Failed(60, "Server unavailable"),
        )
        assertEquals(
            "Skip back imported (15s). Skip forward (60s) failed: Server unavailable.",
            result.describe(),
        )
        assertTrue(result.anyFailed)
        assertFalse(result.allImported)

        val partial = SeekImportResult(SeekImportOutcome.NotStored, SeekImportOutcome.Imported(60))
        assertEquals("Skip back had no device value. Skip forward imported (60s).", partial.describe())
        assertTrue(partial.allImported)
    }

    private fun value(key: String, seconds: Int) =
        EffectiveSettingValue(key = key, value = JsonPrimitive(seconds), source = "profile")
}
