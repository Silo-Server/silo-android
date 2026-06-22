package com.continuum.app.android.ui.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source guard for the phase-6 shared-element hero transition. The morph only
 * works when every link in the chain is present: the host publishes the scopes,
 * the poster cards enroll as sources, and the detail backdrop enrolls as the
 * matching target. This codebase has a recurring failure mode of polish that is
 * built but silently unwired — these assertions fail loudly if any end is dropped.
 */
class MobileSharedElementSourceTest {
    private fun read(path: String) = File(path).readText()

    private val sharedElement = read(
        "src/androidMain/kotlin/com/continuum/app/android/ui/navigation/SharedElementTransition.kt",
    )
    private val appNavigation = read(
        "src/androidMain/kotlin/com/continuum/app/android/ui/navigation/AppNavigation.kt",
    )
    private val mediaCard = read(
        "src/androidMain/kotlin/com/continuum/app/android/ui/components/MediaCard.kt",
    )
    private val mediaRow = read(
        "src/androidMain/kotlin/com/continuum/app/android/ui/components/MediaRow.kt",
    )
    private val similarRail = read(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/detail/SimilarRail.kt",
    )
    private val detailShared = read(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/detail/DetailSharedComponents.kt",
    )

    @Test
    fun sharedScopesArePublishedFromTheHost() {
        assertTrue(
            appNavigation.contains("SharedTransitionLayout(modifier = Modifier.fillMaxSize())") &&
                appNavigation.contains("CompositionLocalProvider(LocalSharedTransitionScope provides this)") &&
                appNavigation.contains("CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this)"),
            "AppNavigation must wrap the NavHost in a SharedTransitionLayout and publish " +
                "both the shared-transition scope and the per-destination visibility scope.",
        )
    }

    @Test
    fun helperDefinesScopesAndBoundsModifier() {
        assertTrue(
            sharedElement.contains("val LocalSharedTransitionScope") &&
                sharedElement.contains("val LocalNavAnimatedVisibilityScope") &&
                sharedElement.contains("fun Modifier.heroSharedBounds(") &&
                sharedElement.contains("ResizeMode.RemeasureToBounds"),
            "SharedElementTransition must expose both scope locals and a heroSharedBounds " +
                "modifier that remeasures artwork to the animated bounds.",
        )
    }

    @Test
    fun posterCardsEnrollAsHeroSources() {
        assertTrue(
            mediaCard.contains("sharedContentId: String? = null") &&
                mediaCard.contains(".heroSharedBounds(sharedContentId)"),
            "MediaCard must accept a sharedContentId and tag its poster as the hero source.",
        )
        assertTrue(
            mediaRow.contains("sharedContentId = item.contentId"),
            "Home/media rails must pass each item's id so its poster can morph into the hero.",
        )
        assertTrue(
            similarRail.contains("sharedContentId = item.contentId"),
            "The detail 'More Like This' rail must enroll its posters as hero sources.",
        )
    }

    @Test
    fun detailBackdropIsTheHeroTarget() {
        assertTrue(
            detailShared.contains("contentId = detail.contentId") &&
                detailShared.contains(".heroSharedBounds(contentId)"),
            "The detail backdrop must tag itself as the hero target keyed on the content id.",
        )
    }
}
