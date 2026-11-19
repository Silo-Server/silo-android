package org.siloserver.silo.model.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
 *
 * Accounts live in [AccountEntry]. [accountId] and [profileId] are not stored
 * here: the registry fills them from the server's current account (the active
 * account on the active server, otherwise the server's most recently used one)
 * so code that watches a server entry also sees account and profile switches.
 */
@Serializable
data class ServerEntry(
    val id: String,
    val url: String,
    val fetchedName: String? = null,
    val userOverrideName: String? = null,
    /** Selected profile of [accountId]; derived, never persisted. */
    @Transient val profileId: String? = null,
    val lastUsedAtEpochMs: Long = 0L,
    /** Native API contract learned on connect; see [ServerContract]. */
    val contract: ServerContract = ServerContract.UNKNOWN,
    /**
     * Deployment identity (`GET /api/v2/system/identity`) last verified at
     * [url]. One deployment answers the same id at every address, so this —
     * not URL spelling — is how a TV's advertisement or a `silo://device`
     * link is matched to a saved server. Null until verified.
     */
    val verifiedServerId: String? = null,
    /**
     * Local data written before accounts existed that no account has adopted
     * yet: the server had no session when accounts were introduced. The next
     * account to sign in here adopts it ([AccountEntry.adoptsLegacyData]).
     */
    val legacyDataUnclaimed: Boolean = false,
    /** The server's current account ([AccountEntry.localAccountId]); derived, never persisted. */
    @Transient val accountId: String? = null,
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
