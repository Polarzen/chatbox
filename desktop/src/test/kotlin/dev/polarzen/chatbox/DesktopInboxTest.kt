package dev.polarzen.chatbox

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopInboxTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun nativeInboxCommitsCursorAndIndependentRepliesTogetherAndRetainsTheirSharedIds() {
        val path = files.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val base = "https://bot.invalid"
        val p = store.ensureProfile("native", base)
        val key = SessionKey(p.id, p.currentSession)
        val prior = Entry("你好", "你好", "已完成")
        store.saveSessionContent(p.id, Chat(key.sessionId, listOf(prior)))
        store.saveDraft(key, 1, ComposerDraft("没发"))
        assertEquals(0L, store.inboxCursor(key))
        val messages = listOf(AstrBotInboxMessage(5, "提醒"), AstrBotInboxMessage(9, "提醒"))
        assertEquals(ReplyDestination(key, 2), store.appendInbox(key, 0L, messages))
        assertNull(store.appendInbox(key, 0L, messages))
        val restored = DesktopStore(path)
        assertEquals(9L, restored.inboxCursor(key))
        assertEquals(ComposerDraft("没发"), restored.readDraft(key))
        val chat = restored.sessionContent(p.id, key.sessionId)
        assertEquals(listOf("你好", "", ""), chat.entries.map { it.question })
        assertEquals(listOf(null, 5L, 9L), chat.entries.map { it.inboxId })
        restored.saveSessionContent(p.id, chat.copy(entries = chat.entries + Entry("稍后", "好", "已完成")))
        restored.prepareDeviceHistory(key, "shared")
        assertNull(restored.inboxCursor(key))
        val imported = restored.profileSeedPage(key, "shared", 0, 50)
        assertEquals(listOf(inboxTurnId(key.sessionId, 5), inboxTurnId(key.sessionId, 9)),
            imported.slice(1..2).map { it.id })
    }

    @Test fun nativeInboxWaitsForRunningTurnAndNeverRecreatesDeletedSessions() {
        val store = DesktopStore(files.root.toPath().resolve("history.json"))
        val p = store.ensureProfile("native", "https://bot.invalid")
        val key = SessionKey(p.id, p.currentSession)
        val message = listOf(AstrBotInboxMessage(2, "晚安"))
        store.saveSessionContent(p.id, Chat(key.sessionId, listOf(Entry("你好", "", "发送中"))))
        assertNull(store.inboxCursor(key))
        assertNull(store.appendInbox(key, 0, message))
        store.saveSessionContent(p.id, Chat(key.sessionId, listOf(Entry("你好", "你好", "已完成"))))
        assertEquals(0L, store.inboxCursor(key))
        store.deleteSession(key.profileId, key.sessionId)
        assertNull(store.appendInbox(key, 0, message))
        assertFalse(store.hasSession(key))
    }

    @Test fun runningSharedReplyBecomesVisibleBeforeCompletionWithoutAdvancingHistory() {
        val stored = Chat("room", listOf(Entry("第一轮", "好", "已完成")))
        val running = SharedTurn(2, "stable-id", "第二轮", "正在生成", "running")
        val live = desktopLiveChat(stored, running)
        assertEquals(1, stored.entries.size)
        assertEquals("正在生成", live.entries.last().answer)
        assertEquals("发送中", live.entries.last().status)
        assertEquals("更多", desktopLiveChat(stored, running.copy(answer = "更多")).entries.last().answer)
        assertSame(live, desktopLiveChat(live, running))
        assertSame(stored, desktopLiveChat(stored, running.copy(seq = 3)))
        assertSame(stored, desktopLiveChat(stored, running.copy(status = "complete")))
    }
}
