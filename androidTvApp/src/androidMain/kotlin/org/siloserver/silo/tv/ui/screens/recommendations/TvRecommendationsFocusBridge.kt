package org.siloserver.silo.tv.ui.screens.recommendations

internal suspend fun requestRecommendationRowFocus(
    requestRowContainer: () -> Boolean,
    awaitFrame: suspend () -> Unit,
    requestFirstCard: () -> Boolean,
): Boolean {
    if (!requestRowContainer()) return false
    awaitFrame()
    requestFirstCard()
    return true
}

internal fun shouldBridgeRecommendationsDown(
    showingRecommendations: Boolean,
    hasVisibleRecommendations: Boolean,
): Boolean = showingRecommendations && hasVisibleRecommendations
