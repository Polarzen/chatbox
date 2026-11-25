"""Durable plugin-driven jobs. The caller owns a single immediate SQLite transaction.

Call migrate_jobs after the device/profile migrations. enqueue_workout is called only
for a newly completed, non-baseline workout and each currently enabled profile.
Nothing here sends a question to AstrBot. A delivered job is a completed local
transcript row with an empty question and a server-generated answer.
"""

from datetime import date, datetime, time, timedelta
from hashlib import sha256
import json
from uuid import NAMESPACE_URL, uuid4, uuid5

if __package__:
    from . import service as core, wearable_metrics
else:
    import service as core
    import wearable_metrics


def migrate_jobs(db):
    db.execute("""CREATE TABLE IF NOT EXISTS wearable_jobs(
        id TEXT PRIMARY KEY, device TEXT NOT NULL REFERENCES device_members,
        profile TEXT NOT NULL REFERENCES device_catalog, kind TEXT NOT NULL,
        source TEXT NOT NULL, event_key TEXT NOT NULL, timezone TEXT NOT NULL,
        due_ms INTEGER NOT NULL, deadline_ms INTEGER, state TEXT NOT NULL DEFAULT 'pending',
        evidence TEXT, UNIQUE(device,profile,kind,source,event_key))""")
    db.execute("""CREATE INDEX IF NOT EXISTS wearable_pending ON wearable_jobs(
        device,profile,state,due_ms)""")
    db.execute("""CREATE INDEX IF NOT EXISTS assistant_turn_ids ON turns(id) WHERE question='' AND status='complete'""")


def enqueue_workout(db, device, profile, source, exercise_id, now_ms):
    db.execute("""INSERT INTO wearable_jobs(id,device,profile,kind,source,event_key,timezone,due_ms)
        VALUES(?,?,?,'workout',?,?,'',?) ON CONFLICT(device,profile,kind,source,event_key) DO NOTHING""",
               (str(uuid4()), device, profile, source, exercise_id, now_ms))


def _morning_window(day, settings, zone):
    at = time.fromisoformat(settings["morningTime"])
    # fold=0 picks the first occurrence in autumn and shifts a nonexistent
    # spring time forward by the length of the gap.
    due = int(datetime.combine(day, at, tzinfo=zone).timestamp() * 1000)
    return due, due + settings["waitMinutes"] * 60_000


def _schedule_morning(db, device, profile, settings, now_ms):
    zone = wearable_metrics._zone(settings["timezone"])
    today = datetime.fromtimestamp(now_ms / 1000, zone).date()
    earliest = datetime.fromtimestamp((now_ms - settings["waitMinutes"] * 60_000) / 1000, zone).date()
    for ordinal in range(earliest.toordinal(), today.toordinal() + 1):
        day = date.fromordinal(ordinal)
        due, deadline = _morning_window(day, settings, zone)
        if day != today and now_ms >= deadline:
            continue
        db.execute("""INSERT INTO wearable_jobs(id,device,profile,kind,source,event_key,timezone,
            due_ms,deadline_ms,state) VALUES(?,?,?,'morning','',?,?,?,?,?)
            ON CONFLICT(device,profile,kind,source,event_key) DO UPDATE SET
            due_ms=excluded.due_ms,deadline_ms=excluded.deadline_ms,timezone=excluded.timezone,
            state=excluded.state,evidence=NULL WHERE state='pending'
            AND (due_ms<>excluded.due_ms OR deadline_ms<>excluded.deadline_ms OR timezone<>excluded.timezone)""",
                   (str(uuid4()), device, profile, day.isoformat(), settings["timezone"],
                    due, deadline, "pending" if now_ms < deadline else "expired"))


