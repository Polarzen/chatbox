package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class DesktopSessionHistoryTest {
    @Test fun oldSessionsKeepTheirDisplayedOrderAndOnlyNewSessionsMoveToTheHead() {
        val temp = Files.createTempDirectory("chatbox-session-titles-")
        try {
            val file = temp.resolve("history.json")
            val first = session("old-first", "earlier question")
            val second = session("old-second", "question hidden by title").put("title", "  Fixed title  ")
            val imageOnly = session("old-image", "").put("entries", JSONArray().put(entry("", "photo.png")))
            val empty = session("old-empty", "")
            val profile = JSONObject()
                .put("id", "profile-a")
                .put("address", "https://example.invalid")
                .put("kind", "manual")
                .put("offlineOnly", false)
                .put("currentSession", "old-first")
                .put("sessions", JSONArray().put(first).put(second).put(imageOnly).put(empty))
            val original = JSONObject()
                .put("schemaVersion", 2)
                .put("username", "fixture-user")
                .put("activeProfileId", "profile-a")
                .put("profiles", JSONArray().put(profile))
            Files.writeString(file, original.toString())
            val beforeRead = Files.readString(file)

            val store = DesktopStore(file)
            val listed = store.sessions("profile-a")
            assertEquals(listOf("old-empty", "old-image", "old-second", "old-first"), listed.map { it.id })
            assertEquals("Fixed title", listed.single { it.id == "old-second" }.title)
            assertEquals("earlier question", listed.single { it.id == "old-first" }.title)
            assertEquals("图片会话", listed.single { it.id == "old-image" }.title)
            assertEquals("新会话", listed.single { it.id == "old-empty" }.title)

            assertEquals(Chat("old-first", listOf(Entry("earlier question", "", "已完成"))), store.readProfile("profile-a"))
            assertEquals("profile-a", store.activeProfileId)
            assertTrue("The first load persists the former displayed order", beforeRead != Files.readString(file))
            assertEquals(listOf("old-empty", "old-image", "old-second", "old-first"),
                DesktopStore(file).sessions("profile-a").map { it.id })

            store.selectSession("profile-a", "old-first")
            assertEquals("old-empty", store.sessions("profile-a").first().id)
            assertFalse(store.sessions("profile-a").first { it.id == "old-first" }.title.isBlank())
            assertEquals("old-empty", DesktopStore(file).sessions("profile-a").first().id)
            val fresh = store.createSession("profile-a")
            assertEquals(fresh.session, DesktopStore(file).sessions("profile-a").first().id)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun sessionUpdatesAndSelectionsPreserveOrderAndTitlesKeepUnicodeWhole() {
        val temp = Files.createTempDirectory("chatbox-session-recency-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val profile = store.ensureProfile("profile-a", "https://example.invalid")
            val first = profile.currentSession
            val second = store.createSession(profile.id).session
            val title = "你".repeat(38) + "🧪" + " after the limit"

            store.saveSessionContent(profile.id, Chat(second, listOf(Entry(title, "reply", "已完成"))))
            val shortTitle = store.sessions(profile.id).first { it.id == second }.title
            assertEquals(40, shortTitle.codePointCount(0, shortTitle.length))
            assertTrue(shortTitle.endsWith("🧪…"))
            assertEquals(second, store.sessions(profile.id).first().id)

            store.selectSession(profile.id, first)
            assertEquals(listOf(second, first), store.sessions(profile.id).map { it.id })
            assertEquals(title, store.sessionContent(profile.id, second).entries.single().question)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun renamingOnlyUpdatesTheTargetNameAndResetRestoresItsOriginalTitle() {
        val temp = Files.createTempDirectory("chatbox-session-rename-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val profile = store.ensureProfile("profile-a", "https://first.example.invalid")
            val id = profile.currentSession
            store.saveSessionContent(profile.id, Chat(id, listOf(Entry("question", "answer", "已完成"))))
            val sibling = store.createSession(profile.id)
            store.ensureProfile("profile-b", "https://second.example.invalid")
            store.saveProfile("profile-b", Chat(id, listOf(Entry("other profile", "", "已完成"))))

            val original = JSONObject(Files.readString(file))
            val target = original.getJSONArray("profiles").getJSONObject(0).getJSONArray("sessions").getJSONObject(1)
            target.put("title", "Legacy title").put("createdAt", 11).put("lastUsedAt", 22)
            target.getJSONArray("entries").getJSONObject(0).put("extraMetadata", "preserved")
            Files.writeString(file, original.toString())

            val imported = DesktopStore(file)
            val name = "我的 🧪 " + "session ".repeat(7).trimEnd()
            imported.renameSession(profile.id, id, "  $name  ")
            val renamed = imported.sessions(profile.id).single { it.id == id }
            assertEquals(name, renamed.title)
            assertEquals(name, renamed.manualName)
            assertEquals(renamed, DesktopStore(file).sessions(profile.id).single { it.id == id })
            assertEquals("other profile", imported.sessions("profile-b").single { it.id == id }.title)
            assertThrows(IllegalArgumentException::class.java) { imported.renameSession(profile.id, id, " \t ") }
            assertEquals(name, imported.sessions(profile.id).single { it.id == id }.title)

            imported.renameSession(profile.id, sibling.session, name)
            assertEquals(listOf(name, name), imported.sessions(profile.id).map { it.title })
            imported.renameSession(profile.id, id, null)
            imported.renameSession(profile.id, sibling.session, null)
            assertTrue("Rename and reset must preserve the full original history", original.similar(JSONObject(Files.readString(file))))
            val restored = DesktopStore(file).sessions(profile.id).single { it.id == id }
            assertEquals("Legacy title", restored.title)
            assertEquals(null, restored.manualName)
            assertEquals("新会话", imported.sessions(profile.id).single { it.id == sibling.session }.title)

            imported.renameSession(profile.id, sibling.session, name)
            imported.saveSessionContent(profile.id, sibling.copy(entries = listOf(Entry("New question", "", "已完成"))))
            assertEquals(name, imported.sessions(profile.id).single { it.id == sibling.session }.title)
            imported.renameSession(profile.id, sibling.session, null)
            assertEquals("New question", DesktopStore(file).sessions(profile.id).single { it.id == sibling.session }.title)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun targetedLateReplyPreservesSelectionContentAndSessionMetadataAcrossRestart() {
        val temp = Files.createTempDirectory("chatbox-targeted-session-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val firstProfile = store.ensureProfile("profile-a", "https://first.example.invalid")
            val firstSession = firstProfile.currentSession
            val pending = Chat(firstSession, listOf(Entry("question", "", "发送中")))
            store.saveSessionContent(firstProfile.id, pending)
            val second = store.createSession(firstProfile.id)
            store.saveSessionContent(firstProfile.id, second.copy(entries = listOf(Entry("other", "answer", "已完成"))))
            store.selectSession(firstProfile.id, second.session)

            val selectedProfile = store.ensureProfile("profile-b", "https://second.example.invalid")
            val orderBeforeLateReply = store.sessions(firstProfile.id).map { it.id }
            val selectedSession = store.profile(firstProfile.id)!!.currentSession
            val activeProfile = store.activeProfileId

            val initialSnapshot = store.sessionContent(firstProfile.id, firstSession)
            assertEquals(pending, initialSnapshot)
            assertEquals("发送中", initialSnapshot.entries.single().status)
            assertEquals("上次回复未确认",
                DesktopStore(file).sessionContent(firstProfile.id, firstSession).entries.single().status)
            assertEquals("发送中", store.sessionContent(firstProfile.id, firstSession).entries.single().status)
            assertEquals(activeProfile, store.activeProfileId)
            assertEquals(selectedSession, store.profile(firstProfile.id)!!.currentSession)

            val completed = initialSnapshot.copy(entries = listOf(Entry("question", "late answer", "已完成")))
            store.saveSessionContent(firstProfile.id, completed)
            assertEquals(selectedProfile.id, store.activeProfileId)
            assertEquals(second.session, store.profile(firstProfile.id)!!.currentSession)

            val after = JSONObject(Files.readString(file))
            assertEquals(second.session, after.getJSONArray("profiles").getJSONObject(0).getString("currentSession"))
            assertEquals(selectedProfile.id, after.getString("activeProfileId"))
            assertEquals(orderBeforeLateReply, DesktopStore(file).sessions(firstProfile.id).map { it.id })

            val reopened = DesktopStore(file)
            assertEquals(selectedProfile.id, reopened.activeProfileId)
            assertEquals(second.session, reopened.profile(firstProfile.id)!!.currentSession)
            assertEquals(completed, reopened.sessionContent(firstProfile.id, firstSession))
            assertEquals("answer", reopened.sessionContent(firstProfile.id, second.session).entries.single().answer)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun deletingCurrentSessionSelectsNextOrPreviousAndLastDeletionPersistsEmptyOrder() {
        val temp = Files.createTempDirectory("chatbox-session-delete-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val profile = store.ensureProfile("profile-a", "https://example.invalid")
            val first = profile.currentSession
            val next = store.createSession(profile.id).session
            val last = store.createSession(profile.id).session

            assertEquals(next, store.deleteSession(profile.id, last).session)
            assertEquals(listOf(next, first), store.sessions(profile.id).map { it.id })

            // A stored order can legitimately leave the selected ID at the end (for example after
            // importing old history); deleting it then falls back to the previous item.
            val previousFixture = JSONObject(Files.readString(file))
            val sessionRows = previousFixture.getJSONArray("profiles").getJSONObject(0).getJSONArray("sessions")
            val previous = session("previous", "previous question")
            val current = session("current", "current question")
            sessionRows.put(previous).put(current)
            previousFixture.getJSONArray("profiles").getJSONObject(0).put("currentSession", "current")
            Files.writeString(file, previousFixture.toString())
            val imported = DesktopStore(file)
            assertEquals("previous", imported.deleteSession(profile.id, "current").session)

            imported.sessions(profile.id).map { it.id }.filter { it != "previous" }.forEach { id ->
                imported.deleteSession(profile.id, id)
            }
            val empty = imported.deleteSession(profile.id, "previous")
            assertEquals(Chat(""), empty)
            assertTrue(imported.sessions(profile.id).isEmpty())
            assertEquals("", imported.profile(profile.id)!!.currentSession)
            assertEquals(Chat(""), imported.readProfile(profile.id))
            imported.selectProfile(profile.id)
            assertTrue(imported.sessions(profile.id).isEmpty())
            assertEquals(Chat(""), DesktopStore(file).readProfile(profile.id))
            assertThrows(IllegalStateException::class.java) {
                imported.saveSessionContent(profile.id, Chat("previous", listOf(Entry("late", "reply", "已完成"))))
            }
            assertTrue(imported.sessions(profile.id).isEmpty())
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun failedSessionDeletionDoesNotChangeMemoryOrDurableHistory() {
        val temp = Files.createTempDirectory("chatbox-session-delete-rollback-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val profile = store.ensureProfile("profile-a", "https://example.invalid")
            val oldSession = profile.currentSession
            val removable = store.createSession(profile.id).session
            val before = Files.readString(file)
            val displaced = temp.resolve("history-original.json")

            Files.move(file, displaced)
            Files.createDirectory(file)
            assertThrows(Exception::class.java) { store.deleteSession(profile.id, removable) }
            assertEquals(listOf(removable, oldSession), store.sessions(profile.id).map { it.id })
            assertEquals(removable, store.profile(profile.id)!!.currentSession)

            Files.delete(file)
            Files.move(displaced, file)
            assertEquals(before, Files.readString(file))
            assertEquals(listOf(removable, oldSession), DesktopStore(file).sessions(profile.id).map { it.id })
            assertEquals(removable, DesktopStore(file).profile(profile.id)!!.currentSession)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun offlineLocalHistoryCanBeDeleted() {
        val temp = Files.createTempDirectory("chatbox-offline-delete-")
        try {
            val file = temp.resolve("history.json")
            val profile = JSONObject()
                .put("id", "offline-profile")
                .put("address", "")
                .put("kind", "legacy")
                .put("offlineOnly", true)
                .put("currentSession", "offline-session")
                .put("sessions", JSONArray().put(session("offline-session", "saved locally")))
            Files.writeString(file, JSONObject()
                .put("schemaVersion", 2)
                .put("sessionOrderVersion", 1)
                .put("username", "offline-user")
                .put("activeProfileId", "offline-profile")
                .put("profiles", JSONArray().put(profile))
                .toString())

            val store = DesktopStore(file)
            assertEquals(Chat(""), store.deleteSession("offline-profile", "offline-session"))
            assertTrue(store.sessions("offline-profile").isEmpty())
            assertEquals("", store.profile("offline-profile")!!.currentSession)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    private fun session(id: String, question: String): JSONObject = JSONObject()
        .put("id", id)
        .put("entries", JSONArray().put(entry(question)))

    private fun entry(question: String, attachmentName: String? = null): JSONObject = JSONObject()
        .put("question", question)
        .put("answer", "")
        .put("status", "已完成")
        .also { attachmentName?.let { name -> it.put("attachmentDisplayName", name) } }

}
