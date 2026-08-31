package org.siloserver.silo.tv.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.authorizedProfileToken
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileCommitResult
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TvProfileSelectionUiState(
    val profiles: List<Profile> = emptyList(),
    val canManageProfiles: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedProfileId: String? = null,
    // Manage mode: tapping a profile opens edit, plus a delete affordance shows.
    val isManageMode: Boolean = false,
    // PIN entry flow.
    val pinProfile: Profile? = null,
    val isVerifyingPin: Boolean = false,
    val pinError: String? = null,
    // Profile pending delete confirmation (manage mode).
    val deleteCandidate: Profile? = null,
    val isDeleting: Boolean = false,
)

/**
 * TV profile picker. PIN-protected profiles now work: selecting one opens a
 * [org.siloserver.silo.tv.ui.components.TvPinEntryDialog] which drives the PIN
 * flow in this ViewModel. Non-PIN profiles still route directly.
 */
class TvProfileSelectionViewModel(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvProfileSelectionUiState())
    val uiState: StateFlow<TvProfileSelectionUiState> = _uiState.asStateFlow()

    /** Monotonic generation for PIN verification; see [onPinEntered]. */
    private var pinAttempt: Int = 0

    /** Monotonic generation for profile-list loads; see [loadProfiles]. */
    private var loadAttempt: Int = 0

    init {
        loadProfiles()
    }

    /**
     * The identity the displayed grid was fetched under.
     *
     * Every selection is qualified with it, protected or not. Passing null for
     * unprotected picks disabled the guard for exactly the case with no PIN
     * round trip to re-establish scope — so if another account became active
     * between the grid being accepted and the commit entering the barrier, one
     * account's profile id was written into the other's token slot.
     */
    private var gridScope: AuthScopeSnapshot? = null

    fun loadProfiles() {
        val load = ++loadAttempt
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val scope = profileRepository.captureIdentityScope()
            val isAdmin = (authRepository?.getCurrentUser() as? ApiResult.Success)?.data?.role?.equals("admin", ignoreCase = true) == true
            val listed = profileRepository.listProfiles()
            // Two separate reasons to drop this response: a newer load
            // superseded it, or the identity it was fetched under is gone.
            if (load != loadAttempt) return@launch
            if (!profileRepository.identityScopeUnchanged(scope)) {
                // The displayed grid is gone, so its scope must go with it.
                gridScope = null
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        profiles = emptyList(),
                        canManageProfiles = false,
                        isManageMode = false,
                        deleteCandidate = null,
                    )
                }
                return@launch
            }
            when (val result = listed) {
                is ApiResult.Success -> {
                    // The scope moves with the grid, and ONLY with it. See the
                    // phone ViewModel: assigning it before the result meant a
                    // failed reload under a NEW identity left the OLD grid
                    // qualified by the NEW scope.
                    gridScope = scope
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profiles = result.data,
                            canManageProfiles = isAdmin,
                            isManageMode = if (isAdmin) it.isManageMode else false,
                            deleteCandidate = if (isAdmin) it.deleteCandidate else null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message.ifBlank { "Failed to load profiles" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Network error. Please try again.")
                    }
                }
            }
        }
    }

    fun toggleManageMode() {
        if (!_uiState.value.canManageProfiles) return
        _uiState.update {
            it.copy(
                isManageMode = !it.isManageMode,
                // Leaving manage mode clears any pending delete prompt.
                deleteCandidate = if (it.isManageMode) null else it.deleteCandidate,
            )
        }
    }

    /**
     * Called when a profile is activated on the selection grid. In manage mode
     * the screen routes to edit instead (it inspects [TvProfileSelectionUiState.isManageMode]),
     * so this only runs the select/PIN flow.
     */
    fun onProfileSelected(profile: Profile) {
        if (_uiState.value.isManageMode) {
            // Manage-mode taps open edit, handled by the screen composable.
            return
        }
        // Focus/Select can already be dispatched when a scope mismatch clears
        // the grid. A null gridScope deliberately means no grid metadata, but
        // it also disables the repository guard, so reject cards that are no
        // longer part of the accepted grid.
        if (_uiState.value.profiles.none { it.id == profile.id }) return
        // Bump before branching: ANY accepted selection supersedes a
        // verification still in flight, including choosing an unprotected
        // profile while a protected one is mid-verify.
        pinAttempt++

        if (profile.hasPin) {
            // Open the PIN dialog; actual selection happens in onPinEntered.
            _uiState.update {
                it.copy(pinProfile = profile, pinError = null, isVerifyingPin = false)
            }
            return
        }
        // Qualified by the grid's scope. An unprotected pick has no PIN round
        // trip to re-establish identity, so without this it was the one path
        // that committed unguarded.
        commitSelection(profile, expectedScope = gridScope)
    }

    fun onPinDialogDismissed() {
        // Abandon any in-flight verification for the dismissed profile.
        pinAttempt++
        _uiState.update {
            it.copy(pinProfile = null, pinError = null, isVerifyingPin = false)
        }
    }

    fun onPinEntered(pin: String) {
        val profile = _uiState.value.pinProfile ?: return
        val attempt = ++pinAttempt
        _uiState.update { it.copy(isVerifyingPin = true, pinError = null) }
        viewModelScope.launch {
            // Pin the answer to the identity that was asked. TV can install a
            // temporary remote-playback identity mid-flight, and this profile's
            // proof must never land in that overlay.
            val scope = profileRepository.captureIdentityScope()
            val r = profileRepository.verifyPin(profile.id, pin)
            // Cancelling (or picking another profile) during the round trip
            // must abandon this answer — otherwise Back still entered the
            // profile, and on TV a "Change Server" mid-flight could land this
            // profile's token on a different server.
            if (attempt != pinAttempt) return@launch

            when (r) {
                is ApiResult.Success -> {
                    // The server returns 200 with valid=false for a wrong PIN, so
                    // gate on the issued token (matches phone) — never commit on
                    // a bare 200, nor on a valid=true carrying no proof.
                    val token = r.data.authorizedProfileToken()
                    if (token != null) {
                        commitSelection(profile, token, scope)
                        _uiState.update { it.copy(pinProfile = null, isVerifyingPin = false) }
                    } else {
                        _uiState.update { it.copy(isVerifyingPin = false, pinError = "Incorrect PIN") }
                    }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isVerifyingPin = false,
                        pinError = r.message.ifBlank { "Incorrect PIN" },
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isVerifyingPin = false,
                        pinError = "Network error. Please try again.",
                    )
                }
            }
        }
    }

    private fun commitSelection(
        profile: Profile,
        profileToken: String? = null,
        expectedScope: AuthScopeSnapshot? = null,
    ) {
        viewModelScope.launch {
            val result = profileRepository.selectProfile(profile.id, profileToken, expectedScope)
            if (result == ProfileCommitResult.ScopeChanged) {
                // Identity moved under us — don't route into this profile, and
                // drop the grid with it. A retained grid keeps D-pad focus on
                // profiles belonging to a session we no longer hold.
                _uiState.update {
                    it.copy(
                        profiles = emptyList(),
                        selectedProfileId = null,
                        pinProfile = null,
                        isVerifyingPin = false,
                        pinError = null,
                        deleteCandidate = null,
                        isManageMode = false,
                    )
                }
                loadProfiles()
                return@launch
            }
            _uiState.update { it.copy(selectedProfileId = profile.id) }
        }
    }

    fun onSelectionConsumed() {
        _uiState.update { it.copy(selectedProfileId = null) }
    }

    /** Opens the delete confirmation for [profile] (manage mode). */
    fun requestDelete(profile: Profile) {
        if (!_uiState.value.canManageProfiles) return
        _uiState.update { it.copy(deleteCandidate = profile) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deleteCandidate = null) }
    }

    /** Confirms deletion of the pending candidate and reloads on success. */
    fun confirmDelete() {
        val profile = _uiState.value.deleteCandidate ?: return
        _uiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            when (val result = profileRepository.deleteProfile(profile.id)) {
                is ApiResult.Success -> {
                    // Deleting the signed-in profile invalidates its profile
                    // token server-side; drop the local selection BEFORE the
                    // list reload so it doesn't ride dead credentials and
                    // render an empty grid ("all profiles gone").
                    if (profile.id == profileRepository.getActiveProfileId()) {
                        profileRepository.clearProfile()
                    }
                    _uiState.update { it.copy(isDeleting = false, deleteCandidate = null) }
                    loadProfiles()
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isDeleting = false,
                        deleteCandidate = null,
                        error = result.message.ifBlank { "Failed to delete profile" },
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isDeleting = false,
                        deleteCandidate = null,
                        error = "Network error. Please try again.",
                    )
                }
            }
        }
    }
}