def private_room(db, device, profile, sid):
    """Existing phone-origin transcript visible only to this phone and its paired PC."""
    row = db.execute("""SELECT r.id FROM profile_sessions s
        JOIN rooms r ON r.id=s.room AND r.session=s.sid AND r.legacy=0
        JOIN device_catalog c ON c.profile=s.profile AND c.upstream=r.upstream AND c.username=r.username
        JOIN profiles p ON p.id=c.profile AND p.source_id='device:' || c.profile
        JOIN device_members phone ON phone.id=c.origin AND phone.id=? AND phone.revoked=0
            AND phone.owner=0 AND phone.username=c.username
        LEFT JOIN device_session_state d ON d.profile=s.profile AND d.sid=s.sid
        LEFT JOIN device_members author ON author.id=d.origin
        WHERE s.profile=? AND s.sid=? AND s.ready=1 AND s.removed=0 AND s.reason=''
        AND s.total=s.imported AND COALESCE(d.unverified,0)=0 AND COALESCE(d.removed,0)=0
        AND (d.origin IS NULL OR (author.revoked=0 AND (author.owner=1 OR author.id=phone.id)))
        AND NOT EXISTS (SELECT 1 FROM profile_sessions other WHERE other.room=r.id
            AND (other.profile<>s.profile OR other.sid<>s.sid))
        AND NOT EXISTS (SELECT 1 FROM devices WHERE room=r.id)
        AND NOT EXISTS (SELECT 1 FROM profile_devices WHERE profile=c.profile)""",
                     (device, profile, sid)).fetchone()
    return row[0] if row else None


def _summary(result):
    data = result["data"]
    if result["metric"] == "sleep":
        minutes = data["sessionSeconds"] / 60
        text = f"昨晚睡眠记录区间为 {minutes:g} 分钟。"
        if data.get("asleepSeconds") is not None:
            text += f" 已记录的实际睡眠为 {data['asleepSeconds'] / 60:g} 分钟。"
        else:
            text += " 分期记录不完整，无法确定实际睡眠总时长。"
    else:
        text = f"本次运动（{data['exerciseType']}）持续 {data['durationSeconds'] / 60:g} 分钟。"
        if data.get("distanceMeters") is not None:
            text += f" 距离为 {data['distanceMeters']:g} 米。"
    return text + f" 数据来源为 {result['source']}。"


def _enabled(kind, settings):
    metric, switch = ("sleep", "morningEnabled") if kind == "morning" else ("exercise", "workoutEnabled")
    return (settings[switch] and metric in settings["metrics"]
            and settings["statuses"].get(metric) != "no_permission")


def _set_state(db, job, state):
    db.execute("UPDATE wearable_jobs SET state=?,evidence=NULL WHERE id=?", (state, job["id"]))
    return {"status": state}


def _query(job, read, now_ms):
    if job["kind"] == "workout":
        return read({"metric": "exercise", "exerciseId": job["event_key"]}, now_ms)
    # A late sync after local midnight still refers to the scheduled date.
    day = date.fromisoformat(job["event_key"])
    zone = wearable_metrics._zone(job["timezone"])
    midnight = datetime.combine(day + timedelta(days=1), time.min, tzinfo=zone)
    return read({"metric": "sleep", "relative": "last_night"}, min(now_ms, int(midnight.timestamp()*1000)-1))


