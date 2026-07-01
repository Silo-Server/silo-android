package com.continuum.app.tv.ui.theme

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvLauncherIconAssetsTest {

    @Test
    fun adaptiveForegroundUsesMarkOnlySafeZoneInsteadOfBakedSquareTile() {
        val foreground = ImageIO.read(
            File("src/androidMain/res/mipmap-xxxhdpi/ic_launcher_foreground.png"),
        )

        assertEquals(432, foreground.width)
        assertEquals(432, foreground.height)
        assertTrue(
            foreground.colorModel.hasAlpha(),
            "Adaptive foreground must use transparency so Google TV can own the launcher mask.",
        )
        assertEquals(0, foreground.alphaAt(0, 0), "Top-left corner should be transparent.")
        assertEquals(0, foreground.alphaAt(foreground.width - 1, 0), "Top-right corner should be transparent.")
        assertEquals(0, foreground.alphaAt(0, foreground.height - 1), "Bottom-left corner should be transparent.")
        assertEquals(0, foreground.alphaAt(foreground.width - 1, foreground.height - 1), "Bottom-right corner should be transparent.")
        assertEquals(0, foreground.alphaAt(foreground.width / 2, 0), "Top edge should be transparent.")
        assertEquals(0, foreground.alphaAt(foreground.width / 2, foreground.height - 1), "Bottom edge should be transparent.")
        assertEquals(0, foreground.alphaAt(0, foreground.height / 2), "Left edge should be transparent.")
        assertEquals(0, foreground.alphaAt(foreground.width - 1, foreground.height / 2), "Right edge should be transparent.")
        assertEquals(
            0,
            foreground.alphaAt(foreground.width / 2, foreground.height / 2),
            "The adaptive foreground should be mark-only, not a centered opaque square tile.",
        )

        val bounds = foreground.opaqueBounds()
        assertTrue(
            bounds.minX in 170..185 && bounds.maxX in 245..265,
            "Opaque mark should stay centered horizontally inside the adaptive safe zone: $bounds",
        )
        assertTrue(
            bounds.minY in 80..100 && bounds.maxY in 330..355,
            "Opaque mark should stay vertically inset inside the adaptive safe zone: $bounds",
        )
        assertTrue(
            bounds.width in 70..100 && bounds.height in 240..275,
            "Adaptive foreground should contain a tall Silo mark, not a full tile: $bounds",
        )
    }

    @Test
    fun legacyTvLauncherIconsKeepAndroidDensityDimensions() {
        val expected = mapOf(
            "mipmap-mdpi/ic_launcher.png" to 48,
            "mipmap-hdpi/ic_launcher.png" to 72,
            "mipmap-xhdpi/ic_launcher.png" to 96,
            "mipmap-xxhdpi/ic_launcher.png" to 144,
            "mipmap-xxxhdpi/ic_launcher.png" to 192,
        )

        expected.forEach { (path, size) ->
            val image = ImageIO.read(File("src/androidMain/res/$path"))
            assertEquals(size, image.width, path)
            assertEquals(size, image.height, path)
        }
    }

    @Test
    fun legacyTvLauncherIconIsCircleFriendlyForGoogleTvRows() {
        val icon = ImageIO.read(
            File("src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png"),
        )

        assertEquals(192, icon.width)
        assertEquals(192, icon.height)
        assertTrue(icon.colorModel.hasAlpha(), "Legacy TV launcher icon should keep transparent corners.")
        assertEquals(0, icon.alphaAt(0, 0), "Top-left corner should be transparent.")
        assertEquals(0, icon.alphaAt(icon.width - 1, 0), "Top-right corner should be transparent.")
        assertEquals(0, icon.alphaAt(0, icon.height - 1), "Bottom-left corner should be transparent.")
        assertEquals(0, icon.alphaAt(icon.width - 1, icon.height - 1), "Bottom-right corner should be transparent.")
        assertTrue(icon.alphaAt(icon.width / 2, icon.height / 2) > 240, "Center blue field should remain opaque.")
    }
}

private fun java.awt.image.BufferedImage.alphaAt(x: Int, y: Int): Int =
    if (colorModel.hasAlpha()) {
        (getRGB(x, y) ushr 24) and 0xff
    } else {
        255
    }

private data class AlphaBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    val width: Int = maxX - minX + 1
    val height: Int = maxY - minY + 1
}

private fun java.awt.image.BufferedImage.opaqueBounds(): AlphaBounds {
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (alphaAt(x, y) > 16) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
    }
    return AlphaBounds(minX = minX, minY = minY, maxX = maxX, maxY = maxY)
}
