package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.content.TextContent
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.model.section.SectionOverride
import org.siloserver.silo.network.*

/**
 * The acting profile's Home layout on API v2 (`/api/v2/profile/sections`,
 * `scope=home`): the rows as the profile sees them, its saved overrides, and
 * the whole-set replace. The same overrides drive Home on every
 * device and the admin's per-profile Home sections card. Every call is pinned
 * to the profile captured before it and refused when that profile changed.
 */
class ProfileSectionsV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) {
    @Serializable
    private data class OverrideSet(val overrides: List<SectionOverride>)

    suspend fun capture(): AuthScopeSnapshot? = tokens.captureProfileScope()

    /** GET /api/v2/profile/sections/settings?scope=home: Home rows in profile order, hidden rows included. */
    suspend fun homeSettings(owner: AuthScopeSnapshot): ApiResult<List<ProfileSectionSetting>> =
        exchange<JsonObject, List<ProfileSectionSetting>>(owner, HttpStatusCode.OK, {
            client.get("/api/v2/profile/sections/settings") { scoped(owner) }
        }) { body -> items(body, ProfileSectionSetting.serializer()).also { rows -> require(rows.all { it.id.isNotBlank() }) } }

    /** GET /api/v2/profile/sections?scope=home: the profile's saved Home overrides. */
    suspend fun homeOverrides(owner: AuthScopeSnapshot): ApiResult<List<SectionOverride>> =
        exchange<JsonObject, List<SectionOverride>>(owner, HttpStatusCode.OK, {
            client.get("/api/v2/profile/sections") { scoped(owner) }
        }) { body -> items(body, SectionOverride.serializer()) }

    /** PUT /api/v2/profile/sections?scope=home: replaces the whole Home override set. */
    suspend fun replaceHome(owner: AuthScopeSnapshot, overrides: List<SectionOverride>): ApiResult<Unit> =
        exchange<Unit, Unit>(owner, HttpStatusCode.NoContent, {
            client.put("/api/v2/profile/sections") {
                scoped(owner)
                setBody(TextContent(SiloJson.encodeToString(OverrideSet.serializer(), OverrideSet(overrides)), ContentType.Application.Json))
            }
        }) { }

    private suspend inline fun <reified T, R> exchange(
        owner: AuthScopeSnapshot,
        status: HttpStatusCode,
        crossinline block: suspend () -> HttpResponse,
        crossinline project: (T) -> R,
    ): ApiResult<R> = ownedV2Call<T, R>(gate, tokens, owner, OwnerPolicy.PROFILE, status, { block() }, project)

    private fun HttpRequestBuilder.scoped(owner: AuthScopeSnapshot) {
        authScope(owner)
        requireSiloAuth()
        url { parameters.append("scope", "home") }
    }

    private fun <T> items(body: JsonObject, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val rows = requireNotNull(body["items"] as? JsonArray) { "Missing items." }
        return SiloJson.decodeFromJsonElement(ListSerializer(serializer), rows)
    }
}
