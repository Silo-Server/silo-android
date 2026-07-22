package org.siloserver.silo.common.diagnostics.bundle

import java.io.OutputStream

/**
 * Minimal plain-USTAR tar writer.
 *
 * Hand-rolled deliberately: the server (`internal/diagnostics/bundle.go`)
 * rejects PAX and GNU tar formats outright, and most JVM tar libraries emit
 * PAX extensions by default. Our entry names are short ASCII from a fixed
 * allowlist, so the 100-byte USTAR name field always suffices. Header layout
 * is the POSIX ustar standard (magic `ustar\0`, version `00`), which Go's
 * `archive/tar` recognizes as `FormatUSTAR`.
 *
 * The declared header size and the bytes written come from the same value in
 * the same function — there is no code path where they can drift.
 */
internal object UstarTarWriter {

    fun writeEntry(out: OutputStream, name: String, data: ByteArray) {
        val nameBytes = name.encodeToByteArray()
        require(nameBytes.size in 1..100) { "tar entry name must be 1..100 bytes: $name" }

        val header = ByteArray(512)
        nameBytes.copyInto(header, 0)
        writeOctal(header, 100, 8, 0b110_100_100L) // mode 0644
        writeOctal(header, 108, 8, 0) // uid
        writeOctal(header, 116, 8, 0) // gid
        writeOctal(header, 124, 12, data.size.toLong()) // size
        writeOctal(header, 136, 12, 0) // mtime (0 = deterministic; not inspected server-side)
        // chksum field is all spaces while computing the checksum.
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = '0'.code.toByte() // typeflag: regular file
        "ustar".encodeToByteArray().copyInto(header, 257) // magic (+ NUL already zero at 262)
        header[263] = '0'.code.toByte() // version "00"
        header[264] = '0'.code.toByte()

        var checksum = 0L
        for (b in header) checksum += b.toInt() and 0xFF
        // Standard checksum encoding: 6 octal digits, NUL, space.
        val chk = checksum.toString(8).padStart(6, '0')
        for (i in chk.indices) header[148 + i] = chk[i].code.toByte()
        header[154] = 0
        header[155] = ' '.code.toByte()

        out.write(header)
        out.write(data)
        val padding = (512 - (data.size % 512)) % 512
        if (padding > 0) out.write(ByteArray(padding))
    }

    /**
     * The POSIX end-of-archive marker: two 512-byte zero blocks. The server
     * tolerates up to 64 KiB of additional zero padding but requires none —
     * the minimum keeps `uncompressed_bytes` trivially predictable.
     */
    fun writeEndOfArchive(out: OutputStream) {
        out.write(ByteArray(1024))
    }

    /** Writes `(length-1)` zero-padded octal digits + NUL at [offset]. */
    private fun writeOctal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        require(text.length == length - 1) { "octal value too large for field" }
        for (i in text.indices) header[offset + i] = text[i].code.toByte()
        header[offset + length - 1] = 0
    }
}

/** Counts bytes passed through; wraps the exact stream the tar writer writes into. */
internal class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
