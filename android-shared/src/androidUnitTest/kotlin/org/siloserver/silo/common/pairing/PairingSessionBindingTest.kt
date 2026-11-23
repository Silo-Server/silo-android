package org.siloserver.silo.common.pairing

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingSessionBindingTest {
    /** Contract §A.2/§A.4: both values are 43-character base64url without padding; the binding is SHA-256 of the secret. */
    @Test
    fun bindingIsTheUnpaddedBase64UrlSha256OfTheSecret() {
        val secret = ByteArray(32) { it.toByte() }
        val bound = PairingSessionBinding.of(secret)
        val format = Regex("^[A-Za-z0-9_-]{43}$")
        assertTrue(format.matches(bound.binding), bound.binding)
        assertTrue(format.matches(bound.secret), bound.secret)
        assertEquals(secret.toList(), Base64.getUrlDecoder().decode(bound.secret).toList())
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(secret))
        assertEquals(expected, bound.binding)
    }

    @Test
    fun eachSessionGetsItsOwnSecret() {
        assertNotEquals(PairingSessionBinding.generate().secret, PairingSessionBinding.generate().secret)
    }
}
