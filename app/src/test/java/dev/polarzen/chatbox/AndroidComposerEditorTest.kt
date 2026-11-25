package dev.polarzen.chatbox

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidComposerEditorTest {
    @Test fun cursorAndCompositionDoNotWriteTheDraft() {
        val editor = AndroidComposerEditor("拼音")
        val composing = TextFieldValue("拼音", selection = TextRange(1), composition = TextRange(0, 2))
        editor.edit(composing) { error("Only text changes should reach storage") }
        editor.sync("拼音")
        assertEquals(composing, editor.value)
    }

    @Test fun shiftEnterReplacesTheSelectedText() {
        val editor = AndroidComposerEditor("第一段第二段", TextRange(3, 1))
        val edits = mutableListOf<String>()
        editor.insertNewline(edits::add)
        assertEquals(listOf("第\n第二段"), edits)
        assertEquals(TextRange(2), editor.value.selection)
    }

    @Test fun sendClearsAndIdenticalNewDraftIsSavedAgain() {
        val editor = AndroidComposerEditor("")
        val edits = mutableListOf<String>()
        val typed = TextFieldValue("再来一次", TextRange(4))
        editor.edit(typed, edits::add)
        editor.sync("")
        assertEquals(TextFieldValue(), editor.value)
        editor.edit(typed, edits::add)
        assertEquals(listOf(typed.text, typed.text), edits)
        assertEquals(typed, editor.value)
    }

    @Test fun restoredPendingDraftPlacesCursorAtEndAndDropsOldComposition() {
        val editor = AndroidComposerEditor("写")
        editor.edit(TextFieldValue("写", composition = TextRange(0, 1))) {}
        editor.sync("原提问")
        assertEquals(TextFieldValue("原提问", TextRange(3)), editor.value)
    }
}
