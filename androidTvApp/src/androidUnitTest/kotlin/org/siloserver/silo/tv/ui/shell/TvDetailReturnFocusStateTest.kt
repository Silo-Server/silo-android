package org.siloserver.silo.tv.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDetailReturnFocusStateTest {
    @Test
    fun requestedRetryKeepsCardFallbackPending() {
        val state = beginTvDetailReturnRetry(previousRequestId = 7, needsRetry = true)

        assertEquals(8, state.requestId)
        assertTrue(state.needsRetry)
        assertTrue(state.fallbackPending)
    }

    @Test
    fun completedRetryClearsRetryAndFallback() {
        val completed = completeTvDetailReturnRetry(
            TvDetailReturnFocusState(requestId = 8, needsRetry = true, fallbackPending = true),
        )

        assertEquals(8, completed.requestId)
        assertFalse(completed.needsRetry)
        assertFalse(completed.fallbackPending)
    }

    @Test
    fun explicitRootSelectionResetsReturnState() {
        assertEquals(
            TvDetailReturnFocusState(),
            resetTvDetailReturnFocus(),
        )
    }

    @Test
    fun otherRootRetryDoesNotArmThisRootsFallback() {
        val state = beginTvDetailReturnRetryIfRoot(
            previousState = TvDetailReturnFocusState(),
            isDetailReturnForRoot = false,
            needsRetry = true,
        )

        assertEquals(0, state.requestId)
        assertFalse(state.needsRetry)
        assertFalse(state.fallbackPending)
    }

    @Test
    fun rootRetryArmsCardFallbackUntilRetryCompletes() {
        val state = beginTvDetailReturnRetryIfRoot(
            previousState = TvDetailReturnFocusState(requestId = 7),
            isDetailReturnForRoot = true,
            needsRetry = true,
        )

        assertEquals(8, state.requestId)
        assertTrue(state.needsRetry)
        assertTrue(state.fallbackPending)
    }

    @Test
    fun successfulResumeDoesNotLeaveRetryOrFallbackPending() {
        val state = beginTvDetailReturnRetryIfRoot(
            previousState = TvDetailReturnFocusState(
                requestId = 7,
                needsRetry = true,
                fallbackPending = true,
            ),
            isDetailReturnForRoot = true,
            needsRetry = false,
        )

        assertEquals(8, state.requestId)
        assertFalse(state.needsRetry)
        assertFalse(state.fallbackPending)
    }
}
