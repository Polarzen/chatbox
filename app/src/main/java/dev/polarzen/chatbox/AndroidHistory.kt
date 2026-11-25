package dev.polarzen.chatbox

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class MobileAsset(val path: String, val kind: String, val name: String, val remoteId: String? = null)
internal data class MobileDraft(val text: String = "", val attachments: List<MobileAsset> = emptyList())
internal const val DEVICE_MEDIA_WAITING = "等待设备确认"
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
    val draftRevision: Long = 0,
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
    private data class DraftEdit(val text: String, val revision: Long)
    private val edits = mutableMapOf<Pair<String,String>, DraftEdit>()
    private val writer = ScheduledThreadPoolExecutor(1) { job ->
        Thread(job, "chatbox-draft-writer").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var scheduled: ScheduledFuture<*>? = null
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "mobile-history.db", null, 3) {
        override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

        override fun onCreate(db: SQLiteDatabase) {
            createPreferenceStorage(db)
            db.execSQL("CREATE TABLE profiles (base TEXT PRIMARY KEY, selected TEXT)")
            db.execSQL("""CREATE TABLE sessions (
                base TEXT NOT NULL REFERENCES profiles(base), id TEXT NOT NULL, sharedBase TEXT NOT NULL,
                manualName TEXT, draftText TEXT NOT NULL DEFAULT '', draftAssets TEXT NOT NULL DEFAULT '[]', draftRevision INTEGER NOT NULL DEFAULT 0,
                UNIQUE(base, id))""")
            db.execSQL("""CREATE TABLE turns (
                base TEXT NOT NULL, session TEXT NOT NULL, id TEXT NOT NULL, question TEXT NOT NULL,
                answer TEXT NOT NULL, status TEXT NOT NULL, sent TEXT NOT NULL, replies TEXT NOT NULL, createdAt INTEGER NOT NULL,
                UNIQUE(base, session, id), FOREIGN KEY (base, session) REFERENCES sessions(base, id) ON DELETE CASCADE)""")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..2 && newVersion == 3)
            if (oldVersion == 1) db.execSQL("ALTER TABLE sessions ADD COLUMN draftRevision INTEGER NOT NULL DEFAULT 0")
            createPreferenceStorage(db)
        }
    }

    val user: String get() = synchronized(identityLock) {
        prefs.getString("user", null) ?: ("chatbox_" + uuid().replace("-", "")).also {
            check(prefs.edit().putString("user", it).commit()) { "无法保存本机用户信息。" }
        }
    }

    @Synchronized override fun close() {
        try { flushDrafts() } finally { writer.shutdownNow(); helper.close() }
    }

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
        db.rawQuery("SELECT session,id,${smallSqlText("question")},${smallSqlText("answer")},status,${smallSqlText("sent")},${smallSqlText("replies")},createdAt FROM turns WHERE base=? ORDER BY rowid", args).use {
            while (it.moveToNext()) {
                val keys = arrayOf(base,it.getString(0),it.getString(1))
                fun field(index: Int,column: String) = it.fullSqlText(db,index,column,"turns","base=? AND session=? AND id=?",keys)
                turns.getOrPut(it.getString(0)) { mutableListOf() }.add(MobileTurn(
                    it.getString(1), field(2,"question"), field(3,"answer"), it.getString(4),
                    readAssets(field(5,"sent")), readAssets(field(6,"replies")), it.getLong(7),
                ))
            }
        }
        return db.rawQuery("SELECT id,sharedBase,manualName,${smallSqlText("draftText")},${smallSqlText("draftAssets")},draftRevision FROM sessions WHERE base=? ORDER BY rowid DESC", args).use {
            buildList {
                while (it.moveToNext()) {
                    val sessionId = it.getString(0)
                    val keys = arrayOf(base,sessionId)
                    fun field(index: Int,column: String) = it.fullSqlText(db,index,column,"sessions","base=? AND id=?",keys)
                    add(MobileSession(sessionId, it.getString(1), it.getString(2), turns[sessionId].orEmpty(),
                        MobileDraft(field(3,"draftText"), readAssets(field(4,"draftAssets"))), it.getLong(5)))
                }
            }
        }
    }

    private fun withEdits(base: String, s: MobileSession): MobileSession {
        val edit = edits[base to s.id] ?: return s
        return s.copy(draft = s.draft.copy(text = edit.text), draftRevision = edit.revision)
    }

    @Synchronized fun sessions(base: String): List<MobileSession> {
        val key = normalizeBase(base)
        val saved = view(key).value.sessions
        return if (edits.isEmpty()) saved else saved.map { withEdits(key,it) }
    }

    @Synchronized fun preferenceJournal(base: String, sid: String) =
        readPreferenceStorage(helper.readableDatabase, PreferenceRoom(normalizeBase(base), sid))

    @Synchronized fun preferenceJournal(base: String, sid: String, edit: (PreferenceJournal) -> PreferenceJournal) =
        editPreferenceStorage(helper.writableDatabase, PreferenceRoom(normalizeBase(base), sid), edit)

    @Synchronized fun selected(base: String): MobileSession {
        val profile = view(normalizeBase(base)).value
        return profile.sessions.firstOrNull { it.id == profile.selected }?.let { withEdits(normalizeBase(base),it) } ?: create(base)
    }

    @Synchronized fun select(base: String, id: String) {
        val key = normalizeBase(base)
        val profile = view(key)
        if (profile.value.selected == id || profile.value.sessions.none { it.id == id }) return
        flushDrafts()
        helper.writableDatabase.execSQL("UPDATE profiles SET selected=? WHERE base=?", arrayOf(id, key))
        profile.value = profile.value.copy(selected = id)
    }

    @Synchronized fun create(base: String): MobileSession {
        flushDrafts()
        val key = normalizeBase(base)
        val profile = view(key)
        val session = MobileSession(uuid(), key)
        transaction { insertSession(it, key, session.id, key) }
        profile.value = ProfileView(session.id, listOf(session) + profile.value.sessions)
        return session
    }

    /** Recovered user-authored text has no remote route. Replaying recovery after process death
     * leaves an already restored/edited draft intact and never changes the open conversation. */
    @Synchronized fun restoreDeviceDraft(id: String,text: String) {
        val state = view("")
        if (state.value.sessions.any { it.id == id }) return
        val draft = MobileSession(id,manualName = "待核对的本机草稿",draft = MobileDraft(text))
        helper.writableDatabase.execSQL("INSERT INTO sessions(base,id,sharedBase,manualName,draftText) VALUES('',?,'',?,?)",
            arrayOf(id,draft.manualName,text))
        state.value = state.value.copy(sessions = listOf(draft) + state.value.sessions)
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
        edits.remove(key to id)
        val removed = old.sessions[index]
        val remaining = old.sessions.toMutableList().apply { removeAt(index) }
        val next = if (id == old.selected) (remaining.getOrNull(index) ?: remaining.lastOrNull())?.id else old.selected
        transaction { db ->
            db.execSQL("UPDATE profiles SET selected=? WHERE base=?", arrayOf(next, key))
            db.delete("sessions", "base=? AND id=?", arrayOf(key, id))
            db.delete("chat_preference", "profile=? AND sid=?", arrayOf(key, id))
        }
        profile.value = ProfileView(next, remaining)
        removeUnreferenced(removed.draft.attachments + removed.turns.flatMap { it.sent + it.replies })
    }

    @Synchronized fun draft(base: String, id: String, value: MobileDraft) =
        editDraft(base, id, value.attachments) { value }

    @Synchronized fun draftText(base: String, id: String, text: String) {
        val key = normalizeBase(base)
        val session = view(key).value.sessions.firstOrNull { it.id == id } ?: return
        val previous = edits[key to id]
        if ((previous?.text ?: session.draft.text) == text) return
        edits[key to id] = DraftEdit(text, (previous?.revision ?: session.draftRevision) + 1)
        scheduled?.cancel(false)
        scheduled = writer.schedule({ flushDrafts() }, 180, TimeUnit.MILLISECONDS)
    }

    /** Called on send, profile/session changes and backgrounding. Keystrokes only replace an
     * in-memory snapshot. The same monitor orders idle writes, deletes and draft consumption. */
    @Synchronized fun flushDrafts() {
        scheduled?.cancel(false)
        scheduled = null
        if (edits.isEmpty()) return
        transaction { db ->
            for ((key, edit) in edits) db.execSQL("UPDATE sessions SET draftText=?,draftRevision=? WHERE base=? AND id=?",
                arrayOf(edit.text,edit.revision,key.first,key.second))
        }
        for ((key, edit) in edits) {
            val state = view(key.first)
            val s = state.value.sessions.firstOrNull { it.id == key.second } ?: continue
            state.replace(s.copy(draft = s.draft.copy(text = edit.text), draftRevision = edit.revision))
        }
        edits.clear()
    }

    @Synchronized fun addAssets(base: String, id: String, assets: List<MobileAsset>) =
        editDraft(base, id, assets) { it.copy(attachments = it.attachments + assets) }

    @Synchronized fun removeAsset(base: String, id: String, asset: MobileAsset) =
        editDraft(base, id) { it.copy(attachments = it.attachments - asset) }

    private fun editDraft(base: String, id: String, incoming: List<MobileAsset> = emptyList(),
        edit: (MobileDraft) -> MobileDraft) {
        flushDrafts()
        val key = normalizeBase(base)
        val profile = view(key)
        val session = profile.value.sessions.firstOrNull { it.id == id } ?: return removeUnreferenced(incoming)
        val value = edit(session.draft)
        if (session.draft == value) return
        helper.writableDatabase.execSQL("UPDATE sessions SET draftText=?,draftAssets=?,draftRevision=draftRevision+1 WHERE base=? AND id=?",
            arrayOf(value.text, writeAssets(value.attachments), key, id))
        profile.replace(session.copy(draft = value, draftRevision = session.draftRevision + 1))
        removeUnreferenced(session.draft.attachments - value.attachments.toSet())
    }

    @Synchronized fun begin(base: String, id: String, turn: MobileTurn, consumeDraft: Boolean = true,
                            prepared: Boolean = false, expectedDraftRevision: Long? = null) {
        flushDrafts()
        val key = normalizeBase(base)
        val profile = view(key)
        val session = checkNotNull(profile.value.sessions.firstOrNull { it.id == id }) { "本机会话已删除。" }
        val preparedIndex = if (prepared) session.turns.indexOfFirst {
            it.id == turn.id && it.sent == turn.sent && it.question == turn.question && it.status == DEVICE_MEDIA_WAITING
        }.also { check(it >= 0) } else -1
        val initial = if (prepared) turn.copy(createdAt = session.turns[preparedIndex].createdAt) else turn
        val matches = consumeDraft && (expectedDraftRevision == null || expectedDraftRevision == session.draftRevision) &&
            session.draft.text.trim() == turn.question.trim() && session.draft.attachments == turn.sent
        transaction { db ->
            if (prepared) {
                check(db.update("turns",turnValues(initial),"base=? AND session=? AND id=?",
                    arrayOf(key,id,turn.id)) == 1)
            } else insertTurn(db, key, id, initial)
            if (matches) db.execSQL("UPDATE sessions SET draftText='',draftAssets='[]',draftRevision=draftRevision+1 WHERE base=? AND id=?", arrayOf(key, id))
        }
        val turns = if (prepared) session.turns.toMutableList().apply { set(preparedIndex,initial) } else session.turns + initial
        profile.replace(session.copy(turns = turns, draft = if (matches) MobileDraft() else session.draft,
            draftRevision = if (matches) session.draftRevision + 1 else session.draftRevision))
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

    @Synchronized fun recoverUnqueuedMedia(queuedIds: Set<String>) {
        val db = helper.writableDatabase
        val abandoned = db.rawQuery("SELECT base,session,id FROM turns WHERE status=?",arrayOf(DEVICE_MEDIA_WAITING)).use { c ->
            buildList { while (c.moveToNext()) if (c.getString(2) !in queuedIds) {
                add(Triple(c.getString(0),c.getString(1),c.getString(2)))
            } }
        }
        for ((base,sid,id) in abandoned) {
            val held = sessions(base).find { it.id == sid }?.turns?.find { it.id == id } ?: continue
            update(base,sid,held.copy(status = "未发送：设备待发记录未保存，请检查原附件。"))
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

// Android's CursorWindow is smaller than a supported service reply. Use byte slices
// for exceptional fields; concatenate them before decoding UTF-8 across slice boundaries.
internal fun smallSqlText(column: String) = "CASE WHEN length(CAST($column AS BLOB))<=262144 THEN $column END"

internal fun Cursor.fullSqlText(db: SQLiteDatabase, index: Int, column: String, table: String,
                                predicate: String, keys: Array<String>): String {
    if (!isNull(index)) return getString(index)
    val bytes = ByteArrayOutputStream()
    do {
        val part = db.rawQuery("SELECT substr(CAST($column AS BLOB),?,262144) FROM $table WHERE $predicate",
            arrayOf((bytes.size()+1).toString(), *keys)).use { check(it.moveToFirst()); it.getBlob(0) }
        bytes.write(part)
    } while (part.size == 262144)
    return bytes.toString("UTF-8")
}
