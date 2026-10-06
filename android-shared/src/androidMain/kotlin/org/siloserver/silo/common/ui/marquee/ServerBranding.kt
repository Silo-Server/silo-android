package org.siloserver.silo.common.ui.marquee

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.BrandingApi
import org.siloserver.silo.network.api.BrandingStatus
import java.net.URI

/**
 * A server's public branding (`GET /api/v2/theme/branding`) with its asset
 * paths resolved against the server. Read before sign-in, so the first-run
 * screens can name the server and tint the backdrop to its accent.
 */
@Serializable
data class ServerBranding(
    val serverName: String? = null,
    val loginSubtitle: String? = null,
    val accentColor: String? = null,
    val markUrl: String? = null,
    val wordmarkUrl: String? = null,
) {
    companion object {
        fun from(document: BrandingStatus, serverUrl: String) = ServerBranding(
            serverName = document.serverName.usable(),
            loginSubtitle = document.loginSubtitle.usable(),
            accentColor = document.accentColor.usable(),
            markUrl = resolveAsset(document.markUrl, serverUrl),
            wordmarkUrl = resolveAsset(document.wordmarkUrl, serverUrl),
        )

        /**
         * Server asset paths are site-relative. Only same-origin http(s) URLs
         * are accepted, so branding can't point the app at another host.
         */
        fun resolveAsset(value: String?, serverUrl: String): String? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val base = runCatching { URI(serverUrl.trimEnd('/') + "/") }.getOrNull() ?: return null
            val resolved = runCatching { base.resolve(raw.removePrefix("/")) }.getOrNull() ?: return null
            val origin = origin(resolved) ?: return null
            return if (origin == origin(base)) resolved.toString() else null
        }

        /** Scheme, host and port with the default port filled in, so `https://h` and `https://h:443` match. */
        private fun origin(uri: URI): String? {
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = uri.port.takeIf { it > 0 } ?: when (scheme) {
                "https" -> 443
                "http" -> 80
                else -> return null
            }
            return "$scheme://$host:$port"
        }

        /** `host` or `host:port`, for a server's address line. */
        fun hostLabel(serverUrl: String): String {
            val uri = runCatching { URI(serverUrl) }.getOrNull() ?: return serverUrl
            val host = uri.host ?: return serverUrl
            return if (uri.port > 0) "$host:${uri.port}" else host
        }

        private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The last branding each server showed, so returning to sign-in or the
 * profile picker shows that server's name and accent at once instead of
 * flashing the generic look first.
 */
class ServerBrandingCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val serializer = MapSerializer(String.serializer(), ServerBranding.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun branding(serverUrl: String): ServerBranding? = readAll()[key(serverUrl)]

    fun store(serverUrl: String, branding: ServerBranding) {
        val all = readAll().toMutableMap()
        all[key(serverUrl)] = branding
        prefs.edit().putString(KEY, json.encodeToString(serializer, all)).apply()
    }

    private fun readAll(): Map<String, ServerBranding> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    private fun key(serverUrl: String) = AndroidServerRegistry.normalizeUrl(serverUrl)

    private companion object {
        const val PREFS = "marquee_server_branding"
        const val KEY = "branding.v1"
    }
}

/**
 * Reads a server's branding and saves it. A failure is no branding: the
 * screens fall back to the plain brand light and the saved name.
 */
class ServerBrandingLoader(
    private val api: BrandingApi,
    val cache: ServerBrandingCache,
) {
    suspend fun load(serverUrl: String): ServerBranding? {
        if (serverUrl.isBlank()) return null
        val document = (api.getBranding(serverUrl) as? ApiResult.Success)?.data ?: return null
        return ServerBranding.from(document, serverUrl).also { cache.store(serverUrl, it) }
    }
}
