package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.*

/** `GET /api/v2/catalog/themes/capabilities`. `revision` is served but never read. */
@Serializable
data class ThemeSongsCapabilityV2(
    val state: String,
    val allowed: Boolean? = null,
    /** `routed` (audio may come from a proxy origin) or `local_direct_play`. */
    val delivery: String? = null,
    /** Whether the server can convert a theme the client cannot decode. */
    val transcode: Boolean = false,
    @SerialName("cluster_routing") val clusterRouting: Boolean = false,
    @SerialName("grant_lifetime_seconds") val grantLifetimeSeconds: Int? = null,
) {
    /** Themes play only when the server serves them and lets this login use them. */
    val isAvailable: Boolean get() = state == "available" && allowed != false
}

/** One container and codec the client decodes; an empty codec accepts any codec in the container. */
@Serializable
data class ThemeAudioFormatV2(
    val container: String,
    @SerialName("audio_codec") val audioCodec: String = "",
)

@Serializable
private data class ThemePlaybackRequestV2(
    @SerialName("accepted_formats") val acceptedFormats: List<ThemeAudioFormatV2>,
)

/**
 * A short-lived grant to play one theme. [url] is a credential: never log or
 * persist it, so [toString] leaves it out.
 */
@Serializable
data class ThemePlaybackGrantV2(
    val url: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    /** `original`, or `converted`: progressive AAC with no length or byte ranges. */
    val delivery: String? = null,
    @SerialName("content_type") val contentType: String? = null,
) {
    /** A converted stream cannot seek back to its start; replay it with a new grant. */
    val isConverted: Boolean get() = delivery == "converted"

    override fun toString(): String = "ThemePlaybackGrantV2(delivery=$delivery, contentType=$contentType, expiresAt=$expiresAt)"
}

/** Local theme songs (`docs/catalog-api.md`, "Local theme songs, V2"). */
class ThemeMusicV2Api(private val client: HttpClient, private val tokens: TokenManager?, private val gate: ApiV2Gate) {

    suspend fun capability(scope: AuthScopeSnapshot? = null): ApiResult<ThemeSongsCapabilityV2> {
        val owner = scope ?: tokens?.captureProfileScope() ?: return identityChanged()
        return ownedV2Call<ThemeSongsCapabilityV2, ThemeSongsCapabilityV2>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.OK, { pinned ->
            client.get("/api/v2/catalog/themes/capabilities") { authScope(pinned!!); requireSiloAuth() }
        }) { it }
    }

    /**
     * Authorizes [themeId] of [ownerId] for [scope]'s profile. The server sends
     * the original when it matches [accepted], else an AAC conversion when
     * [accepted] takes `m4a`/`mp4` AAC. A relative `url` is resolved against
     * the server that answered.
     */
    suspend fun grant(
        ownerId: String,
        themeId: String,
        accepted: List<ThemeAudioFormatV2>,
        scope: AuthScopeSnapshot,
    ): ApiResult<ThemePlaybackGrantV2> {
        var requestUrl: Url? = null
        return ownedV2Call<ThemePlaybackGrantV2, ThemePlaybackGrantV2>(gate, tokens, scope, OwnerPolicy.PROFILE, HttpStatusCode.OK, { pinned ->
            client.post("/api/v2/catalog/items/${ownerId.encodeURLPathPart()}/themes/${themeId.encodeURLPathPart()}/playback") {
                authScope(pinned!!); requireSiloAuth()
                if (accepted.isNotEmpty()) {
                    contentType(ContentType.Application.Json)
                    setBody(ThemePlaybackRequestV2(accepted))
                }
            }.also { requestUrl = it.call.request.url }
        }) { grant ->
            require(grant.url.isNotBlank()) { "The server returned a theme grant without a URL." }
            val base = requestUrl
            if (base == null) grant else grant.copy(url = URLBuilder(base).takeFrom(grant.url).buildString())
        }
    }
}
