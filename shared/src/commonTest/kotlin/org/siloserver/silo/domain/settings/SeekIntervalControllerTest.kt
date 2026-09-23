package org.siloserver.silo.domain.settings

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.LegacyAudiobookIntervals
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekImportOutcome
import org.siloserver.silo.model.settings.SeekIntervalPair
import org.siloserver.silo.model.settings.SeekIntervals
import org.siloserver.silo.model.settings.SeekMedia
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
import kotlin.test.assertTrue

class SeekIntervalControllerTest {

    private data class Put(val key: String, val scope: SettingScope, val value: JsonElement)

    private class FakeSettingsApi(
        val capabilities: ApiResult<SettingsContractCapabilities> = ApiResult.Success(SUPPORTED),
        val effective: ApiResult<EffectiveSettingValuesResponse> =
            ApiResult.Success(EffectiveSettingValuesResponse()),
        val putResult: (String) -> ApiResult<StoredSettingValue> = {
            ApiResult.Success(StoredSettingValue(key = it, scope = "profile"))
        },
    ) : SettingsApi(
        org.siloserver.silo.network.apiv2.SettingsV2Api(
            HttpClient(),
            org.siloserver.silo.network.TokenManagerImpl(),
            org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted,
        ),
    ) {
        val puts = mutableListOf<Put>()
        val effectiveReads = mutableListOf<List<String>>()

        override suspend fun getContractCapabilities(): ApiResult<SettingsContractCapabilities> = capabilities

        override suspend fun getEffectiveValues(
            keys: List<String>,
            libraryIds: List<Int>,
            seriesIds: List<String>,
            authority: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<EffectiveSettingValuesResponse> {
            effectiveReads += keys
            return effective
        }

        override suspend fun putValue(
            key: String,
            scope: SettingScopeIdentity,
            value: JsonElement,
            profileId: String?,
            authority: org.siloserver.silo.network.AuthScopeSnapshot?,
        ): ApiResult<StoredSettingValue> {
            puts += Put(key, scope.scope, value)
            return putResult(key)
        }
    }

    @Test
    fun loadResolvesBothMediaTypesFromOneBatchedRead() = runTest {
        val api = FakeSettingsApi(
            effective = ApiResult.Success(
                EffectiveSettingValuesResponse(
                    settings = listOf(
                        effective(SettingKeys.PLAYER_VIDEO_SKIP_BACK_SECONDS, 5),
                        effective(SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS, 45),
                        effective(SettingKeys.PLAYER_AUDIOBOOK_SKIP_BACK_SECONDS, 15),
                        effective(SettingKeys.PLAYER_AUDIOBOOK_SKIP_FORWARD_SECONDS, 90),
                    ),
                ),
            ),
        )
        val result = SeekIntervalController(SettingsRepository(api)).load()

        assertEquals(
            SeekIntervalController.LoadResult.Supported(
                video = SeekIntervalPair(5, 45),
                audiobook = SeekIntervalPair(15, 90),
            ),
            result,
        )
        assertEquals(listOf(SeekIntervals.KEYS), api.effectiveReads)
    }

    @Test
    fun missingKeysResolveToContractDefaults() = runTest {
        val result = SeekIntervalController(SettingsRepository(FakeSettingsApi())).load()
        assertEquals(
            SeekIntervalController.LoadResult.Supported(SeekIntervals.DEFAULTS, SeekIntervals.DEFAULTS),
            result,
        )
    }

    @Test
    fun anOlderManifestIsUnsupportedAndSkipsTheRead() = runTest {
        val api = FakeSettingsApi(capabilities = ApiResult.Success(SUPPORTED.copy(manifestRevision = 8)))
        assertEquals(SeekIntervalController.LoadResult.Unsupported, SeekIntervalController(SettingsRepository(api)).load())
        assertTrue(api.effectiveReads.isEmpty())
    }

    @Test
    fun capabilities404IsUnsupportedButOtherFailuresAreTransient() = runTest {
        val notFound = FakeSettingsApi(capabilities = ApiResult.Error(404, "not_found", "Not Found"))
        assertEquals(
            SeekIntervalController.LoadResult.Unsupported,
            SeekIntervalController(SettingsRepository(notFound)).load(),
        )

        val serverError = FakeSettingsApi(capabilities = ApiResult.Error(503, "unavailable", "Down"))
        assertIs<SeekIntervalController.LoadResult.Failed>(SeekIntervalController(SettingsRepository(serverError)).load())

        val offline = FakeSettingsApi(capabilities = ApiResult.NetworkError(RuntimeException("offline")))
        assertIs<SeekIntervalController.LoadResult.Failed>(SeekIntervalController(SettingsRepository(offline)).load())

        val readFails = FakeSettingsApi(effective = ApiResult.Error(500, "boom", "Boom"))
        assertIs<SeekIntervalController.LoadResult.Failed>(SeekIntervalController(SettingsRepository(readFails)).load())
    }

    @Test
    fun saveWritesAJsonNumberAtProfileScope() = runTest {
        val api = FakeSettingsApi()
        val result = SeekIntervalController(SettingsRepository(api))
            .save(SeekMedia.Video, SeekDirection.Forward, 60)

        assertEquals(SeekIntervalController.SaveResult.Saved(60), result)
        assertEquals(
            listOf(Put(SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS, SettingScope.PROFILE, JsonPrimitive(60))),
            api.puts,
        )
    }

    @Test
    fun anInvalidValueIsNeverSent() = runTest {
        val api = FakeSettingsApi()
        val result = SeekIntervalController(SettingsRepository(api))
            .save(SeekMedia.Audiobook, SeekDirection.Back, 20)

        assertEquals(SeekIntervalController.SaveResult.Invalid(20), result)
        assertTrue(api.puts.isEmpty())
    }

    @Test
    fun importReportsEachDirectionAndOneFailureDoesNotBlockTheOther() = runTest {
        val api = FakeSettingsApi(
            putResult = { key ->
                if (key == SettingKeys.PLAYER_AUDIOBOOK_SKIP_FORWARD_SECONDS) {
                    ApiResult.Error(500, "boom", "Server error")
                } else {
                    ApiResult.Success(StoredSettingValue(key = key, scope = "profile"))
                }
            },
        )
        val result = SeekIntervalController(SettingsRepository(api))
            .importLegacyAudiobook(LegacyAudiobookIntervals(backSeconds = 15, forwardSeconds = 60))

        assertEquals(SeekImportOutcome.Imported(15), result.back)
        assertEquals(SeekImportOutcome.Failed(60, "Server error"), result.forward)
        assertEquals(
            setOf(
                Put(SettingKeys.PLAYER_AUDIOBOOK_SKIP_BACK_SECONDS, SettingScope.PROFILE, JsonPrimitive(15)),
                Put(SettingKeys.PLAYER_AUDIOBOOK_SKIP_FORWARD_SECONDS, SettingScope.PROFILE, JsonPrimitive(60)),
            ),
            api.puts.toSet(),
        )
    }

    @Test
    fun importSkipsDirectionsWithoutAStoredOrValidValue() = runTest {
        val api = FakeSettingsApi()
        val result = SeekIntervalController(SettingsRepository(api))
            .importLegacyAudiobook(LegacyAudiobookIntervals(backSeconds = null, forwardSeconds = 20))

        assertEquals(SeekImportOutcome.NotStored, result.back)
        assertEquals(SeekImportOutcome.Invalid(20), result.forward)
        assertTrue(api.puts.isEmpty())
    }

    private fun effective(key: String, seconds: Int) =
        EffectiveSettingValue(key = key, value = JsonPrimitive(seconds), source = "profile")

    private companion object {
        val SUPPORTED = SettingsContractCapabilities(
            apiVersion = 1,
            manifestRevision = 9,
            supportsBatchedEffective = true,
        )
    }
}
