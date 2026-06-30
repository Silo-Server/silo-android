package com.continuum.app.tv.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.model.profile.UpdateProfileRequest
import com.continuum.app.model.profile.canonicalProfileQualityPreference
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mirrors the phone app's `EditProfileViewModel`. Loads an existing profile by
 * id (via [ProfileRepository.listProfiles], since the repo exposes no
 * getProfile(id)) and saves through [ProfileRepository.updateProfile].
 */
data class TvEditProfileUiState(
    val profileId: String = "",
    val name: String = "",
    val selectedAvatar: String? = null,
    val avatarStyleId: String = TvProfileAvatarPresets.DefaultStyleId,
    val selectedAvatarSeed: String? = null,
    val avatarBatch: Int = 0,
    val isChild: Boolean = false,
    val maxContentRating: String? = null,
    val pinEnabled: Boolean = false,
    val pin: String = "",
    val qualityPreference: String? = null,
    val subtitleMode: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
)

class TvEditProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val profileId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvEditProfileUiState(profileId = profileId))
    val uiState: StateFlow<TvEditProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = profileRepository.listProfiles()) {
                is ApiResult.Success -> {
                    val profile = result.data.find { it.id == profileId }
                    if (profile != null) {
                        val preset = TvProfileAvatarPresets.parseRef(profile.avatar)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                name = profile.name,
                                selectedAvatar = profile.avatar,
                                avatarStyleId = preset?.styleId ?: TvProfileAvatarPresets.DefaultStyleId,
                                selectedAvatarSeed = preset?.seed,
                                isChild = profile.isChild,
                                maxContentRating = profile.maxContentRating,
                                pinEnabled = profile.hasPin,
                                qualityPreference = profile.qualityPreference,
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

    fun onAvatarSelected(emoji: String) {
        _uiState.update { it.copy(selectedAvatar = emoji) }
    }

    fun onAvatarStyleSelected(styleId: String) {
        _uiState.update {
            it.copy(
                avatarStyleId = styleId,
                selectedAvatar = null,
                selectedAvatarSeed = null,
                avatarBatch = 0,
            )
        }
    }

    fun onAvatarPresetSelected(preset: TvProfileAvatarPresets.Preset) {
        _uiState.update {
            it.copy(
                avatarStyleId = preset.styleId,
                selectedAvatar = preset.ref,
                selectedAvatarSeed = preset.seed,
            )
        }
    }

    fun onAvatarShuffle() {
        _uiState.update {
            it.copy(
                avatarBatch = it.avatarBatch + 1,
                selectedAvatar = null,
                selectedAvatarSeed = null,
            )
        }
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

    fun onSubtitleModeSelected(mode: String) {
        _uiState.update {
            it.copy(subtitleMode = if (mode == "Off") null else mode.lowercase().replace(" ", "_"))
        }
    }

    fun onSaveClick() {
        val current = _uiState.value

        if (current.name.isBlank()) {
            _uiState.update { it.copy(error = "Profile name is required") }
            return
        }
        if (current.pinEnabled && current.pin.isNotEmpty() && current.pin.length != 4) {
            _uiState.update { it.copy(error = "PIN must be 4 digits") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }

            val request = UpdateProfileRequest(
                name = current.name,
                avatar = current.effectiveAvatarRef(),
                pin = if (current.pinEnabled && current.pin.isNotEmpty()) current.pin else null,
                isChild = current.isChild,
                maxContentRating = current.maxContentRating,
                qualityPreference = current.qualityPreference,
                subtitleMode = current.subtitleMode,
            )

            when (profileRepository.updateProfile(current.profileId, request)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
                }

                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isSaving = false, error = "Failed to update profile")
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

private fun TvEditProfileUiState.effectiveAvatarRef(): String? =
    TvProfileAvatarPresets.effectiveAvatarRef(
        styleId = avatarStyleId,
        selectedSeed = selectedAvatarSeed,
        batch = avatarBatch,
        name = name,
        fallbackAvatar = selectedAvatar,
    )
