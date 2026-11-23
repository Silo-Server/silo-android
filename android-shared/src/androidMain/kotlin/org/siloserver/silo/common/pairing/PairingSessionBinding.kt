package org.siloserver.silo.common.pairing

import org.siloserver.silo.repository.DeviceLoginBinding
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The per-session secret a TV binds its device-login requests to (contract
 * §B.1): 32 random bytes, made when a phone connects and used for every
 * start and poll in that session on every HTTPS server that supports
 * binding. A plain-HTTP server never gets the binding or the secret, even
 * when it reports `session_binding`.
 */
internal object PairingSessionBinding {
    private const val SECRET_BYTES = 32
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(random: SecureRandom = SecureRandom()): DeviceLoginBinding =
        of(ByteArray(SECRET_BYTES).also(random::nextBytes))

    /** The binding for [secret]: base64url(SHA-256(secret)) and base64url(secret), both without padding. */
    fun of(secret: ByteArray): DeviceLoginBinding {
        require(secret.size == SECRET_BYTES) { "session secret must be $SECRET_BYTES bytes" }
        val hash = MessageDigest.getInstance("SHA-256").digest(secret)
        return DeviceLoginBinding(binding = encoder.encodeToString(hash), secret = encoder.encodeToString(secret))
    }
}
