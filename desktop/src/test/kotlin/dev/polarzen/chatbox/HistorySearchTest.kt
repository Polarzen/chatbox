package dev.polarzen.chatbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class HistorySearchTest {
    @Test fun clippedExcerptsHighlightTheExactOccurrenceAndKeepSurrogatePairsWhole() {
        val answer = "x".repeat(32) + "🧪" + "a".repeat(22) + "HIT  hit" + "x".repeat(50)
        val snapshot = HistorySearchSnapshot("one", listOf(
            SearchSession("session", "title", listOf(SearchEntry("", answer))),
        ))
        val hits = snapshot.search("hit")
        assertEquals(listOf(29, 30), hits.map { it.excerptStart })
        assertEquals(listOf("HIT", "hit"), hits.map {
            it.excerpt.substring(it.excerptStart, it.excerptStart + it.endExclusive - it.start)
        })
        assertTrue(hits.last().excerpt.startsWith("…🧪"))
        assertTrue(hits.last().excerpt.endsWith("…"))
    }

    @Test fun resultsIdentifyEachLiteralOccurrenceAndIgnoreOtherProfilesAndMetadata() {
        val temp = Files.createTempDirectory("chatbox-history-search-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val first = store.ensureProfile("one", "https://one.invalid").currentSession
            val second = store.createSession("one").session
            val answer = "🧪中Ab.12 中aB.12"
            store.saveSessionContent("one", Chat(first, listOf(
                Entry("中AB.12", answer, "metadata", "/metadata.jpg", "metadata.jpg",
                    listOf(ReplyAttachment("metadata", 0, ReplyAttachmentType.IMAGE))),
            )))
            store.renameSession("one", first, "shared")
            store.renameSession("one", second, "shared")
            store.ensureProfile("two", "https://two.invalid")
            store.saveProfile("two", Chat(first, listOf(Entry("foreign", "foreign", ""))))
            val snapshot = store.historySearchSnapshot("one")
            assertEquals(listOf(second, first), snapshot.search("SHARED").map { it.sessionId })
            assertTrue(snapshot.search("shared").all { it.entryIndex == -1 && it.side == HistorySide.TITLE })
            val hits = snapshot.search("中ab.12")
            assertEquals(listOf(HistorySide.USER, HistorySide.ASSISTANT, HistorySide.ASSISTANT), hits.map { it.side })
            assertEquals(listOf(0, 2, 9), hits.map { it.start })
            assertEquals(listOf(6, 8, 15), hits.map { it.endExclusive })
            assertTrue(hits.all { it.sessionId == first && it.profileId == "one" && it.entryIndex == 0 })
            assertTrue(hits.all { it.excerpt.contains("中ab.12", ignoreCase = true) })
            assertTrue(snapshot.search("metadata").isEmpty())
            assertTrue(snapshot.search("foreign").isEmpty())
            assertTrue(snapshot.search(" ").isEmpty())
            assertTrue(snapshot.search(".*").isEmpty())
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun snapshotsRetainOldTextAndSearchMultipleOverridesOnlyForTheirOriginalSessions() {
        val temp = Files.createTempDirectory("chatbox-history-search-stream-")
        try {
            val store = DesktopStore(temp.resolve("history.json"))
            val id = store.ensureProfile("one", "https://one.invalid").currentSession
            val old = Chat(id, listOf(Entry("question", "old", "发送中")))
            store.saveSessionContent("one", old)
            val sibling = store.createSession("one")
            val foreignId = store.ensureProfile("two", "https://two.invalid").currentSession
            val snapshot = store.historySearchSnapshot("one")
            val live = old.copy(entries = listOf(old.entries.single().copy(answer = "partial")))
            val unsaved = sibling.copy(entries = listOf(Entry("unsaved question", "partial backup", "已完成")))
            val foreign = Chat(foreignId, listOf(Entry("foreign", "partial foreign", "发送中")))
            val overrides = mapOf(id to live, sibling.session to unsaved, foreignId to foreign)
            val hits = snapshot.search("part", overrides)
            assertEquals(listOf(sibling.session, id), hits.map { it.sessionId })
            assertTrue(hits.all { it.side == HistorySide.ASSISTANT && it.profileId == "one" })
            assertTrue(snapshot.search("old", overrides).isEmpty())
            assertTrue(snapshot.search("foreign", overrides).isEmpty())
            assertTrue(snapshot.search("part", mapOf(id to foreign)).isEmpty())
            store.saveSessionContent("one", live)
            store.renameSession("one", id, "changed")
            assertEquals("question", snapshot.search("old").single().sessionTitle)
            assertEquals("changed", store.historySearchSnapshot("one").search("part").single().sessionTitle)
            assertThrows(CancellationException::class.java) { snapshot.search("question", cancelled = { true }) }
            assertThrows(CancellationException::class.java) { store.historySearchSnapshot("one") { true } }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun searchesExactlyTheDisplayNameUsedForLegacyAndImageSessions() {
        val temp = Files.createTempDirectory("chatbox-history-search-title-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val legacy = store.ensureProfile("one", "https://one.invalid").currentSession
            val image = store.createSession("one")
            store.saveSessionContent("one", image.copy(entries = listOf(Entry("", "", "", "/private/photo.jpg"))))
            val json = JSONObject(Files.readString(file))
            json.getJSONArray("profiles").getJSONObject(0).getJSONArray("sessions").getJSONObject(1)
                .put("title", "  Legacy   title ")
            Files.writeString(file, json.toString())
            val restored = DesktopStore(file)
            val snapshot = restored.historySearchSnapshot("one")
            assertEquals(legacy, snapshot.search("Legacy title").single().sessionId)
            assertEquals(image.session, snapshot.search("图片会话").single().sessionId)
            assertTrue(snapshot.search("/private").isEmpty())
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
