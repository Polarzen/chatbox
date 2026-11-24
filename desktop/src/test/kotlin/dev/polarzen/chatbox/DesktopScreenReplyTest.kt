package dev.polarzen.chatbox

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopScreenReplyTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    @Test fun notificationWaitsForModalAfterHandoffAndSavesPreviousProfileDraft() {
        val dir = files.root.toPath()
        val history = dir.resolve("history.json")
        val store = DesktopStore(history)
        val destination = store.createProfile("http://127.0.0.1:7101")
        store.saveSessionContent(destination.id, Chat(destination.currentSession,
            listOf(Entry("question", "saved answer", "已完成"))))
        val target = ReplyDestination(SessionKey(destination.id, destination.currentSession), 0)
        val origin = store.createProfile("http://127.0.0.1:7102")
        val key = SessionKey(origin.id, origin.currentSession)
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("credentials")))
        val cache = ReplyImageCache(dir.resolve("reply-images"))
        val requested = mutableStateOf<ReplyDestination?>(null)
        val modalOpen = AtomicBoolean(true)
        val checked = AtomicBoolean(false)
        val handled = AtomicInteger()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            DesktopScreen(store, keys, cache, openReply = requested.value,
                canNavigateReply = { checked.set(true); !modalOpen.get() },
                onOpenReplyHandled = { requested.value = null; handled.incrementAndGet() })
        }
        rule.onNodeWithTag("composer-field").performTextInput("keep my draft")
        rule.runOnIdle { requested.value = target }
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            checked.get()
        }
        assertEquals(0, handled.get())
        assertEquals(origin.id, store.activeProfileId)
        assertEquals("keep my draft", DesktopStore(history).readDraft(key).text)

        modalOpen.set(false)
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeBy(100)
            handled.get() == 1
        }
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithText("saved answer", substring = true).assertIsDisplayed()
        assertEquals(destination.id, store.activeProfileId)
        assertEquals(destination.currentSession, store.profile(destination.id)!!.currentSession)
        assertEquals(1, store.sessionContent(destination.id, destination.currentSession).entries.size)
    }
}
