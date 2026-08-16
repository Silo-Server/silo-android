package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.playback.isBitmapSubtitleCodecFamily
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
 * 2026-07-09). Candidates are supplied in the caller's own iteration order and
 * carry a [AutoSubtitleCandidate.selectionIndex] in the server's COMBINED
 * selection space, so the winner can be handed straight to a playback start
 * request.
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
)

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
 * Within a pool: full-dialogue text → non-forced text → any text → first.
 * Bitmap tracks stay DEPRIORITISED, never excluded: a bitmap track that is the
 * only candidate still wins.
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
        ?: if (context.showForced) candidates.firstOrNull { it.forced } else null
    return target?.let(AutoSubtitleResolution::Select) ?: AutoSubtitleResolution.NoChange
}

private fun bestAutoSubtitleCandidate(
    candidates: List<AutoSubtitleCandidate>,
    targetLanguage: String?,
): AutoSubtitleCandidate? {
    val pool = if (targetLanguage == null) {
        candidates
    } else {
        candidates.filter { autoSubtitleLanguageKey(it.language) == targetLanguage }
    }
    if (pool.isEmpty()) return null

    pool.firstOrNull { !it.forced && !it.isHearingImpaired() && !it.isBitmap() }?.let { return it }
    pool.firstOrNull { !it.forced && !it.isBitmap() }?.let { return it }
    pool.firstOrNull { !it.isBitmap() }?.let { return it }
    return pool.first()
}

private fun bestForcedAutoSubtitleCandidate(
    candidates: List<AutoSubtitleCandidate>,
    targetLanguage: String?,
): AutoSubtitleCandidate? {
    val pool = candidates
        .filter { targetLanguage == null || autoSubtitleLanguageKey(it.language) == targetLanguage }
        .filter { it.forced }
    if (pool.isEmpty()) return null

    pool.firstOrNull { !it.isHearingImpaired() && !it.isBitmap() }?.let { return it }
    pool.firstOrNull { !it.isHearingImpaired() }?.let { return it }
    return pool.first()
}

/** The ONE SDH predicate: an explicit signal, or the track's own title. */
fun AutoSubtitleCandidate.isHearingImpaired(): Boolean =
    hearingImpaired || subtitleLabelIndicatesHearingImpaired(title)

/** The ONE bitmap predicate (PGS / VobSub / DVB / HDMV aliases). */
private fun AutoSubtitleCandidate.isBitmap(): Boolean = isBitmapSubtitleCodecFamily(codec)

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
): List<AutoSubtitleCandidate> = rows.map { row ->
    AutoSubtitleCandidate(
        selectionIndex = row.index,
        language = row.language,
        codec = row.codec,
        title = row.catalogLabel ?: row.label,
        forced = row.forced == true,
    )
}
