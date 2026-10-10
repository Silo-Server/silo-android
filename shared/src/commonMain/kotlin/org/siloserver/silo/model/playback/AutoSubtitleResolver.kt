package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.playback.isBitmapSubtitleCodecFamily
import org.siloserver.silo.playback.isClientMountableBitmapCodecFamily
import org.siloserver.silo.playback.playbackSubtitleIdentity
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired

/**
 * The ONE subtitle auto-selection resolver.
 *
 * The detail page's "Auto - <track>" preview and the player's no-handoff
 * fallback used to be two independent implementations (plus a third on phone),
 * with divergent inventories, SDH detection, bitmap detection and language
 * folding. They disagreed in the field: the detail row previewed an external
 * SRT while playback started on an embedded PGS track, because the player only
 * ever ranked tracks Media3 had already mounted.
 *
 * The DETAIL PAGE's semantics are the reference behaviour — its ordering and
 * cascade are what the viewer sees and what QA signed off (tvOS parity, QA
 * 2026-07-09). Candidates carry a [AutoSubtitleCandidate.selectionIndex] in the
 * server's COMBINED selection space, so the winner can be handed straight to a
 * playback start request, and their own [AutoSubtitleCandidate.source], so the
 * caller's iteration order (catalog: embedded first; inventory: externals
 * first) never decides between two otherwise equal tracks.
 */
data class AutoSubtitleCandidate(
    /**
     * COMBINED-space selection index (externals first, embedded after) — the
     * identity `subtitle_track_index` requests and session `subtitle_urls`
     * resolve against. Callers that only need an ordinal (the detail preview)
     * may put their own ordinal here; the resolver never interprets it.
     */
    val selectionIndex: Int,
    val language: String? = null,
    val codec: String? = null,
    /** Catalog/track title. Feeds the SDH predicate alongside [hearingImpaired]. */
    val title: String? = null,
    val forced: Boolean = false,
    /**
     * A hearing-impaired signal the caller already knows (Media3 role flags, an
     * accessibility label). ORed with a title match — never a replacement for
     * it, because the catalog only ever says SDH in the title.
     */
    val hearingImpaired: Boolean = false,
    /** Where the track lives. `null` (unknown) ranks with embedded, as on the web player. */
    val source: AutoSubtitleSource? = null,
    /**
     * Whether showing this track makes the server burn it into the picture for
     * THIS device. `null` when the caller cannot tell, which falls back to
     * treating every bitmap track as a burn-in.
     */
    val needsBurnIn: Boolean? = null,
)

/** Track provenance, in auto-selection preference order. */
enum class AutoSubtitleSource { EMBEDDED, EXTERNAL, DOWNLOADED }

/** Cascaded preference inputs. Same shape on every surface. */
data class AutoSubtitleContext(
    /** Cascaded `subtitle_language`. `null` = no preference; empty = "no subs". */
    val preferredLanguage: String?,
    /** Cascaded `subtitle_mode`. `null`/blank → "auto". */
    val mode: String?,
    /** Whether forced subs should be auto-selected when available. */
    val showForced: Boolean = false,
    /** Language of the audio track that will play. */
    val audioLanguage: String? = null,
)

sealed class AutoSubtitleResolution {
    /** Auto picked nothing, and nothing needs turning off. */
    data object NoChange : AutoSubtitleResolution()

    /** Auto decided subtitles must be off. */
    data object Disable : AutoSubtitleResolution()

    data class Select(val candidate: AutoSubtitleCandidate) : AutoSubtitleResolution()
}

/** The chosen candidate, or null when Auto resolves to no subtitle at all. */
fun AutoSubtitleResolution.selectedCandidate(): AutoSubtitleCandidate? =
    (this as? AutoSubtitleResolution.Select)?.candidate

