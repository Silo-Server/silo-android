package org.siloserver.silo.control

/**
 * Constants for the SiloControl LAN remote-control protocol (phone controls a
 * TV playing content). Platform-neutral so this mirrors the silo-apple
 * `SiloControlProtocol` byte-for-byte (Android NSD + sockets layer is built on
 * top of this).
 */
object SiloControlProtocol {
    /** Wire protocol version. Bump on any breaking change to message shapes. */
    const val VERSION = 1

    /** Bonjour/NSD service type the TV advertises and the phone browses for. */
    const val SERVICE_TYPE = "_silocast._tcp"
}
