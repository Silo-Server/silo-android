package org.siloserver.silo.tv.ui.shell

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TvTopMenuFocusRequestTest {
    @Test
    fun focusIsRequestedOnlyAfterTheTargetHasHadAFrameToCompose() = runTest {
        val events = mutableListOf<String>()

        requestTopMenuFocusUntilApplied(
            awaitFrame = { events += "frame" },
            requestFocus = { events += "focus"; true },
        )

        assertEquals(listOf("frame", "focus"), events)
    }

    @Test
    fun aTargetThatIsNotAttachedYetIsRetriedOnTheNextFrame() = runTest {
        val events = mutableListOf<String>()
        var attempts = 0

        requestTopMenuFocusUntilApplied(
            awaitFrame = { events += "frame" },
            requestFocus = {
                events += "focus"
                attempts += 1
                attempts == 2
            },
        )

        assertEquals(listOf("frame", "focus", "frame", "focus"), events)
    }

    @Test
    fun retryStopsWhenItsTargetIsNoLongerCurrent() = runTest {
        val events = mutableListOf<String>()
        var targetIsCurrent = true

        requestTopMenuFocusUntilApplied(
            awaitFrame = { events += "frame" },
            isTargetCurrent = { targetIsCurrent },
            requestFocus = {
                events += "focus"
                targetIsCurrent = false
                false
            },
        )

        assertEquals(listOf("frame", "focus"), events)
    }

    @Test
    fun retryStopsAfterSixFramesWhenTheTargetNeverAttaches() = runTest {
        var frames = 0
        var attempts = 0

        requestTopMenuFocusUntilApplied(
            awaitFrame = { frames += 1 },
            requestFocus = {
                attempts += 1
                attempts == 7
            },
        )

        assertEquals(6, frames)
        assertEquals(6, attempts)
    }

    @Test
    fun libraryTargetIsNotAvailableAfterItsDestinationDisappears() {
        val movies = TvRootDestination.LibraryType(TvLibraryTabType.Movies)

        assertEquals(
            false,
            isTopMenuFocusTargetAvailable(
                target = TvTopMenuPanel.Root(movies),
                destinations = listOf(TvRootDestination.Home, TvRootDestination.Calendar),
            ),
        )
    }

    @Test
    fun focusRequestRemainsPendingUntilItsLibraryDestinationAppears() = runTest {
        val movies = TvRootDestination.LibraryType(TvLibraryTabType.Movies)
        val identity = 1 to TvTopMenuPanel.Root(movies)
        val initialHandled = 0 to null
        var focusAttempts = 0

        val handledWhileAbsent = handleTopMenuFocusRequestIfAvailable(
            requestIdentity = identity,
            lastHandledRequest = initialHandled,
            isFocusSuppressed = false,
            isTargetAvailable = false,
            requestFocus = {
                focusAttempts += 1
                true
            },
        )
        val handledAfterAppearing = handleTopMenuFocusRequestIfAvailable(
            requestIdentity = identity,
            lastHandledRequest = handledWhileAbsent,
            isFocusSuppressed = false,
            isTargetAvailable = true,
            requestFocus = {
                focusAttempts += 1
                true
            },
        )

        assertEquals(initialHandled, handledWhileAbsent)
        assertEquals(identity, handledAfterAppearing)
        assertEquals(1, focusAttempts)
    }
}
