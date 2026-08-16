package org.siloserver.silo.tv.ui.screens.player

import kotlinx.coroutines.CancellationException
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.isLocalDownloadedSubtitle
import org.siloserver.silo.model.playback.rebaseDownloadedSubtitleUrl
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT
import org.siloserver.silo.playback.decodeSubtitleIdentityPreference
import org.siloserver.silo.playback.encodeCatalogSubtitlePreference
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.playback.matchesSubtitleMediaIdentity
import org.siloserver.silo.playback.resolveCatalogSubtitlePreferenceOrdinal
import org.siloserver.silo.playback.resolveDownloadedSubtitlePreferenceOrdinal
import org.siloserver.silo.playback.resolveAudioTrackOrdinal
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate

internal data class TvFreshSubtitlePreferenceResolution(
    val identity: SubtitleIdentity,
    val migratedPreference: String? = null,
)

internal data class TvFreshSubtitleRestoreResult(
    val rows: List<PlayerSubtitleInfo>,
    val resolution: TvFreshSubtitlePreferenceResolution?,
)

/**
 * Resolves persisted state against the current playback metadata. Raw indexes
 * are never trusted across sessions; a typed catalog identity is rebuilt with
 * the current combined server index after its stable metadata matches.
 */
internal fun resolveTvFreshSubtitlePreference(
    preference: String?,
    catalogTracks: List<SubtitleTrack>,
    hydratedRows: List<PlayerSubtitleInfo>,
): TvFreshSubtitlePreferenceResolution? {
    val saved = preference?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val typed = decodeSubtitleIdentityPreference(saved)

    if (typed == SubtitleIdentity.Off) {
        return TvFreshSubtitlePreferenceResolution(SubtitleIdentity.Off)
    }
    if (typed == null && saved == SUBTITLE_OFF_FINGERPRINT) {
        return TvFreshSubtitlePreferenceResolution(
            identity = SubtitleIdentity.Off,
            migratedPreference = encodeSubtitleIdentityPreference(SubtitleIdentity.Off),
        )
    }

    if (typed is SubtitleIdentity.ServerSidecar ||
        typed is SubtitleIdentity.ServerBurnIn ||
        typed is SubtitleIdentity.Embedded
    ) {
        val authoritativeMatches = hydratedRows.filter { row ->
            tvSubtitleIdentity(row) == typed
        }
        if (authoritativeMatches.size == 1) {
            return TvFreshSubtitlePreferenceResolution(
                tvSubtitleIdentity(authoritativeMatches.single()),
            )
        }
        val ordinal = resolveCatalogSubtitlePreferenceOrdinal(catalogTracks, saved) ?: return null
        val rebuilt = encodeCatalogSubtitlePreference(catalogTracks, ordinal)
            ?.let(::decodeSubtitleIdentityPreference)
            ?: return null
        return TvFreshSubtitlePreferenceResolution(rebuilt)
    }

    if (typed is SubtitleIdentity.Downloaded) {
        val ordinal = resolveDownloadedSubtitlePreferenceOrdinal(typed, hydratedRows)
            ?: return null
        val rebuilt = tvSubtitleIdentity(hydratedRows[ordinal])
        return TvFreshSubtitlePreferenceResolution(
            identity = rebuilt,
            migratedPreference = encodeSubtitleIdentityPreference(rebuilt)
                .takeIf { rebuilt != typed },
        )
    }

    if (typed is SubtitleIdentity.LocalMedia3) {
        val localRows = hydratedRows.filter {
            tvSubtitleIdentity(it) is SubtitleIdentity.LocalMedia3
        }
        if (localRows.isEmpty()) {
            // Player-only Media3 tracks do not exist in the fresh server
            // response. Preserve the typed intent so the adapter can arm an
            // exact remount owner for the first Media3 track snapshot.
            return TvFreshSubtitlePreferenceResolution(typed)
        }
        val candidates = localRows.filter { row ->
            row.tvMediaIdentity().matchesSubtitleMediaIdentity(typed.media)
        }
        if (candidates.size != 1) return null
        return TvFreshSubtitlePreferenceResolution(typed)
    }

    // A non-typed value is the legacy fingerprint. Resolve it uniquely, then
    // immediately return the typed value that should replace it durably.
    val catalogMatches = catalogTracks.indices.filter { ordinal ->
        subtitleTrackFingerprint(catalogTracks[ordinal]) == saved
    }
    if (catalogMatches.size == 1) {
        val rebuilt = encodeCatalogSubtitlePreference(catalogTracks, catalogMatches.single())
            ?.let(::decodeSubtitleIdentityPreference)
            ?: return null
        return TvFreshSubtitlePreferenceResolution(
            identity = rebuilt,
            migratedPreference = encodeSubtitleIdentityPreference(rebuilt),
        )
    }

    val hydratedMatches = hydratedRows.filter { subtitleTrackFingerprint(it) == saved }
    if (hydratedMatches.size != 1) return null
    val rebuilt = tvSubtitleIdentity(hydratedMatches.single())
    return TvFreshSubtitlePreferenceResolution(
        identity = rebuilt,
        migratedPreference = encodeSubtitleIdentityPreference(rebuilt),
    )
}

