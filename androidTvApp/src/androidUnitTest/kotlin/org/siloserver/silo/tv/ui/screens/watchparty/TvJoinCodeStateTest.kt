package org.siloserver.silo.tv.ui.screens.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvJoinCodeStateTest {
    @Test fun appendsUppercaseAndCapsAtEight() {
        var s = JoinCodeState(); "abcd2345ef".forEach { s = s.append(it) }
        assertEquals("ABCD2345", s.code); assertTrue(s.isComplete)
        assertEquals("ABCD2345", s.normalized)
    }
    @Test fun rejectsCharactersOutsideTheCodeAlphabet() {
        val s = JoinCodeState().append('-').append(' ').append('0').append('1').append('I').append('O').append('A')
        assertEquals("A", s.code); assertFalse(s.isComplete)
    }
    @Test fun backspaceRemovesLast() {
        assertEquals("A", JoinCodeState().append('A').append('B').backspace().code)
    }
    @Test fun clearEmpties() {
        assertEquals("", JoinCodeState().append('A').append('B').clear().code)
    }
    @Test fun incompleteUntilEight() {
        assertFalse(JoinCodeState().append('A').isComplete)
        assertNull(JoinCodeState().append('A').normalized)
        var t = JoinCodeState(); "ABCD2345".forEach { t = t.append(it) }; assertTrue(t.isComplete)
    }
}
