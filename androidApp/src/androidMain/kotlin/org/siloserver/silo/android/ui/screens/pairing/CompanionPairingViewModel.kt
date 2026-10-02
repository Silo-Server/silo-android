package org.siloserver.silo.android.ui.screens.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.common.pairing.CompanionPairingApproval
import org.siloserver.silo.common.pairing.CompanionPairingCoordinator
import org.siloserver.silo.common.pairing.CompanionPairingNsdBrowser
import org.siloserver.silo.common.pairing.CompanionPairingResult
import org.siloserver.silo.common.pairing.CompanionPairingServer
import org.siloserver.silo.common.pairing.CompanionPairingServerStore
import org.siloserver.silo.common.pairing.CompanionPairingStatus
import org.siloserver.silo.common.pairing.CompanionPairingTarget

/**
 * A nearby TV this phone can help. [signInServer] is set for a signed-out
 * TV (`st=login`): the one saved server whose verified identity matches the
 * TV's `srv`, pushed without a chooser.
 */
data class CompanionOffer(
    val target: CompanionPairingTarget,
    val signInServer: CompanionPairingServer? = null,
)

/**
 * The app-wide nearby-TV offer. Only TVs this phone can actually serve are
 * offered: first-run (`st=setup`, or no `st` from older TVs) when the phone
 * has a signed-in server, and signed-out (`st=login`) TVs only when the phone
 * holds the server they advertise. Closing the card hides one TV advertising
 * session; a new session is offered again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionPairingViewModel(
    private val browser: CompanionPairingNsdBrowser,
    private val coordinator: CompanionPairingCoordinator,
    private val serverStore: CompanionPairingServerStore,
) : ViewModel() {
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    val offers: StateFlow<List<CompanionOffer>> = combine(browser.targets, dismissed) { targets, hidden ->
        targets.filter { it.isOfferable && it.dismissalKey !in hidden }
    }.mapLatest { targets ->
        if (targets.isEmpty()) return@mapLatest emptyList()
        val hasServers = serverStore.snapshot().servers.isNotEmpty()
        if (!hasServers) return@mapLatest emptyList()
        targets.mapNotNull { target ->
            if (target.isSignedOutTv) {
                coordinator.serverForSignedOutTv(target)?.let { CompanionOffer(target, it) }
            } else {
                CompanionOffer(target)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val status: StateFlow<CompanionPairingStatus> = coordinator.status

    private val _pendingApproval = MutableStateFlow<CompanionPairingApproval?>(null)
    val pendingApproval: StateFlow<CompanionPairingApproval?> = _pendingApproval.asStateFlow()

    private val _serverChoices = MutableStateFlow<List<CompanionPairingServer>?>(null)
    val serverChoices: StateFlow<List<CompanionPairingServer>?> = _serverChoices.asStateFlow()

    private var pendingServerSelection: CompletableDeferred<List<CompanionPairingServer>>? = null
    private var pendingDecision: CompletableDeferred<Boolean>? = null
    private var pairingJob: Job? = null
    private var active = false

    /**
     * Discover nearby TVs only while the offer can be shown: not over the
     * sign-in chain or playback, where the host hides it, so those routes run
     * no multicast discovery or identity probes. A pairing in progress when
     * the offer is hidden (a sign-out, an expired session) is cancelled.
     */
    fun setActive(enabled: Boolean) {
        if (enabled == active) return
        active = enabled
        if (enabled) {
            browser.start()
        } else {
            dismissPairing()
            browser.stop()
        }
    }

    fun pair(offer: CompanionOffer) {
        if (pairingJob?.isActive == true) return
        val target = offer.target
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val self = kotlin.coroutines.coroutineContext[Job]
            try {
                coordinator.pair(
                    target = target,
                    signInServer = offer.signInServer,
                    chooseServers = { choices ->
                        val selection = CompletableDeferred<List<CompanionPairingServer>>()
                        pendingServerSelection = selection
                        _serverChoices.value = choices
                        try {
                            selection.await()
                        } finally {
                            _serverChoices.value = null
                            pendingServerSelection = null
                        }
                    },
                    confirmFirstMatch = { approval ->
                        val decision = CompletableDeferred<Boolean>()
                        pendingDecision = decision
                        _pendingApproval.value = approval
                        try {
                            decision.await()
                        } finally {
                            _pendingApproval.value = null
                            pendingDecision = null
                        }
                    },
                ).also { result ->
                    // The TV stops advertising once it's set up; its session
                    // must not be offered again.
                    if (result is CompanionPairingResult.Completed) hide(target)
                }
            } finally {
                if (pairingJob === self) pairingJob = null
            }
        }
        pairingJob = job
        job.start()
    }

    fun continueWithServers(serverIds: Set<String>) {
        val choices = _serverChoices.value ?: return
        val selected = choices.filter { it.id in serverIds }
        if (selected.isNotEmpty()) {
            pendingServerSelection?.complete(selected)
        }
    }

    fun approveMatchCode() {
        pendingDecision?.complete(true)
    }

    fun cancelMatchCode() {
        pendingDecision?.complete(false)
    }

    /**
     * The person closed the card ("Not now", Cancel, Back, Close or Done):
     * hide this TV's advertising session and reset. The TV is offered again
     * on its next advertising session (a new `sid`); "Try again" on a failure
     * re-offers it explicitly.
     */
    fun dismiss(target: CompanionPairingTarget) {
        hide(target)
        dismissPairing()
    }

    fun dismissPairing() {
        val finished = coordinator.status.value is CompanionPairingStatus.Completed
        pendingServerSelection?.cancel()
        pendingServerSelection = null
        _serverChoices.value = null
        pendingDecision?.cancel()
        pendingDecision = null
        _pendingApproval.value = null
        pairingJob?.cancel()
        pairingJob = null
        coordinator.reset()
        if (finished && active) {
            // Discovery restarts after a setup so the next TV (or this one,
            // signed out later) is found fresh rather than from stale results.
            browser.stop()
            browser.start()
        }
    }

    private fun hide(target: CompanionPairingTarget) {
        dismissed.update { it + target.dismissalKey }
    }

    override fun onCleared() {
        dismissPairing()
        active = false
        browser.stop()
        super.onCleared()
    }
}

/** One TV advertising session: a TV that restarts advertising gets a new `sid`. */
val CompanionPairingTarget.dismissalKey: String
    get() = "$deviceId:${sessionId ?: serviceName}"
