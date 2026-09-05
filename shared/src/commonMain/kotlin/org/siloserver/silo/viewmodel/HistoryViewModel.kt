package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.apiv2.HistoryContinuationV2
import org.siloserver.silo.repository.PersonalDataRepository

/** History has cursor paging and no total or sort/filter contract. */
class HistoryViewModel(
    private val repository: PersonalDataRepository,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
) : ViewModel() {
    private val state = MutableStateFlow(PersonalListUiState())
    val uiState = state.asStateFlow()
    var hasLoadedOnce = false
        private set
    private var continuation: HistoryContinuationV2? = null
    private var generation = 0
    private var loadJob: Job? = null

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            identityTransitions.transitions.collect { transition ->
                if (transition.affectsCurrentIdentity) {
                    when (transition.phase) {
                        IdentityTransitionPhase.WILL_CHANGE -> {
                            generation++
                            loadJob?.cancel()
                            continuation = null
                            state.value = PersonalListUiState()
                            hasLoadedOnce = false
                        }
                        IdentityTransitionPhase.DID_CHANGE -> load(reset = true)
                    }
                }
            }
        }
        load(reset = true)
    }

    fun retry() = load(reset = true)
    fun refresh() = load(reset = true, refreshing = true)
    fun loadMore() {
        val current = state.value
        if (current.isLoading || current.isLoadingMore || current.isRefreshing || current.error != null || !current.hasMore) return
        load(reset = false)
    }

    private fun load(reset: Boolean, refreshing: Boolean = false) {
        if (reset) {
            generation++
            loadJob?.cancel()
            continuation = null
        }
        val requestGeneration = generation
        val requestIdentity = identityTransitions.generation.value
        val cursor = if (reset) null else continuation
        state.update { it.copy(isLoading = reset && !refreshing, isRefreshing = refreshing,
            isLoadingMore = !reset, error = null) }
        loadJob = viewModelScope.launch {
            val result = repository.listHistory(continuation = cursor)
            currentCoroutineContext().ensureActive()
            if (requestGeneration != generation || requestIdentity != identityTransitions.generation.value) return@launch
            when (result) {
                is ApiResult.Success -> {
                    continuation = result.data.continuation
                    hasLoadedOnce = true
                    state.update { previous ->
                        val watches = result.data.items.associate { it.item.contentId to it.watch }
                        previous.copy(items = (if (reset) emptyList() else previous.items) + result.data.items.map { it.item },
                            historyWatches = (if (reset) emptyMap() else previous.historyWatches) + watches,
                            hasMore = continuation != null, total = null,
                            isLoading = false, isRefreshing = false, isLoadingMore = false, error = null)
                    }
                }
                is ApiResult.Error -> state.update {
                    val retained = if (result.error == "identity_changed") PersonalListUiState() else it
                    retained.copy(isLoading = false, isLoadingMore = false, isRefreshing = false, error = result.message)
                }
                is ApiResult.NetworkError -> state.update {
                    it.copy(isLoading = false, isLoadingMore = false, isRefreshing = false,
                        error = result.exception.message ?: "History could not be loaded.")
                }
            }
        }
    }
}
