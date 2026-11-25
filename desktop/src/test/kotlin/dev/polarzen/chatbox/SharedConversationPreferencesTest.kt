package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedConversationPreferencesTest {
    @Test fun observesCapacityOnlyWithKnownPositiveBudgetAndEstimate() {
        val prefs = ConversationPreferences()
        val observed = AstrBotContextPreview(true, "r", true, "", emptyList(), 85, 100)
        fun capacity(preview: AstrBotContextPreview) = contextNoticeConditions(preview, emptyList(), prefs)["capacity"]
        assertEquals(true, capacity(observed))
        assertEquals(false, capacity(observed.copy(estimatedTokens = 84)))
        assertEquals(false, capacity(observed.copy(maxTokens = 101)))
        assertEquals(true, capacity(observed.copy(maxTokens = Long.MAX_VALUE, estimatedTokens = Long.MAX_VALUE)))
        assertNull(capacity(observed.copy(supported = false)))
        for (unknown in listOf(null, 0L, -1L)) {
            assertNull(capacity(observed.copy(estimatedTokens = unknown)))
            assertNull(capacity(observed.copy(maxTokens = unknown, nearLimit = true)))
        }
    }

    @Test fun predictsEvictionOnlyForVerifiedUnpinnedSidesOfTheOldestAutomaticPair() {
        val turns = listOf(ContextTurn(1, "相同", "一", "complete"), ContextTurn(3, "三", "三答", "complete"),
            ContextTurn(2, "相同", "二", "complete"), ContextTurn(4, "未结束", "部分", "running"))
        val prefs = ConversationPreferences(recentTurns = 2, pins = listOf(ContextPin(2, "user")))
        val preview = AstrBotContextPreview(true, "r", true, "", listOf(
            ContextPreviewMessage("user", "相同", 1), ContextPreviewMessage("user", "相同", 2),
            ContextPreviewMessage("assistant", "二", 2), ContextPreviewMessage("user", "三", 3)))
        fun rolling(p: ConversationPreferences, seen: AstrBotContextPreview = preview) =
            contextNoticeConditions(seen, turns, p)["rolling"]
        assertEquals(true, rolling(prefs))
        assertEquals(false, rolling(prefs.copy(pins = prefs.pins + ContextPin(2, "assistant"))))
        assertEquals(false, rolling(prefs.copy(recentTurns = 4)))
        assertEquals(false, rolling(ConversationPreferences(recentTurns = 0)))
        val missing = preview.selected.filter { it.role != "assistant" }
        for (unverified in listOf(ContextPreviewMessage("assistant", "二", 1),
            ContextPreviewMessage("assistant", "二", null), ContextPreviewMessage("assistant", "", 2),
            ContextPreviewMessage("assistant", "旧答", 2))) {
            assertNull(rolling(prefs, preview.copy(selected = missing + unverified)))
        }
    }

    @Test fun displayNamesAreUnicodeLabelsWithIndependentOverrides() {
        val prefs = ConversationPreferences(profileName = "  林老师  ", peerName = "林老师")
        assertEquals("林老师", prefs.effectiveName)
        assertEquals("林老师", prefs.copy(peerName = "　 ").effectiveName)
        assertEquals("助手", prefs.copy(profileName = "", peerName = null).effectiveName)
        assertEquals(prefs.normalized(), ConversationPreferences.fromJson(JSONObject(prefs.toJson().toString())))
        val emoji = "🐾".repeat(40)
        assertEquals(emoji, prefs.copy(peerName = emoji).normalized().peerName)
        for (bad in listOf(emoji + "字", "\n坏名", "坏\u007f名", "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { prefs.copy(peerName = bad).normalized() }
        }
    }

    @Test fun recentPairsAndRolePinsUseSequenceAndPreserveTheFullHistory() {
        val turns = listOf(
            ContextTurn(5, "仍在生成", "暂存", "running"),
            ContextTurn(2, "相同文字", "旧答二", "complete"),
            ContextTurn(3, "仅有问题", "", "complete"),
            ContextTurn(1, "相同文字", "旧答一", "complete"),
            ContextTurn(4, "新问", " 新答 ", "complete"),
        )
        val pins = listOf(ContextPin(4, "assistant"), ContextPin(1, "user"), ContextPin(4, "assistant"))
        val prefs = ConversationPreferences(recentTurns = 1, pins = pins)
        assertNull(selectContext(turns, ConversationPreferences()))
        assertEquals(3, completedContextTurns(turns))
        assertEquals(listOf(
            ContextMessage(1, "user", "相同文字"), ContextMessage(4, "user", "新问"),
            ContextMessage(4, "assistant", " 新答 "),
        ), selectContext(turns, prefs))
        assertEquals(listOf(ContextMessage(1, "user", "相同文字"), ContextMessage(4, "assistant", " 新答 ")),
            selectContext(turns, prefs.copy(recentTurns = 0)))
        val expanded = selectContext(turns, prefs.copy(recentTurns = 3))!!
        assertEquals(listOf(1L, 2L), expanded.filter { it.text == "相同文字" }.map { it.seq })
        assertEquals(6, expanded.size)
    }

    @Test fun invalidNumbersRolesAndPinListsCannotBecomeValidSettingsByCoercion() {
        for (bad in listOf("1", 1.5, 501, -1, 4294967296L)) {
            assertThrows(IllegalArgumentException::class.java) {
                ConversationPreferences.fromJson(JSONObject().put("recentTurns", bad))
            }
        }
        for (bad in listOf(0, 10001)) {
            assertThrows(IllegalArgumentException::class.java) { ConversationPreferences(remindAfter = bad).normalized() }
        }
        for (pin in listOf(ContextPin(0, "user"), ContextPin(1, "system"))) {
            assertThrows(IllegalArgumentException::class.java) {
                ConversationPreferences(recentTurns = 0, pins = listOf(pin)).toJson()
            }
        }
        val tooMany = JSONArray()
        repeat(129) { tooMany.put(JSONObject().put("seq", it + 1).put("role", "user")) }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationPreferences.fromJson(JSONObject().put("recentTurns", 0).put("pins", tooMany))
        }
    }

    @Test fun offlineEditsRetainIndependentRemoteFieldsAndIdentifyConflicts() {
        val base = ConversationPreferences(profileName = "默认", recentTurns = 3)
        val local = base.copy(peerName = "甲", profileName = null)
        val remote = base.copy(peerName = "乙", remindAfter = 7)
        val result = mergeConversationPreferences(base, local, remote)
        assertEquals(setOf("peerName"), result.conflicts)
        assertEquals(local.copy(remindAfter = 7), result.value)
        val diff = conversationPreferenceChanges(base, result.value)
        assertEquals(setOf("profileName", "peerName", "remindAfter"), diff.changes.keys().asSequence().toSet())
        assertEquals("默认", diff.expected.getString("profileName"))
        assertSame(JSONObject.NULL, diff.changes.get("profileName"))
        assertSame(JSONObject.NULL, diff.expected.get("peerName"))
        assertTrue(mergeConversationPreferences(base, local, local).conflicts.isEmpty())
        val pinned = base.copy(pins = listOf(ContextPin(1, "assistant")))
        val concurrent = mergeConversationPreferences(base, base.copy(recentTurns = null), pinned)
        assertEquals(setOf("pins", "recentTurns"), concurrent.conflicts)
        assertEquals(base.copy(recentTurns = null), concurrent.value)
    }
}
