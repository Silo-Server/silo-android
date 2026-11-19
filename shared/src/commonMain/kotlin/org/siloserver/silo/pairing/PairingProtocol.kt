package org.siloserver.silo.pairing

/**
 * Constants for the companion-pairing LAN protocol. Platform-neutral so this
 * mirrors the silo-apple implementation byte-for-byte (Android NSD + sockets
 * layer is built on top of this).
 */
object PairingProtocol {
    /** Wire protocol version. Bump on any breaking change to message shapes. */
    const val VERSION = 1

    /** Bonjour/NSD service type the TV advertises and the phone browses for. */
    const val SERVICE_TYPE = "_silopair._tcp"

    /**
     * The Roku's pairing service (silo-roku `docs/companion-pairing.md`). Roku
     * has no TLS on a listening socket, so it speaks the same messages over
     * plain TCP; the separate type keeps it off the TLS path.
     */
    const val ROKU_SERVICE_TYPE = "_silopair-roku._tcp"

    /**
     * TXT `acct=1`: the TV accepts any number of `pushServer` messages in one
     * session (the same server again included) and keeps each sign-in as its
     * own account, matched by the poll's `tokens.user.id`.
     */
    const val TXT_ACCEPTS_ACCOUNTS = "acct"

    /** TXT `lim=<n>` (optional, Roku): the most sign-ins the TV can keep from one session. */
    const val TXT_SIGN_IN_LIMIT = "lim"
}
