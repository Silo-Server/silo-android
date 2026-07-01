package com.continuum.app.tv.ui.theme

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvLauncherIconAssetsTest {

    @Test
    fun adaptiveForegroundUsesCenteredSquareTileInsteadOfOpaqueFullBleedField() {
        val foreground = ImageIO.read(
            File("src/androidMain/res/mipmap-xxxhdpi/ic_launcher_foreground.png"),
        )

        assertEquals(432, foreground.width)
        assertEquals(432, foreground.height)
        assertTrue(
            foreground.colorModel.hasAlpha(),
            "Adaptive foreground must keep transparent corners so the launcher mask reveals a square tile, not a full-bleed circle.",
        )
        assertEquals(0, foreground.alphaAt(0, 0), "Top-left corner should be transparent.")
        assertEquals(0, foreground.alphaAt(foreground.width - 1, 0), "Top-right corner should be transparent.")
        assertEquals(0, foreground.alphaAt(0, foreground.height - 1), "Bottom-left corner should be transparent.")
        assertEquals(0, foreground.alphaAt(foreground.width - 1, foreground.height - 1), "Bottom-right corner should be transparent.")
        assertTrue(
            foreground.alphaAt(foreground.width / 2, foreground.height / 2) > 240,
            "The centered Silo tile should remain opaque.",
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
}

private fun java.awt.image.BufferedImage.alphaAt(x: Int, y: Int): Int =
    if (colorModel.hasAlpha()) {
        (getRGB(x, y) ushr 24) and 0xff
    } else {
        255
    }
