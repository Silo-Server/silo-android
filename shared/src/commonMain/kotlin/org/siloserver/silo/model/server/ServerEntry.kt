package org.siloserver.silo.model.server

import kotlinx.serialization.Serializable

/**
 * One saved server in the multi-server registry.
 *
 * Mirrors the Swift `ServerEntry` shipped on iOS/tvOS. The [id] is a stable,
 * opaque token derived from the normalized URL — two URLs that normalize to
 * the same value share an id, so re-adding the same server upserts rather
 * than duplicates.
 *
 * Names: [fetchedName] is the server's native branding identity, with the
 * legacy health name used only when branding is unavailable;
 * [userOverrideName] is whatever the user types in the rename dialog and
 * always wins when both are present.
 */
@Serializable
data class ServerEntry(
    val id: String,
    val url: String,
    val fetchedName: String? = null,
    val userOverrideName: String? = null,
    val profileId: String? = null,
    val lastUsedAtEpochMs: Long = 0L,
) {
    val displayName: String
        get() = userOverrideName?.takeIf { it.isNotBlank() }
            ?: fetchedName?.takeIf { it.isNotBlank() }
            ?: hostFromUrl(url)
            ?: url
}

private fun hostFromUrl(url: String): String? {
    val withoutScheme = url.substringAfter("://", missingDelimiterValue = url)
    val hostWithPort = withoutScheme.substringBefore('/')
    return hostWithPort.takeIf { it.isNotBlank() }
}
