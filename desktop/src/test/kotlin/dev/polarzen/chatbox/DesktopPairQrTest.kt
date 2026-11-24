package dev.polarzen.chatbox

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopPairQrTest {
    @Test fun scannerReadsDesktopPixelsAndPreservesUtf8AndDelimiters() {
        val expected = DevicePairCode("https://example.test:8443/配对", "测试 +%&=🙂")
        val image = devicePairQrImage(expected)
        val colors = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val scan = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, colors))))
        assertEquals(expected, decodeDevicePairCode(scan.text))
    }

    @Test fun rejectsOtherRoutesAmbiguousFieldsAndInsecureAddresses() {
        val valid = "chatbox://pair?v=1&url=https%3A%2F%2Fexample.test&code=sample"
        val invalid = listOf(
            valid.replace("chatbox", "https"), valid.replace("://pair?", "://other?"),
            valid.replace("://pair?", "://pair/path?"), valid + "#fragment",
            valid.replace("v=1", "v=2"), valid.replace("v=1&", ""), valid + "&v=1",
            valid + "&url=https://example.test", valid + "&code=sample", valid + "&token=sample",
            valid.replace("https%3A", "http%3A"), valid.replace("example.test", "user@example.test"),
            valid.replace("example.test", "example.test%23fragment"),
            valid.replace("code=sample", "code="), valid.replace("sample", "%FF"),
        )
        for (raw in invalid) {
            assertThrows(IllegalArgumentException::class.java) { decodeDevicePairCode(raw) }
        }
    }
}