def _evidence(result):
    # A retry may run later or after an unrelated batch. Only evidence used in
    # the message must agree with the plugin's successful query.
    fields = {k: result.get(k) for k in ("metric", "source", "dataStatus", "data")}
    return sha256(json.dumps(fields, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def run_job_action(db, action, body, device, settings, read, now_ms):
    """Called with fresh authenticated settings. read(query, as_of_ms) enforces metric permissions."""
    profile = settings["profile"]
    key = (device, profile)
    if action == "wearable-jobs":
        core.fields(body, ())
        if _enabled("morning", settings):
            _schedule_morning(db, device, profile, settings, now_ms)
        db.execute("""UPDATE wearable_jobs SET state='expired',evidence=NULL WHERE device=? AND profile=?
            AND state='pending' AND deadline_ms<=?""", (*key, now_ms))
        rows = db.execute("""SELECT id,kind FROM wearable_jobs WHERE device=? AND profile=? AND state='pending' AND due_ms<=?
            AND ((kind='workout' AND ? AND source=?) OR (kind='morning' AND ? AND timezone=?))
            ORDER BY due_ms,id LIMIT 32""", (*key, now_ms, _enabled("workout", settings), settings["source"],
                                             _enabled("morning", settings), settings["timezone"]))
        return {"jobs": [dict(row) for row in rows]}
    if action not in ("wearable-job-query", "wearable-job-finish"):
        raise core.ApiError(404, "未知手环任务接口。")
    core.fields(body, ("id",))
    job = db.execute("SELECT * FROM wearable_jobs WHERE device=? AND profile=? AND id=?",
                     (*key, core.request_id(body["id"]))).fetchone()
    if not job:
        raise core.ApiError(404, "找不到此档案的健康任务。")
    if job["state"] != "pending":
        return {"status": job["state"]}
    kind = job["kind"]
    if not _enabled(kind, settings) or (kind == "workout" and job["source"] != settings["source"]):
        return _set_state(db, job, "cancelled")
    if kind == "morning":
        due, deadline = _morning_window(date.fromisoformat(job["event_key"]), settings,
                                        wearable_metrics._zone(settings["timezone"]))
        if (due, deadline, settings["timezone"]) != (job["due_ms"], job["deadline_ms"], job["timezone"]):
            db.execute("DELETE FROM wearable_jobs WHERE id=?", (job["id"],))
            return {"status": "rescheduled"}
        if now_ms < due:
            return {"status": "waiting"}
    if job["deadline_ms"] is not None and now_ms >= job["deadline_ms"]:
        return _set_state(db, job, "expired")
    if action == "wearable-job-finish" and not job["evidence"]:
        raise core.ApiError(409, "插件须先查询此任务。")

    result = _query(job, read, now_ms)
    digest = _evidence(result)
    if action == "wearable-job-query":
        db.execute("UPDATE wearable_jobs SET evidence=? WHERE id=?", (digest, job["id"]))
        return result
    if not result.get("data"):
        unavailable = result["dataStatus"] == "source_unavailable"
        if unavailable or (kind == "morning" and result["dataStatus"] == "no_records"):
            return {"status": "waiting"}
        return _set_state(db, job, "missing")
    if digest != job["evidence"]:
        db.execute("UPDATE wearable_jobs SET evidence=NULL WHERE id=?", (job["id"],))
        return {"status": "pending"}
    if not settings["notifyMorning" if kind == "morning" else "notifyWorkout"]:
        return _set_state(db, job, "silent")
    room = private_room(db, device, profile, settings["sid"])
    if not room:
        return _set_state(db, job, "destination_unavailable")
    # Clearing health cache also clears its job ledger. The already published
    # transcript retains a stable id, so rediscovery cannot duplicate a summary.
    identity = [device, profile, kind, job["source"], job["event_key"]]
    turn_id = str(uuid5(NAMESPACE_URL, "chatbox:wearable:" + json.dumps(identity, ensure_ascii=False)))
    existing = db.execute("SELECT room FROM turns WHERE id=? AND question='' AND status='complete' LIMIT 1",
                          (turn_id,)).fetchone()
    if existing:
        return _set_state(db, job, "delivered")
    if db.execute("SELECT 1 FROM turns WHERE room=? AND id=?", (room, turn_id)).fetchone():
        return _set_state(db, job, "destination_unavailable")
    if db.execute("SELECT 1 FROM turns WHERE room=? AND (status='running' OR needs_review=1) LIMIT 1",
                  (room,)).fetchone():
        return {"status": "waiting"}
    # The caller's immediate transaction serializes appenders. A dropped HTTP
    # response is recovered from the terminal job state, in this same commit.
    db.execute("""INSERT INTO turns(room,seq,id,question,answer,status)
        SELECT ?,COALESCE(MAX(seq),0)+1,?,'',?,'complete' FROM turns WHERE room=?""",
               (room, turn_id, _summary(result), room))
    core.profile_activity(db, room)
    return _set_state(db, job, "delivered")
