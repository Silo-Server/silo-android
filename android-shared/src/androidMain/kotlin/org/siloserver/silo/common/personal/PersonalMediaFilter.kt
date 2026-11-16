package org.siloserver.silo.common.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.isTvItemType
import org.siloserver.silo.viewmodel.PersonalListUiState

/**
 * All / Movies / TV Shows: the switch on the Favorites and Watchlist lists
 * (Apple's `PersonalMediaFilter`). The lists fetch unscoped and narrow on the
 * client, because the catalog has no single scope for "series and their
 * episodes" and TV Shows has to keep saved episodes.
 */
enum class PersonalMediaFilter(@StringRes val label: Int) {
    All(CommonR.string.search_filter_all),
    Movies(CommonR.string.personal_filter_movies),
    TvShows(CommonR.string.personal_filter_tvShows),
    ;

    fun includes(type: String?): Boolean {
        val normalized = type?.trim()?.lowercase()
        return when (this) {
            All -> true
            Movies -> normalized == "movie"
            TvShows -> isTvItemType(normalized) || normalized == "show"
        }
    }

    /** The items this option shows, in the list's own order. */
    fun apply(items: List<BrowseItem>): List<BrowseItem> =
        if (this == All) items else items.filter { includes(it.type) }

    /**
     * The count for the list header. All uses the server's total. A narrowed
     * view counts what it shows, and only once every page has loaded, so the
     * number never claims more or fewer titles than the list holds. Pass
     * [othersHidden] when the caller also drops items the server counted.
     */
    fun shownTotal(state: PersonalListUiState, shownCount: Int, othersHidden: Boolean = false): Int? = when {
        this == All && !othersHidden -> state.total
        state.hasMore -> null
        else -> shownCount
    }

    companion object {
        fun fromStored(value: String?): PersonalMediaFilter =
            entries.firstOrNull { it.name == value } ?: All
    }
}

/**
 * The option the viewer picked last, shared by Favorites and Watchlist on
 * this device and kept across launches. It starts on All until a choice is
 * made, as on Apple.
 */
class PersonalMediaFilterStore internal constructor(private val prefs: SharedPreferences) {
    private val _filter = MutableStateFlow(PersonalMediaFilter.fromStored(prefs.getString(KEY, null)))
    val filter: StateFlow<PersonalMediaFilter> = _filter.asStateFlow()

    fun select(filter: PersonalMediaFilter) {
        _filter.value = filter
        prefs.edit { putString(KEY, filter.name) }
    }

    companion object {
        private const val PREFS_NAME = "personal_media_filter"
        private const val KEY = "filter"

        @Volatile
        private var instance: PersonalMediaFilterStore? = null

        fun get(context: Context): PersonalMediaFilterStore =
            instance ?: synchronized(this) {
                instance ?: PersonalMediaFilterStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}

@Composable
fun rememberPersonalMediaFilterStore(): PersonalMediaFilterStore {
    val context = LocalContext.current
    return remember(context) { PersonalMediaFilterStore.get(context) }
}
