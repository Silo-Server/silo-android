package org.siloserver.silo.tv.ui.screens.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.tv.data.preferences.LegacyTvPrefsMigration
import org.siloserver.silo.tv.data.preferences.TvLibrarySelectionStore
import org.siloserver.silo.tv.ui.util.visibleOnTv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val libraries: List<UserLibrary> = emptyList(),
        val selectedLibraryId: Int? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Bumps when the profile hides or shows a library on another device. */
    val hiddenLibrariesRevision: StateFlow<Int> = personalDataRepository.hiddenLibrariesRevision
    private var seenHiddenLibrariesRevision = hiddenLibrariesRevision.value

    fun onHiddenLibrariesRevision(revision: Int) {
        if (revision == seenHiddenLibrariesRevision) return
        seenHiddenLibrariesRevision = revision
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            // Seed the per-profile selection from the legacy global
            // `tv_prefs` key BEFORE the first read — the resolve below
            // writes a value, which would make the seed unreachable.
            // Sentinel-gated no-op after the first run.
            legacyTvPrefsMigration.migrateIfNeeded()
            val storedLibraryId = librarySelectionStore.getSelectedLibraryId()
            when (val result = personalDataRepository.listUserLibraries()) {
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
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.message.ifBlank { "Failed to load libraries" },
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Network error: ${result.exception.message ?: "unknown"}",
                    )
                }
            }
        }
    }
}