/**
 * Resolves the track Auto should start with.
 *
 * Cascade (unchanged from the detail page):
 * mode `off` / an explicitly empty preferred language → off; no preferred
 * language → only mode `always` picks anything; audio already in the preferred
 * language under mode `auto` → off, or the language's forced track when forced
 * subs are enabled; otherwise the best track in the preferred language, falling
 * back to any forced track when forced subs are enabled.
 *
 * Within a pool, [autoSubtitlePreferenceOrder] ranks the tracks; a track that
 * needs a burn-in is DEPRIORITISED, never excluded, so it still wins when it is
 * the only candidate.
 *
 * "Show forced subtitles" is a SEPARATE setting and never outranks the
 * viewer's full-subtitle preference: when subtitles are wanted (mode `always`,
 * or `auto` with foreign audio) the full-dialogue track wins and a forced
 * track is only the last resort when the language has nothing else. Forced
 * leads only in the branch where subtitles would otherwise be OFF (audio
 * already in the preferred language). Product owner call, 2026-08-16: an
 * "English – Always" profile with forced enabled was starting on the Forced
 * track of a disc that also carried a plain English track.
 */
fun resolveAutoSubtitle(
    candidates: List<AutoSubtitleCandidate>,
    context: AutoSubtitleContext,
): AutoSubtitleResolution {
    if (candidates.isEmpty()) return AutoSubtitleResolution.NoChange

    val mode = context.mode?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "auto"
    if (mode == "off") return AutoSubtitleResolution.Disable

    val preferred = context.preferredLanguage
    if (preferred != null && preferred.isBlank()) return AutoSubtitleResolution.Disable

    val targetLanguage = autoSubtitleLanguageKey(preferred)
    if (targetLanguage == null) {
        if (mode != "always") return AutoSubtitleResolution.NoChange
        return bestAutoSubtitleCandidate(candidates, null)
            ?.let(AutoSubtitleResolution::Select)
            ?: AutoSubtitleResolution.NoChange
    }

    val audioLanguage = autoSubtitleLanguageKey(context.audioLanguage)
    if (mode == "auto" && audioLanguage != null && audioLanguage == targetLanguage) {
        if (context.showForced) {
            bestForcedAutoSubtitleCandidate(candidates, targetLanguage)
                // Idempotent re-select even when this track is already on:
                // NoChange is reserved for "no track should be on", so a
                // launch-time consumer can map it to an explicit disable
                // without turning off a forced track the defaults picked.
                ?.let { return AutoSubtitleResolution.Select(it) }
        }
        return AutoSubtitleResolution.Disable
    }

    val target = bestAutoSubtitleCandidate(candidates, targetLanguage)
        ?: if (context.showForced) {
            candidates.filter { it.forced }.minWithOrNull(autoSubtitlePreferenceOrder)
        } else {
            null
        }
    return target?.let(AutoSubtitleResolution::Select) ?: AutoSubtitleResolution.NoChange
}

/**
 * Preference order between tracks that already match the wanted language.
 * Each tier only breaks ties in the one before it (server/web parity,
 * silo-server #1849):
 * 1. a track this device renders itself beats one the server must burn in;
 * 2. full dialogue beats forced, and plain beats SDH, so a file's own forced
 *    or SDH track never displaces the full track the viewer asked for;
 * 3. embedded beats external beats downloaded. External sidecars are the ones
 *    that drift out of sync.
 * Use with [minWithOrNull], which keeps the caller's order for full ties.
 */
val autoSubtitlePreferenceOrder: Comparator<AutoSubtitleCandidate> = compareBy(
    { it.requiresBurnIn() },
    { it.forced },
    { it.isHearingImpaired() },
    { it.source?.ordinal ?: 0 },
)

private fun bestAutoSubtitleCandidate(
    candidates: List<AutoSubtitleCandidate>,
    targetLanguage: String?,
): AutoSubtitleCandidate? {
    val pool = if (targetLanguage == null) {
        candidates
    } else {
        candidates.filter { autoSubtitleLanguageKey(it.language) == targetLanguage }
    }
    return pool.minWithOrNull(autoSubtitlePreferenceOrder)
}

private fun bestForcedAutoSubtitleCandidate(
    candidates: List<AutoSubtitleCandidate>,
    targetLanguage: String?,
): AutoSubtitleCandidate? =
    candidates
        .filter { targetLanguage == null || autoSubtitleLanguageKey(it.language) == targetLanguage }
        .filter { it.forced }
        .minWithOrNull(autoSubtitlePreferenceOrder)

