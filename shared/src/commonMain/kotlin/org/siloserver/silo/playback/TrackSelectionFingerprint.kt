package org.siloserver.silo.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.combinedSubtitleSelectionIndexes

const val SUBTITLE_OFF_FINGERPRINT = "off"
private const val TYPED_SUBTITLE_PREFERENCE_PREFIX = "silo-subtitle-v2:"

private val typedSubtitlePreferenceJson = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

@Serializable
private data class PersistedSubtitleIdentityV2(
    val kind: String,
    val serverIndex: Int? = null,
    val downloadId: Int? = null,
    val media: PersistedSubtitleMediaIdentityV2? = null,
)

@Serializable
private data class PersistedSubtitleMediaIdentityV2(
    val trackId: String? = null,
    val label: String? = null,
    val language: String? = null,
    val codecFamily: String? = null,
    val forced: Boolean? = null,
    val hearingImpaired: Boolean? = null,
)

fun encodeSubtitleIdentityPreference(identity: SubtitleIdentity): String =
    TYPED_SUBTITLE_PREFERENCE_PREFIX + typedSubtitlePreferenceJson.encodeToString(
        identity.toPersisted(),
    )

fun decodeSubtitleIdentityPreference(value: String?): SubtitleIdentity? {
    val encoded = value
        ?.trim()
        ?.takeIf { it.startsWith(TYPED_SUBTITLE_PREFERENCE_PREFIX) }
        ?.removePrefix(TYPED_SUBTITLE_PREFERENCE_PREFIX)
        ?: return null
    return runCatching {
        typedSubtitlePreferenceJson
            .decodeFromString<PersistedSubtitleIdentityV2>(encoded)
            .toIdentity()
    }.getOrNull()
}

fun encodeCatalogSubtitlePreference(
    tracks: List<SubtitleTrack>,
    selectedOrdinal: Int,
): String? {
    if (selectedOrdinal == -1) {
        return encodeSubtitleIdentityPreference(SubtitleIdentity.Off)
    }
    val track = tracks.getOrNull(selectedOrdinal) ?: return null
    val serverIndex = combinedSubtitleSelectionIndexes(tracks).getOrNull(selectedOrdinal)
        ?: return null
    val media = track.catalogMediaIdentity()
    val identity = when {
        // VobSub/DVB have no sidecar route and are burn-in wherever they live;
        // PGS keeps the normal embedded/external split because the server does
        // sidecar it as `.sup`.
        track.codec.isCatalogBitmapSubtitle() &&
            !isClientMountableBitmapCodecFamily(track.codec) ->
            SubtitleIdentity.ServerBurnIn(serverIndex, media)
        !track.external -> SubtitleIdentity.Embedded(
            serverIndex = serverIndex,
            media = media,
        )
        track.codec.isCatalogBitmapSubtitle() ->
            SubtitleIdentity.ServerBurnIn(serverIndex, media)
        else -> SubtitleIdentity.ServerSidecar(serverIndex, media)
    }
    return encodeSubtitleIdentityPreference(identity)
}

fun resolveCatalogSubtitlePreferenceOrdinal(
    tracks: List<SubtitleTrack>,
    preference: String?,
): Int? {
    val typed = decodeSubtitleIdentityPreference(preference)
    if (typed != null) {
        if (typed == SubtitleIdentity.Off) return -1
        val stableMedia = typed.catalogMediaIdentityOrNull()
        if (stableMedia != null) {
            if (!stableMedia.hasPositiveCatalogDiscriminator()) return null
            return tracks.indices
                .filter { ordinal ->
                    tracks[ordinal].matchesTypedCatalogIdentity(typed, stableMedia)
                }
                .singleOrNull()
        }
        val serverIndex = when (typed) {
            SubtitleIdentity.Off -> return -1
            is SubtitleIdentity.ServerSidecar -> typed.serverIndex
            is SubtitleIdentity.ServerBurnIn -> typed.serverIndex
            is SubtitleIdentity.Embedded -> typed.serverIndex
            is SubtitleIdentity.Downloaded,
            is SubtitleIdentity.LocalMedia3,
            -> return null
        }
        return combinedSubtitleSelectionIndexes(tracks)
            .indexOf(serverIndex)
            .takeIf { it >= 0 }
    }
    return resolveSubtitleTrackOrdinal(tracks, preference)
}

