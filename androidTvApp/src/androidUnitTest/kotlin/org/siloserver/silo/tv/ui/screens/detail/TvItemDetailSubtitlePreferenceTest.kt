package org.siloserver.silo.tv.ui.screens.detail

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.DefaultMetadataAiApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.MetadataAiRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SettingsRepository
import org.siloserver.silo.tv.testing.FakePlayerSettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The detail row's "Auto" subtitle preview must resolve the same preferences
 * playback will use.
 *
 * The TV settings screen writes `playback.subtitle_language` / `subtitle_mode`
 * / `show_forced_subtitles` at `scope=profile` through
 * [ProfileSettingsController]. The server does not mirror a canonical write
 * back into the `user_profiles` columns `GET /profiles` serves, so reading
 * those columns here previewed the preference from *before* the user's last
 * edit while `TvVideoPlaybackStarter` — which reads WatchDetail's
 * server-resolved `effective_*` fields — played the new one. Same screen, same
 * item, two answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvItemDetailSubtitlePreferenceTest {

    @Test
    fun `the auto preview reads the canonical values, not the stale profile columns`() =
        runDetailTest {
            val viewModel = createViewModel(
                settingsApi = FakeSettingsApi(
                    effective = effectiveOf(
                        SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE to JsonPrimitive("ja"),
                        SettingKeys.PLAYBACK_SUBTITLE_MODE to JsonPrimitive("always"),
                        SettingKeys.PLAYBACK_SHOW_FORCED_SUBTITLES to JsonPrimitive(false),
                    ),
                ),
                // What GET /profiles still serves: the pre-edit columns.
                profileSubtitleLanguage = "en",
                profileSubtitleMode = "off",
                profileShowForced = true,
            )
            awaitState(viewModel) { it.preferredSubtitleLanguage != null }

            val state = viewModel.uiState.value
            assertEquals("ja", state.preferredSubtitleLanguage)
            assertEquals("always", state.subtitleMode)
            assertEquals(false, state.showForcedSubtitles)
        }

    @Test
    fun `a resolved empty language reads as no preference, not as no subtitles`() =
        runDetailTest {
            // The canonical snapshot spells "no preference" as ""; the Auto
            // preview spells it as null and reads "" as "turn subtitles off".
            val viewModel = createViewModel(
                settingsApi = FakeSettingsApi(
                    effective = effectiveOf(
                        SettingKeys.PLAYBACK_SUBTITLE_MODE to JsonPrimitive("always"),
                    ),
                ),
                profileSubtitleLanguage = "de",
            )
            awaitState(viewModel) { it.subtitleMode == "always" }

            assertEquals(null, viewModel.uiState.value.preferredSubtitleLanguage)
        }

    @Test
    fun `the profile columns stay the fallback when the contract cannot be resolved`() =
        runDetailTest {
            val viewModel = createViewModel(
                settingsApi = FakeSettingsApi(
                    capabilities = SettingsCapabilitiesResult.ServerUpgradeRequired,
                ),
                profileSubtitleLanguage = "de",
                profileSubtitleMode = "always",
                profileShowForced = false,
            )
            awaitState(viewModel) { it.preferredSubtitleLanguage != null }

            val state = viewModel.uiState.value
            assertEquals("de", state.preferredSubtitleLanguage)
            assertEquals("always", state.subtitleMode)
            assertEquals(false, state.showForcedSubtitles)
        }

    // ------------------------------------------------------------------

    private val createdViewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    private fun runDetailTest(block: suspend () -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            // Cancel viewModelScope work BEFORE resetting Main; a coroutine
            // still alive when the next test calls setMain throws from
            // TestMainDispatcher.
            createdViewModels.forEach { it.viewModelScope.cancel() }
            createdViewModels.clear()
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        settingsApi: SettingsApi,
        profileSubtitleLanguage: String? = null,
        profileSubtitleMode: String? = null,
        profileShowForced: Boolean? = null,
    ): TvItemDetailViewModel {
        val client = detailClient(profileSubtitleLanguage, profileSubtitleMode, profileShowForced)
        return TvItemDetailViewModel(
            catalogRepository = CatalogRepository(CatalogApi(client)),
            personalDataRepository = PersonalDataRepository(PersonalDataApi(client)),
            playerSettingsStore = FakePlayerSettingsStore(),
            profileRepository = ProfileRepository(ProfileApi(client), FakeTokenManager()),
            profileSettings = ProfileSettingsController(SettingsRepository(settingsApi)),
            metadataAiRepository = MetadataAiRepository(DefaultMetadataAiApi(client)),
            contentId = CONTENT_ID,
        ).also { createdViewModels += it }
    }

    private suspend fun awaitState(
        viewModel: TvItemDetailViewModel,
        predicate: (TvItemDetailUiState) -> Boolean,
    ) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(30_000) {
                while (!predicate(viewModel.uiState.value)) {
                    delay(10)
                }
            }
        }
    }

    private fun effectiveOf(vararg values: Pair<String, JsonElement>) =
        EffectiveSettingValuesResponse(
            settings = values.map { (key, value) ->
                EffectiveSettingValue(
                    key = key,
                    value = value,
                    source = SettingScope.PROFILE.wire,
                )
            },
        )

    private class FakeSettingsApi(
        private val capabilities: SettingsCapabilitiesResult =
            SettingsCapabilitiesResult.Available(SettingsContractCapabilities(revision = 1)),
        private val effective: EffectiveSettingValuesResponse = EffectiveSettingValuesResponse(),
    ) : SettingsApi(HttpClient()) {
        override suspend fun getContractCapabilities(): SettingsCapabilitiesResult = capabilities

        override suspend fun getEffectiveValues(
            keys: List<String>,
            libraryIds: List<Int>,
            seriesIds: List<String>,
        ): ApiResult<EffectiveSettingValuesResponse> = ApiResult.Success(effective)
    }

    private class FakeTokenManager : TokenManager {
        override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
        override suspend fun getAccessToken(): String = "token"
        override suspend fun getRefreshToken(): String? = null
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
        override suspend fun clearTokens() = Unit
        override suspend fun invalidateSession() = Unit
        override suspend fun getProfileId(): String = PROFILE_ID
        override suspend fun setProfileId(profileId: String?) = Unit
        override suspend fun getProfileToken(): String? = null
        override suspend fun setProfileToken(token: String?) = Unit
        override suspend fun getServerUrl(): String = "https://tv.example"
        override suspend fun setServerUrl(url: String) = Unit
        override suspend fun getCurrentServerId(): String = "server"
        override suspend fun switchActiveServer(serverId: String?) = Unit
        override suspend fun signOutCurrentServer() = Unit
    }

    private fun detailClient(
        subtitleLanguage: String?,
        subtitleMode: String?,
        showForced: Boolean?,
    ): HttpClient = HttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/profiles" -> respond(
                    content = buildString {
                        append("""{"profiles":[{"id":"$PROFILE_ID","name":"Profile"""")
                        subtitleLanguage?.let { append(""","subtitle_language":"$it"""") }
                        subtitleMode?.let { append(""","subtitle_mode":"$it"""") }
                        showForced?.let { append(""","show_forced_subtitles":$it""") }
                        append("}]}")
                    },
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )
                "/api/v1/catalog/items/$CONTENT_ID" -> respond(
                    content = """
                        {"content_id":"$CONTENT_ID","type":"movie","title":"Detail"}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )
                else -> respond(
                    content = """{"error":"not_found","message":"not found"}""",
                    status = HttpStatusCode.NotFound,
                    headers = JSON_HEADERS,
                )
            }
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
    }

    private companion object {
        const val CONTENT_ID = "movie-1"
        const val PROFILE_ID = "profile-1"
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
