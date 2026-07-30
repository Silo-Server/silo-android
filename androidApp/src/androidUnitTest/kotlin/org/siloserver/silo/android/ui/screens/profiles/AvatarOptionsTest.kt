package org.siloserver.silo.android.ui.screens.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarOptionsTest {
    @Test
    fun builtInAvatarsUseServerAcceptedDiceBearReferences() {
        assertEquals(20, AvatarOptions.presets.distinct().size)
        AvatarOptions.presets.forEach { avatarRef ->
            val parts = avatarRef.split(':', limit = 4)
            assertEquals(listOf("preset", "dicebear", "fun-emoji"), parts.take(3))
            assertTrue(parts[3].matches(Regex("[A-Za-z0-9-]{1,64}")))
        }
    }
}
