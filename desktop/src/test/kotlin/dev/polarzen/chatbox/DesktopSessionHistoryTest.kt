package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopSessionHistoryTest {
    @Test fun oldSessionsGetTitlesAndDeterministicCreationOrderWithoutReadTouches() {
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
            assertEquals(beforeRead, Files.readString(file))
            assertEquals(listOf("old-empty", "old-image", "old-second", "old-first"), store.sessions("profile-a").map { it.id })

            store.selectSession("profile-a", "old-first")
            assertEquals("old-first", store.sessions("profile-a").first().id)
            assertFalse(store.sessions("profile-a").first { it.id == "old-first" }.title.isBlank())
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun explicitSendsAndSelectionsMoveSessionsToTheTopAndTitlesKeepUnicodeWhole() {
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
            assertEquals(first, store.sessions(profile.id).first().id)
            assertEquals(title, store.sessionContent(profile.id, second).entries.single().question)
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
            val before = JSONObject(Files.readString(file))
            val beforeFirst = findSession(before, firstProfile.id, firstSession)
            val createdAt = beforeFirst.getLong("createdAt")
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
            val afterFirst = findSession(after, firstProfile.id, firstSession)
            assertEquals(createdAt, afterFirst.getLong("createdAt"))
            assertEquals(second.session, after.getJSONArray("profiles").getJSONObject(0).getString("currentSession"))
            assertEquals(selectedProfile.id, after.getString("activeProfileId"))

            val reopened = DesktopStore(file)
            assertEquals(selectedProfile.id, reopened.activeProfileId)
            assertEquals(second.session, reopened.profile(firstProfile.id)!!.currentSession)
            assertEquals(completed, reopened.sessionContent(firstProfile.id, firstSession))
            assertEquals("answer", reopened.sessionContent(firstProfile.id, second.session).entries.single().answer)
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

    private fun findSession(history: JSONObject, profileId: String, sessionId: String): JSONObject {
        val profiles = history.getJSONArray("profiles")
        for (profileIndex in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(profileIndex)
            if (profile.getString("id") != profileId) continue
            val sessions = profile.getJSONArray("sessions")
            for (sessionIndex in 0 until sessions.length()) {
                val session = sessions.getJSONObject(sessionIndex)
                if (session.getString("id") == sessionId) return session
            }
        }
        error("Missing test session")
    }
}
