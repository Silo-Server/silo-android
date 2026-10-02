package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import org.siloserver.silo.android.ui.components.aurora.AuroraInk
import org.siloserver.silo.model.auth.SignInProvider

/** "Sign in with <provider>". */
fun signInWithLabel(provider: SignInProvider): String = "Sign in with ${provider.displayName}"

/**
 * One external sign-in provider's button: its icon (SVG or bitmap from the
 * plugin, a generic sign-in glyph when it has none or it can't be drawn) and
 * [label]. Glass-outlined so the cream password button stays the primary
 * action when both are offered.
 */
@Composable
fun SignInProviderButton(
    provider: SignInProvider,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    val active = enabled && !busy
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(width = 1.dp, color = Color.White.copy(alpha = if (active) 0.22f else 0.10f), shape = shape)
            .clickable(
                enabled = active,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) {
                CircularProgressIndicator(color = AuroraInk, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                ProviderIcon(provider)
            }
            Text(
                text = label,
                color = AuroraInk.copy(alpha = if (active) 1f else 0.6f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
        }
    }
}

@Composable
private fun ProviderIcon(provider: SignInProvider) {
    val iconUrl = provider.iconUrl
    var failed by remember(iconUrl) { mutableStateOf(iconUrl == null) }
    if (failed) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Login,
            contentDescription = null,
            tint = AuroraInk,
            modifier = Modifier.size(22.dp),
        )
        return
    }
    val context = LocalContext.current
    AsyncImage(
        model = remember(iconUrl) {
            ImageRequest.Builder(context)
                .data(iconUrl)
                .apply {
                    // The app's image loader has no SVG decoder; plugin icons
                    // (the OIDC plugin's sso.svg) often are SVG.
                    if (iconUrl.orEmpty().substringBefore('?').endsWith(".svg", ignoreCase = true)) {
                        decoderFactory(SvgDecoder.Factory())
                    }
                }
                .build()
        },
        contentDescription = null,
        onError = { failed = true },
        modifier = Modifier.size(22.dp),
    )
}
