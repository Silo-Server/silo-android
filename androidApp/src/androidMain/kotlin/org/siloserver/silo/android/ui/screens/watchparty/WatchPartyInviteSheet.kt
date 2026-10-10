package org.siloserver.silo.android.ui.screens.watchparty

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText

/**
 * The invitation, after Apple's `WatchPartyInviteView` phone layout: the
 * code, a QR of the invitation link, Share and Copy. A guest has no link
 * (the server gives the invite path to the host only), so a guest shares or
 * copies the code alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchPartyInviteSheet(
    code: String,
    inviteUrl: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Join my Silo Watch Party")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share Watch Party invitation"))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black,
        contentColor = SiloOnSurface,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((WatchPartyMetrics.BODY * 1.4f).dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchPartyEyebrow("Invite friends")
                Text(
                    "Join with this code",
                    fontSize = (WatchPartyMetrics.HERO_TITLE * 0.8f).sp,
                    fontWeight = FontWeight.Bold,
                    color = SiloOnSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Anyone with a profile on this Silo server can enter it from Watch Party.",
                    fontSize = WatchPartyMetrics.BODY.sp,
                    color = SiloSecondaryText,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                code,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 8.sp,
                color = SiloOnSurface,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { contentDescription = "Party code ${code.toList().joinToString(", ")}" },
            )
            if (inviteUrl != null) {
                WatchPartyQrCode(
                    content = inviteUrl,
                    size = 200.dp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(12.dp)
                        .semantics { contentDescription = "Scan to join this party" },
                )
                Text(
                    "Or scan to open the invitation on a phone.",
                    fontSize = WatchPartyMetrics.CAPTION.sp,
                    color = SiloSecondaryText,
                    textAlign = TextAlign.Center,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                WatchPartyButton(
                    text = if (inviteUrl != null) "Share invitation" else "Share code",
                    icon = Icons.Outlined.IosShare,
                    onClick = { share(watchPartyShareText(code, inviteUrl)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (inviteUrl != null) {
                        WatchPartyButton(
                            text = if (copied == "link") "Copied" else "Copy link",
                            icon = if (copied == "link") Icons.Filled.Check else Icons.Outlined.Link,
                            kind = WatchPartyButtonKind.Secondary,
                            onClick = {
                                clipboard.setText(AnnotatedString(inviteUrl))
                                copied = "link"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    WatchPartyButton(
                        text = if (copied == "code") "Copied" else "Copy code",
                        icon = if (copied == "code") Icons.Filled.Check else Icons.Outlined.ContentCopy,
                        kind = WatchPartyButtonKind.Secondary,
                        onClick = {
                            clipboard.setText(AnnotatedString(code))
                            copied = "code"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            WatchPartyButton(
                text = "Done",
                kind = WatchPartyButtonKind.Secondary,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/** [content] as a QR code, one square per module, at error correction M. */
@Composable
internal fun WatchPartyQrCode(content: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(content) { watchPartyQrMatrix(content) }
    Canvas(modifier.size(size)) {
        val count = matrix.width
        val module = this.size.minDimension / count
        for (y in 0 until count) {
            for (x in 0 until count) {
                if (matrix.get(x, y)) {
                    // A hair of overlap keeps sub-pixel seams out of dark runs.
                    drawRect(Color.Black, Offset(x * module, y * module), Size(module + 0.5f, module + 0.5f))
                }
            }
        }
    }
}

private fun watchPartyQrMatrix(content: String): BitMatrix =
    // Width and height 0 return the bare module grid; the white card is the quiet zone.
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
    )
