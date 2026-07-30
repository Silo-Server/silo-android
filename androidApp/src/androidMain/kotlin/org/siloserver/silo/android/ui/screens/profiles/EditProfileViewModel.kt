package org.siloserver.silo.android.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.UpdateProfileRequest
import org.siloserver.silo.model.profile.canonicalProfileQualityPreference
import org.siloserver.silo.model.profile.hasProfileNamed
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditProfileUiState(
    val profileId: String = "",
    val name: String = "",
    val selectedAvatar: String? = null,
    val isChild: Boolean = false,
    val maxContentRating: String? = null,
    val pinEnabled: Boolean = false,
    val pin: String = "",
    val qualityPreference: String? = null,
    val language: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleMode: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
)

class EditProfileViewModel(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    /** All profiles from [loadProfile], kept for the rename duplicate check. */
    private var existingProfiles: List<Profile> = emptyList()

    /**
     * Whether the loaded profile already had a PIN. Needed to distinguish
     * "remove the existing PIN" (send an explicit empty string) from "there was
     * never a PIN" (omit the field). The shared Json uses explicitNulls=false,
     * so a null pin is dropped from the PUT and the server keeps the old value.
     */
    private var loadedHasPin: Boolean = false

    /**
     * Loads the profile by ID. Called once from the composable via LaunchedEffect.
     * We fetch the full list and find the matching profile because the repository
     * API does not expose a getProfile(id) method.
     */
    fun loadProfile(profileId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, profileId = profileId) }

            when (val result = profileRepository.listProfiles()) {
                is ApiResult.Success -> {
                    existingProfiles = result.data
                    val profile = result.data.find { it.id == profileId }
                    if (profile != null) {
                        loadedHasPin = profile.hasPin
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                name = profile.name,
                                selectedAvatar = profile.avatar,
                                isChild = profile.isChild,
                                maxContentRating = profile.maxContentRating,
                                pinEnabled = profile.hasPin,
                                qualityPreference = profile.qualityPreference,
                                language = profile.language,
                                subtitleLanguage = profile.subtitleLanguage,
                                subtitleMode = profile.subtitleMode,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(isLoading = false, error = "Profile not found")
                        }
                    }
                }

                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to load profile")
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

    fun onNameChanged(value: String) {
        _uiState.update { it.copy(name = value, error = null) }
    }

    fun onAvatarSelected(avatarRef: String) {
        _uiState.update { it.copy(selectedAvatar = avatarRef) }
    }

    fun onChildToggled(checked: Boolean) {
        _uiState.update {
            it.copy(
                isChild = checked,
                maxContentRating = if (checked && it.maxContentRating == null) "PG" else it.maxContentRating,
            )
        }
    }

    fun onContentRatingSelected(rating: String) {
        _uiState.update { it.copy(maxContentRating = rating) }
    }

    fun onPinToggled(enabled: Boolean) {
        _uiState.update { it.copy(pinEnabled = enabled, pin = if (!enabled) "" else it.pin) }
    }

    fun onPinChanged(value: String) {
        val filtered = value.filter { it.isDigit() }.take(4)
        _uiState.update { it.copy(pin = filtered, error = null) }
    }

    fun onQualitySelected(quality: String) {
        _uiState.update {
            it.copy(qualityPreference = canonicalProfileQualityPreference(quality))
        }
    }

    fun onLanguageSelected(language: String?) {
        _uiState.update { it.copy(language = language) }
    }

    fun onSubtitleLanguageSelected(language: String?) {
        _uiState.update { it.copy(subtitleLanguage = language) }
    }

    fun onSubtitleModeSelected(mode: String) {
        _uiState.update {
            // "Off" must go over the wire as the explicit "off" value, not null:
            // explicitNulls=false drops a null subtitle_mode so the server would
            // keep the previous mode instead of switching subtitles off.
            it.copy(subtitleMode = if (mode == "Off") "off" else mode.lowercase().replace(" ", "_"))
        }
    }

    fun onSaveClick() {
        val current = _uiState.value

        if (current.name.isBlank()) {
            _uiState.update { it.copy(error = "Profile name is required") }
            return
        }
        if (existingProfiles.hasProfileNamed(current.name, excludeId = current.profileId)) {
            _uiState.update { it.copy(error = "A profile with this name already exists") }
            return
        }
        if (current.pinEnabled && current.pin.isNotEmpty() && current.pin.length != 4) {
            _uiState.update { it.copy(error = "PIN must be 4 digits") }
            return
        }

        // Map the PIN state onto the wire value the server expects:
        //  - a freshly entered 4-digit PIN sets/replaces the PIN;
        //  - turning a PIN off on a profile that had one sends an explicit ""
        //    so the server clears it (a null would be omitted and ignored);
        //  - otherwise omit the field (null) to leave the PIN unchanged.
        val pinValue = when {
            current.pinEnabled && current.pin.isNotEmpty() -> current.pin
            !current.pinEnabled && loadedHasPin -> ""
            else -> null
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }

            val request = UpdateProfileRequest(
                name = current.name,
                avatar = current.selectedAvatar,
                pin = pinValue,
                isChild = current.isChild,
                maxContentRating = current.maxContentRating,
                qualityPreference = current.qualityPreference,
                language = current.language,
                subtitleLanguage = current.subtitleLanguage,
                subtitleMode = current.subtitleMode,
            )

            when (val result = profileRepository.updateProfile(current.profileId, request)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
                }

                is ApiResult.Error -> {
                    // Surface the server's explanation (e.g. a name conflict)
                    // instead of a generic failure.
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = result.message.ifBlank { "Failed to update profile" },
                        )
                    }
                }

                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isSaving = false, error = "Network error. Please try again.")
                    }
                }
            }
        }
    }

    fun onSaveSuccessConsumed() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
}
