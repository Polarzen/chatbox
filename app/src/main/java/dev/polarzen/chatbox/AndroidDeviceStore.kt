package dev.polarzen.chatbox

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.Closeable
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class DeviceRoomKey(val profile: String, val sid: String)
internal data class DeviceLocalKey(val base: String, val sid: String)
internal data class DeviceImportLink(val local: DeviceLocalKey, val remote: DeviceRoomKey, val count: Int,
                                     val title: String, val legacyAlias: Boolean = false)
internal data class DeviceQueued(val key: DeviceRoomKey, val id: String, val question: String,
                                val original: String?, val local: DeviceLocalKey?, val start: Boolean,
                                val media: Boolean, val revision: Long, val dispatched: Boolean = false,
                                val authored: String = original ?: question, val mediaKeyId: String? = null,
                                val nativePrompt: String? = null, val finalStatus: String? = null, val finalAnswer: String? = null)
internal data class DeviceRoomDraft(val key: DeviceRoomKey, val text: String, val created: Long)

/** The only device-wide grant and its complete remote cache live in this database. Native history
 * and both legacy grants are independent. An interrupted invalidation resumes before cache reads. */
internal class AndroidDeviceStore(context: Context, name: String = "device-sync.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 4), Closeable {
    private val alias = "chatbox.device.$name"
    private val cipher = AndroidSharedCipher(alias, name.toByteArray(Charsets.UTF_8))
    private val prefs = context.getSharedPreferences("device-sync-cleanup", Context.MODE_PRIVATE)
    private val staged = mutableMapOf<DeviceRoomKey,DeviceRoomDraft>()
    private val writer = ScheduledThreadPoolExecutor(1) { job ->
        Thread(job, "chatbox-device-drafts").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var scheduled: ScheduledFuture<*>? = null

    override fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA secure_delete=ON", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE grant_state(slot INTEGER PRIMARY KEY CHECK(slot=1),credential BLOB NOT NULL,confirmed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE profile(id TEXT PRIMARY KEY,source TEXT,base TEXT,username TEXT,name TEXT,origin TEXT,upstream TEXT,available INTEGER)")
        db.execSQL("CREATE TABLE directory(profile TEXT,sid TEXT,title TEXT,activity INTEGER,total INTEGER,imported INTEGER,reason TEXT,origin TEXT,PRIMARY KEY(profile,sid))")
        db.execSQL("CREATE TABLE body(profile TEXT,sid TEXT,seq INTEGER,id TEXT,question TEXT,answer TEXT,status TEXT,PRIMARY KEY(profile,sid,seq),UNIQUE(profile,sid,id))")
        db.execSQL("CREATE TABLE local_link(base TEXT,nativeSid TEXT,profile TEXT,sid TEXT,imported INTEGER,title TEXT,legacyAlias INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(base,nativeSid))")
        db.execSQL("""CREATE TABLE outbox(profile TEXT,sid TEXT,id TEXT UNIQUE,question TEXT,original TEXT,base TEXT,
            nativeSid TEXT,start INTEGER,media INTEGER,revision INTEGER,dispatched INTEGER,authored TEXT,
            mediaKeyId TEXT,nativePrompt TEXT,finalStatus TEXT,finalAnswer TEXT,PRIMARY KEY(profile,sid))""")
        db.execSQL("CREATE TABLE recovery(id TEXT PRIMARY KEY,text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE draft(profile TEXT,sid TEXT,text TEXT,created INTEGER,PRIMARY KEY(profile,sid))")
        db.execSQL("CREATE TABLE hidden(profile TEXT,sid TEXT,PRIMARY KEY(profile,sid))")
        db.execSQL("CREATE TABLE retired(profile TEXT,sid TEXT,PRIMARY KEY(profile,sid))")
        db.execSQL("CREATE TABLE local_operation(base TEXT,sid TEXT,id TEXT,PRIMARY KEY(base,sid,id))")
        db.execSQL("CREATE TABLE page_cursor(profile TEXT,sid TEXT,seq INTEGER,PRIMARY KEY(profile,sid))")
        db.execSQL("CREATE TABLE trusted_address(address TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..3 && newVersion == 4)
        db.execSQL("CREATE TABLE IF NOT EXISTS retired(profile TEXT,sid TEXT,PRIMARY KEY(profile,sid))")
        db.execSQL("CREATE TABLE IF NOT EXISTS trusted_address(address TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS page_cursor(profile TEXT,sid TEXT,seq INTEGER,PRIMARY KEY(profile,sid))")
        fun columns(table: String) = db.rawQuery("PRAGMA table_info($table)",null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        if ("legacyAlias" !in columns("local_link")) db.execSQL("ALTER TABLE local_link ADD COLUMN legacyAlias INTEGER NOT NULL DEFAULT 0")
        val columns = columns("outbox")
        if ("revision" !in columns) db.execSQL("ALTER TABLE outbox ADD COLUMN revision INTEGER NOT NULL DEFAULT -1")
        if ("authored" !in columns) db.execSQL("ALTER TABLE outbox ADD COLUMN authored TEXT")
        for (field in listOf("mediaKeyId","nativePrompt","finalStatus","finalAnswer")) {
            if (field !in columns) db.execSQL("ALTER TABLE outbox ADD COLUMN $field TEXT")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS recovery(id TEXT PRIMARY KEY,text TEXT NOT NULL)")
    }

    @Synchronized override fun close() {
        try { flushDrafts() } finally { writer.shutdownNow(); super.close() }
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun <T> query(sql: String, args: Array<String>? = null, row: (Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    fun grant(): Pair<DeviceBinding, Boolean>? {
        if (prefs.getBoolean(alias, false)) forget()
        return query("SELECT credential,confirmed FROM grant_state") {
            val obj = JSONObject(cipher.decrypt(it.getBlob(0)))
            DeviceBinding(sharedAddress(obj.getString("address"), false), obj.getString("instance"),
                obj.getString("device"), obj.getString("token"), false) to (it.getInt(1) != 0)
        }.singleOrNull()
    }

    fun saveGrant(b: DeviceBinding, confirmed: Boolean) {
        require(!b.owner && !prefs.getBoolean(alias, false))
        val secret = JSONObject().put("address", b.address).put("instance", b.instance).put("device", b.device)
            .put("token", b.token).toString()
        transaction { db ->
            db.execSQL("INSERT OR REPLACE INTO grant_state VALUES(1,?,?)",
                arrayOf(cipher.encrypt(secret, create = true), if (confirmed) 1 else 0))
            if (confirmed) db.execSQL("INSERT OR IGNORE INTO trusted_address VALUES(?)", arrayOf(b.address))
        }
    }
    fun trustedAddresses() = query("SELECT address FROM trusted_address") { it.getString(0) }.toSet()

    @Synchronized fun forget(history: AndroidHistory? = null) {
        check(prefs.edit().putBoolean(alias, true).commit())
        try {
            flushDrafts()
            transaction { db ->
                // Transfer only locally authored text. IDs of recovered native drafts expose no
                // endpoint, remote title, service-side messages, source username or credential.
                val drafts = drafts().filter { it.text.isNotBlank() }.associateBy { it.key }
                for ((key,draft) in drafts) recover(db,"draft\u0000${key.profile}\u0000${key.sid}",draft.text)
                for (op in pending()) {
                    val local = op.local
                    val originalStillInHistory = local != null && history?.sessions(local.base)
                        ?.firstOrNull { it.id == local.sid }?.draft?.text == op.authored
                    if (!op.media && !originalStillInHistory && op.authored.isNotBlank() && drafts[op.key]?.text != op.authored)
                        recover(db,op.id,op.authored)
                }
                for (table in listOf("grant_state","profile","directory","body","local_link","outbox","draft","hidden","retired","local_operation","page_cursor","trusted_address"))
                    db.delete(table, null, null)
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
        check(prefs.edit().remove(alias).commit())
    }

    fun profiles() = query("SELECT * FROM profile") {
        DeviceProfile(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4),
            it.getString(5), it.getString(6), it.getInt(7) != 0)
    }

    fun directory() = query("SELECT * FROM directory") {
        DeviceSession(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getInt(4),
            it.getInt(5), it.getString(6), it.getString(7))
    }

    fun replaceDirectory(profiles: List<DeviceProfile>, sessions: List<DeviceSession>) = transaction { db ->
        db.delete("profile", null, null)
        profiles.forEach {
            db.execSQL("INSERT INTO profile VALUES(?,?,?,?,?,?,?,?)", arrayOf(it.profile, it.sourceId, it.base,
                it.username, it.name, it.originDevice, it.upstream, if (it.available) 1 else 0))
        }
        db.execSQL("INSERT OR IGNORE INTO retired SELECT profile,sid FROM directory")
        db.delete("directory", null, null)
        sessions.forEach {
            saveEntry(db,it)
        }
        db.execSQL("DELETE FROM retired WHERE EXISTS(SELECT 1 FROM directory WHERE profile=retired.profile AND sid=retired.sid)")
        // Only an entire revision may retire a cached remote body.
        db.execSQL("""DELETE FROM body WHERE NOT EXISTS(SELECT 1 FROM directory d
            WHERE d.profile=body.profile AND d.sid=body.sid) AND NOT EXISTS(SELECT 1 FROM outbox o
            WHERE o.profile=body.profile AND o.sid=body.sid)""")
        db.execSQL("""DELETE FROM page_cursor WHERE NOT EXISTS(SELECT 1 FROM body b
            WHERE b.profile=page_cursor.profile AND b.sid=page_cursor.sid)""")
    }

    private fun saveEntry(db: SQLiteDatabase,entry: DeviceSession) = with(entry) {
        db.execSQL("INSERT OR REPLACE INTO directory VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(profile,sid,title,activity,total,imported,reason,originDevice))
    }

    fun saveAccepted(entry: DeviceSession,turn: SharedTurn) = transaction { db ->
        saveEntry(db,entry)
        saveTurn(db,DeviceRoomKey(entry.profile,entry.sid),turn)
    }

    fun links() = query("SELECT base,nativeSid,profile,sid,imported,title,legacyAlias FROM local_link") {
        DeviceImportLink(DeviceLocalKey(it.getString(0), it.getString(1)), DeviceRoomKey(it.getString(2), it.getString(3)),
            it.getInt(4), it.getString(5), it.getInt(6) != 0)
    }

    fun link(link: DeviceImportLink) = with(link) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO local_link VALUES(?,?,?,?,?,?,?)",
            arrayOf(local.base, local.sid, remote.profile, remote.sid, count, title,if (legacyAlias) 1 else 0))
    }

    fun pending() = query("""SELECT profile,sid,id,question,original,base,nativeSid,start,media,revision,dispatched,
        COALESCE(authored,original,question),mediaKeyId,nativePrompt,finalStatus,${smallSqlText("finalAnswer")} FROM outbox""") {
        DeviceQueued(DeviceRoomKey(it.getString(0), it.getString(1)), it.getString(2), it.getString(3),
            it.getString(4), if (it.isNull(5)) null else DeviceLocalKey(it.getString(5), it.getString(6)),
            it.getInt(7) != 0, it.getInt(8) != 0, it.getLong(9), it.getInt(10) != 0,it.getString(11),
            it.getString(12),it.getString(13),it.getString(14),if (it.isNull(14)) null else
                it.fullSqlText(readableDatabase,15,"finalAnswer","outbox","id=?",arrayOf(it.getString(2))))
    }

    fun begin(op: DeviceQueued) = transaction { db ->
        with(op) {
            db.execSQL("""INSERT INTO outbox(profile,sid,id,question,original,base,nativeSid,start,media,revision,dispatched,
                authored,mediaKeyId,nativePrompt) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?)""", arrayOf(key.profile, key.sid, id,
                question, original, local?.base, local?.sid, if (start) 1 else 0, if (media) 1 else 0, revision,authored,mediaKeyId,nativePrompt))
            if (local != null) db.execSQL("INSERT INTO local_operation VALUES(?,?,?)", arrayOf(local.base,local.sid,id))
        }
    }

    fun claimMedia(id: String): Boolean = writableDatabase.update("outbox",ContentValues().apply { put("dispatched",1) },
        "id=? AND media=1 AND dispatched=0",arrayOf(id)) == 1

    fun mediaResult(id: String,status: String,answer: String) {
        check(writableDatabase.update("outbox",ContentValues().apply { put("finalStatus",status); put("finalAnswer",answer) },
            "id=? AND media=1 AND dispatched=1 AND finalStatus IS NULL",arrayOf(id)) == 1)
    }
    fun move(id: String, key: DeviceRoomKey) =
        writableDatabase.execSQL("UPDATE outbox SET profile=?,sid=? WHERE id=?", arrayOf(key.profile,key.sid,id))
    fun release(id: String) { writableDatabase.delete("outbox", "id=?", arrayOf(id)) }
    private fun recover(db: SQLiteDatabase, seed: String, text: String) {
        val id = UUID.nameUUIDFromBytes("chatbox-recovery-v1\u0000$seed\u0000$text".toByteArray(Charsets.UTF_8)).toString()
        db.execSQL("INSERT OR IGNORE INTO recovery VALUES(?,?)",arrayOf(id,text))
    }

    fun reject(op: DeviceQueued, preserve: Boolean) = transaction { db ->
        if (preserve && op.authored.isNotBlank()) recover(db,op.id,op.authored)
        db.delete("outbox","id=?",arrayOf(op.id))
    }

    fun restoreDrafts(history: AndroidHistory) {
        for ((id,text) in query("SELECT id,${smallSqlText("text")} FROM recovery") {
            it.getString(0) to it.fullSqlText(readableDatabase,1,"text","recovery","id=?",arrayOf(it.getString(0)))
        }) {
            history.restoreDeviceDraft(id,text)
            writableDatabase.delete("recovery","id=?",arrayOf(id))
        }
    }
    fun owned(base: String, sid: String) = query("SELECT id FROM local_operation WHERE base=? AND sid=?",
        arrayOf(base,sid)) { it.getString(0) }.toSet()

    fun drafts() = query("SELECT profile,sid,${smallSqlText("text")},created FROM draft") {
        val key = DeviceRoomKey(it.getString(0),it.getString(1))
        DeviceRoomDraft(key,it.fullSqlText(readableDatabase,2,"text","draft","profile=? AND sid=?",arrayOf(key.profile,key.sid)),it.getLong(3))
    }

    @Synchronized fun stageDraft(key: DeviceRoomKey, value: String, created: Long) {
        staged[key] = DeviceRoomDraft(key,value,created)
        scheduled?.cancel(false)
        scheduled = writer.schedule({ flushDrafts() }, 180, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun flushDrafts() {
        scheduled?.cancel(false)
        scheduled = null
        if (staged.isEmpty()) return
        transaction { db ->
            for ((key, draft) in staged) {
                db.execSQL("INSERT OR REPLACE INTO draft VALUES(?,?,?,?)", arrayOf(key.profile,key.sid,draft.text,draft.created))
                db.execSQL("UPDATE outbox SET original=NULL WHERE profile=? AND sid=?", arrayOf(key.profile,key.sid))
            }
        }
        staged.clear()
    }

    fun draft(key: DeviceRoomKey, value: String, created: Long) { stageDraft(key,value,created); flushDrafts() }

    fun running() = query("SELECT DISTINCT profile,sid FROM body WHERE status='running'") {
        DeviceRoomKey(it.getString(0),it.getString(1))
    }

    fun hidden() = query("SELECT profile,sid FROM hidden") { DeviceRoomKey(it.getString(0), it.getString(1)) }.toSet()
    fun retired() = query("SELECT profile,sid FROM retired") { DeviceRoomKey(it.getString(0),it.getString(1)) }.toSet()
    fun hide(key: DeviceRoomKey) = writableDatabase.execSQL("INSERT OR IGNORE INTO hidden VALUES(?,?)", arrayOf(key.profile,key.sid))

    fun saveTurns(key: DeviceRoomKey, turns: List<SharedTurn>, cursor: Long? = null) = transaction { db ->
        for (turn in turns) saveTurn(db,key,turn)
        if (cursor != null) db.execSQL("INSERT OR REPLACE INTO page_cursor VALUES(?,?,?)",
            arrayOf(key.profile,key.sid,cursor))
    }

    private fun saveTurn(db: SQLiteDatabase,key: DeviceRoomKey,turn: SharedTurn) = with(turn) {
        // A canonical legacy attachment can change the operation ID at the same sequence.
        db.execSQL("INSERT OR REPLACE INTO body VALUES(?,?,?,?,?,?,?)",
            arrayOf(key.profile,key.sid,seq,id,question,answer,status))
    }

    fun cursor(key: DeviceRoomKey) = query("SELECT seq FROM page_cursor WHERE profile=? AND sid=?",
        arrayOf(key.profile,key.sid)) { it.getLong(0) }.singleOrNull() ?: 0L

    fun turns(key: DeviceRoomKey): List<SharedTurn> {
        // Replies can exceed a CursorWindow. Read oversized fields in bounded UTF-8 byte slices.
        val args = arrayOf(key.profile,key.sid)
        return query("SELECT seq,id,${smallSqlText("question")},${smallSqlText("answer")},status FROM body WHERE profile=? AND sid=? ORDER BY seq", args) { c ->
            fun field(index: Int, column: String) = c.fullSqlText(readableDatabase,index,column,"body",
                "profile=? AND sid=? AND seq=?", arrayOf(*args,c.getLong(0).toString()))
            SharedTurn(c.getLong(0), c.getString(1), field(2,"question"), field(3,"answer"), c.getString(4))
        }
    }
}
