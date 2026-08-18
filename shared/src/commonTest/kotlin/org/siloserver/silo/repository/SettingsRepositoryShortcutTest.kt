package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.siloserver.silo.model.settings.PrimaryMenuBuiltin
import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.model.settings.UiCustomizationCodec
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SettingsRepositoryShortcutTest {
    @Test
    fun atomicShortcutWriteEncodesItemAndForwardsIntentIdentity() = runTest {
        val api = CapturingSettingsApi()
        val repository = SettingsRepository(api)
        val item = PrimaryMenuItem.Collection("favorites", "Family Favorites", libraryId = 7)

        val result = repository.setNavigationShortcutPresent(
            item = item,
            present = false,
            mutationId = "shortcut-mut-2",
            profileId = "profile-1",
        )

        assertIs<ApiResult.Success<*>>(result)
        assertEquals(UiCustomizationCodec.encodeShortcutItem(item), api.item)
        assertEquals(false, api.present)
        assertEquals("shortcut-mut-2", api.mutationId)
        assertEquals("profile-1", api.profileId)
    }

    @Test
    fun builtinsAreRejectedBeforeAtomicShortcutRequest() = runTest {
        val api = CapturingSettingsApi()
        val repository = SettingsRepository(api)

        val result = repository.setNavigationShortcutPresent(
            item = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
            present = true,
        )

        assertIs<ApiResult.Error>(result)
        assertEquals("invalid_shortcut_item", result.error)
        assertEquals(null, api.item)
    }

    private class CapturingSettingsApi : SettingsApi(HttpClient()) {
        var item: JsonElement? = null
        var present: Boolean? = null
        var mutationId: String? = null
        var profileId: String? = null

        override suspend fun putNavigationShortcutItem(
            item: JsonElement,
            present: Boolean,
            mutationId: String,
            profileId: String?,
            authScope: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<StoredSettingValue> {
            this.item = item
            this.present = present
            this.mutationId = mutationId
            this.profileId = profileId
            return ApiResult.Success(
                StoredSettingValue(
                    key = "nav.shortcuts",
                    scope = "profile",
                    value = kotlinx.serialization.json.buildJsonObject {
                        put("items", kotlinx.serialization.json.buildJsonArray { })
                    },
                ),
            )
        }
    }
}
