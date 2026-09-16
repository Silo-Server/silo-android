package org.siloserver.silo.network

import io.ktor.http.Url
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Resolve against the supplying response's origin without re-encoding signed paths or queries. */
fun resolveArtworkUrl(value: String, serverUrl: String): String {
    if (!value.startsWith("/") || value.startsWith("//")) return value
    val server = Url(serverUrl)
    require(server.protocol.name == "http" || server.protocol.name == "https")
    val host = if (':' in server.host) "[${server.host.removeSurrounding("[", "]")}]" else server.host
    val port = if (server.port == server.protocol.defaultPort) "" else ":${server.port}"
    return "${server.protocol.name}://$host$port$value"
}

// Resolve wire artwork before caching or projecting it into phone, TV, Cast, or
// media-session models. Never consult the active server when rendering old data.
// TMDB poster_path/backdrop_path and non-artwork URLs deliberately stay untouched.
internal fun JsonElement.resolveArtworkUrls(serverUrl: String): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (key, value) ->
        when {
            key in artworkUrlFields && value is JsonPrimitive && value.isString ->
                JsonPrimitive(resolveArtworkUrl(value.content, serverUrl))
            key == "poster_urls" && value is JsonArray -> JsonArray(value.map { url ->
                if (url is JsonPrimitive && url.isString) JsonPrimitive(resolveArtworkUrl(url.content, serverUrl)) else url
            })
            else -> value.resolveArtworkUrls(serverUrl)
        }
    })
    is JsonArray -> JsonArray(map { it.resolveArtworkUrls(serverUrl) })
    else -> this
}

private val artworkUrlFields = setOf("poster_url", "backdrop_url", "logo_url", "avatar_url", "photo_url", "still_url", "thumbnail_url")
