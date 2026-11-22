// shared/src/commonTest/kotlin/org/siloserver/silo/model/subtitles/SubtitleModelsSerializationTest.kt
package org.siloserver.silo.model.subtitles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleModelsSerializationTest {

    // Mirrors SiloJson (network/SiloHttpClientImpl.kt) so decode
    // behavior in tests matches production wire handling exactly.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `decodes search response with optional fields present and absent`() {
        val payload = """
            {
              "results": [
                {
                  "id": "os-9182736",
                  "provider": "opensubtitles",
                  "language": "en",
                  "release_name": "Dune.Part.Three.2026.2160p.WEB-DL.DDP5.1-FLUX",
                  "format": "srt",
                  "score": 91.5,
                  "downloads": 48210,
                  "hearing_impaired": false,
                  "upload_date": "2026-05-30T14:21:07Z"
                },
                {
                  "id": "sdl-555",
                  "provider": "subdl",
                  "language": "nl",
                  "release_name": "Dune Part Three WEBRip",
                  "format": "ass",
                  "score": 38,
                  "downloads": 112,
                  "hearing_impaired": true
                }
              ],
              "warnings": ["subsource: rate limited"]
            }
        """.trimIndent()

        val response = json.decodeFromString(SubtitleSearchResponse.serializer(), payload)

        assertEquals(2, response.results.size)
        val first = response.results[0]
        assertEquals("os-9182736", first.id)
        assertEquals(SubtitleProvider.OpenSubtitles, first.provider)
        assertEquals("en", first.language)
        assertEquals("Dune.Part.Three.2026.2160p.WEB-DL.DDP5.1-FLUX", first.releaseName)
        assertEquals("srt", first.format)
        assertEquals(91.5, first.score)
        assertEquals(48210, first.downloads)
        assertFalse(first.hearingImpaired)
        assertEquals("2026-05-30T14:21:07Z", first.uploadDate)

        val second = response.results[1]
        assertEquals(SubtitleProvider.Subdl, second.provider)
        assertEquals(38.0, second.score)  // integer score on the wire
        assertTrue(second.hearingImpaired)
        assertNull(second.uploadDate)     // upload_date absent

        assertEquals(listOf("subsource: rate limited"), response.warnings)
    }

    @Test
    fun `decodes search response without warnings`() {
        val response = json.decodeFromString(
            SubtitleSearchResponse.serializer(),
            """{"results": []}""",
        )
        assertTrue(response.results.isEmpty())
        assertTrue(response.warnings.isEmpty())
    }

    @Test
    fun `decodes download response envelope`() {
        val payload = """
            {
              "subtitle": {
                "id": 312,
                "media_file_id": 1048,
                "provider": "opensubtitles",
                "language": "en",
                "format": "srt",
                "release_name": "Dune.Part.Three.2026.2160p.WEB-DL.DDP5.1-FLUX",
                "score": 91.5,
                "hearing_impaired": false,
                "created_at": "2026-06-12T09:30:00Z"
              }
            }
        """.trimIndent()

        val sub = json.decodeFromString(SubtitleDownloadResponse.serializer(), payload).subtitle

        assertEquals(312, sub.id)
        assertEquals(1048, sub.mediaFileId)
        assertEquals("opensubtitles", sub.provider)
        assertEquals("en", sub.language)
        assertEquals("srt", sub.format)
        assertEquals("Dune.Part.Three.2026.2160p.WEB-DL.DDP5.1-FLUX", sub.releaseName)
        assertEquals(91.5, sub.score)
        assertFalse(sub.hearingImpaired)
        assertEquals("2026-06-12T09:30:00Z", sub.createdAt)
    }

    @Test
    fun `decodes subtitles list with null array from Go nil slice`() {
        // Go marshals a nil slice as JSON null; coerceInputValues maps it to the default.
        val response = json.decodeFromString(
            DownloadedSubtitlesResponse.serializer(),
            """{"subtitles": null}""",
        )
        assertTrue(response.subtitles.isEmpty())
    }

    @Test
    fun `decodes ai status both shapes`() {
        val on = json.decodeFromString(
            SubtitleAiStatus.serializer(),
            """{"enabled": true, "transcribe_enabled": false}""",
        )
        assertTrue(on.enabled)
        assertFalse(on.transcribeEnabled)

        // Disabled probe shape from handlers.WriteSubtitleAIDisabledStatus.
        val off = json.decodeFromString(
            SubtitleAiStatus.serializer(),
            """{"enabled": false, "transcribe_enabled": false}""",
        )
        assertFalse(off.enabled)
        assertFalse(off.transcribeEnabled)
    }

    @Test
    fun `decodes quota limited and exempt zero-value shapes`() {
        val limited = json.decodeFromString(
            SubtitleAiQuota.serializer(),
            """{"limited": true, "limit": 5, "used": 3, "remaining": 2, "period": "week"}""",
        )
        assertTrue(limited.limited)
        assertEquals(5, limited.limit)
        assertEquals(3, limited.used)
        assertEquals(2, limited.remaining)
        assertEquals("week", limited.period)

        // Exempt/admin callers get the Go zero value.
        val exempt = json.decodeFromString(
            SubtitleAiQuota.serializer(),
            """{"limited": false, "limit": 0, "used": 0, "remaining": 0, "period": ""}""",
        )
        assertFalse(exempt.limited)
        assertEquals("", exempt.period)
    }

    @Test
    fun `decodes pending job envelope with null result id and absent error`() {
        val payload = """
            {
              "job": {
                "id": 91,
                "media_file_id": 1048,
                "kind": "translate",
                "source_index": 2,
                "source_language": "en",
                "target_language": "nl",
                "engine": "openai",
                "model": "gpt-4o-mini",
                "status": "pending",
                "progress": 0,
                "progress_message": "",
                "result_subtitle_id": null,
                "created_at": "2026-06-12T10:00:00Z",
                "updated_at": "2026-06-12T10:00:00Z"
              }
            }
        """.trimIndent()

        val job = json.decodeFromString(SubtitleAiJobResponse.serializer(), payload).job

        assertEquals(91L, job.id)
        assertEquals(1048, job.mediaFileId)
        assertEquals(SubtitleAiJobKind.Translate, job.kind)
        assertEquals(2, job.sourceIndex)
        assertEquals("en", job.sourceLanguage)
        assertEquals("nl", job.targetLanguage)
        assertEquals("openai", job.engine)
        assertEquals("gpt-4o-mini", job.model)
        assertEquals(SubtitleAiJobStatus.Pending, job.status)
        assertEquals(0.0, job.progress)
        assertNull(job.resultSubtitleId)
        assertNull(job.errorMessage)
        assertFalse(job.isTerminal)
    }

    @Test
    fun `decodes jobs list and terminal status helpers`() {
        val payload = """
            {
              "jobs": [
                {"id": 91, "media_file_id": 1048, "kind": "translate", "source_index": 2,
                 "source_language": "en", "target_language": "nl", "engine": "openai",
                 "model": "gpt-4o-mini", "status": "completed", "progress": 1,
                 "progress_message": "Done", "result_subtitle_id": 313,
                 "created_at": "2026-06-12T10:00:00Z", "updated_at": "2026-06-12T10:04:00Z"},
                {"id": 92, "media_file_id": 1048, "kind": "transcribe", "source_index": 0,
                 "source_language": "", "target_language": "", "engine": "openai",
                 "model": "whisper-1", "status": "failed", "progress": 0.4,
                 "progress_message": "", "result_subtitle_id": null,
                 "error_message": "audio extraction failed",
                 "created_at": "2026-06-12T11:00:00Z", "updated_at": "2026-06-12T11:01:00Z"},
                {"id": 93, "media_file_id": 1048, "kind": "transcribe_translate", "source_index": 1,
                 "source_language": "", "target_language": "nl", "engine": "openai",
                 "model": "whisper-1", "status": "running", "progress": 0.25,
                 "progress_message": "Transcribing audio", "result_subtitle_id": null,
                 "created_at": "2026-06-12T11:30:00Z", "updated_at": "2026-06-12T11:31:00Z"}
              ]
            }
        """.trimIndent()

        val jobs = json.decodeFromString(SubtitleAiJobsResponse.serializer(), payload).jobs

        assertEquals(3, jobs.size)
        assertEquals(313, jobs[0].resultSubtitleId)
        assertTrue(jobs[0].isTerminal)
        assertEquals("audio extraction failed", jobs[1].errorMessage)
        assertTrue(jobs[1].isTerminal)
        assertEquals(SubtitleAiJobKind.TranscribeTranslate, jobs[2].kind)
        assertEquals(SubtitleAiJobStatus.Running, jobs[2].status)
        assertEquals(0.25, jobs[2].progress)
        assertFalse(jobs[2].isTerminal)
    }

    @Test
    fun `encodes search request with wire names`() {
        val encoded = json.encodeToString(
            SubtitleSearchRequest.serializer(),
            SubtitleSearchRequest(mediaFileId = 1048, languages = listOf("en", "nl")),
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("media_file_id", "languages"), obj.keys)
    }

    @Test
    fun `encodes download request echoing all search result fields`() {
        val encoded = json.encodeToString(
            SubtitleDownloadRequest.serializer(),
            SubtitleDownloadRequest(
                mediaFileId = 1048,
                provider = SubtitleProvider.OpenSubtitles,
                subtitleId = "os-9182736",
                language = "en",
                releaseName = "Dune.Part.Three.2026.2160p.WEB-DL.DDP5.1-FLUX",
                format = "srt",
                score = 91.5,
                hearingImpaired = false,
            ),
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf(
                "media_file_id", "provider", "subtitle_id", "language",
                "release_name", "format", "score", "hearing_impaired",
            ),
            obj.keys,
        )
    }

    @Test
    fun `translate request omits nulls including a missing session_id`() {
        val encoded = json.encodeToString(
            SubtitleTranslateRequest.serializer(),
            SubtitleTranslateRequest(
                mediaFileId = 1048,
                sourceIndex = 2,
                targetLanguage = "nl",
                startPosition = 1234.5,
            ),
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertFalse("session_id" in obj.keys)
        assertFalse("source_language" in obj.keys)  // null → omitted (explicitNulls=false)
        assertEquals(
            setOf("media_file_id", "kind", "source_index", "target_language", "start_position"),
            obj.keys,
        )
    }

    @Test
    fun `plain transcribe request omits target language`() {
        val encoded = json.encodeToString(
            SubtitleTranslateRequest.serializer(),
            SubtitleTranslateRequest(
                mediaFileId = 1048,
                kind = SubtitleAiJobKind.Transcribe,
                sourceIndex = 1,  // audio track index for ASR kinds
            ),
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertFalse("target_language" in obj.keys)
        assertFalse("start_position" in obj.keys)
        assertEquals(setOf("media_file_id", "kind", "source_index"), obj.keys)
    }
}
