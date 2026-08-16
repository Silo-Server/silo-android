package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.recommendation.DiscoverRow
import org.siloserver.silo.model.recommendation.TasteProfile
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.RecommendationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecommendationsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val sections: List<ResolvedSection> = emptyList(),
    val tasteProfile: TasteProfile? = null,
    val error: String? = null,
)

class RecommendationsViewModel(
    private val recommendationRepository: RecommendationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecommendationsUiState())
    val uiState: StateFlow<RecommendationsUiState> = _uiState.asStateFlow()

    init {
        loadRecommendations()
    }

    fun loadRecommendations() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            fetchRecommendations()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            fetchRecommendations()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private suspend fun fetchRecommendations() {
        val discoverResultDeferred = viewModelScope.async {
            recommendationRepository.getDiscover()
        }
        val tasteProfileResultDeferred = viewModelScope.async {
            recommendationRepository.getTasteProfile()
        }

        val discoverResult = discoverResultDeferred.await()
        val tasteProfile = when (val result = tasteProfileResultDeferred.await()) {
            is ApiResult.Success -> result.data
            else -> null
        }

        when (discoverResult) {
            is ApiResult.Success -> {
                val sections = discoverResult.data.rows.toResolvedSections()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sections = sections,
                        tasteProfile = tasteProfile,
                        error = null,
                    )
                }
            }

            is ApiResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        tasteProfile = tasteProfile,
                        error = discoverResult.message.ifBlank {
                            "Failed to load recommendations"
                        },
                    )
                }
            }

            is ApiResult.NetworkError -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        tasteProfile = tasteProfile,
                        error = "Network error. Check your connection.",
                    )
                }
            }
        }
    }

}

internal fun List<DiscoverRow>.toResolvedSections(): List<ResolvedSection> =
    map(DiscoverRow::toResolvedSection)
        .filter { it.items.isNotEmpty() }
        .distinctBy(ResolvedSection::id)
        .sortedByDescending { it.title.equals("For You", ignoreCase = true) }

/**
 * Modern servers provide a stable section kind, and for the kinds that can
 * repeat (clusters, genres) a key alongside it. The other kinds are singletons
 * and the server sends no key at all, so the kind alone IS their stable
 * identity.
 *
 * Requiring a key would push exactly those rows onto the legacy path below,
 * whose identity includes the row's contents — and "Popular" and "Recently
 * Added" change contents constantly. Their section id would then change on
 * every refresh, which is what the For You detail return matches on.
 *
 * Accepting a *bare* kind is not safe either: two keyless rows sharing a kind
 * encode identically, and [toResolvedSections] resolves duplicates by dropping
 * them, so a row would silently vanish from the feed. So the kind alone is
 * trusted only for kinds this client knows to be singletons — see
 * [SingletonServerSectionKinds]. A repeatable or unrecognised kind arriving
 * without a key falls back to content identity, which is unique by
 * construction.
 *
 * Servers that send no kind at all fall back the same way, because type+label
 * alone is not unique. Length-prefixing every component keeps the encoding
 * unambiguous even when a label contains separators.
 */
private fun DiscoverRow.toResolvedSection(): ResolvedSection = ResolvedSection(
    id = stableSectionId(),
    sectionType = type,
    title = label,
    // Discover rows carry no `featured` flag of their own, but the personalised
    // "for-you-main" row is the one the server ranks highest for this profile,
    // so it is the natural hero. Marking it here lets any client hero-render it
    // through the shared [splitFeatured] path; clients that want a flat feed
    // simply ignore the flag.
    featured = sectionKind?.equals(ForYouMainSectionKind, ignoreCase = true) == true,
    itemLimit = items.size,
    totalCount = items.size,
    items = items,
)

private const val ForYouMainSectionKind = "for-you-main"

/**
 * Section kinds the server emits at most once per discover response, and
 * therefore sends with no key. Mirrors `discoverRowSectionKey` in the server's
 * `internal/api/handlers/recommendations.go`; the repeatable kinds it can
 * return — `cluster` and `genre` — are deliberately absent, because those
 * always carry a key and must never be identified by kind alone.
 */
private val SingletonServerSectionKinds = setOf(
    ForYouMainSectionKind,
    "similar-users",
    "popular",
    "recently-added",
    "top-rated",
)

private fun DiscoverRow.stableSectionId(): String {
    val kind = sectionKind?.takeIf(String::isNotBlank)
    val key = sectionKey?.takeIf(String::isNotBlank)
    if (kind != null && (key != null || kind in SingletonServerSectionKinds)) {
        return "discover:server:${encodeIdentityPart(kind)}${encodeIdentityPart(key.orEmpty())}"
    }

    val itemIdentities = items
        .map { item -> encodeIdentityPart(item.type) + encodeIdentityPart(item.contentId) }
        .sorted()
        .joinToString(separator = "")
    return "discover:legacy:" +
        encodeIdentityPart(type) +
        encodeIdentityPart(label) +
        encodeIdentityPart(itemIdentities)
}

private fun encodeIdentityPart(value: String): String = "${value.length}:$value"
