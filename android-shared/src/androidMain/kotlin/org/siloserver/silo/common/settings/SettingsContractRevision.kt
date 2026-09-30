package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The settings manifest revision of the connected server, read from the
 * contract capabilities endpoint and cached per server URL.
 *
 * The settings flusher asks for it before sending a write whose shape depends
 * on the revision, and the settings UI hides controls the server would
 * discard. Both share one instance so they agree on what the server supports.
 */
class SettingsContractRevision(
    private val fetchCapabilities: suspend () -> ApiResult<SettingsContractCapabilities>,
    /** The server requests currently address; null when it cannot be read. */
    private val getServerUrl: suspend () -> String? = { null },
) {
    data class Known(val serverUrl: String, val manifestRevision: Int)

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

    private fun cachedFor(serverUrl: String): Int? =
        _known.value?.takeIf { it.serverUrl == serverUrl }?.manifestRevision

    private suspend fun probe(serverUrl: String): Int? {
        if (!isActive(serverUrl)) return null
        val revision = when (val result = fetchCapabilities()) {
            is ApiResult.Success -> result.data.manifestRevision
            // No capabilities route: the server predates every revision gate.
            is ApiResult.Error -> if (result.code == 404) 0 else null
            is ApiResult.NetworkError -> null
        } ?: return null
        // The request goes to whichever server is active when it is sent, so a
        // switch during the fetch means the answer describes another server.
        if (!isActive(serverUrl)) return null
        _known.value = Known(serverUrl, revision)
        return revision
    }

    private suspend fun isActive(serverUrl: String): Boolean {
        val active = runCatching { getServerUrl() }.getOrNull()
        return active == null || active == serverUrl
    }
}
