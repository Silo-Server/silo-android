package org.siloserver.silo.common.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip / clamp / default / profile-null coverage for the local
 * per-profile audiobook playback-preferences store. Mirrors the
 * AndroidPlayerSettingsStoreTest harness (temp-file DataStore factory,
 * Context stub) minus the server-sync surface — these are device-local prefs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookSettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(profileId: String? = "test-profile"): AudiobookSettingsStore =
        AudiobookSettingsStore(
            context = mockContextStub(),
            getActiveProfileId = { profileId },
            profileChangeSignal = flowOf(Unit),
            dataStoreFactory = { _, id ->
                val file = File(tempFolder.root, "ds_$id.preferences_pb")
                PreferenceDataStoreFactory.create(produceFile = { file })
            },
        )

    @Test
    fun `flows emit defaults when nothing stored`() = runTest {
        val store = newStore()
        assertEquals(30, store.skipBackSecondsFlow.first())
        assertEquals(30, store.skipForwardSecondsFlow.first())
        assertEquals(1.0f, store.defaultSpeedFlow.first())
        assertEquals(false, store.skipSilenceEnabledFlow.first())
        assertEquals(false, store.volumeNormalizationEnabledFlow.first())
        assertEquals(0, store.volumeBoostDbFlow.first())
    }

    @Test
    fun `setters round-trip through the store`() = runTest {
        val store = newStore()
        store.setSkipBackSeconds(15)
        store.setSkipForwardSeconds(60)
        store.setSkipSilenceEnabled(true)
        store.setVolumeNormalizationEnabled(true)
        store.setVolumeBoostDb(6)
        assertEquals(15, store.skipBackSecondsFlow.first())
        assertEquals(60, store.skipForwardSecondsFlow.first())
        assertEquals(true, store.skipSilenceEnabledFlow.first())
        assertEquals(true, store.volumeNormalizationEnabledFlow.first())
        assertEquals(6, store.volumeBoostDbFlow.first())
    }

    @Test
    fun `skip interval rejects disallowed values and falls back to 30`() = runTest {
        val store = newStore()
        store.setSkipBackSeconds(99)
        assertEquals(30, store.skipBackSecondsFlow.first())
        store.setSkipForwardSeconds(15)
        assertEquals(15, store.skipForwardSecondsFlow.first())
    }

    @Test
    fun `default speed clamps to 0_5 to 3_0`() = runTest {
        val store = newStore()
        store.setDefaultSpeed(10.0f)
        assertEquals(3.0f, store.defaultSpeedFlow.first())
        store.setDefaultSpeed(0.1f)
        assertEquals(0.5f, store.defaultSpeedFlow.first())
    }

    @Test
    fun `volume boost clamps to 0 to 12 dB`() = runTest {
        val store = newStore()
        store.setVolumeBoostDb(99)
        assertEquals(12, store.volumeBoostDbFlow.first())
        store.setVolumeBoostDb(-5)
        assertEquals(0, store.volumeBoostDbFlow.first())
    }

    @Test
    fun `null profile emits defaults and setters no-op`() = runTest {
        val store = newStore(profileId = null)
        assertEquals(30, store.skipBackSecondsFlow.first())
        store.setSkipBackSeconds(10)
        assertEquals(30, store.skipBackSecondsFlow.first())
    }

    /**
     * Context is only touched by the default dataStoreFactory, which the
     * tests replace — so a bare ContextWrapper that fails loudly if
     * dereferenced is sufficient.
     */
    private fun mockContextStub(): android.content.Context =
        object : android.content.ContextWrapper(null) {}
}
