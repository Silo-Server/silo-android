package org.siloserver.silo.tv.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.DefaultMetadataAiApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.MetadataAiRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SettingsRepository
import org.siloserver.silo.repository.port.LocalTrackSelection
import org.siloserver.silo.repository.port.OutboxHandle
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.repository.port.WriteOutcome
import org.siloserver.silo.tv.testing.FakePlayerSettingsStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real detail ViewModel refresh path: series -> seasons -> episodes
 * -> asynchronous target detail, including session and durable track merging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvNextUpSelectionHandoffTest {
    @Test
    fun cancelledPriorSeasonCannotEndReplacementLoad() = runDetailTest {
        val scenario = Scenario(suffix = "-cancelled-season-owner")
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)

        scenario.seasonOneEpisodesGate = CompletableDeferred()
        scenario.seasonTwoEpisodesGate = CompletableDeferred()
        fixture.viewModel.onSeasonSelected(2)
        awaitCondition {
            fixture.viewModel.uiState.value.let { it.selectedSeason == 2 && it.episodesLoading }
        }

        fixture.viewModel.onSeasonSelected(1)
        awaitCondition { fixture.viewModel.uiState.value.selectedSeason == 1 }
        delay(20)

        assertTrue(
            fixture.viewModel.uiState.value.episodesLoading,
            "a canceled season request must not end the replacement request's loading state",
        )

        scenario.seasonOneEpisodesGate?.complete(Unit)
        scenario.seasonTwoEpisodesGate?.complete(Unit)
        awaitCondition {
            fixture.viewModel.uiState.value.let { state ->
                !state.episodesLoading && state.episodes.all { it.seasonNumber == 1 }
            }
        }
    }

    @Test
    fun seasonRefreshStartedAfterSelectionKeepsThatSelection() = runDetailTest {
        val scenario = Scenario(suffix = "-season-refresh-after-selection")
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)

        fixture.viewModel.onSeasonSelected(2)
        awaitCondition {
            fixture.viewModel.uiState.value.episodes.any { it.seasonNumber == 2 }
        }

        val seasonsBeforeRefresh = scenario.seasonsRequests.get()
        scenario.seasonsGate = CompletableDeferred()
        fixture.viewModel.loadAll()
        awaitCondition { scenario.seasonsRequests.get() > seasonsBeforeRefresh }

        scenario.seasonsGate?.complete(Unit)
        awaitCondition { !fixture.viewModel.uiState.value.seasonsLoading }

        assertEquals(2, fixture.viewModel.uiState.value.selectedSeason)
        assertTrue(fixture.viewModel.uiState.value.episodes.all { it.seasonNumber == 2 })
    }

    @Test
    fun delayedSeasonRefreshPreservesNewerSelectionAndInvalidatesOldPlayTarget() = runDetailTest {
        val scenario = Scenario(suffix = "-season-refresh")
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)

        val seasonsBeforeRefresh = scenario.seasonsRequests.get()
        scenario.seasonsGate = CompletableDeferred()
        scenario.seasonTwoEpisodesGate = CompletableDeferred()
        fixture.viewModel.loadAll()
        awaitCondition { scenario.seasonsRequests.get() > seasonsBeforeRefresh }

        fixture.viewModel.onSeasonSelected(2)
        awaitCondition {
            fixture.viewModel.uiState.value.let { it.selectedSeason == 2 && it.episodesLoading }
        }
        assertEquals(
            scenario.episodeOneId,
            fixture.viewModel.uiState.value.nextUpEpisode?.contentId,
            "the previous target stays rendered so the action row keeps its geometry",
        )
        assertFalse(
            fixture.viewModel.uiState.value.nextUpTargetReady,
            "the placeholder must not remain playable while season 2 loads",
        )

        scenario.seasonsGate?.complete(Unit)
        awaitCondition { !fixture.viewModel.uiState.value.seasonsLoading }
        assertEquals(
            2,
            fixture.viewModel.uiState.value.selectedSeason,
            "a late seasons refresh must not replay its older initial selection",
        )

        scenario.seasonTwoEpisodesGate?.complete(Unit)
        awaitCondition {
            fixture.viewModel.uiState.value.episodes.any { it.seasonNumber == 2 }
        }
        assertEquals(2, fixture.viewModel.uiState.value.selectedSeason)
        assertTrue(fixture.viewModel.uiState.value.nextUpTargetReady)
    }

    @Test
    fun focusedSeriesEpisodeBecomesHeroPlayAndSelectorTarget() = runDetailTest {
        val scenario = Scenario(suffix = "-focus-target")
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)

        fixture.viewModel.onSeriesEpisodeActivated(scenario.episodeTwoId)
        awaitEpisode(fixture.viewModel, scenario.episodeTwoId)

        val state = fixture.viewModel.uiState.value
        assertEquals(scenario.episodeTwoId, state.nextUpEpisode?.contentId)
        assertEquals(scenario.episodeTwoId, state.nextUpPlaybackDetail?.contentId)
    }

    @Test
    fun absentVersionCodecAndContainerDecodeAsNull() = runDetailTest {
        val scenario = Scenario(
            suffix = "-null-version-metadata",
            oldVersions = listOf(version(101, "1080p", codec = null, container = null)),
        )
        val fixture = createFixture(scenario)

        awaitEpisode(fixture.viewModel, scenario.episodeOneId)

        val version = fixture.viewModel.uiState.value.nextUpPlaybackDetail?.versions?.single()
        assertNull(version?.codecVideo)
        assertNull(version?.container)
    }

    @Test
    fun changingNextUpResolvesOldSourceAgainstNewEpisodeFiles() = runDetailTest {
        val scenario = Scenario(
            suffix = "-source",
            oldVersions = listOf(version(101, "720p"), version(102, "1080p", codec = "hevc")),
            newVersions = listOf(version(201, "480p"), version(202, "1080p", codec = "hevc")),
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpVersionSelected(102)

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        assertEquals(202, fixture.viewModel.uiState.value.selectedNextUpFileId)
    }

    @Test
    fun changingNextUpResolvesSubtitleAtDifferentCombinedIndex() = runDetailTest {
        val french = subtitle(index = 4, language = "fre", external = true, title = "French")
        val scenario = Scenario(
            suffix = "-subtitle-index",
            oldVersions = listOf(
                version(
                    101,
                    "1080p",
                    subtitles = listOf(
                        subtitle(index = 9, language = "eng"),
                        french,
                    ),
                ),
            ),
            newVersions = listOf(
                version(
                    201,
                    "1080p",
                    subtitles = listOf(
                        subtitle(index = 2, language = "spa", external = true),
                        subtitle(index = 8, language = "eng"),
                        french.copy(index = 7),
                    ),
                ),
            ),
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        // Old combined index 0 is the sole external track. On the target,
        // French is the second external track and therefore combined index 1.
        fixture.viewModel.onNextUpSubtitleTrackSelected(0)

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        assertEquals(1, fixture.viewModel.uiState.value.selectedNextUpSubtitleIndex)
    }

    @Test
    fun lastPlayedAutoSourceCapturesAndResolvesSubtitleAgainstTheDisplayedVersions() = runDetailTest {
        val scenario = Scenario(
            suffix = "-auto-source-subtitle",
            oldVersions = listOf(
                version(101, "1080p", subtitles = listOf(subtitle(1, "eng"))),
                version(102, "720p", subtitles = listOf(subtitle(2, "fre", external = true))),
            ),
            newVersions = listOf(
                version(201, "1080p", subtitles = listOf(subtitle(3, "eng"))),
                version(
                    202,
                    "720p",
                    subtitles = listOf(
                        subtitle(4, "spa", external = true),
                        subtitle(5, "fre", external = true),
                    ),
                ),
            ),
            oldLastFileId = 102,
            newLastFileId = 202,
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpSubtitleTrackSelected(0)

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        assertNull(fixture.viewModel.uiState.value.selectedNextUpFileId)
        assertEquals(1, fixture.viewModel.uiState.value.selectedNextUpSubtitleIndex)
    }

    @Test
    fun preferredQualityAutoSourceCapturesAndResolvesSubtitleAgainstTheDisplayedVersions() = runDetailTest {
        val scenario = Scenario(
            suffix = "-preferred-quality-subtitle",
            oldVersions = listOf(
                version(101, "1080p", subtitles = listOf(subtitle(1, "eng"))),
                version(102, "720p", subtitles = listOf(subtitle(2, "fre", external = true))),
            ),
            newVersions = listOf(
                version(201, "1080p", subtitles = listOf(subtitle(3, "eng"))),
                version(
                    202,
                    "720p",
                    subtitles = listOf(
                        subtitle(4, "spa", external = true),
                        subtitle(5, "fre", external = true),
                    ),
                ),
            ),
            preferredQuality = "720p",
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpSubtitleTrackSelected(0)

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        assertNull(fixture.viewModel.uiState.value.selectedNextUpFileId)
        assertEquals(1, fixture.viewModel.uiState.value.selectedNextUpSubtitleIndex)
    }

    @Test
    fun explicitOffRemainsOffAcrossNextUpRefresh() = runDetailTest {
        val scenario = Scenario(suffix = "-off")
        TvDetailTrackSelectionSession.remember(
            scenario.episodeTwoId,
            fileId = 201,
            audio = null,
            subtitle = 0,
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpSubtitleTrackSelected(-1)

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        assertEquals(-1, fixture.viewModel.uiState.value.selectedNextUpSubtitleIndex)
    }

    @Test
    fun missingExplicitSubtitleUsesAutoAndDoesNotRestoreTargetDurableSubtitle() = runDetailTest {
        val oldFrench = subtitle(index = 4, language = "fre", external = true, title = "French")
        val targetEnglish = subtitle(index = 8, language = "eng", title = "English")
        val targetAudio = listOf(
            AudioTrack(index = 3, codec = "aac", language = "eng"),
            AudioTrack(index = 7, codec = "ac3", language = "jpn"),
        )
        val scenario = Scenario(
            suffix = "-missing-subtitle",
            oldVersions = listOf(version(101, "1080p", subtitles = listOf(oldFrench))),
            newVersions = listOf(
                version(201, "1080p", subtitles = listOf(targetEnglish), audio = targetAudio),
            ),
        )
        val fixture = createFixture(scenario)
        fixture.userState.saved[scenario.episodeTwoId to 201] = LocalTrackSelection(
            audioFingerprint = audioTrackFingerprint(targetAudio[1]),
            subtitleFingerprint = subtitleTrackFingerprint(targetEnglish),
        )
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpSubtitleTrackSelected(0)

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        val state = fixture.viewModel.uiState.value
        assertNull(state.selectedNextUpSubtitleIndex)
        assertEquals(1, state.selectedNextUpAudioIndex, "durable audio behavior stays unchanged")
    }

    @Test
    fun autoAllowsExistingTargetDurableSubtitleRestore() = runDetailTest {
        val targetEnglish = subtitle(index = 8, language = "eng", title = "English")
        val scenario = Scenario(
            suffix = "-auto-durable",
            newVersions = listOf(version(201, "1080p", subtitles = listOf(targetEnglish))),
        )
        val fixture = createFixture(scenario)
        fixture.userState.saved[scenario.episodeTwoId to 201] = LocalTrackSelection(
            audioFingerprint = null,
            subtitleFingerprint = subtitleTrackFingerprint(targetEnglish),
        )
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        assertEquals(0, fixture.viewModel.uiState.value.selectedNextUpSubtitleIndex)
    }

    @Test
    fun staleRefreshCompletionCannotApplyHandoffToAnotherEpisode() = runDetailTest {
        val scenario = Scenario(suffix = "-stale")
        scenario.episodeOneWatchGate = CompletableDeferred()
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        val staleGate = CompletableDeferred<Unit>()
        val freshGate = CompletableDeferred<Unit>()
        scenario.episodeOneResponses.addLast(
            DetailResponse(staleGate, itemDetailJson(scenario.episodeOneId, listOf(version(801, "720p")))),
        )
        scenario.episodeOneResponses.addLast(
            DetailResponse(freshGate, itemDetailJson(scenario.episodeOneId, listOf(version(901, "2160p")))),
        )
        scenario.episodeOneDefaultVersions = listOf(version(901, "2160p"))

        fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, false)
        awaitCondition { scenario.pendingEpisodeOneResponses.get() == 1 }
        fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, true)
        awaitEpisode(fixture.viewModel, scenario.episodeTwoId)
        fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, false)
        staleGate.complete(Unit)
        awaitCondition { scenario.pendingEpisodeOneResponses.get() == 0 }
        delay(20)

        assertNotEquals(
            801,
            fixture.viewModel.uiState.value.nextUpPlaybackDetail?.versions?.singleOrNull()?.fileId,
        )
        freshGate.complete(Unit)
        awaitCondition {
            fixture.viewModel.uiState.value.nextUpPlaybackDetail?.versions?.singleOrNull()?.fileId == 901
        }
    }

    @Test
    fun carriedSelectionIsNotPersistedBeforeExplicitUserInput() = runDetailTest {
        val scenario = Scenario(
            suffix = "-no-persist",
            oldVersions = listOf(version(101, "1080p", subtitles = listOf(subtitle(4, "fre", external = true)))),
            newVersions = listOf(version(201, "1080p", subtitles = listOf(subtitle(7, "fre", external = true)))),
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpVersionSelected(101)
        fixture.viewModel.onNextUpSubtitleTrackSelected(0)
        awaitCondition { fixture.userState.writes.isNotEmpty() }
        fixture.userState.writes.clear()

        advanceToEpisodeTwo(fixture.viewModel, scenario)

        assertTrue(fixture.userState.writes.none { it.contentId == scenario.episodeTwoId })
        assertNull(TvDetailTrackSelectionSession.recall(scenario.episodeTwoId))
    }

    @Test
    fun profileOrServerChangeClearsPendingNextUpHandoff() = runDetailTest {
        for (kind in listOf(IdentityTransitionKind.PROFILE_SWITCH, IdentityTransitionKind.SERVER_SWITCH)) {
            val scenario = Scenario(suffix = "-${kind.name.lowercase()}")
            val gate = CompletableDeferred<Unit>()
            scenario.episodeTwoGate = gate
            val fixture = createFixture(scenario)
            awaitEpisode(fixture.viewModel, scenario.episodeOneId)
            fixture.viewModel.onNextUpVersionSelected(101)
            fixture.viewModel.onNextUpSubtitleTrackSelected(-1)

            fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, true)
            awaitCondition { scenario.episodeTwoRequests.get() > 0 }
            fixture.identityTransitions.changing(kind) {
                when (kind) {
                    IdentityTransitionKind.PROFILE_SWITCH -> fixture.tokenManager.profileId = "profile-2"
                    IdentityTransitionKind.SERVER_SWITCH -> fixture.tokenManager.serverId = "server-2"
                    else -> error("unexpected kind")
                }
            }
            gate.complete(Unit)
            awaitCondition {
                val state = fixture.viewModel.uiState.value
                state.nextUpEpisode?.contentId == scenario.episodeTwoId &&
                    state.didLoadNextUpPlaybackDetail &&
                    !state.isLoadingNextUpPlaybackDetail
            }

            val state = fixture.viewModel.uiState.value
            assertNull(state.selectedNextUpFileId)
            assertNull(state.selectedNextUpSubtitleIndex)
        }
    }

    @Test
    fun explicitSelectorInputBeforePendingInstallWins() = runDetailTest {
        val french = subtitle(index = 4, language = "fre", external = true)
        val scenario = Scenario(
            suffix = "-selector-before-install",
            oldVersions = listOf(
                version(101, "720p"),
                version(102, "1080p", codec = "hevc", subtitles = listOf(french)),
            ),
            newVersions = listOf(
                version(201, "720p"),
                version(202, "1080p", codec = "hevc", subtitles = listOf(french.copy(index = 7))),
            ),
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpVersionSelected(102)
        fixture.viewModel.onNextUpSubtitleTrackSelected(0)

        val identityGate = CompletableDeferred<Unit>()
        val previousSnapshotCalls = fixture.tokenManager.snapshotCalls.get()
        fixture.tokenManager.snapshotResponses.addLast(
            SnapshotResponse(identityGate, fixture.tokenManager.currentScope()),
        )
        fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, true)
        awaitCondition { fixture.tokenManager.snapshotCalls.get() > previousSnapshotCalls }

        fixture.viewModel.onNextUpVersionSelected(201)
        fixture.viewModel.onNextUpSubtitleTrackSelected(-1)
        identityGate.complete(Unit)
        awaitEpisode(fixture.viewModel, scenario.episodeTwoId)

        val state = fixture.viewModel.uiState.value
        assertEquals(201, state.selectedNextUpFileId)
        assertEquals(-1, state.selectedNextUpSubtitleIndex)
    }

    @Test
    fun explicitSelectorInputDuringDurableResolutionWins() = runDetailTest {
        val french = subtitle(index = 4, language = "fre", external = true)
        val scenario = Scenario(
            suffix = "-selector-during-durable",
            oldVersions = listOf(
                version(101, "720p"),
                version(102, "1080p", codec = "hevc", subtitles = listOf(french)),
            ),
            newVersions = listOf(
                version(201, "720p"),
                version(202, "1080p", codec = "hevc", subtitles = listOf(french.copy(index = 7))),
            ),
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpVersionSelected(102)
        fixture.viewModel.onNextUpSubtitleTrackSelected(0)

        val durableGate = CompletableDeferred<Unit>()
        val targetKey = scenario.episodeTwoId to 202
        fixture.userState.readGates[targetKey] = durableGate
        fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, true)
        awaitCondition { targetKey in fixture.userState.startedReads }

        fixture.viewModel.onNextUpVersionSelected(201)
        fixture.viewModel.onNextUpSubtitleTrackSelected(-1)
        durableGate.complete(Unit)
        awaitEpisode(fixture.viewModel, scenario.episodeTwoId)

        val state = fixture.viewModel.uiState.value
        assertEquals(201, state.selectedNextUpFileId)
        assertEquals(-1, state.selectedNextUpSubtitleIndex)
    }

    @Test
    fun nullAuthScopeFailsClosedBeforeHandoffInstall() = runDetailTest {
        val scenario = Scenario(suffix = "-null-scope")
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpSubtitleTrackSelected(-1)
        fixture.tokenManager.snapshotResponses.addLast(SnapshotResponse(gate = null, scope = null))

        fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, true)
        awaitCondition {
            val state = fixture.viewModel.uiState.value
            state.nextUpEpisode?.contentId == scenario.episodeTwoId &&
                state.didLoadNextUpPlaybackDetail &&
                !state.isLoadingNextUpPlaybackDetail
        }

        assertEquals(0, scenario.episodeTwoRequests.get())
        assertNull(fixture.viewModel.uiState.value.selectedNextUpSubtitleIndex)
    }

    @Test
    fun nullProfileSnapshotIsNotFilledFromSeparateTokenRead() = runDetailTest {
        val scenario = Scenario(suffix = "-null-profile")
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        fixture.viewModel.onNextUpSubtitleTrackSelected(-1)
        fixture.tokenManager.snapshotResponses.addLast(
            SnapshotResponse(gate = null, scope = fixture.tokenManager.currentScope(profileId = null)),
        )

        fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, true)
        awaitCondition {
            val state = fixture.viewModel.uiState.value
            state.nextUpEpisode?.contentId == scenario.episodeTwoId &&
                state.didLoadNextUpPlaybackDetail &&
                !state.isLoadingNextUpPlaybackDetail
        }

        val state = fixture.viewModel.uiState.value
        assertNull(state.nextUpPlaybackDetail)
        assertNull(state.selectedNextUpSubtitleIndex)
    }

    @Test
    fun lateDurableSeedForPriorEpisodeCannotRememberCarriedTarget() = runDetailTest {
        val scenario = Scenario(
            suffix = "-late-seed",
            oldVersions = listOf(version(101, "1080p")),
            newVersions = listOf(version(201, "1080p")),
        )
        val fixture = createFixture(scenario)
        awaitEpisode(fixture.viewModel, scenario.episodeOneId)
        val oldKey = scenario.episodeOneId to 101
        fixture.userState.saved[oldKey] = LocalTrackSelection(
            audioFingerprint = null,
            subtitleFingerprint = null,
        )
        val seedGate = CompletableDeferred<Unit>()
        fixture.userState.readGates[oldKey] = seedGate

        fixture.viewModel.onNextUpVersionSelected(101)
        awaitCondition { oldKey in fixture.userState.startedReads }
        fixture.viewModel.onSetEpisodeWatched(scenario.episodeOneId, true)
        awaitEpisode(fixture.viewModel, scenario.episodeTwoId)
        seedGate.complete(Unit)
        delay(20)

        assertNull(TvDetailTrackSelectionSession.recall(scenario.episodeTwoId))
    }

    // ------------------------------------------------------------------

    private val createdViewModels = mutableListOf<ViewModel>()

    private fun runDetailTest(block: suspend () -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            createdViewModels.forEach { viewModel ->
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
            createdViewModels.clear()
            Dispatchers.resetMain()
        }
    }

    private fun createFixture(scenario: Scenario): Fixture {
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val tokenManager = FakeTokenManager(identityTransitions)
        val client = scenario.client()
        val userState = RecordingUserItemState()
        val catalogRepository = CatalogRepository(
            catalogApi = CatalogApi(client),
            identityTransitions = identityTransitions,
        )
        val personalDataRepository = PersonalDataRepository(
            personalDataApi = PersonalDataApi(client),
            userItemStatePort = userState,
            identityTransitions = identityTransitions,
        )
        val profileRepository = ProfileRepository(
            profileApi = ProfileApi(client),
            tokenManager = tokenManager,
            identityTransitions = identityTransitions,
        )
        val viewModel = TvItemDetailViewModel(
            catalogRepository = catalogRepository,
            personalDataRepository = personalDataRepository,
            playerSettingsStore = FakePlayerSettingsStore().apply {
                preferredQualityFlow.value = scenario.preferredQuality
            },
            profileRepository = profileRepository,
            profileSettings = ProfileSettingsController(SettingsRepository(UnavailableSettingsApi())),
            metadataAiRepository = MetadataAiRepository(DefaultMetadataAiApi(client)),
            contentId = scenario.seriesId,
            userItemState = userState,
            tokenManager = tokenManager,
            identityTransitions = identityTransitions,
        ).also { createdViewModels += it }
        return Fixture(viewModel, userState, tokenManager, identityTransitions)
    }

    private suspend fun advanceToEpisodeTwo(viewModel: TvItemDetailViewModel, scenario: Scenario) {
        viewModel.onSetEpisodeWatched(scenario.episodeOneId, true)
        awaitEpisode(viewModel, scenario.episodeTwoId)
    }

    private suspend fun awaitEpisode(viewModel: TvItemDetailViewModel, contentId: String) {
        awaitCondition {
            val state = viewModel.uiState.value
            state.nextUpEpisode?.contentId == contentId &&
                state.nextUpPlaybackDetail?.contentId == contentId &&
                state.didLoadNextUpPlaybackDetail
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withContext(Dispatchers.IO) {
            withTimeout(30_000) {
                while (!predicate()) delay(10)
            }
        }
    }

    private data class Fixture(
        val viewModel: TvItemDetailViewModel,
        val userState: RecordingUserItemState,
        val tokenManager: FakeTokenManager,
        val identityTransitions: DefaultIdentityTransitionBarrier,
    )

    private class Scenario(
        val suffix: String = "",
        val oldVersions: List<VersionFixture> = listOf(version(101, "1080p")),
        val newVersions: List<VersionFixture> = listOf(version(201, "1080p")),
        val oldLastFileId: Int? = null,
        val newLastFileId: Int? = null,
        val preferredQuality: String = "auto",
    ) {
        val seriesId = "series$suffix"
        val episodeOneId = "episode-1$suffix"
        val episodeTwoId = "episode-2$suffix"
        val seasonTwoEpisodeId = "season-2-episode-1$suffix"
        var episodeOneWatched = false
        var episodeTwoWatched = false
        var episodeTwoGate: CompletableDeferred<Unit>? = null
        var episodeOneWatchGate: CompletableDeferred<Unit>? = null
        var seasonsGate: CompletableDeferred<Unit>? = null
        var seasonOneEpisodesGate: CompletableDeferred<Unit>? = null
        var seasonTwoEpisodesGate: CompletableDeferred<Unit>? = null
        val seasonsRequests = AtomicInteger()
        val episodeTwoRequests = AtomicInteger()
        val pendingEpisodeOneResponses = AtomicInteger()
        var episodeOneDefaultVersions = oldVersions
        val episodeOneResponses = ConcurrentLinkedDeque<DetailResponse>()

        fun client(): HttpClient = HttpClient(
            MockEngine { request ->
                fun json(content: String) = respond(
                    content = content,
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )
                when (request.url.encodedPath) {
                    "/api/v1/catalog/items/$seriesId" -> json(
                        """{"content_id":"$seriesId","type":"series","title":"Series"}""",
                    )
                    "/api/v1/catalog/series/$seriesId/seasons" -> {
                        seasonsRequests.incrementAndGet()
                        seasonsGate?.await()
                        json(
                            """{"seasons":[
                                {"content_id":"season-1$suffix","season_number":1,"title":"Season 1"},
                                {"content_id":"season-2$suffix","season_number":2,"title":"Season 2"}
                            ]}""".trimIndent(),
                        )
                    }
                    "/api/v1/catalog/series/$seriesId/seasons/1/episodes" -> {
                        seasonOneEpisodesGate?.await()
                        json(episodesJson())
                    }
                    "/api/v1/catalog/series/$seriesId/seasons/2/episodes" -> {
                        seasonTwoEpisodesGate?.await()
                        json(
                            """{"episodes":[
                                {"content_id":"$seasonTwoEpisodeId","season_number":2,"episode_number":1,"title":"Season Two"}
                            ]}""".trimIndent(),
                        )
                    }
                    "/api/v1/catalog/items/$episodeOneId" -> {
                        val queued = episodeOneResponses.pollFirst()
                        if (queued == null) {
                            json(itemDetailJson(episodeOneId, episodeOneDefaultVersions, oldLastFileId))
                        } else {
                            pendingEpisodeOneResponses.set(episodeOneResponses.size)
                            queued.gate?.await()
                            json(queued.json)
                        }
                    }
                    "/api/v1/catalog/items/$episodeTwoId" -> {
                        episodeTwoRequests.incrementAndGet()
                        episodeTwoGate?.await()
                        json(itemDetailJson(episodeTwoId, newVersions, newLastFileId))
                    }
                    "/api/v1/catalog/items/$seasonTwoEpisodeId" ->
                        json(itemDetailJson(seasonTwoEpisodeId, newVersions, newLastFileId))
                    "/api/v1/watched/$episodeOneId" -> {
                        episodeOneWatchGate?.await()
                        episodeOneWatched = request.method.value != "DELETE"
                        respond("", HttpStatusCode.NoContent)
                    }
                    "/api/v1/watched/$episodeTwoId" -> {
                        episodeTwoWatched = request.method.value != "DELETE"
                        respond("", HttpStatusCode.NoContent)
                    }
                    "/api/v1/profiles" -> json(
                        """{"profiles":[{"id":"profile-1","name":"Profile"}]}""",
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

        private fun episodesJson(): String =
            """{"episodes":[
                {"content_id":"$episodeOneId","season_number":1,"episode_number":1,"title":"One","user_data":{"played":$episodeOneWatched}},
                {"content_id":"$episodeTwoId","season_number":1,"episode_number":2,"title":"Two","user_data":{"played":$episodeTwoWatched}}
            ]}""".trimIndent()
    }

    private class RecordingUserItemState : UserItemStatePort {
        data class Write(val contentId: String, val fileId: Int, val kind: String, val fingerprint: String?)

        val saved = ConcurrentHashMap<Pair<String, Int>, LocalTrackSelection>()
        val writes = CopyOnWriteArrayList<Write>()
        val readGates = ConcurrentHashMap<Pair<String, Int>, CompletableDeferred<Unit>>()
        val startedReads: MutableSet<Pair<String, Int>> = ConcurrentHashMap.newKeySet()

        override suspend fun recordWatched(contentId: String, watched: Boolean) = OutboxHandle.NONE
        override suspend fun recordFavorite(contentId: String, favorite: Boolean) = OutboxHandle.NONE
        override suspend fun recordRating(contentId: String, rating: Int?) = OutboxHandle.NONE
        override suspend fun resolve(handle: OutboxHandle, outcome: WriteOutcome) = Unit

        override suspend fun recordAudioTrackSelection(
            contentId: String,
            fileId: Int,
            audioFingerprint: String?,
        ) {
            writes += Write(contentId, fileId, "audio", audioFingerprint)
        }

        override suspend fun recordSubtitleTrackSelection(
            contentId: String,
            fileId: Int,
            subtitleFingerprint: String?,
        ) {
            writes += Write(contentId, fileId, "subtitle", subtitleFingerprint)
        }

        override suspend fun localTrackSelection(contentId: String, fileId: Int): LocalTrackSelection? {
            val key = contentId to fileId
            startedReads += key
            readGates[key]?.await()
            return saved[key]
        }
    }

    private class FakeTokenManager(
        private val identityTransitions: IdentityTransitionBarrier,
    ) : TokenManager {
        var serverId = "server-1"
        var profileId = "profile-1"
        val snapshotCalls = AtomicInteger()
        val snapshotResponses = ConcurrentLinkedDeque<SnapshotResponse>()

        override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
        override suspend fun getAccessToken(): String = "token"
        override suspend fun getRefreshToken(): String? = null
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
        override suspend fun clearTokens() = Unit
        override suspend fun invalidateSession() = Unit
        override suspend fun getProfileId(): String = profileId
        override suspend fun setProfileId(profileId: String?) {
            this.profileId = profileId.orEmpty()
        }
        override suspend fun getProfileToken(): String? = null
        override suspend fun setProfileToken(token: String?) = Unit
        override suspend fun getServerUrl(): String = "https://tv.example"
        override suspend fun setServerUrl(url: String) = Unit
        override suspend fun getCurrentServerId(): String = serverId
        override suspend fun switchActiveServer(serverId: String?) {
            this.serverId = serverId.orEmpty()
        }
        override suspend fun signOutCurrentServer() = Unit
        fun currentScope(profileId: String? = this.profileId) = AuthScopeSnapshot(
            serverId = serverId,
            profileId = profileId,
            serverUrl = "https://tv.example",
            profileToken = null,
            identityGeneration = identityTransitions.generation.value,
        )

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? {
            snapshotCalls.incrementAndGet()
            val queued = snapshotResponses.pollFirst()
            queued?.gate?.await()
            return if (queued != null) queued.scope else currentScope()
        }
    }

    private class UnavailableSettingsApi : SettingsApi(HttpClient()) {
        override suspend fun getContractCapabilities(): SettingsCapabilitiesResult =
            SettingsCapabilitiesResult.ServerUpgradeRequired
    }

    private data class DetailResponse(val gate: CompletableDeferred<Unit>?, val json: String)
    private data class SnapshotResponse(
        val gate: CompletableDeferred<Unit>?,
        val scope: AuthScopeSnapshot?,
    )

    private data class VersionFixture(
        val fileId: Int,
        val resolution: String,
        val codec: String?,
        val container: String?,
        val subtitles: List<SubtitleTrack>,
        val audio: List<AudioTrack>,
    )

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

        fun version(
            fileId: Int,
            resolution: String,
            codec: String? = "h264",
            container: String? = "mkv",
            subtitles: List<SubtitleTrack> = emptyList(),
            audio: List<AudioTrack> = emptyList(),
        ) = VersionFixture(fileId, resolution, codec, container, subtitles, audio)

        fun subtitle(
            index: Int,
            language: String,
            external: Boolean = false,
            title: String? = null,
        ) = SubtitleTrack(
            index = index,
            codec = "srt",
            language = language,
            title = title,
            external = external,
        )

        fun itemDetailJson(
            contentId: String,
            versions: List<VersionFixture>,
            lastFileId: Int? = null,
        ): String =
            """{"content_id":"$contentId","type":"episode","title":"Episode","user_data":{"last_file_id":$lastFileId},"versions":[${versions.joinToString(",", transform = ::versionJson)}]}"""

        private fun jsonString(value: String?): String = value?.let { "\"$it\"" } ?: "null"

        private fun versionJson(version: VersionFixture): String =
            """{"file_id":${version.fileId},"resolution":"${version.resolution}","codec_video":${jsonString(version.codec)},"container":${jsonString(version.container)},"subtitle_tracks":[${version.subtitles.joinToString(",", transform = ::subtitleJson)}],"audio_tracks":[${version.audio.joinToString(",", transform = ::audioJson)}]}"""

        private fun subtitleJson(track: SubtitleTrack): String =
            """{"index":${track.index},"codec":"${track.codec}","language":"${track.language}","title":${track.title?.let { "\"$it\"" } ?: "null"},"forced":${track.forced},"external":${track.external}}"""

        private fun audioJson(track: AudioTrack): String =
            """{"index":${track.index},"codec":"${track.codec}","language":"${track.language}"}"""
    }
}
