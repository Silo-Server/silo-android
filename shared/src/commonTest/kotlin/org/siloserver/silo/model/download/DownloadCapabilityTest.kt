package org.siloserver.silo.model.download

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadCapabilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes the server capability envelope`() {
        val source = """
            {
              "enabled": true,
              "download_allowed": true,
              "quality_presets": ["original", "20mbps", "10mbps", "5mbps", "2mbps", "1mbps"],
              "transcode_enabled": true,
              "transcode_user_allowed": true,
              "season_download": true,
              "series_monitoring": true,
              "monitoring_modes": ["all", "future"]
            }
        """.trimIndent()

        val cap = json.decodeFromString<DownloadCapability>(source)
        assertTrue(cap.isUsable)
        assertEquals(6, cap.qualityPresets.size)
        assertTrue(cap.transcodeEnabled)
    }

    @Test
    fun `labels presets with the server resolution ceiling`() {
        val source = """
            {
              "enabled": true,
              "download_allowed": true,
              "quality_presets": ["original", "20mbps", "1mbps"],
              "quality_options": [
                {"preset": "original"},
                {"preset": "20mbps", "bitrate_kbps": 20000, "max_height": 2160},
                {"preset": "1mbps", "bitrate_kbps": 1000, "max_height": 480}
              ]
            }
        """.trimIndent()

        val cap = json.decodeFromString<DownloadCapability>(source)
        assertEquals("Original", cap.labelFor(DownloadQuality.Original))
        assertEquals("20 Mbps · up to 4K", cap.labelFor(DownloadQuality.Mbps20))
        assertEquals("1 Mbps · up to 480p", cap.labelFor(DownloadQuality.Mbps1))
        // A preset the server does not describe, or no capability yet, keeps the bitrate label.
        assertEquals("10 Mbps", cap.labelFor(DownloadQuality.Mbps10))
        assertEquals("5 Mbps", (null as DownloadCapability?).labelFor(DownloadQuality.Mbps5))
    }

    @Test
    fun `allowedQualities offers every preset when transcode is enabled and allowed`() {
        val cap = DownloadCapability(
            enabled = true,
            downloadAllowed = true,
            qualityPresets = listOf("original", "20mbps", "10mbps", "5mbps", "2mbps", "1mbps"),
            transcodeEnabled = true,
            transcodeUserAllowed = true,
        )
        assertEquals(DownloadQuality.entries.toList(), cap.allowedQualities())
    }

    @Test
    fun `a saved default the account can no longer request falls back to Original`() {
        val transcodeOff = DownloadCapability(
            enabled = true,
            downloadAllowed = true,
            qualityPresets = listOf("original"),
            transcodeEnabled = false,
            transcodeUserAllowed = true,
        )
        assertEquals(DownloadQuality.Original, transcodeOff.effectiveDefault(DownloadQuality.Mbps10))
        val transcodeOn = transcodeOff.copy(qualityPresets = listOf("original", "10mbps"), transcodeEnabled = true)
        assertEquals(DownloadQuality.Mbps10, transcodeOn.effectiveDefault(DownloadQuality.Mbps10))
        // Before the capability loads, the saved value stands.
        assertEquals(DownloadQuality.Mbps10, (null as DownloadCapability?).effectiveDefault(DownloadQuality.Mbps10))
        // Without Original on offer, the first offered preset is used.
        val bitrateOnly = transcodeOn.copy(qualityPresets = listOf("5mbps", "2mbps"))
        assertEquals(DownloadQuality.Mbps5, bitrateOnly.effectiveDefault(DownloadQuality.Mbps10))
    }

    @Test
    fun `allowedQualities collapses to Original when transcode is disabled`() {
        val cap = DownloadCapability(
            enabled = true,
            downloadAllowed = true,
            // Even if the server still listed bitrate presets, a disabled
            // transcode gate must hide them (avoids a 403 transcode_disabled).
            qualityPresets = listOf("original", "10mbps", "5mbps"),
            transcodeEnabled = false,
            transcodeUserAllowed = true,
        )
        assertEquals(listOf(DownloadQuality.Original), cap.allowedQualities())
    }

    @Test
    fun `allowedQualities collapses to Original when the user is not allowed to transcode`() {
        val cap = DownloadCapability(
            enabled = true,
            downloadAllowed = true,
            qualityPresets = listOf("original", "10mbps"),
            transcodeEnabled = true,
            transcodeUserAllowed = false,
        )
        assertEquals(listOf(DownloadQuality.Original), cap.allowedQualities())
    }

    @Test
    fun `allowedQualities filters to only the presets the server advertised`() {
        val cap = DownloadCapability(
            enabled = true,
            downloadAllowed = true,
            qualityPresets = listOf("original", "5mbps"),
            transcodeEnabled = true,
            transcodeUserAllowed = true,
        )
        assertEquals(
            listOf(DownloadQuality.Original, DownloadQuality.Mbps5),
            cap.allowedQualities(),
        )
    }

    @Test
    fun `allowedQualities never returns empty`() {
        val cap = DownloadCapability(enabled = false, downloadAllowed = false)
        assertFalse(cap.isUsable)
        assertEquals(listOf(DownloadQuality.Original), cap.allowedQualities())
    }
}
