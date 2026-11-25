"""Device chat preferences stored under the authoritative profile and session IDs."""

import json
import unicodedata

if __package__:
    from . import service as core
else:
    import service as core


FIELDS = frozenset(("profileName", "peerName", "recentTurns", "pins", "remindAfter"))


def migrate(db):
    if "display_name" not in {r[1] for r in db.execute("PRAGMA table_info(device_catalog)")}:
        db.execute("ALTER TABLE device_catalog ADD COLUMN display_name TEXT")
    db.execute("""CREATE TABLE IF NOT EXISTS chat_preferences(
                  profile TEXT NOT NULL, sid TEXT NOT NULL, peer_name TEXT, recent_turns INTEGER,
                  pins TEXT NOT NULL DEFAULT '[]', remind_after INTEGER, PRIMARY KEY(profile,sid),
                  FOREIGN KEY(profile,sid) REFERENCES profile_sessions(profile,sid) ON DELETE CASCADE)""")
    db.execute("""CREATE TABLE IF NOT EXISTS chat_reminder_claims(
                  profile TEXT NOT NULL, sid TEXT NOT NULL, threshold INTEGER NOT NULL,
                  PRIMARY KEY(profile,sid,threshold),
                  FOREIGN KEY(profile,sid) REFERENCES chat_preferences(profile,sid) ON DELETE CASCADE)""")
    db.execute("""CREATE TABLE IF NOT EXISTS chat_context_notices(
                  profile TEXT NOT NULL, sid TEXT NOT NULL, kind TEXT NOT NULL, through INTEGER NOT NULL,
                  active INTEGER NOT NULL, PRIMARY KEY(profile,sid,kind),
                  FOREIGN KEY(profile,sid) REFERENCES chat_preferences(profile,sid) ON DELETE CASCADE)""")


SELECT_PREFERENCES = """SELECT c.display_name,p.peer_name,p.recent_turns,p.pins,p.remind_after
                        FROM profile_sessions s JOIN device_catalog c ON c.profile=s.profile
                        LEFT JOIN chat_preferences p ON p.profile=s.profile AND p.sid=s.sid"""


def _preferences(row):
    name, peer, recent, pins, remind = row or (None, None, None, None, None)
    return dict(profileName=name, peerName=peer, recentTurns=recent,
                pins=json.loads(pins) if pins else [], remindAfter=remind)


def read_preferences(db, entry):
    """Read after session_row authorization, within the same transaction."""
    row = db.execute(SELECT_PREFERENCES + " WHERE s.profile=? AND s.sid=?",
                     (entry["profile"], entry["sid"])).fetchone()
    return _preferences(row)


def preferences_for_room(db, room):
    """Read for an already-authorized upstream dispatch. Unconfigured rooms use defaults."""
    row = db.execute(SELECT_PREFERENCES + """
        LEFT JOIN device_session_state d ON d.profile=s.profile AND d.sid=s.sid
        WHERE s.room=? AND s.ready=1 AND s.removed=0 AND COALESCE(d.removed,0)=0""", (room,)).fetchone()
    return _preferences(row)


def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise core.ApiError(400, f"请输入 {low} 至 {high} 的整数。")
    return value


def normalize(field, value):
    if field == "pins":
        if not isinstance(value, list) or len(value) > 128:
            raise core.ApiError(400, "最多可固定 128 条消息。")
        pins = set()
        for pin in value:
            core.fields(pin, ("seq", "role"))
            seq = integer(pin["seq"], 1, 2**63 - 1)
            if pin["role"] not in ("user", "assistant"):
                raise core.ApiError(400, "固定消息的角色无效。")
            pins.add((seq, pin["role"]))
        return [dict(seq=seq, role=role) for seq, role in sorted(pins, key=lambda p: (p[0], p[1] == "assistant"))]
    if value is None:
        return None
    if field in ("profileName", "peerName"):
        if not isinstance(value, str) or any(unicodedata.category(c) in ("Cc", "Cs") for c in value):
            raise core.ApiError(400, "显示名称不能包含控制字符。")
        name = value.strip()
        if not 1 <= len(name) <= 40:
            raise core.ApiError(400, "显示名称须为 1 至 40 个字符。")
        return name
    if field == "recentTurns":
        return integer(value, 0, 500)
    return integer(value, 1, 10000)


def _completed_source(entry):
    if entry["ready"]:
        return "turns WHERE room=? AND status='complete'", (entry["room"],)
    return "profile_imports WHERE profile=? AND sid=? AND status='complete'", (entry["profile"], entry["sid"])


