package org.siloserver.silo.domain.settings

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleArtControllerTest {

    private sealed interface Call {
        data class Put(val scope: SettingScope, val value: Boolean) : Call
        data class Delete(val scope: SettingScope) : Call
    }

    /**
     * A settings server that stores `ui.title_art` at profile and
     * profile_device scope and resolves it in the contract's order
     * (profile → profile_device → default).
     */
    private class FakeTitleArtServer(
        var capabilities: ApiResult<SettingsContractCapabilities> = ApiResult.Success(SUPPORTED),
        var profileValue: Boolean? = null,
        var deviceValue: Boolean? = null,
    ) : SettingsApi(
        org.siloserver.silo.network.apiv2.SettingsV2Api(
            HttpClient(),
            org.siloserver.silo.network.TokenManagerImpl(),
            org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted,
        ),
    ) {
        val calls = mutableListOf<Call>()
        var failPutAt: SettingScope? = null
        var deleteResult: ApiResult<Unit>? = null
        var effectiveFails = false

        override suspend fun getContractCapabilities(): ApiResult<SettingsContractCapabilities> = capabilities

        override suspend fun getEffectiveValues(
            keys: List<String>,
            libraryIds: List<Int>,
            seriesIds: List<String>,
            authority: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<EffectiveSettingValuesResponse> {
            if (effectiveFails) return ApiResult.Error(500, "boom", "Boom")
            val (value, source) = when {
                profileValue != null -> profileValue!! to "profile"
                deviceValue != null -> deviceValue!! to "profile_device"
                else -> true to EffectiveSettingValue.SOURCE_DEFAULT
            }
            return ApiResult.Success(
                EffectiveSettingValuesResponse(
                    settings = listOf(
                        EffectiveSettingValue(
                            key = SettingKeys.UI_TITLE_ART,
                            value = JsonPrimitive(value),
                            source = source,
                        ),
                    ),
                ),
            )
        }

        override suspend fun putValue(
            key: String,
            scope: SettingScopeIdentity,
            value: JsonElement,
            profileId: String?,
            authority: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<StoredSettingValue> {
            val bool = (value as JsonPrimitive).content.toBooleanStrict()
            calls += Call.Put(scope.scope, bool)
            if (failPutAt == scope.scope) return ApiResult.Error(500, "boom", "Server error")
            when (scope.scope) {
                SettingScope.PROFILE -> profileValue = bool
                SettingScope.PROFILE_DEVICE -> deviceValue = bool
                else -> return ApiResult.Error(400, "invalid_scope", "Scope not allowed")
            }
            return ApiResult.Success(StoredSettingValue(key = key, scope = scope.scope.wire))
        }

        override suspend fun deleteValue(
            key: String,
            scope: SettingScopeIdentity,
            profileId: String?,
            authority: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<Unit> {
            calls += Call.Delete(scope.scope)
            deleteResult?.let { return it }
            val existed = when (scope.scope) {
                SettingScope.PROFILE -> (profileValue != null).also { profileValue = null }
                SettingScope.PROFILE_DEVICE -> (deviceValue != null).also { deviceValue = null }
                else -> false
            }
            return if (existed) ApiResult.Success(Unit) else ApiResult.Error(404, "not_found", "Not Found")
        }
    }

    private fun controller(server: FakeTitleArtServer) = TitleArtController(SettingsRepository(server))

    @Test
    fun loadReadsTheServerResolvedValueAndItsSource() = runTest {
        assertEquals(
            TitleArtController.LoadResult.Supported(TitleArtPreference(showTitleArt = true, appliesToAllDevices = false)),
            controller(FakeTitleArtServer()).load(),
        )
        assertEquals(
            TitleArtController.LoadResult.Supported(TitleArtPreference(showTitleArt = false, appliesToAllDevices = false)),
            controller(FakeTitleArtServer(deviceValue = false)).load(),
        )
        // Profile wins over this device's own value, and marks "all devices".
        assertEquals(
            TitleArtController.LoadResult.Supported(TitleArtPreference(showTitleArt = true, appliesToAllDevices = true)),
            controller(FakeTitleArtServer(profileValue = true, deviceValue = false)).load(),
        )
    }

    @Test
    fun serversBeforeRevision16AreUnsupportedAndNotRead() = runTest {
        val older = FakeTitleArtServer(capabilities = ApiResult.Success(SUPPORTED.copy(manifestRevision = 15)))
        assertEquals(TitleArtController.LoadResult.Unsupported, controller(older).load())

        val noContract = FakeTitleArtServer(capabilities = ApiResult.Error(404, "not_found", "Not Found"))
        assertEquals(TitleArtController.LoadResult.Unsupported, controller(noContract).load())

        val down = FakeTitleArtServer(capabilities = ApiResult.Error(503, "unavailable", "Down"))
        assertIs<TitleArtController.LoadResult.Failed>(controller(down).load())

        val offline = FakeTitleArtServer(capabilities = ApiResult.NetworkError(RuntimeException("offline")))
        assertIs<TitleArtController.LoadResult.Failed>(controller(offline).load())
    }

    @Test
    fun mainSwitchWritesThisDeviceUnlessTheValueAppliesToAllDevices() = runTest {
        val server = FakeTitleArtServer()
        val result = controller(server).setShowTitleArt(show = false, appliesToAllDevices = false)

        assertEquals(listOf<Call>(Call.Put(SettingScope.PROFILE_DEVICE, false)), server.calls)
        assertEquals(
            TitleArtController.WriteResult.Saved(TitleArtPreference(showTitleArt = false, appliesToAllDevices = false)),
            result,
        )
    }

    @Test
    fun mainSwitchWritesTheProfileWhileTheValueAppliesToAllDevices() = runTest {
        val server = FakeTitleArtServer(profileValue = true)
        val result = controller(server).setShowTitleArt(show = false, appliesToAllDevices = true)

        assertEquals(listOf<Call>(Call.Put(SettingScope.PROFILE, false)), server.calls)
        assertEquals(
            TitleArtController.WriteResult.Saved(TitleArtPreference(showTitleArt = false, appliesToAllDevices = true)),
            result,
        )
    }

    @Test
    fun applyToAllDevicesOnWritesTheCurrentValueAtProfileScope() = runTest {
        val server = FakeTitleArtServer(deviceValue = false)
        val result = controller(server).setAppliesToAllDevices(enabled = true, showTitleArt = false)

        assertEquals(listOf<Call>(Call.Put(SettingScope.PROFILE, false)), server.calls)
        assertEquals(
            TitleArtController.WriteResult.Saved(TitleArtPreference(showTitleArt = false, appliesToAllDevices = true)),
            result,
        )
    }

    @Test
    fun applyToAllDevicesOffPinsThisDeviceBeforeClearingTheProfile() = runTest {
        val server = FakeTitleArtServer(profileValue = false, deviceValue = true)
        val result = controller(server).setAppliesToAllDevices(enabled = false, showTitleArt = false)

        assertEquals(
            listOf(Call.Put(SettingScope.PROFILE_DEVICE, false), Call.Delete(SettingScope.PROFILE)),
            server.calls,
        )
        // This device keeps the look it had; the profile value is gone.
        assertNull(server.profileValue)
        assertEquals(false, server.deviceValue)
        assertEquals(
            TitleArtController.WriteResult.Saved(TitleArtPreference(showTitleArt = false, appliesToAllDevices = false)),
            result,
        )
    }

    @Test
    fun aMissingProfileValueOnDeleteCountsAsCleared() = runTest {
        val server = FakeTitleArtServer()
        server.deleteResult = ApiResult.Error(404, "not_found", "Not Found")
        val result = controller(server).setAppliesToAllDevices(enabled = false, showTitleArt = true)

        assertEquals(
            listOf(Call.Put(SettingScope.PROFILE_DEVICE, true), Call.Delete(SettingScope.PROFILE)),
            server.calls,
        )
        assertIs<TitleArtController.WriteResult.Saved>(result)
    }

    @Test
    fun aFailedDevicePinNeverClearsTheProfileValue() = runTest {
        val server = FakeTitleArtServer(profileValue = false)
        server.failPutAt = SettingScope.PROFILE_DEVICE
        val result = controller(server).setAppliesToAllDevices(enabled = false, showTitleArt = false)

        assertEquals(listOf<Call>(Call.Put(SettingScope.PROFILE_DEVICE, false)), server.calls)
        assertEquals(false, server.profileValue)
        assertIs<TitleArtController.WriteResult.Failed>(result)
    }

    @Test
    fun aFailedProfileDeleteOtherThan404IsReported() = runTest {
        val server = FakeTitleArtServer(profileValue = false)
        server.deleteResult = ApiResult.Error(500, "boom", "Server error")
        val result = controller(server).setAppliesToAllDevices(enabled = false, showTitleArt = false)

        assertEquals(TitleArtController.WriteResult.Failed("Server error"), result)
    }

    @Test
    fun aWriteThatLandedStillSucceedsWhenTheReReadFails() = runTest {
        val server = FakeTitleArtServer()
        server.effectiveFails = true
        val result = controller(server).setShowTitleArt(show = false, appliesToAllDevices = false)

        assertEquals(TitleArtController.WriteResult.Saved(resolved = null), result)
        assertTrue(server.calls.isNotEmpty())
    }

    @Test
    fun aNonBooleanValueResolvesToTheDefault() {
        val preference = TitleArtController.preferenceOf(
            EffectiveSettingValue(key = SettingKeys.UI_TITLE_ART, value = JsonPrimitive("nope"), source = "profile"),
        )
        assertEquals(TitleArtPreference(showTitleArt = true, appliesToAllDevices = true), preference)
    }

    private companion object {
        val SUPPORTED = SettingsContractCapabilities(
            apiVersion = 1,
            manifestRevision = 16,
            supportsBatchedEffective = true,
        )
    }
}
