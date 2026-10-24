package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.MountedSubtitleTrack
import org.siloserver.silo.common.player.isSubtitleArtifactTrackId
import org.siloserver.silo.common.player.subtitleArtifactTrackId
import org.siloserver.silo.model.download.OfflineSubtitleFile
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitlePreferenceUpdate
import org.siloserver.silo.playback.canonicalSubtitleCodecFamily
import org.siloserver.silo.playback.isBitmapSubtitleCodecFamily
import org.siloserver.silo.playback.isClientMountableBitmapCodecFamily
import org.siloserver.silo.playback.orNullIfBlank

/**
 * Menu indexes for text tracks found inside a downloaded file. Kept clear of
 * the sidecar rows' indexes (0 until sidecar count), which also key the
 * sidecars' Media3 track ids (`silo-subtitle:<index>`).
 */
internal const val OFFLINE_EMBEDDED_SUBTITLE_INDEX_BASE = 10_000

/** Subtitle preferences applied to offline playback, as online playback resolves them. */
internal data class OfflineSubtitlePreferences(
    val preferredLanguage: String?,
    val mode: String?,
    val showForced: Boolean,
    /** The series' remembered track. */
    val trackSignature: org.siloserver.silo.model.playback.SubtitleTrackSignature? = null,
)

/**
 * These preferences under the series' saved subtitle [row]. An empty language
 * or mode in the row means no preference at that level, as on the server, so
 * the inherited one stands; Off is the mode `off`. An omitted forced override
 * keeps the inherited setting.
 */
internal fun OfflineSubtitlePreferences.withSeriesRow(row: SubtitlePreferenceUpdate) = copy(
    preferredLanguage = row.subtitleLanguage.orNullIfBlank() ?: preferredLanguage,
    mode = row.subtitleMode.orNullIfBlank() ?: mode,
    showForced = row.showForcedSubtitles ?: showForced,
    trackSignature = row.trackSignature,
)

/**
 * Menu rows for the subtitle sidecars saved with a download. Each row's
 * [PlayerSubtitleInfo.index] is its position here, which the mount turns into
 * the sidecar's Media3 track id, so selection resolves by that exact id.
 * Files that have gone missing are left out.
 */
internal fun offlineSidecarSubtitleRows(
    files: List<OfflineSubtitleFile>,
    fileExists: (String) -> Boolean = { java.io.File(it).isFile },
): List<PlayerSubtitleInfo> =
    files.filter { fileExists(it.path) }.mapIndexed { index, file ->
        PlayerSubtitleInfo(
            index = index,
            language = file.language,
            codec = file.format,
            label = file.title ?: if (file.hearingImpaired) "SDH" else null,
            source = "external",
            forced = file.forced,
            hearingImpaired = file.hearingImpaired,
            url = "file://" + java.io.File(file.path).toURI().rawPath,
            // The id the mount gives this sidecar; also keeps two otherwise
            // identical sidecars distinct when the menu resolves a selection.
            mediaTrackId = subtitleArtifactTrackId(index),
        )
    }

/**
 * Menu rows for the text tracks inside the downloaded file itself (MP4 timed
 * text in a prepared download; any embedded track of an original download).
 *
 * The file's own default flag is deliberately not carried: a prepared MP4
 * marks its first subtitle track default whether or not it should show, so
 * only the forced flag and the viewer's preferences may turn one on.
 *
 * [tracks] are the renderable ones (renderableMountedTextTracks). Merged
 * sidecars and bitmap families the player cannot mount are left out.
 */
internal fun localEmbeddedSubtitleRows(tracks: List<MountedSubtitleTrack>): List<PlayerSubtitleInfo> =
    tracks
        .filter { track ->
            val family = canonicalSubtitleCodecFamily(track.codec)
            track.trackId != null &&
                !isSubtitleArtifactTrackId(track.trackId) &&
                (!isBitmapSubtitleCodecFamily(family) || isClientMountableBitmapCodecFamily(family))
        }
        .mapIndexed { ordinal, track ->
            PlayerSubtitleInfo(
                index = OFFLINE_EMBEDDED_SUBTITLE_INDEX_BASE + ordinal,
                language = track.language,
                codec = track.codec,
                label = track.label,
                source = "embedded",
                forced = track.forced,
                hearingImpaired = track.hearingImpaired,
                url = "",
                mediaTrackId = track.trackId,
            )
        }

/** A row [localEmbeddedSubtitleRows] produced. */
internal fun PlayerSubtitleInfo.isLocalEmbeddedSubtitleRow(): Boolean =
    source == "embedded" && url.isBlank() && index >= OFFLINE_EMBEDDED_SUBTITLE_INDEX_BASE

/**
 * A row that exists only in offline playback: a track inside the downloaded
 * file or a sidecar saved on the device. Its index is a local menu position,
 * never a server subtitle index.
 */
internal fun PlayerSubtitleInfo.isOfflineLocalSubtitleRow(): Boolean =
    isLocalEmbeddedSubtitleRow() || url.startsWith("file://")
