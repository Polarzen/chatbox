"""SQLite storage for profile directories and their independently uploaded PC transcripts."""

import time


def migrate_profiles(db):
    # The caller owns a single immediate transaction, including on a 0.1.17 database.
    # executescript() implicitly commits the caller's transaction.
    statements = (
        """CREATE TABLE IF NOT EXISTS profiles(
               id TEXT PRIMARY KEY, source_id TEXT NOT NULL, base TEXT NOT NULL, username TEXT NOT NULL,
               name TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, complete INTEGER NOT NULL DEFAULT 0,
               expected INTEGER NOT NULL DEFAULT 0, issue TEXT NOT NULL DEFAULT '',
               UNIQUE(base,username,source_id))""",
        """CREATE TABLE IF NOT EXISTS profile_devices(
               id TEXT PRIMARY KEY, digest BLOB NOT NULL UNIQUE, profile TEXT NOT NULL REFERENCES profiles,
               name TEXT NOT NULL, owner INTEGER NOT NULL CHECK(owner IN (0,1)))""",
        """CREATE TABLE IF NOT EXISTS profile_codes(
               digest BLOB PRIMARY KEY, profile TEXT UNIQUE REFERENCES profiles, expires INTEGER NOT NULL)""",
        """CREATE UNIQUE INDEX IF NOT EXISTS one_profile_bootstrap ON profile_codes((1)) WHERE profile IS NULL""",
        """CREATE TABLE IF NOT EXISTS profile_recovery(
               profile TEXT PRIMARY KEY REFERENCES profiles, digest BLOB NOT NULL UNIQUE, expires INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS profile_sessions(
               profile TEXT NOT NULL REFERENCES profiles, sid TEXT NOT NULL, title TEXT NOT NULL,
               activity INTEGER NOT NULL, total INTEGER NOT NULL, imported INTEGER NOT NULL DEFAULT 0,
               room TEXT REFERENCES rooms, ready INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '',
               fresh INTEGER NOT NULL DEFAULT 0,
               PRIMARY KEY(profile,sid))""",
        """CREATE INDEX IF NOT EXISTS profile_recent ON profile_sessions(profile,activity DESC,sid)""",
        """CREATE TABLE IF NOT EXISTS profile_imports(
               profile TEXT NOT NULL, sid TEXT NOT NULL, seq INTEGER NOT NULL, id TEXT NOT NULL,
               question TEXT NOT NULL, answer TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('complete','unknown')),
               PRIMARY KEY(profile,sid,seq), UNIQUE(profile,sid,id),
               FOREIGN KEY(profile,sid) REFERENCES profile_sessions(profile,sid) ON DELETE CASCADE)""",
        """CREATE TRIGGER IF NOT EXISTS profile_add AFTER INSERT ON profile_sessions BEGIN
               UPDATE profiles SET revision=revision+1 WHERE id=NEW.profile; END""",
        """CREATE TRIGGER IF NOT EXISTS profile_remove AFTER DELETE ON profile_sessions BEGIN
               UPDATE profiles SET revision=revision+1 WHERE id=OLD.profile; END""",
        """CREATE TRIGGER IF NOT EXISTS profile_update AFTER UPDATE ON profile_sessions
               WHEN OLD.title<>NEW.title OR OLD.activity<>NEW.activity OR OLD.total<>NEW.total
                 OR OLD.imported<>NEW.imported OR OLD.reason<>NEW.reason OR OLD.ready<>NEW.ready
                 OR OLD.room IS NOT NEW.room BEGIN
               UPDATE profiles SET revision=revision+1 WHERE id=NEW.profile; END""",
    )
    for statement in statements:
        db.execute(statement)
    if "launcher_id" not in {row[1] for row in db.execute("PRAGMA table_info(profiles)")}:
        db.execute("ALTER TABLE profiles ADD COLUMN launcher_id TEXT")
    columns = {row[1] for row in db.execute("PRAGMA table_info(profile_sessions)")}
    for name in ("removed", "generation"):
        if name not in columns:
            db.execute(f"ALTER TABLE profile_sessions ADD COLUMN {name} INTEGER NOT NULL DEFAULT 0")
    if not db.execute("SELECT 1 FROM sqlite_master WHERE type='index' AND name='one_profile_per_room'").fetchone():
        # Early preview databases could contain competing grants without an attachment timestamp.
        # Preserve every PC upload. Require fresh proof to select the intended authority.
        db.execute("""UPDATE profile_sessions SET room=NULL,ready=0,fresh=0,
                      reason='原房间曾关联多个档案，须在目标档案重新核实主人授权。'
                      WHERE room IN (SELECT room FROM profile_sessions WHERE room IS NOT NULL
                                     GROUP BY room HAVING count(*)>1)""")
        db.execute("CREATE UNIQUE INDEX one_profile_per_room ON profile_sessions(room) WHERE room IS NOT NULL")
        db.execute("DROP INDEX IF EXISTS profiles_by_room")
    db.execute("""CREATE TRIGGER IF NOT EXISTS profile_visibility AFTER UPDATE OF removed ON profile_sessions
                  WHEN OLD.removed<>NEW.removed BEGIN
                  UPDATE profiles SET revision=revision+1 WHERE id=NEW.profile; END""")


