package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class DesktopReplyStorageTest {
    @Test fun replyImagesRoundTripInOrderAndOldOrMalformedOptionalFieldsAreHarmless() {
        val temp = Files.createTempDirectory("chatbox-reply-history-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val server = "https://images.example"
            val original = store.read(server).copy(entries = listOf(Entry(
                question = "question",
                answer = "answer",
                status = "已完成",
                replyImages = listOf(
                    ReplyImage("first", 0),
                    ReplyImage(null, 3),
                    ReplyImage("last", 6),
                    ReplyImage("past-end", 100),
                ),
            )))
            store.save(server, original)

            val restored = DesktopStore(file).read(server).entries.single()
            assertEquals("answer", restored.answer)
            assertEquals(listOf(
                ReplyImage("first", 0), ReplyImage(null, 3), ReplyImage("last", 6), ReplyImage("past-end", 6),
            ), restored.replyImages)

            val oldState = JSONObject(Files.readString(file))
            val oldEntry = oldState.getJSONArray("profiles").getJSONObject(0)
                .getJSONArray("sessions").getJSONObject(0).getJSONArray("entries").getJSONObject(0)
            oldEntry.remove("replyImages")
            Files.writeString(file, oldState.toString())
            val oldHistoryEntry = DesktopStore(file).read(server).entries.single()
            assertEquals("answer", oldHistoryEntry.answer)
            assertTrue(oldHistoryEntry.replyImages.isEmpty())

            oldEntry.put("replyImages", JSONArray()
                .put(JSONObject().put("attachmentId", 4).put("textOffset", 1))
                .put(JSONObject().put("attachmentId", "valid").put("textOffset", -10))
                .put("malformed row"))
            Files.writeString(file, oldState.toString())
            val malformed = DesktopStore(file).read(server).entries.single()
            assertEquals("answer", malformed.answer)
            assertEquals(listOf(ReplyImage("valid", 0)), malformed.replyImages)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun imageCacheDecodesPngsPersistsAcrossInstancesAndScopesByProfile() {
        val temp = Files.createTempDirectory("chatbox-reply-cache-")
        try {
            val cache = ReplyImageCache(temp)
            val firstBytes = png(2, 1)
            val secondBytes = png(1, 3)
            val first = cache.save("profile-one", "same-attachment", firstBytes)
            val second = cache.save("profile-two", "same-attachment", secondBytes)
            assertEquals(2, first.width)
            assertEquals(1, first.height)
            assertEquals(1, second.width)
            assertEquals(3, second.height)

            val reopened = ReplyImageCache(temp)
            val restoredFirst = assertNotNullBitmap(reopened.read("profile-one", "same-attachment"))
            val restoredSecond = assertNotNullBitmap(reopened.read("profile-two", "same-attachment"))
            assertEquals(2, restoredFirst.width)
            assertEquals(1, restoredFirst.height)
            assertEquals(1, restoredSecond.width)
            assertEquals(3, restoredSecond.height)
            assertNull(reopened.read("profile-three", "same-attachment"))

            assertThrows(IllegalArgumentException::class.java) {
                reopened.save("profile-one", "corrupt", byteArrayOf(1, 2, 3, 4))
            }
            Files.list(temp).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".image") }.forEach { path ->
                    Files.write(path, byteArrayOf(1, 2, 3, 4))
                }
            }
            assertNull(reopened.read("profile-one", "same-attachment"))
            assertNull(reopened.read("profile-two", "same-attachment"))
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, 0xff336699.toInt())
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun assertNotNullBitmap(bitmap: androidx.compose.ui.graphics.ImageBitmap?): androidx.compose.ui.graphics.ImageBitmap =
        checkNotNull(bitmap) { "Expected cached image to decode" }
}
