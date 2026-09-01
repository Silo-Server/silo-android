package org.siloserver.silo.common.player.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlaybackDelivery

const val AUDIO_TRACK_SELECTION_FAILED_CLASSIFICATION = "audio_track_selection_failed"
const val AUDIO_SELECTION_CONFIRMATION_TIMEOUT_MS = 5_000L

/**
 * What the viewer wants the audio to be, as a CATALOG ordinal into
 * `FileVersion.audioTracks`.
 *
 * [explicit] separates a fresh decision from a restore: a launch pick or a
 * persisted fingerprint puts playback back where it was and must not be
 * recorded as a new choice for episode carry-over, whereas a picker or remote
 * selection must.
 *
 * [fileId] scopes it. Audio ordinals are per-file and the outgoing version can
 * stay interactive while a replacement loads, so an intent made in that window
 * must not be reconciled against a different file.
 */
data class DesiredAudio(
    val generation: Long,
    val catalogOrdinal: Int,
    val explicit: Boolean,
    val fileId: Int?,
    val confirmed: Boolean = false,
)

/**
 * A pending local audio switch: select [targetOrdinal] on the player, and on
 * confirmation commit [catalogOrdinal] as the viewer's choice.
 */
data class LocalAudioSelection(
    val generation: Long,
    val catalogOrdinal: Int,
    /** Media3 audio-group ordinal — what AudioTrackManager expects. */
    val targetOrdinal: Int,
    /**
     * Distinct per issuance. StateFlow conflates equal values, so re-applying
     * after a remount has to look different or the collector never fires.
     */
    val attempt: Long,
)

/**
 * What to do about the desired audio, given one track snapshot.
 *
 * Extracted from the ViewModel so the decision is testable on its own: the
 * ViewModel takes fourteen constructor dependencies, and every review round of
 * this area has turned up a case that neither reasoning nor the pure-helper
 * tests caught. The orchestration around it — generations, persistence, the
 * request flow — stays in the ViewModel; only the decision lives here.
 */
sealed interface AudioReconcileAction {
    /** Nothing to do with this snapshot. */
    data object None : AudioReconcileAction

    /** The intent belongs to a different file and must be abandoned. */
    data object DropForeignFile : AudioReconcileAction

    /** The player is on the wanted track. */
    data object Confirm : AudioReconcileAction

    /** Select this mounted ordinal on the player. */
    data class Apply(val targetOrdinal: Int) : AudioReconcileAction
}

/**
 * Decides what a snapshot means for [desired].
 *
 * @param selectedOrdinal the Media3 ordinal currently selected, if any.
 * @param planAudioOrdinal the catalog ordinal the server says it delivered.
 * @param requiresMountedIdentity whether a byte-for-byte original-file plan
 * must prove the selected catalog row against Media3's mounted inventory.
 */
fun reconcileDesiredAudioAction(
    desired: DesiredAudio?,
    activeFileId: Int?,
    catalog: List<AudioTrack>,
    mounted: List<MountedAudioTrack>,
    selectedOrdinal: Int?,
    planAudioOrdinal: Int?,
    requiresMountedIdentity: Boolean = false,
): AudioReconcileAction {
    if (desired == null) return AudioReconcileAction.None
    // An empty or partial snapshot is not evidence of anything. The intent must
    // survive it: discarding on the first callback is what made a launch pick
    // silently fail.
    if (mounted.isEmpty()) return AudioReconcileAction.None

    // Audio ordinals are per-file, and the outgoing version stays interactive
    // while a replacement loads, so an intent from that window would otherwise
    // name a different track here.
    if (desired.fileId != null && desired.fileId != activeFileId) {
        return AudioReconcileAction.DropForeignFile
    }

    val wanted = catalog.getOrNull(desired.catalogOrdinal) ?: return AudioReconcileAction.None

    // Resolved ONCE against the whole snapshot. Matching a one-element list
    // asks a different question: the matcher stops as soon as one candidate
    // remains, so a main mix and its commentary — same language, same codec —
    // would confirm each other.
    val target = matchMountedAudioTrack(wanted, mounted)
        ?: positionalOriginalMount(
            desired = desired,
            wanted = wanted,
            catalog = catalog,
            mounted = mounted,
            planAudioOrdinal = planAudioOrdinal,
            requiresMountedIdentity = requiresMountedIdentity,
        )
        ?: return if (!requiresMountedIdentity && planAudioOrdinal == desired.catalogOrdinal) {
            // Not in this stream, but the server says it delivered this row: a
            // transcode's recoded output cannot identity-match its own source,
            // so this is satisfied rather than retried forever.
            AudioReconcileAction.Confirm
        } else {
            AudioReconcileAction.None
        }

    // Both ordinals come from this same snapshot, and target was resolved by
    // identity, so comparing them IS the identity comparison.
    return if (selectedOrdinal == target.ordinal) {
        AudioReconcileAction.Confirm
    } else {
        AudioReconcileAction.Apply(target.ordinal)
    }
}

