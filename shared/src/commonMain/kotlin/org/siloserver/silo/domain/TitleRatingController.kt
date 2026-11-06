package org.siloserver.silo.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.port.PersonalWriteIntent

data class TitleRatingState(
    /** The profile's stars for this title, 1 to 5; null when unrated. */
    val rating: Int? = null,
    /** Why the last change didn't save, until the viewer dismisses it. */
    val failureMessage: String? = null,
    /** A change is on its way to the server; a page holds back new picks until it settles. */
    val isSaving: Boolean = false,
)

/**
 * The viewer's star rating on one title page, shared by the phone and TV
 * screens. It owns the rating on show so a save and a detail reload can't
 * overwrite each other.
 *
 * A choice shows straight away and only one save runs at a time; pages hold
 * back new picks during a save, and one that still arrives is dropped. A save
 * finishes even if the viewer leaves the page, though a failure then has no
 * screen to show on. A refused save restores the
 * earlier rating. A save whose outcome is unknown is never sent again: the
 * stored rating is read back instead. Only a read that overlapped no save may change
 * the rating.
 *
 * Call from the scope's single thread.
 */
class TitleRatingController(
    private val itemId: String,
    private val personalData: PersonalDataRepository,
    private val scope: CoroutineScope,
    /** Called with the rating the server now holds, for the offline copy. */
    private val onSaved: suspend (Int?) -> Unit = {},
) {
    private val _state = MutableStateFlow(TitleRatingState())
    val state: StateFlow<TitleRatingState> = _state.asStateFlow()

    private var saving: PersonalWriteIntent? = null

    /** Bumped when a save starts and ends, so a read can tell it overlapped one. */
    private var revision = 0L

    class ReadStamp internal constructor(internal val revision: Long)

    /** Take this before fetching a rating to pass to [adoptRead]. */
    fun beginRead() = ReadStamp(revision)

    fun adoptRead(stamp: ReadStamp, rating: Int?) {
        if (isSaving() || stamp.revision != revision) return
        // Getting here also means a save left over from another profile no longer counts.
        _state.update { it.copy(rating = rating, isSaving = false) }
    }

    /** Saves [rating], or clears it for null. Returns the save, or null if none started. */
    fun set(rating: Int?): Job? {
        val previous = _state.value.rating
        if (rating == previous || isSaving()) return null
        val intent = personalData.beginRating(itemId, rating)
        saving = intent
        revision++
        _state.update { it.copy(rating = rating, failureMessage = null, isSaving = true) }
        // Undispatched so leaving the page can't cancel the save before it starts.
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    val result = personalData.performPersonalWrite(intent)
                    if (!personalData.isCurrent(intent)) return@withContext
                    when (result) {
                        is ApiResult.Success -> recordSaved(rating)
                        // A gateway can report 5xx for a save the server finished.
                        is ApiResult.Error ->
                            if (result.code >= 500) settleUnknownOutcome(intent, rating, previous)
                            else fail(previous, NOT_SAVED_MESSAGE)
                        is ApiResult.NetworkError -> settleUnknownOutcome(intent, rating, previous)
                    }
                } finally {
                    if (saving == intent) {
                        saving = null
                        _state.update { it.copy(isSaving = false) }
                        // After an identity change this save no longer overlaps anything the page reads.
                        if (personalData.isCurrent(intent)) revision++
                    }
                }
            }
        }
    }

    fun dismissFailure() {
        _state.update { it.copy(failureMessage = null) }
    }

    /** A save left over from another profile or server no longer holds this page. */
    private fun isSaving() = saving?.let(personalData::isCurrent) == true

    private suspend fun settleUnknownOutcome(intent: PersonalWriteIntent, target: Int?, previous: Int?) {
        val stored = personalData.readRating(itemId)
        if (!personalData.isCurrent(intent)) return
        when (stored) {
            is ApiResult.Success -> {
                if (stored.data != target) fail(stored.data, NOT_SAVED_MESSAGE)
                recordSaved(stored.data)
            }
            else -> fail(previous, UNCONFIRMED_MESSAGE)
        }
    }

    /** The server has the rating, so a failed offline copy only waits for the next load. */
    private suspend fun recordSaved(rating: Int?) {
        try {
            onSaved(rating)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    private fun fail(rating: Int?, message: String) {
        _state.update { it.copy(rating = rating, failureMessage = message) }
    }

    companion object {
        const val FAILURE_TITLE = "Couldn't Save Rating"
        const val NOT_SAVED_MESSAGE = "Your rating wasn't changed. Try again."
        const val UNCONFIRMED_MESSAGE =
            "The server didn't confirm the change, so your rating may not be up to date. Try again."
    }
}
