package org.siloserver.silo.common.pairing

import org.siloserver.silo.repository.DeviceLoginRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Narrow seam over [DeviceLoginRepository] for the pairing receiver, exposing
 * only what the receiver state machine needs. Lets the unit test substitute a
 * deterministic fake without a real [org.siloserver.silo.network.api.DeviceLoginApi].
 *
 * [DeviceLoginRepositoryPort] adapts the production repository.
 */
interface DeviceLoginPort {
    /** Observable device-login state machine. */
    val state: StateFlow<DeviceLoginRepository.DeviceLoginState>

    /** Begin a device-login session; suspends until a terminal state is reached. */
    suspend fun begin(serverUrl: String, deviceName: String?, devicePlatform: String?)

    /** Reset back to Idle for the next attempt. */
    fun reset()

    /**
     * Withdraw [deviceCode] at [serverUrl] so an abandoned code can't be
     * approved later. Best effort: a failure leaves it to expire.
     */
    suspend fun withdraw(serverUrl: String, deviceCode: String)
}

/** Production adapter delegating to the real [DeviceLoginRepository]. */
class DeviceLoginRepositoryPort(
    private val repository: DeviceLoginRepository,
) : DeviceLoginPort {
    override val state: StateFlow<DeviceLoginRepository.DeviceLoginState>
        get() = repository.state

    override suspend fun begin(serverUrl: String, deviceName: String?, devicePlatform: String?) =
        repository.beginAt(serverUrl, deviceName, devicePlatform)

    override fun reset() = repository.reset()

    // The shared withdraw rule (DeviceSignInMachine): skip only a server that
    // says it has no cancel; one whose capability can't be read still gets
    // it, and a server without the route answers 404, which is harmless.
    override suspend fun withdraw(serverUrl: String, deviceCode: String) {
        // A slow capability read counts as unknown, leaving time for the cancel.
        val capability = withTimeoutOrNull(CAPABILITY_TIMEOUT) { repository.capability(serverUrl) }
        if (capability?.cancel == false) return
        repository.cancel(serverUrl, deviceCode)
    }

    private companion object {
        val CAPABILITY_TIMEOUT = 2.seconds
    }
}
