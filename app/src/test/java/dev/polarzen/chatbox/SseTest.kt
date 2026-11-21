package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Test

class SseTest {
    @Test fun commentsAndMultipleDataLines() {
        val actual = mutableListOf<String>()
        readSse(": heartbeat\r\ndata: 一\r\ndata: 二\r\n\r\ndata: 三\r\n\r\n".reader().buffered()) {
            actual += it; true
        }
        assertEquals(listOf("一\n二", "三"), actual)
    }
    @Test fun stopAndDropIncompleteFrame() {
        val actual = mutableListOf<String>()
        readSse("data: done\n\ndata: discarded\n".reader().buffered()) { actual += it; true }
        assertEquals(listOf("done"), actual)
        actual.clear()
        readSse("data: done\n\ndata: ignored\n\n".reader().buffered()) { actual += it; false }
        assertEquals(listOf("done"), actual)
    }
}
