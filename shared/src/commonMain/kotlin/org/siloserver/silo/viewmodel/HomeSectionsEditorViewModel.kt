package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.ProfileHomeLayoutRepository

data class HomeSectionsEditorState(
    val isLoading: Boolean = true,
    /** The profile's Home rows in display order, hidden rows included. */
    val rows: List<ProfileSectionSetting> = emptyList(),
    val loadError: String? = null,
    /** The last change that could not be saved; unless a later change carries it, the rows are back as the server has them. */
    val saveError: String? = null,
)

/**
 * State holder for the phone and TV Home Sections editors. The rows come
 * from the acting profile's server layout, and every change is saved there
 * at once, so the order and visibility follow the profile to every device
 * and show in the admin's Home sections card. Saves run one at a time, in
 * the order they were made, each carrying the whole list.
 */
class HomeSectionsEditorViewModel(private val repository: ProfileHomeLayoutRepository) : ViewModel() {
    private val _state = MutableStateFlow(HomeSectionsEditorState())
    val state: StateFlow<HomeSectionsEditorState> = _state.asStateFlow()

    /** The profile the rows were read for; every save is pinned to it. */
    private var owner: AuthScopeSnapshot? = null

    /** The rows as the server last confirmed them: read, or saved. A failed save goes back to these unless a later change carries it. */
    private var confirmed: List<ProfileSectionSetting> = emptyList()

    /** Held by each save, and by each read so it never runs beside a save and shows the layout before it. */
    private val saves = Mutex()
    private var loadJob: Job? = null

    /** Counts edits and reloads, so a failed save can tell whether something later owns the rows on screen. */
    private var rowsGeneration = 0L

    /**
     * (Re)reads the profile's rows; the editors call it each time they open.
     * Until the read lands the editor shows its loading state and takes no
     * edits, so neither another profile's old rows nor an edit made before
     * the read can be saved over the layout it returns.
     */
    fun load() {
        loadJob?.cancel()
        owner = null
        rowsGeneration++
        _state.update { it.copy(isLoading = true, rows = emptyList(), loadError = null, saveError = null) }
        loadJob = viewModelScope.launch {
            saves.withLock {
                when (val result = repository.load()) {
                    is ApiResult.Success -> {
                        owner = result.data.owner
                        confirmed = result.data.rows
                        _state.update { it.copy(isLoading = false, rows = result.data.rows, loadError = null, saveError = null) }
                    }
                    else -> _state.update {
                        it.copy(
                            isLoading = false,
                            rows = emptyList(),
                            loadError = result.errorMessage("Could not load Home sections."),
                        )
                    }
                }
            }
        }
    }

    /** Moves row [id] by [offset] places (−1 earlier, +1 later) and saves. */
    fun move(id: String, offset: Int) {
        val rows = _state.value.rows.toMutableList()
        val from = rows.indexOfFirst { it.id == id }
        val to = from + offset
        if (from < 0 || to !in rows.indices) return
        rows.add(to, rows.removeAt(from))
        commit(rows)
    }

    /** Saves the rows in the order of [ids], for a drag that ended; unknown ids are ignored. */
    fun setOrder(ids: List<String>) {
        val current = _state.value.rows
        val byId = current.associateBy { it.id }
        val ordered = ids.distinct().mapNotNull(byId::get)
        commit(ordered + current.filterNot { row -> ordered.any { it.id == row.id } })
    }

    fun setHidden(id: String, hidden: Boolean) {
        commit(_state.value.rows.map { if (it.id == id) it.copy(hidden = hidden) else it })
    }

    fun dismissSaveError() {
        _state.update { it.copy(saveError = null) }
    }

    private fun commit(next: List<ProfileSectionSetting>) {
        val owner = owner
        if (next == _state.value.rows || owner == null) return
        _state.update { it.copy(rows = next, saveError = null) }
        val edit = ++rowsGeneration
        viewModelScope.launch {
            saves.withLock {
                val result = repository.save(owner, next, baseline = confirmed)
                if (result is ApiResult.Success) {
                    confirmed = next
                    _state.update { it.copy(saveError = null) }
                } else {
                    _state.update { state ->
                        state.copy(
                            // A later change carries this one and saves the
                            // whole list itself; only the last change's list
                            // goes back, to what the server has.
                            rows = if (edit == rowsGeneration) confirmed else state.rows,
                            saveError = result.errorMessage("Could not save Home sections."),
                        )
                    }
                }
            }
        }
    }
}
