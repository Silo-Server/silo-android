package org.siloserver.silo.model.feature

/**
 * Cross-cutting account-lifecycle events the diagnostics subsystem must react
 * to immediately (its consent contract requires purging a binding's persisted
 * state at sign-out / server removal, not on the next lazy refresh).
 *
 * Defined in commonMain so [org.siloserver.silo.repository.AuthRepository] and
 * the server-management screens can notify without seeing the Android-only
 * coordinator; bound via Koin by the diagnostics module, `getOrNull()` on the
 * consuming side so platforms without diagnostics pay nothing.
 */
interface DiagnosticsAccountEvents {
    fun onSignedOut(localServerId: String?)
    fun onServerRemoved(localServerId: String)
}
