package org.siloserver.silo.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.R

// Mirrors iosApp/iosApp/Theme/Typography.swift — system sans (Roboto on Android, the
// closest analog to SF), no custom display font, exact iOS pt sizes mapped to sp.

/**
 * Inter, the face the TV app already uses for all of its type. On the phone
 * it is reserved for the video player's chrome, where tabular figures keep
 * timecodes from jittering and the controls should read the same as on TV.
 */
val SiloInterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val SiloHeroTitle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 36.sp,
    lineHeight = 40.sp,
)

val SiloTitleStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
)

val SiloHeadlineStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
)

val SiloSubheadlineStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

val SiloBodyStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

val SiloCaptionStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

val SiloSmallStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 14.sp,
)

val SiloPinStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 36.sp,
)

val SiloTypography = Typography(
    displayLarge = SiloHeroTitle,
    displayMedium = SiloHeroTitle.copy(fontSize = 32.sp, lineHeight = 36.sp),
    displaySmall = SiloTitleStyle.copy(fontSize = 24.sp, lineHeight = 28.sp),
    headlineLarge = SiloTitleStyle.copy(fontSize = 22.sp, lineHeight = 28.sp),
    headlineMedium = SiloTitleStyle,
    headlineSmall = SiloHeadlineStyle,
    titleLarge = SiloTitleStyle,
    titleMedium = SiloHeadlineStyle,
    titleSmall = SiloSubheadlineStyle,
    bodyLarge = SiloBodyStyle.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = SiloBodyStyle,
    bodySmall = SiloCaptionStyle,
    labelLarge = SiloSubheadlineStyle,
    labelMedium = SiloCaptionStyle,
    labelSmall = SiloSmallStyle,
)
