package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.calendar.CalendarDay
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.CalendarRepository
import org.siloserver.silo.util.IsoDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** ISO "YYYY-MM-DD" for the platform's current date. */
    val today: String = "",
    /** Monday anchoring the visible week, ISO "YYYY-MM-DD". */
    val weekStart: String = "",
    /**
     * The day highlighted in the week strip, ISO "YYYY-MM-DD". Independent of
     * [today]: selecting a day in the strip scrolls the day list to that
     * shelf. Defaults to / follows [today] when the visible week is current,
     * otherwise the first day of the visible week.
     */
    val selectedDay: String = "",
    val filter: String = CalendarFilter.Following,
    val libraryId: Int? = null,
    /** Server-grouped day buckets for the visible week. */
    val days: List<CalendarDay> = emptyList(),
    val error: String? = null,
) {
    /** The 7 ISO dates of the visible week, Monday first. */
    val weekDates: List<String>
        get() = if (weekStart.isBlank()) emptyList() else (0L..6L).map { IsoDate.plusDays(weekStart, it) }

    val weekEnd: String
        get() = if (weekStart.isBlank()) "" else IsoDate.plusDays(weekStart, 6)

    val isCurrentWeek: Boolean
        get() = today.isNotBlank() && weekStart == IsoDate.weekStart(today)

    val hasAnyItems: Boolean
        get() = days.any { it.items.isNotEmpty() }

    fun itemsFor(date: String): List<CalendarItem> =
        days.firstOrNull { it.date == date }?.items.orEmpty()
}

/**
 * Remembers the user's Following / Trending / All choice across launches
 * (iOS: `UserDefaults["calendar.filter"]`). Platforms back it with their
 * preference store; tests use [InMemory].
 */
interface CalendarFilterStore {
    fun read(): String?
    fun write(filter: String)

    class InMemory(private var value: String? = null) : CalendarFilterStore {
        override fun read(): String? = value
        override fun write(filter: String) { value = filter }
    }
}

/**
 * Shared calendar/upcoming ViewModel (pattern: RequestsViewModels). The
 * platform supplies "today" and the IANA timezone so week math stays
 * deterministic in commonTest — no Clock.System defaults baked in.
 *
 * Responses are cached per (week, filter, library) for the ViewModel's
 * lifetime and served stale-while-revalidate (iOS `CalendarViewModel`):
 * paging back to a week or flipping a filter you have already seen renders
 * instantly and quietly refreshes behind, instead of blanking the agenda.
 */
class CalendarViewModel(
    private val repository: CalendarRepository,
    private val timezoneId: String,
    private val todayProvider: () -> String,
    private val filterStore: CalendarFilterStore = CalendarFilterStore.InMemory(),
) : ViewModel() {

    private data class CacheKey(val weekStart: String, val filter: String, val libraryId: Int?)

    private val cache = HashMap<CacheKey, List<CalendarDay>>()

    private val CalendarUiState.cacheKey: CacheKey
        get() = CacheKey(weekStart, filter, libraryId)

    /**
     * Monotonically increasing counter incremented on every fetch start.
     * Each in-flight coroutine captures the value at launch time and skips
     * state writes when a newer fetch has already started — preventing a
     * slow/stale response from overwriting a more-recent result.
     */
    private var loadGeneration = 0

    private val _uiState: MutableStateFlow<CalendarUiState>
    val uiState: StateFlow<CalendarUiState>

    init {
        val today = todayProvider()
        _uiState = MutableStateFlow(
            CalendarUiState(
                today = today,
                weekStart = IsoDate.weekStart(today),
                selectedDay = today,
                filter = filterStore.read()?.takeIf { it.isNotBlank() } ?: CalendarFilter.Following,
            ),
        )
        uiState = _uiState.asStateFlow()
        load()
    }

    fun load() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            // Recompute "today" on every fetch so a resident/always-on app self-
            // corrects after midnight: the "Today" highlight + header and
            // isCurrentWeek (which gates the Today button) stay accurate without
            // requiring the user to press Today first. weekStart is untouched so
            // the visible week — and thus the fetched range — doesn't shift.
            _uiState.update {
                // Stale-while-revalidate: a cached week renders immediately and
                // is not "loading"; an unseen week clears the previous week's
                // rows so they cannot show under the new strip while it loads.
                val cached = cache[it.cacheKey]
                it.copy(
                    isLoading = cached == null,
                    // A load that supersedes an in-flight refresh takes over
                    // the refresh flag too; the refresh coroutine will refuse
                    // to clear it once its generation is stale.
                    isRefreshing = false,
                    days = cached.orEmpty(),
                    error = null,
                    today = todayProvider(),
                )
            }
            fetch(generation)
        }
    }

    fun refresh() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            // Pull-to-refresh is an explicit "get me fresh data": evict the
            // cache entry so a failure cannot fall back to the stale copy.
            cache.remove(_uiState.value.cacheKey)
            _uiState.update { it.copy(isRefreshing = true, error = null, today = todayProvider()) }
            fetch(generation)
            if (generation == loadGeneration) {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun nextWeek() = moveWeek(7)

    fun prevWeek() = moveWeek(-7)

    fun goToToday() {
        val today = todayProvider()
        val weekStart = IsoDate.weekStart(today)
        if (weekStart == _uiState.value.weekStart) {
            _uiState.update { it.copy(today = today, selectedDay = today) }
            return
        }
        _uiState.update { it.copy(today = today, weekStart = weekStart, selectedDay = today) }
        load()
    }

    /** Highlight a day in the week strip (no fetch — the week is already loaded). */
    fun selectDay(date: String) {
        if (date == _uiState.value.selectedDay) return
        _uiState.update { it.copy(selectedDay = date) }
    }

    fun setFilter(filter: String) {
        if (filter == _uiState.value.filter) return
        filterStore.write(filter)
        _uiState.update { it.copy(filter = filter) }
        load()
    }

    fun setLibrary(libraryId: Int?) {
        if (libraryId == _uiState.value.libraryId) return
        _uiState.update { it.copy(libraryId = libraryId) }
        load()
    }

    private fun moveWeek(days: Long) {
        _uiState.update {
            val weekStart = IsoDate.plusDays(it.weekStart, days)
            // Keep the highlight on a day inside the visible week: today when
            // paging back onto the current week, otherwise the week's Monday.
            val selectedDay = if (weekStart == IsoDate.weekStart(it.today)) it.today else weekStart
            it.copy(weekStart = weekStart, selectedDay = selectedDay)
        }
        load()
    }

    private suspend fun fetch(generation: Int) {
        val state = _uiState.value
        val result = repository.getCalendar(
            start = state.weekStart,
            end = state.weekEnd,
            filter = state.filter,
            libraryId = state.libraryId,
            timezone = timezoneId,
        )
        // Discard the result if a newer fetch has already started.
        if (generation != loadGeneration) return
        when (result) {
            is ApiResult.Success -> {
                cache[state.cacheKey] = result.data.events
                _uiState.update {
                    it.copy(isLoading = false, days = result.data.events, error = null)
                }
            }
            is ApiResult.Error, is ApiResult.NetworkError -> _uiState.update {
                // Keep showing cached rows on failure; only an empty screen
                // becomes an error screen (iOS: error set only if days.isEmpty).
                it.copy(
                    isLoading = false,
                    error = if (it.days.isEmpty()) result.errorMessage("Failed to load calendar") else null,
                )
            }
        }
    }
}
