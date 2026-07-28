package org.siloserver.silo.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The preset table is a client-side pairing of two contract settings, so the
 * invariants worth pinning are the ones a silent edit could break: every
 * resolution must be a member the server's enum accepts, every bitrate must
 * sit inside the contract's range, and the table must round-trip — a preset
 * written and read back has to select itself, or the picker shows the wrong
 * entry for a value the user just chose.
 *
 * Mirrors web/src/lib/qualityPresets.test.ts.
 */
class QualityPresetsTest {

    // playback.preferred_quality's enum, and playback.max_bitrate_kbps's bounds.
    private val contractResolutions = setOf("auto", "480p", "720p", "1080p", "2160p", "original")
    private val minBitrate = 100
    private val maxBitrate = 200_000

    @Test
    fun `every preset uses contract-legal values`() {
        for (preset in QualityPresets.ALL) {
            assertTrue(
                preset.resolution in contractResolutions,
                "${preset.id}: ${preset.resolution} is not a playback.preferred_quality member",
            )
            preset.bitrateKbps?.let {
                assertTrue(
                    it in minBitrate..maxBitrate,
                    "${preset.id}: $it is outside playback.max_bitrate_kbps bounds",
                )
            }
        }
    }

    @Test
    fun `preset ids and axis pairs are unique`() {
        assertEquals(
            QualityPresets.ALL.size,
            QualityPresets.ALL.map { it.id }.toSet().size,
            "duplicate preset id",
        )
        assertEquals(
            QualityPresets.ALL.size,
            QualityPresets.ALL.map { it.resolution to it.bitrateKbps }.toSet().size,
            "two presets store the same pair, so one can never be selected",
        )
    }

    @Test
    fun `every preset round-trips through its stored pair`() {
        for (preset in QualityPresets.ALL) {
            assertEquals(
                preset.id,
                QualityPresets.presetFor(preset.resolution, preset.bitrateKbps)?.id,
                "${preset.id} does not select itself",
            )
        }
    }

    @Test
    fun `the table matches the web client's semantics`() {
        // Named explicitly rather than derived, so a retune on one platform
        // that is not mirrored on the other shows up here.
        assertEquals(
            listOf(
                "auto" to null,
                "original" to null,
                "2160p" to null,
                "1080p" to 10000,
                "1080p" to 6000,
                "1080p" to 3000,
                "720p" to 4000,
                "720p" to 2000,
                "480p" to 1500,
            ),
            QualityPresets.ALL.map { it.resolution to it.bitrateKbps },
        )
        assertEquals(
            listOf(
                "Auto", "Original", "4K", "1080p High", "1080p", "1080p Low",
                "720p High", "720p", "480p",
            ),
            QualityPresets.ALL.map { it.label },
        )
    }

    @Test
    fun `a combination no preset covers selects nothing but still describes`() {
        assertNull(QualityPresets.presetFor("1080p", 4500))
        assertEquals("1080p at 4.5 Mbps", QualityPresets.describe("1080p", 4500))
        assertEquals("4K at 25 Mbps", QualityPresets.describe("2160p", 25000))
    }

    @Test
    fun `uncapped is the absence of a bitrate`() {
        assertEquals("auto", QualityPresets.presetFor("auto", null)?.id)
        // 0 is the local store's spelling of uncapped; it must not read as a cap.
        assertEquals("auto", QualityPresets.presetFor("auto", 0)?.id)
        assertEquals("Auto", QualityPresets.describe("auto", 0))
    }

    @Test
    fun `legacy compound resolutions decompose to the enum member`() {
        // These are the transcode-ladder spellings older builds stored. The
        // bitrate they encoded is deliberately dropped: it lives on its own
        // axis now, and inventing a cap would throttle playback silently.
        assertEquals("1080p", QualityPresets.normalizeResolution("1080p-high"))
        assertEquals("1080p", QualityPresets.normalizeResolution("1080p-8"))
        assertEquals("720p", QualityPresets.normalizeResolution("720p-high"))
        assertEquals("2160p", QualityPresets.normalizeResolution("4K"))
        assertEquals("auto", QualityPresets.normalizeResolution("328p"))
        assertEquals("auto", QualityPresets.normalizeResolution(""))
        assertEquals("auto", QualityPresets.normalizeResolution(null))
        assertEquals("original", QualityPresets.normalizeResolution("Original"))
    }

    @Test
    fun `presetFor normalizes before matching so a legacy value still selects`() {
        assertEquals("2160p", QualityPresets.presetFor("4k", null)?.id)
        assertEquals("1080p", QualityPresets.presetFor("1080p-high", 6000)?.id)
    }
}
