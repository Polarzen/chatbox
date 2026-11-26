"""Fetch passive AstrBot deliveries for visible, already reconciled device rooms."""

import hashlib
import http.client
import json
import sqlite3
import threading
import time
import uuid
from urllib.parse import urlencode

if __package__:
    from . import device_service as devices
    from . import service as core
else:
    import device_service as devices
    import service as core


PAGE = 50
MAX_PAGE_BYTES = 4 * 1024 * 1024


def migrate(db):
    db.execute("""CREATE TABLE IF NOT EXISTS device_inbox_state(
        room TEXT PRIMARY KEY REFERENCES rooms, cursor INTEGER NOT NULL DEFAULT 0,
        next_poll INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT '')""")


def turn_id(sid, row_id):
    # Same namespace and RFC 4122 version-3 layout as Java UUID.nameUUIDFromBytes.
    name = f"chatbox-inbox-v1:{sid.lower()}:{row_id}".encode()
    return str(uuid.UUID(bytes=hashlib.md5(name, usedforsecurity=False).digest(), version=3))


def fetch(base, key, verify, username, sid, after):
    query = urlencode(dict(username=username, session_id=sid, after=after, limit=PAGE))
    with core.astrbot.connected(base, 5, verify, deadline=time.monotonic()+5) as (conn, prefix):
        conn.request("GET", prefix + "/api/v1/chatbox/inbox?" + query,
                     headers={"Authorization": "Bearer " + key, "Accept": "application/json",
                              "ngrok-skip-browser-warning": "1"})
        with conn.getresponse() as response:
            if response.status in (401, 403):
                return "denied", [], False
            if response.status in (404, 405, 501):
                return "unsupported", [], False
            if response.status != 200 or response.headers.get_content_type() != "application/json":
                return "unavailable", [], False
            raw = response.read(MAX_PAGE_BYTES+1)
    if len(raw) > MAX_PAGE_BYTES:
        raise ValueError("inbox response too large")
    reply = json.loads(raw.decode("utf-8"))
    if not isinstance(reply, dict) or reply.get("status") != "ok" or not isinstance(reply.get("data"), dict):
        raise ValueError("invalid inbox envelope")
    rows = reply["data"].get("messages")
    more = reply["data"].get("more")
    if type(more) is not bool or not isinstance(rows, list) or len(rows) > PAGE or (more and not rows):
        raise ValueError("invalid inbox page")
    previous = after
    for row in rows:
        if (not isinstance(row, dict) or type(row.get("id")) is not int or not previous < row["id"] <= 2**63-1
                or not isinstance(row.get("text"), str) or not row["text"].strip()):
            raise ValueError("invalid inbox message")
        row["text"].encode("utf-8")  # Reject isolated surrogate escapes before persisting any row.
        previous = row["id"]
    return "available", rows, more


TARGETS = """SELECT r.id AS room,r.upstream,r.username,r.session,c.profile,c.source_id,c.base,
                    COALESCE(i.cursor,0) AS cursor, COALESCE(i.next_poll,0) AS next_poll,
                    COALESCE(i.status,'') AS status
             FROM device_catalog c JOIN device_members m ON m.id=c.origin
             JOIN profile_sessions s ON s.profile=c.profile
             JOIN rooms r ON r.id=s.room AND r.upstream=c.upstream AND r.username=c.username AND r.session=s.sid
             LEFT JOIN device_session_state d ON d.profile=c.profile AND d.sid=s.sid
             LEFT JOIN device_inbox_state i ON i.room=r.id
             WHERE s.ready=1 AND s.removed=0 AND s.reason='' AND s.total=s.imported
               AND m.revoked=0 AND COALESCE(d.unverified,0)=0 AND COALESCE(d.removed,0)=0
               AND EXISTS (SELECT 1 FROM turns t WHERE t.room=r.id)
               AND """


