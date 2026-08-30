package org.siloserver.silo.viewmodel

/**
 * Privacy-safe semantic result of one Home data-source operation.
 *
 * The shared ViewModel reports only fixed enums, aggregate counts, and elapsed
 * time. Platform observers must not receive section IDs, titles, item IDs, or
 * error messages.
 */
data class HomeLoadObservation(
    val trigger: HomeLoadTrigger,
    val source: HomeLoadSource,
    val outcome: HomeLoadOutcome,
    val durationMs: Long,
    val sectionCount: Int,
    val duplicateSectionKeyCount: Int = 0,
    val duplicateItemRowCount: Int = 0,
)

enum class HomeLoadTrigger {
    INITIAL,
    MANUAL_REFRESH,
    REALTIME,
}

enum class HomeLoadSource {
    CACHE,
    NETWORK,
}

enum class HomeLoadOutcome {
    HIT,
    MISS,
    SUCCESS,
    PARTIAL,
    API_ERROR,
    NETWORK_ERROR,
    SUPERSEDED,
    SKIPPED,
}

fun interface HomeDiagnosticsObserver {
    fun completed(observation: HomeLoadObservation)

    data object None : HomeDiagnosticsObserver {
        override fun completed(observation: HomeLoadObservation) = Unit
    }
}
