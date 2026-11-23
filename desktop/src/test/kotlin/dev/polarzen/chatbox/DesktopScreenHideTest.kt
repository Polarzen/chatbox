package dev.polarzen.chatbox

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopScreenHideTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    @Test fun failedHideKeepsWindowAndOriginalSessionDraftUntilRetryCanPersistIt() {
        val dir = files.root.toPath()
        val history = dir.resolve("history.json")
        val store = DesktopStore(history)
        val profile = store.createProfile("http://127.0.0.1:7101")
        val key = SessionKey(profile.id, profile.currentSession)
        store.saveDraft(key, store.nextDraftRevision(), ComposerDraft("original "))
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("credentials")))
        val images = ReplyImageCache(dir.resolve("reply-images"))
        val visibility = DesktopShellVisibility()
        val results = mutableListOf<Boolean>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            DesktopScreen(store, keys, images, hideRequested = visibility.hideRequested, onHideResult = {
                results.add(it)
                visibility.finishHide(it, true)
            })
        }
        val composer = rule.onNodeWithTag("composer-field")
        composer.assertIsDisplayed()
        val backup = Files.move(history, dir.resolve("previous.json"))
        Files.createDirectory(history)
        val blocker = Files.createFile(history.resolve("non-empty"))
        try {
            composer.performTextInput("edit")
            rule.runOnIdle { visibility.hide(true) }
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeByFrame()
                rule.runOnIdle { results.isNotEmpty() }
            }
            rule.onNodeWithText("无法保存草稿，窗口保持打开", substring = true).assertIsDisplayed()
            rule.runOnIdle {
                assertEquals(listOf(false), results)
                assertTrue(visibility.visible)
                assertFalse(visibility.hideRequested)
            }
            assertEquals("original ", DesktopStore(backup).readDraft(key).text)
        } finally {
            Files.delete(blocker)
            Files.delete(history)
            Files.move(backup, history)
        }
        rule.onNodeWithText("关闭").performClick()
        rule.mainClock.advanceTimeBy(200)
        assertEquals("original edit", composer.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.runOnIdle { visibility.hide(true) }
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { results.size == 2 }
        }
        rule.runOnIdle {
            assertEquals(listOf(false, true), results)
            assertFalse(visibility.visible)
        }
        assertEquals("original edit", DesktopStore(history).readDraft(key).text)
    }
}
