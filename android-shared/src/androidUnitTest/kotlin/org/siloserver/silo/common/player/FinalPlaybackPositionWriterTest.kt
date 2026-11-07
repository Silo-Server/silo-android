package org.siloserver.silo.common.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.port.PlaybackWriteScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinalPlaybackPositionWriterTest {
    private val scopeA = PlaybackWriteScope("server", "profile-a", null, 4L)
    private val scopeB = PlaybackWriteScope("server", "profile-b", null, 5L)

    @Test
    fun submitReturnsBeforeSuspendedWriteCompletes() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = FinalPlaybackPositionWriter(
            scope = backgroundScope,
            scopeProvider = { null },
            write = {
                started.complete(Unit)
                release.await()
            },
        )

        assertTrue(writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 42.0, 100.0)))
        runCurrent()
        assertTrue(started.isCompleted)
        assertFalse(release.isCompleted)
        release.complete(Unit)
    }

    @Test
    fun aFailedWriteIsRetriedInsteadOfDiscarded() = runTest {
        // The drain removes the batch from `pending` before writing, so a
        // swallowed failure lost the user's final position outright — and this
        // queue is the durable record that survives a server-side session reset.
        val written = mutableListOf<FinalPlaybackPosition>()
        var failuresLeft = 1
        val writer = FinalPlaybackPositionWriter(
            scope = backgroundScope,
            scopeProvider = { null },
            write = {
                if (failuresLeft > 0) {
                    failuresLeft--
                    error("room write failed")
                }
                written += it
            },
        )

        assertTrue(writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 42.0, 100.0)))
        runCurrent()
        assertEquals(emptyList(), written, "first attempt fails")

        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, written.size, "the position must be rewritten, not dropped")
        assertEquals(42.0, written.single().positionSeconds)
    }

    @Test
    fun aNewerSubmissionWinsOverARequeuedFailure() = runTest {
        val written = mutableListOf<FinalPlaybackPosition>()
        var failuresLeft = 1
        val writer = FinalPlaybackPositionWriter(
            scope = backgroundScope,
            scopeProvider = { null },
            write = {
                if (failuresLeft > 0) {
                    failuresLeft--
                    error("room write failed")
                }
                written += it
            },
        )

        assertTrue(writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 42.0, 100.0)))
        runCurrent()
        // The user kept watching while that write was failing.
        assertTrue(writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 90.0, 100.0)))
        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(
            listOf(90.0),
            written.map { it.positionSeconds },
            "the retry must not resurrect a position older than the newest intent",
        )
    }

    @Test
    fun invalidSnapshotsAreRejected() = runTest {
        val writer = FinalPlaybackPositionWriter(
            scope = backgroundScope,
            scopeProvider = { null },
            write = { error("must not write") },
        )

        assertFalse(writer.submit(FinalPlaybackPosition(scopeA, "", 7, 1.0, 2.0)))
        assertFalse(writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, -1.0, 2.0)))
        assertFalse(writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, Double.NaN, 2.0)))
        assertFalse(writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 1.0, Double.NaN)))
    }

    @Test
    fun pendingSnapshotsForSameFileCoalesceToNewestValue() = runTest {
        val written = mutableListOf<FinalPlaybackPosition>()
        val writer = FinalPlaybackPositionWriter(
            scope = backgroundScope,
            scopeProvider = { null },
            write = { written += it },
        )

        writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 10.0, 100.0))
        writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 20.0, 100.0))
        writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 30.0, 100.0))
        runCurrent()

        assertEquals(
            listOf(FinalPlaybackPosition(scopeA, "movie", 7, 30.0, 100.0, eventTimeMs = 0)),
            written.map { it.copy(eventTimeMs = 0) },
        )
    }

    @Test
    fun pendingSnapshotsFromDifferentProfilesNeverCoalesce() = runTest {
        val written = mutableListOf<FinalPlaybackPosition>()
        val writer = FinalPlaybackPositionWriter(
            scope = backgroundScope,
            scopeProvider = { null },
            write = { written += it },
        )

        writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 10.0, 100.0))
        writer.submit(FinalPlaybackPosition(scopeB, "movie", 7, 20.0, 100.0))
        runCurrent()

        assertEquals(listOf(scopeA, scopeB), written.map { it.scope })
    }

    @Test
    fun playbackScopeContainsIdentifiersOnly() {
        assertEquals(
            setOf("serverId", "profileId", "credentialGenerationId", "identityGeneration"),
            PlaybackWriteScope::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun captureScopeCopiesOnlyIdentityFields() = runTest {
        val writer = FinalPlaybackPositionWriter(
            scope = backgroundScope,
            scopeProvider = {
                AuthScopeSnapshot(
                    serverId = "server",
                    profileId = "profile-a",
                    serverUrl = "https://server.example",
                    profileToken = "secret-profile-token",
                    credentialGenerationId = "credential",
                    identityGeneration = 4L,
                )
            },
            write = { error("must not write") },
        )

        assertEquals(
            PlaybackWriteScope("server", "profile-a", "credential", 4L),
            writer.captureScope(),
        )
    }

    @Test
    fun captureScopeRejectsMissingProfile() = runTest {
        val writer = FinalPlaybackPositionWriter(
            scope = backgroundScope,
            scopeProvider = {
                AuthScopeSnapshot(
                    serverId = "server",
                    profileId = null,
                    serverUrl = "https://server.example",
                    profileToken = null,
                    identityGeneration = 4L,
                )
            },
            write = { error("must not write") },
        )

        assertNull(writer.captureScope())
    }
}
