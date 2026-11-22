package org.siloserver.silo.common.lan

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Level `sealed` of the Roku receiver's control channel (silo-roku
 * `docs/silocontrol.md` section 9.2). Roku sockets have no TLS, so a Roku
 * that requires pairing seals each frame itself with keys derived from a
 * 16-byte pairing key the phone scanned from the Roku's Connect a Phone
 * screen.
 *
 * Each side opens with one plain `sealed_hello` frame carrying the key ID and
 * a nonce; HKDF-SHA256 over both nonces derives the session keys, and every
 * later frame body is `seq || iv || AES-128-CBC ciphertext || HMAC-SHA256 tag`.
 * Silo-roku's `tests/control/sealed.spec.bs` vectors pin the construction.
 */
@OptIn(ExperimentalEncodingApi::class)
object SiloCastSealed {
    const val FROM_PHONE: Byte = 0x01
    const val FROM_TV: Byte = 0x02
    const val KEY_BYTES = 16
    const val NONCE_BYTES = 16
    const val HELLO_TYPE = "sealed_hello"

    private const val LABEL = "silo-cast-sealed-1"
    private const val SEQ_BYTES = 8
    private const val IV_BYTES = 16
    private const val TAG_BYTES = 32
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    class SessionKeys(val encKey: ByteArray, val macKey: ByteArray, val ivKey: ByteArray)

    /** A peer's `sealed_hello`. */
    class Hello(val keyId: String, val nonce: ByteArray)

    /** The first 8 hex digits of SHA-256 of [key], as TXT `kid` and `sealed_hello.keyId` carry it. */
    fun keyId(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).copyOf(4).joinToString("") { "%02x".format(it) }

    fun sessionKeys(key: ByteArray, phoneNonce: ByteArray, tvNonce: ByteArray): SessionKeys {
        val prk = hmac(phoneNonce + tvNonce, key)
        // HKDF-SHA256 expand with one output block each.
        fun expand(purpose: String) = hmac(prk, "$LABEL $purpose".encodeToByteArray() + byteArrayOf(1))
        return SessionKeys(
            encKey = expand("enc").copyOf(16),
            macKey = expand("mac"),
            ivKey = expand("iv").copyOf(16),
        )
    }

    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    /** Seals one frame body. The phone may use any unpredictable IV; tests pass a fixed one. */
    fun seal(
        keys: SessionKeys,
        direction: Byte,
        seq: Long,
        plaintext: ByteArray,
        iv: ByteArray = ByteArray(IV_BYTES).also(random::nextBytes),
    ): ByteArray {
        require(iv.size == IV_BYTES)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), IvParameterSpec(iv))
        val signed = seqBytes(seq) + iv + cipher.doFinal(plaintext)
        return signed + tag(keys, direction, signed)
    }

    /**
     * Opens a frame body sealed in [direction] with sequence number [expectedSeq],
     * checking the tag before decrypting. Null for any failure: a tampered,
     * replayed, reordered, misdirected, or short frame.
     */
    fun open(keys: SessionKeys, direction: Byte, expectedSeq: Long, body: ByteArray): ByteArray? {
        if (body.size < SEQ_BYTES + IV_BYTES + IV_BYTES + TAG_BYTES) return null
        val signed = body.copyOfRange(0, body.size - TAG_BYTES)
        if (!MessageDigest.isEqual(tag(keys, direction, signed), body.copyOfRange(body.size - TAG_BYTES, body.size))) return null
        if (!signed.copyOfRange(0, SEQ_BYTES).contentEquals(seqBytes(expectedSeq))) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keys.encKey, "AES"),
                IvParameterSpec(signed.copyOfRange(SEQ_BYTES, SEQ_BYTES + IV_BYTES)),
            )
            cipher.doFinal(signed, SEQ_BYTES + IV_BYTES, signed.size - SEQ_BYTES - IV_BYTES)
        }.getOrNull()
    }

    fun helloText(keyId: String, nonce: ByteArray): String =
        buildJsonObject {
            put("type", HELLO_TYPE)
            put("v", 1)
            put("keyId", keyId)
            put("nonce", base64Url.encode(nonce))
        }.toString()

    /** A peer's `sealed_hello`, or null when [text] is anything else or malformed. */
    fun readHello(text: String): Hello? {
        val frame = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun string(key: String) = (frame[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (string("type") != HELLO_TYPE) return null
        val keyId = string("keyId")?.lowercase()?.takeIf { KEY_ID.matches(it) } ?: return null
        val nonce = string("nonce")?.let { runCatching { base64Url.decode(it) }.getOrNull() } ?: return null
        if (nonce.size != NONCE_BYTES) return null
        return Hello(keyId, nonce)
    }

    /**
     * A pairing key as the pairing link (`key=`, RFC 4648 base32 without
     * padding) or the Roku's screen (the same, in groups of four) writes it;
     * also 32 hex digits, as the development controller takes it. Null unless
     * it is exactly 16 bytes.
     */
    fun parseKey(text: String): ByteArray? {
        val cleaned = text.filterNot { it.isWhitespace() || it == '-' || it == '=' }
        if (cleaned.length == KEY_BYTES * 2 && cleaned.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        var bits = 0
        var value = 0
        val out = ArrayList<Byte>(KEY_BYTES)
        for (char in cleaned.uppercase()) {
            val digit = BASE32.indexOf(char)
            if (digit < 0) return null
            value = (value shl 5) or digit
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += ((value shr bits) and 0xff).toByte()
            }
        }
        return out.toByteArray().takeIf { it.size == KEY_BYTES && bits < 5 }
    }

    /** [key] in base32 without padding, the pairing link's form. */
    fun base32(key: ByteArray): String {
        val out = StringBuilder()
        var bits = 0
        var value = 0
        for (byte in key) {
            value = (value shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(BASE32[(value shr bits) and 31])
            }
        }
        if (bits > 0) out.append(BASE32[(value shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun tag(keys: SessionKeys, direction: Byte, signed: ByteArray): ByteArray =
        hmac(keys.macKey, LABEL.encodeToByteArray() + byteArrayOf(direction) + signed)

    private fun seqBytes(seq: Long): ByteArray = ByteArray(SEQ_BYTES) { i -> (seq ushr (8 * (SEQ_BYTES - 1 - i))).toByte() }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    private val KEY_ID = Regex("[0-9a-f]{8}")
}
