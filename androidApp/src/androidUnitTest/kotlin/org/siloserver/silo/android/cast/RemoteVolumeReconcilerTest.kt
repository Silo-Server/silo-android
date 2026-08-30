package org.siloserver.silo.android.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteVolumeReconcilerTest {
    @Test
    fun `latest requested volume survives stale replies during a burst`() {
        val reconciler = RemoteVolumeReconciler()

        reconciler.requested(0.5625, atMs = 1_000L)
        reconciler.requested(0.625, atMs = 1_100L)

        assertEquals(0.625, reconciler.reconcile(0.5625, atMs = 1_200L), 0.0001)
        assertEquals(0.625, reconciler.reconcile(0.625, atMs = 1_300L), 0.0001)
        assertEquals(0.2, reconciler.reconcile(0.2, atMs = 1_400L), 0.0001)
    }

    @Test
    fun `reversal cannot be acknowledged by a pre-request snapshot`() {
        val reconciler = RemoteVolumeReconciler()

        reconciler.requested(0.5625, atMs = 1_000L)
        reconciler.requested(0.5, atMs = 1_100L)

        assertEquals(0.5, reconciler.reconcile(0.5, atMs = 1_150L), 0.0001)
        assertEquals(0.5, reconciler.reconcile(0.5625, atMs = 1_200L), 0.0001)
        assertEquals(0.5, reconciler.reconcile(0.5, atMs = 1_250L), 0.0001)
        assertEquals(0.8, reconciler.reconcile(0.8, atMs = 1_300L), 0.0001)
    }

    @Test
    fun `held volume expires when the TV never confirms it`() {
        val reconciler = RemoteVolumeReconciler()
        reconciler.requested(0.9, atMs = 1_000L)

        assertEquals(0.9, reconciler.reconcile(0.3, atMs = 4_999L), 0.0001)
        assertEquals(0.3, reconciler.reconcile(0.3, atMs = 5_000L), 0.0001)
    }

    @Test
    fun `clearing makes inbound volume authoritative`() {
        val reconciler = RemoteVolumeReconciler()
        reconciler.requested(0.9, atMs = 1_000L)
        reconciler.requestedMuted(isMuted = true, atMs = 1_000L)

        reconciler.clear()

        assertEquals(0.3, reconciler.reconcile(0.3, atMs = 1_100L), 0.0001)
        assertEquals(false, reconciler.reconcileMuted(inbound = false, atMs = 1_100L))
    }

    @Test
    fun `requested mute survives a stale unmuted frame`() {
        val reconciler = RemoteVolumeReconciler()
        reconciler.requestedMuted(isMuted = true, atMs = 1_000L)

        assertEquals(true, reconciler.reconcileMuted(inbound = false, atMs = 1_100L))
        assertEquals(true, reconciler.reconcileMuted(inbound = true, atMs = 1_200L))
        assertEquals(false, reconciler.reconcileMuted(inbound = false, atMs = 1_300L))
    }

    @Test
    fun `mute reversal cannot be acknowledged by a pre-request snapshot`() {
        val reconciler = RemoteVolumeReconciler()
        reconciler.requestedMuted(isMuted = true, atMs = 1_000L)
        reconciler.requestedMuted(isMuted = false, atMs = 1_100L)

        assertEquals(false, reconciler.reconcileMuted(inbound = false, atMs = 1_150L))
        assertEquals(false, reconciler.reconcileMuted(inbound = true, atMs = 1_200L))
        assertEquals(false, reconciler.reconcileMuted(inbound = false, atMs = 1_250L))
        assertEquals(true, reconciler.reconcileMuted(inbound = true, atMs = 1_300L))
    }
}
