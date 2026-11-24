package dev.polarzen.chatbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

internal fun devicePairQrImage(pair: DevicePairCode): BufferedImage {
    val pixels = QRCodeWriter().encode(encodeDevicePairCode(pair), BarcodeFormat.QR_CODE, 240, 240)
    return BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_RGB).apply {
        for (y in 0 until height) {
            for (x in 0 until width) {
                setRGB(x, y, if (pixels[x, y]) 0x000000 else 0xffffff)
            }
        }
    }
}

@Composable
internal fun DesktopPairQr(pair: DevicePairCode) {
    val bitmap = remember(pair) {
        try {
            devicePairQrImage(pair).toComposeImageBitmap()
        } catch (_: WriterException) {
            null
        }
    }
    if (bitmap != null) {
        Image(bitmap, "手机设备配对二维码", Modifier.size(200.dp), filterQuality = FilterQuality.None)
    } else {
        Text("地址和配对码太长，无法显示二维码。请在手机上手动输入。")
    }
}
