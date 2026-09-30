package org.siloserver.silo.overlays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayRegistryTest {

    @Test
    fun defFor_resolvesEveryId() {
        for (id in OverlayId.entries) {
            val def = OverlayRegistry.def(id)
            assertTrue(def != null, "no def for $id")
            assertEquals(id, def.id)
        }
    }

    @Test
    fun defsIn_groupByCategory_andCoverAll() {
        var total = 0
        for (category in OverlayCategory.entries) {
            val defs = OverlayRegistry.defs(category)
            assertTrue(defs.all { it.category == category })
            total += defs.size
        }
        assertEquals(OverlayRegistry.all.size, total)
    }

    @Test
    fun resolutionHdr_suppressesStandalones_whenEnabled() {
        val base = OverlaySchema.buildDefaults()
        val items = base.items.toMutableMap()
        items[OverlayId.ResolutionHdr] = items.getValue(OverlayId.ResolutionHdr).copy(enabled = true)
        val prefs = base.copy(items = items)
        assertTrue(OverlayRegistry.isSuppressed(OverlayId.Resolution, prefs))
        assertTrue(OverlayRegistry.isSuppressed(OverlayId.Hdr, prefs))
        assertTrue(!OverlayRegistry.isSuppressed(OverlayId.Audio, prefs))
    }

    @Test
    fun enabled_respectsUserOrder() {
        val base = OverlaySchema.buildDefaults()
        // Defaults enable resolution, hdr, audio at top-left.
        val ordered = base.copy(order = listOf(OverlayId.Audio, OverlayId.Hdr, OverlayId.Resolution))
        val ids = OverlayRegistry.enabled(OverlayPosition.TopLeft, ordered).map { it.id }
        assertEquals(listOf(OverlayId.Audio, OverlayId.Hdr, OverlayId.Resolution), ids)
    }

    @Test
    fun enabled_fallsBackToRegistryOrder_whenOrderEmpty() {
        val prefs = OverlaySchema.buildDefaults()
        val ids = OverlayRegistry.enabled(OverlayPosition.TopLeft, prefs).map { it.id }
        // Registry order for the top-left defaults: resolution, hdr, audio.
        assertEquals(listOf(OverlayId.Resolution, OverlayId.Hdr, OverlayId.Audio), ids)
    }

    @Test
    fun resolutionHdr_getValue_combinesPretty() {
        val def = OverlayRegistry.def(OverlayId.ResolutionHdr)!!
        assertEquals("4K DV", def.getValue(OverlayData(resolution = "2160p", hdr = "DV")))
        assertEquals("4K HDR", def.getValue(OverlayData(resolution = "4k", hdr = "HDR10")))
        assertEquals("1080p", def.getValue(OverlayData(resolution = "1080p", hdr = null)))
        assertNull(def.getValue(OverlayData(resolution = null)))
    }

    @Test
    fun imdbRating_getValue_formatsOneDecimal() {
        val def = OverlayRegistry.def(OverlayId.RatingImdb)!!
        assertEquals("8.4", def.getValue(OverlayData(ratingImdb = 8.42)))
        assertEquals("7.0", def.getValue(OverlayData(ratingImdb = 7.0)))
        assertNull(def.getValue(OverlayData(ratingImdb = null)))
    }

    @Test
    fun runtime_getValue_formatsHoursMinutes() {
        val def = OverlayRegistry.def(OverlayId.Runtime)!!
        assertEquals("2h 22m", def.getValue(OverlayData(runtime = 142)))
        assertEquals("45m", def.getValue(OverlayData(runtime = 45)))
        assertNull(def.getValue(OverlayData(runtime = 0)))
    }

    @Test
    fun showStatus_getValue_trimsBeforeMappingAndFallback() {
        val def = OverlayRegistry.def(OverlayId.ShowStatus)!!
        assertEquals("Returning", def.getValue(OverlayData(showStatus = "  returning series  ")))
        assertEquals("Limited Series", def.getValue(OverlayData(showStatus = "  Limited Series  ")))
        assertNull(def.getValue(OverlayData(showStatus = "   ")))
    }
}
