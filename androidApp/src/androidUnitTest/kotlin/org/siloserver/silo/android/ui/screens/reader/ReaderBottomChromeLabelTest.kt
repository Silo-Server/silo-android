package org.siloserver.silo.android.ui.screens.reader

import org.siloserver.silo.common.ebook.ReaderCapabilities
import org.siloserver.silo.model.book.BookFormat
import kotlin.test.Test
import kotlin.test.assertFalse

class ReaderBottomChromeLabelTest {
    @Test
    fun externalBottomChromeLabelDoesNotPretendToHavePages() {
        val label = readerBottomChromeLabel(
            ReaderUiState(
                capabilities = ReaderCapabilities.forFormat(BookFormat.Unknown),
                format = BookFormat.Unknown,
            ),
        )

        assertFalse(label.orEmpty().contains("Page"))
    }
}
