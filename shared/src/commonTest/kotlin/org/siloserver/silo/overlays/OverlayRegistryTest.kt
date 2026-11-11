package org.siloserver.silo.overlays

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // The profile document the web app writes with the defaults plus a
    // combined badge turned on. Each combined badge hides the badges it
    // repeats, and the wider one also hides the narrower.
    private fun topLeftLabels(vararg combined: String): List<UiText> {
        val items = combined.joinToString(",") { "\"$it\":{\"enabled\":true,\"position\":\"top-left\"}" }
        val prefs = OverlaySchema.parse("""{"version":2,"preset":"classic","order":[],"items":{$items}}""")
        val data = OverlayData(resolution = "2160p", hdr = "DV HDR10", audio = "Atmos")
        return OverlayRegistry.enabled(OverlayPosition.TopLeft, prefs).mapNotNull { it.getValue(data) }
    }

    @Test
    fun combinedBadges_hideTheBadgesTheyRepeat() {
        assertEquals(listOf(UiText.Raw("4K DV"), UiText.Raw("Atmos")), topLeftLabels("resolution_hdr"))
        assertEquals(listOf(UiText.Raw("4K DV Atmos")), topLeftLabels("resolution_hdr_audio"))
        assertEquals(listOf(UiText.Raw("4K DV Atmos")), topLeftLabels("resolution_hdr", "resolution_hdr_audio"))
    }

    @Test
    fun resolutionHdrAudio_getValue_joinsThePartsTheFileHas() {
        val def = OverlayRegistry.all.single { it.id.raw == "resolution_hdr_audio" }
        assertEquals(UiText.Raw("1080p TrueHD"), def.getValue(OverlayData(resolution = "1080p", audio = "TrueHD")))
        assertEquals(UiText.Raw("480p"), def.getValue(OverlayData(resolution = "480p")))
        assertEquals(UiText.Raw("HDR DTS-HD"), def.getValue(OverlayData(hdr = "HDR10", audio = "DTS-HD")))
        assertNull(def.getValue(OverlayData(audio = "")))
        assertEquals(OverlayIconId.DolbyVision, def.getIcon?.invoke(OverlayData(hdr = "DV HDR10")))
        assertNull(def.getIcon?.invoke(OverlayData(hdr = "HDR10", audio = "Atmos")))
    }

    @Test
    fun enabled_respectsUserOrder() {
        val base = OverlaySchema.buildDefaults()
        // Defaults enable resolution, hdr, audio at top-left.
        // An id Android does not know keeps its place without affecting the rest.
        val ordered = base.copy(order = listOf("audio", "advisory_age", "hdr", "resolution"))
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
        assertEquals(UiText.Raw("4K DV"), def.getValue(OverlayData(resolution = "2160p", hdr = "DV")))
        assertEquals(UiText.Raw("4K HDR"), def.getValue(OverlayData(resolution = "4k", hdr = "HDR10")))
        assertEquals(UiText.Raw("1080p"), def.getValue(OverlayData(resolution = "1080p", hdr = null)))
        assertNull(def.getValue(OverlayData(resolution = null)))
    }

    @Test
    fun imdbRating_labelCarriesTheSourceMark() {
        val def = OverlayRegistry.def(OverlayId.RatingImdb)!!
        assertEquals(UiText.Raw("IMDb 8.4"), def.getValue(OverlayData(ratingImdb = 8.42)))
        assertEquals(UiText.Raw("IMDb 7.0"), def.getValue(OverlayData(ratingImdb = 7.0)))
        assertNull(def.getValue(OverlayData(ratingImdb = null)))
        assertEquals(UiText.Raw("IMDb"), def.sourceMark)
        assertEquals(UiText.Raw("9.2"), def.getScore?.invoke(OverlayData(ratingImdb = 9.2)))
    }

    @Test
    fun ratings_matchWeb() {
        val data = OverlayData(ratingImdb = 9.2, ratingTmdb = 7.8, ratingRtCritic = 89, ratingRtAudience = 74)
        val expected = mapOf(
            OverlayId.RatingImdb to Triple("IMDb", "9.2", "#f5c518"),
            OverlayId.RatingTmdb to Triple("TMDB", "7.8", "#01b4e4"),
            OverlayId.RatingRt to Triple("RT", "89%", "#fa320a"),
            OverlayId.RatingRtAudience to Triple("RT Audience", "74%", "#fa6400"),
        )
        for ((id, values) in expected) {
            val (mark, score, accent) = values
            val def = OverlayRegistry.def(id)!!
            assertEquals(UiText.Raw("$mark $score"), def.getValue(data), "$id label")
            assertEquals(UiText.Raw(mark), def.sourceMark, "$id mark")
            assertEquals(UiText.Raw(score), def.getScore?.invoke(data), "$id score")
            assertEquals(accent, def.defaultAccent)
            assertEquals(OverlayPosition.LeftEdge, def.defaultPosition)
            assertEquals(false, def.iconCapable)
            assertNull(def.iconId)
            assertNull(def.getIcon)
            assertNull(def.getValueKey, "$id takes no value images")
            assertNull(def.getScore?.invoke(OverlayData()))
        }
        assertEquals("rt_critic", OverlayRegistry.def(OverlayId.RatingRt)?.ratingSource)
        assertEquals("rt_audience", OverlayRegistry.def(OverlayId.RatingRtAudience)?.ratingSource)
        assertTrue(OverlayRegistry.isOffered(OverlayRegistry.def(OverlayId.RatingImdb)!!, emptySet()))
        assertFalse(OverlayRegistry.isOffered(OverlayRegistry.def(OverlayId.RatingRt)!!, setOf("rt_audience")))
    }

    @Test
    fun formatOutOfTen_roundsHalvesUpLikeJavaScript() {
        // Kotlin's round() is half-even; Math.round is half-up.
        assertEquals("7.3", OverlayRegistry.formatOutOfTen(7.25))
        assertEquals("7.4", OverlayRegistry.formatOutOfTen(7.35))
        assertEquals("10.0", OverlayRegistry.formatOutOfTen(9.96))
        assertEquals("0.0", OverlayRegistry.formatOutOfTen(0.0))
        assertEquals("90%", OverlayRegistry.formatPercent(89.5))
    }

    @Test
    fun nothingDefaultsToTheStatusCorner_andDefaultsMatchWeb() {
        val expected = mapOf(
            "resolution" to "top-left", "hdr" to "top-left", "resolution_hdr" to "top-left",
            "resolution_hdr_audio" to "top-left", "audio" to "top-left", "audio_channels" to "top-left",
            "video_codec" to "top-left", "container" to "bottom-left", "aspect_ratio" to "bottom-right",
            "release_type" to "bottom-left", "edition" to "bottom-left", "multi_audio" to "bottom-right",
            "multi_sub" to "bottom-right", "rating_imdb" to "left-edge", "rating_tmdb" to "left-edge",
            "rating_rt" to "left-edge", "rating_rt_audience" to "left-edge", "content_rating" to "bottom-right",
            "year" to "bottom-left", "runtime" to "bottom-left", "original_language" to "bottom-left",
            "studio" to "bottom-right", "network" to "bottom-right", "show_status" to "right-edge",
            "imdb_top_250" to "right-edge", "rt_certified_fresh" to "right-edge",
        )
        assertEquals(expected, OverlayRegistry.all.associate { it.id.raw to it.defaultPosition.raw })
        assertEquals(
            setOf(OverlayId.Resolution, OverlayId.Hdr, OverlayId.Audio, OverlayId.ReleaseType),
            OverlayRegistry.all.filter { it.defaultEnabled }.map { it.id }.toSet(),
        )
        assertEquals(26, OverlayRegistry.def(OverlayId.ResolutionHdrAudio)?.introducedInManifest)
        assertEquals(listOf(OverlayId.ResolutionHdrAudio), OverlayRegistry.all.filter { it.introducedInManifest != null }.map { it.id })
    }

    @Test
    fun valueKeys_areTheRawApiValues() {
        val data = OverlayData(
            resolution = "2160p", hdr = "DV HDR10", audio = "Atmos", audioChannels = "7.1", videoCodec = "H.265",
            container = "MKV", aspectRatio = "2.39:1", releaseType = "REMUX", edition = "Director's Cut",
            contentRating = "PG-13", originalLanguage = "ja", studio = "A24", network = "HBO",
            showStatus = "returning series", year = 2024, runtime = 120, multiAudio = true, multiSub = true,
        )
        val keys = OverlayRegistry.all.associate { it.id.raw to it.getValueKey?.invoke(data) }
        assertEquals(
            mapOf(
                "resolution" to "2160p", "hdr" to "DV HDR10", "resolution_hdr" to "2160p DV HDR10",
                "resolution_hdr_audio" to "2160p DV HDR10 Atmos", "audio" to "Atmos", "audio_channels" to "7.1",
                "video_codec" to "H.265", "container" to "MKV", "aspect_ratio" to "2.39:1",
                "release_type" to "REMUX", "edition" to "Director's Cut", "multi_audio" to null, "multi_sub" to null,
                "rating_imdb" to null, "rating_tmdb" to null, "rating_rt" to null, "rating_rt_audience" to null,
                "content_rating" to "PG-13", "year" to null, "runtime" to null, "original_language" to "ja",
                "studio" to "A24", "network" to "HBO", "show_status" to "Returning", "imdb_top_250" to null,
                "rt_certified_fresh" to null,
            ),
            keys,
        )
        val resolutionHdr = OverlayRegistry.def(OverlayId.ResolutionHdr)!!
        assertEquals("1080p", resolutionHdr.getValueKey?.invoke(OverlayData(resolution = "1080p")))
        assertNull(resolutionHdr.getValueKey?.invoke(OverlayData(hdr = "HDR10")))
        val combined = OverlayRegistry.def(OverlayId.ResolutionHdrAudio)!!
        assertEquals("1080p TrueHD", combined.getValueKey?.invoke(OverlayData(resolution = "1080p", audio = "TrueHD")))
        assertNull(combined.getValueKey?.invoke(OverlayData(resolution = "", audio = "")))
        assertNull(OverlayRegistry.def(OverlayId.Resolution)!!.getValueKey?.invoke(OverlayData(resolution = "")))
    }

    @Test
    fun showStatus_keyIsNormalizedAndUnknownValuesPassThrough() {
        val def = OverlayRegistry.def(OverlayId.ShowStatus)!!
        val cases = mapOf(
            "Returning Series" to "Returning", "continuing" to "Returning", "in_production" to "Returning",
            "In Production" to "Returning", "ENDED" to "Ended", "canceled" to "Cancelled",
            "Cancelled" to "Cancelled", "planned" to "Upcoming", "upcoming" to "Upcoming",
            "Limited Series" to "Limited Series",
        )
        for ((value, key) in cases) assertEquals(key, def.getValueKey?.invoke(OverlayData(showStatus = value)), value)
        assertNull(def.getValueKey?.invoke(OverlayData(showStatus = "")))
        assertEquals(listOf("Returning", "Ended", "Cancelled", "Upcoming"), def.valueKeySuggestions)
    }

    @Test
    fun icons_matchWeb() {
        val audio = OverlayRegistry.def(OverlayId.Audio)!!
        assertEquals(OverlayIconId.Atmos, audio.getIcon?.invoke(OverlayData(audio = "ATMOS")))
        // Equality, not contains: "TrueHD Atmos" is a plain audio badge.
        assertEquals(OverlayIconId.Volume, audio.getIcon?.invoke(OverlayData(audio = "TrueHD Atmos")))
        val hdr = OverlayRegistry.def(OverlayId.Hdr)!!
        assertEquals(OverlayIconId.DolbyVision, hdr.getIcon?.invoke(OverlayData(hdr = "DV HDR10")))
        assertEquals(OverlayIconId.Hdr10, hdr.getIcon?.invoke(OverlayData(hdr = "HDR10")))
        assertNull(hdr.getIcon?.invoke(OverlayData(hdr = "HLG")))
    }

    @Test
    fun resolutionHdr_keepsHdr10PlusInItsSuffix() {
        val def = OverlayRegistry.def(OverlayId.ResolutionHdr)!!
        assertEquals(UiText.Raw("4K HDR10+"), def.getValue(OverlayData(resolution = "2160p", hdr = "HDR10+")))
        assertEquals(UiText.Raw("4K DV"), def.getValue(OverlayData(resolution = "2160p", hdr = "DV HDR10+")))
        assertNull(def.getValue(OverlayData(resolution = "  ")))
    }

    @Test
    fun runtime_getValue_formatsHoursMinutes() {
        val def = OverlayRegistry.def(OverlayId.Runtime)!!
        assertEquals(
            UiText.Key(SharedString.OverlayRuntimeHoursMinutes, listOf(UiText.Number(2), UiText.Number(22))),
            def.getValue(OverlayData(runtime = 142)),
        )
        assertEquals(UiText.Key(SharedString.OverlayRuntimeMinutes, listOf(UiText.Number(45))), def.getValue(OverlayData(runtime = 45)))
        assertNull(def.getValue(OverlayData(runtime = 0)))
    }

    @Test
    fun showStatus_getValue_translatesKnownStatuses_andPassesOthersThrough() {
        val def = OverlayRegistry.def(OverlayId.ShowStatus)!!
        assertEquals(UiText.Key(SharedString.OverlayReturning), def.getValue(OverlayData(showStatus = "returning series")))
        assertEquals(UiText.Key(SharedString.OverlayCancelled), def.getValue(OverlayData(showStatus = "canceled")))
        assertEquals(UiText.Raw("Limited Series"), def.getValue(OverlayData(showStatus = "Limited Series")))
        assertNull(def.getValue(OverlayData(showStatus = "")))
        // Android trims (web does not): a padded value is the same status, a blank one none.
        assertEquals(UiText.Key(SharedString.OverlayReturning), def.getValue(OverlayData(showStatus = "  returning series  ")))
        assertEquals(UiText.Raw("Limited Series"), def.getValue(OverlayData(showStatus = "  Limited Series  ")))
        assertEquals("Ended", def.getValueKey?.invoke(OverlayData(showStatus = " ended ")))
        assertNull(def.getValue(OverlayData(showStatus = "   ")))
        assertNull(def.getValue(OverlayData(showStatus = null)))
    }

    @Test
    fun enabled_coversEveryPosition() {
        val prefs = OverlaySchema.parse(
            """{"version":2,"preset":"classic","order":[],"items":{
               "rating_imdb":{"enabled":true,"position":"left-edge"},
               "show_status":{"enabled":true,"position":"top-right"},
               "studio":{"enabled":true,"position":"bottom-right"}}}""",
        )
        assertEquals(listOf(OverlayId.RatingImdb), OverlayRegistry.enabled(OverlayPosition.LeftEdge, prefs).map { it.id })
        assertEquals(listOf(OverlayId.ShowStatus), OverlayRegistry.enabled(OverlayPosition.RightEdge, prefs).map { it.id })
        assertEquals(listOf(OverlayId.Studio), OverlayRegistry.enabled(OverlayPosition.BottomRight, prefs).map { it.id })
    }
}
