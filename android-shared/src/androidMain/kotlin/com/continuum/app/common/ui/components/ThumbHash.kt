package com.continuum.app.common.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decoder for ThumbHash — a very compact (~25 byte) image placeholder format
 * (https://evanw.github.io/thumbhash/). Produces a tiny blurred preview bitmap
 * suitable as an instant placeholder under a loading network image.
 *
 * Faithful port of the reference `thumbHashToRGBA` algorithm. Pure and local
 * (no I/O); callers wrap decoding in a try/catch so a malformed hash simply
 * yields a neutral placeholder instead of throwing.
 */
internal object ThumbHash {

    /** Decode [hash] into a small [ImageBitmap], or null if the bytes are invalid. */
    fun toImageBitmap(hash: ByteArray): ImageBitmap? = runCatching { decode(hash) }.getOrNull()

    private fun decode(hash: ByteArray): ImageBitmap {
        fun byteAt(i: Int): Int = hash[i].toInt() and 0xFF

        // Header.
        val header24 = byteAt(0) or (byteAt(1) shl 8) or (byteAt(2) shl 16)
        val header16 = byteAt(3) or (byteAt(4) shl 8)
        val lDc = (header24 and 63) / 63f
        val pDc = ((header24 shr 6) and 63) / 31.5f - 1f
        val qDc = ((header24 shr 12) and 63) / 31.5f - 1f
        val lScale = ((header24 shr 18) and 31) / 31f
        val hasAlpha = (header24 shr 23) != 0
        val lCount = header16 and 7
        val pScale = ((header16 shr 3) and 63) / 63f
        val qScale = ((header16 shr 9) and 63) / 63f
        val isLandscape = (header16 shr 15) != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else lCount)
        val ly = max(3, if (isLandscape) lCount else (if (hasAlpha) 5 else 7))
        val aDc = if (hasAlpha) (byteAt(5) and 15) / 15f else 1f
        val aScale = (byteAt(5) shr 4) / 15f

        // AC terms, read as a shared 4-bit nibble stream across all channels.
        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        fun decodeChannel(nx: Int, ny: Int, scale: Float): FloatArray {
            val ac = ArrayList<Float>()
            for (cy in 0 until ny) {
                var cx = if (cy != 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    val nibble = (byteAt(acStart + (acIndex shr 1)) shr ((acIndex and 1) shl 2)) and 15
                    ac.add((nibble / 7.5f - 1f) * scale)
                    acIndex++
                    cx++
                }
            }
            return ac.toFloatArray()
        }
        val lAc = decodeChannel(lx, ly, lScale)
        val pAc = decodeChannel(3, 3, pScale * 1.25f)
        val qAc = decodeChannel(3, 3, qScale * 1.25f)
        val aAc = if (hasAlpha) decodeChannel(5, 5, aScale) else FloatArray(0)

        // Output dimensions from the approximate aspect ratio (max edge 32px).
        val ratio = lx.toFloat() / ly.toFloat()
        val w = (if (ratio > 1f) 32f else 32f * ratio).roundToInt().coerceAtLeast(1)
        val h = (if (ratio > 1f) 32f / ratio else 32f).roundToInt().coerceAtLeast(1)

        val pixels = IntArray(w * h)
        val fx = FloatArray(max(lx, if (hasAlpha) 5 else 3))
        val fy = FloatArray(max(ly, if (hasAlpha) 5 else 3))
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                var l = lDc
                var p = pDc
                var q = qDc
                var a = aDc
                for (cx in fx.indices) fx[cx] = cos(PI.toFloat() / w * (x + 0.5f) * cx)
                for (cy in fy.indices) fy[cy] = cos(PI.toFloat() / h * (y + 0.5f) * cy)

                // Luminance.
                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy != 0) 0 else 1
                    val fy2 = fy[cy] * 2f
                    while (cx * ly < lx * (ly - cy)) {
                        l += lAc[j] * fx[cx] * fy2
                        j++
                        cx++
                    }
                }
                // Chrominance (P and Q share the 3x3 basis).
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy != 0) 0 else 1
                    val fy2 = fy[cy] * 2f
                    while (cx < 3 - cy) {
                        val f = fx[cx] * fy2
                        p += pAc[j] * f
                        q += qAc[j] * f
                        j++
                        cx++
                    }
                }
                // Alpha.
                if (hasAlpha) {
                    j = 0
                    for (cy in 0 until 5) {
                        var cx = if (cy != 0) 0 else 1
                        val fy2 = fy[cy] * 2f
                        while (cx < 5 - cy) {
                            a += aAc[j] * fx[cx] * fy2
                            j++
                            cx++
                        }
                    }
                }

                // YPbPr-ish -> RGB.
                val bCh = l - 2f / 3f * p
                val rCh = (3f * l - bCh + q) / 2f
                val gCh = rCh - q
                val r = (255f * min(1f, max(0f, rCh))).roundToInt()
                val g = (255f * min(1f, max(0f, gCh))).roundToInt()
                val b = (255f * min(1f, max(0f, bCh))).roundToInt()
                val al = (255f * min(1f, max(0f, a))).roundToInt()
                pixels[i] = (al shl 24) or (r shl 16) or (g shl 8) or b
                i++
            }
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap.asImageBitmap()
    }
}
