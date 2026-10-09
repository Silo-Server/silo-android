package org.siloserver.silo.repository

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.SiloJson

/**
 * The libraries the active profile hid from its own navigation
 * (`ui.disabled_library_ids`, the web's Settings → Libraries → "Visible in
 * navigation"). A preference, not access control: for a profile without a
 * library limit the server still lists these libraries in
 * `GET /user/libraries`, so the client leaves them out the way the web does.
 *
 * The set is bound to the identity generation it was read under: after a
 * profile or server switch nothing reads as hidden until the new profile's
 * value arrives, and a read that straddles a switch is dropped. Reads run one
 * at a time, so an older answer never lands over a newer one.
 */
class HiddenLibrariesStore(
    private val settingsRepository: SettingsRepository,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
) {
    private data class Snapshot(val generation: Long, val ids: Set<Int>)

    @Volatile
    private var snapshot = Snapshot(generation = -1, ids = emptySet())
    private val readMutex = Mutex()
    private var failedReadGeneration = -1L
    private val _revision = MutableStateFlow(0)

    /**
     * Bumps when the set changes, so screens that loaded the library list
     * re-load it. A first read made by a list load ([ensureLoaded]) doesn't
     * bump it, since that load applies the result, unless an earlier read for
     * this profile failed and lists went out unfiltered.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun current(): Set<Int> = snapshot.idsFor(identityTransitions.generation.value)

    /** Reads the setting unless this profile's value is already known. */
    suspend fun ensureLoaded() {
        if (snapshot.generation != identityTransitions.generation.value) read(announce = false)
    }

    /** Re-reads the setting. A failed read keeps the last value read for this profile. */
    suspend fun refresh() = read(announce = true)

    private suspend fun read(announce: Boolean) = readMutex.withLock {
        val generation = identityTransitions.generation.value
        // A read that finished while this one waited already answered it.
        if (!announce && snapshot.generation == generation) return@withLock
        // A switch advances the generation before it installs the new
        // identity; wait it out so this read can't answer for the old one.
        identityTransitions.withCurrentGeneration(generation) { } ?: return@withLock
        val result = settingsRepository.getEffectiveValues(listOf(KEY))
        if (generation != identityTransitions.generation.value) return@withLock
        if (result !is ApiResult.Success) {
            failedReadGeneration = generation
            return@withLock
        }
        val previous = snapshot.idsFor(generation)
        val ids = parseLibraryIds(result.data[KEY]?.value)
        snapshot = Snapshot(generation, ids)
        val listsWentOutUnfiltered = failedReadGeneration == generation
        if ((announce || listsWentOutUnfiltered) && ids != previous) _revision.update { it + 1 }
    }

    private fun Snapshot.idsFor(generation: Long): Set<Int> =
        if (this.generation == generation) ids else emptySet()

    companion object {
        const val KEY = SettingKeys.UI_DISABLED_LIBRARY_IDS

        /**
         * Same rules as the web's `parseLibraryIDList`: the canonical array,
         * the legacy JSON-string encoding of one, or null. Entries that are not
         * positive whole numbers are dropped.
         */
        fun parseLibraryIds(value: JsonElement?): Set<Int> {
            val array = when (value) {
                is JsonArray -> value
                is JsonPrimitive -> if (value.isString && value.content.isNotBlank()) {
                    runCatching { SiloJson.parseToJsonElement(value.content) }.getOrNull() as? JsonArray
                } else {
                    null
                }
                else -> null
            } ?: return emptySet()
            return array.mapNotNullTo(linkedSetOf()) { entry ->
                (entry as? JsonPrimitive)
                    ?.takeUnless { it.isString }
                    ?.doubleOrNull
                    ?.takeIf { it >= 1 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }
                    ?.toInt()
            }
        }
    }
}

fun List<UserLibrary>.withoutHidden(hiddenIds: Set<Int>): List<UserLibrary> =
    if (hiddenIds.isEmpty()) this else filterNot { it.id in hiddenIds }
