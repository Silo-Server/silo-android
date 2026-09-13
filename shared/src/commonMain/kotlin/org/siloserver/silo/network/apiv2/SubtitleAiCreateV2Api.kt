package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*

@Serializable
private data class AiCreationReceipt(
    val job: AiJobV2,
    @SerialName("live_delivery_attached") val liveDeliveryAttached: Boolean,
)

/** One-send foreground creation. Android polls persisted jobs instead of requesting live cues. */
class SubtitleAiCreateV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) {
    // Process-local fence, not an offline queue. A changed playhead/dialog must
    // not turn a lost creation receipt into a new request for the same source.
    private data class Intent(val owner: AuthScopeSnapshot, val file: Int, val kind: String,
        val source: Int, val sourceLanguage: String, val targetLanguage: String)
    private val mutex = Mutex()
    private val unresolved = mutableSetOf<Intent>()

    suspend fun create(request: SubtitleTranslateRequest, expected: AuthScopeSnapshot? = null): ApiResult<SubtitleAiJobResponse> {
        val owner = expected ?: tokens.snapshotCurrentScope() ?: return identityChanged()
        if (!owner.stillOwns(tokens, OwnerPolicy.PROFILE)) return identityChanged()
        val position = request.startPosition
        if (request.mediaFileId <= 0 || request.kind !in setOf("translate", "transcribe", "transcribe_translate") ||
            request.sourceIndex < -1 || position == null || !position.isFinite() || position < 0) {
            return ApiResult.Error(0, "invalid_subtitle_request", "The subtitle source or playback position is invalid.")
        }
        val intent = Intent(owner, request.mediaFileId, request.kind, request.sourceIndex,
            request.sourceLanguage.orEmpty(), request.targetLanguage.orEmpty())
        if (!mutex.withLock { unresolved.add(intent) }) return ApiResult.Error(0, "subtitle_creation_uncertain",
            "This subtitle request is pending or may have started. It has not been sent again.")
        var settled = false
        try {
            val result = ownedV2Call<AiCreationReceipt, SubtitleAiJobResponse>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.Accepted, { pinned ->
                client.post("/api/v2/subtitles/ai/translate") {
                    authScope(pinned!!); requireSiloAuth(); singleAttempt()
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("media_file_id", request.mediaFileId.toString())
                        put("kind", request.kind)
                        put("source_index", request.sourceIndex)
                        put("source_language", request.sourceLanguage.orEmpty())
                        put("target_language", request.targetLanguage.orEmpty())
                        put("start_position", position)
                    })
                }
            }) { receipt ->
                val job = receipt.job.project()
                require(job.mediaFileId == request.mediaFileId && job.kind == request.kind && job.sourceIndex == request.sourceIndex) {
                    "The server returned an unsupported subtitle creation receipt."
                }
                SubtitleAiJobResponse(job, receipt.liveDeliveryAttached)
            }
            return when (result) {
                is ApiResult.Success -> { settled = true; result }
                is ApiResult.Error -> {
                    // Definite request refusals can be corrected by a new user
                    // decision. Timeout, server errors and malformed replies cannot.
                    settled = result.code in 400..499 && result.code != 408
                    result
                }
                is ApiResult.NetworkError -> ApiResult.Error(0, "subtitle_creation_uncertain", "The request may have started. It has not been sent again.")
            }
        } finally {
            if (settled) withContext(NonCancellable) { mutex.withLock { unresolved.remove(intent) } }
        }
    }
}
