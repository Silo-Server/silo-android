package org.siloserver.silo.model.download

import org.siloserver.silo.model.catalog.AudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineTracksTest {

    /** A multi-track prepared download, shaped like the server's manifest. */
    private val preparedManifest = """
        {
          "download_id": "dl_1",
          "media_file_id": "4567",
          "delivery_format": "transcode",
          "container": "mp4",
          "selected_audio_track_index": 1,
          "audio_tracks": [
            {"index": 0, "language": "eng", "title": "Main", "codec": "aac", "layout": "stereo", "channels": 2, "default": true},
            {"index": 1, "language": "jpn", "codec": "aac", "channels": 2, "default": false},
            {"index": 2, "language": "eng", "title": "Commentary", "codec": "aac", "channels": 2, "default": false}
          ],
          "subtitles": [
            {"language": "eng", "format": "ass", "forced": false, "hearing_impaired": false,
             "fetch_url": "/api/v2/downloads/dl_1/subtitles/embedded:2"},
            {"language": "eng", "format": "sup", "forced": true, "hearing_impaired": false,
             "fetch_url": "/api/v2/downloads/dl_1/subtitles/embedded:3"}
          ],
          "manifest_version": 3
        }
    """.trimIndent()

    @Test
    fun decodesTheServerManifestIgnoringFieldsItDoesNotUse() {
        val manifest = assertNotNull(decodeOfflineManifestTracks(preparedManifest))

        assertEquals("transcode", manifest.deliveryFormat)
        assertEquals(1, manifest.selectedAudioTrackIndex)
        assertEquals(3, manifest.audioTracks.size)
        assertEquals(listOf("ass", "sup"), manifest.subtitles.map { it.format })
        assertTrue(manifest.subtitles[1].forced)
    }

    @Test
    fun audioTracksKeepFilePositionsAndTheManifestSelection() {
        val info = assertNotNull(decodeOfflineManifestTracks(preparedManifest)).toOfflineTrackInfo(emptyList())
        assertTrue(info.audioByPosition)

        assertEquals(listOf(0, 1, 2), info.audioTracks.map { it.index })
        assertEquals(listOf("Main", null, "Commentary"), info.audioTracks.map { it.title })
        assertEquals(1, info.defaultAudioPosition())
        assertEquals("jpn", info.audioTracks[1].language)
    }

    @Test
    fun originalDownloadsKeepAudioRowsWithoutPositionalSelection() {
        val manifest = assertNotNull(
            decodeOfflineManifestTracks(preparedManifest.replace("\"transcode\"", "\"original\"")),
        )
        val sidecar = OfflineSubtitleFile(path = "subtitles/0.srt", format = "srt", language = "eng")

        val info = manifest.toOfflineTrackInfo(listOf(sidecar))

        // Media3 may not report a source container's audio groups in file order,
        // so an original file keeps its menu rows but is not selected by position.
        assertFalse(info.audioByPosition)
        assertEquals(3, info.audioTracks.size)
        assertEquals(1, info.defaultAudioPosition())
        assertEquals(listOf(sidecar), info.subtitles)
    }

    @Test
    fun anOutOfRangeManifestSelectionIsDropped() {
        val manifest = OfflineManifestTracks(
            selectedAudioTrackIndex = 2,
            audioTracks = listOf(AudioTrack(index = 0, language = "eng"), AudioTrack(index = 1, language = "fre")),
        )

        assertNull(manifest.toOfflineTrackInfo(emptyList()).selectedAudioTrackIndex)
    }

    @Test
    fun defaultAudioFallsBackToTheDefaultFlagThenTheFirstTrack() {
        val flagged = OfflineTrackInfo(
            audioTracks = listOf(
                AudioTrack(index = 0, language = "eng"),
                AudioTrack(index = 1, language = "fre", isDefault = true),
            ),
            selectedAudioTrackIndex = 7,
        )
        assertEquals(1, flagged.defaultAudioPosition())
        assertEquals(0, flagged.copy(audioTracks = flagged.audioTracks.map { it.copy(isDefault = false) }).defaultAudioPosition())
        assertEquals(0, OfflineTrackInfo().defaultAudioPosition())
    }

    @Test
    fun legacyPreparedManifestDescribesItsSingleTrack() {
        val manifest = assertNotNull(
            decodeOfflineManifestTracks(
                """{"selected_audio_track_index":0,"audio_tracks":[{"index":0,"language":"en","codec":"aac","default":true}],"subtitles":[]}""",
            ),
        )
        val info = manifest.toOfflineTrackInfo(emptyList())

        assertEquals(1, info.audioTracks.size)
        assertEquals(0, info.defaultAudioPosition())
        assertTrue(info.subtitles.isEmpty())
    }

    @Test
    fun manifestWithoutTrackFieldsDecodesEmpty() {
        val manifest = assertNotNull(decodeOfflineManifestTracks("""{"download_id":"dl_1","subtitles":null}"""))

        assertTrue(manifest.audioTracks.isEmpty())
        assertTrue(manifest.subtitles.isEmpty())
        assertNull(decodeOfflineManifestTracks("not json"))
    }

    @Test
    fun subtitleFormatsMapToMountableLocalFormats() {
        assertEquals("srt", offlineSubtitleFormat("srt"))
        assertEquals("srt", offlineSubtitleFormat("SubRip"))
        assertEquals("vtt", offlineSubtitleFormat("webvtt"))
        assertEquals("ass", offlineSubtitleFormat("ass"))
        assertEquals("ssa", offlineSubtitleFormat("ssa"))
        assertEquals("pgs", offlineSubtitleFormat("sup"))
        assertEquals("ttml", offlineSubtitleFormat("ttml"))
        assertNull(offlineSubtitleFormat("sub"))
        assertNull(offlineSubtitleFormat(null))
        assertEquals("sup", offlineSubtitleExtension("pgs"))
        assertEquals("ass", offlineSubtitleExtension("ass"))
    }

    @Test
    fun onlyManagedDownloadSubtitleProxyPathsAreFetched() {
        assertTrue(isOfflineSubtitleFetchUrl("/api/v2/downloads/dl_1/subtitles/embedded:2"))
        assertTrue(isOfflineSubtitleFetchUrl("/api/v2/downloads/dl_1/subtitles/downloaded:17"))
        assertFalse(isOfflineSubtitleFetchUrl("https://evil.example/api/v2/downloads/dl_1/subtitles/external:0"))
        assertFalse(isOfflineSubtitleFetchUrl("/api/v2/downloads/dl_1/../../admin/subtitles/x"))
        assertFalse(isOfflineSubtitleFetchUrl("/api/v2/downloads/dl_1/artwork/poster"))
        assertFalse(isOfflineSubtitleFetchUrl("/api/v1/downloads/dl_1/subtitles/external:0"))
    }
}
