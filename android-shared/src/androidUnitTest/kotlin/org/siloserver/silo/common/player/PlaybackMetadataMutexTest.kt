package org.siloserver.silo.common.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.playback.*
import org.siloserver.silo.network.*
import org.siloserver.silo.repository.PlaybackRepository
import kotlin.test.*

class PlaybackMetadataMutexTest {
    @Test fun lifecycleWaitRechecksOwnerBeforePublishingAcknowledgedSession() = runTest {
        val client = HttpClient(MockEngine { error("No real dispatch") })
        try {
            val stopped = mutableListOf<String>()
            val manager = object : PlaybackSessionManager(PlaybackRepository(testSequencedPlayback(client, TokenManagerImpl())), TokenManagerImpl()) {
                override suspend fun stopSession(sessionId: String): ApiResult<Unit> { stopped += sessionId; return ApiResult.Success(Unit) }
            }
            val lifecycle = PlaybackSessionLifecycle(manager, org.siloserver.silo.network.api.HealthApi(client),
                org.siloserver.silo.repository.PersonalDataRepository(org.siloserver.silo.network.api.PersonalDataApi(client)), backgroundScope)
            val epoch = lifecycle.acquireOwnershipEpoch()
            val field = PlaybackSessionLifecycle::class.java.getDeclaredField("mutex").apply { isAccessible = true }
            val lock = field.get(lifecycle) as Mutex
            lock.lock()
            var valid = true
            val pending = async {
                lifecycle.adoptActiveSessionIfCurrent(
                    StartParams("item", 42, ClientCodecCapabilities(), clientPlaybackContext = ClientPlaybackContext(formFactor = "mobile", appVersion = "test")),
                    PlaybackSessionResponse("allocated", 1, "p", 42, PlayMethod.DIRECT, streamUrl = "https://example.invalid/stream"),
                    expectedOwnershipEpoch = epoch, expectedMetadataOwnerCurrent = { valid })
            }
            yield(); valid = false; lock.unlock()
            assertFalse(pending.await())
            assertFalse(lifecycle.state.value is SessionState.Active)
            assertEquals(listOf("allocated"), stopped)
        } finally { client.close() }
    }
    @Test fun replacementWhileWaitingForContentMutexRefusesBeforeResetAndNetwork() = runTest {
        var current = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1, isIdentityGenerationStamped = true, credentialEpoch = 1)
        val expected = current
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = current }
        var network = 0
        val client = HttpClient(MockEngine { network++; respond("{}", HttpStatusCode.ServiceUnavailable) })
        try {
            val manager = PlaybackSessionManager(PlaybackRepository(testSequencedPlayback(client, tokens)), tokens)
            val lockField = PlaybackSessionManager::class.java.getDeclaredField("contentStartMutex").apply { isAccessible = true }
            val lock = lockField.get(manager) as Mutex
            val orphanField = PlaybackSessionManager::class.java.getDeclaredField("orphanedSessionIds").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val orphans = orphanField.get(manager) as MutableSet<String>
            orphans += "unrelated-predecessor"
            lock.lock()
            val started = CompletableDeferred<Unit>()
            val pending = async {
                started.complete(Unit)
                manager.startVideoSessionV3(42, "p", ClientCodecCapabilities(), ClientPlaybackContext(formFactor = "mobile", appVersion = "test"), null, null, null, null, expectedMetadataOwner = expected)
            }
            started.await(); yield()
            current = current.copy(profileToken = "new", identityGeneration = 3)
            lock.unlock()
            assertEquals("identity_changed", assertIs<ApiResult.Error>(pending.await()).error)
            assertEquals(0, network)
            assertEquals(setOf("unrelated-predecessor"), orphans)
        } finally { client.close() }
    }
}

/** A real [SequencedPlayback] over [client]; the journal is in-memory and no login authority is saved. */
internal fun testSequencedPlayback(client: HttpClient, tokens: TokenManager): org.siloserver.silo.repository.SequencedPlayback {
    val journal = object : org.siloserver.silo.repository.PlaybackJournalStore {
        var entries = emptyList<org.siloserver.silo.repository.PlaybackJournalEntry>()
        override suspend fun read() = entries
        override suspend fun write(entries: List<org.siloserver.silo.repository.PlaybackJournalEntry>) { this.entries = entries }
    }
    val authorities = object : DurableLoginAuthorityProvider { override suspend fun snapshotDurableLoginAuthority(): DurableLoginAuthority? = null }
    return org.siloserver.silo.repository.SequencedPlayback(
        org.siloserver.silo.network.apiv2.PlaybackV2Api(client, org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted), tokens, authorities, journal) { "stop" }
}
