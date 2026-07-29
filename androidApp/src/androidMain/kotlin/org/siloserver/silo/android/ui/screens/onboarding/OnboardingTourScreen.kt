package org.siloserver.silo.android.ui.screens.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.aurora.AuroraAccent
import org.siloserver.silo.android.ui.components.aurora.AuroraGhostButton
import org.siloserver.silo.android.ui.components.aurora.AuroraInk
import org.siloserver.silo.android.ui.components.aurora.AuroraInkSecondary
import org.siloserver.silo.android.ui.components.aurora.AuroraPrimaryButton
import org.siloserver.silo.android.ui.components.aurora.AuroraScreen
import org.siloserver.silo.android.ui.components.aurora.AuroraScrim
import org.siloserver.silo.android.ui.components.aurora.AuroraVariant
import org.siloserver.silo.android.ui.components.aurora.auroraGlass
import org.siloserver.silo.model.onboarding.OnboardingStep

/** Gutter the fixed rows and the pager's peek share. */
private val TourGutter = 20.dp

/** Client-side illustration keys — the server only ever names them. */
private fun illustrationFor(key: String?): ImageVector = when (key) {
    "watchlist" -> Icons.Filled.Favorite
    "watch-together" -> Icons.Filled.Groups
    "calendar" -> Icons.Filled.CalendarMonth
    "playback" -> Icons.Filled.PlayCircle
    "subtitles" -> Icons.Filled.Subtitles
    else -> Icons.Filled.AutoAwesome
}

/**
 * Server-driven first-run tour: one step per page, skip always reachable.
 * Kind filtering happened in the ViewModel; by the time a step renders here
 * it is one of the known kinds.
 */
