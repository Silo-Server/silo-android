package org.siloserver.silo.android.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomDeliveryKeySourceTest {
    private val reportingStartAnchor = "// Drift reporting loop"
    private val reportingEndAnchor = "// ready / buffering during the waiting barrier."

    private val controllerSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/RoomSyncController.kt",
    ).readText()

    @Test
    fun stateReportRequiresAnExplicitNonNullDeliveryKey() {
        val reportingBlock = reportingBlock(controllerSource)
        val guardIndex = reportingBlock.indexOf("deliveryKey != null")
        val stateReportIndex = reportingBlock.indexOf("repository.stateReport(")

        assertFalse(reportingBlock.contains("deliveryKey!!"))
        assertTrue(guardIndex >= 0, "Drift reporting block must guard deliveryKey explicitly.")
        assertTrue(stateReportIndex >= 0, "Drift reporting block must report state.")
        assertTrue(guardIndex < stateReportIndex)
    }

    @Test
    fun reportingBlockFailsClosedWhenEitherAnchorIsMissing() {
        assertFailsWith<AssertionError> {
            reportingBlock(controllerSource.replace(reportingStartAnchor, ""))
        }
        assertFailsWith<AssertionError> {
            reportingBlock(controllerSource.replace(reportingEndAnchor, ""))
        }
    }

    private fun reportingBlock(source: String): String {
        val startAnchorIndex = source.indexOf(reportingStartAnchor)
        assertTrue(startAnchorIndex >= 0, "Missing drift-reporting start anchor.")
        val blockStart = startAnchorIndex + reportingStartAnchor.length
        val endAnchorIndex = source.indexOf(reportingEndAnchor, startIndex = blockStart)
        assertTrue(endAnchorIndex >= blockStart, "Missing drift-reporting end anchor.")
        return source.substring(blockStart, endAnchorIndex)
    }
}
