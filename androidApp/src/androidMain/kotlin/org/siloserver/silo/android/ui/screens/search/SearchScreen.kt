package org.siloserver.silo.android.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.model.navigation.MediaMode
import org.siloserver.silo.model.navigation.MediaModeCapabilities
import org.siloserver.silo.model.navigation.mobileMediaModeCapabilities
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PersonalDataRepository
import org.koin.compose.koinInject

/**
 * The search screen with a search bar and results grid.
 *
 * Shows a search text field at the top, real-time results as the user types
 * (debounced 300ms), and appropriate empty/loading/error states.
 *
 * @param onItemClick Callback with content ID when a result is tapped.
 * @param viewModel The search ViewModel (provided by Koin).
 */
@Composable
fun SearchScreen(
    onItemClick: (String) -> Unit,
    onRequestMediaClick: (RequestMediaResult) -> Unit,
    onRequestLibraryItemClick: (String) -> Unit,
    onBackClick: (() -> Unit)? = null,
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
    initialMediaType: MobileSearchMediaType? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val voiceAvailableState = rememberVoiceSearchAvailability()
    val voiceAvailable = voiceAvailableState.value
    val personalDataRepository: PersonalDataRepository = koinInject()
    val requestsFeatureStore: RequestsFeatureStore = koinInject()
    val requestsEnabled by requestsFeatureStore.isEnabled.collectAsState()
    val availableModes by produceState(
        initialValue = MediaModeCapabilities(
            listOf(
                MediaMode.Video,
                MediaMode.Audio,
                MediaMode.Reading,
            ),
        ).mobileModes(),
        personalDataRepository,
    ) {
        value = when (val result = personalDataRepository.listUserLibraries()) {
            is ApiResult.Success -> result.data.mobileMediaModeCapabilities().mobileModes()
            else -> value
        }
    }

    LaunchedEffect(availableModes) {
        viewModel.setAvailableModes(availableModes)
    }

    LaunchedEffect(initialMediaType, state.availableMediaTypes) {
        viewModel.selectInitialMediaType(initialMediaType)
    }

    Scaffold(
        topBar = {
            if (onBackClick != null) {
                SiloTopBar(
                    title = "Search",
                    onBackClick = onBackClick,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchBar(
                query = state.query,
                onQueryChanged = { viewModel.onQueryChanged(it) },
                onClear = { viewModel.clearSearch() },
                onVoiceQuery = { viewModel.onVoiceQuery(it) },
                voiceAvailable = voiceAvailable,
                onVoiceUnavailable = { voiceAvailableState.value = false },
            )

            if (state.isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.query.isNotBlank() && state.availableMediaTypes.size > 1) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    state.availableMediaTypes.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = state.mediaType == type,
                            onClick = { viewModel.onMediaTypeChanged(type) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = state.availableMediaTypes.size,
                            ),
                            label = { Text(type.label) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            @Composable
            fun SearchEmptyState(text: String, subtitle: String) {
                // Mirrors iOS EmptyStateView preceded by Spacer(minLength: 80).
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(80.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }

            @Composable
            fun RequestSearchFooter() {
                RequestSearchSection(
                    query = state.query,
                    selectedMediaType = state.mediaType,
                    requestsEnabled = requestsEnabled,
                    onRequestMediaClick = onRequestMediaClick,
                    onRequestLibraryItemClick = onRequestLibraryItemClick,
                )
            }

            when {
                state.isSearching && state.results.isEmpty() -> {
                    // Sits in the top part of the content area, matching the
                    // empty state's offset, so it stays visible above the IME
                    // instead of being centred in the space the keyboard covers.
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.height(80.dp))
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Searching…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                !state.hasSearched && state.query.isBlank() -> {
                    SearchEmptyState(
                        text = "Search Silo",
                        subtitle = if (voiceAvailable) {
                            "Find movies, shows, books, audio, and people. " +
                                "Tap the mic to search by voice."
                        } else {
                            "Find movies, shows, books, audio, and people."
                        },
                    )
                }
                state.error != null && state.results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = state.error ?: "Search failed",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(onClick = { viewModel.retry() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
                state.hasSearched && state.results.isEmpty() && !state.isSearching -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            SearchEmptyState(
                                text = "No results",
                                subtitle = "Try a different search term",
                            )
                        }
                        RequestSearchFooter()
                    }
                }
                else -> {
                    SearchResults(
                        results = state.results,
                        total = state.total,
                        isSearching = state.isSearching,
                        hasMore = state.hasMore,
                        onItemClick = onItemClick,
                        onLoadMore = { viewModel.loadMore() },
                        modifier = Modifier.fillMaxSize(),
                        footer = { RequestSearchFooter() },
                    )
                }
            }
        }
    }
}
