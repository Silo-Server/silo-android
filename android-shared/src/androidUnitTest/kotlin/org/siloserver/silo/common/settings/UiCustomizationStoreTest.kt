package org.siloserver.silo.common.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.model.settings.CardCaptionPreset
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.NavigationShortcuts
import org.siloserver.silo.model.settings.PosterSizePreset
import org.siloserver.silo.model.settings.PrimaryMenu
import org.siloserver.silo.model.settings.PrimaryMenuBuiltin
import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.SiloClientFamily
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.model.settings.UiCustomizationCodec
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.repository.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UiCustomizationStoreTest {
    @Test
    fun capabilityStateDistinguishesConfirmedUnsupportedFromTransientFailure() = runTest {
        val api = FakeSettingsApi(
            capabilityResult = SettingsCapabilitiesResult.ServerUpgradeRequired,
        )
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            assertEquals(false, store.uiCustomizationSupported.value)
            assertEquals(0, api.gets.size)

            api.capabilityResult = SettingsCapabilitiesResult.NetworkError(IOException("offline"))
            store.refresh()
            assertEquals(null, store.uiCustomizationSupported.value)
            assertEquals(0, api.gets.size)

            api.capabilityResult = SettingsCapabilitiesResult.Available(
                SettingsContractCapabilities(
                    revision = 5,
                    supportsBatchedEffective = true,
                    supportsIdempotentWrites = true,
                    supportsAtomicShortcuts = true,
                ),
            )
            store.refresh()
            assertEquals(true, store.uiCustomizationSupported.value)
            assertEquals(1, api.gets.size)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun pendingWriteWaitsForCapabilityRecoveryThenDrainsWithSameMutation() = runTest {
        val api = FakeSettingsApi(online = false)
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setCardPresentation(
                CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
            )
            advanceUntilIdle()
            assertEquals(1, api.puts.size)

            api.online = true
            api.capabilityResult = SettingsCapabilitiesResult.NetworkError(IOException("offline probe"))
            store.refresh()
            assertEquals(1, api.puts.size)

            api.capabilityResult = SettingsCapabilitiesResult.Available(
                SettingsContractCapabilities(
                    revision = 5,
                    supportsBatchedEffective = true,
                    supportsIdempotentWrites = true,
                    supportsAtomicShortcuts = true,
                ),
            )
            store.refresh()
            assertEquals(2, api.puts.size)
            assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)

            store.refresh()
            assertEquals(2, api.puts.size)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun familyValuesWriteProfileClientWhileShortcutsUseAtomicProfileEndpoint() = runTest {
        val api = FakeSettingsApi(
            effective = mapOf(
                SettingKeys.UI_CARD_PRESENTATION to EffectiveSettingValue(
                    key = SettingKeys.UI_CARD_PRESENTATION,
                    value = UiCustomizationCodec.encodeCardPresentation(
                        CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
                    ),
                    source = SettingScope.PROFILE_CLIENT.wire,
                ),
            ),
        )
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            assertEquals(PosterSizePreset.LARGE, store.cardPresentation.value.posterSize)

            store.setCardPresentation(CardPresentation.DEFAULT)
            store.setShortcutPresent(PrimaryMenuItem.Library(7, "Movies"), present = true)
            advanceUntilIdle()

            assertEquals(
                listOf(SettingScope.PROFILE_CLIENT),
                api.puts.map { it.scope.scope },
            )
            assertEquals(SiloClientFamily.TV, api.puts.single().scope.clientFamily)
            assertEquals(1, api.shortcutPuts.size)
            assertEquals(true, api.shortcutPuts.single().present)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun offlineCacheIsIsolatedByProfileAndRestoredOnReturn() = runTest {
        val api = FakeSettingsApi(online = false)
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            setProfileId("profile-one")
        }
        val (store, storeScope) = store(api, tokenManager)
        try {
            store.refresh()
            val offlineChoice = CardPresentation(
                PosterSizePreset.LARGE,
                CardCaptionPreset.TITLE,
            )
            store.setCardPresentation(offlineChoice)
            advanceUntilIdle()
            assertEquals(offlineChoice, store.cardPresentation.value)

            tokenManager.setProfileId("profile-two")
            store.refresh()
            assertEquals(CardPresentation.DEFAULT, store.cardPresentation.value)

            tokenManager.setProfileId("profile-one")
            store.refresh()
            assertEquals(offlineChoice, store.cardPresentation.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun editsAfterProfileSwitchBindToNewIdentityBeforeRefresh() = runTest {
        val api = FakeSettingsApi(online = false)
        val cache = InMemoryCache()
        val tokenManager = SnapshotTokenManager(
            AuthScopeSnapshot(
                serverId = "server-1",
                profileId = "profile-a",
                serverUrl = "https://silo.example",
                profileToken = null,
                identityGeneration = 1,
            ),
        )
        val (store, storeScope) = store(api, tokenManager, cache)
        val menu = PrimaryMenu(listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)))
        val card = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val shortcut = PrimaryMenuItem.Library(7, "Movies")
        try {
            store.refresh()
            tokenManager.snapshot = tokenManager.snapshot.copy(
                profileId = "profile-b",
                identityGeneration = 2,
            )

            // No B refresh has happened yet: activeIdentity still points at A.
            store.setPrimaryMenu(menu)
            store.setCardPresentation(card)
            store.setShortcutPresent(shortcut, present = true)
            advanceUntilIdle()

            assertEquals(listOf("profile-b", "profile-b"), api.puts.map { it.profileId })
            assertEquals("profile-b", api.shortcutPuts.single().profileId)
            assertEquals(menu, store.primaryMenu.value)
            assertEquals(card, store.cardPresentation.value)
            assertEquals(NavigationShortcuts(listOf(shortcut)), store.shortcuts.value)

            tokenManager.snapshot = tokenManager.snapshot.copy(
                profileId = "profile-a",
                identityGeneration = 3,
            )
            store.refresh()
            assertEquals(null, store.primaryMenu.value)
            assertEquals(CardPresentation.DEFAULT, store.cardPresentation.value)
            assertEquals(NavigationShortcuts.EMPTY, store.shortcuts.value)

            tokenManager.snapshot = tokenManager.snapshot.copy(
                profileId = "profile-b",
                identityGeneration = 4,
            )
            store.refresh()
            assertEquals(menu, store.primaryMenu.value)
            assertEquals(card, store.cardPresentation.value)
            assertEquals(NavigationShortcuts(listOf(shortcut)), store.shortcuts.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun anInFlightRefreshCannotOverwriteANewerLocalChoice() = runTest {
        val remote = CardPresentation.DEFAULT
        val local = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val api = FakeSettingsApi(
            effective = mapOf(
                SettingKeys.UI_CARD_PRESENTATION to EffectiveSettingValue(
                    key = SettingKeys.UI_CARD_PRESENTATION,
                    value = UiCustomizationCodec.encodeCardPresentation(remote),
                ),
            ),
        ).apply {
            getGate = CompletableDeferred()
            putGate = CompletableDeferred()
        }
        val (store, storeScope) = store(api)
        try {
            val refresh = launch { store.refresh() }
            runCurrent()

            store.setCardPresentation(local)
            api.getGate?.complete(Unit)
            refresh.join()

            assertEquals(local, store.cardPresentation.value)
            api.putGate?.complete(Unit)
            advanceUntilIdle()
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun aSuccessfulEditDuringGetCannotBeOverwrittenAfterPendingClears() = runTest {
        val remote = CardPresentation.DEFAULT
        val local = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val cache = InMemoryCache()
        val api = FakeSettingsApi(
            effective = mapOf(
                SettingKeys.UI_CARD_PRESENTATION to EffectiveSettingValue(
                    key = SettingKeys.UI_CARD_PRESENTATION,
                    value = UiCustomizationCodec.encodeCardPresentation(remote),
                ),
            ),
        ).apply { getGate = CompletableDeferred() }
        val (store, storeScope) = store(api, cache = cache)
        try {
            val refresh = launch { store.refresh() }
            runCurrent()

            store.setCardPresentation(local)
            runCurrent()
            assertEquals(1, api.puts.size)

            api.getGate?.complete(Unit)
            refresh.join()
            assertEquals(local, store.cardPresentation.value)

            // The stale GET must not have replaced the durable cache either.
            storeScope.cancel()
            api.online = false
            val (restartedStore, restartedScope) = store(api, cache = cache)
            try {
                restartedStore.refresh()
                assertEquals(local, restartedStore.cardPresentation.value)
            } finally {
                restartedScope.cancel()
            }
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun aSuccessfulResetDuringGetCannotBeOverwrittenAfterPendingClears() = runTest {
        val remoteMenu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES),
            ),
        )
        val cache = InMemoryCache()
        val api = FakeSettingsApi(
            effective = mapOf(
                SettingKeys.NAV_PRIMARY_MENU to EffectiveSettingValue(
                    key = SettingKeys.NAV_PRIMARY_MENU,
                    value = UiCustomizationCodec.encodePrimaryMenu(remoteMenu),
                ),
            ),
        )
        val (store, storeScope) = store(api, cache = cache)
        try {
            store.refresh()
            assertEquals(remoteMenu, store.primaryMenu.value)

            api.getGate = CompletableDeferred()
            val refresh = launch { store.refresh() }
            runCurrent()

            store.resetPrimaryMenu()
            runCurrent()
            assertEquals(1, api.deletes.size)

            api.getGate?.complete(Unit)
            refresh.join()
            assertEquals(null, store.primaryMenu.value)

            storeScope.cancel()
            api.online = false
            val (restartedStore, restartedScope) = store(api, cache = cache)
            try {
                restartedStore.refresh()
                assertEquals(null, restartedStore.primaryMenu.value)
            } finally {
                restartedScope.cancel()
            }
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun pendingRetryAndNewEditAreSerializedSoTheNewEditLandsLast() = runTest {
        val cached = CardPresentation(PosterSizePreset.COMPACT, CardCaptionPreset.TITLE)
        val newer = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val api = FakeSettingsApi(online = false)
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setCardPresentation(cached)
            advanceUntilIdle()
            assertEquals(1, api.puts.size)

            api.online = true
            api.putGate = CompletableDeferred()
            val refresh = launch { store.refresh() }
            runCurrent()
            assertEquals(2, api.puts.size)

            store.setCardPresentation(newer)
            runCurrent()
            // The new edit is cached synchronously, but its PUT must wait for
            // the older retry's wire turn instead of racing and finishing first.
            assertEquals(2, api.puts.size)

            api.putGate?.complete(Unit)
            refresh.join()
            advanceUntilIdle()

            assertEquals(3, api.puts.size)
            assertEquals(
                UiCustomizationCodec.encodeCardPresentation(newer),
                api.puts.last().value,
            )
            assertEquals(newer, store.cardPresentation.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun ambiguousWriteRetryReusesItsPersistedMutationId() = runTest {
        val api = FakeSettingsApi(online = false)
        val cache = InMemoryCache()
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            setProfileId("profile-one")
        }
        val desired = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val (firstStore, firstScope) = store(api, tokenManager, cache)
        try {
            firstStore.refresh()
            firstStore.setCardPresentation(desired)
            advanceUntilIdle()
            assertEquals(1, api.puts.size)

            // Model an app restart after the server may have accepted the PUT
            // but the response never reached this client.
            firstScope.cancel()
            api.online = true
            val (restartedStore, restartedScope) = store(api, tokenManager, cache)
            try {
                restartedStore.refresh()
                advanceUntilIdle()

                assertEquals(2, api.puts.size)
                assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
                assertEquals(desired, restartedStore.cardPresentation.value)
            } finally {
                restartedScope.cancel()
            }
        } finally {
            firstScope.cancel()
        }
    }

    @Test
    fun ambiguousSuccessReplayCannotOverwriteANewerRemoteEdit() = runTest {
        val cachedEdit = CardPresentation(PosterSizePreset.COMPACT, CardCaptionPreset.TITLE)
        val newerRemoteEdit = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        var firstMutationId: String? = null
        val api = FakeSettingsApi().apply {
            onPut = { put ->
                val originalId = firstMutationId
                if (originalId == null) {
                    // The server applied this logical write, but the response
                    // was lost. Retain its idempotency receipt.
                    firstMutationId = put.mutationId
                    ApiResult.NetworkError(IOException("response lost"))
                } else {
                    // A repeated receipt is a no-op. A new id would replay the
                    // stale cached value over the other TV's newer edit.
                    if (put.mutationId != originalId) {
                        effective = effective + (
                            put.key to EffectiveSettingValue(
                                key = put.key,
                                value = put.value,
                                source = SettingScope.PROFILE_CLIENT.wire,
                            )
                        )
                    }
                    ApiResult.Success(
                        StoredSettingValue(
                            key = put.key,
                            scope = put.scope.scope.wire,
                            value = put.value,
                        ),
                    )
                }
            }
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setCardPresentation(cachedEdit)
            advanceUntilIdle()

            api.effective = mapOf(
                SettingKeys.UI_CARD_PRESENTATION to EffectiveSettingValue(
                    key = SettingKeys.UI_CARD_PRESENTATION,
                    value = UiCustomizationCodec.encodeCardPresentation(newerRemoteEdit),
                    source = SettingScope.PROFILE_CLIENT.wire,
                ),
            )
            store.refresh()

            assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
            assertEquals(newerRemoteEdit, store.cardPresentation.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun aNewLogicalEditGetsANewMutationId() = runTest {
        val api = FakeSettingsApi()
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setCardPresentation(
                CardPresentation(PosterSizePreset.COMPACT, CardCaptionPreset.TITLE),
            )
            advanceUntilIdle()
            store.setCardPresentation(
                CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
            )
            advanceUntilIdle()

            assertEquals(2, api.puts.size)
            assertNotEquals(api.puts.first().mutationId, api.puts.last().mutationId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun offlineShortcutOperationSurvivesRestartAndReusesExactMutation() = runTest {
        val item = PrimaryMenuItem.Library(7, "Movies")
        val cache = InMemoryCache()
        val api = FakeSettingsApi(online = false)
        val (firstStore, firstScope) = store(api, cache = cache)
        try {
            firstStore.refresh()
            firstStore.setShortcutPresent(item, present = true)
            advanceUntilIdle()

            assertEquals(NavigationShortcuts(listOf(item)), firstStore.shortcuts.value)
            assertEquals(1, api.shortcutPuts.size)

            firstScope.cancel()
            api.online = true
            val (restartedStore, restartedScope) = store(api, cache = cache)
            try {
                restartedStore.refresh()

                assertEquals(2, api.shortcutPuts.size)
                assertEquals(api.shortcutPuts.first(), api.shortcutPuts.last())
                assertEquals(NavigationShortcuts(listOf(item)), restartedStore.shortcuts.value)
            } finally {
                restartedScope.cancel()
            }
        } finally {
            firstScope.cancel()
        }
    }

    @Test
    fun ambiguousShortcutRetryReusesBodyAndMutationId() = runTest {
        val item = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        var receipt: StoredSettingValue? = null
        val api = FakeSettingsApi().apply {
            onShortcutPut = { put ->
                if (receipt == null) {
                    receipt = applyShortcutPut(put)
                    ApiResult.NetworkError(IOException("response lost"))
                } else {
                    ApiResult.Success(checkNotNull(receipt))
                }
            }
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(item, present = true)
            advanceUntilIdle()
            store.refresh()

            assertEquals(2, api.shortcutPuts.size)
            assertEquals(api.shortcutPuts.first(), api.shortcutPuts.last())
            assertEquals(NavigationShortcuts(listOf(item)), store.shortcuts.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun newerOppositeShortcutIntentIsNotClearedByOlderSuccess() = runTest {
        val item = PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 7)
        val firstPutGate = CompletableDeferred<Unit>()
        val api = FakeSettingsApi().apply {
            shortcutPutGate = firstPutGate
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(item, present = true)
            runCurrent()

            store.setShortcutPresent(item, present = false)
            runCurrent()
            assertEquals(NavigationShortcuts.EMPTY, store.shortcuts.value)

            firstPutGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(true, false), api.shortcutPuts.map { it.present })
            assertNotEquals(
                api.shortcutPuts.first().mutationId,
                api.shortcutPuts.last().mutationId,
            )
            assertEquals(NavigationShortcuts.EMPTY, store.shortcuts.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun newerEquivalentShortcutIntentIsNotClearedByOlderSuccess() = runTest {
        val item = PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 7)
        val firstPutGate = CompletableDeferred<Unit>()
        val api = FakeSettingsApi().apply {
            shortcutPutGate = firstPutGate
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(item, present = true)
            runCurrent()

            store.setShortcutPresent(item, present = true)
            runCurrent()

            firstPutGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(true, true), api.shortcutPuts.map { it.present })
            assertNotEquals(
                api.shortcutPuts.first().mutationId,
                api.shortcutPuts.last().mutationId,
            )
            assertEquals(NavigationShortcuts(listOf(item)), store.shortcuts.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun definitiveShortcutRejectionDropsOnlyThatOperationAndKeepsDraining() = runTest {
        val rejected = PrimaryMenuItem.Library(7, "Deleted")
        val second = PrimaryMenuItem.Section(8, "recent", "Recently Added")
        val third = PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 8)
        val rejectedItem = checkNotNull(UiCustomizationCodec.encodeShortcutItem(rejected))
        val api = FakeSettingsApi().apply {
            onShortcutPut = { put ->
                if (put.item == rejectedItem) {
                    ApiResult.Error(
                        code = 404,
                        error = "not_found",
                        message = "Library 7 no longer exists",
                    )
                } else {
                    ApiResult.Success(applyShortcutPut(put))
                }
            }
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(rejected, present = true)
            store.setShortcutPresent(second, present = true)
            store.setShortcutPresent(third, present = true)
            advanceUntilIdle()

            // The rejected head is attempted once, then dropped: it neither
            // repeats nor blocks the operations authored behind it.
            assertEquals(1, api.shortcutPuts.count { it.item == rejectedItem })
            assertEquals(3, api.shortcutPuts.size)
            assertEquals(
                NavigationShortcuts(listOf(second, third)),
                store.shortcuts.value,
            )

            // Pending cleared, so a later refresh adopts the server document
            // instead of replaying the rejection forever.
            val putsAfterDrain = api.shortcutPuts.size
            store.refresh()
            advanceUntilIdle()

            assertEquals(putsAfterDrain, api.shortcutPuts.size)
            assertEquals(
                NavigationShortcuts(listOf(second, third)),
                store.shortcuts.value,
            )
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun definitiveRejectionRevertsOnlyItsOwnPinWhilePendingSiblingSurvives() = runTest {
        val rejected = PrimaryMenuItem.Library(7, "Deleted")
        val sibling = PrimaryMenuItem.Section(8, "recent", "Recently Added")
        val rejectedItem = checkNotNull(UiCustomizationCodec.encodeShortcutItem(rejected))
        val api = FakeSettingsApi().apply {
            onShortcutPut = { put ->
                if (put.item == rejectedItem) {
                    ApiResult.Error(
                        code = 400,
                        error = "invalid_value",
                        message = "Shortcut item is not storable",
                    )
                } else {
                    ApiResult.NetworkError(IOException("offline"))
                }
            }
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(rejected, present = true)
            store.setShortcutPresent(sibling, present = true)
            advanceUntilIdle()

            // The sibling never reached the server, so nothing but the local
            // rollback can have removed the rejected pin.
            assertEquals(
                NavigationShortcuts(listOf(sibling)),
                store.shortcuts.value,
            )

            api.onShortcutPut = null
            store.refresh()
            advanceUntilIdle()

            assertEquals(
                listOf(sibling),
                api.shortcutPuts.filter { it.item != rejectedItem }
                    .map { checkNotNull(UiCustomizationCodec.parseShortcutItem(it.item)) }
                    .distinct(),
            )
            assertEquals(
                NavigationShortcuts(listOf(sibling)),
                store.shortcuts.value,
            )
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun definitivelyRejectedUnpinRestoresOnlyThatShortcut() = runTest {
        val rejected = PrimaryMenuItem.Library(7, "Movies")
        val untouched = PrimaryMenuItem.Section(8, "recent", "Recently Added")
        val rejectedItem = checkNotNull(UiCustomizationCodec.encodeShortcutItem(rejected))
        val api = FakeSettingsApi(
            effective = shortcutEffective(NavigationShortcuts(listOf(rejected, untouched))),
        ).apply {
            onShortcutPut = { put ->
                if (put.item == rejectedItem) {
                    ApiResult.Error(
                        code = 403,
                        error = "scope_not_allowed",
                        message = "This profile cannot edit that shortcut",
                    )
                } else {
                    ApiResult.NetworkError(IOException("offline"))
                }
            }
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            advanceUntilIdle()
            assertEquals(
                NavigationShortcuts(listOf(rejected, untouched)),
                store.shortcuts.value,
            )

            store.setShortcutPresent(rejected, present = false)
            store.setShortcutPresent(untouched, present = false)
            advanceUntilIdle()

            // The rejected removal is undone; the still-pending sibling
            // removal keeps its optimistic effect.
            assertEquals(listOf(rejected), store.shortcuts.value.items)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun shortcutNetworkFailureStopsTheDrainAndRetriesTheWholeQueueLater() = runTest {
        val first = PrimaryMenuItem.Library(7, "Movies")
        val second = PrimaryMenuItem.Section(8, "recent", "Recently Added")
        val firstItem = checkNotNull(UiCustomizationCodec.encodeShortcutItem(first))
        val api = FakeSettingsApi(online = false)
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(first, present = true)
            store.setShortcutPresent(second, present = true)
            advanceUntilIdle()

            // The head stays at the front of the outbox and nothing behind it
            // is sent while it is still retryable.
            assertEquals(listOf(firstItem), api.shortcutPuts.map { it.item }.distinct())
            assertEquals(
                NavigationShortcuts(listOf(first, second)),
                store.shortcuts.value,
            )

            api.online = true
            store.refresh()
            advanceUntilIdle()

            assertEquals(
                listOf(first, second),
                api.shortcutPuts.takeLast(2)
                    .map { checkNotNull(UiCustomizationCodec.parseShortcutItem(it.item)) },
            )
            assertEquals(
                NavigationShortcuts(listOf(first, second)),
                store.shortcuts.value,
            )
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun mutationIdConflictRetriesTheSameIntentUnderAFreshId() = runTest {
        val item = PrimaryMenuItem.Library(7, "Movies")
        var conflicted = false
        val api = FakeSettingsApi().apply {
            onShortcutPut = { put ->
                if (!conflicted) {
                    conflicted = true
                    ApiResult.Error(
                        code = 409,
                        error = "mutation_id_conflict",
                        message = "Mutation id already used for different content",
                    )
                } else {
                    ApiResult.Success(applyShortcutPut(put))
                }
            }
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(item, present = true)
            advanceUntilIdle()

            // The intent is replayed verbatim under an id the server has never
            // seen, rather than rolled back or retried into the same conflict.
            assertEquals(2, api.shortcutPuts.size)
            assertEquals(
                api.shortcutPuts.first().copy(mutationId = ""),
                api.shortcutPuts.last().copy(mutationId = ""),
            )
            assertNotEquals(
                api.shortcutPuts.first().mutationId,
                api.shortcutPuts.last().mutationId,
            )
            assertEquals(NavigationShortcuts(listOf(item)), store.shortcuts.value)

            store.refresh()
            advanceUntilIdle()

            assertEquals(2, api.shortcutPuts.size)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun compoundNavigationIntentSurvivesDeathAfterItsSingleDurableWrite() = runTest {
        val cache = InMemoryCache()
        val api = FakeSettingsApi()
        val menuItem = PrimaryMenuItem.Library(7, "Movies")
        val menu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                menuItem,
            ),
        )
        val (firstStore, firstScope) = store(api, cache = cache)
        try {
            firstStore.refresh()
            var interrupt = true
            cache.afterPutString = { key ->
                if (interrupt && key.endsWith(".navigation_outbox")) {
                    interrupt = false
                    throw CancellationException("simulated process death")
                }
            }

            firstStore.setPrimaryMenuAndShortcut(menu, menuItem, present = true)
            advanceUntilIdle()

            assertEquals(0, api.puts.size)
            assertEquals(0, api.shortcutPuts.size)

            firstScope.cancel()
            cache.afterPutString = null
            val (restartedStore, restartedScope) = store(api, cache = cache)
            try {
                restartedStore.refresh()

                assertEquals(menu, restartedStore.primaryMenu.value)
                assertEquals(
                    NavigationShortcuts(listOf(menuItem)),
                    restartedStore.shortcuts.value,
                )
                assertEquals(1, api.puts.size)
                assertEquals(1, api.shortcutPuts.size)
                assertEquals("profile-one", api.puts.single().profileId)
                assertEquals("profile-one", api.shortcutPuts.single().profileId)
            } finally {
                restartedScope.cancel()
            }
        } finally {
            firstScope.cancel()
        }
    }

    @Test
    fun compoundMenuFailureRetriesOnlyMenuAfterShortcutCompletes() = runTest {
        val cache = InMemoryCache()
        val item = PrimaryMenuItem.Library(7, "Movies")
        val menu = PrimaryMenu(listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME), item))
        var failMenu = true
        val api = FakeSettingsApi().apply {
            onPut = { put ->
                if (put.key == SettingKeys.NAV_PRIMARY_MENU && failMenu) {
                    failMenu = false
                    ApiResult.NetworkError(IOException("menu response lost"))
                } else {
                    ApiResult.Success(
                        StoredSettingValue(put.key, put.scope.scope.wire, value = put.value),
                    )
                }
            }
        }
        val (firstStore, firstScope) = store(api, cache = cache)
        try {
            firstStore.refresh()
            firstStore.setPrimaryMenuAndShortcut(menu, item, present = true)
            advanceUntilIdle()

            assertEquals(1, api.puts.size)
            assertEquals(1, api.shortcutPuts.size)

            firstScope.cancel()
            val (restartedStore, restartedScope) = store(api, cache = cache)
            try {
                restartedStore.refresh()

                assertEquals(2, api.puts.size)
                assertEquals(api.puts.first(), api.puts.last())
                assertEquals(1, api.shortcutPuts.size)
                assertEquals(menu, restartedStore.primaryMenu.value)
                assertEquals(NavigationShortcuts(listOf(item)), restartedStore.shortcuts.value)
            } finally {
                restartedScope.cancel()
            }
        } finally {
            firstScope.cancel()
        }
    }

    @Test
    fun compoundShortcutFailureRetriesOnlyShortcutAfterMenuCompletes() = runTest {
        val cache = InMemoryCache()
        val item = PrimaryMenuItem.Library(7, "Movies")
        val menu = PrimaryMenu(listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME), item))
        var receipt: StoredSettingValue? = null
        val api = FakeSettingsApi().apply {
            onShortcutPut = { put ->
                if (receipt == null) {
                    receipt = applyShortcutPut(put)
                    ApiResult.NetworkError(IOException("shortcut response lost"))
                } else {
                    ApiResult.Success(checkNotNull(receipt))
                }
            }
        }
        val (firstStore, firstScope) = store(api, cache = cache)
        try {
            firstStore.refresh()
            firstStore.setPrimaryMenuAndShortcut(menu, item, present = true)
            advanceUntilIdle()

            assertEquals(1, api.puts.size)
            assertEquals(1, api.shortcutPuts.size)

            firstScope.cancel()
            val (restartedStore, restartedScope) = store(api, cache = cache)
            try {
                restartedStore.refresh()

                assertEquals(1, api.puts.size)
                assertEquals(2, api.shortcutPuts.size)
                assertEquals(api.shortcutPuts.first(), api.shortcutPuts.last())
                assertEquals(menu, restartedStore.primaryMenu.value)
                assertEquals(NavigationShortcuts(listOf(item)), restartedStore.shortcuts.value)
            } finally {
                restartedScope.cancel()
            }
        } finally {
            firstScope.cancel()
        }
    }

    @Test
    fun newerMenuEditWinsWhenCompoundMenuRequestWasAlreadyInFlight() = runTest {
        val item = PrimaryMenuItem.Library(7, "Movies")
        val compoundMenu = PrimaryMenu(
            listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME), item),
        )
        val newerMenu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
            ),
        )
        val menuPutGate = CompletableDeferred<Unit>()
        val api = FakeSettingsApi().apply { putGate = menuPutGate }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setPrimaryMenuAndShortcut(compoundMenu, item, present = true)
            runCurrent()
            assertEquals(compoundMenu, store.primaryMenu.value)
            assertEquals(1, api.puts.size)

            store.setPrimaryMenu(newerMenu)
            runCurrent()
            assertEquals(newerMenu, store.primaryMenu.value)

            menuPutGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, api.puts.size)
            assertEquals(
                listOf(compoundMenu, newerMenu),
                api.puts.map { checkNotNull(UiCustomizationCodec.parsePrimaryMenu(it.value)) },
            )
            assertNotEquals(api.puts.first().mutationId, api.puts.last().mutationId)
            assertEquals(newerMenu, store.primaryMenu.value)
            assertEquals(NavigationShortcuts(listOf(item)), store.shortcuts.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun shortcutSuccessAfterClearCannotRepaintTheSignedOutUi() = runTest {
        val item = PrimaryMenuItem.Library(7, "Movies")
        val firstPutGate = CompletableDeferred<Unit>()
        val api = FakeSettingsApi().apply {
            shortcutPutGate = firstPutGate
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(item, present = true)
            runCurrent()

            store.clear()
            firstPutGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(NavigationShortcuts.EMPTY, store.shortcuts.value)
            assertEquals(1, api.shortcutPuts.size)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun effectiveGetCannotOverwriteOptimisticShortcutWhileOperationIsPending() = runTest {
        val item = PrimaryMenuItem.Library(7, "Movies")
        val api = FakeSettingsApi().apply {
            onShortcutPut = { ApiResult.NetworkError(IOException("offline shortcut endpoint")) }
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            store.setShortcutPresent(item, present = true)
            advanceUntilIdle()
            assertEquals(NavigationShortcuts(listOf(item)), store.shortcuts.value)

            // GET remains online and still reports the empty server document.
            // The pending atomic intent must remain painted instead.
            store.refresh()
            assertEquals(NavigationShortcuts(listOf(item)), store.shortcuts.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun deviceOverrideSourceIsPreservedUntilUseFamilySettingsClearsExactDevice() = runTest {
        val deviceValue = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val familyValue = CardPresentation.DEFAULT
        val deviceMenu = org.siloserver.silo.model.settings.PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(
                    org.siloserver.silo.model.settings.PrimaryMenuBuiltin.HOME,
                ),
                PrimaryMenuItem.Builtin(
                    org.siloserver.silo.model.settings.PrimaryMenuBuiltin.MOVIES,
                ),
            ),
        )
        val familyMenu = org.siloserver.silo.model.settings.PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(
                    org.siloserver.silo.model.settings.PrimaryMenuBuiltin.HOME,
                ),
            ),
        )
        val api = FakeSettingsApi(
            effective = mapOf(
                SettingKeys.NAV_PRIMARY_MENU to EffectiveSettingValue(
                    key = SettingKeys.NAV_PRIMARY_MENU,
                    value = UiCustomizationCodec.encodePrimaryMenu(deviceMenu),
                    source = SettingScope.PROFILE_DEVICE.wire,
                    scope = SettingScope.PROFILE_DEVICE.wire,
                ),
                SettingKeys.UI_CARD_PRESENTATION to EffectiveSettingValue(
                    key = SettingKeys.UI_CARD_PRESENTATION,
                    value = UiCustomizationCodec.encodeCardPresentation(deviceValue),
                    source = SettingScope.PROFILE_DEVICE.wire,
                    scope = SettingScope.PROFILE_DEVICE.wire,
                ),
            ),
        ).apply {
            onDelete = { key, scope ->
                if (scope.scope == SettingScope.PROFILE_DEVICE) {
                    val inherited = when (key) {
                        SettingKeys.NAV_PRIMARY_MENU -> EffectiveSettingValue(
                            key = key,
                            value = UiCustomizationCodec.encodePrimaryMenu(familyMenu),
                            source = SettingScope.PROFILE_CLIENT.wire,
                            scope = SettingScope.PROFILE_CLIENT.wire,
                        )
                        SettingKeys.UI_CARD_PRESENTATION -> EffectiveSettingValue(
                            key = key,
                            value = UiCustomizationCodec.encodeCardPresentation(familyValue),
                            source = SettingScope.PROFILE_CLIENT.wire,
                            scope = SettingScope.PROFILE_CLIENT.wire,
                        )
                        else -> null
                    }
                    if (inherited != null) effective = effective + (key to inherited)
                }
            }
        }
        val (store, storeScope) = store(api)
        try {
            store.refresh()
            assertEquals(SettingScope.PROFILE_DEVICE.wire, store.primaryMenuSource.value)
            assertEquals(SettingScope.PROFILE_DEVICE.wire, store.cardPresentationSource.value)
            assertEquals(deviceValue, store.cardPresentation.value)

            store.useFamilySettings()
            advanceUntilIdle()

            assertEquals(
                listOf(SettingScope.PROFILE_DEVICE, SettingScope.PROFILE_DEVICE),
                api.deletes.map { it.scope.scope },
            )
            assertEquals(
                listOf(SiloClientFamily.TV, SiloClientFamily.TV),
                api.deletes.map { it.scope.clientFamily },
            )
            assertEquals(SettingScope.PROFILE_CLIENT.wire, store.primaryMenuSource.value)
            assertEquals(SettingScope.PROFILE_CLIENT.wire, store.cardPresentationSource.value)
            assertEquals(familyMenu, store.primaryMenu.value)
            assertEquals(familyValue, store.cardPresentation.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun offlineDeviceDeleteSurvivesRestartAndRetriesBeforeEffectiveFetch() = runTest {
        val deviceValue = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val familyValue = CardPresentation.DEFAULT
        val cache = InMemoryCache()
        val api = FakeSettingsApi(
            effective = mapOf(
                SettingKeys.UI_CARD_PRESENTATION to EffectiveSettingValue(
                    key = SettingKeys.UI_CARD_PRESENTATION,
                    value = UiCustomizationCodec.encodeCardPresentation(deviceValue),
                    source = SettingScope.PROFILE_DEVICE.wire,
                    scope = SettingScope.PROFILE_DEVICE.wire,
                ),
            ),
        )
        val (firstStore, firstScope) = store(api, cache = cache)
        try {
            firstStore.refresh()
            api.online = false
            firstStore.useFamilySettings()
            advanceUntilIdle()

            assertEquals(1, api.deletes.size)
            assertEquals(deviceValue, firstStore.cardPresentation.value)

            firstScope.cancel()
            api.online = true
            api.onDelete = { key, scope ->
                if (key == SettingKeys.UI_CARD_PRESENTATION &&
                    scope.scope == SettingScope.PROFILE_DEVICE
                ) {
                    api.effective = api.effective + (
                        key to EffectiveSettingValue(
                            key = key,
                            value = UiCustomizationCodec.encodeCardPresentation(familyValue),
                            source = SettingScope.PROFILE_CLIENT.wire,
                            scope = SettingScope.PROFILE_CLIENT.wire,
                        )
                    )
                }
            }
            val (restartedStore, restartedScope) = store(api, cache = cache)
            try {
                restartedStore.refresh()

                assertEquals(2, api.deletes.size)
                assertEquals(familyValue, restartedStore.cardPresentation.value)
                assertEquals(
                    SettingScope.PROFILE_CLIENT.wire,
                    restartedStore.cardPresentationSource.value,
                )
            } finally {
                restartedScope.cancel()
            }
        } finally {
            firstScope.cancel()
        }
    }

    @Test
    fun partialDeviceDeleteRetriesOnlyUnresolvedKeyAndSuppressesItsStaleGet() = runTest {
        val deviceMenu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES),
            ),
        )
        val familyMenu = PrimaryMenu(
            listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)),
        )
        val deviceCard = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val staleCard = CardPresentation(PosterSizePreset.COMPACT, CardCaptionPreset.TITLE)
        val familyCard = CardPresentation.DEFAULT
        val cache = InMemoryCache()
        var failCardDelete = true
        val api = FakeSettingsApi(
            effective = mapOf(
                SettingKeys.NAV_PRIMARY_MENU to EffectiveSettingValue(
                    key = SettingKeys.NAV_PRIMARY_MENU,
                    value = UiCustomizationCodec.encodePrimaryMenu(deviceMenu),
                    source = SettingScope.PROFILE_DEVICE.wire,
                    scope = SettingScope.PROFILE_DEVICE.wire,
                ),
                SettingKeys.UI_CARD_PRESENTATION to EffectiveSettingValue(
                    key = SettingKeys.UI_CARD_PRESENTATION,
                    value = UiCustomizationCodec.encodeCardPresentation(deviceCard),
                    source = SettingScope.PROFILE_DEVICE.wire,
                    scope = SettingScope.PROFILE_DEVICE.wire,
                ),
            ),
        ).apply {
            onDeleteResult = { key, _ ->
                if (key == SettingKeys.UI_CARD_PRESENTATION && failCardDelete) {
                    failCardDelete = false
                    effective = effective + (
                        key to EffectiveSettingValue(
                            key = key,
                            value = UiCustomizationCodec.encodeCardPresentation(staleCard),
                            source = SettingScope.PROFILE_DEVICE.wire,
                            scope = SettingScope.PROFILE_DEVICE.wire,
                        )
                    )
                    ApiResult.NetworkError(IOException("offline during card delete"))
                } else {
                    null
                }
            }
            onDelete = { key, scope ->
                if (scope.scope == SettingScope.PROFILE_DEVICE) {
                    val inherited = when (key) {
                        SettingKeys.NAV_PRIMARY_MENU -> EffectiveSettingValue(
                            key = key,
                            value = UiCustomizationCodec.encodePrimaryMenu(familyMenu),
                            source = SettingScope.PROFILE_CLIENT.wire,
                            scope = SettingScope.PROFILE_CLIENT.wire,
                        )
                        SettingKeys.UI_CARD_PRESENTATION -> EffectiveSettingValue(
                            key = key,
                            value = UiCustomizationCodec.encodeCardPresentation(familyCard),
                            source = SettingScope.PROFILE_CLIENT.wire,
                            scope = SettingScope.PROFILE_CLIENT.wire,
                        )
                        else -> null
                    }
                    if (inherited != null) effective = effective + (key to inherited)
                }
            }
        }
        val (firstStore, firstScope) = store(api, cache = cache)
        try {
            firstStore.refresh()
            firstStore.useFamilySettings()
            advanceUntilIdle()

            assertEquals(
                listOf(SettingKeys.NAV_PRIMARY_MENU, SettingKeys.UI_CARD_PRESENTATION),
                api.deletes.map { it.key },
            )
            assertEquals(familyMenu, firstStore.primaryMenu.value)
            // The GET returned staleCard, but the unresolved durable delete
            // keeps the last trusted device value painted until it can retry.
            assertEquals(deviceCard, firstStore.cardPresentation.value)

            firstScope.cancel()
            val (restartedStore, restartedScope) = store(api, cache = cache)
            try {
                restartedStore.refresh()

                assertEquals(
                    listOf(
                        SettingKeys.NAV_PRIMARY_MENU,
                        SettingKeys.UI_CARD_PRESENTATION,
                        SettingKeys.UI_CARD_PRESENTATION,
                    ),
                    api.deletes.map { it.key },
                )
                assertEquals(familyMenu, restartedStore.primaryMenu.value)
                assertEquals(familyCard, restartedStore.cardPresentation.value)
            } finally {
                restartedScope.cancel()
            }
        } finally {
            firstScope.cancel()
        }
    }

    @Test
    fun rapidCardTransformsComposeInAuthoredOrder() = runTest {
        val api = FakeSettingsApi()
        val (store, storeScope) = store(api)
        try {
            store.refresh()

            store.updateCardPresentation { it.copy(posterSize = PosterSizePreset.LARGE) }
            store.updateCardPresentation { it.copy(caption = CardCaptionPreset.ARTWORK) }
            advanceUntilIdle()

            val expected = CardPresentation(
                PosterSizePreset.LARGE,
                CardCaptionPreset.ARTWORK,
            )
            assertEquals(expected, store.cardPresentation.value)
            assertEquals(
                UiCustomizationCodec.encodeCardPresentation(expected),
                api.puts.last().value,
            )
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun rapidMenuTransformsComposeAcrossACompoundShortcutWrite() = runTest {
        val api = FakeSettingsApi()
        val (store, storeScope) = store(api)
        val home = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)
        val movies = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES)
        val library = PrimaryMenuItem.Library(7, "Movies Library")
        val fallback = PrimaryMenu(listOf(home, movies))
        try {
            store.refresh()

            store.updatePrimaryMenuAndShortcut(
                fallback = fallback,
                item = library,
                present = true,
            ) { current -> PrimaryMenu(current.items + library) }
            store.updatePrimaryMenu(fallback) { current ->
                PrimaryMenu(listOf(movies) + current.items.filterNot { it == movies })
            }
            advanceUntilIdle()

            assertEquals(PrimaryMenu(listOf(movies, home, library)), store.primaryMenu.value)
            assertEquals(NavigationShortcuts(listOf(library)), store.shortcuts.value)
            assertEquals(
                PrimaryMenu(listOf(movies, home, library)),
                UiCustomizationCodec.parsePrimaryMenu(api.puts.last().value),
            )
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun rejectedCompoundTransformDoesNotPersistAnOrphanShortcut() = runTest {
        val api = FakeSettingsApi()
        val (store, storeScope) = store(api)
        val home = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)
        val library = PrimaryMenuItem.Library(7, "Movies Library")
        val fallback = PrimaryMenu(listOf(home))
        try {
            store.refresh()
            val putsBeforeMutation = api.puts.size
            val menuBeforeMutation = store.primaryMenu.value

            store.updatePrimaryMenuAndShortcut(
                fallback = fallback,
                item = library,
                present = true,
            ) { null }
            advanceUntilIdle()

            assertEquals(putsBeforeMutation, api.puts.size)
            assertEquals(menuBeforeMutation, store.primaryMenu.value)
            assertEquals(NavigationShortcuts.EMPTY, store.shortcuts.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun shortcutUnpinAndMenuHideRemainIndependent() = runTest {
        val api = FakeSettingsApi()
        val (store, storeScope) = store(api)
        val home = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)
        val library = PrimaryMenuItem.Library(7, "Movies")
        val menu = PrimaryMenu(listOf(home, library))
        try {
            store.refresh()
            store.setPrimaryMenuAndShortcut(menu, library, present = true)
            advanceUntilIdle()

            store.setShortcutPresent(library, present = false)
            advanceUntilIdle()
            assertEquals(menu, store.primaryMenu.value)
            assertEquals(NavigationShortcuts.EMPTY, store.shortcuts.value)

            store.setShortcutPresent(library, present = true)
            advanceUntilIdle()
            store.updatePrimaryMenu(menu) { PrimaryMenu(it.items.filterNot { item -> item == library }) }
            advanceUntilIdle()
            assertEquals(PrimaryMenu(listOf(home)), store.primaryMenu.value)
            assertEquals(NavigationShortcuts(listOf(library)), store.shortcuts.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun successfulPutIsAcknowledgedAcrossSameOwnerIdentityGenerationChange() = runTest {
        val api = FakeSettingsApi()
        val original = authScope("server-a", "https://server-a.example", generation = 1)
        val tokenManager = SnapshotTokenManager(original)
        val (store, storeScope) = store(api, tokenManager)
        try {
            store.refresh()
            api.putGate = CompletableDeferred()
            store.setCardPresentation(
                CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
            )
            runCurrent()
            assertEquals(1, api.puts.size)

            tokenManager.snapshot = original.copy(
                identityGeneration = 2,
            )
            api.putGate?.complete(Unit)
            advanceUntilIdle()

            store.refresh()
            assertEquals(1, api.puts.size)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun profileTokenRotationDoesNotStrandSameOwnerDelete() = runTest {
        val api = FakeSettingsApi()
        val original = authScope("server-a", "https://server-a.example", generation = 1)
        val tokenManager = SnapshotTokenManager(original)
        val (store, storeScope) = store(api, tokenManager)
        try {
            store.refresh()
            api.onDelete = { _, _ ->
                tokenManager.snapshot = original.copy(
                    profileToken = "rotated-pin-profile-token",
                    identityGeneration = 2,
                )
            }
            store.resetPrimaryMenu()
            advanceUntilIdle()
            assertEquals(1, api.deletes.size)

            store.refresh()
            assertEquals(1, api.deletes.size)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun replacementCredentialOwnerCannotAcknowledgeOrDrainTheOldOwnersWrite() = runTest {
        val api = FakeSettingsApi()
        val oldOwner = authScope("server-a", "https://server-a.example", generation = 1)
        val tokenManager = SnapshotTokenManager(oldOwner)
        val (store, storeScope) = store(api, tokenManager)
        try {
            store.refresh()
            api.putGate = CompletableDeferred()
            store.setCardPresentation(
                CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
            )
            runCurrent()
            assertEquals(1, api.puts.size)

            tokenManager.snapshot = oldOwner.copy(
                profileToken = null,
                credentialOwnerId = "replacement-account-owner",
                identityGeneration = 2,
                credentialEpoch = 2,
            )
            api.putGate?.complete(Unit)
            advanceUntilIdle()

            store.refresh()
            assertEquals(1, api.puts.size)

            tokenManager.snapshot = oldOwner.copy(identityGeneration = 3)
            store.refresh()
            assertEquals(2, api.puts.size)
            assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun epochFallbackSeparatesReplacementLoginWhenDurableOwnerIdIsUnavailable() = runTest {
        val api = FakeSettingsApi()
        val oldLogin = authScope("server-a", "https://server-a.example", generation = 1).copy(
            profileToken = null,
            credentialOwnerId = null,
            credentialEpoch = 1,
        )
        val tokenManager = SnapshotTokenManager(oldLogin)
        val (store, storeScope) = store(api, tokenManager)
        try {
            store.refresh()
            api.putGate = CompletableDeferred()
            store.setCardPresentation(
                CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
            )
            runCurrent()
            assertEquals(1, api.puts.size)

            tokenManager.snapshot = oldLogin.copy(
                identityGeneration = 2,
                credentialEpoch = 2,
            )
            api.putGate?.complete(Unit)
            advanceUntilIdle()

            store.refresh()
            assertEquals(1, api.puts.size)

            tokenManager.snapshot = oldLogin.copy(identityGeneration = 3)
            store.refresh()
            assertEquals(2, api.puts.size)
            assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun temporaryCredentialGenerationSeparatesOverlayOwners() = runTest {
        val api = FakeSettingsApi()
        val firstOverlay = authScope(
            "server-a",
            "https://server-a.example",
            generation = 1,
        ).copy(
            credentialGenerationId = "temporary-owner-a",
            credentialOwnerId = null,
            credentialEpoch = 0,
        )
        val tokenManager = SnapshotTokenManager(firstOverlay)
        val (store, storeScope) = store(api, tokenManager)
        try {
            store.refresh()
            api.putGate = CompletableDeferred()
            store.setCardPresentation(
                CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK),
            )
            runCurrent()
            assertEquals(1, api.puts.size)

            tokenManager.snapshot = firstOverlay.copy(
                credentialGenerationId = "temporary-owner-b",
                identityGeneration = 2,
            )
            api.putGate?.complete(Unit)
            advanceUntilIdle()

            store.refresh()
            assertEquals(1, api.puts.size)

            tokenManager.snapshot = firstOverlay.copy(identityGeneration = 3)
            store.refresh()
            assertEquals(2, api.puts.size)
            assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun putUsesCapturedServerScopeAndRecoversOnlyWhenThatServerReturns() = runTest {
        val api = FakeSettingsApi()
        val cache = InMemoryCache()
        val scopeA = authScope("server-a", "https://server-a.example", generation = 1)
        val scopeB = authScope("server-b", "https://server-b.example", generation = 2)
        val tokenManager = SnapshotTokenManager(scopeA)
        val (store, storeScope) = store(api, tokenManager, cache)
        val desired = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        try {
            store.refresh()
            val gate = api.pauseNextPutConstruction()
            store.setCardPresentation(desired)
            gate.started.await()

            tokenManager.snapshot = scopeB
            gate.release.complete(Unit)
            advanceUntilIdle()
            assertEquals(scopeA, api.puts.single().authScope)

            store.refresh()
            assertEquals(1, api.puts.size)

            val returnedA = scopeA.copy(identityGeneration = 3)
            tokenManager.snapshot = returnedA
            store.refresh()
            assertEquals(2, api.puts.size)
            assertEquals(returnedA, api.puts.last().authScope)
            assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
            assertEquals(api.puts.first().value, api.puts.last().value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun deleteUsesCapturedServerScopeAndDoesNotDrainOnAnotherServer() = runTest {
        val api = FakeSettingsApi()
        val scopeA = authScope("server-a", "https://server-a.example", generation = 1)
        val scopeB = authScope("server-b", "https://server-b.example", generation = 2)
        val tokenManager = SnapshotTokenManager(scopeA)
        val (store, storeScope) = store(api, tokenManager)
        try {
            store.refresh()
            val gate = api.pauseNextDeleteConstruction()
            store.resetPrimaryMenu()
            gate.started.await()

            tokenManager.snapshot = scopeB
            gate.release.complete(Unit)
            advanceUntilIdle()
            assertEquals(scopeA, api.deletes.single().authScope)

            store.refresh()
            assertEquals(1, api.deletes.size)

            val returnedA = scopeA.copy(identityGeneration = 3)
            tokenManager.snapshot = returnedA
            store.refresh()
            assertEquals(listOf(scopeA, returnedA), api.deletes.map { it.authScope })
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun shortcutUsesCapturedServerScopeAndDoesNotDrainOnAnotherServer() = runTest {
        val api = FakeSettingsApi()
        val scopeA = authScope("server-a", "https://server-a.example", generation = 1)
        val scopeB = authScope("server-b", "https://server-b.example", generation = 2)
        val tokenManager = SnapshotTokenManager(scopeA)
        val (store, storeScope) = store(api, tokenManager)
        val library = PrimaryMenuItem.Library(7, "Movies")
        try {
            store.refresh()
            val gate = api.pauseNextShortcutConstruction()
            store.setShortcutPresent(library, present = true)
            gate.started.await()

            tokenManager.snapshot = scopeB
            gate.release.complete(Unit)
            advanceUntilIdle()
            assertEquals(scopeA, api.shortcutPuts.single().authScope)

            store.refresh()
            assertEquals(1, api.shortcutPuts.size)

            val returnedA = scopeA.copy(identityGeneration = 3)
            tokenManager.snapshot = returnedA
            store.refresh()
            assertEquals(listOf(scopeA, returnedA), api.shortcutPuts.map { it.authScope })
            assertEquals(
                api.shortcutPuts.first().mutationId,
                api.shortcutPuts.last().mutationId,
            )
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun compoundIntentPinsFirstAttemptAndRecoversBothHalvesOnOriginalServer() = runTest {
        val api = FakeSettingsApi()
        val scopeA = authScope("server-a", "https://server-a.example", generation = 1)
        val scopeB = authScope("server-b", "https://server-b.example", generation = 2)
        val tokenManager = SnapshotTokenManager(scopeA)
        val (store, storeScope) = store(api, tokenManager)
        val library = PrimaryMenuItem.Library(7, "Movies")
        val menu = PrimaryMenu(
            listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME), library),
        )
        try {
            store.refresh()
            val gate = api.pauseNextPutConstruction()
            store.setPrimaryMenuAndShortcut(menu, library, present = true)
            gate.started.await()

            tokenManager.snapshot = scopeB
            gate.release.complete(Unit)
            advanceUntilIdle()
            assertEquals(scopeA, api.puts.single().authScope)
            assertEquals(0, api.shortcutPuts.size)

            store.refresh()
            assertEquals(1, api.puts.size)
            assertEquals(0, api.shortcutPuts.size)

            val returnedA = scopeA.copy(identityGeneration = 3)
            tokenManager.snapshot = returnedA
            store.refresh()
            assertEquals(2, api.puts.size)
            assertEquals(returnedA, api.puts.last().authScope)
            assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
            assertEquals(api.puts.first().value, api.puts.last().value)
            assertEquals(listOf(returnedA), api.shortcutPuts.map { it.authScope })
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun clearRejectsOldGetEvenAfterSameIdentityReactivates() = runTest {
        val stale = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        val fresh = CardPresentation.DEFAULT
        val api = FakeSettingsApi(
            effective = cardEffective(stale),
        )
        val (store, storeScope) = store(api)
        try {
            val staleGate = api.pauseNextGet()
            val staleRefresh = launch { store.refresh() }
            staleGate.started.await()

            store.clear()
            api.effective = cardEffective(fresh)
            val freshGate = api.pauseNextGet()
            val freshRefresh = launch { store.refresh() }
            runCurrent()
            assertEquals(fresh, store.cardPresentation.value)

            staleGate.release.complete(Unit)
            freshGate.started.await()
            // The old response has returned while the fresh response remains
            // blocked. It must not repaint the same logical identity.
            assertEquals(fresh, store.cardPresentation.value)

            freshGate.release.complete(Unit)
            staleRefresh.join()
            freshRefresh.join()
            assertEquals(fresh, store.cardPresentation.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun serverSwitchPaintsNewCacheBeforeSlowOldRefreshCompletes() = runTest {
        val api = FakeSettingsApi(online = false)
        val scopeB = authScope("server-b", "https://server-b.example", generation = 1)
        val scopeA = authScope("server-a", "https://server-a.example", generation = 2)
        val tokenManager = SnapshotTokenManager(scopeB)
        val (store, storeScope) = store(api, tokenManager)
        val cachedB = CardPresentation(PosterSizePreset.LARGE, CardCaptionPreset.ARTWORK)
        try {
            store.refresh()
            store.setCardPresentation(cachedB)
            advanceUntilIdle()

            tokenManager.snapshot = scopeA
            api.online = true
            val gate = api.pauseNextGet()
            val refreshA = launch { store.refresh() }
            gate.started.await()
            assertEquals(CardPresentation.DEFAULT, store.cardPresentation.value)

            tokenManager.snapshot = scopeB.copy(identityGeneration = 3)
            val refreshB = launch { store.refresh() }
            runCurrent()
            // B activation is not serialized behind A's network request.
            assertEquals(cachedB, store.cardPresentation.value)

            api.online = false
            gate.release.complete(Unit)
            refreshA.join()
            refreshB.join()
            assertEquals(cachedB, store.cardPresentation.value)
            assertEquals(
                listOf(scopeA, scopeB.copy(identityGeneration = 3)),
                api.gets.takeLast(2).map { it.authScope },
            )
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun foldableRestartFromMobileToTabletDoesNotReplayMobileMenu() = runTest {
        verifyFamilyReclassification(
            originalFamily = SiloClientFamily.MOBILE,
            reclassifiedFamily = SiloClientFamily.TABLET,
        )
    }

    @Test
    fun foldableRestartFromTabletToMobileKeepsShortcutProfileWide() = runTest {
        verifyFamilyReclassification(
            originalFamily = SiloClientFamily.TABLET,
            reclassifiedFamily = SiloClientFamily.MOBILE,
        )
    }

    @Test
    fun liveFoldableReclassificationRekeysStoreWithoutReplayingTheOtherFamilyMenu() = runTest {
        var currentFamily = SiloClientFamily.MOBILE
        val cache = InMemoryCache()
        val api = FakeSettingsApi(online = false)
        val tokenManager = TokenManagerImpl().also {
            it.setServerUrl("https://silo.example")
            it.setProfileId("profile-one")
        }
        val library = PrimaryMenuItem.Library(7, "Movies")
        val mobileMenu = PrimaryMenu(
            listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME), library),
        )
        val (store, storeScope) = store(
            api = api,
            tokenManager = tokenManager,
            cache = cache,
            family = currentFamily,
            familyProvider = { currentFamily },
        )
        try {
            store.refresh()
            store.setPrimaryMenuAndShortcut(mobileMenu, library, present = true)
            advanceUntilIdle()
            assertEquals(SiloClientFamily.MOBILE, store.family)
            assertEquals(1, api.puts.size)
            assertEquals(SiloClientFamily.MOBILE, api.puts.single().scope.clientFamily)
            assertEquals(1, api.shortcutPuts.size)

            currentFamily = SiloClientFamily.TABLET
            store.reclassifyClientFamily()

            // The lifecycle callback re-keys synchronously, before its async
            // refresh can start, so no mobile-family presentation leaks across.
            assertEquals(SiloClientFamily.TABLET, store.family)
            assertEquals(null, store.primaryMenu.value)
            assertEquals(NavigationShortcuts(listOf(library)), store.shortcuts.value)

            api.online = true
            store.refresh()

            // The mobile menu stays queued while its profile-wide shortcut drains.
            assertEquals(1, api.puts.size)
            assertEquals(2, api.shortcutPuts.size)

            currentFamily = SiloClientFamily.MOBILE
            store.reclassifyClientFamily()

            // Returning also paints the durable mobile cache synchronously.
            assertEquals(mobileMenu, store.primaryMenu.value)
            store.refresh()

            assertEquals(mobileMenu, store.primaryMenu.value)
            assertEquals(NavigationShortcuts(listOf(library)), store.shortcuts.value)
            assertEquals(2, api.puts.size)
            assertEquals(SiloClientFamily.MOBILE, api.puts.last().scope.clientFamily)
            assertEquals(2, api.shortcutPuts.size)
            assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
            assertEquals(api.puts.first().value, api.puts.last().value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun familyBoundaryRejectsAnOldQueuedEditBeforeItCanReachNewFamilyStorage() = runTest {
        var currentFamily = SiloClientFamily.MOBILE
        val api = FakeSettingsApi()
        val (store, storeScope) = store(
            api = api,
            family = currentFamily,
            familyProvider = { currentFamily },
        )
        val firstMenu = PrimaryMenu(
            listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)),
        )
        val queuedMenu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )
        try {
            store.refresh()
            store.setPrimaryMenu(firstMenu)
            advanceUntilIdle()
            assertEquals(1, api.puts.size)

            // Leave this command in the authoring channel, then cross the
            // family boundary before its binding or cache key is resolved.
            store.setPrimaryMenu(queuedMenu)
            currentFamily = SiloClientFamily.TABLET
            store.reclassifyClientFamily()
            assertEquals(null, store.primaryMenu.value)

            advanceUntilIdle()
            assertEquals(1, api.puts.size, "the stale command must be rejected")
            assertEquals(null, store.primaryMenu.value)

            currentFamily = SiloClientFamily.MOBILE
            store.reclassifyClientFamily()
            assertEquals(firstMenu, store.primaryMenu.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun inFlightOldFamilyEditKeepsItsBindingAndCannotRepaintNewFamily() = runTest {
        var currentFamily = SiloClientFamily.MOBILE
        val api = FakeSettingsApi()
        val (store, storeScope) = store(
            api = api,
            family = currentFamily,
            familyProvider = { currentFamily },
        )
        val mobileMenu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )
        try {
            store.refresh()
            val gate = api.pauseNextPutConstruction()
            store.setPrimaryMenu(mobileMenu)
            gate.started.await()

            currentFamily = SiloClientFamily.TABLET
            store.reclassifyClientFamily()
            assertEquals(null, store.primaryMenu.value)

            gate.release.complete(Unit)
            advanceUntilIdle()
            assertEquals(SiloClientFamily.MOBILE, api.puts.single().scope.clientFamily)
            assertEquals(null, store.primaryMenu.value)

            currentFamily = SiloClientFamily.MOBILE
            store.reclassifyClientFamily()
            assertEquals(mobileMenu, store.primaryMenu.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun offlineCacheIsIsolatedByServerWhenProfileIdsMatch() = runTest {
        val api = FakeSettingsApi(online = false)
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://server-a.example")
            setProfileId("same-profile")
        }
        val (store, storeScope) = store(api, tokenManager)
        try {
            store.refresh()
            val serverAChoice = CardPresentation(
                PosterSizePreset.LARGE,
                CardCaptionPreset.ARTWORK,
            )
            store.setCardPresentation(serverAChoice)
            advanceUntilIdle()

            tokenManager.setServerUrl("https://server-b.example")
            store.refresh()
            assertEquals(CardPresentation.DEFAULT, store.cardPresentation.value)

            tokenManager.setServerUrl("https://server-a.example")
            store.refresh()
            assertEquals(serverAChoice, store.cardPresentation.value)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun temporaryScopeTransitionsClearInlineAndRehydrateEachAtomicOwner() = runTest {
        val savedPresentation = CardPresentation(
            PosterSizePreset.LARGE,
            CardCaptionPreset.ARTWORK,
        )
        val guestPresentation = CardPresentation(
            PosterSizePreset.COMPACT,
            CardCaptionPreset.TITLE,
        )
        val api = FakeSettingsApi(effective = cardEffective(savedPresentation))
        val savedScope = authScope(
            "saved-server",
            "https://saved.example",
            generation = 1,
        )
        val guestScope = AuthScopeSnapshot(
            serverId = "guest-server",
            profileId = "guest-profile",
            serverUrl = "https://guest.example",
            profileToken = "guest-profile-token",
            credentialGenerationId = "guest-generation",
            identityGeneration = 2,
        )
        val tokenManager = SnapshotTokenManager(savedScope)
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val (store, storeScope) = store(
            api = api,
            tokenManager = tokenManager,
            identityTransitions = identityTransitions,
        )
        try {
            store.refresh()
            assertEquals(savedPresentation, store.cardPresentation.value)

            api.effective = cardEffective(guestPresentation)
            identityTransitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_BEGIN) {
                assertEquals(CardPresentation.DEFAULT, store.cardPresentation.value)
                tokenManager.snapshot = guestScope
            }
            advanceUntilIdle()

            assertEquals(guestPresentation, store.cardPresentation.value)
            assertEquals("guest-profile", api.gets.last().profileId)
            assertEquals(
                "guest-generation",
                api.gets.last().authScope?.credentialGenerationId,
            )

            api.effective = cardEffective(savedPresentation)
            identityTransitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_END) {
                assertEquals(CardPresentation.DEFAULT, store.cardPresentation.value)
                tokenManager.snapshot = savedScope.copy(identityGeneration = 3)
            }
            advanceUntilIdle()

            assertEquals(savedPresentation, store.cardPresentation.value)
            assertEquals("same-profile", api.gets.last().profileId)
            assertEquals(
                savedScope.credentialOwnerId,
                api.gets.last().authScope?.credentialOwnerId,
            )
        } finally {
            storeScope.cancel()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.store(
        api: FakeSettingsApi,
        tokenManager: TokenManager? = null,
        cache: InMemoryCache = InMemoryCache(),
        family: SiloClientFamily = SiloClientFamily.TV,
        familyProvider: () -> SiloClientFamily = { family },
        identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    ): Pair<DefaultUiCustomizationStore, CoroutineScope> {
        val resolvedTokenManager = tokenManager ?: TokenManagerImpl().also {
            it.setServerUrl("https://silo.example")
            it.setProfileId("profile-one")
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return DefaultUiCustomizationStore(
            family = family,
            repository = SettingsRepository(api),
            tokenManager = resolvedTokenManager,
            cache = cache,
            scope = scope,
            identityTransitions = identityTransitions,
            familyProvider = familyProvider,
        ) to scope
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyFamilyReclassification(
        originalFamily: SiloClientFamily,
        reclassifiedFamily: SiloClientFamily,
    ) {
        val cache = InMemoryCache()
        val api = FakeSettingsApi(online = false)
        val tokenManager = TokenManagerImpl().also {
            it.setServerUrl("https://silo.example")
            it.setProfileId("profile-one")
        }
        val library = PrimaryMenuItem.Library(7, "Movies")
        val originalMenu = PrimaryMenu(
            listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME), library),
        )
        val (originalStore, originalScope) = store(
            api = api,
            tokenManager = tokenManager,
            cache = cache,
            family = originalFamily,
        )
        try {
            originalStore.refresh()
            originalStore.setPrimaryMenuAndShortcut(
                originalMenu,
                library,
                present = true,
            )
            advanceUntilIdle()
            assertEquals(1, api.puts.size)
            assertEquals(originalFamily, api.puts.single().scope.clientFamily)
            assertEquals(1, api.shortcutPuts.size)

            originalScope.cancel()
            api.online = true
            val (reclassifiedStore, reclassifiedScope) = store(
                api = api,
                tokenManager = tokenManager,
                cache = cache,
                family = reclassifiedFamily,
            )
            try {
                reclassifiedStore.refresh()

                // The menu belongs to the original family and stays queued;
                // the shortcut is profile-wide and recovers immediately.
                assertEquals(null, reclassifiedStore.primaryMenu.value)
                assertEquals(NavigationShortcuts(listOf(library)), reclassifiedStore.shortcuts.value)
                assertEquals(1, api.puts.size)
                assertEquals(2, api.shortcutPuts.size)
            } finally {
                reclassifiedScope.cancel()
            }

            val (returnedStore, returnedScope) = store(
                api = api,
                tokenManager = tokenManager,
                cache = cache,
                family = originalFamily,
            )
            try {
                returnedStore.refresh()

                assertEquals(originalMenu, returnedStore.primaryMenu.value)
                assertEquals(NavigationShortcuts(listOf(library)), returnedStore.shortcuts.value)
                assertEquals(2, api.puts.size)
                assertEquals(originalFamily, api.puts.last().scope.clientFamily)
                assertEquals(2, api.shortcutPuts.size)
                assertEquals(api.puts.first().mutationId, api.puts.last().mutationId)
                assertEquals(api.puts.first().value, api.puts.last().value)
            } finally {
                returnedScope.cancel()
            }
        } finally {
            originalScope.cancel()
        }
    }

    private fun authScope(serverId: String, serverUrl: String, generation: Long) =
        AuthScopeSnapshot(
            serverId = serverId,
            profileId = "same-profile",
            serverUrl = serverUrl,
            profileToken = "fake-" + serverId,
            identityGeneration = generation,
            credentialOwnerId = "owner-" + serverId,
            credentialEpoch = 1,
        )

    private fun shortcutEffective(
        value: NavigationShortcuts,
    ): Map<String, EffectiveSettingValue> =
        mapOf(
            SettingKeys.NAV_SHORTCUTS to EffectiveSettingValue(
                key = SettingKeys.NAV_SHORTCUTS,
                value = UiCustomizationCodec.encodeShortcuts(value),
                source = SettingScope.PROFILE.wire,
                scope = SettingScope.PROFILE.wire,
            ),
        )

    private fun cardEffective(value: CardPresentation): Map<String, EffectiveSettingValue> =
        mapOf(
            SettingKeys.UI_CARD_PRESENTATION to EffectiveSettingValue(
                key = SettingKeys.UI_CARD_PRESENTATION,
                value = UiCustomizationCodec.encodeCardPresentation(value),
                source = SettingScope.PROFILE_CLIENT.wire,
                scope = SettingScope.PROFILE_CLIENT.wire,
            ),
        )

    private class SnapshotTokenManager(
        var snapshot: AuthScopeSnapshot,
        private val delegate: TokenManager = TokenManagerImpl(),
    ) : TokenManager by delegate {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = snapshot
    }

    private class CallGate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class FakeSettingsApi(
        var effective: Map<String, EffectiveSettingValue> = emptyMap(),
        var online: Boolean = true,
        var capabilityResult: SettingsCapabilitiesResult =
            SettingsCapabilitiesResult.Available(
                SettingsContractCapabilities(
                    revision = 5,
                    supportsBatchedEffective = true,
                    supportsIdempotentWrites = true,
                    supportsAtomicShortcuts = true,
                ),
            ),
    ) : SettingsApi(HttpClient()) {
        data class Get(
            val profileId: String?,
            val authScope: AuthScopeSnapshot?,
        )
        data class Put(
            val key: String,
            val scope: SettingScopeIdentity,
            val value: JsonElement,
            val mutationId: String,
            val profileId: String?,
            val authScope: AuthScopeSnapshot?,
        )
        data class ShortcutPut(
            val item: JsonElement,
            val present: Boolean,
            val mutationId: String,
            val profileId: String?,
            val authScope: AuthScopeSnapshot?,
        )
        data class Delete(
            val key: String,
            val scope: SettingScopeIdentity,
            val authScope: AuthScopeSnapshot?,
        )

        val gets = mutableListOf<Get>()
        val puts = mutableListOf<Put>()
        val shortcutPuts = mutableListOf<ShortcutPut>()
        val deletes = mutableListOf<Delete>()
        var getGate: CompletableDeferred<Unit>? = null
        var putGate: CompletableDeferred<Unit>? = null
        var shortcutPutGate: CompletableDeferred<Unit>? = null
        private val getCallGates = ArrayDeque<CallGate>()
        var putConstructionGate: CallGate? = null
        var shortcutConstructionGate: CallGate? = null
        var deleteConstructionGate: CallGate? = null
        var onPut: ((Put) -> ApiResult<StoredSettingValue>)? = null
        var onShortcutPut: ((ShortcutPut) -> ApiResult<StoredSettingValue>)? = null
        var onDelete: ((String, SettingScopeIdentity) -> Unit)? = null
        var onDeleteResult: ((String, SettingScopeIdentity) -> ApiResult<Unit>?)? = null

        fun pauseNextGet(): CallGate = CallGate().also(getCallGates::addLast)

        fun pauseNextPutConstruction(): CallGate = CallGate().also {
            check(putConstructionGate == null)
            putConstructionGate = it
        }

        fun pauseNextShortcutConstruction(): CallGate = CallGate().also {
            check(shortcutConstructionGate == null)
            shortcutConstructionGate = it
        }

        fun pauseNextDeleteConstruction(): CallGate = CallGate().also {
            check(deleteConstructionGate == null)
            deleteConstructionGate = it
        }

        override suspend fun getContractCapabilities(): SettingsCapabilitiesResult =
            capabilityResult

        override suspend fun getEffectiveValues(
            keys: List<String>,
            libraryIds: List<Int>,
            seriesIds: List<String>,
            profileId: String?,
            authScope: AuthScopeSnapshot?,
        ): ApiResult<EffectiveSettingValuesResponse> {
            val responseValues = effective
            getCallGates.removeFirstOrNull()?.let { gate ->
                gate.started.complete(Unit)
                gate.release.await()
            }
            getGate?.await()
            gets += Get(profileId, authScope)
            return if (online) {
                ApiResult.Success(
                    EffectiveSettingValuesResponse(
                        settings = keys.mapNotNull(responseValues::get),
                        revision = SettingKeys.REVISION,
                    ),
                )
            } else {
                ApiResult.NetworkError(IOException("offline"))
            }
        }

        override suspend fun putValue(
            key: String,
            scope: SettingScopeIdentity,
            value: JsonElement,
            mutationId: String,
            profileId: String?,
            authScope: AuthScopeSnapshot?,
        ): ApiResult<StoredSettingValue> {
            putConstructionGate?.also { gate ->
                putConstructionGate = null
                gate.started.complete(Unit)
                gate.release.await()
            }
            val put = Put(key, scope, value, mutationId, profileId, authScope)
            puts += put
            putGate?.await()
            onPut?.let { return it(put) }
            return if (online) {
                ApiResult.Success(
                    StoredSettingValue(key = key, scope = scope.scope.wire, value = value),
                )
            } else {
                ApiResult.NetworkError(IOException("offline"))
            }
        }

        override suspend fun putNavigationShortcutItem(
            item: JsonElement,
            present: Boolean,
            mutationId: String,
            profileId: String?,
            authScope: AuthScopeSnapshot?,
        ): ApiResult<StoredSettingValue> {
            shortcutConstructionGate?.also { gate ->
                shortcutConstructionGate = null
                gate.started.complete(Unit)
                gate.release.await()
            }
            val put = ShortcutPut(item, present, mutationId, profileId, authScope)
            shortcutPuts += put
            val gate = shortcutPutGate
            shortcutPutGate = null
            gate?.await()
            onShortcutPut?.let { return it(put) }
            return if (online) {
                ApiResult.Success(applyShortcutPut(put))
            } else {
                ApiResult.NetworkError(IOException("offline"))
            }
        }

        fun applyShortcutPut(put: ShortcutPut): StoredSettingValue {
            val item = checkNotNull(UiCustomizationCodec.parseShortcutItem(put.item))
            val current = effective[SettingKeys.NAV_SHORTCUTS]?.value
                ?.let(UiCustomizationCodec::parseShortcuts)
                ?: NavigationShortcuts.EMPTY
            val identity = UiCustomizationCodec.identity(item)
            val withoutItem = current.items.filterNot {
                UiCustomizationCodec.identity(it) == identity
            }
            val next = if (put.present && current.items.any {
                    UiCustomizationCodec.identity(it) == identity
                }
            ) {
                current
            } else if (put.present) {
                NavigationShortcuts(withoutItem + item)
            } else {
                NavigationShortcuts(withoutItem)
            }
            val encoded = UiCustomizationCodec.encodeShortcuts(next)
            effective = effective + (
                SettingKeys.NAV_SHORTCUTS to EffectiveSettingValue(
                    key = SettingKeys.NAV_SHORTCUTS,
                    value = encoded,
                    source = SettingScope.PROFILE.wire,
                    scope = SettingScope.PROFILE.wire,
                )
            )
            return StoredSettingValue(
                key = SettingKeys.NAV_SHORTCUTS,
                scope = SettingScope.PROFILE.wire,
                value = encoded,
            )
        }

        override suspend fun deleteValue(
            key: String,
            scope: SettingScopeIdentity,
            profileId: String?,
            authScope: AuthScopeSnapshot?,
        ): ApiResult<Unit> {
            deleteConstructionGate?.also { gate ->
                deleteConstructionGate = null
                gate.started.complete(Unit)
                gate.release.await()
            }
            deletes += Delete(key, scope, authScope)
            return if (online) {
                onDeleteResult?.invoke(key, scope)?.let { return it }
                onDelete?.invoke(key, scope)
                ApiResult.Success(Unit)
            } else {
                ApiResult.NetworkError(IOException("offline"))
            }
        }
    }

    private class InMemoryCache : AndroidServerSettingsCache(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        private val values = mutableMapOf<String, String>()
        var afterPutString: ((String) -> Unit)? = null
        private fun key(serverUrl: String, name: String) = "${serverUrl.trimEnd('/')}|$name"

        override fun getString(serverUrl: String, key: String, defaultValue: String): String =
            values[key(serverUrl, key)] ?: defaultValue

        override fun putString(serverUrl: String, key: String, value: String) {
            values[key(serverUrl, key)] = value
            afterPutString?.invoke(key)
        }

        override fun getBoolean(serverUrl: String, key: String, defaultValue: Boolean): Boolean =
            values[key(serverUrl, key)]?.toBooleanStrictOrNull() ?: defaultValue

        override fun putBoolean(serverUrl: String, key: String, value: Boolean) {
            values[key(serverUrl, key)] = value.toString()
        }
    }
}
