package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.HdrCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlaybackOutputPolicyTest {
    @Test
    fun `decoder HLG is not advertised when active output omits it`() {
        val effective = TvPlaybackOutputPolicy.effectiveHdrCapabilities(
            codec = HdrCapabilities(hdr10 = true, hdr10Plus = true, hlg = true),
            display = HdrCapabilities(hdr10 = true),
        )

        assertEquals(
            HdrCapabilities(hdr10 = true),
            effective,
        )
    }

    @Test
    fun `display HLG is not advertised without decoder support`() {
        val effective = TvPlaybackOutputPolicy.effectiveHdrCapabilities(
            codec = HdrCapabilities(hdr10 = true),
            display = HdrCapabilities(hdr10 = true, hlg = true),
        )

        assertFalse(effective.hlg)
    }

    @Test
    fun `HLG survives when both decoder and active output support it`() {
        val effective = TvPlaybackOutputPolicy.effectiveHdrCapabilities(
            codec = HdrCapabilities(hlg = true),
            display = HdrCapabilities(hlg = true),
        )

        assertTrue(effective.hlg)
    }

    @Test
    fun `NVIDIA Shield enables hardware AV sync tunneling`() {
        assertTrue(
            TvPlaybackOutputPolicy.shouldEnableTunneling(
                manufacturer = "NVIDIA",
                model = "SHIELD Android TV",
                device = "mdarcy",
            ),
        )
    }

    @Test
    fun `Google TV Streamer keeps tunneling disabled by model`() {
        assertFalse(
            TvPlaybackOutputPolicy.shouldEnableTunneling(
                manufacturer = "Google",
                model = "Google TV Streamer",
                device = "mustang",
            ),
        )
    }

    @Test
    fun `Google TV Streamer keeps tunneling disabled by device codename`() {
        assertFalse(
            TvPlaybackOutputPolicy.shouldEnableTunneling(
                manufacturer = "Google",
                model = "GZRNL",
                device = "mustang",
            ),
        )
    }

    @Test
    fun `other Android TV devices can negotiate tunneling`() {
        assertTrue(
            TvPlaybackOutputPolicy.shouldEnableTunneling(
                manufacturer = "Sony",
                model = "BRAVIA 4K",
                device = "bravia_atv3_4k",
            ),
        )
    }

    @Test
    fun `intersection keeps decoder profile bounds only for surviving profiles`() {
        val effective = TvPlaybackOutputPolicy.effectiveHdrCapabilities(
            codec = HdrCapabilities(
                hdr10 = true,
                dolbyVisionProfiles = listOf(5, 8),
                dolbyVisionProfileLevels = listOf(
                    org.siloserver.silo.model.playback.DolbyVisionProfileCapability(5, 9),
                    org.siloserver.silo.model.playback.DolbyVisionProfileCapability(8, 9),
                ),
            ),
            display = HdrCapabilities(hdr10 = true, dolbyVisionProfiles = listOf(8)),
        )

        assertEquals(listOf(8), effective.dolbyVisionProfiles)
        assertEquals(listOf(8), effective.dolbyVisionProfileLevels.map { it.profile })
    }
}
