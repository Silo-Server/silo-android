package org.siloserver.silo.tv.ui.components.marquee

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import kotlin.math.tan

/**
 * How to set this TV up from a phone: three numbered steps, one action each,
 * with a small picture of exactly that action. Short titles only, so they
 * read from across the room (silo-apple `MarqueeTVSetupSteps`).
 */
@Composable
fun TvMarqueeSetupSteps(modifier: Modifier = Modifier) {
    Column(modifier.width(350.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Step(1, "Open Silo on your phone") { AppIconPicture() }
        Step(2, "Tap Set up") { SetupCardPicture() }
        Step(3, "Finish on your phone") { DonePicture() }
    }
}

@Composable
private fun Step(number: Int, title: String, picture: @Composable () -> Unit) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "Step $number: $title" },
        horizontalArrangement = Arrangement.spacedBy(17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, MarqueeColors.Hairline, RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center,
            ) { picture() }
            Box(
                Modifier
                    .offset(x = (-8).dp, y = (-8).dp)
                    .shadow(4.dp, CircleShape)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MarqueeColors.Ink),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text(title, color = MarqueeColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    }
}

/** The Silo app icon: the three-bar mark on black. */
@Composable
private fun AppIconPicture() {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 13.dp, height = 28.dp)) {
            val bar = size.height / 3.6f
            val slope = tan(Math.toRadians(-18.0)).toFloat()
            listOf(MarqueeColors.BrandBlue, MarqueeColors.BrandRed, MarqueeColors.BrandOrange).forEachIndexed { index, color ->
                val top = index * (bar + size.height * 0.05f)
                // A bar slanted like CSS skewY(-18deg).
                val path = Path().apply {
                    moveTo(0f, top)
                    lineTo(size.width, top + slope * size.width)
                    lineTo(size.width, top + bar + slope * size.width)
                    lineTo(0f, top + bar)
                    close()
                }
                drawPath(path, color)
            }
        }
    }
}

/** The setup card Silo shows on a nearby phone, with its Set up button ringed. */
@Composable
private fun SetupCardPicture() {
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF262626).copy(alpha = 0.95f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(start = 6.dp, end = 6.dp, top = 7.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.width(28.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)))
        Box(
            Modifier
                .fillMaxWidth()
                .border(2.dp, MarqueeColors.BrandOrange.copy(alpha = 0.85f), CircleShape)
                .padding(2.dp)
                .height(15.dp)
                .clip(CircleShape)
                .background(MarqueeColors.Ink),
            contentAlignment = Alignment.Center,
        ) {
            Text("Set up", color = Color.Black, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A green check: the rest happens on the phone. */
@Composable
private fun DonePicture() {
    val green = MarqueeColors.Live
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(green.copy(alpha = 0.16f))
            .border(1.5.dp, green, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = green, modifier = Modifier.size(24.dp))
    }
}
