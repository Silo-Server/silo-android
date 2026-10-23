package org.siloserver.silo.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.ProfileSectionsV2Api

/**
 * Bumped after the acting profile's Home layout changed on the server, so
 * every Home on this device re-reads its rows in the new order.
 */
class HomeLayoutChanges {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun changed() {
        _revision.update { it + 1 }
    }
}

/** The acting profile's Home rows as it sees them, pinned to the profile they were read for. */
data class ProfileHomeLayout(
    val owner: AuthScopeSnapshot,
    val rows: List<ProfileSectionSetting>,
)

/**
 * Reads and writes the acting profile's Home row order and visibility on the
 * server. The server applies them to `/api/v2/home/sections` for that profile
 * on every device, and the admin sees and sets them in the profile's Home
 * sections card, so there is no device-local copy.
 */
class ProfileHomeLayoutRepository(
    private val api: ProfileSectionsV2Api,
    private val changes: HomeLayoutChanges,
    private val newId: () -> String = ::newOverrideId,
) {
    suspend fun load(): ApiResult<ProfileHomeLayout> {
        val owner = api.capture() ?: return noProfile()
        return when (val rows = api.homeSettings(owner)) {
            is ApiResult.Success -> ApiResult.Success(ProfileHomeLayout(owner, rows.data))
            is ApiResult.Error -> rows
            is ApiResult.NetworkError -> rows
        }
    }

    /**
     * Saves [rows] (the whole Home list in its new order, with each row's
     * visibility) as the profile's layout, keeping every other saved
     * customization. [baseline] is the list as the server last confirmed it;
     * see [buildHomeSectionOverrides].
     */
    suspend fun save(
        owner: AuthScopeSnapshot,
        rows: List<ProfileSectionSetting>,
        baseline: List<ProfileSectionSetting>,
    ): ApiResult<Unit> {
        val saved = when (val read = api.homeOverrides(owner)) {
            is ApiResult.Success -> read.data
            is ApiResult.Error -> return read
            is ApiResult.NetworkError -> return read
        }
        val result = api.replaceHome(owner, buildHomeSectionOverrides(rows, saved, baseline, newId))
        if (result is ApiResult.Success) changes.changed()
        return result
    }

    private fun noProfile() = ApiResult.Error(0, "identity_changed", "Choose a profile to arrange its Home rows.")
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun newOverrideId(): String = kotlin.uuid.Uuid.random().toString()