def _validate_pins(db, entry, pins):
    if not pins:
        return
    source, args = _completed_source(entry)
    seqs = sorted({pin["seq"] for pin in pins})
    placeholders = ",".join("?" for _ in seqs)
    rows = db.execute(f"""SELECT seq,question<>'',answer<>'' FROM {source}
                          AND seq IN ({placeholders})""", (*args, *seqs))
    available = {(seq, role) for seq, question, answer in rows
                 for role, exists in (("user", question), ("assistant", answer)) if exists}
    if any((pin["seq"], pin["role"]) not in available for pin in pins):
        raise core.ApiError(400, "只能固定已完成轮次中的现有消息。")


def update_preferences(db, entry, expected, changes):
    if (not isinstance(expected, dict) or not isinstance(changes, dict) or not changes
            or not changes.keys() <= expected.keys() <= FIELDS):
        raise core.ApiError(400, "请提供每项设置的原值和新值。")
    current = read_preferences(db, entry)
    old_selection = (current["recentTurns"], current["pins"])
    for field, value in changes.items():
        after = normalize(field, value)
        before = normalize(field, expected[field])
        if current[field] != before and current[field] != after:
            raise core.ApiError(409, "此项设置已在其他设备修改，请刷新。")
        current[field] = after
    if current["pins"] and current["recentTurns"] is None:
        raise core.ApiError(400, "固定消息前请选择保留最近多少轮。")
    if "pins" in changes:
        _validate_pins(db, entry, current["pins"])
    if "profileName" in changes:
        db.execute("UPDATE device_catalog SET display_name=? WHERE profile=?", (current["profileName"], entry["profile"]))
    if changes.keys() - {"profileName"}:
        db.execute("""INSERT INTO chat_preferences(profile,sid,peer_name,recent_turns,pins,remind_after)
                      VALUES(?,?,?,?,?,?) ON CONFLICT(profile,sid) DO UPDATE SET peer_name=excluded.peer_name,
                      recent_turns=excluded.recent_turns,pins=excluded.pins,remind_after=excluded.remind_after""",
                   (entry["profile"], entry["sid"], current["peerName"], current["recentTurns"],
                    json.dumps(current["pins"], separators=(",", ":")), current["remindAfter"]))
    if old_selection != (current["recentTurns"], current["pins"]):
        db.execute("DELETE FROM chat_context_notices WHERE profile=? AND sid=?", (entry["profile"], entry["sid"]))
    return current


def claim_reminder(db, entry, threshold):
    threshold = integer(threshold, 1, 10000)
    key = (entry["profile"], entry["sid"], threshold)
    if not db.execute("SELECT 1 FROM chat_preferences WHERE profile=? AND sid=? AND remind_after=?", key).fetchone():
        return {"claimed": False}
    source, args = _completed_source(entry)
    # The threshold-th completed turn proves eligibility without materializing the transcript.
    if not db.execute(f"SELECT 1 FROM {source} LIMIT 1 OFFSET ?", (*args, threshold - 1)).fetchone():
        return {"claimed": False}
    claimed = db.execute("INSERT OR IGNORE INTO chat_reminder_claims(profile,sid,threshold) VALUES(?,?,?)", key)
    return {"claimed": bool(claimed.rowcount)}


def context_notice(db, entry, kind, through, active):
    if kind not in ("capacity", "rolling") or type(active) is not bool:
        raise core.ApiError(400, "预警类型或状态无效。")
    through = integer(through, 0, 2**63 - 1)
    target = (entry["profile"], entry["sid"])
    if not db.execute("SELECT 1 FROM chat_preferences WHERE profile=? AND sid=? AND recent_turns IS NOT NULL",
                      target).fetchone():
        return {"claimed": False}
    source, args = _completed_source(entry)
    if through != db.execute(f"SELECT COALESCE(MAX(seq),0) FROM {source}", args).fetchone()[0]:
        return {"claimed": False}
    key = (*target, kind)
    previous = db.execute("SELECT through,active FROM chat_context_notices WHERE profile=? AND sid=? AND kind=?",
                          key).fetchone()
    # Within one history snapshot, an active observation takes precedence across devices.
    if previous and (through < previous["through"] or (through == previous["through"] and
                                                       (previous["active"] or not active))):
        return {"claimed": False}
    db.execute("""INSERT INTO chat_context_notices(profile,sid,kind,through,active) VALUES(?,?,?,?,?)
                  ON CONFLICT(profile,sid,kind) DO UPDATE SET through=excluded.through,active=excluded.active""",
               (*key, through, active))
    return {"claimed": active and not (previous and previous["active"])}
