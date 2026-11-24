package dev.polarzen.chatbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

internal data class MobileAsset(val path: String, val kind: String, val name: String, val remoteId: String? = null)
internal data class MobileDraft(val text: String = "", val attachments: List<MobileAsset> = emptyList())
internal data class MobileTurn(
    val id: String,
    val question: String,
    val answer: String = "",
    val status: String = "发送中",
    val sent: List<MobileAsset> = emptyList(),
    val replies: List<MobileAsset> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

internal data class MobileSession(
    val id: String,
    val sharedBase: String = "",
    val manualName: String? = null,
    val turns: List<MobileTurn> = emptyList(),
    val draft: MobileDraft = MobileDraft(),
) {
    val title: String get() {
        manualName?.let { return it }
        val question = turns.firstOrNull { it.question.isNotBlank() }?.question
            ?: return if (turns.any { turn -> turn.sent.any { it.kind == "image" } }) "图片会话" else "新会话"
        val normalized = question.trim().replace(Regex("\\s+"), " ")
        if (normalized.codePointCount(0, normalized.length) <= 40) return normalized
        return normalized.substring(0, normalized.offsetByCodePoints(0, 39)).trimEnd() + "…"
    }
}

internal class AndroidHistory(context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences("personal_chat", Context.MODE_PRIVATE)
    private val mediaDirectory = File(context.filesDir, "media")
    private data class ProfileView(val selected: String?, val sessions: List<MobileSession>)
    private val views = mutableMapOf<String, MutableState<ProfileView>>()
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "mobile-history.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE profiles (base TEXT PRIMARY KEY, selected TEXT)")
            db.execSQL("""CREATE TABLE sessions (
                base TEXT NOT NULL REFERENCES profiles(base), id TEXT NOT NULL, sharedBase TEXT NOT NULL,
                manualName TEXT, draftText TEXT NOT NULL DEFAULT '', draftAssets TEXT NOT NULL DEFAULT '[]',
                UNIQUE(base, id))""")
            db.execSQL("""CREATE TABLE turns (
                base TEXT NOT NULL, session TEXT NOT NULL, id TEXT NOT NULL, question TEXT NOT NULL,
                answer TEXT NOT NULL, status TEXT NOT NULL, sent TEXT NOT NULL, replies TEXT NOT NULL, createdAt INTEGER NOT NULL,
                UNIQUE(base, session, id), FOREIGN KEY (base, session) REFERENCES sessions(base, id) ON DELETE CASCADE)""")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            error("Unsupported history version $oldVersion → $newVersion")
    }

    val user: String get() = synchronized(identityLock) {
        prefs.getString("user", null) ?: ("chatbox_" + uuid().replace("-", "")).also {
            check(prefs.edit().putString("user", it).commit()) { "无法保存本机用户信息。" }
        }
    }

    override fun close() = helper.close()

    private fun legacyKeys(): Set<String> = prefs.all.keys.mapNotNullTo(mutableSetOf()) {
        when {
            it.startsWith("chat:") -> it.removePrefix("chat:")
            it.startsWith("draft:") -> it.removePrefix("draft:")
            else -> null
        }
    }

    fun legacyBases(): List<String> = buildSet {
        addAll(legacyKeys().map(::normalizeBase))
        prefs.getString("base", null)?.let { add(normalizeBase(it)) }
        helper.readableDatabase.rawQuery("SELECT base FROM profiles", null).use {
            while (it.moveToNext()) add(it.getString(0))
        }
    }.sorted()

    // The presence of a profile marks its completed import, even after its last session is deleted.
    private fun ensureProfile(db: SQLiteDatabase, base: String, aliases: List<String>) {
        if (db.rawQuery("SELECT 1 FROM profiles WHERE base=?", arrayOf(base)).use { it.moveToFirst() }) return
        db.execSQL("INSERT INTO profiles(base) VALUES (?)", arrayOf(base))
        for (rawBase in aliases) {
            val legacy = prefs.getString("chat:$rawBase", null)?.let(::JSONObject)
            val id = legacy?.optString("session")?.takeIf { it.isNotBlank() } ?: uuid()
            insertSession(db, base, id, rawBase)
            val draft = prefs.getString("draft:$rawBase", "").orEmpty()
            db.execSQL("UPDATE sessions SET draftText=? WHERE base=? AND id=?", arrayOf(draft, base, id))
            val lines = legacy?.optJSONArray("lines") ?: continue
            for (index in 0 until lines.length()) {
                val row = lines.getJSONObject(index)
                val status = row.optString("state").let { if (it == "发送中") "上次回复未确认" else it }
                insertTurn(db, base, id, MobileTurn(uuid(), row.optString("question"), row.optString("answer"), status, createdAt = 0))
            }
        }
    }

    private fun insertSession(db: SQLiteDatabase, base: String, id: String, sharedBase: String) {
        db.execSQL("INSERT INTO sessions(base,id,sharedBase) VALUES (?,?,?)", arrayOf(base, id, sharedBase))
        db.execSQL("UPDATE profiles SET selected=? WHERE base=?", arrayOf(id, base))
    }

    private fun <T> transaction(action: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            return action(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun view(base: String): MutableState<ProfileView> = views.getOrPut(base) {
        val last = prefs.getString("base", null)
        val aliases = legacyKeys().filter { normalizeBase(it) == base }
            .sortedWith(compareBy<String> { it == last }.thenBy { it })
        val loaded = transaction { db ->
            ensureProfile(db, base, aliases)
            ProfileView(selectedId(db, base), readSessions(db, base))
        }
        if (aliases.isNotEmpty()) {
            // Retrying after a process exit between these two commits only removes the old copy.
            val edit = prefs.edit()
            for (alias in aliases) edit.remove("chat:$alias").remove("draft:$alias")
            check(edit.commit()) { "无法清理已迁移的会话副本。" }
        }
        mutableStateOf(loaded, referentialEqualityPolicy())
    }

    private fun selectedId(db: SQLiteDatabase, base: String): String? =
        db.rawQuery("SELECT selected FROM profiles WHERE base=?", arrayOf(base)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun readSessions(db: SQLiteDatabase, base: String): List<MobileSession> {
        val args = arrayOf(base)
        val turns = mutableMapOf<String, MutableList<MobileTurn>>()
        db.rawQuery("SELECT session,id,question,answer,status,sent,replies,createdAt FROM turns WHERE base=? ORDER BY rowid", args).use {
            while (it.moveToNext()) {
                turns.getOrPut(it.getString(0)) { mutableListOf() }.add(MobileTurn(
                    it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                    readAssets(it.getString(5)), readAssets(it.getString(6)), it.getLong(7),
                ))
            }
        }
        return db.rawQuery("SELECT id,sharedBase,manualName,draftText,draftAssets FROM sessions WHERE base=? ORDER BY rowid DESC", args).use {
            buildList {
                while (it.moveToNext()) {
                    val sessionId = it.getString(0)
                    add(MobileSession(sessionId, it.getString(1), it.getString(2), turns[sessionId].orEmpty(),
                        MobileDraft(it.getString(3), readAssets(it.getString(4)))))
                }
            }
        }
    }

    @Synchronized fun sessions(base: String): List<MobileSession> = view(normalizeBase(base)).value.sessions

    @Synchronized fun selected(base: String): MobileSession {
        val profile = view(normalizeBase(base)).value
        return profile.sessions.firstOrNull { it.id == profile.selected } ?: create(base)
    }

    @Synchronized fun select(base: String, id: String) {
        val key = normalizeBase(base)
        val profile = view(key)
        if (profile.value.selected == id || profile.value.sessions.none { it.id == id }) return
        helper.writableDatabase.execSQL("UPDATE profiles SET selected=? WHERE base=?", arrayOf(id, key))
        profile.value = profile.value.copy(selected = id)
    }

    @Synchronized fun create(base: String): MobileSession {
        val key = normalizeBase(base)
        val profile = view(key)
        val session = MobileSession(uuid(), key)
        transaction { insertSession(it, key, session.id, key) }
        profile.value = ProfileView(session.id, listOf(session) + profile.value.sessions)
        return session
    }

    /** An empty name restores the computed title. */
    @Synchronized fun rename(base: String, id: String, name: String) {
        val key = normalizeBase(base)
        val profile = view(key)
        val session = profile.value.sessions.firstOrNull { it.id == id } ?: return
        val db = helper.writableDatabase
        db.execSQL("UPDATE sessions SET manualName=NULLIF(?, '') WHERE base=? AND id=?", arrayOf(name.trim(), key, id))
        profile.replace(session.copy(manualName = name.trim().ifEmpty { null }))
    }

    private fun MutableState<ProfileView>.replace(session: MobileSession) {
        value = value.copy(sessions = value.sessions.map { if (it.id == session.id) session else it })
    }

    @Synchronized fun delete(base: String, id: String) {
        val key = normalizeBase(base)
        val profile = view(key)
        val old = profile.value
        val index = old.sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        val removed = old.sessions[index]
        val remaining = old.sessions.toMutableList().apply { removeAt(index) }
        val next = if (id == old.selected) (remaining.getOrNull(index) ?: remaining.lastOrNull())?.id else old.selected
        transaction { db ->
            db.execSQL("UPDATE profiles SET selected=? WHERE base=?", arrayOf(next, key))
            db.delete("sessions", "base=? AND id=?", arrayOf(key, id))
        }
        profile.value = ProfileView(next, remaining)
        removeUnreferenced(removed.draft.attachments + removed.turns.flatMap { it.sent + it.replies })
    }

    @Synchronized fun draft(base: String, id: String, value: MobileDraft) {
        val key = normalizeBase(base)
        val profile = view(key)
        val session = profile.value.sessions.firstOrNull { it.id == id } ?: return removeUnreferenced(value.attachments)
        if (session.draft == value) return
        helper.writableDatabase.execSQL("UPDATE sessions SET draftText=?,draftAssets=? WHERE base=? AND id=?",
            arrayOf(value.text, writeAssets(value.attachments), key, id))
        profile.replace(session.copy(draft = value))
        removeUnreferenced(session.draft.attachments - value.attachments.toSet())
    }

    @Synchronized fun begin(base: String, id: String, turn: MobileTurn) {
        val key = normalizeBase(base)
        val profile = view(key)
        val session = checkNotNull(profile.value.sessions.firstOrNull { it.id == id }) { "本机会话已删除。" }
        val matches = session.draft.text.trim() == turn.question.trim() && session.draft.attachments == turn.sent
        transaction { db ->
            insertTurn(db, key, id, turn)
            if (matches) db.execSQL("UPDATE sessions SET draftText='',draftAssets='[]' WHERE base=? AND id=?", arrayOf(key, id))
        }
        profile.replace(session.copy(turns = session.turns + turn, draft = if (matches) MobileDraft() else session.draft))
    }

    private fun insertTurn(db: SQLiteDatabase, base: String, session: String, turn: MobileTurn) {
        db.insertOrThrow("turns", null, turnValues(turn).apply {
            put("base", base)
            put("session", session)
            put("id", turn.id)
            put("createdAt", turn.createdAt)
        })
    }

    @Synchronized fun update(base: String, id: String, turn: MobileTurn) {
        val key = normalizeBase(base)
        val profile = view(key)
        val session = profile.value.sessions.firstOrNull { it.id == id }
        val index = session?.turns?.indexOfLast { it.id == turn.id } ?: -1
        if (session == null || index < 0) return removeUnreferenced(turn.replies)
        val before = session.turns[index]
        val updated = if (before.createdAt == turn.createdAt) turn else turn.copy(createdAt = before.createdAt)
        helper.writableDatabase.update("turns", turnValues(updated), "base=? AND session=? AND id=?", arrayOf(key, id, turn.id))
        profile.replace(session.copy(turns = session.turns.toMutableList().apply { set(index, updated) }))
        if (before.replies != updated.replies) removeUnreferenced(before.replies - updated.replies.toSet())
    }

    @Synchronized fun recoverInterrupted() {
        val count = helper.writableDatabase.update("turns", ContentValues().apply { put("status", "上次回复未确认") },
            "status='发送中' OR status LIKE '上传中%' OR status LIKE '正在上传附件%' OR status LIKE '等待回复%' OR status LIKE '回复中%'", null)
        if (count != 0) views.forEach { (base, state) ->
            state.value = state.value.copy(sessions = readSessions(helper.readableDatabase, base))
        }
    }

    /** Only consider files removed by this operation. An in-flight import may still be unpublished. */
    private fun removeUnreferenced(removed: List<MobileAsset>) {
        if (removed.isEmpty()) return
        val candidates = removed.asSequence().map { it.path }.filter { it.isNotEmpty() }.map(::File)
            .filter { it.parentFile == mediaDirectory && it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .associateBy { it.absolutePath }.toMutableMap()
        if (candidates.isEmpty()) return
        helper.readableDatabase.rawQuery("""SELECT draftAssets FROM sessions WHERE draftAssets!='[]'
            UNION ALL SELECT sent FROM turns WHERE sent!='[]'
            UNION ALL SELECT replies FROM turns WHERE replies!='[]'""", null).use {
            while (candidates.isNotEmpty() && it.moveToNext()) {
                readAssets(it.getString(0)).forEach { asset -> candidates.remove(asset.path) }
            }
        }
        candidates.values.forEach { it.delete() }
    }

    private fun turnValues(turn: MobileTurn) = ContentValues().apply {
        put("question", turn.question)
        put("answer", turn.answer)
        put("status", turn.status)
        put("sent", writeAssets(turn.sent))
        put("replies", writeAssets(turn.replies))
    }

    private fun writeAssets(assets: List<MobileAsset>): String = JSONArray().apply {
        assets.forEach { put(JSONObject().put("path", it.path).put("kind", it.kind).put("name", it.name).put("remoteId", it.remoteId)) }
    }.toString()

    private fun readAssets(encoded: String): List<MobileAsset> = JSONArray(encoded).let { items ->
        List(items.length()) { index ->
            val asset = items.getJSONObject(index)
            MobileAsset(asset.getString("path"), asset.getString("kind"), asset.getString("name"), asset.opt("remoteId") as? String)
        }
    }

    companion object {
        private val identityLock = Any()
        private fun uuid() = UUID.randomUUID().toString()

        internal fun normalizeBase(address: String): String {
            val raw = address.trim().trimEnd('/')
            val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
            val host = uri.host?.lowercase(Locale.ROOT) ?: return raw
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return raw
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return raw
            val port = if (uri.port < 0) "" else ":${uri.port}"
            return "$scheme://$host$port${uri.rawPath.orEmpty()}"
        }
    }
}
