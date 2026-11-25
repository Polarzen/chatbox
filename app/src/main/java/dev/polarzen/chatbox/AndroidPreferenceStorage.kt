package dev.polarzen.chatbox

import android.database.sqlite.SQLiteDatabase

internal fun createPreferenceStorage(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS chat_preference(profile TEXT NOT NULL,sid TEXT NOT NULL,record TEXT NOT NULL,
        PRIMARY KEY(profile,sid))""")
}

/** An empty sid stores the source-wide name separately from each conversation. */
internal fun readPreferenceStorage(db: SQLiteDatabase, key: PreferenceRoom): PreferenceJournal {
    require(key.sid.isNotEmpty())
    return db.rawQuery("SELECT sid,record FROM chat_preference WHERE profile=? AND sid IN ('',?)",
        arrayOf(key.profile,key.sid)).use { c ->
        var profile: String? = null
        var room: String? = null
        while (c.moveToNext()) {
            if (c.getString(0).isEmpty()) profile = c.getString(1) else room = c.getString(1)
        }
        PreferenceJournal.decode(profile,room)
    }
}

internal fun editPreferenceStorage(db: SQLiteDatabase, key: PreferenceRoom,
                                  edit: (PreferenceJournal) -> PreferenceJournal): PreferenceJournal {
    db.beginTransaction()
    try {
        val old = readPreferenceStorage(db,key)
        val next = edit(old)
        if (next != old) {
            for ((sid,profile) in listOf("" to true,key.sid to false)) {
                val record = next.encode(profile)
                if (record != old.encode(profile)) db.execSQL("INSERT OR REPLACE INTO chat_preference VALUES(?,?,?)",
                    arrayOf(key.profile,sid,record))
            }
        }
        db.setTransactionSuccessful()
        return next
    } finally { db.endTransaction() }
}