class DeviceInbox:
    def __init__(self, api, workers):
        self.api = api
        self.workers = workers
        self.lock = threading.Lock()
        self.running = False
        self.next_wake = 0

    def wake(self, token, preferred=None):
        # Clients frequently poll turns/directories. An idle host has no timer or network traffic.
        with self.lock:
            now = time.monotonic()
            if self.running or now < self.next_wake:
                return
            self.running = True
            self.next_wake = now + 0.5
            self.workers.submit(self.poll, token, preferred)

    def candidates(self, token, preferred):
        with core.database(self.api.path) as db:
            device = devices.authorize(db, token)
            # A room with an old cursor cannot starve other rooms. Prefer an open chat
            # only when due, and fetch at most one page per room per refresh.
            return db.execute(TARGETS + devices.SCOPE + """ AND COALESCE(i.next_poll,0)<=?
                ORDER BY (c.profile=? AND s.sid=?) DESC, COALESCE(i.next_poll,0), s.activity DESC, r.id LIMIT 4""",
                (*devices.scope(device), int(time.time()), *(preferred or ("", "")))).fetchall()

    def current(self, db, token, room):
        device = devices.authorize(db, token)
        return db.execute(TARGETS + devices.SCOPE + " AND r.id=?", (*devices.scope(device), room)).fetchone()

    def route_is_current(self, db, target):
        return self.api.routes.resolve(db, target["source_id"], target["base"], target["upstream"]) == target["upstream"]

    def poll(self, token, preferred):
        try:
            for candidate in self.candidates(token, preferred):
                self.poll_room(token, candidate["room"])
        except (core.ApiError, sqlite3.Error):
            # The token can be revoked while a fetch is in progress. Another active
            # device can resume from the last committed cursor with its own scope.
            pass
        finally:
            with self.lock:
                self.running = False

    def poll_room(self, token, room):
        with core.database(self.api.path) as db:
            target = self.current(db, token, room)
            if not target:
                return
            valid_route = self.route_is_current(db, target)
        try:
            if not valid_route:
                raise core.astrbot.NotDispatched("unavailable inbox route")
            base, key, verify = self.api.routes.target(target["upstream"])
            status, rows, more = fetch(base, key, verify, target["username"], target["session"], target["cursor"])
        except (core.astrbot.NotDispatched, core.astrbot.SessionIndexError, OSError,
                http.client.HTTPException, ValueError, RecursionError):
            status, rows, more = "unavailable", [], False
        with core.database(self.api.path, write=True) as db:
            current = self.current(db, token, room)
            if not current or tuple(current) != tuple(target):
                return
            if not self.route_is_current(db, target):
                status, rows, more = "unavailable", [], False
            after = target["cursor"]
            seq = db.execute("SELECT MAX(seq) FROM turns WHERE room=?", (room,)).fetchone()[0]
            changed = False
            for row in rows:
                identifier = turn_id(target["session"], row["id"])
                existing = core.find_turn(db, room, identifier)
                if existing is None:
                    seq += 1
                    db.execute("INSERT INTO turns(room,seq,id,question,answer,status) VALUES(?,?,?,'',?,'complete')",
                               (room, seq, identifier, row["text"]))
                    changed = True
                elif existing["question"] or existing["answer"] != row["text"] or existing["status"] != "complete":
                    # A conflicting identifier must never overwrite a locally imported message.
                    status, more = "unavailable", False
                    break
                after = row["id"]
            if changed:
                core.profile_activity(db, room)
            if status != target["status"]:
                db.execute("UPDATE profiles SET revision=revision+1 WHERE id=?", (target["profile"],))
            delay = 1 if more else {"available": 5, "unavailable": 30, "unsupported": 300, "denied": 60}[status]
            db.execute("""INSERT INTO device_inbox_state(room,cursor,next_poll,status) VALUES(?,?,?,?)
                          ON CONFLICT(room) DO UPDATE SET cursor=excluded.cursor,
                              next_poll=excluded.next_poll,status=excluded.status""",
                       (room, after, int(time.time())+delay, status))
