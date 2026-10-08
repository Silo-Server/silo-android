package org.siloserver.silo.tv.ui.screens.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.common.network.ServerReachabilityState
import org.siloserver.silo.common.network.ServerReachabilityStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.tv.data.preferences.LegacyTvPrefsMigration
import org.siloserver.silo.tv.data.preferences.TvLibrarySelectionStore
import org.siloserver.silo.tv.ui.util.visibleOnTv
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Powers the top-level Android TV Libraries surface. Loads all visible
 * libraries, restores the last selected library, and persists future
 * selections so the tab reopens where the user left it.
 */
class TvLibrariesViewModel(
    private val personalDataRepository: PersonalDataRepository,
    private val librarySelectionStore: TvLibrarySelectionStore,
    private val legacyTvPrefsMigration: LegacyTvPrefsMigration,
    /** Server reachability; each reachable probe retries a failed load. */
    reachability: Flow<ServerReachabilityState> = emptyFlow(),
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val libraries: List<UserLibrary> = emptyList(),
        val selectedLibraryId: Int? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Bumps when the profile hides or shows a library on another device. */
    val hiddenLibrariesRevision: StateFlow<Int> = personalDataRepository.hiddenLibrariesRevision
    private var seenHiddenLibrariesRevision = hiddenLibrariesRevision.value

    // The latest load replaces any still running, so an older answer can't
    // land last. Declared before init, which starts the first load.
    private var loadJob: Job? = null
    // The last load failed and none is running. A revision that asked for a
    // re-load won't come again, so the next reachable probe retries it.
    private var reloadPending = false
    // Only the first load may fall back to the offline cache; later ones
    // re-check, so a failure stays pending instead of showing a stale list.
    private var firstLoadAttempted = false

    init {
        load()
        reachability
            .onEach { if (reloadPending && it.status == ServerReachabilityStatus.Reachable) load() }
            .launchIn(viewModelScope)
    }

    fun onHiddenLibrariesRevision(revision: Int) {
        if (revision == seenHiddenLibrariesRevision) return
        seenHiddenLibrariesRevision = revision
        // Drop newly hidden libraries now, so a failed re-load can't keep them.
        _uiState.update { state ->
            val libraries = personalDataRepository.withoutHidden(state.libraries)
            state.copy(
                libraries = libraries,
                selectedLibraryId = state.selectedLibraryId
                    ?.takeIf { id -> libraries.any { it.id == id } }
                    ?: libraries.firstOrNull()?.id,
            )
        }
        load()
    }

    fun onLibrarySelected(libraryId: Int) {
        if (_uiState.value.selectedLibraryId == libraryId) return
        _uiState.update { it.copy(selectedLibraryId = libraryId) }
        viewModelScope.launch {
            librarySelectionStore.setSelectedLibraryId(libraryId)
        }
    }

    fun load() {
        loadJob?.cancel()
        reloadPending = false
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            // Seed the per-profile selection from the legacy global
            // `tv_prefs` key BEFORE the first read — the resolve below
            // writes a value, which would make the seed unreachable.
            // Sentinel-gated no-op after the first run.
            legacyTvPrefsMigration.migrateIfNeeded()
            val storedLibraryId = librarySelectionStore.getSelectedLibraryId()
            val result = if (firstLoadAttempted) {
                personalDataRepository.recheckUserLibraries(_uiState.value.libraries.mapTo(mutableSetOf()) { it.id })
            } else {
                personalDataRepository.listUserLibraries()
            }
            firstLoadAttempted = true
            when (result) {
                is ApiResult.Success -> {
                    val libraries = result.data
                        .visibleOnTv()
                        .sortedBy { lib -> lib.sortOrder }
                    val resolvedLibraryId = libraries.firstOrNull { it.id == storedLibraryId }?.id
                        ?: libraries.firstOrNull()?.id

                    if (resolvedLibraryId != storedLibraryId) {
                        librarySelectionStore.setSelectedLibraryId(resolvedLibraryId)
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            libraries = libraries,
                            selectedLibraryId = resolvedLibraryId,
                            error = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    reloadPending = true
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message.ifBlank { "Failed to load libraries" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    reloadPending = true
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Network error: ${result.exception.message ?: "unknown"}",
                        )
                    }
                }
            }
        }
    }
}
