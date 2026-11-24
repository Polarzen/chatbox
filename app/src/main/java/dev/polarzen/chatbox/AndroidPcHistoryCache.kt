package dev.polarzen.chatbox

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale

internal data class PcDirectoryState(
    val name: String, val revision: String, val received: Int, val finished: Boolean,
    val published: Boolean, val expected: Int, val issue: String, val syncedAt: Long,
)

internal data class PcComposer(val draft: String, val pending: String?)

/** Independent of mobile-history.db and the original per-room keys. Removing a grant
 * deletes its credential, directory, bodies, hidden flags and drafts in one transaction. */
internal class AndroidPcHistoryCache(context: Context, private val databaseName: String = "pc-history.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 2) {
    private val alias = "chatbox.profile.$databaseName"
    private val cleanup = context.getSharedPreferences("pc-history-cleanup", Context.MODE_PRIVATE)
    private fun cipher(scope: String) = AndroidSharedCipher(alias, scope.toByteArray())

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA secure_delete=ON", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE grant_state(
            slot INTEGER PRIMARY KEY CHECK(slot=1), scope TEXT NOT NULL UNIQUE, credential BLOB NOT NULL,
            name TEXT NOT NULL, revision TEXT NOT NULL DEFAULT '', received INTEGER NOT NULL DEFAULT 0,
            finished INTEGER NOT NULL DEFAULT 0, published INTEGER NOT NULL DEFAULT 0,
            expected INTEGER NOT NULL DEFAULT 0, issue TEXT NOT NULL DEFAULT '', synced INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE session(
            scope TEXT NOT NULL REFERENCES grant_state(scope) ON DELETE CASCADE, sid TEXT NOT NULL,
            title TEXT NOT NULL, search_title TEXT NOT NULL, activity INTEGER NOT NULL, total INTEGER NOT NULL,
            imported INTEGER NOT NULL, reason TEXT NOT NULL, seen TEXT NOT NULL, hidden INTEGER NOT NULL DEFAULT 0,
            cursor INTEGER NOT NULL DEFAULT 0, draft TEXT NOT NULL DEFAULT '', pending TEXT,
            PRIMARY KEY(scope,sid))""")
        db.execSQL("CREATE INDEX directory_order ON session(scope,hidden,activity DESC,sid)")
        db.execSQL("""CREATE TABLE turn(
            scope TEXT NOT NULL, sid TEXT NOT NULL, seq INTEGER NOT NULL, id TEXT NOT NULL, question TEXT NOT NULL,
            answer TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(scope,sid,seq), UNIQUE(scope,sid,id),
            FOREIGN KEY(scope,sid) REFERENCES session(scope,sid) ON DELETE CASCADE)""")
        createTurnAliases(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "不支持的电脑历史缓存版本 $oldVersion → $newVersion" }
        createTurnAliases(db)
    }

    private fun createTurnAliases(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE turn_alias(
            scope TEXT NOT NULL, sid TEXT NOT NULL, id TEXT NOT NULL, seq INTEGER NOT NULL,
            PRIMARY KEY(scope,sid,id),
            FOREIGN KEY(scope,sid,seq) REFERENCES turn(scope,sid,seq) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX turn_alias_sequence ON turn_alias(scope,sid,seq)")
    }

    fun grant(): Pair<String, ProfileBinding>? {
        check(!cleanup.getBoolean(databaseName, false))
        return readableDatabase.rawQuery("SELECT scope,credential FROM grant_state", null).use {
            if (!it.moveToFirst()) return null
            val scope = it.getString(0)
            val v = JSONObject(cipher(scope).decrypt(it.getBlob(1)))
            scope to ProfileBinding(sharedAddress(v.getString("address"), false), v.getString("profile"),
                v.getString("instance"), v.getString("token"), false, v.getString("name"))
        }
    }

    fun bind(b: ProfileBinding): String {
        require(!b.owner)
        check(!cleanup.getBoolean(databaseName, false))
        val raw = JSONObject().put("address", sharedAddress(b.address, false)).put("profile", b.profile)
            .put("instance", b.instance).put("token", b.token).put("name", b.name).toString()
        // Service tokens are independently issued for each device authorization.
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val scope = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        writableDatabase.execSQL("INSERT INTO grant_state(slot,scope,credential,name) VALUES(1,?,?,?)",
            arrayOf(scope, cipher(scope).encrypt(raw, create = true), b.name))
        return scope
    }

    fun forget() {
        // Make revocation survive a failed SQLite delete or process death during a cascade.
        // Each independent invalidation is attempted even if the preceding store is unavailable.
        try {
            check(cleanup.edit().putBoolean(databaseName, true).commit())
        } finally {
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            } finally {
                writableDatabase.delete("grant_state", null, null)
            }
        }
        check(cleanup.edit().remove(databaseName).commit())
    }

    fun directory(scope: String): PcDirectoryState =
        readableDatabase.rawQuery("""SELECT name,revision,received,finished,published,expected,issue,synced
            FROM grant_state WHERE scope=?""", arrayOf(scope)).use {
            check(it.moveToFirst())
            PcDirectoryState(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3) != 0,
                it.getInt(4) != 0, it.getInt(5), it.getString(6), it.getLong(7))
        }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun applyDirectory(scope: String, page: ProfileDirectory, offset: Int, now: Long) = transaction { db ->
        for (row in page.sessions) {
            val v = ContentValues().apply {
                put("title", row.title)
                put("search_title", row.title.lowercase(Locale.ROOT))
                put("activity", row.activity)
                put("total", row.total)
                put("imported", row.imported)
                put("reason", row.reason)
                put("seen", page.revision)
            }
            if (db.update("session", v, "scope=? AND sid=?", arrayOf(scope, row.sid)) == 0) {
                v.put("scope", scope)
                v.put("sid", row.sid)
                db.insertOrThrow("session", null, v)
            }
        }
        if (!page.more) db.delete("session", "scope=? AND seen<>?", arrayOf(scope, page.revision))
        val v = ContentValues().apply {
            put("name", page.name)
            put("expected", page.expected)
            put("issue", page.issue)
            put("revision", page.revision)
            put("received", offset + page.sessions.size)
            put("finished", !page.more)
            put("published", page.complete)
            if (!page.more) put("synced", now)
        }
        check(db.update("grant_state", v, "scope=?", arrayOf(scope)) == 1)
    }

    fun touchDirectory(scope: String, page: ProfileDirectory, now: Long) {
        // First page confirms the same complete revision as the one already on disk.
        writableDatabase.execSQL("UPDATE grant_state SET name=?,expected=?,issue=?,published=?,synced=? WHERE scope=?",
            arrayOf(page.name, page.expected, page.issue, if (page.complete) 1 else 0, now, scope))
    }

    private val sessionFields = "sid,title,activity,total,imported,reason"
    private fun Cursor.session() = ProfileSession(getString(0), getString(1), getLong(2), getInt(3), getInt(4), getString(5))

    fun sessions(scope: String, search: String, hidden: Boolean, limit: Int): List<ProfileSession> =
        readableDatabase.rawQuery("""SELECT $sessionFields FROM session
            WHERE scope=? AND hidden=? AND instr(search_title,?)>0 ORDER BY activity DESC,sid LIMIT ?""",
            arrayOf(scope, if (hidden) "1" else "0", search.lowercase(Locale.ROOT), limit.toString())).use {
            buildList { while (it.moveToNext()) add(it.session()) }
        }

    fun session(scope: String, sid: String): ProfileSession? =
        readableDatabase.rawQuery("SELECT $sessionFields FROM session WHERE scope=? AND sid=?", arrayOf(scope, sid)).use {
            if (it.moveToFirst()) it.session() else null
        }

    fun hide(scope: String, sid: String, hidden: Boolean) {
        writableDatabase.execSQL("UPDATE session SET hidden=? WHERE scope=? AND sid=?",
            arrayOf(if (hidden) 1 else 0, scope, sid))
    }

    // Android CursorWindow cannot hold a large service reply (up to several MiB) in one row.
    // Read ordinary rows together; retrieve exceptional fields as bounded UTF-8 byte slices.
    private fun small(column: String) = "CASE WHEN length(CAST($column AS BLOB))<=262144 THEN $column END"

    private fun textField(row: Cursor, index: Int, column: String, table: String, predicate: String, keys: Array<String>): String {
        if (!row.isNull(index)) return row.getString(index)
        val out = ByteArrayOutputStream()
        do {
            val part = readableDatabase.rawQuery(
                "SELECT substr(CAST($column AS BLOB),?,262144) FROM $table WHERE $predicate",
                arrayOf((out.size() + 1).toString(), *keys)).use {
                check(it.moveToFirst())
                it.getBlob(0)
            }
            out.write(part)
        } while (part.size == 262144)
        return out.toString("UTF-8")
    }

    fun composer(scope: String, sid: String): PcComposer {
        val keys = arrayOf(scope, sid)
        return readableDatabase.rawQuery("SELECT ${small("draft")},pending FROM session WHERE scope=? AND sid=?", keys).use {
            check(it.moveToFirst())
            PcComposer(textField(it, 0, "draft", "session", "scope=? AND sid=?", keys), it.getString(1))
        }
    }

    fun draft(scope: String, sid: String, value: String) {
        writableDatabase.execSQL("UPDATE session SET draft=? WHERE scope=? AND sid=? AND pending IS NULL", arrayOf(value, scope, sid))
    }

    fun begin(scope: String, sid: String, id: String) {
        val values = ContentValues().apply { put("pending", id) }
        check(writableDatabase.update("session", values,
            "scope=? AND sid=? AND pending IS NULL AND draft<>'' AND reason=''", arrayOf(scope, sid)) == 1)
    }

    fun rejected(scope: String, sid: String, id: String) {
        writableDatabase.execSQL("UPDATE session SET pending=NULL WHERE scope=? AND sid=? AND pending=?", arrayOf(scope, sid, id))
    }

    fun cursor(scope: String, sid: String): Long =
        readableDatabase.rawQuery("SELECT cursor FROM session WHERE scope=? AND sid=?", arrayOf(scope, sid)).use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    fun applyTurns(scope: String, sid: String, rows: List<SharedTurn>, cursor: Long? = null) = transaction { db ->
        var pending = db.rawQuery("SELECT pending FROM session WHERE scope=? AND sid=?", arrayOf(scope, sid)).use {
            check(it.moveToFirst())
            it.getString(0)
        }
        for ((index, row) in rows.withIndex()) {
            if (cursor != null && index > 0 && rows[index - 1].seq == row.seq) {
                check(rows[index - 1].id == row.id) { "同一正文页给出了冲突的轮次编号。" }
            }
            mergeTurn(db, scope, sid, row, sequentialPage = cursor != null)
            if (row.id == pending) {
                // A directory/ID alias cannot acknowledge a different local draft.
                val matches = db.rawQuery("SELECT 1 FROM session WHERE scope=? AND sid=? AND draft=?",
                    arrayOf(scope, sid, row.question)).use { it.moveToFirst() }
                check(matches) { "发送回执的问题与本机待确认提问不一致。" }
                if (row.status != "running") {
                    db.execSQL("UPDATE session SET draft='',pending=NULL WHERE scope=? AND sid=? AND pending=?",
                        arrayOf(scope, sid, row.id))
                    pending = null
                }
            }
        }
        if (cursor != null) db.execSQL("UPDATE session SET cursor=? WHERE scope=? AND sid=?", arrayOf(cursor, scope, sid))
    }

    private fun mergeTurn(db: SQLiteDatabase, scope: String, sid: String, row: SharedTurn, sequentialPage: Boolean) {
        // Compare bodies inside SQLite. A complete answer can exceed Android's CursorWindow.
        val previousId = db.rawQuery("SELECT id,status,question=?,answer=? FROM turn WHERE scope=? AND sid=? AND seq=?",
            arrayOf(row.question, row.answer, scope, sid, row.seq.toString())).use { old ->
            if (!old.moveToFirst()) {
                return@use null
            }
            val sameId = old.getString(0) == row.id
            val oldStatus = old.getString(1)
            check(old.getInt(2) != 0) { "第 ${row.seq} 轮的问题与缓存不一致。" }
            if (sameId && oldStatus == "running") {
                db.execSQL("UPDATE turn SET answer=?,status=? WHERE scope=? AND sid=? AND seq=?",
                    arrayOf(row.answer, row.status, scope, sid, row.seq))
                return
            }
            check((oldStatus == "complete" || oldStatus == "unknown") && oldStatus == row.status && old.getInt(3) != 0) {
                "第 ${row.seq} 轮的回复或状态与缓存不一致。"
            }
            if (sameId) return
            old.getString(0)
        }
        val keys = arrayOf(scope, sid, row.id, row.seq.toString())
        val elsewhere = db.rawQuery("""SELECT 1 FROM turn WHERE scope=? AND sid=? AND id=? AND seq<>?
            UNION ALL SELECT 1 FROM turn_alias WHERE scope=? AND sid=? AND id=? AND seq<>? LIMIT 1""",
            keys + keys).use { it.moveToFirst() }
        check(!elsewhere) { "回执编号已属于本会话的另一轮。" }
        if (previousId == null) {
            db.execSQL("INSERT INTO turn(scope,sid,seq,id,question,answer,status) VALUES(?,?,?,?,?,?,?)",
                arrayOf(scope, sid, row.seq, row.id, row.question, row.answer, row.status))
            return
        }
        // A verified legacy attachment may replace an imported prefix UUID at this exact seq.
        // Only a checkpointed ordered page establishes the canonical UUID. A lookup by the
        // old request ID still returns that alias and must leave the canonical row in place.
        val alias = if (sequentialPage) previousId else row.id
        db.execSQL("INSERT OR IGNORE INTO turn_alias(scope,sid,id,seq) VALUES(?,?,?,?)",
            arrayOf(scope, sid, alias, row.seq))
        if (sequentialPage) {
            db.execSQL("UPDATE turn SET id=? WHERE scope=? AND sid=? AND seq=?",
                arrayOf(row.id, scope, sid, row.seq))
        }
    }

    fun turns(scope: String, sid: String, limit: Int): List<SharedTurn> = readableDatabase.rawQuery(
        """SELECT seq,id,${small("question")},${small("answer")},status FROM turn
            WHERE scope=? AND sid=? ORDER BY seq DESC LIMIT ?""", arrayOf(scope, sid, limit.toString())).use { c ->
        buildList {
            while (c.moveToNext()) {
                val seq = c.getLong(0)
                val keys = arrayOf(scope, sid, seq.toString())
                val predicate = "scope=? AND sid=? AND seq=?"
                add(SharedTurn(seq, c.getString(1), textField(c, 2, "question", "turn", predicate, keys),
                    textField(c, 3, "answer", "turn", predicate, keys), c.getString(4)))
            }
        }.asReversed()
    }
}
