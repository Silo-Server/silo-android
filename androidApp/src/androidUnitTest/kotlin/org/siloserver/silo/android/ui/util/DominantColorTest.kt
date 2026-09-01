package org.siloserver.silo.android.ui.util

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DominantColorTest {
    @Test
    fun `valid thumbhash provides an opaque normalized tint`() {
        val tint = assertNotNull(
            averageTintFromThumbhash("1QcSHQRnh493V4dIh4eXh1h4kJUI"),
        )

        assertTrue(tint.alpha == 1f)
        assertTrue(tint.red in 0f..1f)
        assertTrue(tint.green in 0f..1f)
        assertTrue(tint.blue in 0f..1f)
    }

    @Test
    fun `missing or malformed thumbhash falls back safely`() {
        assertNull(averageTintFromThumbhash(null))
        assertNull(averageTintFromThumbhash("not a thumbhash"))
    }
}
