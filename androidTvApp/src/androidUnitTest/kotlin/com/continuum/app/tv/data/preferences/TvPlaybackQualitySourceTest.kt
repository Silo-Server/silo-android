package com.continuum.app.tv.data.preferences

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvPlaybackQualitySourceTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/data/preferences/TvSettingsEnums.kt",
    ).readText()

    @Test
    fun tvPlaybackSettingsExpose4kAndNormalizeLegacy4kWireValue() {
        assertTrue(source.contains("P4K(\"4K\", \"2160p\")"))
        assertTrue(source.contains("\"4k\" -> P4K"))
    }
}
