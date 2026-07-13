package org.siloserver.silo.common.player.video

/** Process-local view of the runtime corrections requested by the mounted V3 plan. */
internal class PlaybackRuntimeCorrectionState {
    @Volatile
    private var activeCorrections: Set<String> = emptySet()

    fun activate(corrections: Collection<String>) {
        activeCorrections = corrections.toSet()
    }

    fun isEnabled(correctionId: String): Boolean = correctionId in activeCorrections
}
