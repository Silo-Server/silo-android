package org.siloserver.silo.domain

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PersonalDataRepository

/**
 * Cross-screen actions invoked from a card's long-press / context menu.
 *
 * ViewModels delegate to this coordinator instead of each one duplicating
 * watched / favorite / watchlist plumbing on top of
 * [PersonalDataRepository]. The coordinator returns the [ApiResult] so the
 * caller can roll back optimistic UI changes on failure.
 *
 * Mirrors the iOS context-menu actions in
 * `iosApp/iosApp/Components/MediaCard.swift` and
 * `iosApp/iosApp/Screens/Home/SectionRow.swift`.
 */
class MediaActionsCoordinator(
    private val personalDataRepository: PersonalDataRepository,
) {
    suspend fun setWatched(itemId: String, watched: Boolean): ApiResult<Unit> =
        personalDataRepository.setWatched(itemId, watched)

    fun beginWatched(itemId: String, watched: Boolean) = personalDataRepository.beginWatched(itemId, watched)
    suspend fun performPersonalWrite(intent: org.siloserver.silo.repository.port.PersonalWriteIntent) = personalDataRepository.performPersonalWrite(intent)
    fun isCurrent(intent: org.siloserver.silo.repository.port.PersonalWriteIntent) = personalDataRepository.isCurrent(intent)

    val memberships get() = personalDataRepository.memberships
}