def profile_activity(db, room):
    db.execute("UPDATE profile_sessions SET activity=MAX(activity,?) WHERE room=? AND ready=1 AND removed=0",
               (int(time.time() * 1000), room))


def reconcile(db, entry):
    """Make a complete, authorized PC prefix visible with its original room's authoritative suffix."""
    if entry["ready"] or entry["reason"] or not entry["room"] or entry["imported"] != entry["total"]:
        return
    profile, sid, room = entry["profile"], entry["sid"], entry["room"]
    key = (profile, sid)
    mismatch = db.execute("""
        SELECT p.seq FROM profile_imports p JOIN turns t ON t.room=? AND t.seq=p.seq
        WHERE p.profile=? AND p.sid=? AND
              (p.question<>t.question OR p.answer<>t.answer OR p.status<>t.status) ORDER BY p.seq LIMIT 1
        """, (room, *key)).fetchone()
    last = db.execute("SELECT COALESCE(MAX(seq),0) FROM turns WHERE room=?", (room,)).fetchone()[0]
    reason = ""
    if mismatch:
        reason = f"电脑历史第 {mismatch[0]} 轮与原共享房间不一致。可阅读电脑上传的记录，无法接续。"
    else:
        collision = db.execute("""
            SELECT p.seq FROM profile_imports p JOIN turns t ON t.room=? AND t.id=p.id
            WHERE p.profile=? AND p.sid=? AND p.seq<>t.seq ORDER BY p.seq LIMIT 1
            """, (room, *key)).fetchone()
        if collision:
            reason = f"电脑历史第 {collision[0]} 轮的编号已在原房间另一位置使用，无法接续。"
    if not reason and last < entry["total"]:
        busy = db.execute("""SELECT 1 FROM turns WHERE room=? AND (status='running' OR needs_review=1)
                             UNION ALL SELECT 1 FROM device_context_admissions WHERE room=? AND state='pending' LIMIT 1""",
                          (room, room)).fetchone()
        if busy:
            reason = "原共享房间有正在生成或结果未知的轮次，暂时无法合并电脑历史。"
        else:
            db.execute("""
                INSERT INTO turns(room,seq,id,question,answer,status,needs_review)
                SELECT ?,seq,id,question,answer,status,(seq=? AND status='unknown')
                FROM profile_imports WHERE profile=? AND sid=? AND seq>? ORDER BY seq
                """, (room, entry["total"], *key, last))
    db.execute("UPDATE profile_sessions SET reason=?,ready=? WHERE profile=? AND sid=?",
               (reason, int(not reason), *key))
