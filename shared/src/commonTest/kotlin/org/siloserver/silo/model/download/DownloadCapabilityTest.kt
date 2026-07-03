package org.siloserver.silo.model.download

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadCapabilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes quality_presets payload`() {
        val source = """
            {
              "enabled": true,
              "download_allowed": true,
              "quality_presets": ["original", "10mbps", "5mbps"],
              "transcode_enabled": true,
              "transcode_user_allowed": true,
              "season_download": true,
              "series_monitoring": false,
              "monitoring_modes": []
            }
        """.trimIndent()

        val c = json.decodeFromString<DownloadCapability>(source)
        assertTrue(c.isUsable)
        assertTrue(c.transcodeEnabled)
        assertTrue(c.transcodeUserAllowed)
        assertEquals(listOf("original", "10mbps", "5mbps"), c.effectivePresets)
    }

    @Test
    fun `falls back to legacy formats key`() {
        val source = """
            {
              "enabled": true,
              "download_allowed": true,
              "formats": ["original", "2mbps"]
            }
        """.trimIndent()

        val c = json.decodeFromString<DownloadCapability>(source)
        assertEquals(listOf("original", "2mbps"), c.effectivePresets)
    }

    @Test
    fun `defaults to original when neither key is present`() {
        val c = json.decodeFromString<DownloadCapability>("""{"enabled": true, "download_allowed": false}""")
        assertEquals(listOf(DownloadQuality.Original.wire), c.effectivePresets)
        assertFalse(c.isUsable)
        assertFalse(c.transcodeEnabled)
    }

    @Test
    fun `resolveDownloadQuality prefers requested then stored default then original`() {
        val allowed = listOf("original", "10mbps", "5mbps")
        assertEquals("5mbps", resolveDownloadQuality("5mbps", allowed, storedDefault = "10mbps"))
        assertEquals("10mbps", resolveDownloadQuality("20mbps", allowed, storedDefault = "10mbps"))
        assertEquals("10mbps", resolveDownloadQuality(null, allowed, storedDefault = "10mbps"))
        assertEquals("original", resolveDownloadQuality("20mbps", allowed, storedDefault = "2mbps"))
        assertEquals("original", resolveDownloadQuality(null, emptyList(), storedDefault = "10mbps"))
    }

    @Test
    fun `DownloadQuality fromWire maps known presets and rejects unknowns`() {
        assertEquals(DownloadQuality.Original, DownloadQuality.fromWire("original"))
        assertEquals(DownloadQuality.TwentyMbps, DownloadQuality.fromWire("20mbps"))
        assertEquals(null, DownloadQuality.fromWire("999mbps"))
        assertEquals(null, DownloadQuality.fromWire(null))
    }
}
