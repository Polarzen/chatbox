package dev.polarzen.chatbox

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AndroidHistorySearchTest {
    @Test fun titleQuestionAndEachOverlappingAnswerMatchKeepSessionRoundAndSide() {
        val one = MobileSession("a", "profile", "Nana", listOf(
            MobileTurn("t1", "intro", "nothing"), MobileTurn("t2", "BANANA", "Banana!")))
        val two = MobileSession("b", "profile", "same name", listOf(MobileTurn("t1", "nana", "na")))
        val matches = mobileHistoryHits(listOf(two, one), "ana")
        assertEquals(listOf("b", "a", "a", "a", "a", "a"), matches.map { it.sessionId })
        assertEquals(listOf(1, 0, 2, 2, 2, 2), matches.map { it.round })
        assertEquals(listOf("t1", null, "t2", "t2", "t2", "t2"), matches.map { it.turnId })
        assertEquals(listOf(1, 1, 1, 3, 1, 3), matches.map { it.start })
        assertEquals(listOf(MobileHistorySide.USER, MobileHistorySide.TITLE, MobileHistorySide.USER,
            MobileHistorySide.USER, MobileHistorySide.ASSISTANT, MobileHistorySide.ASSISTANT), matches.map { it.side })
        assertTrue(matches.all { it.stillMatches(if (it.sessionId == one.id) one else two) })
        assertFalse(matches.first().stillMatches(one))
        assertFalse(matches.last().stillMatches(one.copy(turns = one.turns.dropLast(1))))
        assertFalse(matches.last().stillMatches(one.copy(turns = one.turns.dropLast(1) + one.turns.last().copy(answer = "b"))))
        assertTrue(matches.last().stillMatches(one.copy(turns = one.turns.reversed())))
    }

    @Test fun excerptsKeepSurrogatesWholeAndHighlightOriginalCase() {
        val answer = "x".repeat(32) + "🧪" + "a".repeat(22) + "HIT  hit" + "x".repeat(50)
        val session = MobileSession("s", "profile", "title", listOf(MobileTurn("id", "", answer)))
        val hits = mobileHistoryHits(listOf(session), "hit")
        assertEquals(listOf(29, 30), hits.map { it.excerptStart })
        assertEquals(listOf("HIT", "hit"), hits.map { it.excerpt.substring(it.excerptStart, it.excerptStart + 3) })
        assertTrue(hits.last().excerpt.startsWith("…🧪"))
        assertTrue(hits.last().excerpt.endsWith("…"))
    }

    @Test fun onlyIncomingProfileHistoryAndDisplayedTextAreSearched() {
        val session = MobileSession("s", "private.invalid", "title", listOf(
            MobileTurn("private-id", "value [ok]", "done", "private state",
                sent = listOf(MobileAsset("/private/path", "image", "private name")))),
            MobileDraft("private draft"))
        assertTrue(mobileHistoryHits(listOf(session), "private").isEmpty())
        assertTrue(mobileHistoryHits(listOf(session), " ").isEmpty())
        assertTrue(mobileHistoryHits(listOf(session), ".*").isEmpty())
        assertEquals(6, mobileHistoryHits(listOf(session), "[ok]").single().start)
        assertThrows(CancellationException::class.java) {
            mobileHistoryHits(listOf(session), "value") { throw CancellationException() }
        }
    }
}
