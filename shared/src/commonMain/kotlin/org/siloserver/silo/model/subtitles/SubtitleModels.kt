// shared/src/commonMain/kotlin/org/siloserver/silo/model/subtitles/SubtitleModels.kt
package org.siloserver.silo.model.subtitles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Provider ids the server may return for search results / downloaded subtitles. */
object SubtitleProvider {
    const val OpenSubtitles = "opensubtitles"
    const val Subdl = "subdl"
    const val Subsource = "subsource"
}

/** `kind` values for POST /api/v2/subtitles/ai/translate. */
object SubtitleAiJobKind {
    const val Translate = "translate"
    const val Transcribe = "transcribe"
    const val TranscribeTranslate = "transcribe_translate"
}

/** Job lifecycle statuses (internal/subtitles/ai/job.go). */
object SubtitleAiJobStatus {
    const val Pending = "pending"
    const val Running = "running"
    const val Completed = "completed"
    const val Failed = "failed"
    const val Cancelled = "cancelled"
}

/** Body for POST /api/v2/subtitles/search. */
@Serializable
data class SubtitleSearchRequest(
    @SerialName("media_file_id") val mediaFileId: Int,
    val languages: List<String>,
)

/** One provider hit. `id` is a provider-scoped string, echoed back on download. */
@Serializable
data class SubtitleResult(
    val id: String,
    val provider: String,
    val language: String,
    @SerialName("release_name") val releaseName: String,
    val format: String,
    /** Match score 0–100. */
    val score: Double = 0.0,
    val downloads: Int = 0,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    @SerialName("upload_date") val uploadDate: String? = null,
)

@Serializable
data class SubtitleSearchResponse(
    val results: List<SubtitleResult> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Body for POST /api/v2/subtitles/download — echoes the chosen [SubtitleResult]. */
@Serializable
data class SubtitleDownloadRequest(
    @SerialName("media_file_id") val mediaFileId: Int,
    val provider: String,
    @SerialName("subtitle_id") val subtitleId: String,
    val language: String,
    @SerialName("release_name") val releaseName: String,
    val format: String,
    val score: Double,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean,
)

/** A subtitle stored server-side, listed by GET /api/v2/subtitles/{media_file_id}. */
@Serializable
data class DownloadedSubtitle(
    val id: Int,
    @SerialName("media_file_id") val mediaFileId: Int,
    val provider: String,
    val language: String,
    val format: String,
    @SerialName("release_name") val releaseName: String,
    val score: Double = 0.0,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

/** Envelope for POST /api/v2/subtitles/download. */
@Serializable
data class SubtitleDownloadResponse(val subtitle: DownloadedSubtitle)

/** Envelope for GET /api/v2/subtitles/{media_file_id}. */
@Serializable
data class DownloadedSubtitlesResponse(
    val subtitles: List<DownloadedSubtitle> = emptyList(),
)

/** GET /api/v2/subtitles/ai/status — both false when AI is unconfigured. */
@Serializable
data class SubtitleAiStatus(
    val enabled: Boolean = false,
    @SerialName("transcribe_enabled") val transcribeEnabled: Boolean = false,
)

/**
 * GET /api/v2/subtitles/ai/quota — transcribe-kind budget. Exempt callers
 * (admins) and disabled quotas get the zero value with `limited = false`;
 * the remaining fields are only meaningful when `limited` is true.
 */
@Serializable
data class SubtitleAiQuota(
    val limited: Boolean = false,
    val limit: Int = 0,
    val used: Int = 0,
    val remaining: Int = 0,
    /** "day" | "week" | "month"; empty when not limited. */
    val period: String = "",
)

/**
 * Input for subtitle AI creation. The v2 adapter emits required strings and a string file ID.
 *
 * Deliberately has NO `session_id` field: the web sends one to receive live
 * cue streaming over the playback websocket; Android polls the job instead
 * (web/src/player/.../usePlaybackSession.ts vs. SubtitlesRepository.pollJob).
 *
 * [sourceIndex] is the combined subtitle index for `translate`, and the audio
 * track index for the transcribe kinds. [targetLanguage] is optional only for
 * a plain `transcribe` (acts as a language hint).
 */
@Serializable
data class SubtitleTranslateRequest(
    @SerialName("media_file_id") val mediaFileId: Int,
    val kind: String = SubtitleAiJobKind.Translate,
    @SerialName("source_index") val sourceIndex: Int,
    @SerialName("source_language") val sourceLanguage: String? = null,
    @SerialName("target_language") val targetLanguage: String? = null,
    @SerialName("start_position") val startPosition: Double? = null,
)

@Serializable
data class SubtitleAiJob(
    val id: Long,
    @SerialName("media_file_id") val mediaFileId: Int,
    val kind: String = SubtitleAiJobKind.Translate,
    @SerialName("source_index") val sourceIndex: Int = 0,
    @SerialName("source_language") val sourceLanguage: String = "",
    @SerialName("target_language") val targetLanguage: String = "",
    val engine: String = "",
    val model: String = "",
    val status: String,
    /** 0.0 .. 1.0 */
    val progress: Double = 0.0,
    @SerialName("progress_message") val progressMessage: String = "",
    @SerialName("result_subtitle_id") val resultSubtitleId: Int? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val isTerminal: Boolean
        get() = status == SubtitleAiJobStatus.Completed ||
            status == SubtitleAiJobStatus.Failed ||
            status == SubtitleAiJobStatus.Cancelled
}

/** Envelope for translate (202) and GET /ai/jobs/{id}. */
@Serializable
data class SubtitleAiJobResponse(
    val job: SubtitleAiJob,
    @SerialName("live_delivery_attached") val liveDeliveryAttached: Boolean = false,
)

/** Envelope for GET /ai/jobs?media_file_id=N. */
@Serializable
data class SubtitleAiJobsResponse(val jobs: List<SubtitleAiJob> = emptyList())
