package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import org.siloserver.silo.repository.ProfileRepository

/**
 * Ends the household management session the profile menu's Add Profile
 * started, once the new-profile form leaves the back stack. Scoped to the
 * form's navigation entry rather than its composition, so the session and the
 * primary profile's PIN proof outlast an activity recreation that keeps the
 * form.
 */
class MenuAddProfileSession(private val profileRepository: ProfileRepository) : ViewModel() {
    override fun onCleared() {
        profileRepository.householdManagement.end()
    }
}
