package org.siloserver.silo.model.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.ProfileRepository

/**
 * The signed-in profile, held once for the whole app.
 *
 * Every header draws the active profile's avatar, and this used to be fetched
 * by a screen-scoped view model. A fresh view model starts with no profile, so
 * each such screen rendered the generic person glyph first and swapped to the
 * real avatar when the request landed — a visible flash on page loads that had
 * nothing new to learn.
 *
 * Holding it here means the value survives navigation and view-model
 * lifetimes: the first reader pays for the fetch, every later one reads the
 * cached profile synchronously. A failed refresh keeps the last known profile
 * rather than blanking it, so a dropped network does not send the header back
 * to the person glyph — but only while the active profile has not changed. A
 * cached profile is never shown for a different identity.
 */
class ActiveProfileStore(
    private val profileRepository: ProfileRepository,
) {
    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    private var loadedForProfileId: String? = null

    /**
     * Load the active profile if it is not already cached.
     *
     * [force] re-reads even when cached — used after the profile is edited.
     * Returns true when a value is available afterwards.
     */
    suspend fun refresh(force: Boolean = false): Boolean {
        val activeProfileId = profileRepository.getActiveProfileId()
        if (activeProfileId == null) {
            reset()
            return false
        }
        // The cache belongs to the profile it was loaded for, and nothing else.
        // Drop it the moment the active profile moves: this store outlives the
        // session-expiry path, which routes to Login without resetting it, so
        // retaining it here would draw the previous user's name and avatar in
        // the header of whichever account signs in next and fails its first
        // profile fetch.
        if (loadedForProfileId != activeProfileId) {
            loadedForProfileId = null
            _activeProfile.value = null
        }
        if (!force && _activeProfile.value != null) {
            return true
        }

        return when (val result = profileRepository.listProfiles()) {
            is ApiResult.Success -> {
                val profile = result.data.firstOrNull { it.id == activeProfileId }
                if (profile != null) {
                    _activeProfile.value = profile
                    loadedForProfileId = activeProfileId
                }
                profile != null
            }
            // Keep whatever is cached. Blanking here is what produced the
            // person glyph on a flaky connection.
            is ApiResult.Error, is ApiResult.NetworkError -> _activeProfile.value != null
        }
    }

    /** Clear on sign-out or a server switch, so no profile leaks across accounts. */
    fun reset() {
        loadedForProfileId = null
        _activeProfile.value = null
    }
}
