package app.nearu.finder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nearu.finder.logic.SessionCodes
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun SessionQr(code: String, modifier: Modifier = Modifier) {
    val matrix = remember(code) {
        QRCodeWriter().encode(SessionCodes.qrPayload(code), BarcodeFormat.QR_CODE, 0, 0)
    }
    Canvas(modifier.size(220.dp)) {
        val cols = matrix.width
        val cell = size.minDimension / cols
        drawRect(Color.White, size = size)
        for (y in 0 until matrix.height) {
            for (x in 0 until cols) {
                if (matrix.get(x, y)) {
                    drawRect(
                        Ink,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell + 0.6f, cell + 0.6f),
                    )
                }
            }
        }
    }
}
