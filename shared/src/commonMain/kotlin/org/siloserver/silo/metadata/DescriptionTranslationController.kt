package org.siloserver.silo.metadata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.MetadataAiRepository

enum class DescriptionTranslationPhase {
    Idle,
    Translating,
    Failed,
}

/**
 * Viewer-facing description translation, mirroring silo-apple's
 * DescriptionTranslationCoordinator: fire the 202 translate request, then
 * re-fetch item detail on a bounded backoff until the server clears
 * `pending_translation_language` (there is no job-status endpoint for the
 * on-view flow). One translation at a time; `auto` mode fires once per
 * (contentId, targetLanguage) via the latch helpers.
 */
class DescriptionTranslationController(
    private val repository: MetadataAiRepository,
    // Injected so tests run without real delays; production passes delay(...).
    private val delayMs: suspend (Long) -> Unit,
) {
    private val _phase = MutableStateFlow(DescriptionTranslationPhase.Idle)
    val phase: StateFlow<DescriptionTranslationPhase> = _phase.asStateFlow()

    private val _runningContentId = MutableStateFlow<String?>(null)

    /**
     * The item whose translation [phase] describes, so a surface that switches
     * items (the TV focus marquee) never shows one item's progress on another.
     * Kept after a failure so the failed item can offer a retry.
     */
    val runningContentId: StateFlow<String?> = _runningContentId.asStateFlow()

    private val autoFired = mutableSetOf<String>()

    /**
     * @param refetchPendingLanguage re-fetches item detail and returns the
     *   current `pending_translation_language`; null means the translation
     *   landed (the refetch has already delivered the new overview).
     * @param onTranslated invoked once the pending language clears.
     * @param onPoll invoked on every poll tick before the refetch, for data
     *   the job also changes but the detail does not carry (a season job
     *   translates its episodes, so the episode list refreshes here).
     */
    suspend fun translate(
        contentId: String,
        targetLanguage: String,
        refetchPendingLanguage: suspend (org.siloserver.silo.network.AuthScopeSnapshot?) -> String?,
        onTranslated: suspend () -> Unit,
        onPoll: suspend (org.siloserver.silo.network.AuthScopeSnapshot?) -> Unit = {},
    ) {
        if (_phase.value == DescriptionTranslationPhase.Translating) return
        _runningContentId.value = contentId
        _phase.value = DescriptionTranslationPhase.Translating

        val owner = repository.captureAuthority()
        try {
            if (!repository.isCurrent(owner)) {
                _phase.value = DescriptionTranslationPhase.Failed
                return
            }
            when (val result = repository.translateDescription(contentId, targetLanguage, owner)) {
                is ApiResult.Success -> {
                    // 202 can reuse a failed job for fifteen minutes. It does
                    // not prove another execution started or text is ready.
                    if (result.data.status in setOf("failed", "canceled")) {
                        _phase.value = DescriptionTranslationPhase.Failed
                        return
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _phase.value = DescriptionTranslationPhase.Failed
                    return
                }
            }
            for (backoffSeconds in POLL_BACKOFF_SECONDS) {
                delayMs(backoffSeconds * 1_000L)
                if (!repository.isCurrent(owner)) {
                    _phase.value = DescriptionTranslationPhase.Failed
                    return
                }
                onPoll(owner)
                if (!repository.isCurrent(owner)) {
                    _phase.value = DescriptionTranslationPhase.Failed
                    return
                }
                val pending = refetchPendingLanguage(owner)
                if (!repository.isCurrent(owner)) {
                    _phase.value = DescriptionTranslationPhase.Failed
                    return
                }
                if (pending == null) {
                    _phase.value = DescriptionTranslationPhase.Idle
                    onTranslated()
                    return
                }
            }
            _phase.value = DescriptionTranslationPhase.Failed
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Screen left mid-poll: release the single-flight latch so a
            // fresh controller use can translate again, then propagate.
            _phase.value = DescriptionTranslationPhase.Idle
            throw e
        } catch (_: Exception) {
            // A throwing refetch/onTranslated must not strand the phase at
            // Translating forever (resetFailure only clears Failed).
            _phase.value = DescriptionTranslationPhase.Failed
        }
    }

    private var deferredAuto = false

    /**
     * `auto` on-view mode: claim (content, language) for a translation that
     * will start now. Returns false without claiming when it already fired, or
     * while another translation runs — that one would make [translate] a no-op
     * and the claim would block every later try. In the busy case
     * [takeDeferredAuto] reports it, so the caller tries again once the
     * running translation ends.
     */
    fun claimAutoFire(contentId: String, targetLanguage: String): Boolean {
        if (_phase.value == DescriptionTranslationPhase.Translating) {
            deferredAuto = true
            return false
        }
        if (!shouldAutoFire(contentId, targetLanguage)) return false
        markAutoFired(contentId, targetLanguage)
        return true
    }

    /** True once, after an auto claim was refused because a translation was running. */
    fun takeDeferredAuto(): Boolean = deferredAuto.also { deferredAuto = false }

    /** `auto` on-view mode: fire at most once per (content, language) per session. */
    fun shouldAutoFire(contentId: String, targetLanguage: String): Boolean =
        "$contentId|$targetLanguage" !in autoFired

    fun markAutoFired(contentId: String, targetLanguage: String) {
        autoFired += "$contentId|$targetLanguage"
    }

    fun resetFailure() {
        if (_phase.value == DescriptionTranslationPhase.Failed) {
            _phase.value = DescriptionTranslationPhase.Idle
        }
    }

    private companion object {
        // Apple DescriptionTranslationCoordinator's opening backoff, held at
        // 5s to the web client's 45s budget: a season job also translates its
        // episodes before the season's pending language clears.
        val POLL_BACKOFF_SECONDS = listOf(1L, 2L, 3L, 4L, 5L, 5L, 5L, 5L, 5L, 5L, 5L)
    }
}
