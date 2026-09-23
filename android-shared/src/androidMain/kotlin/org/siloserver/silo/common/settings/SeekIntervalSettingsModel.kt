package org.siloserver.silo.common.settings

import org.siloserver.silo.common.player.AudiobookSettingsStore
import org.siloserver.silo.domain.settings.SeekIntervalController
import org.siloserver.silo.model.settings.LegacyAudiobookIntervals
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekIntervalPair
import org.siloserver.silo.model.settings.SeekIntervalState
import org.siloserver.silo.model.settings.SeekIntervalSupport
import org.siloserver.silo.model.settings.SeekIntervals
import org.siloserver.silo.model.settings.SeekMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the phone and TV settings screens render for the skip intervals. */
data class SeekIntervalSettingsUiState(
    val support: SeekIntervalSupport = SeekIntervalSupport.Unknown,
    val video: SeekIntervalPair = SeekIntervals.DEFAULTS,
    val audiobook: SeekIntervalPair = SeekIntervals.DEFAULTS,
    /** Device-local audiobook values the user explicitly stored before revision 9. */
    val legacyAudiobook: LegacyAudiobookIntervals = LegacyAudiobookIntervals(),
    val importInProgress: Boolean = false,
    /** Per-direction result of the last explicit import. */
    val importMessage: String? = null,
    /** Last failed save, cleared by the next successful one. */
    val saveError: String? = null,
    /** Media group [saveError] belongs to, so each screen shows it under that group. */
    val saveErrorMedia: SeekMedia? = null,
) {
    val choices: List<Int> get() = SeekIntervals.CHOICES

    /** Pickers are live only once the server has confirmed the keys. */
    val editable: Boolean get() = support == SeekIntervalSupport.Supported

    /** The first server check has not answered yet and nothing is cached. */
    val checking: Boolean get() = support == SeekIntervalSupport.Unknown

    /** The check failed with no earlier answer; players use device values. */
    val checkFailed: Boolean get() = support == SeekIntervalSupport.Unavailable

    fun saveErrorFor(media: SeekMedia): String? = saveError?.takeIf { saveErrorMedia == media }

    /**
     * Offer the explicit import only on a supporting server, and only while a
     * stored device value differs from the profile's current one.
     */
    val showLegacyImport: Boolean
        get() = editable && legacyAudiobook.importable.any { (direction, seconds) ->
            audiobook.seconds(direction) != seconds
        }

    /** Names the device values the import would upload, e.g. "Skip back 15s, skip forward 60s". */
    val legacyAudiobookSummary: String
        get() = SeekDirection.entries.mapNotNull { direction ->
            legacyAudiobook.importable[direction]?.let { seconds ->
                val name = if (direction == SeekDirection.Back) "skip back" else "skip forward"
                "$name ${seconds}s"
            }
        }.joinToString(", ").replaceFirstChar { it.uppercase() }

    companion object {
        fun from(
            seek: SeekIntervalState,
            legacy: LegacyAudiobookIntervals,
            local: LocalState,
        ): SeekIntervalSettingsUiState = SeekIntervalSettingsUiState(
            support = seek.support,
            video = seek.videoIntervals,
            audiobook = seek.audiobookIntervals,
            legacyAudiobook = legacy,
            importInProgress = local.importInProgress,
            importMessage = local.importMessage,
            saveError = local.saveError,
            saveErrorMedia = local.saveErrorMedia,
        )
    }

    data class LocalState(
        val importInProgress: Boolean = false,
        val importMessage: String? = null,
        val saveError: String? = null,
        val saveErrorMedia: SeekMedia? = null,
    )
}

/**
 * Settings-screen half of the profile-wide seek intervals, owned by the phone
 * and TV settings view models. Picks save at profile scope through
 * [SeekIntervalStore]; the legacy audiobook import runs only when the user
 * asks for it and reports each direction separately.
 */
class SeekIntervalSettingsModel(
    private val store: SeekIntervalStore,
    private val legacyAudiobook: Flow<LegacyAudiobookIntervals>,
    private val scope: CoroutineScope,
) {
    constructor(
        store: SeekIntervalStore,
        audiobookSettingsStore: AudiobookSettingsStore,
        scope: CoroutineScope,
    ) : this(store, audiobookSettingsStore.legacySkipIntervalsFlow, scope)

    private val local = MutableStateFlow(SeekIntervalSettingsUiState.LocalState())

    val state: StateFlow<SeekIntervalSettingsUiState> =
        combine(store.state, legacyAudiobook, local) { seek, legacy, localState ->
            SeekIntervalSettingsUiState.from(seek, legacy, localState)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            SeekIntervalSettingsUiState.from(
                store.state.value,
                LegacyAudiobookIntervals(),
                SeekIntervalSettingsUiState.LocalState(),
            ),
        )

    /** Re-probe the server; the settings screen opening is a refresh edge. */
    fun refresh() {
        scope.launch { store.refresh() }
    }

    fun select(media: SeekMedia, direction: SeekDirection, seconds: Int) {
        if (!state.value.editable) return
        scope.launch {
            val error = when (val result = store.save(media, direction, seconds)) {
                is SeekIntervalController.SaveResult.Saved -> null
                is SeekIntervalController.SaveResult.Invalid -> "${result.seconds}s is not a supported interval."
                is SeekIntervalController.SaveResult.Failed ->
                    "Couldn't save the skip interval" +
                        (result.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
            }
            local.update { it.copy(saveError = error, saveErrorMedia = media.takeIf { error != null }) }
        }
    }

    /** Explicit, user-initiated upload of the device's legacy audiobook values. */
    fun importLegacyAudiobook() {
        val current = state.value
        if (!current.editable || current.importInProgress) return
        local.update { it.copy(importInProgress = true, importMessage = null) }
        scope.launch {
            val message = try {
                store.importLegacyAudiobook(current.legacyAudiobook)?.describe()
                    ?: "This server does not support profile-wide skip intervals. Nothing was uploaded."
            } finally {
                local.update { it.copy(importInProgress = false) }
            }
            local.update { it.copy(importMessage = message) }
        }
    }

    fun dismissMessages() {
        local.update { it.copy(importMessage = null, saveError = null, saveErrorMedia = null) }
    }
}