/**
 * Hydration and restore resolution are one owned publication unit. A stale
 * load can finish its network call, but it cannot return rows or an intent.
 */
@Suppress("UNUSED_PARAMETER")
internal suspend fun resolveOwnedTvFreshSubtitleRestore(
    owner: TvPlayerLoadOwner,
    registry: TvPlayerLoadOwnerRegistry,
    preference: String?,
    catalogTracks: List<SubtitleTrack>,
    initialRows: List<PlayerSubtitleInfo>,
    sessionId: String,
    serverUrl: String,
    hydrateDownloadedRows: suspend () -> ApiResult<List<PlayerSubtitleInfo>>,
): TvFreshSubtitleRestoreResult? {
    if (!registry.owns(owner)) return null
    val hydration = try {
        hydrateDownloadedRows()
    } catch (cancellation: CancellationException) {
        throw cancellation
    }
    if (!registry.owns(owner)) return null

    return when (hydration) {
        is ApiResult.Success -> {
            val retained = initialRows.filterNot(PlayerSubtitleInfo::isDownloadedTvPolicyRow)
            val downloaded = hydration.data.map { row ->
                if (row.isDownloadedTvPolicyRow()) {
                    row.copy(url = rebaseDownloadedSubtitleUrl(row.url, sessionId))
                } else {
                    row
                }
            }
            // Hydration returns the full merged set, not downloads alone.
            // Prefer its rebased rows and deduplicate by the server index used
            // by picker identities and replans.
            val rows = (downloaded + retained).distinctBy(PlayerSubtitleInfo::index)
            TvFreshSubtitleRestoreResult(
                rows = rows,
                resolution = resolveTvFreshSubtitlePreference(
                    preference = preference,
                    catalogTracks = catalogTracks,
                    hydratedRows = rows,
                ),
            )
        }
        is ApiResult.Error,
        is ApiResult.NetworkError,
        -> TvFreshSubtitleRestoreResult(rows = initialRows, resolution = null)
    }.takeIf { registry.owns(owner) }
}

internal class TvPlayerMutationFence(
    private val registry: TvPlayerLoadOwnerRegistry,
    private val invalidateTransactions: () -> Unit,
) {
    fun beginLoad(
        contentId: String,
        preferredFileId: Int?,
        preferredQuality: String?,
    ): TvPlayerLoadOwner {
        return registry.begin(contentId, preferredFileId, preferredQuality)
    }

    fun owns(owner: TvPlayerLoadOwner): Boolean = registry.owns(owner)

    /**
     * The inverse side of the load/replan fence. A user mutation against the
     * currently mounted session wins over an unpublished replacement load.
     */
    fun beginReplan() {
        registry.invalidate()
    }

    fun invalidateAll() {
        registry.invalidate()
        invalidateTransactions()
    }
}

internal fun beginTvReplacementLoad(
    state: TvPlayerViewModel.UiState,
): TvPlayerViewModel.UiState = state.copy(
    isLoading = false,
    error = null,
    serverUnreachable = false,
    subtitleFailureMessage = null,
)

internal fun failTvReplacementLoad(
    state: TvPlayerViewModel.UiState,
    message: String,
): TvPlayerViewModel.UiState = state.copy(
    isLoading = false,
    error = null,
    serverUnreachable = false,
    subtitleFailureMessage = message,
)

internal suspend fun stopReplacedTvSessionAfterPublication(
    replacedSessionId: String?,
    publishedSessionId: String?,
    stopSession: suspend (String) -> Unit,
) {
    val stale = replacedSessionId
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { it == publishedSessionId }
        ?: return
    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        runCatching { stopSession(stale) }
    }
}

internal fun tvAudioTrackPersistenceUpdate(
    committedAudioTrackIndex: Int?,
    audioTracks: List<AudioTrack>,
): TrackSelectionFingerprintUpdate =
    // An ORDINAL into audioTracks: audio carries no index on the wire, so
    // matching on AudioTrack.index found nothing for any ordinal above 0 and
    // silently Preserved — the chosen track was never persisted, so reopening
    // the item lost it.
    committedAudioTrackIndex
        ?.let(audioTracks::getOrNull)
        ?.let(::audioTrackFingerprint)
        ?.let(TrackSelectionFingerprintUpdate::Set)
        ?: TrackSelectionFingerprintUpdate.Preserve

