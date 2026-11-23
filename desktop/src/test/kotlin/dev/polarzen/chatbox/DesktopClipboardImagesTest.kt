package dev.polarzen.chatbox

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.awt.image.ImageObserver
import java.io.IOException
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopClipboardImagesTest {
    @get:Rule val temp = TemporaryFolder()
    private val clipboard = Clipboard("test")

    @Test fun textFallsThroughAndBusyClipboardReportsFailure() {
        clipboard.setContents(StringSelection("第一行\n第二行"), null)
        assertEquals(ClipboardImages.NativePaste, captureClipboardImages { clipboard })
        assertEquals("第一行\n第二行", clipboard.getData(DataFlavor.stringFlavor))
        val busy = captureClipboardImages { throw IllegalStateException() } as ClipboardImages.Failure
        assertTrue(busy.message.contains("占用"))
    }

    @Test fun explorerFilesKeepOrderAndUseSharedValidation() {
        val files = listOf("二.jpeg", "无效.txt", "一.png").map(temp::newFile)
        clipboard.setContents(content(DataFlavor.javaFileListFlavor) { files }, null)
        val captured = captureClipboardImages { clipboard } as ClipboardImages.Files
        val addition = addSourceImages(emptyList(), captured.files)
        assertEquals(listOf(files[0].toPath(), files[2].toPath()), addition.paths)
        assertTrue(addition.notice.orEmpty().contains("无效.txt"))
    }

    @Test fun capturedImageSurvivesClipboardReplacementAndWritesDistinctReadablePngs() {
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
        image.setRGB(1, 2, 0xff1971a2.toInt())
        clipboard.setContents(content(DataFlavor.imageFlavor) { image }, null)
        val captured = captureClipboardImages { clipboard } as ClipboardImages.Bitmap
        clipboard.setContents(StringSelection("已替换"), null)
        val directory = temp.root.toPath().resolve("managed")
        val first = writeClipboardPng(captured.image, directory)
        val second = writeClipboardPng(captured.image, directory)
        assertNotEquals(first, second)
        assertEquals(directory, first.parent)
        assertEquals(image.getRGB(1, 2), ImageIO.read(first.toFile()).getRGB(1, 2))
    }

    @Test fun unreadableAndOversizedImagesLeaveDirectoryEmpty() {
        clipboard.setContents(content(DataFlavor.imageFlavor) { throw IOException("不可读") }, null)
        val failure = captureClipboardImages { clipboard } as ClipboardImages.Failure
        assertTrue(failure.message.contains("不可读"))
        val huge = object : BufferedImage(1, 1, TYPE_INT_RGB) {
            override fun getWidth(observer: ImageObserver?) = Int.MAX_VALUE
            override fun getHeight(observer: ImageObserver?) = Int.MAX_VALUE
        }
        assertThrows(IllegalArgumentException::class.java) { writeClipboardPng(huge, temp.root.toPath()) }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    private fun content(flavor: DataFlavor, data: () -> Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(flavor)
        override fun isDataFlavorSupported(requested: DataFlavor) = requested == flavor
        override fun getTransferData(requested: DataFlavor): Any {
            if (requested != flavor) throw UnsupportedFlavorException(requested)
            return data()
        }
    }
}
