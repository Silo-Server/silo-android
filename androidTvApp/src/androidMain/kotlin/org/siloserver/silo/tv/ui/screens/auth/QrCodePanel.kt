package org.siloserver.silo.tv.ui.screens.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a QR code into a square Compose Canvas of [size]×[size].
 *
 * Uses ZXing's [QRCodeWriter] with error correction level M (15% tolerance)
 * — enough to survive TV-screen reflections / phone camera angles without
 * inflating the module count needlessly for short URLs.
 */
@Composable
fun QrCodePanel(
    content: String,
    size: Dp = 320.dp,
    foreground: Color = Color.Black,
    background: Color = Color.White,
    modifier: Modifier = Modifier,
    /** Spoken label; the QR is otherwise invisible to TalkBack. */
    description: String? = null,
) {
    val matrix = remember(content) {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        )
        // 256 is the requested matrix dimension; ZXing rounds to module count.
        writer.encode(content, BarcodeFormat.QR_CODE, 256, 256, hints)
    }

    Box(
        modifier = modifier
            .size(size)
            .background(background)
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val moduleCount = matrix.width
            val modulePx = this.size.minDimension / moduleCount
            for (y in 0 until moduleCount) {
                for (x in 0 until moduleCount) {
                    if (matrix.get(x, y)) {
                        drawRectModule(
                            color = foreground,
                            topLeft = Offset(x * modulePx, y * modulePx),
                            size = Size(modulePx, modulePx),
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRectModule(
    color: Color,
    topLeft: Offset,
    size: Size,
) {
    drawRect(color = color, topLeft = topLeft, size = size)
}
