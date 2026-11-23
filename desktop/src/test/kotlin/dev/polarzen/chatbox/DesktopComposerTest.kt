package dev.polarzen.chatbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
class DesktopComposerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun enterSendsAndLeavesDraftUntouched() {
        val initial = TextFieldValue("保持草稿", selection = TextRange(2))
        val sends = AtomicInteger()
        val draft = installComposer(initial, sends)

        composeRule.onNodeWithTag("composer-field").performKeyInput { pressKey(Key.Enter) }

        composeRule.runOnIdle {
            assertEquals(1, sends.get())
            assertEquals(initial, draft.value)
        }
    }

    @Test
    fun shiftEnterInsertsNewlineWithoutSending() {
        val initial = TextFieldValue("换行", selection = TextRange(2))
        val sends = AtomicInteger()
        val draft = installComposer(initial, sends)

        composeRule.onNodeWithTag("composer-field").performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) }
        }

        composeRule.runOnIdle {
            assertEquals(0, sends.get())
            assertEquals("换行\n", draft.value.text)
            assertEquals(1, draft.value.text.count { it == '\n' })
            assertEquals(TextRange(3), draft.value.selection)
        }
    }

    @Test
    fun shiftEnterReplacesExactlySelectedTextWithNewline() {
        val initial = TextFieldValue("甲乙丙丁", selection = TextRange(1, 3))
        val sends = AtomicInteger()
        val draft = installComposer(initial, sends)

        composeRule.onNodeWithTag("composer-field").performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) }
        }

        composeRule.runOnIdle {
            assertEquals(0, sends.get())
            assertEquals("甲\n丁", draft.value.text)
            assertEquals(TextRange(2), draft.value.selection)
        }
    }

    @Test
    fun enterWithModelledActiveCompositionDoesNotSendOrAlterComposedDraft() {
        val initial = TextFieldValue(
            text = "中文",
            selection = TextRange(2),
            composition = TextRange(0, 2),
        )
        val sends = AtomicInteger()
        val draft = installComposer(initial, sends)
        composeRule.runOnIdle { assertEquals(initial, draft.value) }

        composeRule.onNodeWithTag("composer-field").performKeyInput { pressKey(Key.Enter) }

        composeRule.runOnIdle {
            assertEquals(0, sends.get())
        }
    }

    @Test
    fun attachmentPastePreservesSelectionAndDoesNotSend() {
        val initial = TextFieldValue("甲乙", selection = TextRange(0, 1))
        val sends = AtomicInteger()
        val pastes = AtomicInteger()
        val draft = installComposer(initial, sends, onPaste = { pastes.incrementAndGet(); true })

        composeRule.onNodeWithTag("composer-field").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }

        composeRule.runOnIdle {
            assertEquals(1, pastes.get())
            assertEquals(0, sends.get())
            assertEquals(initial, draft.value)
        }
    }

    @Test
    fun captureDisablesButtonAndEnterUntilStoppedOrCancelled() {
        val sends = AtomicInteger()
        val stops = AtomicInteger()
        val cancels = AtomicInteger()
        var elapsed by mutableStateOf<Long?>(null)
        composeRule.setContent {
            Composer(
                draft = TextFieldValue("录音说明"),
                onDraft = {},
                selectedImage = null,
                onChooseImage = {},
                onRemoveImage = {},
                onSend = { sends.incrementAndGet() },
                canSend = true,
                recordingMillis = elapsed,
                canRecord = true,
                onStartRecording = { elapsed = 8000L },
                onStopRecording = { stops.incrementAndGet(); elapsed = null },
                onCancelRecording = { cancels.incrementAndGet(); elapsed = null },
            )
        }
        composeRule.onNodeWithTag("start-recording").performClick()
        composeRule.onNodeWithTag("send-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("composer-field").performClick().performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithTag("cancel-recording").performClick()
        composeRule.onNodeWithTag("start-recording").performClick()
        composeRule.onNodeWithTag("stop-recording").performClick()
        composeRule.runOnIdle {
            assertEquals(1, stops.get())
            assertEquals(1, cancels.get())
            assertEquals(0, sends.get())
        }
        composeRule.onNodeWithTag("send-button").performClick()
        composeRule.runOnIdle { assertEquals(1, sends.get()) }
    }

    private fun installComposer(initial: TextFieldValue, sends: AtomicInteger, onPaste: () -> Boolean = { false }): DraftState {
        val draft = DraftState(initial)
        composeRule.setContent {
            Composer(
                draft = draft.value,
                onDraft = { draft.value = it },
                onPasteAttachment = onPaste,
                selectedImage = null,
                onChooseImage = {},
                onRemoveImage = {},
                onSend = { sends.incrementAndGet() },
                canSend = true,
            )
        }
        composeRule.onNodeWithTag("composer-field").performClick()
        composeRule.runOnIdle { draft.value = initial }
        return draft
    }

    private class DraftState(initial: TextFieldValue) {
        var value by mutableStateOf(initial)
    }
}
