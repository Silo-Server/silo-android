package org.siloserver.silo.tv.ui.screens.search

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSearchLayoutReadabilityTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/search/TvSearchScreen.kt",
    ).readText()

    @Test
    fun searchHeaderStartsBelowTopMenuChrome() {
        assertTrue(source.contains("top = TvTopMenuLayout.contentTopInset"))
    }

    @Test
    fun searchSupportsPredictableDpadHandoffFromFieldToResults() {
        assertTrue(source.contains(".focusProperties { down = firstFilterChipFocusRequester }"))
        assertTrue(source.contains("Modifier.focusProperties { down = firstContentFocusRequester }"))
        assertTrue(source.contains("up = firstFilterChipFocusRequester"))
        assertTrue(source.contains("firstResultFocusRequester.requestFocus()"))
        assertTrue(source.contains("firstRequestResultFocusRequester.requestFocus()"))
        assertTrue(source.contains("var pendingSearchFocus by remember { mutableStateOf(false) }"))
        assertTrue(source.contains("requestSearchSettled"))
        assertTrue(source.contains("visibleRequestResults.size"))
        assertTrue(source.contains("pendingSearchFocus = true"))
    }

    @Test
    fun searchMovesItsHeaderWithTheScrollingGrid() {
        assertTrue(source.contains("SearchStage("))
        assertTrue(source.contains(".weight(1f)"))
        assertTrue(source.contains("header = {"))
        assertFalse(source.contains("scrollToItem("))
    }

    @Test
    fun searchUsesPlatformImeTextFieldInsteadOfSiloOwnedKeyboard() {
        assertTrue(source.contains("OutlinedTextField("))
        assertTrue(source.contains("KeyboardOptions("))
        assertTrue(source.contains("KeyboardActions("))
        assertTrue(source.contains("KeyboardType.Text"))
        assertTrue(source.contains("ImeAction.Search"))
        assertFalse(source.contains("SearchDisplayField("))
        assertFalse(source.contains("SiloSearchKeyboard("))
    }

    @Test
    fun searchEmbedsRequestsWhenServerFeatureIsEnabled() {
        assertTrue(source.contains("RequestsFeatureStore"))
        assertTrue(source.contains("TvRequestSearchSection("))
        assertTrue(source.contains("onOpenRequestDetail"))
    }

    @Test
    fun requestSearchHasVisibleStatesAndFocusHandoff() {
        assertTrue(source.contains("requestState.isLoading"))
        assertTrue(source.contains("requestState.error"))
        assertTrue(source.contains("requestState.hasSubmittedQuery"))
        assertTrue(source.contains("RequestSearchFeedbackRow("))
        assertTrue(source.contains("firstRequestResultFocusRequester.requestFocus()"))
        assertTrue(source.contains("requestSearchSettled"))
    }
}
