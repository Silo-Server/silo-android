package org.siloserver.silo.android.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.profile.CreateProfileRequest
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.hasProfileNamed
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateProfileUiState(
    val name: String = "",
    val selectedAvatar: String? = null,
    val isChild: Boolean = false,
    val maxContentRating: String? = null,
    val pinEnabled: Boolean = false,
    val pin: String = "",
    val language: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleMode: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val createSuccess: Boolean = false,
)

/** Available content ratings, ordered from least to most restrictive. */
val CONTENT_RATINGS = listOf("G", "PG", "PG-13", "R", "NC-17")

/** Available quality preferences. */
val QUALITY_OPTIONS = listOf("Auto", "4K", "1080p", "720p", "480p")

/** Available subtitle modes. */
val SUBTITLE_MODES = listOf("Off", "Default", "Always", "Forced Only")

class CreateProfileViewModel(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateProfileUiState())
    val uiState: StateFlow<CreateProfileUiState> = _uiState.asStateFlow()

    /** Existing profiles, loaded for the duplicate-name pre-check. Best
     *  effort: if the load fails the server still enforces the rule. */
    private var existingProfiles: List<Profile> = emptyList()

    init {
        viewModelScope.launch {
            (profileRepository.listProfiles() as? ApiResult.Success)?.let {
                existingProfiles = it.data
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

    fun onLanguageSelected(language: String?) {
        _uiState.update { it.copy(language = language) }
    }

    fun onSubtitleLanguageSelected(language: String?) {
        _uiState.update { it.copy(subtitleLanguage = language) }
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
        if (existingProfiles.hasProfileNamed(current.name)) {
            _uiState.update { it.copy(error = "A profile with this name already exists") }
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
                avatar = current.selectedAvatar,
                pin = if (current.pinEnabled) current.pin else null,
                isChild = if (current.isChild) true else null,
                maxContentRating = current.maxContentRating,
                language = current.language,
                subtitleLanguage = current.subtitleLanguage,
                subtitleMode = current.subtitleMode,
            )

            when (val result = profileRepository.createProfile(request)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, createSuccess = true) }
                }

                is ApiResult.Error -> {
                    // Surface the server's explanation (e.g. the account's
                    // profile limit) instead of a generic failure.
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message.ifBlank { "Failed to create profile" },
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

    fun onCreateSuccessConsumed() {
        _uiState.update { it.copy(createSuccess = false) }
    }
}
