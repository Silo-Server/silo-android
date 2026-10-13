package org.siloserver.silo.android.ui.screens.player

import kotlinx.coroutines.CancellationException
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.mergeDownloadedSubtitles
import org.siloserver.silo.model.subtitles.DownloadedSubtitlesResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.decodeSubtitleIdentityPreference
import org.siloserver.silo.playback.resolveMountedSubtitleOrdinal
import org.siloserver.silo.playback.subtitleMediaIdentityOrNull

internal data class MobileFreshSubtitleRestore(
    val subtitleTracks: List<PlayerSubtitleInfo>,
    val persistedPreferencePresent: Boolean,
    val persistedSelectionOrdinal: Int?,
    val persistedSelectionIdentity: SubtitleIdentity?,
)

internal suspend fun prepareMobileFreshSubtitleRestore(
    mediaFileId: Int?,
    mountedSubtitles: List<PlayerSubtitleInfo>,
    sessionId: String,
    persistedPreference: String?,
    authoritativeInventory: Boolean = false,
    loadDownloadedSubtitles: suspend (Int) -> ApiResult<DownloadedSubtitlesResponse>,
): MobileFreshSubtitleRestore {
    val downloaded = if (authoritativeInventory || mediaFileId == null) {
        emptyList()
    } else {
        try {
            when (val result = loadDownloadedSubtitles(mediaFileId)) {
                is ApiResult.Success -> result.data.subtitles
                else -> emptyList()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }
    val subtitleTracks = if (authoritativeInventory) {
        mountedSubtitles
    } else {
        mergeDownloadedSubtitles(
            existing = mountedSubtitles,
            downloaded = downloaded,
            sessionId = sessionId,
        )
    }
    val preference = persistedPreference?.trim()?.takeIf(String::isNotEmpty)
    val persistedIdentity = decodeSubtitleIdentityPreference(preference)
    val persistedOrdinal = persistedIdentity
        ?.let { identity ->
            resolveMobileSubtitleOrdinal(identity, subtitleTracks)
                ?: resolveSameServerTrackOrdinal(identity, subtitleTracks)
        }
        ?: resolveMountedSubtitleOrdinal(subtitleTracks, preference)
    val resolvedIdentity = when (persistedOrdinal) {
        null -> null
        -1 -> SubtitleIdentity.Off
        else -> subtitleTracks
            .getOrNull(persistedOrdinal)
            ?.let(::mobileSubtitleIdentity)
    }

    return MobileFreshSubtitleRestore(
        subtitleTracks = subtitleTracks,
        persistedPreferencePresent = preference != null,
        persistedSelectionOrdinal = persistedOrdinal,
        persistedSelectionIdentity = resolvedIdentity,
    )
}

/**
 * The row for the very server track [identity] was remembered as, when this
 * session delivers it in another form.
 *
 * How a server track reaches the player — inside the container, as a sidecar,
 * burned in — is the server's per-session decision, not part of the viewer's
 * choice. A track remembered while it played natively ([SubtitleIdentity.Embedded])
 * is offered as a sidecar by the next session's first plan, and the typed
 * match, which requires the same form, then missed: the remembered choice
 * neither restored nor let Auto run, and playback started with subtitles off.
 * The server's track id is exact, so this is no metadata guess.
 */
private fun resolveSameServerTrackOrdinal(
    identity: SubtitleIdentity,
    subtitleTracks: List<PlayerSubtitleInfo>,
): Int? {
    if (!identity.isServerTrack()) return null
    val trackId = identity.subtitleMediaIdentityOrNull()?.trackId?.takeIf(String::isNotBlank) ?: return null
    return subtitleTracks.indices
        .filter { index ->
            val row = subtitleTracks[index]
            row.serverTrackId == trackId && mobileSubtitleIdentity(row).isServerTrack()
        }
        .singleOrNull()
}

private fun SubtitleIdentity.isServerTrack(): Boolean =
    this is SubtitleIdentity.Embedded ||
        this is SubtitleIdentity.ServerSidecar ||
        this is SubtitleIdentity.ServerBurnIn
