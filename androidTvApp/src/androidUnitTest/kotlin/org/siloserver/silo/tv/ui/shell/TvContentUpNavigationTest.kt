package org.siloserver.silo.tv.ui.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvContentUpNavigationTest {
    @Test
    fun heldUpAfterContentCannotMoveStaysInContent() {
        assertFalse(shouldRequestMenuAfterContentUp(movedWithinContent = false, isRepeat = true))
    }

    @Test
    fun freshUpAfterContentCannotMoveEntersMenu() {
        assertTrue(shouldRequestMenuAfterContentUp(movedWithinContent = false, isRepeat = false))
    }
}
