package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.DeviceSettingRevisions
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The settings manifest revision of the connected server, read from the
 * contract capabilities endpoint and cached per server URL, with the keys its
 * manifest defines.
 *
 * The settings flusher asks for it before sending a write whose shape depends
 * on the revision, and the settings UI hides controls the server would
 * discard. Both share one instance so they agree on what the server supports.
 */
class SettingsContractRevision(
    private val fetchCapabilities: suspend () -> ApiResult<SettingsContractCapabilities>,
    /** The server requests currently address; null when it cannot be read. */
    private val getServerUrl: suspend () -> String? = { null },
    /**
     * The keys the server's manifest defines (`GET /settings/contract`), for
     * [PlaybackSettingsKeys.ManifestDetectedKeys]. Null leaves them unknown.
     */
    private val fetchManifestKeys: (suspend () -> ApiResult<Set<String>>)? = null,
) {
    data class Known(
        val serverUrl: String,
        val manifestRevision: Int,
        /** The keys the manifest defines; null when they could not be read. */
        val manifestKeys: Set<String>?,
        val contractEtag: String,
    )

    private val mutex = Mutex()
    private val _known = MutableStateFlow<Known?>(null)

    /** The last revision a probe answered, with the server that answered it. */
    val known: StateFlow<Known?> = _known.asStateFlow()

    /**
     * The cached revision for [serverUrl], probing when none is cached.
     * Null means unknown: the probe failed, or [serverUrl] is not the server
     * requests would reach, so any answer would describe a different server.
     */
    suspend fun revisionFor(serverUrl: String): Int? {
        cachedFor(serverUrl)?.let { return it }
        return mutex.withLock {
            cachedFor(serverUrl) ?: probe(serverUrl)
        }
    }

    /**
     * Re-read the revision for [serverUrl] even when one is cached, so a
     * server upgraded while the app runs is noticed. A failed probe keeps the
     * cached value.
     */
    suspend fun refresh(serverUrl: String): Int? = mutex.withLock {
        probe(serverUrl) ?: cachedFor(serverUrl)
    }

    /**
     * Whether the server at [serverUrl] stores [key], as last read, or null
     * while that is unknown: by the manifest's keys for
     * [PlaybackSettingsKeys.ManifestDetectedKeys], else by
     * [DeviceSettingRevisions.minimumRevision]. Every other key is stored. A server
     * that lacks a key answers a batched read naming it with 404 for the whole
     * batch and refuses a write as unknown_setting, so the store and the
     * flusher ask for, send, and clear such a key only once it is stored.
     */
    fun stores(serverUrl: String, key: String): Boolean? {
        val known = _known.value?.takeIf { it.serverUrl == serverUrl }
        if (key in PlaybackSettingsKeys.ManifestDetectedKeys) return known?.manifestKeys?.let { key in it }
        val needed = DeviceSettingRevisions.minimumRevision(key) ?: return true
        return known?.let { it.manifestRevision >= needed }
    }

    /**
     * Whether the server at [serverUrl] accepts [key] at profile scope, as
     * last read, or null while that is unknown. Only a key whose profile scope
     * a later revision added ([PlaybackSettingsKeys.profileScopeMinimumRevision])
     * depends on the revision; below it the server stores the key per device
     * only and refuses `scope=profile`.
     */
    fun storesPerProfile(serverUrl: String, key: String): Boolean? {
        val needed = PlaybackSettingsKeys.profileScopeMinimumRevision(key) ?: return true
        return cachedFor(serverUrl)?.let { it >= needed }
    }

    /** [storesPerProfile], probing when it is not known yet. */
    suspend fun storesPerProfileKey(serverUrl: String, key: String): Boolean? {
        val needed = PlaybackSettingsKeys.profileScopeMinimumRevision(key) ?: return true
        return revisionFor(serverUrl)?.let { it >= needed }
    }

    /** [stores], probing when it is not known yet. */
    suspend fun storesKey(serverUrl: String, key: String): Boolean? {
        stores(serverUrl, key)?.let { return it }
        return mutex.withLock {
            stores(serverUrl, key) ?: run {
                probe(serverUrl)
                stores(serverUrl, key)
            }
        }
    }

    private fun cachedFor(serverUrl: String): Int? =
        _known.value?.takeIf { it.serverUrl == serverUrl }?.manifestRevision

    private suspend fun probe(serverUrl: String): Int? {
        if (!isActive(serverUrl)) return null
        val capabilities = when (val result = fetchCapabilities()) {
            is ApiResult.Success -> result.data
            // No capabilities route: the server predates every revision gate.
            is ApiResult.Error -> if (result.code == 404) null else return null
            is ApiResult.NetworkError -> return null
        }
        val revision = capabilities?.manifestRevision ?: 0
        val etag = capabilities?.contractEtag.orEmpty()
        val keys = if (capabilities == null) emptySet() else manifestKeys(serverUrl, revision, etag)
        // The request goes to whichever server is active when it is sent, so a
        // switch during the fetch means the answer describes another server.
        if (!isActive(serverUrl)) return null
        _known.value = Known(serverUrl, revision, keys, etag)
        return revision
    }

    /** The keys already read for this server's contract, else read now; a manifest is fixed per contract. */
    private suspend fun manifestKeys(serverUrl: String, revision: Int, etag: String): Set<String>? {
        _known.value
            ?.takeIf { it.serverUrl == serverUrl && it.manifestRevision == revision && it.contractEtag == etag }
            ?.manifestKeys
            ?.let { return it }
        val fetch = fetchManifestKeys ?: return null
        return when (val result = fetch()) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> if (result.code == 404) emptySet() else null
            is ApiResult.NetworkError -> null
        }
    }

    private suspend fun isActive(serverUrl: String): Boolean {
        val active = runCatching { getServerUrl() }.getOrNull()
        return active == null || active == serverUrl
    }
}
