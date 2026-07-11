package org.siloserver.silo.common.pairing

import org.siloserver.silo.repository.DeviceLoginRepository
import kotlinx.coroutines.flow.StateFlow

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
}
