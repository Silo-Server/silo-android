package org.siloserver.silo.android.ui.screens.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import org.siloserver.silo.common.pairing.CompanionPairingApproval
import org.siloserver.silo.common.pairing.CompanionPairingCoordinator
import org.siloserver.silo.common.pairing.CompanionPairingNsdBrowser
import org.siloserver.silo.common.pairing.CompanionPairingResult
import org.siloserver.silo.common.pairing.CompanionPairingServer
import org.siloserver.silo.common.pairing.CompanionPairingStatus
import org.siloserver.silo.common.pairing.CompanionPairingTarget

class CompanionPairingViewModel(
    private val browser: CompanionPairingNsdBrowser,
    private val coordinator: CompanionPairingCoordinator,
) : ViewModel() {
    val targets: StateFlow<List<CompanionPairingTarget>> = browser.targets.stateIn(
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

    init {
        browser.start()
    }

    fun pair(target: CompanionPairingTarget) {
        if (pairingJob?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val self = kotlin.coroutines.coroutineContext[Job]
            try {
                val result = coordinator.pair(
                    target = target,
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
                )
                if (result is CompanionPairingResult.Completed) {
                    browser.stop()
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

    fun dismissPairing() {
        pendingServerSelection?.cancel()
        pendingServerSelection = null
        _serverChoices.value = null
        pendingDecision?.cancel()
        pendingDecision = null
        _pendingApproval.value = null
        pairingJob?.cancel()
        pairingJob = null
        coordinator.reset()
    }

    override fun onCleared() {
        dismissPairing()
        browser.stop()
        super.onCleared()
    }
}
