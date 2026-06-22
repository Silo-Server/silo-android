package com.continuum.app.tv.ui.screens.search

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvSearchLayoutReadabilityTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/search/TvSearchScreen.kt",
    ).readText()

    @Test
    fun searchHeaderStartsBelowTopMenuChrome() {
        assertTrue(source.contains("top = TvTopMenuLayout.contentTopInset"))
    }

    @Test
    fun searchSupportsPredictableDpadHandoffFromFieldToResults() {
        // The field pins DOWN to the chip rail (now a multi-line focusProperties
        // block that also routes RIGHT to the voice-search mic when available).
        assertTrue(source.contains("down = firstFilterChipFocusRequester"))
        assertTrue(source.contains("Modifier.focusProperties { down = firstResultFocusRequester }"))
        assertTrue(source.contains("up = firstFilterChipFocusRequester"))
        assertTrue(source.contains("keyboardController?.hide()"))
        assertTrue(source.contains("onResultsFocusRequested()"))
    }

    @Test
    fun voiceSearchMicIsGatedOnRecognitionAvailability() {
        // Mic affordance must only render (and only claim D-pad focus) when
        // speech recognition is available, so the remote never lands on a dead
        // control on mic-less TV devices.
        assertTrue(source.contains("if (voiceAvailable)"))
        assertTrue(source.contains("right = micFocusRequester"))
        assertTrue(source.contains("SearchMicButton("))
    }
}
