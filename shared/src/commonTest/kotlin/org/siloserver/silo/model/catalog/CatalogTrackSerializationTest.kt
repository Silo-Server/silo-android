package org.siloserver.silo.model.catalog

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTrackSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AudioTrack decodes server layout field into channelLayout`() {
        val source = """{"codec":"eac3","channels":6,"layout":"5.1","default":true}"""

        val track = json.decodeFromString<AudioTrack>(source)

        assertEquals("5.1", track.channelLayout)
    }

    @Test
    fun `AudioTrack decodes server default field into isDefault`() {
        val source = """{"codec":"eac3","channels":6,"layout":"5.1","default":true}"""

        val track = json.decodeFromString<AudioTrack>(source)

        assertTrue(track.isDefault)
    }

    @Test
    fun `SubtitleTrack decodes server default field into isDefault`() {
        val source = """{"codec":"subrip","default":true}"""

        val track = json.decodeFromString<SubtitleTrack>(source)

        assertTrue(track.isDefault)
    }

    @Test
    fun `VideoTrack preserves server Dolby Vision metadata`() {
        val source = """{"codec":"hevc","dolby_vision":"Profile 8","dv_profile":8}"""

        val track = json.decodeFromString<VideoTrack>(source)

        assertEquals("Profile 8", track.dolbyVision)
        assertEquals(8, track.dolbyVisionProfile)
    }
}
