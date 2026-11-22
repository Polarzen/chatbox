package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopSourcePathInputTest {
    @Test
    fun mixedWindowsInvalidPathsKeepValidFilesAndOnlyReportGenericInvalidNameReason() {
        assumeWindows()
        val valid = Files.createTempFile("chatbox-valid-", ".png")
        try {
            val colonPath = File("C:\\chatbox\\bad:name.png")
            val quotePath = File("C:\\chatbox\\bad\"name.jpg")
            val addition = addSourceImages(emptyList(), listOf(colonPath, valid.toFile(), quotePath))
            assertEquals(listOf(valid), addition.paths)
            assertNotNull(addition.notice)
            assertTrue(addition.notice.orEmpty().contains("已添加 1 张图片"))
            assertTrue(addition.notice.orEmpty().contains("无效文件名（2 个）"))
            assertFalse(addition.notice.orEmpty().contains(colonPath.path))
            assertFalse(addition.notice.orEmpty().contains(quotePath.path))
        } finally {
            Files.deleteIfExists(valid)
        }
    }

    @Test
    fun allInvalidWindowsPathsLeaveExistingAttachmentGroupUnchanged() {
        assumeWindows()
        val existing = Files.createTempFile("chatbox-existing-", ".jpeg")
        try {
            val invalidFiles = listOf(
                File("C:\\chatbox\\bad:name.png"),
                File("C:\\chatbox\\bad\"name.jpg"),
            )
            val addition = addSourceImages(listOf(existing), invalidFiles)
            assertEquals(listOf(existing), addition.paths)
            assertTrue(addition.notice.orEmpty().contains("未添加图片"))
            assertTrue(addition.notice.orEmpty().contains("无效文件名（2 个）"))
            invalidFiles.forEach { assertFalse(addition.notice.orEmpty().contains(it.path)) }
        } finally {
            Files.deleteIfExists(existing)
        }
    }

    private fun assumeWindows() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
    }
}
