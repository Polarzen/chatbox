package dev.polarzen.chatbox

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.util.UUID

/** Exercise the grant, page and pending transactions on Android SQLite and AndroidKeyStore. */
internal object AndroidPcHistoryChecks {
    fun verify(context: Context) {
        val file = "pc-history-test-${UUID.randomUUID()}.db"
        try {
            exercise(context, file)
        } finally {
            context.deleteDatabase(file)
            check(context.getSharedPreferences("pc-history-cleanup", Context.MODE_PRIVATE).edit().remove(file).commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("chatbox.profile.$file")
        }
    }

    private fun exercise(context: Context, file: String) {
        val binding = ProfileBinding("https://service.invalid", "profile-a", "instance-a",
            UUID.randomUUID().toString(), false, "档案 A")
        val one = ProfileSession("one", "同名", 2, 120, 120, "")
        val two = one.copy(sid = "two", activity = 3)
        val three = one.copy(sid = "three", title = "不同", activity = 1, reason = "原身份失效")
        val pending = UUID.randomUUID().toString()
        val large = "汉🧪".repeat(320_000)
        val rows = (1L..120L).map { n ->
            SharedTurn(n, UUID.randomUUID().toString(), "重复提问", if (n == 120L) large else "答$n", "complete")
        }
        fun directory(rev: String, more: Boolean, vararg list: ProfileSession) =
            ProfileDirectory(list.toList(), more, rev, true, 3, "", "档案 A")

        val scope: String
        val initial = AndroidPcHistoryCache(context, file)
        try {
            val c = initial
            check(c.grant() == null)
            val g = c.bind(binding)
            check(c.grant() == g to binding)
            c.readableDatabase.rawQuery("SELECT credential FROM grant_state", null).use {
                check(it.moveToFirst() && !String(it.getBlob(0), Charsets.UTF_8).contains(binding.token))
            }
            c.applyDirectory(g, directory("1", false, one, two, three), 0, 100)
            check(c.sessions(g, "同名", false, 1) == listOf(two))
            check(c.sessions(g, "同名", false, 10) == listOf(two, one))
            c.hide(g, "one", true)
            c.draft(g, "one", "独立草稿")
            c.draft(g, "two", "待发送")
            c.begin(g, "two", pending)
            c.applyTurns(g, "two", rows.take(50), cursor = 50)
            c.applyTurns(g, "two", rows.drop(50), cursor = 120)
            c.applyTurns(g, "two", rows.takeLast(1))
            check(c.turns(g, "two", 5) == rows.takeLast(5))
            check(c.turns(g, "two", 200) == rows)
            c.applyDirectory(g, directory("2", true, two.copy(title = "改名")), 0, 200)
            check(c.directory(g).let { !it.finished && it.syncedAt == 100L && it.received == 1 })
            check(c.session(g, "three") == three)
            // Reopen the preceding release-candidate schema with its cached bodies and pending send.
            c.writableDatabase.execSQL("DROP TABLE turn_alias")
            c.writableDatabase.version = 1
            scope = g
        } finally {
            initial.close()
        }

        val c = AndroidPcHistoryCache(context, file)
        try {
            check(c.grant() == scope to binding)
            check(c.directory(scope).revision == "2")
            check(c.sessions(scope, "", true, 10) == listOf(one))
            check(c.composer(scope, "one") == PcComposer("独立草稿", null))
            check(c.composer(scope, "two") == PcComposer("待发送", pending))
            check(c.cursor(scope, "two") == 120L)
            c.applyDirectory(scope, directory("2", false, one), 1, 250)
            check(c.session(scope, "three") == null)
            check(c.directory(scope).let { it.finished && it.received == 2 && it.syncedAt == 250L })
            check(c.composer(scope, "two").pending == pending)
            val imported = rows.last()
            val authoritative = imported.copy(id = UUID.randomUUID().toString())
            c.applyTurns(scope, "two", listOf(authoritative), cursor = 120L)
            c.applyTurns(scope, "two", listOf(authoritative), cursor = 120L)
            c.applyTurns(scope, "two", listOf(imported)) // ID lookup echoes its original alias.
            check(c.turns(scope, "two", 1) == listOf(authoritative))
            check(c.composer(scope, "two") == PcComposer("待发送", pending))
            val changed = authoritative.copy(id = UUID.randomUUID().toString())
            for (conflict in listOf(changed.copy(question = "错问"), changed.copy(answer = "错答"),
                changed.copy(status = "unknown"), changed.copy(status = "running"), changed.copy(id = rows[0].id))) {
                check(runCatching { c.applyTurns(scope, "two", listOf(conflict), cursor = 999L) }.isFailure)
                check(c.cursor(scope, "two") == 120L)
            }
            // A retired UUID cannot appear at another seq, even across process reopen.
            c.close()
            check(runCatching { c.applyTurns(scope, "two", listOf(imported.copy(seq = 121))) }.isFailure)
            check(c.turns(scope, "two", 1) == listOf(authoritative))
            val response = SharedTurn(122, pending, "待发送", "", "running")
            c.applyTurns(scope, "two", listOf(response))
            check(c.cursor(scope, "two") == 120L)
            check(c.composer(scope, "two").pending == pending)
            val concurrent = response.copy(seq = 121, id = UUID.randomUUID().toString(), status = "complete")
            val terminal = response.copy(answer = "完成", status = "complete")
            c.applyTurns(scope, "two", listOf(concurrent, terminal), cursor = 122L)
            c.applyTurns(scope, "two", listOf(terminal))
            check(c.turns(scope, "two", 2) == listOf(concurrent, terminal))
            check(c.composer(scope, "two") == PcComposer("", null))
            c.draft(scope, "two", terminal.question)
            c.applyTurns(scope, "two", listOf(terminal))
            check(c.composer(scope, "two") == PcComposer(terminal.question, null))
            c.draft(scope, "two", "另一条草稿")
            c.begin(scope, "two", imported.id)
            check(runCatching { c.applyTurns(scope, "two", listOf(imported)) }.isFailure)
            check(c.composer(scope, "two") == PcComposer("另一条草稿", imported.id))
            c.rejected(scope, "two", imported.id)
            c.draft(scope, "two", imported.question)
            c.begin(scope, "two", imported.id)
            c.applyTurns(scope, "two", listOf(imported))
            c.applyTurns(scope, "two", listOf(imported))
            check(c.composer(scope, "two") == PcComposer("", null))
            check(c.turns(scope, "two", 3) == listOf(authoritative, concurrent, terminal))

            // A tail preview and an interrupted prefix are still distinct on reopening.
            val sparse = (rows.take(5) + rows.drop(70)).map { it.copy(answer = "答") }
            c.applyTurns(scope, "one", sparse, cursor = 5)
            val phone = AndroidPcHistory(c)
            phone.open("one")
            check(phone.turns == sparse.takeLast(40))
            phone.older()
            check(phone.turns == sparse)
            phone.back()
            phone.open("two")
            check(phone.turns.size == 40)
            phone.open("one")
            check(phone.turns == sparse)
            check(phone.turns.zipWithNext().single { (a, b) -> b.seq > a.seq + 1 }.let { (a, b) -> a.seq == 5L && b.seq == 71L })

            phone.editDraft("新")
            phone.editDraft("新草稿")
            phone.open("one") // Re-read the body just as a refresh does.
            check(phone.draft == "新草稿")
            phone.open("two")
            check(phone.draft == "" && c.composer(scope, "one") == PcComposer("新草稿", null))
            phone.open("one")
            check(phone.draft == "新草稿")

            val tooLong = "🧪".repeat(16384) + "问"
            phone.editDraft(tooLong)
            runBlocking { phone.send() }
            check(phone.notice.contains("64 KiB") && phone.pendingId == null)
            check(c.composer(scope, "one") == PcComposer(tooLong, null))

            // SQLite cannot complete the cascade. The key and the independent on-disk
            // cleanup marker still prevent the old grant from reopening or consuming an invite.
            c.writableDatabase.execSQL("""CREATE TRIGGER refuse_cleanup BEFORE DELETE ON grant_state
                BEGIN SELECT RAISE(ABORT,'test refusal'); END""")
            phone.forget()
            check(!phone.bound && phone.cleanupRequired && phone.turns.isEmpty())
            check(runCatching { c.grant() }.isFailure)
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("chatbox.profile.$file"))
            c.close()
            val restored = AndroidPcHistory(c)
            val message = restored.notice
            runBlocking { restored.join("http://deliberately-invalid", "unused") }
            check(!restored.bound && restored.cleanupRequired && restored.notice == message)
            c.writableDatabase.execSQL("DROP TRIGGER refuse_cleanup")
            restored.forget()
            check(!restored.cleanupRequired && c.grant() == null)
            val other = c.bind(binding.copy(token = UUID.randomUUID().toString()))
            check(other != scope)
            check(c.sessions(other, "", false, 10).isEmpty())
            check(runCatching { c.applyDirectory(scope, directory("late", false, two), 0, 300) }.isFailure)
            c.applyDirectory(other, directory("1", false, two), 0, 301)
            check(runCatching { c.applyTurns(scope, "two", listOf(terminal)) }.isFailure)
            check(c.composer(other, "two") == PcComposer("", null))
            check(c.turns(other, "two", 10).isEmpty())
            c.forget()
            check(c.grant() == null)
        } finally {
            c.close()
        }
    }
}
