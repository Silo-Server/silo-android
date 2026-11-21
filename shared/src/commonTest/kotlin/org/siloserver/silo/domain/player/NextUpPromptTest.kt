package org.siloserver.silo.domain.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextUpPromptTest {
    // A 48:12 episode whose credits marker starts at 46:31: the marker must
    // not move the prompt (silo-android#910).
    private val duration = 2892.0

    @Test fun promptOpensWhenPlaybackCrossesTheChosenSecondsBeforeTheEnd() {
        assertTrue(NextUpPrompt.crossesPromptPoint(2861.6, 2862.1, duration, promptSeconds = 30))
        assertTrue(NextUpPrompt.crossesPromptPoint(2881.5, 2882.0, duration, promptSeconds = 10))
        assertFalse(NextUpPrompt.crossesPromptPoint(2791.0, 2792.0, duration, promptSeconds = 30))
    }

    @Test fun playbackAlreadyPastThePointDoesNotReopenThePrompt() {
        assertFalse(NextUpPrompt.crossesPromptPoint(2870.0, 2870.5, duration, promptSeconds = 30))
    }

    @Test fun atEndAndUnknownDurationNeverOpenBeforeTheStreamEnds() {
        assertFalse(NextUpPrompt.crossesPromptPoint(2891.0, 2892.0, duration, promptSeconds = 0))
        assertFalse(NextUpPrompt.crossesPromptPoint(10.0, 11.0, 0.0, promptSeconds = 30))
    }
}
