package org.siloserver.silo.android.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SubtitleAspectSyncWiringTest {
    @Test
    fun phoneSubtitleManagerUsesPhonePresentation() {
        val source = source("org/siloserver/silo/android/di/AndroidModule.kt")

        assertTrue(source.contains("SubtitleManager(\n            libassBridge = get(),\n            presentation = AndroidSubtitlePresentation.Phone,"))
    }

    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }
}