private fun SubtitleIdentity.toPersisted(): PersistedSubtitleIdentityV2 = when (this) {
    SubtitleIdentity.Off -> PersistedSubtitleIdentityV2(kind = "off")
    is SubtitleIdentity.ServerSidecar -> PersistedSubtitleIdentityV2(
        kind = "server_sidecar",
        serverIndex = serverIndex,
        media = media?.toPersisted(),
    )
    is SubtitleIdentity.ServerBurnIn -> PersistedSubtitleIdentityV2(
        kind = "server_burn_in",
        serverIndex = serverIndex,
        media = media?.toPersisted(),
    )
    is SubtitleIdentity.Embedded -> PersistedSubtitleIdentityV2(
        kind = "embedded",
        serverIndex = serverIndex,
        media = media.toPersisted(),
    )
    is SubtitleIdentity.Downloaded -> PersistedSubtitleIdentityV2(
        kind = "downloaded",
        downloadId = downloadId,
        media = media.toPersisted(),
    )
    is SubtitleIdentity.LocalMedia3 -> PersistedSubtitleIdentityV2(
        kind = "local_media3",
        media = media.toPersisted(),
    )
}

private fun SubtitleMediaIdentity.toPersisted(): PersistedSubtitleMediaIdentityV2 =
    PersistedSubtitleMediaIdentityV2(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codecFamily,
        forced = forced,
        hearingImpaired = hearingImpaired,
    )

private fun PersistedSubtitleIdentityV2.toIdentity(): SubtitleIdentity? {
    val mediaIdentity = media?.toIdentity()
    return when (kind) {
        "off" -> SubtitleIdentity.Off
        "server_sidecar" -> serverIndex?.let { index ->
            SubtitleIdentity.ServerSidecar(index, mediaIdentity)
        }
        "server_burn_in" -> serverIndex?.let { index ->
            SubtitleIdentity.ServerBurnIn(index, mediaIdentity)
        }
        "embedded" -> serverIndex?.let { index ->
            mediaIdentity?.let { SubtitleIdentity.Embedded(index, it) }
        }
        "downloaded" -> downloadId?.let { id ->
            mediaIdentity?.let { SubtitleIdentity.Downloaded(id, it) }
        }
        "local_media3" -> mediaIdentity?.let(SubtitleIdentity::LocalMedia3)
        else -> null
    }
}

private fun PersistedSubtitleMediaIdentityV2.toIdentity(): SubtitleMediaIdentity =
    SubtitleMediaIdentity(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codecFamily,
        forced = forced,
        hearingImpaired = hearingImpaired,
    )

fun audioTrackFingerprint(track: AudioTrack): String =
    trackSelectionFingerprint(
        index = track.index,
        language = track.language,
        codec = track.codec,
        title = track.title,
        forced = track.isDefault,
    )

fun subtitleTrackFingerprint(track: SubtitleTrack): String =
    trackSelectionFingerprint(
        index = track.index,
        language = track.language,
        codec = track.codec,
        title = track.title,
        forced = track.forced,
    )

fun subtitleTrackFingerprint(track: PlayerSubtitleInfo): String =
    trackSelectionFingerprint(
        index = track.index,
        language = track.language,
        codec = track.codec,
        title = track.label,
        forced = track.forced == true,
    )

fun resolveAudioTrackOrdinal(
    tracks: List<AudioTrack>,
    fingerprint: String?,
): Int? {
    val saved = fingerprint.normalizedFingerprintOrNull() ?: return null
    return tracks.indexOfFirst { audioTrackFingerprint(it) == saved }
        .takeIf { it >= 0 }
}

/**
 * Resolves the automatic audio-language setting onto the selected file's
 * catalog index before the playback plan is requested.
 *
 * Media3 still receives the language as a track-selector preference, but the
 * server must see the same ordinal so it evaluates the codec and channel
 * requirements of the track the player will actually select. When duplicate
 * rows share a language, prefer the file's default and otherwise keep catalog
 * order deterministic.
 */
fun resolvePreferredAudioTrackOrdinal(
    tracks: List<AudioTrack>,
    preferredLanguage: String?,
): Int? {
    val preferred = canonicalSubtitleLanguage(preferredLanguage) ?: return null
    val matches = tracks.withIndex().filter { (_, track) ->
        canonicalSubtitleLanguage(track.language) == preferred
    }
    return matches.firstOrNull { (_, track) -> track.isDefault }?.index
        ?: matches.firstOrNull()?.index
}

fun resolveSubtitleTrackOrdinal(
    tracks: List<SubtitleTrack>,
    fingerprint: String?,
): Int? {
    val saved = fingerprint.normalizedFingerprintOrNull() ?: return null
    if (saved == SUBTITLE_OFF_FINGERPRINT) return -1
    return tracks.indexOfFirst { subtitleTrackFingerprint(it) == saved }
        .takeIf { it >= 0 }
}

/**
 * Resolves a saved subtitle fingerprint against the MOUNTED subtitle list
 * ([PlayerSubtitleInfo]). The mobile player records selections with the
 * PlayerSubtitleInfo fingerprint overload (its uiState carries the mounted
 * list), so restoring them must match against the same list — the catalog
 * [SubtitleTrack] list uses a different index space (demux stream index vs
 * mounted ordinal) and its fingerprints never match. Distinct name because
 * a List overload would erase to the same JVM signature.
 */
