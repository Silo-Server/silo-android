package org.siloserver.silo.common.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R
import org.siloserver.silo.common.player.AudiobookSettingsStore
import org.siloserver.silo.domain.settings.SeekIntervalController
import org.siloserver.silo.model.settings.LegacyAudiobookIntervals
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekImportOutcome
import org.siloserver.silo.model.settings.SeekImportResult
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
    val importOutcome: SeekImportMessage? = null,
    /** Last failed save, cleared by the next successful one. */
    val saveError: SeekSaveError? = null,
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

    /** The last failed save, when it belongs under [media]'s group. */
    fun saveErrorOf(media: SeekMedia): SeekSaveError? = saveError?.takeIf { saveErrorMedia == media }

    /** [saveErrorOf] as the words shown under [media]'s group. */
    @Composable
    fun saveErrorFor(media: SeekMedia): String? = saveErrorOf(media)?.let { seekSaveErrorText(it) }

    /** The words for [importOutcome]: one sentence per direction. */
    val importMessage: String?
        @Composable get() = importOutcome?.let { seekImportMessageText(it) }

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
        @Composable get() {
            val back = legacyAudiobook.importable[SeekDirection.Back]
            val forward = legacyAudiobook.importable[SeekDirection.Forward]
            return when {
                back != null && forward != null -> stringResource(R.string.settings_seekIntervals_legacy_both, back, forward)
                back != null -> stringResource(R.string.settings_seekIntervals_legacy_back, back)
                forward != null -> stringResource(R.string.settings_seekIntervals_legacy_forward, forward)
                else -> ""
            }
        }

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
            importOutcome = local.importOutcome,
            saveError = local.saveError,
            saveErrorMedia = local.saveErrorMedia,
        )
    }

    data class LocalState(
        val importInProgress: Boolean = false,
        val importOutcome: SeekImportMessage? = null,
        val saveError: SeekSaveError? = null,
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
                is SeekIntervalController.SaveResult.Invalid -> SeekSaveError.Invalid(result.seconds)
                is SeekIntervalController.SaveResult.Failed -> when (result.message) {
                    DefaultSeekIntervalStore.UNSUPPORTED_MESSAGE -> SeekSaveError.Unsupported
                    else -> SeekSaveError.Failed(result.message?.takeIf { it.isNotBlank() })
                }
            }
            local.update { it.copy(saveError = error, saveErrorMedia = media.takeIf { error != null }) }
        }
    }

    /** Explicit, user-initiated upload of the device's legacy audiobook values. */
    fun importLegacyAudiobook() {
        val current = state.value
        if (!current.editable || current.importInProgress) return
        local.update { it.copy(importInProgress = true, importOutcome = null) }
        scope.launch {
            val outcome = try {
                store.importLegacyAudiobook(current.legacyAudiobook)
                    ?.let { SeekImportMessage.Result(it) }
                    ?: SeekImportMessage.Unsupported
            } finally {
                local.update { it.copy(importInProgress = false) }
            }
            local.update { it.copy(importOutcome = outcome) }
        }
    }

    fun dismissMessages() {
        local.update { it.copy(importOutcome = null, saveError = null, saveErrorMedia = null) }
    }
}

/** Why a skip interval pick didn't save. */
sealed interface SeekSaveError {
    /** [seconds] isn't one of the offered intervals; nothing was written. */
    data class Invalid(val seconds: Int) : SeekSaveError

    /** The server is known not to hold profile skip intervals. */
    data object Unsupported : SeekSaveError

    /** The write failed; [detail] is the server's or the network's own words, when there are any. */
    data class Failed(val detail: String?) : SeekSaveError
}

/** What the last explicit legacy import did. */
sealed interface SeekImportMessage {
    /** The server doesn't hold profile skip intervals, so nothing was uploaded. */
    data object Unsupported : SeekImportMessage

    data class Result(val result: SeekImportResult) : SeekImportMessage
}

@Composable
private fun seekSaveErrorText(error: SeekSaveError): String = when (error) {
    is SeekSaveError.Invalid -> stringResource(R.string.settings_seekIntervals_invalid, error.seconds)
    SeekSaveError.Unsupported -> stringResource(
        R.string.settings_seekIntervals_saveFailedDetail,
        stringResource(R.string.settings_seekIntervals_unsupportedServer),
    )
    is SeekSaveError.Failed -> error.detail?.let { stringResource(R.string.settings_seekIntervals_saveFailedDetail, it) }
        ?: stringResource(R.string.settings_seekIntervals_saveFailed)
}

/** One sentence per direction, joined with a space. */
@Composable
private fun seekImportMessageText(message: SeekImportMessage): String = when (message) {
    SeekImportMessage.Unsupported -> stringResource(R.string.settings_seekIntervals_import_unsupported)
    is SeekImportMessage.Result -> SeekDirection.entries.map { direction ->
        seekImportOutcomeText(direction, message.result.outcome(direction))
    }.joinToString(" ")
}

@Composable
private fun seekImportOutcomeText(direction: SeekDirection, outcome: SeekImportOutcome): String {
    val back = direction == SeekDirection.Back
    return when (outcome) {
        is SeekImportOutcome.Imported -> stringResource(
            if (back) R.string.settings_seekIntervals_import_backImported else R.string.settings_seekIntervals_import_forwardImported,
            outcome.seconds,
        )
        SeekImportOutcome.NotStored -> stringResource(
            if (back) R.string.settings_seekIntervals_import_backNotStored else R.string.settings_seekIntervals_import_forwardNotStored,
        )
        is SeekImportOutcome.Invalid -> stringResource(
            if (back) R.string.settings_seekIntervals_import_backInvalid else R.string.settings_seekIntervals_import_forwardInvalid,
            outcome.seconds,
        )
        is SeekImportOutcome.Failed -> outcome.message?.takeIf { it.isNotBlank() }?.let { detail ->
            stringResource(
                if (back) R.string.settings_seekIntervals_import_backFailedDetail else R.string.settings_seekIntervals_import_forwardFailedDetail,
                outcome.seconds,
                detail,
            )
        } ?: stringResource(
            if (back) R.string.settings_seekIntervals_import_backFailed else R.string.settings_seekIntervals_import_forwardFailed,
            outcome.seconds,
        )
    }
}
