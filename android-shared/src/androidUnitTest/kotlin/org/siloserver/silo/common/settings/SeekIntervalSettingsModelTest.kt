package org.siloserver.silo.common.settings

import org.siloserver.silo.domain.settings.SeekIntervalController
import org.siloserver.silo.model.settings.LegacyAudiobookIntervals
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekImportOutcome
import org.siloserver.silo.model.settings.SeekImportResult
import org.siloserver.silo.model.settings.SeekIntervalPair
import org.siloserver.silo.model.settings.SeekIntervalState
import org.siloserver.silo.model.settings.SeekIntervalSupport
import org.siloserver.silo.model.settings.SeekMedia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SeekIntervalSettingsModelTest {

    private val supported = SeekIntervalState(
        support = SeekIntervalSupport.Supported,
        videoIntervals = SeekIntervalPair(10, 30),
        audiobookIntervals = SeekIntervalPair(10, 30),
    )

    @Test
    fun `pickers are disabled until the server confirms support`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeSeekIntervalStore()
        val model = SeekIntervalSettingsModel(store, MutableStateFlow(LegacyAudiobookIntervals()), backgroundScope)

        assertFalse(model.state.value.editable)
        model.select(SeekMedia.Video, SeekDirection.Back, 15)
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun `a pick saves at the chosen media and direction`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeSeekIntervalStore(supported)
        val model = SeekIntervalSettingsModel(store, MutableStateFlow(LegacyAudiobookIntervals()), backgroundScope)

        model.select(SeekMedia.Audiobook, SeekDirection.Forward, 60)

        assertEquals(listOf(Triple(SeekMedia.Audiobook, SeekDirection.Forward, 60)), store.saves)
        assertEquals(SeekIntervalPair(10, 60), model.state.value.audiobook)
    }

    @Test
    fun `a save error shows only under the group that failed`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeSeekIntervalStore(supported)
        store.saveFailure = SeekIntervalController.SaveResult.Failed("boom")
        val model = SeekIntervalSettingsModel(store, MutableStateFlow(LegacyAudiobookIntervals()), backgroundScope)

        model.select(SeekMedia.Audiobook, SeekDirection.Back, 15)

        assertTrue(model.state.value.saveErrorFor(SeekMedia.Audiobook) != null)
        assertNull(model.state.value.saveErrorFor(SeekMedia.Video))

        store.saveFailure = null
        model.select(SeekMedia.Audiobook, SeekDirection.Back, 15)
        assertNull(model.state.value.saveErrorFor(SeekMedia.Audiobook))
    }

    @Test
    fun `legacy import is offered only on a supporting server with differing device values`() =
        runTest(UnconfinedTestDispatcher()) {
            val legacy = MutableStateFlow(LegacyAudiobookIntervals(backSeconds = 15, forwardSeconds = 60))
            val unsupported = SeekIntervalSettingsModel(
                FakeSeekIntervalStore(SeekIntervalState(SeekIntervalSupport.Unsupported)),
                legacy,
                backgroundScope,
            )
            assertFalse(unsupported.state.value.showLegacyImport)

            val model = SeekIntervalSettingsModel(FakeSeekIntervalStore(supported), legacy, backgroundScope)
            assertTrue(model.state.value.showLegacyImport)
            assertEquals("Skip back 15s, skip forward 60s", model.state.value.legacyAudiobookSummary)

            // Nothing stored on the device: nothing to offer.
            legacy.value = LegacyAudiobookIntervals()
            assertFalse(model.state.value.showLegacyImport)

            // Stored values that already match the profile: nothing to offer.
            legacy.value = LegacyAudiobookIntervals(backSeconds = 10, forwardSeconds = 30)
            assertFalse(model.state.value.showLegacyImport)
        }

    @Test
    fun `import runs only when asked and reports both directions`() = runTest(UnconfinedTestDispatcher()) {
        val legacy = LegacyAudiobookIntervals(backSeconds = 15, forwardSeconds = 60)
        val store = FakeSeekIntervalStore(
            initial = supported,
            importResult = SeekImportResult(
                back = SeekImportOutcome.Imported(15),
                forward = SeekImportOutcome.Failed(60, "Server error"),
            ),
        )
        val model = SeekIntervalSettingsModel(store, MutableStateFlow(legacy), backgroundScope)
        assertTrue(store.imports.isEmpty())

        model.importLegacyAudiobook()

        assertEquals(listOf(legacy), store.imports)
        assertEquals(
            "Skip back imported (15s). Skip forward (60s) failed: Server error.",
            model.state.value.importMessage,
        )
        assertFalse(model.state.value.importInProgress)
    }
}
