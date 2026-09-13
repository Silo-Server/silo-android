package org.siloserver.silo.network.api

import org.siloserver.silo.model.settings.LibraryPlaybackPrefRequest
import org.siloserver.silo.model.settings.LibraryPlaybackPrefsResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.SettingsV2Api

open class LibraryPlaybackPrefsApi(
    private val v2: SettingsV2Api,
) {

    open suspend fun list(): ApiResult<LibraryPlaybackPrefsResponse> = v2.libraryPreferences()

    open suspend fun set(
        libraryId: Int,
        request: LibraryPlaybackPrefRequest,
    ): ApiResult<Unit> = v2.patchLibraryPreference(libraryId, request)

    open suspend fun delete(libraryId: Int): ApiResult<Unit> = v2.deleteLibraryPreference(libraryId)
}
