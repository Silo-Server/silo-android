package org.siloserver.silo.network.apiv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.catalog.ThemeSelection
import org.siloserver.silo.model.catalog.ThemeTrack
import org.siloserver.silo.network.apiv2.ApiV2Fixtures.plusUnknown
import org.siloserver.silo.network.apiv2.ApiV2Fixtures.with

/** The vendored theme music fixtures, decoded with the production SiloJson. */
class ThemeMusicContractTest {

    @Test
    fun itemDetailCarriesItsThemeSet() {
        val detail = ApiV2Fixtures.decode<ItemDetailReadV2>(ApiV2Fixtures.bodyObject("get_catalog_item_ok")).toDomain()
        assertEquals(
            ThemeSelection(
                ownerId = "movie:heat-1995",
                items = listOf(ThemeTrack(id = "7", title = "Opening", durationSeconds = 60, container = "mp3")),
            ),
            detail.themes,
        )
    }

    @Test
    fun itemDetailWithoutThemesHasNone() {
        val body = with(ApiV2Fixtures.bodyObject("get_catalog_item_ok"), "themes" to null)
        assertNull(ApiV2Fixtures.decode<ItemDetailReadV2>(body).toDomain().themes)
    }

    @Test
    fun capabilityFixture() {
        val capability = ApiV2Fixtures.decode<ThemeSongsCapabilityV2>(
            ApiV2Fixtures.bodyObject("theme_songs_capability").plusUnknown(),
        )
        assertTrue(capability.isAvailable)
        assertEquals("routed", capability.delivery)
        assertTrue(capability.transcode)
    }

    @Test
    fun capabilityIsUnavailableWhenNotAllowedOrNotConfigured() {
        val base = ApiV2Fixtures.bodyObject("theme_songs_capability")
        val refused = with(base, "allowed" to kotlinx.serialization.json.JsonPrimitive(false))
        val unconfigured = with(base, "state" to kotlinx.serialization.json.JsonPrimitive("not_configured"))
        assertEquals(false, ApiV2Fixtures.decode<ThemeSongsCapabilityV2>(refused).isAvailable)
        assertEquals(false, ApiV2Fixtures.decode<ThemeSongsCapabilityV2>(unconfigured).isAvailable)
    }

    @Test
    fun playbackGrantFixtures() {
        val original = ApiV2Fixtures.decode<ThemePlaybackGrantV2>(ApiV2Fixtures.bodyObject("theme_songs_playback").plusUnknown())
        assertEquals("/api/v2/catalog/items/movie:heat-1995/themes/7/audio?token=fixture-theme-grant", original.url)
        assertEquals(false, original.isConverted)
        assertEquals("audio/mpeg", original.contentType)

        val converted = ApiV2Fixtures.decode<ThemePlaybackGrantV2>(ApiV2Fixtures.bodyObject("theme_songs_playback_converted"))
        assertTrue(converted.isConverted)
        assertEquals("audio/mp4", converted.contentType)
    }
}
