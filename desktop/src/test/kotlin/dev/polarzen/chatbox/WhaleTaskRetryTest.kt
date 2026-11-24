package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhaleTaskRetryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun restoredRequestsKeepRetryAvailableOnlyForSafeCommands() {
        val file = folder.root.toPath().resolve("history.json")
        val store = DesktopStore(file)
        val profile = store.ensureProfile("launcher:test", "http://127.0.0.1:6185")
        val unsafe = listOf("/task 创建任务", "/task read notes -- summarize", "/task write a -- change",
            "/task approve id", "/task cancel id", "/task reject id", "/task unknown")
        val safe = listOf("/task", "/task help", "/task list", "/task status", "/task status id",
            "/task result id", "/tasks approve id", "普通聊天")
        val entries = listOf("发送中", "上次回复未确认").flatMap { status ->
            (unsafe + safe).map { Entry(it, "", status) }
        }
        store.saveSessionContent(profile.id, Chat(profile.currentSession, entries))
        val restored = DesktopStore(file).sessionContent(profile.id, profile.currentSession)
        for (entry in restored.entries) {
            assertEquals("上次回复未确认", entry.status)
            assertEquals(entry.question, entry.question in unsafe, entry.blocksWhaleTaskRetry(profile.id))
            assertFalse(entry.blocksWhaleTaskRetry("manual:example"))
        }
        assertFalse(Entry("/task approve id", "", "发送失败：鲸鱼任务登录已失效，请重新登录后重试。")
            .blocksWhaleTaskRetry(profile.id))
    }
}
