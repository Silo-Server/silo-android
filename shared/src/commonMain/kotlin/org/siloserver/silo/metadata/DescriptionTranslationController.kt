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

    private val autoFired = mutableSetOf<String>()

    /**
     * @param refetchPendingLanguage re-fetches item detail and returns the
     *   current `pending_translation_language`; null means the translation
     *   landed (the refetch has already delivered the new overview).
     * @param onTranslated invoked once the pending language clears.
     */
    suspend fun translate(
        contentId: String,
        targetLanguage: String,
        refetchPendingLanguage: suspend (org.siloserver.silo.network.AuthScopeSnapshot?) -> String?,
        onTranslated: suspend () -> Unit,
    ) {
        if (_phase.value == DescriptionTranslationPhase.Translating) return
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
        // Apple DescriptionTranslationCoordinator schedule (~31s cap).
        val POLL_BACKOFF_SECONDS = listOf(1L, 2L, 3L, 5L, 5L, 5L, 5L, 5L)
    }
}
