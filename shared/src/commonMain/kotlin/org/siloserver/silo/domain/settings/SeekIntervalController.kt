package org.siloserver.silo.domain.settings

import org.siloserver.silo.model.settings.LegacyAudiobookIntervals
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekImportOutcome
import org.siloserver.silo.model.settings.SeekImportResult
import org.siloserver.silo.model.settings.SeekIntervalPair
import org.siloserver.silo.model.settings.SeekIntervals
import org.siloserver.silo.model.settings.SeekMedia
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stateless wire half of the profile-wide seek intervals, shared by the phone
 * and TV apps. Discovery goes through the settings capabilities, reads through
 * the batched effective endpoint, and writes address `scope=profile` (the only
 * scope the contract allows for these keys).
 */
class SeekIntervalController(
    private val repository: SettingsRepository,
) {

    sealed interface LoadResult {
        /** Server speaks revision 9; both media types resolved. */
        data class Supported(
            val video: SeekIntervalPair,
            val audiobook: SeekIntervalPair,
        ) : LoadResult

        /** Definitive answer: the server predates the keys. Never write. */
        data object Unsupported : LoadResult

        /** Transient failure (offline, 5xx). Keep whatever state was known. */
        data class Failed(val message: String?) : LoadResult
    }

    sealed interface SaveResult {
        data class Saved(val seconds: Int) : SaveResult

        /** Rejected locally: not one of [SeekIntervals.CHOICES]. No request made. */
        data class Invalid(val seconds: Int) : SaveResult

        data class Failed(val message: String?) : SaveResult
    }

    suspend fun load(): LoadResult {
        when (val caps = repository.contractCapabilities()) {
            is ApiResult.Success ->
                if (!SeekIntervals.isSupported(caps.data)) return LoadResult.Unsupported
            // A server without the contract routes answers 404: that is an
            // older server, not a transient failure.
            is ApiResult.Error ->
                return if (caps.code == 404) LoadResult.Unsupported else LoadResult.Failed(caps.message)
            is ApiResult.NetworkError -> return LoadResult.Failed(caps.exception.message)
        }
        return when (val result = repository.getEffectiveValues(SeekIntervals.KEYS)) {
            is ApiResult.Success -> LoadResult.Supported(
                video = SeekIntervals.resolve(result.data, SeekMedia.Video),
                audiobook = SeekIntervals.resolve(result.data, SeekMedia.Audiobook),
            )
            is ApiResult.Error -> LoadResult.Failed(result.message)
            is ApiResult.NetworkError -> LoadResult.Failed(result.exception.message)
        }
    }

    /**
     * Writes one interval at profile scope. The caller is responsible for
     * only calling this once support was confirmed; [load] is the gate.
     */
    suspend fun save(media: SeekMedia, direction: SeekDirection, seconds: Int): SaveResult {
        if (!SeekIntervals.isValid(seconds)) return SaveResult.Invalid(seconds)
        return when (
            val result = repository.setProfileValue(
                SeekIntervals.key(media, direction),
                JsonPrimitive(seconds),
            )
        ) {
            is ApiResult.Success -> SaveResult.Saved(seconds)
            is ApiResult.Error -> SaveResult.Failed(result.message)
            is ApiResult.NetworkError -> SaveResult.Failed(result.exception.message)
        }
    }

    /**
     * Explicit, user-initiated import of the legacy device-local audiobook
     * intervals. Each direction is written independently and reported on its
     * own; a failure on one side never rolls back or blocks the other.
     */
    suspend fun importLegacyAudiobook(legacy: LegacyAudiobookIntervals): SeekImportResult =
        coroutineScope {
            val back = async { importOne(SeekDirection.Back, legacy.backSeconds) }
            val forward = async { importOne(SeekDirection.Forward, legacy.forwardSeconds) }
            SeekImportResult(back = back.await(), forward = forward.await())
        }

    private suspend fun importOne(direction: SeekDirection, seconds: Int?): SeekImportOutcome {
        if (seconds == null) return SeekImportOutcome.NotStored
        return when (val result = save(SeekMedia.Audiobook, direction, seconds)) {
            is SaveResult.Saved -> SeekImportOutcome.Imported(seconds)
            is SaveResult.Invalid -> SeekImportOutcome.Invalid(seconds)
            is SaveResult.Failed -> SeekImportOutcome.Failed(seconds, result.message)
        }
    }
}
