package com.continuum.app.tv.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.model.profile.CreateProfileRequest
import com.continuum.app.model.profile.canonicalProfileQualityPreference
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mirrors the phone app's `CreateProfileViewModel`. Drives the TV create-profile
 * form, calling the same [ProfileRepository.createProfile].
 */
data class TvCreateProfileUiState(
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val createSuccess: Boolean = false,
)

class TvCreateProfileViewModel(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvCreateProfileUiState())
    val uiState: StateFlow<TvCreateProfileUiState> = _uiState.asStateFlow()

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
                // Default to PG for child profiles.
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
        // Restrict to 4 digits.
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

    fun onCreateClick() {
        val current = _uiState.value

        if (current.name.isBlank()) {
            _uiState.update { it.copy(error = "Profile name is required") }
            return
        }
        if (current.pinEnabled && current.pin.length != 4) {
            _uiState.update { it.copy(error = "PIN must be 4 digits") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val request = CreateProfileRequest(
                name = current.name,
                avatar = current.effectiveAvatarRef(),
                pin = if (current.pinEnabled) current.pin else null,
                isChild = if (current.isChild) true else null,
                maxContentRating = current.maxContentRating,
                qualityPreference = current.qualityPreference,
                subtitleMode = current.subtitleMode,
            )

            when (profileRepository.createProfile(request)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, createSuccess = true) }
                }

                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to create profile")
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

    fun onCreateSuccessConsumed() {
        _uiState.update { it.copy(createSuccess = false) }
    }
}

private fun TvCreateProfileUiState.effectiveAvatarRef(): String? =
    TvProfileAvatarPresets.effectiveAvatarRef(
        styleId = avatarStyleId,
        selectedSeed = selectedAvatarSeed,
        batch = avatarBatch,
        name = name,
        fallbackAvatar = selectedAvatar,
    )
