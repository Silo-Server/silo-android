package org.siloserver.silo.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.siloserver.silo.metadata.DescriptionTranslationController
import org.siloserver.silo.metadata.DescriptionTranslationPhase
import org.siloserver.silo.model.catalog.hasMachineTranslatedOverview
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.metadata.MetadataAiOnView
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.MetadataAiRepository

/**
 * How the focus marquee shows the rested item's description translation:
 * the landed text (if any), whether it is machine-translated, and whether a
 * translation of this item is running. Applied only to the item it names, so
 * one card's progress never shows on another.
 */
data class TvMarqueeTranslation(
    val contentId: String,
    val synopsis: String?,
    val machineTranslated: Boolean,
    val translating: Boolean,
)

/**
 * On-view description translation for the focus marquee, the Android TV
 * counterpart of the web Featured hero: in `auto` mode a Featured-section item
 * the viewer rests on translates once per (item, language), then its detail is re-read
 * until the server reports the description available. The marquee is never
 * focusable, so `button` mode leaves the action to the item's detail page.
 */
@Stable
internal class TvMarqueeTranslationState(
    private val repository: MetadataAiRepository,
    private val scope: CoroutineScope,
) {
    private val controller = DescriptionTranslationController(
        repository = repository,
        delayMs = { delay(it) },
    )
    val phase = controller.phase
    val runningContentId = controller.runningContentId

    private data class Landed(val overview: String?, val machineTranslated: Boolean, val pending: String?)

    /** Descriptions re-read while this feed was on screen, by content id. */
    private val landed = mutableStateMapOf<String, Landed>()

    fun pendingLanguage(item: SectionItem): String? =
        if (item.contentId in landed) landed[item.contentId]?.pending
        else item.pendingTranslationLanguage?.takeIf { it.isNotBlank() }

    fun translateOnView(item: SectionItem, onLanded: () -> Unit) {
        val contentId = item.contentId
        val target = pendingLanguage(item) ?: return
        // One translation at a time; an item skipped while another runs is
        // tried again the next time the viewer rests on it.
        if (!controller.claimAutoFire(contentId, target)) return
        controller.resetFailure()
        scope.launch {
            controller.translate(
                contentId = contentId,
                targetLanguage = target,
                refetchPendingLanguage = { owner ->
                    when (val result = repository.refreshDetail(contentId, owner)) {
                        is ApiResult.Success -> if (repository.isCurrent(owner)) {
                            val detail = result.data
                            landed[contentId] = Landed(
                                overview = detail.overview?.takeIf { it.isNotBlank() },
                                machineTranslated = hasMachineTranslatedOverview(detail.machineTranslatedFields),
                                pending = detail.pendingTranslationLanguage?.takeIf { it.isNotBlank() },
                            )
                            landed[contentId]?.pending
                        } else {
                            target
                        }
                        else -> target // transient refetch failure: keep polling
                    }
                },
                // The rows still hold the untranslated card; re-read them so
                // the translation survives this feed leaving composition.
                onTranslated = { onLanded() },
            )
        }
    }

    fun presentation(item: SectionItem, phase: DescriptionTranslationPhase, runningId: String?): TvMarqueeTranslation {
        val update = landed[item.contentId]
        return TvMarqueeTranslation(
            contentId = item.contentId,
            synopsis = update?.overview ?: item.overview?.takeIf { it.isNotBlank() },
            machineTranslated = update?.machineTranslated ?: hasMachineTranslatedOverview(item.machineTranslatedFields),
            translating = phase == DescriptionTranslationPhase.Translating && runningId == item.contentId,
        )
    }
}

/**
 * Whether the marquee's item came from a Featured section (the section's
 * `featured` flag). Only those translate on view, matching the web client,
 * which translates the visible Featured slide only; cards in ordinary rows
 * keep their text and marker but never start a job when focus rests on them.
 * [TvMarqueeContent.id] is `"<section id>#<content id>"`.
 */
internal fun isFeaturedMarqueeContent(content: TvMarqueeContent?, sections: List<ResolvedSection>): Boolean =
    content != null && sections.any { section ->
        section.featured && content.id == "${section.id}#${content.contentId}"
    }

/** How long focus rests on a card before `auto` mode counts it as viewed. */
private const val TvMarqueeTranslateDwellMs = 1_000L

/**
 * Drives [TvMarqueeTranslationState] for the item the marquee shows and
 * returns what the marquee should render for it.
 */
@Composable
internal fun rememberTvMarqueeTranslation(
    item: SectionItem?,
    /** Start on-view translation for this item; false only displays it. */
    autoTranslate: Boolean,
    /** Called once a translation lands, to re-read the rows that hold the card. */
    onTranslated: () -> Unit = {},
): TvMarqueeTranslation? {
    val latestOnTranslated by rememberUpdatedState(onTranslated)
    val repository: MetadataAiRepository = koinInject()
    val metadataAiStore: MetadataAiFeatureStore = koinInject()
    val status by metadataAiStore.status.collectAsState()
    val scope = rememberCoroutineScope()
    val state = remember(repository, scope) { TvMarqueeTranslationState(repository, scope) }
    val phase by state.phase.collectAsState()
    val runningId by state.runningContentId.collectAsState()
    val pending = item?.let(state::pendingLanguage)
    val busy = phase == DescriptionTranslationPhase.Translating
    // Re-run when a running translation ends, so an item skipped meanwhile
    // gets its turn while the viewer is still resting on it.
    LaunchedEffect(item?.contentId, pending, status.onView, busy, autoTranslate) {
        if (!autoTranslate || item == null || pending == null || status.onView != MetadataAiOnView.Auto) {
            return@LaunchedEffect
        }
        delay(TvMarqueeTranslateDwellMs)
        state.translateOnView(item) { latestOnTranslated() }
    }
    return item?.let { state.presentation(it, phase, runningId) }
}
