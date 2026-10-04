package org.siloserver.silo.model.subtitles

import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleSyncStateTest {
    private val key = "external-1b12a8ccaa6122106f36824eb19e86f7a6dbee64b733031838a285229dbed67b"

    @Test
    fun decodesARunningSidecarSync() {
        val state = SiloJson.decodeFromString(
            SubtitleSyncState.serializer(),
            """{"key":"$key","media_file_id":"1","source":"external","language":"en","format":"srt",
               "label":"Night Train (2024).en.srt","timing":{"offset_ms":0,"scale":1},
               "sync":{"id":"2","status":"running","trigger":"manual","phase":"analyzing","progress":0.4,
                       "confidence":null,"created_at":"2026-10-04T02:37:35.235Z","finished_at":null}}""",
        )
        assertTrue(state.isSidecar)
        assertNull(state.storedSubtitleId)
        val job = state.sync!!
        assertTrue(job.inProgress)
        assertEquals("2", job.id)
        assertEquals("manual", job.trigger)
        assertEquals(40, syncProgressPercent(job))
        assertEquals("Listening to the audio…", syncPhaseLabel(job))
        assertEquals("Syncing… 40%", subtitleSyncStatusLabel(state.timing, job))
    }

    @Test
    fun decodesAFinishedStoredSyncAndTheCapability() {
        val state = SiloJson.decodeFromString(
            SubtitleSyncState.serializer(),
            """{"key":"stored-9","media_file_id":"1","source":"downloaded","stored_subtitle_id":"9","language":"en",
               "format":"srt","label":"release","timing":{"offset_ms":-3010,"scale":1},
               "sync":{"id":"5","status":"synced","trigger":"auto","confidence":1,"result":{"offset_ms":-3010,"scale":1},
                       "created_at":"2026-10-04T02:37:35.235Z","finished_at":"2026-10-04T02:37:35.351Z"}}""",
        )
        assertFalse(state.isSidecar)
        assertEquals("9", state.storedSubtitleId)
        assertNull(syncProgressPercent(state.sync))
        assertEquals("Synced −3.0 s", subtitleSyncStatusLabel(state.timing, state.sync))
        assertEquals(
            SubtitleSyncResultLine("Synced to the audio: −3.0 s"),
            subtitleSyncResultLine(state.timing, state.sync),
        )

        val capability = SiloJson.decodeFromString(
            SubtitleSyncCapability.serializer(),
            """{"revision":"r","state":"available","allowed":true,"auto_sync":true,"external":true}""",
        )
        assertTrue(capability.available)
        assertTrue(capability.external)
        // A server that predates sidecar sync omits `external`.
        assertFalse(
            SiloJson.decodeFromString(
                SubtitleSyncCapability.serializer(),
                """{"revision":"r","state":"available","allowed":true,"auto_sync":true}""",
            ).external,
        )
    }

    @Test
    fun aStoredDownloadCarriesItsAutomaticJob() {
        val subtitle = DownloadedSubtitle(
            id = 9, mediaFileId = 1, provider = "upload", language = "en", format = "srt", releaseName = "",
            sync = SubtitleSyncJob(id = "7", status = SubtitleSyncJob.PENDING, trigger = "auto"),
        )
        val state = subtitle.syncState()
        assertEquals("stored-9", state.key)
        assertEquals("1", state.mediaFileId)
        assertEquals("upload", state.label)
        assertEquals("7", state.sync?.id)
    }

    @Test
    fun describesProgressPhasesAndFailures() {
        fun running(phase: String?, progress: Double?) =
            SubtitleSyncJob(id = "1", status = SubtitleSyncJob.RUNNING, phase = phase, progress = progress)
        assertEquals("Waiting to start…", syncPhaseLabel(running("queued", 0.0)))
        assertEquals("Matching lines to speech…", syncPhaseLabel(running("matching", 0.9)))
        assertEquals("Syncing…", subtitleSyncStatusLabel(SubtitleTiming(), running("queued", 0.0)))
        assertEquals(100, syncProgressPercent(running("matching", 1.4)))
        assertNull(syncPhaseLabel(SubtitleSyncJob(status = SubtitleSyncJob.FAILED)))

        assertEquals("The subtitle changed while it was syncing. Try again.", syncFailureMessage("subtitle_changed"))
        assertEquals("This video has no audio Silo can read.", syncFailureMessage("no_audio"))
        assertEquals("The server is busy. Try again in a few minutes.", syncFailureMessage("unavailable"))
        assertEquals("Sync failed. Try again.", syncFailureMessage("error"))
        assertEquals("Sync failed. Try again.", syncFailureMessage(null))
    }

    @Test
    fun resultLinesMatchTheWebPlayer() {
        val identity = SubtitleTiming()
        assertEquals(
            SubtitleSyncResultLine("Doesn't match this video's audio; probably for another release.", warning = true),
            subtitleSyncResultLine(identity, SubtitleSyncJob(status = SubtitleSyncJob.NO_MATCH)),
        )
        assertEquals(
            SubtitleSyncResultLine("This video has no audio Silo can read.", warning = true),
            subtitleSyncResultLine(identity, SubtitleSyncJob(status = SubtitleSyncJob.FAILED, failure = "no_audio")),
        )
        assertEquals(
            SubtitleSyncResultLine("Already matches the audio."),
            subtitleSyncResultLine(identity, SubtitleSyncJob(status = SubtitleSyncJob.ALREADY_SYNCED)),
        )
        // A synced subtitle that was reset afterwards has no result to show.
        assertNull(subtitleSyncResultLine(identity, SubtitleSyncJob(status = SubtitleSyncJob.SYNCED)))
        assertNull(subtitleSyncResultLine(identity, null))
        assertEquals("+0.5 s · 25→23.976 fps", describeSyncTiming(SubtitleTiming(500, 25.0 / 23.976)))
        assertEquals("25→23.976 fps", describeSyncTiming(SubtitleTiming(0, 25.0 / 23.976)))
    }
}