@Composable
fun OnboardingTourScreen(
    onDone: () -> Unit,
    viewModel: OnboardingTourViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    // scrollable = false: this screen sizes itself against the display — pips
    // and buttons pinned, the cards taking the space between them. Inside a
    // scrolling parent that weighted body would collapse to nothing.
    // horizontalPadding = 0: the pager runs full-bleed so the next card peeks
    // in from the edge; the fixed rows re-apply the gutter themselves.
    AuroraScreen(
        variant = AuroraVariant.SignIn,
        scrim = AuroraScrim.Soft,
        scrollable = false,
        horizontalPadding = 0.dp,
    ) {
        if (state.isLoading || state.steps.isEmpty()) {
            // Skip stays reachable while the manifest loads: this gate sits
            // between profile selection and Home with the back stack already
            // cleared, so a slow server must never hold the app hostage for
            // the full request timeout.
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TourGutter),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AuroraGhostButton(label = "Skip", onClick = viewModel::onSkip)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFF3EFE9))
                }
            }
            return@AuroraScreen
        }

        val step = state.steps[state.currentIndex]
        val isLast = state.currentIndex == state.steps.lastIndex

        val pagerState = rememberPagerState(
            // The tour can resume mid-way, so the pager has to open on the
            // step the ViewModel restored rather than snapping to zero.
            initialPage = state.currentIndex,
            pageCount = { state.steps.size },
        )

        // Pager -> ViewModel: only once a swipe has settled, so a drag the user
        // releases halfway doesn't record a step they never actually saw.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }
                .collect { viewModel.onPageSettled(it) }
        }
        // ViewModel -> pager: Back/Next (and the resume index) drive the pager
        // so buttons and swipes stay one shared position. Guarded on the
        // pager's target, not its settled page — mid-fling the two disagree and
        // an unguarded call here would cancel the user's own swipe.
        LaunchedEffect(state.currentIndex) {
            if (pagerState.targetPage != state.currentIndex) {
                pagerState.animateScrollToPage(state.currentIndex)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Progress pips + skip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TourGutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    state.steps.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (index == state.currentIndex) 18.dp else 6.dp)
                                .background(
                                    color = if (index == state.currentIndex) {
                                        Color(0xFFF3EFE9)
                                    } else {
                                        Color.White.copy(alpha = 0.25f)
                                    },
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
                AuroraGhostButton(label = "Skip", onClick = viewModel::onSkip)
            }

            Spacer(Modifier.height(24.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                // Inset the pages rather than the pager: the pager itself keeps
                // the full width, so the neighbouring card slides in from the
                // display edge instead of being clipped at a gutter.
                contentPadding = PaddingValues(horizontal = TourGutter),
                pageSpacing = 12.dp,
                verticalAlignment = Alignment.CenterVertically,
            ) { page ->
                // Distance of this page from the settled position: 0 while it
                // is the current card, ±1 once fully a neighbour. Driven by the
                // live scroll offset so the card tracks the finger.
                val offset = (
                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    ).coerceIn(-1f, 1f)
                val distance = abs(offset)

                TourStepPage(
                    step = state.steps[page],
                    selected = state.pendingChoices[state.steps[page].id] ?: "",
                    onChosen = viewModel::onSettingChosen,
                    modifier = Modifier.graphicsLayer {
                        // Neighbours sit slightly back and dimmed, so the stack
                        // reads as depth rather than a filmstrip.
                        val scale = lerp(0.92f, 1f, 1f - distance)
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(0.5f, 1f, 1f - distance)
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            val isTerminal = step.kind == "handoff" || isLast
            Row(
                modifier = Modifier.padding(horizontal = TourGutter),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.currentIndex > 0) {
                    AuroraGhostButton(label = "Back", onClick = viewModel::onBack)
                }
                AuroraPrimaryButton(
                    label = when {
                        isTerminal -> "Done"
                        state.currentIndex == 0 -> "Show me"
                        else -> "Next"
                    },
                    onClick = if (isTerminal) viewModel::onFinish else viewModel::onAdvance,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One tour card. Rendered per pager page rather than for the current step
 * alone, so the neighbouring pages are already drawn as a swipe reveals them.
 */
@Composable
private fun TourStepPage(
    step: OnboardingStep,
    selected: String,
    onChosen: (OnboardingStep, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // No drop shadow: a full-height translucent card lets the shadow's
            // own edge show through as a faint box, and it tracks the card
            // across a swipe.
            .auroraGlass(cornerRadius = 28.dp, elevation = 0.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    Brush.linearGradient(
                        listOf(AuroraAccent.copy(alpha = 0.28f), AuroraAccent.copy(alpha = 0.06f)),
                    ),
                    RoundedCornerShape(20.dp),
                )
                .border(1.dp, AuroraAccent.copy(alpha = 0.30f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = illustrationFor(step.illustration),
                contentDescription = null,
                tint = AuroraAccent,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        step.title?.let {
            Text(
                text = it,
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                color = AuroraInk,
                lineHeight = 34.sp,
            )
        }
        step.body?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                fontSize = 15.sp,
                color = AuroraInkSecondary,
                lineHeight = 23.sp,
            )
        }

        if (step.kind == "setting_choice" && step.setting != null) {
            Spacer(Modifier.height(22.dp))
            SettingChoiceCard(step = step, selected = selected, onChosen = onChosen)
        }
    }
}

/**
 * Renders the manifest's options as a tappable list. Selection lives in the
 * ViewModel (persisted when the user advances past the step), so the
 * highlight can never disagree with what gets saved.
 */
@Composable
private fun SettingChoiceCard(
    step: OnboardingStep,
    selected: String,
    onChosen: (OnboardingStep, String) -> Unit,
) {
    val spec = step.setting ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A hairline well rather than another glass panel: this now sits
            // inside the card's glass, and stacking the two muddies both.
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(10.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        spec.options.forEach { option ->
            val isSelected = option.value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onChosen(step, option.value) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = if (isSelected) Color(0xFFF3EFE9) else Color.Transparent,
                            shape = CircleShape,
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (isSelected) Color(0xFFF3EFE9) else Color.White.copy(alpha = 0.4f),
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = option.label,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = Color(0xFFF3EFE9),
                )
            }
        }
    }
}