/**
 * Identity by position for a byte-for-byte original mount.
 *
 * [matchMountedAudioTrack] is deliberately conservative: it returns null for a
 * main mix and an untitled commentary that agree on language, codec and
 * layout, and for any field the two sides spell differently. On a transcode
 * that null correctly means "not this stream". On an untouched original file
 * Media3 mounts every source track in file order, which is exactly the order
 * the catalog ordinal indexes, so when the inventories are the same size the
 * server-planned row IS the mounted group at that position. Treating that as
 * unproven armed a failure replan against playback that was already correct.
 *
 * Still refused when the positional candidate contradicts a stated field, so
 * a mount that dropped or reordered tracks cannot be confirmed by accident.
 */
private fun positionalOriginalMount(
    desired: DesiredAudio,
    wanted: AudioTrack,
    catalog: List<AudioTrack>,
    mounted: List<MountedAudioTrack>,
    planAudioOrdinal: Int?,
    requiresMountedIdentity: Boolean,
): MountedAudioTrack? {
    if (!requiresMountedIdentity) return null
    if (planAudioOrdinal != desired.catalogOrdinal) return null
    if (mounted.size != catalog.size) return null
    val candidate = mounted.getOrNull(desired.catalogOrdinal) ?: return null
    val wantedLanguage = canonicalAudioLanguage(wanted.language)
    val mountedLanguage = canonicalAudioLanguage(candidate.language)
    if (wantedLanguage != null && mountedLanguage != null && wantedLanguage != mountedLanguage) {
        return null
    }
    val wantedCodec = canonicalAudioCodecFamily(wanted.codec)
    val mountedCodec = canonicalAudioCodecFamily(candidate.codecOrMime)
    if (wantedCodec != null && mountedCodec != null && wantedCodec != mountedCodec) return null
    val wantedChannels = wanted.channels?.takeIf { it > 0 }
    val mountedChannels = candidate.channelCount?.takeIf { it > 0 }
    if (wantedChannels != null && mountedChannels != null && wantedChannels != mountedChannels) {
        return null
    }
    return candidate
}

/**
 * Whether an original-file plan has made a source-track selection promise that
 * still needs runtime proof. Empty snapshots deliberately do not start the
 * clock: Media3 publishes partial inventories while a source is preparing.
 */
fun shouldVerifyOriginalAudioSelection(
    desired: DesiredAudio?,
    delivery: PlaybackDelivery?,
    planAudioOrdinal: Int?,
    mounted: List<MountedAudioTrack>,
    action: AudioReconcileAction,
): Boolean = desired != null &&
    delivery == PlaybackDelivery.ORIGINAL_HTTP &&
    planAudioOrdinal == desired.catalogOrdinal &&
    mounted.isNotEmpty() &&
    action != AudioReconcileAction.Confirm &&
    action != AudioReconcileAction.DropForeignFile

/**
 * One non-sliding deadline per desired-audio generation. Repeated Media3 track
 * snapshots cannot postpone failure forever, and an expired generation cannot
 * re-arm while its recovery replan is being installed.
 */
class AudioSelectionWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = AUDIO_SELECTION_CONFIRMATION_TIMEOUT_MS,
    private val onExpired: (generation: Long) -> Unit,
) {
    private var activeGeneration: Long? = null
    private var expiredGeneration: Long? = null
    private var job: Job? = null

    fun arm(generation: Long) {
        if (expiredGeneration == generation) return
        if (activeGeneration == generation && job?.isActive == true) return
        job?.cancel()
        activeGeneration = generation
        job = scope.launch {
            delay(timeoutMs)
            activeGeneration = null
            expiredGeneration = generation
            onExpired(generation)
        }
    }

    fun resolve(generation: Long) {
        if (activeGeneration == generation) {
            job?.cancel()
            job = null
            activeGeneration = null
        }
        if (expiredGeneration == generation) expiredGeneration = null
    }

    fun reset() {
        job?.cancel()
        job = null
        activeGeneration = null
        expiredGeneration = null
    }
}

/**
 * The mounted audio tracks of a Media3 [androidx.media3.common.Tracks], in the
 * ordinal space [org.siloserver.silo.common.player.AudioTrackManager] expects:
 * position among audio groups, counting only audio groups.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun mountedAudioTracks(tracks: androidx.media3.common.Tracks): List<MountedAudioTrack> {
    val result = mutableListOf<MountedAudioTrack>()
    var ordinal = 0
    for (group in tracks.groups) {
        if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
        val media = group.mediaTrackGroup
        val format = if (media.length > 0) media.getFormat(0) else null
        result += MountedAudioTrack(
            ordinal = ordinal,
            language = format?.language,
            codecOrMime = format?.sampleMimeType ?: format?.codecs,
            channelCount = format?.channelCount?.takeIf { it > 0 },
            label = format?.label,
        )
        ordinal += 1
    }
    return result
}

/** Ordinal of the currently selected audio group, if any. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun selectedMountedAudioOrdinal(tracks: androidx.media3.common.Tracks): Int? {
    var ordinal = 0
    for (group in tracks.groups) {
        if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
        if (group.isSelected) return ordinal
        ordinal += 1
    }
    return null
}
