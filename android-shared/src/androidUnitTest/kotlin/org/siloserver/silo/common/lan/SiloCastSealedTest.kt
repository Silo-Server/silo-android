package org.siloserver.silo.common.lan

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Level `sealed` against the vectors silo-roku shares in
 * `tests/control/sealed.spec.bs` (generated with Node's crypto, as its
 * `scripts/silocontrol-phone.mjs` implements the phone's side).
 */
class SiloCastSealedTest {
    private val key = hex("000102030405060708090a0b0c0d0e0f")
    private val phoneNonce = hex("101112131415161718191a1b1c1d1e1f")
    private val tvNonce = hex("202122232425262728292a2b2c2d2e2f")
    private val keys = SiloCastSealed.sessionKeys(key, phoneNonce, tvNonce)

    @Test
    fun namesTheKeyAndDerivesTheSessionKeys() {
        assertEquals("be45cb26", SiloCastSealed.keyId(key))
        assertEquals("239a09219f1c09a91e710d3c90e16a4d", keys.encKey.toHex())
        assertEquals("066768a42738a935b7ff4148306d0aaf4650844fbfa39191e41e9cdfea22fafe", keys.macKey.toHex())
        assertEquals("3816333626975d56fe75bbe953ab0834", keys.ivKey.toHex())
    }

    @Test
    fun readsAndWritesThePairingKeyInBase32() {
        assertEquals("AAAQEAYEAUDAOCAJBIFQYDIOB4", SiloCastSealed.base32(key))
        assertContentEquals(key, SiloCastSealed.parseKey("AAAQEAYEAUDAOCAJBIFQYDIOB4"))
        // As the Roku shows it: groups of four.
        assertContentEquals(key, SiloCastSealed.parseKey("aaaq eaye auda ocaj bifq ydio b4"))
        assertContentEquals(key, SiloCastSealed.parseKey("000102030405060708090a0b0c0d0e0f"))
        assertNull(SiloCastSealed.parseKey("AAAQEAYEAUDAOCAJBIFQYDIO"))
        assertNull(SiloCastSealed.parseKey("AAAQEAYEAUDAOCAJBIFQYDIOB41"))
        assertNull(SiloCastSealed.parseKey("AAAQEAYEAUDAOCAJBIFQYDIOB!"))
    }

    @Test
    fun readsAPeersSealedHello() {
        val read = assertNotNull(
            SiloCastSealed.readHello("""{"type":"sealed_hello","v":1,"keyId":"BE45CB26","nonce":"EBESExQVFhcYGRobHB0eHw"}"""),
        )
        assertEquals("be45cb26", read.keyId)
        assertContentEquals(phoneNonce, read.nonce)
        assertNull(SiloCastSealed.readHello("""{"type":"sealed_hello","v":1,"keyId":"x","nonce":"AAAA"}"""))
        assertNull(SiloCastSealed.readHello("""{"type":"hello"}"""))
        assertNull(SiloCastSealed.readHello("""{"type":"close","v":2,"reason":"unauthorized"}"""))
        assertNull(SiloCastSealed.readHello("not json"))

        val hello = assertNotNull(SiloCastSealed.readHello(SiloCastSealed.helloText("be45cb26", phoneNonce)))
        assertEquals("be45cb26", hello.keyId)
        assertContentEquals(phoneNonce, hello.nonce)
        assert("\"nonce\":\"EBESExQVFhcYGRobHB0eHw\"" in SiloCastSealed.helloText("be45cb26", phoneNonce))
    }

    @Test
    fun sealsAsTheRokuDoesGivenItsIv() {
        val expected = "0000000000000000e3c9c8e329cba6d9317f6e0442a21dd98277e1fd6de898e0832b3578392e71044f28b35fe6b56267e20d49e56e19cf095275faead9fe27b5d6cd3e7cba162cf9d3e5dead6001a4eb0e596a8ac45b2011"
        // The Roku's IV: AES-128-ECB(ivKey, direction || seq, zero-padded).
        val iv = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.ivKey, "AES"))
            doFinal(ByteArray(16).also { it[0] = SiloCastSealed.FROM_TV })
        }
        assertEquals(expected.substring(16, 48), iv.toHex())
        val body = SiloCastSealed.seal(keys, SiloCastSealed.FROM_TV, 0, """{"type":"ping","v":2}""".encodeToByteArray(), iv)
        assertEquals(expected, body.toHex())
        assertEquals("""{"type":"ping","v":2}""", SiloCastSealed.open(keys, SiloCastSealed.FROM_TV, 0, body)?.decodeToString())
    }

    @Test
    fun opensAPhoneFrameWithItsOwnIv() {
        val body = hex(
            "0000000000000001ffeeddccbbaa99887766554433221100d6cffafe6c10c438a7bb4a4418b0e3e7e1ba097b39d777647df0c04ad0d79684d56990d6c483611bdfc4acf0b7d16c06ff0c50c169f9739cd58a17445e0daeec",
        )
        assertEquals("""{"type":"pong","v":2}""", SiloCastSealed.open(keys, SiloCastSealed.FROM_PHONE, 1, body)?.decodeToString())
        // The same frame, sealed here with the vector's IV.
        val iv = hex("ffeeddccbbaa99887766554433221100")
        assertContentEquals(body, SiloCastSealed.seal(keys, SiloCastSealed.FROM_PHONE, 1, """{"type":"pong","v":2}""".encodeToByteArray(), iv))
    }

    @Test
    fun refusesATamperedReplayedReorderedOrMisdirectedFrame() {
        val body = SiloCastSealed.seal(keys, SiloCastSealed.FROM_PHONE, 3, """{"type":"ping","v":2}""".encodeToByteArray())
        assertNotNull(SiloCastSealed.open(keys, SiloCastSealed.FROM_PHONE, 3, body))
        assertNull(SiloCastSealed.open(keys, SiloCastSealed.FROM_PHONE, 4, body), "replayed")
        assertNull(SiloCastSealed.open(keys, SiloCastSealed.FROM_PHONE, 2, body), "reordered")
        assertNull(SiloCastSealed.open(keys, SiloCastSealed.FROM_TV, 3, body), "the other direction")
        val tampered = body.copyOf().also { it[30] = (it[30] + 1).toByte() }
        assertNull(SiloCastSealed.open(keys, SiloCastSealed.FROM_PHONE, 3, tampered), "tampered")
        val other = SiloCastSealed.sessionKeys(hex("0f0e0d0c0b0a09080706050403020100"), phoneNonce, tvNonce)
        assertNull(SiloCastSealed.open(other, SiloCastSealed.FROM_PHONE, 3, body), "another key")
        assertNull(SiloCastSealed.open(keys, SiloCastSealed.FROM_PHONE, 3, hex("00")), "short")
    }

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
