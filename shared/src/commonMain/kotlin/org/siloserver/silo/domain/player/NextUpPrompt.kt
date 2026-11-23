package org.siloserver.silo.domain.player

/**
 * When the Up Next card opens before an episode ends: `playback.next_up_prompt_seconds`
 * before the end of the file, as on iOS/tvOS (`PlayerNextUpCompletionPolicy`). Credits
 * markers drive Skip Credits; they do not move this point.
 */
object NextUpPrompt {
    /**
     * True when playback moves from [previousPosition] to [position] across the point
     * [promptSeconds] before [duration]. Only the crossing counts, so resuming or seeking
     * past that point doesn't open the card. [promptSeconds] 0 ("At end") or an unknown
     * duration never opens it early; the card then waits for the stream to end.
     */
    fun crossesPromptPoint(
        previousPosition: Double,
        position: Double,
        duration: Double,
        promptSeconds: Int,
    ): Boolean {
        if (promptSeconds <= 0 || duration <= 0) return false
        val promptStart = duration - promptSeconds
        return previousPosition < promptStart && position >= promptStart
    }
}
