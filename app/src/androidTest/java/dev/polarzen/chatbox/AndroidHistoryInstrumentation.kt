package dev.polarzen.chatbox

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import java.io.File
import java.util.UUID

/** Runs against Android's SQLite, preferences and JSON implementations in an isolated test namespace. */
class AndroidHistoryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val testPrefix = "history-test-${UUID.randomUUID()}-"
        val host = targetContext
        val fixture = object : ContextWrapper(host) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                host.getSharedPreferences(testPrefix + name, mode)
            override fun getDatabasePath(name: String): File = host.getDatabasePath(testPrefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                host.openOrCreateDatabase(testPrefix + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                handler: DatabaseErrorHandler?): SQLiteDatabase =
                host.openOrCreateDatabase(testPrefix + name, mode, factory, handler)
        }
        val outcome = runCatching { verifyHistory(fixture) }
        host.deleteDatabase(testPrefix + "mobile-history.db")
        host.deleteSharedPreferences(testPrefix + "personal_chat")
        finish(if (outcome.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", outcome.exceptionOrNull()?.stackTraceToString() ?: "Android history checks passed")
        })
    }

    private fun verifyHistory(fixture: Context) {
        val legacyBase = "HTTPS://EXAMPLE.invalid/A"
        val base = "https://example.invalid/A"
        val blankId = "standalone-pair"
        val legacyId = "legacy-id"
        val prefs = fixture.getSharedPreferences("personal_chat", Context.MODE_PRIVATE)
        check(prefs.edit().putString("base", legacyBase).putString("user", "installed-user")
            .putString("chat:$legacyBase", """{"session":"$legacyId","lines":[
                {"question":"  Old   title  ","answer":"partial","state":"发送中"},
                {"question":"same","answer":"A","state":"已完成"},
                {"question":"same","answer":"B","state":"已完成"}]}""")
            .putString("draft:$legacyBase", "legacy draft")
            .putString("chat:", """{"session":"$blankId","lines":[]}""")
            .putString("draft:", "offline draft").commit())

        val audio = MobileAsset("audio.wav", "audio", "录音.wav")
        val image = MobileAsset("image.png", "image", "图.png")
        val unavailable = MobileAsset("", "image", "离线图片.png", "remote-27")
        val mixed = MobileDraft("  outgoing  ", listOf(image, audio))
        val turn = MobileTurn("new-turn", "outgoing", sent = mixed.attachments, createdAt = 1234)
        val otherBase = "https://example.invalid/a"

        val newSession = AndroidHistory(fixture).use { history ->
            check(history.user == "installed-user")
            check(history.legacyBases().toSet() == setOf("", base))
            check(history.selected("").id == blankId)
            check(history.selected("").draft.text == "offline draft")
            val old = history.selected("  $base/// ")
            check(old.id == legacyId && old.sharedBase == legacyBase && old.title == "Old title")
            check(!prefs.contains("chat:$legacyBase") && !prefs.contains("draft:$legacyBase"))
            check(prefs.getString("base", null) == legacyBase && prefs.getString("user", null) == "installed-user")
            check(old.draft.text == "legacy draft" && old.turns.all { it.createdAt == 0L })
            check(old.turns.map { it.answer } == listOf("partial", "A", "B"))
            check(old.turns.first().status == "上次回复未确认" && old.turns.map { it.id }.distinct().size == 3)

            val fresh = history.create(base)
            history.draft(base, fresh.id, mixed)
            history.select(base, legacyId)
            history.select(base, fresh.id)
            check(history.selected(base).draft == mixed)
            history.begin(base, fresh.id, turn)
            check(history.selected(base).draft == MobileDraft())
            history.addAssets(base, fresh.id, listOf(image))
            history.draftText(base, fresh.id, "interleaved text")
            check(history.selected(base).draft == MobileDraft("interleaved text", listOf(image)))
            history.addAssets(base, fresh.id, listOf(audio))
            history.removeAsset(base, fresh.id, image)
            history.draftText(base, fresh.id, "second edit")
            check(history.selected(base).draft == MobileDraft("second edit", listOf(audio)))
            history.addAssets(base, fresh.id, listOf(image))
            history.draftText(base, fresh.id, mixed.text)
            history.removeAsset(base, fresh.id, audio)
            check(history.selected(base).draft == MobileDraft(mixed.text, listOf(image)))
            history.addAssets(base, fresh.id, listOf(audio))
            history.begin(base, fresh.id, turn.copy(id = "identical-retry"), consumeDraft = false)
            AndroidHistory(fixture).use { reopened ->
                val persisted = reopened.selected(base)
                check(persisted.turns.last().id == "identical-retry" && persisted.draft == mixed)
            }
            history.draft(base, fresh.id, MobileDraft("next draft", listOf(audio)))
            history.begin(base, fresh.id, turn.copy(id = "retry", status = "上传中 1/2"))
            check(history.selected(base).draft.text == "next draft")
            history.rename(base, fresh.id, "  named  ")
            history.select(base, legacyId)
            history.update(base, fresh.id, turn.copy(answer = "complete", status = "已完成", replies = listOf(unavailable, audio)))
            check(history.selected(base) == old)
            check(history.sessions(base).map { it.id } == listOf(fresh.id, legacyId))
            check(history.sessions(base).first().title == "named")
            check(history.selected(otherBase).turns.isEmpty())
            fresh.id
        }

        // The database commit survived but old preferences still exist (process death before cleanup).
        check(prefs.edit().putString("chat:$legacyBase", """{"session":"$legacyId","lines":[]}""").commit())
        AndroidHistory(fixture).use { history ->
            history.recoverInterrupted()
            check(history.selected(base).id == legacyId)
            check(!prefs.contains("chat:$legacyBase"))
            val restored = history.sessions(base).first()
            check(restored.id == newSession && restored.draft == MobileDraft("next draft", listOf(audio)))
            check(restored.turns.first() == turn.copy(answer = "complete", status = "已完成", replies = listOf(unavailable, audio)))
            check(restored.turns.last().status == "上次回复未确认")
            history.rename(base, newSession, "")
            check(history.sessions(base).first().title == "outgoing")
            history.delete(base, legacyId)
            check(history.selected(base).id == newSession)
            history.delete(base, newSession)
            history.update(base, newSession, turn.copy(answer = "late"))
            history.draft(base, newSession, mixed)
            check(history.sessions(base).isEmpty())
        }
        AndroidHistory(fixture).use { history ->
            check(history.sessions(base).isEmpty())
            val fresh = history.selected(base)
            check(fresh.id !in setOf(legacyId, newSession) && fresh.draft == MobileDraft())
            history.begin(base, fresh.id, MobileTurn("audio", "", sent = listOf(audio)))
            check(history.selected(base).title == "新会话")
            history.begin(base, fresh.id, MobileTurn("image", "", sent = listOf(image)))
            check(history.selected(base).title == "图片会话")
            history.begin(base, fresh.id, MobileTurn("text", "你".repeat(38) + "🧪 truncation"))
            check(history.selected(base).title == "你".repeat(38) + "🧪…")
            check(history.selected("").draft.text == "offline draft")
            val directory = File(fixture.filesDir, "media").apply { mkdirs() }
            val owned = File.createTempFile("history-test-", ".png", directory)
            val outside = File.createTempFile("history-test-", ".png", fixture.cacheDir)
            val pending = File.createTempFile("history-test-", ".png", directory)
            try {
                val attached = MobileAsset(owned.absolutePath, "image", "same.png")
                val outsideAsset = attached.copy(path = outside.absolutePath)
                val draft = MobileDraft(attachments = listOf(attached, outsideAsset))
                val sibling = history.selected(otherBase)
                history.draft(base, fresh.id, draft)
                history.draft(otherBase, sibling.id, draft)
                history.removeAsset(base, fresh.id, attached)
                check(owned.exists())
                history.begin(otherBase, sibling.id, MobileTurn("file-turn", "", sent = draft.attachments))
                check(owned.exists())
                history.delete(otherBase, sibling.id)
                check(!owned.exists() && outside.exists())
                history.addAssets(otherBase, sibling.id, listOf(attached.copy(path = pending.absolutePath)))
                check(!pending.exists() && history.sessions(otherBase).isEmpty())
            } finally {
                owned.delete()
                outside.delete()
                pending.delete()
            }
        }
    }
}
