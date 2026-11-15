package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.section.SectionsResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.HomeSectionItemsResponse
import org.siloserver.silo.model.section.HomeLayoutRow
import org.siloserver.silo.network.*

/** One v2 section row: `items` is required on the wire even though the domain model defaults it. */
internal fun decodeSectionV2(row: JsonObject): ResolvedSection {
    require(row["items"] is JsonArray) { "Missing section items." }
    val section = SiloJson.decodeFromJsonElement<ResolvedSection>(row)
    require(section.id.isNotBlank() && section.items.all { it.contentId.isNotBlank() }) { "Blank section identity." }
    return section
}

internal fun decodeSectionsV2(body: JsonObject): SectionsResponse {
    val rows = body["sections"] as? JsonArray ?: throw IllegalArgumentException("Missing sections.")
    return SectionsResponse(rows.map { decodeSectionV2(it.jsonObject) })
}

/** `GET /api/v2/home/layout` without `include_items`: each row's id and type, no cards. */
internal fun decodeHomeLayoutV2(body: JsonObject): List<HomeLayoutRow> {
    val rows = body["sections"] as? JsonArray ?: throw IllegalArgumentException("Missing sections.")
    return rows.map { element ->
        val row = element.jsonObject
        val id = (row["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val type = (row["section_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(!id.isNullOrBlank() && !type.isNullOrBlank()) { "Blank section identity." }
        HomeLayoutRow(id, type)
    }
}

/**
 * The error a section list answers when the server rejected `item_limit` as
 * an unknown query parameter (an older server that advertised it wrongly or
 * was downgraded). The caller drops the parameter and reads again.
 */
const val ITEM_LIMIT_UNSUPPORTED = "item_limit_unsupported"

/** Whether this is v2's 422 `validation_failed` naming `query.<name>` as an `unknown_parameter`. */
internal suspend fun HttpResponse.rejectsQueryParameter(name: String): Boolean {
    if (status != HttpStatusCode.UnprocessableEntity) return false
    // Non-streaming requests keep their body, so the call still reads it as a problem.
    val problem = runCatching {
        SiloJson.decodeFromString(Problem.serializer(), bodyAsText())
    }.getOrNull() ?: return false
    return problem.code == "validation_failed" &&
        problem.errors.any { it.location == "query.$name" && it.code == "unknown_parameter" }
}

/** Re-tags [result] as [ITEM_LIMIT_UNSUPPORTED] when the server refused the parameter. */
internal fun <T> ApiResult<T>.tagItemLimitRejection(rejected: Boolean): ApiResult<T> =
    if (rejected && this is ApiResult.Error) copy(error = ITEM_LIMIT_UNSUPPORTED) else this

/** The part of `getHomeCapabilities` this client reads. */
@Serializable
internal data class HomeCapabilitiesV2(
    /** Largest `item_limit` the section lists accept; absent on servers without the parameter. */
    @SerialName("section_item_limit_max") val sectionItemLimitMax: Int? = null,
)

class HomeSectionsV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
    /**
     * Where Home responses are read and decoded. With 100-item rows the full
     * Home response is over 1 MB, which takes about a second to read and decode
     * on a low-end TV, so it must not run on the caller's (often the main) thread.
     */
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.captureProfileScope()
    suspend fun current(owner: AuthScopeSnapshot): Boolean = owner.stillOwns(tokens, OwnerPolicy.FULL)
    suspend fun dismiss(surface: String, itemId: String, anchor: String, owner: AuthScopeSnapshot): ApiResult<Unit> {
        if (surface !in setOf("continue_watching", "next_up") || itemId.isBlank() || anchor.isBlank())
            return ApiResult.Error(422, "validation_failed", "A Home dismissal needs its observed item and anchor.")
        return ownedV2Call<Unit, Unit>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.NoContent, { scope ->
            client.put("/api/v2/home/dismissals/$surface/${itemId.encodeURLPathPart()}") {
                authScope(scope!!)
                requireSiloAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put(if (surface == "continue_watching") "progress_updated_at" else "series_id", anchor) })
            }
        }) { }
    }

    /** `section_item_limit_max` from `GET /api/v2/home/capabilities`; null when the server has none. */
    suspend fun sectionItemLimitMax(owner: AuthScopeSnapshot): ApiResult<Int?> =
        ownedV2Call<HomeCapabilitiesV2, Int?>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/home/capabilities") { authScope(scope!!); requireSiloAuth() }
        }) { it.sectionItemLimitMax }

    /** [itemLimit] is sent only when the server advertised it; see [sectionItemLimitParam]. */
    suspend fun list(owner: AuthScopeSnapshot, widths: ArtworkWidths? = null, itemLimit: Int? = null): ApiResult<SectionsResponse> = withContext(decodeDispatcher) {
        var rejected = false
        ownedV2Call<JsonObject, SectionsResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/home/sections") {
                authScope(scope!!); requireSiloAuth(); artworkWidths(widths, scope)
                itemLimit?.let { parameter("item_limit", it) }
            }.also { rejected = itemLimit != null && it.rejectsQueryParameter("item_limit") }
        }) { decodeSectionsV2(it) }.tagItemLimitRejection(rejected)
    }

    /**
     * Home's rows without their cards. A few hundred bytes where [list] can
     * answer over 1 MB, so background work that needs only some rows reads
     * this and then [section] for each row it wants.
     */
    suspend fun layout(owner: AuthScopeSnapshot): ApiResult<List<HomeLayoutRow>> = withContext(decodeDispatcher) {
        ownedV2Call<JsonObject, List<HomeLayoutRow>>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/home/layout") { authScope(scope!!); requireSiloAuth() }
        }) { decodeHomeLayoutV2(it) }
    }

    suspend fun section(id: String, owner: AuthScopeSnapshot, widths: ArtworkWidths? = null): ApiResult<HomeSectionItemsResponse> {
        if (id.isBlank()) return ApiResult.Error(422, "validation_failed", "Invalid home section.")
        return withContext(decodeDispatcher) {
            ownedV2Call<JsonObject, HomeSectionItemsResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
                client.get("/api/v2/home/sections/${id.encodeURLPathPart()}/items") {
                    authScope(scope!!); requireSiloAuth(); artworkWidths(widths, scope)
                }
            }) { row ->
                val section = decodeSectionV2(row)
                require(section.id == id) { "Wrong section identity." }
                HomeSectionItemsResponse(section, section.items)
            }
        }
    }
}
