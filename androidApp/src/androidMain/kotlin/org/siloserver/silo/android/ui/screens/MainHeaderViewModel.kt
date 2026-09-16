package org.siloserver.silo.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.model.profile.Profile

data class MainHeaderUiState(
    val isLoading: Boolean = true,
    val activeProfile: Profile? = null,
)

/**
 * A view of [ActiveProfileStore], not an owner of the profile.
 *
 * The profile lives in the store so it survives navigation: a view model
 * recreated for a new screen starts from the cached value instead of null, so
 * the header no longer flashes the generic person glyph before the avatar
 * arrives.
 */
class MainHeaderViewModel(
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {

    private val loading = MutableStateFlow(false)

    val uiState: StateFlow<MainHeaderUiState> =
        combine(activeProfileStore.activeProfile, loading) { profile, isLoading ->
            MainHeaderUiState(isLoading = isLoading && profile == null, activeProfile = profile)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            // Seeded from the cache, so the very first frame already has the
            // avatar whenever the app has loaded it once.
            initialValue = MainHeaderUiState(
                isLoading = activeProfileStore.activeProfile.value == null,
                activeProfile = activeProfileStore.activeProfile.value,
            ),
        )

    /** No-ops when the profile is already cached unless [force] is set. */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            loading.value = true
            try {
                activeProfileStore.refresh(force = force)
            } finally {
                loading.value = false
            }
        }
    }
}