/** The ONE SDH predicate: an explicit signal, or the track's own title. */
fun AutoSubtitleCandidate.isHearingImpaired(): Boolean =
    hearingImpaired || subtitleLabelIndicatesHearingImpaired(title)

/**
 * Whether this track ranks as a burn-in: the caller's answer for this device,
 * or, when it has none, the bitmap predicate (PGS / VobSub / DVB / HDMV
 * aliases).
 */
private fun AutoSubtitleCandidate.requiresBurnIn(): Boolean =
    needsBurnIn ?: isBitmapSubtitleCodecFamily(codec)

/**
 * The ONE ISO-639 folding table for auto-selection language comparison.
 *
 * Deliberately smaller than the display-name alias table and deliberately
 * drops `und`: it answers "is this the language the viewer asked for", not
 * "what do we call this language".
 */
fun autoSubtitleLanguageKey(language: String?): String? {
    val primary = language
        ?.trim()
        ?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
        ?.lowercase()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?: return null
    return when (primary) {
        "eng" -> "en"
        "spa" -> "es"
        "fre", "fra" -> "fr"
        "ger", "deu" -> "de"
        "dut", "nld" -> "nl"
        "jpn" -> "ja"
        "dan" -> "da"
        else -> primary
    }
}

/**
 * Candidates over the CATALOG subtitle list, in catalog order, addressed in
 * combined selection space — the inventory the detail page previews and the
 * one a playback start request can act on.
 *
 * Burn-in follows the server's delivery rule for the capabilities Android
 * declares on every delivery (embedded and sidecar bitmap): text and an
 * embedded PGS track reach the device as-is, while VobSub, DVB and any
 * external bitmap file have no client route and are burned in.
 */
fun catalogAutoSubtitleCandidates(
    catalogTracks: List<SubtitleTrack>,
): List<AutoSubtitleCandidate> {
    val combined = combinedSubtitleSelectionIndexes(catalogTracks)
    return catalogTracks.mapIndexed { ordinal, track ->
        AutoSubtitleCandidate(
            selectionIndex = combined[ordinal],
            language = track.language,
            codec = track.codec,
            title = track.title,
            forced = track.forced,
            source = if (track.external) AutoSubtitleSource.EXTERNAL else AutoSubtitleSource.EMBEDDED,
            needsBurnIn = isBitmapSubtitleCodecFamily(track.codec) &&
                (track.external || !isClientMountableBitmapCodecFamily(track.codec)),
        )
    }
}

/**
 * Candidates over the SERVER subtitle inventory (`subtitle_urls`), which
 * includes external sidecars the player has not mounted yet. Ranking an
 * unmounted sidecar is the point: resolving over Media3's mounted text tracks
 * alone made every external row structurally invisible.
 */
fun inventoryAutoSubtitleCandidates(
    rows: List<PlayerSubtitleInfo>,
): List<AutoSubtitleCandidate> = rows.map(PlayerSubtitleInfo::toAutoSubtitleCandidate)

/**
 * One session subtitle row as a resolver candidate. Burn-in comes from the
 * row's own playback identity, which reads the server's `burn_in_only`
 * delivery and otherwise applies the same rule as [catalogAutoSubtitleCandidates].
 */
fun PlayerSubtitleInfo.toAutoSubtitleCandidate(): AutoSubtitleCandidate =
    AutoSubtitleCandidate(
        selectionIndex = index,
        language = language,
        codec = codec,
        title = catalogLabel ?: label,
        forced = forced == true,
        source = autoSubtitleSource(),
        needsBurnIn = playbackSubtitleIdentity(this) is SubtitleIdentity.ServerBurnIn,
    )

private fun PlayerSubtitleInfo.autoSubtitleSource(): AutoSubtitleSource? {
    if (isLocalDownloadedSubtitle()) return AutoSubtitleSource.DOWNLOADED
    return when ((catalogSource ?: source)?.trim()?.lowercase()) {
        "embedded" -> AutoSubtitleSource.EMBEDDED
        "external" -> AutoSubtitleSource.EXTERNAL
        SUBTITLE_SOURCE_DOWNLOADED -> AutoSubtitleSource.DOWNLOADED
        else -> null
    }
}
