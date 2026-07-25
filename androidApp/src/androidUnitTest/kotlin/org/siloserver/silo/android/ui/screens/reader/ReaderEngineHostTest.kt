package org.siloserver.silo.android.ui.screens.reader

import org.siloserver.silo.common.ebook.ReaderCapabilities
import org.siloserver.silo.model.book.BookFormat
import org.siloserver.silo.model.ebook.EbookReadMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderEngineHostTest {
    @Test
    fun externalPanelRoutingRequiresExternalOnlyReadMode() {
        val unsupportedExternalEngineState = ReaderUiState(
            isLoading = false,
            readMode = EbookReadMode.Unsupported,
            capabilities = ReaderCapabilities.forFormat(BookFormat.Unknown),
        )

        assertFalse(shouldShowExternalReadingPanel(unsupportedExternalEngineState))
        assertTrue(
            shouldShowExternalReadingPanel(
                unsupportedExternalEngineState.copy(readMode = EbookReadMode.ExternalOnly),
            ),
        )
        assertFalse(
            shouldShowExternalReadingPanel(
                unsupportedExternalEngineState.copy(
                    readMode = EbookReadMode.ExternalOnly,
                    capabilities = ReaderCapabilities.forFormat(BookFormat.Epub),
                ),
            ),
        )
    }
}
