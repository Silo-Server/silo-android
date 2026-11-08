package org.siloserver.silo.model.feature

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.RequestsRepository

/**
 * Server-gated media requests capability.
 *
 * Matches the Apple clients: entry points start hidden, a successful
 * `/api/v2/requests/status` probe controls visibility, transient failures keep
 * the previous value, and reset hides the surface before a server/profile switch
 * can reuse stale capability state. Reset also clears [RequestsRepository]'s
 * session state, since both shells call it on sign-in, sign-out, and every
 * server or profile switch. The feature counts only when the server
 * says this profile is `allowed` and the state is `available`.
 *
 * With a [scope], a requests call the server refuses as requests-off
 * ([RequestsRepository.capabilityRefusals]) probes again, so an app that stays
 * in the foreground still sees the change.
 *
 * Request administration (approve, decline, retry, limits) stays in web admin:
 * the native clients never probe or expose it.
 */
class RequestsFeatureStore(
    private val repository: RequestsRepository,
    scope: CoroutineScope? = null,
) {
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isResolved = MutableStateFlow(false)
    /** A probe has answered (or failed) since the last [reset]; until then [isEnabled] is only a default. */
    val isResolved: StateFlow<Boolean> = _isResolved.asStateFlow()

    private val _isKnownUnavailable = MutableStateFlow(false)
    /**
     * The server answered, since the last [reset], that requests are not
     * available to this profile. Open Requests screens close on this, never on
     * the default before the first answer or on a failed probe.
     */
    val isKnownUnavailable: StateFlow<Boolean> = _isKnownUnavailable.asStateFlow()

    private val _followSupported = MutableStateFlow(false)
    /** The server lets a viewer follow a title someone else requested (`follow_supported`). */
    val followSupported: StateFlow<Boolean> = _followSupported.asStateFlow()

    private val _removeFailedSupported = MutableStateFlow(false)
    /** The server lets a viewer remove their own failed request (`remove_failed_supported`). */
    val removeFailedSupported: StateFlow<Boolean> = _removeFailedSupported.asStateFlow()

    // Written on the UI dispatcher (reset) and read after IO resumption
    // (refresh); Volatile gives the stale-response guard a happens-before.
    @kotlin.concurrent.Volatile
    private var generation: Int = 0

    // Probes are numbered as they start, and an answer counts only if no newer
    // probe has answered yet: an older "available" can't undo a newer
    // "disabled", and a newer probe that fails doesn't discard an older answer.
    private val probeOrder = Mutex()
    private var probesStarted = 0
    private var newestAnswer = 0

    /** The [generation] of the latest probe to start, so a session's first probe counts while it is out. */
    @kotlin.concurrent.Volatile
    private var probedGeneration: Int = -1

    init {
        scope?.launch {
            repository.capabilityRefusals.collect {
                // Probe even while the shell's own probe is out: it may have
                // read the status before requests were turned off, and the
                // newer probe's answer wins.
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed probe keeps the previous answer; the next refusal or foreground retries.
                }
            }
        }
    }

    suspend fun refresh() {
        val refreshGeneration = generation
        val ordinal = probeOrder.withLock {
            probedGeneration = refreshGeneration
            ++probesStarted
        }
        val status = repository.status()
        probeOrder.withLock {
            if (refreshGeneration != generation) return
            if (status !is ApiResult.Success) {
                // A transient failure shouldn't yank an already-visible entry point.
                _isResolved.value = true
                return
            }
            if (ordinal <= newestAnswer) return
            newestAnswer = ordinal
            val enabled = status.data.isAvailable
            // Follow and remove support first, so nothing that watches isEnabled reads a stale value of them.
            _followSupported.value = enabled && status.data.followSupported
            _removeFailedSupported.value = enabled && status.data.removeFailedSupported
            _isEnabled.value = enabled
            _isKnownUnavailable.value = !enabled
            _isResolved.value = true
        }
    }

    /**
     * Re-probe when the app returns to the foreground, so an admin turning
     * requests off (or on) while the app was in the background shows up
     * without a restart, as on Apple. Skipped until the shell has started its
     * own probe for this session: before that, nobody is signed in or a server
     * or profile switch is in flight, and the shell's probe will answer. Once
     * that probe has started it runs even while the probe is still out, since
     * the probe may have read the status before requests were turned off, and
     * the newer answer wins.
     */
    suspend fun refreshAfterReturnToForeground() {
        if (!_isResolved.value && probedGeneration != generation) return
        refresh()
    }

    fun reset() {
        generation += 1
        repository.reset()
        _isEnabled.value = false
        _isKnownUnavailable.value = false
        _followSupported.value = false
        _removeFailedSupported.value = false
        _isResolved.value = false
    }
}
