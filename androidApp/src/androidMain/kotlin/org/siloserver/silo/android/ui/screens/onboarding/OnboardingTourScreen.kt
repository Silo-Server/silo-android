package org.siloserver.silo.android.ui.screens.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.aurora.AuroraGhostButton
import org.siloserver.silo.android.ui.components.aurora.AuroraPrimaryButton
import org.siloserver.silo.android.ui.components.aurora.AuroraScreen
import org.siloserver.silo.android.ui.components.aurora.AuroraScrim
import org.siloserver.silo.android.ui.components.aurora.AuroraVariant
import org.siloserver.silo.android.ui.components.aurora.auroraGlass
import org.siloserver.silo.model.onboarding.OnboardingStep

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

    AuroraScreen(variant = AuroraVariant.SignIn, scrim = AuroraScrim.Soft) {
        if (state.isLoading || state.steps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFF3EFE9))
            }
            return@AuroraScreen
        }

        val step = state.steps[state.currentIndex]
        val isLast = state.currentIndex == state.steps.lastIndex

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
        ) {
            // Progress pips + skip
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = illustrationFor(step.illustration),
                        contentDescription = null,
                        tint = Color(0xFFF3EFE9),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                step.title?.let {
                    Text(
                        text = it,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF3EFE9),
                        lineHeight = 34.sp,
                    )
                }
                step.body?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = it,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.68f),
                        lineHeight = 23.sp,
                    )
                }

                if (step.kind == "setting_choice" && step.setting != null) {
                    Spacer(Modifier.height(22.dp))
                    SettingChoiceCard(step = step, onChosen = viewModel::onSettingChosen)
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.currentIndex > 0) {
                    AuroraGhostButton(label = "Back", onClick = viewModel::onBack)
                }
                AuroraPrimaryButton(
                    label = when {
                        step.kind == "handoff" || isLast -> "Done"
                        state.currentIndex == 0 -> "Show me"
                        else -> "Next"
                    },
                    onClick = if (step.kind == "handoff" || isLast) {
                        viewModel::onFinish
                    } else {
                        viewModel::onAdvance
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Renders the manifest's options as a tappable list, saving immediately. */
@Composable
private fun SettingChoiceCard(
    step: OnboardingStep,
    onChosen: (OnboardingStep, String) -> Unit,
) {
    val spec = step.setting ?: return
    var selected by remember(step.id) { mutableStateOf(spec.default ?: "") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .auroraGlass(cornerRadius = 20.dp)
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
                    .clickable {
                        selected = option.value
                        onChosen(step, option.value)
                    }
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