fun resolveMountedSubtitleOrdinal(
    subtitles: List<PlayerSubtitleInfo>,
    fingerprint: String?,
): Int? {
    val saved = fingerprint.normalizedFingerprintOrNull() ?: return null
    if (saved == SUBTITLE_OFF_FINGERPRINT) return -1
    return subtitles.indexOfFirst { subtitleTrackFingerprint(it) == saved }
        .takeIf { it >= 0 }
}

fun trackSelectionFingerprint(
    index: Int,
    language: String?,
    codec: String?,
    title: String?,
    forced: Boolean,
): String = listOf(
    index.toString(),
    language.normalizedTrackField(lowercase = true),
    codec.normalizedTrackField(lowercase = true),
    title.normalizedTrackField(lowercase = false),
    forced.toString(),
).joinToString("|")

private fun String?.normalizedTrackField(lowercase: Boolean): String {
    val value = this
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
    return if (lowercase) value.lowercase() else value
}

private fun String?.normalizedFingerprintOrNull(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun String?.isCatalogBitmapSubtitle(): Boolean {
    val normalized = this
        ?.filter(Char::isLetterOrDigit)
        ?.lowercase()
        ?.takeIf(String::isNotBlank)
        ?: return false
    return normalized.contains("pgs") ||
        normalized.contains("dvd") ||
        normalized.contains("dvbsub") ||
        normalized.contains("vobsub")
}

private fun SubtitleTrack.catalogMediaIdentity(): SubtitleMediaIdentity =
    SubtitleMediaIdentity(
        label = title,
        language = canonicalSubtitleLanguage(language),
        codecFamily = canonicalSubtitleCodecFamily(codec),
        forced = forced,
        hearingImpaired = title
            ?.takeIf(::subtitleLabelIndicatesHearingImpaired)
            ?.let { true },
    )

private fun SubtitleIdentity.catalogMediaIdentityOrNull(): SubtitleMediaIdentity? = when (this) {
    is SubtitleIdentity.ServerSidecar -> media
    is SubtitleIdentity.ServerBurnIn -> media
    is SubtitleIdentity.Embedded -> media
    SubtitleIdentity.Off,
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> null
}

private fun SubtitleTrack.matchesTypedCatalogIdentity(
    identity: SubtitleIdentity,
    expected: SubtitleMediaIdentity,
): Boolean {
    val kindMatches = when (identity) {
        is SubtitleIdentity.ServerSidecar ->
            external && !codec.isCatalogBitmapSubtitle()
        // Burn-in covers external bitmaps plus the embedded families with no
        // sidecar route; embedded PGS stays on the Embedded side, so the two
        // remain disjoint.
        is SubtitleIdentity.ServerBurnIn ->
            codec.isCatalogBitmapSubtitle() &&
                (external || !isClientMountableBitmapCodecFamily(codec))
        is SubtitleIdentity.Embedded ->
            !external &&
                (!codec.isCatalogBitmapSubtitle() || isClientMountableBitmapCodecFamily(codec))
        SubtitleIdentity.Off,
        is SubtitleIdentity.Downloaded,
        is SubtitleIdentity.LocalMedia3,
        -> false
    }
    if (!kindMatches) return false
    val actual = catalogMediaIdentity()
    if (
        expected.trackId.normalizedTrackField(lowercase = false).isNotEmpty() &&
        actual.trackId.normalizedTrackField(lowercase = false) !=
        expected.trackId.normalizedTrackField(lowercase = false)
    ) {
        return false
    }
    if (
        expected.label.normalizedTrackField(lowercase = true).isNotEmpty() &&
        actual.label.normalizedTrackField(lowercase = true) !=
        expected.label.normalizedTrackField(lowercase = true)
    ) {
        return false
    }
    val expectedLanguage = canonicalSubtitleLanguage(expected.language)
    if (
        expectedLanguage != null &&
        canonicalSubtitleLanguage(actual.language) != expectedLanguage
    ) {
        return false
    }
    if (
        canonicalSubtitleCodecFamily(expected.codecFamily) != null &&
        canonicalSubtitleCodecFamily(actual.codecFamily) !=
        canonicalSubtitleCodecFamily(expected.codecFamily)
    ) {
        return false
    }
    if (expected.forced != null && actual.forced != expected.forced) return false
    if (
        expected.hearingImpaired != null &&
        actual.hearingImpaired != expected.hearingImpaired
    ) {
        return false
    }
    return true
}

private fun SubtitleMediaIdentity.hasPositiveCatalogDiscriminator(): Boolean =
    !trackId.isNullOrBlank() ||
        !label.isNullOrBlank() ||
        canonicalSubtitleLanguage(language) != null ||
        !codecFamily.isNullOrBlank() ||
        forced == true ||
        hearingImpaired == true
