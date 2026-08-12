package org.siloserver.silo.common.diagnostics

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream

internal data class DiagnosticsArchiveEntry(val name: String, val bytes: ByteArray)

internal data class EncodedDiagnosticsArchive(
    val bytes: ByteArray,
    val uncompressedBytes: Long,
    val sha256: String,
)

/** Deterministic USTAR/gzip mechanics, deliberately separate from privacy-policy sanitization. */
internal object DiagnosticsArchiveEncoder {
    fun encode(
        entries: List<DiagnosticsArchiveEntry>,
        canonicalHostedGzip: Boolean,
    ): EncodedDiagnosticsArchive {
        val tarBytes = writeUstar(entries)
        val gzipBytes = gzip(tarBytes, canonicalHostedGzip)
        return EncodedDiagnosticsArchive(
            bytes = gzipBytes,
            uncompressedBytes = tarBytes.size.toLong(),
            sha256 = sha256Hex(gzipBytes),
        )
    }

    private fun gzip(bytes: ByteArray, canonicalHostedGzip: Boolean): ByteArray =
        ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(bytes) }
            output.toByteArray().also { compressed ->
                if (canonicalHostedGzip) {
                    check(
                        compressed.size >= GZIP_HEADER_BYTES &&
                            compressed[0] == GZIP_MAGIC_ID1 &&
                            compressed[1] == GZIP_MAGIC_ID2,
                    ) { "hosted diagnostics gzip header is unavailable" }
                    compressed[GZIP_OS_OFFSET] = GZIP_CANONICAL_OS
                }
            }
        }

    private fun writeUstar(entries: List<DiagnosticsArchiveEntry>): ByteArray =
        ByteArrayOutputStream().use { output ->
            entries.forEach { entry ->
                val header = ustarHeader(entry)
                output.write(header)
                output.write(entry.bytes)
                val padding = (BLOCK_SIZE - entry.bytes.size % BLOCK_SIZE) % BLOCK_SIZE
                if (padding > 0) output.write(ByteArray(padding))
            }
            output.write(ByteArray(BLOCK_SIZE * 2))
            output.toByteArray()
        }

    private fun ustarHeader(entry: DiagnosticsArchiveEntry): ByteArray {
        val nameBytes = entry.name.encodeToByteArray()
        require(nameBytes.size <= NAME_BYTES) { "USTAR entry name is too long: ${entry.name}" }
        require(entry.bytes.size.toLong() <= MAX_ENTRY_BYTES) { "USTAR entry is too large: ${entry.name}" }
        val header = ByteArray(BLOCK_SIZE)
        nameBytes.copyInto(header, destinationOffset = 0)
        writeOctal(header, MODE_OFFSET, MODE_LENGTH, FILE_MODE)
        writeOctal(header, UID_OFFSET, UID_LENGTH, 0)
        writeOctal(header, GID_OFFSET, GID_LENGTH, 0)
        writeOctal(header, SIZE_OFFSET, SIZE_LENGTH, entry.bytes.size.toLong())
        writeOctal(header, MTIME_OFFSET, MTIME_LENGTH, 0)
        repeat(CHECKSUM_LENGTH) { header[CHECKSUM_OFFSET + it] = ' '.code.toByte() }
        header[TYPE_OFFSET] = REGULAR_FILE_TYPE
        USTAR_MAGIC.copyInto(header, destinationOffset = MAGIC_OFFSET)
        USTAR_VERSION.copyInto(header, destinationOffset = VERSION_OFFSET)
        writeChecksum(header, header.sumOf { it.toUByte().toLong() })
        return header
    }

    private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val encoded = value.toString(8).padStart(length - 1, '0').encodeToByteArray()
        require(encoded.size == length - 1) { "USTAR numeric field overflow" }
        encoded.copyInto(target, destinationOffset = offset)
        target[offset + length - 1] = 0
    }

    private fun writeChecksum(target: ByteArray, checksum: Long) {
        val encoded = checksum.toString(8).padStart(CHECKSUM_LENGTH - 2, '0').encodeToByteArray()
        require(encoded.size == CHECKSUM_LENGTH - 2) { "USTAR checksum overflow" }
        encoded.copyInto(target, destinationOffset = CHECKSUM_OFFSET)
        target[CHECKSUM_OFFSET + CHECKSUM_LENGTH - 2] = 0
        target[CHECKSUM_OFFSET + CHECKSUM_LENGTH - 1] = ' '.code.toByte()
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private const val BLOCK_SIZE = 512
    private const val NAME_BYTES = 100
    private const val MODE_OFFSET = 100
    private const val MODE_LENGTH = 8
    private const val UID_OFFSET = 108
    private const val UID_LENGTH = 8
    private const val GID_OFFSET = 116
    private const val GID_LENGTH = 8
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val MTIME_OFFSET = 136
    private const val MTIME_LENGTH = 12
    private const val CHECKSUM_OFFSET = 148
    private const val CHECKSUM_LENGTH = 8
    private const val TYPE_OFFSET = 156
    private const val MAGIC_OFFSET = 257
    private const val VERSION_OFFSET = 263
    private const val FILE_MODE = 420L
    private const val REGULAR_FILE_TYPE = '0'.code.toByte()
    private const val MAX_ENTRY_BYTES = 8_589_934_591L
    private const val GZIP_HEADER_BYTES = 10
    private const val GZIP_OS_OFFSET = 9
    private const val GZIP_MAGIC_ID1: Byte = 0x1f
    private const val GZIP_MAGIC_ID2: Byte = -0x75
    private const val GZIP_CANONICAL_OS: Byte = 0
    private val USTAR_MAGIC = byteArrayOf('u'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), 0)
    private val USTAR_VERSION = byteArrayOf('0'.code.toByte(), '0'.code.toByte())
}
