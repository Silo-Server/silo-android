package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvSubtitleAppearanceApplicabilityTest {

    private fun identity(codec: String?): SubtitleIdentity =
        SubtitleIdentity.Embedded(
            serverIndex = 2,
            media = SubtitleMediaIdentity(codecFamily = codec),
        )

    @Test
    fun `text subtitles keep every appearance control`() {
        listOf("subrip", "webvtt", "text/vtt", "ass", "ttml").forEach { codec ->
            val applicability = tvSubtitleAppearanceApplicability(identity(codec))
            assertTrue(applicability.geometryApplies, codec)
            assertTrue(applicability.stylingApplies, codec)
            assertNull(applicability.note, codec)
        }
    }

    @Test
    fun `image subtitles keep Position and Size but lose the styling rows`() {
        listOf(
            "pgs",
            "hdmv_pgs_subtitle",
            "application/pgs",
            "dvbsub",
            "dvd_subtitle",
        ).forEach { codec ->
            val applicability = tvSubtitleAppearanceApplicability(identity(codec))
            assertTrue(applicability.geometryApplies, codec)
            assertFalse(applicability.stylingApplies, codec)
            assertNotNull(applicability.note, codec)
        }
    }

    @Test
    fun `burned-in subtitles take nothing at all`() {
        val applicability = tvSubtitleAppearanceApplicability(
            SubtitleIdentity.ServerBurnIn(
                serverIndex = 1,
                media = SubtitleMediaIdentity(codecFamily = "subrip"),
            ),
        )

        assertFalse(applicability.geometryApplies)
        assertFalse(applicability.stylingApplies)
        assertNotNull(applicability.note)
    }

    @Test
    fun `Off and an unknown selection fall back to the full appearance block`() {
        listOf(null, SubtitleIdentity.Off, identity(null)).forEach { identity ->
            val applicability = tvSubtitleAppearanceApplicability(identity)
            assertTrue(applicability.geometryApplies)
            assertTrue(applicability.stylingApplies)
            assertNull(applicability.note)
        }
    }
}
