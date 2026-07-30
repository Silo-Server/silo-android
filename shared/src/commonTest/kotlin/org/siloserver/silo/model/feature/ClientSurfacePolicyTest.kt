package org.siloserver.silo.model.feature

import kotlin.test.Test
import kotlin.test.assertFalse

class ClientSurfacePolicyTest {
    @Test
    fun watchTogetherCodeStaysPresentButHiddenFromUserMenus() {
        assertFalse(CLIENT_WATCH_TOGETHER_SURFACE_ENABLED)
    }
}
