package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextUpTransitionGateTest {
    @Test
    fun `only successor first frame completes transition`() {
        val gate = NextUpTransitionGate()

        assertTrue(gate.begin("episode-b"))
        assertFalse(gate.completeOnFirstFrame(1L))
        assertFalse(gate.expectMount("episode-a", 2L))
        assertTrue(gate.expectMount("episode-b", 2L))
        assertFalse(gate.completeOnFirstFrame(1L))
        assertTrue(gate.isActive)
        assertTrue(gate.completeOnFirstFrame(2L))
        assertFalse(gate.isActive)
    }

    @Test
    fun `queued predecessor frame cannot complete successor recovery mount`() {
        val gate = NextUpTransitionGate()

        assertTrue(gate.begin("episode-b"))
        assertTrue(gate.expectMount("episode-b", 10L))
        assertTrue(gate.expectMount("episode-b", 11L))
        assertFalse(gate.completeOnFirstFrame(10L))
        assertTrue(gate.isActive)
        assertTrue(gate.completeOnFirstFrame(11L))
        assertFalse(gate.isActive)
    }

    @Test
    fun `duplicate actions are rejected until completion or cancel`() {
        val gate = NextUpTransitionGate()

        assertTrue(gate.begin("episode-b"))
        assertFalse(gate.begin("episode-b"))
        assertFalse(gate.begin("episode-c"))
        gate.cancel()
        assertTrue(gate.begin("episode-c"))
    }
}