/**
 * Whether a commit may write the durable per-item subtitle preference.
 *
 * The transaction adapter cannot tell an automatic pick from a viewer's choice
 * once it is committed — both arrive at the persistence port as the same
 * [SubtitleIdentity] — so the caller carries [automaticIdentity]: the identity
 * the APP selected on the viewer's behalf, if it is still the committed one. An
 * automatic pick must never be written back as though the viewer had made it,
 * or every later launch would "restore" a choice nobody made.
 */
internal fun tvSubtitlePersistenceUpdate(
    committedIdentity: SubtitleIdentity,
    automaticIdentity: SubtitleIdentity?,
): TrackSelectionFingerprintUpdate =
    if (automaticIdentity != null && committedIdentity == automaticIdentity) {
        TrackSelectionFingerprintUpdate.Preserve
    } else {
        TrackSelectionFingerprintUpdate.Set(
            encodeSubtitleIdentityPreference(committedIdentity),
        )
    }

/**
 * Safety net for a text track selected by something that is not the subtitle
 * transaction adapter — device caption settings, a selector quirk, a renderer
 * default. Returns the identity to adopt, or null when there is nothing to
 * reconcile.
 *
 * This is NOT the mechanism by which subtitles get selected; reaching a
 * non-null result means an authority we believed removed is still acting, which
 * is why the caller logs it loudly. It deliberately stands down while anything
 * is in flight: mid-transaction the track list is being republished and the
 * pending identity is about to become the committed one, so "disagreement"
 * there is just latency, not a second authority.
 */
internal fun tvExternalSubtitleAdoption(
    subtitleTracks: List<PlayerTrackEntry>,
    subtitleRows: List<PlayerSubtitleInfo>,
    committedIdentity: SubtitleIdentity,
    pendingIdentity: SubtitleIdentity?,
    selectionInFlight: Boolean,
): SubtitleIdentity? {
    if (selectionInFlight || pendingIdentity != null) return null
    val selected = subtitleTracks.firstOrNull { it.isSelected } ?: return null
    return tvMountedSubtitleIdentity(selected, subtitleTracks, subtitleRows)
        .takeIf { it != committedIdentity }
}

@Suppress("UNUSED_PARAMETER")
internal fun authoritativeTvSubtitleRows(
    snapshotRows: List<PlayerSubtitleInfo>,
    previousRows: List<PlayerSubtitleInfo>,
): List<PlayerSubtitleInfo> = snapshotRows

internal fun tvDownloadedRefreshIdentity(
    row: PlayerSubtitleInfo,
): SubtitleIdentity? =
    (tvSubtitleIdentity(row) as? SubtitleIdentity.Downloaded)
        ?.takeIf { row.downloadId != null }

internal fun resolveTvRemoteSubtitleIntent(
    playerOrdinal: Int,
    subtitleTracks: List<PlayerTrackEntry>,
    subtitleRows: List<PlayerSubtitleInfo>,
): SubtitleIdentity? {
    if (playerOrdinal == -1) return SubtitleIdentity.Off
    val mounted = subtitleTracks.singleOrNull { it.index == playerOrdinal } ?: return null
    return resolveMountedSubtitleRow(mounted, subtitleTracks, subtitleRows)
        ?.let(::tvSubtitleIdentity)
}

/**
 * Remote `set_audio_track` carries an ordinal, and the server addresses audio
 * by ordinal too, so this is an identity mapping guarded by range. It used to
 * read `.index`, which audio never carries, so every remote pick requested 0.
 */
internal fun resolveTvRemoteAudioIntent(
    playerOrdinal: Int,
    audioTracks: List<AudioTrack>,
): Int? = playerOrdinal.takeIf { it in audioTracks.indices }

private fun PlayerSubtitleInfo.isDownloadedTvPolicyRow(): Boolean =
    isLocalDownloadedSubtitle()

private fun PlayerSubtitleInfo.tvMediaIdentity(): SubtitleMediaIdentity = when (
    val identity = tvSubtitleIdentity(this)
) {
    is SubtitleIdentity.ServerSidecar -> identity.media ?: SubtitleMediaIdentity()
    is SubtitleIdentity.ServerBurnIn -> identity.media ?: SubtitleMediaIdentity()
    is SubtitleIdentity.Embedded -> identity.media
    is SubtitleIdentity.Downloaded -> identity.media
    is SubtitleIdentity.LocalMedia3 -> identity.media
    SubtitleIdentity.Off -> SubtitleMediaIdentity()
}
