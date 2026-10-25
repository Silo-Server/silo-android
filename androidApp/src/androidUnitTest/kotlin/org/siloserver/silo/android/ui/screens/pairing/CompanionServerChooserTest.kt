package org.siloserver.silo.android.ui.screens.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import org.siloserver.silo.common.pairing.CompanionPairingServer

class CompanionServerChooserTest {
    private val servers = listOf(
        CompanionPairingServer(id = "home", url = "https://home.example", displayName = "Home", isActive = true),
        CompanionPairingServer(id = "cabin", url = "https://cabin.example", displayName = "Cabin"),
    )

    @Test
    fun aRokuChooserHoldsOneServer() {
        assertEquals(setOf("home"), companionInitialServerSelection(servers, singleSelect = true))
        assertEquals(setOf("cabin"), companionToggleServer(setOf("home"), "cabin", singleSelect = true))
        // Tapping the chosen server keeps it: a Roku always gets one.
        assertEquals(setOf("home"), companionToggleServer(setOf("home"), "home", singleSelect = true))
    }

    @Test
    fun otherTvsKeepTheMultiSelectChooser() {
        assertEquals(setOf("home"), companionInitialServerSelection(servers, singleSelect = false))
        assertEquals(setOf("home", "cabin"), companionToggleServer(setOf("home"), "cabin", singleSelect = false))
        assertEquals(emptySet(), companionToggleServer(setOf("home"), "home", singleSelect = false))
    }
}
