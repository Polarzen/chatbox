"""Device pairing and explicitly shared native profiles on a local Chatbox instance."""

import hmac
import secrets
import sqlite3
import time
import uuid
from urllib.parse import urlsplit

if __package__:
    from . import service as core
else:
    import service as core


def migrate_device(db):
    # Every statement participates in the caller's migration transaction.
    for statement in (
        """CREATE TABLE IF NOT EXISTS device_members(
           id TEXT PRIMARY KEY, digest BLOB NOT NULL UNIQUE, owner INTEGER NOT NULL, username TEXT NOT NULL,
           revoked INTEGER NOT NULL DEFAULT 0)""",
        "CREATE UNIQUE INDEX IF NOT EXISTS device_single_owner ON device_members(owner) WHERE owner=1",
        """CREATE TABLE IF NOT EXISTS device_endpoint(
           singleton INTEGER PRIMARY KEY CHECK(singleton=1), address TEXT NOT NULL)""",
        "INSERT OR IGNORE INTO device_endpoint VALUES(1,'')",
        """CREATE TABLE IF NOT EXISTS device_invite(
           singleton INTEGER PRIMARY KEY CHECK(singleton=1), digest BLOB NOT NULL,
           expires INTEGER NOT NULL, pc_profiles INTEGER NOT NULL, pc_sessions INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS device_catalog(
           profile TEXT PRIMARY KEY REFERENCES profiles, origin TEXT NOT NULL REFERENCES device_members,
           source_id TEXT NOT NULL, base TEXT NOT NULL, username TEXT NOT NULL,
           upstream TEXT REFERENCES route_upstreams, UNIQUE(origin,source_id,base,username))""",
        """CREATE TABLE IF NOT EXISTS device_session_state(
           profile TEXT NOT NULL REFERENCES device_catalog, sid TEXT NOT NULL, origin TEXT NOT NULL REFERENCES device_members,
           removed INTEGER NOT NULL DEFAULT 0, unverified INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(profile,sid))""",
        """CREATE TABLE IF NOT EXISTS device_operations(
           id TEXT PRIMARY KEY, device TEXT NOT NULL REFERENCES device_members, action TEXT NOT NULL,
           profile TEXT NOT NULL REFERENCES device_catalog, sid TEXT NOT NULL, room TEXT NOT NULL REFERENCES rooms,
           FOREIGN KEY(room,id) REFERENCES turns(room,id))""",
        """CREATE TRIGGER IF NOT EXISTS device_turn_added AFTER INSERT ON turns BEGIN
           UPDATE profiles SET revision=revision+1 WHERE id IN
           (SELECT s.profile FROM profile_sessions s JOIN device_catalog c ON c.profile=s.profile
            WHERE s.room=NEW.room AND s.ready=1 AND s.removed=0); END""",
    ):
        db.execute(statement)
    if "unverified" not in {r[1] for r in db.execute("PRAGMA table_info(device_session_state)")}:
        db.execute("ALTER TABLE device_session_state ADD COLUMN unverified INTEGER NOT NULL DEFAULT 0")


def text_id(body, key):
    value = core.string(body, key, max_bytes=4096)
    if value != value.strip():
        raise core.ApiError(400, "身份标识含首尾空白。")
    return value


def credentials(body):
    device = core.request_id(body.get("device"))
    token = core.string(body, "token", max_bytes=128)
    if len(token) < 32 or any(ord(c) < 33 or ord(c) > 126 for c in token):
        raise core.ApiError(400, "设备凭据格式无效。")
    return device, core.digest(token)


def separate_credential(db, digest):
    if db.execute("SELECT 1 FROM devices WHERE digest=? UNION ALL SELECT 1 FROM profile_devices WHERE digest=?",
                  (digest, digest)).fetchone():
        raise core.ApiError(409, "须为设备登记独立的新凭据。")


def me(db, device):
    instance = db.execute("SELECT instance FROM config").fetchone()[0]
    address = db.execute("SELECT address FROM device_endpoint").fetchone()[0]
    return dict(instance=instance, address=address, device=device["id"], owner=bool(device["owner"]))


def provision_owner(path, body):
    core.fields(body, ("device", "token"))
    device, digest = credentials(body)
    with core.database(path, write=True) as db:
        migrate_device(db)
        separate_credential(db, digest)
        existing = db.execute("SELECT * FROM device_members WHERE owner=1").fetchone()
        if existing and existing["id"] != device:
            raise core.ApiError(409, "此数据库已登记另一电脑主人。请使用保存的本机身份。")
        try:
            if existing:
                db.execute("UPDATE device_members SET digest=? WHERE id=?", (digest, device))
            else:
                db.execute("INSERT INTO device_members(id,digest,owner,username) VALUES(?,?,1,'')", (device, digest))
        except sqlite3.IntegrityError as exc:
            raise core.ApiError(409, "设备身份已登记。") from exc
        return me(db, {"id": device, "owner": True})


def authorize(db, token, owner=False):
    row = db.execute("SELECT * FROM device_members WHERE digest=? AND revoked=0", (core.digest(token),)).fetchone()
    if not row:
        raise core.ApiError(401, "设备凭据无效或已撤销。")
    if owner and not row["owner"]:
        raise core.ApiError(403, "此操作需要电脑主人权限。")
    return row


def invitation(db, body):
    code = core.string(body, "code", max_bytes=128)
    row = db.execute("SELECT * FROM device_invite WHERE digest=? AND expires>?",
                     (core.digest(code), int(time.time()))).fetchone()
    if not row:
        raise core.ApiError(403, "设备授权码无效、已过期或已使用。")
    return row


PROFILE_SELECT = """SELECT c.*,p.name,m.owner AS pc FROM device_catalog c
                    JOIN profiles p ON p.id=c.profile JOIN device_members m ON m.id=c.origin"""
SCOPE = "(? OR c.origin=? OR m.owner=1)"


def scope(device):
    return device["owner"], device["id"]


def profile_row(db, device, profile, publish=False):
    row = db.execute(PROFILE_SELECT + " WHERE c.profile=? AND " + SCOPE,
                     (profile, *scope(device))).fetchone()
    if not row:
        raise core.ApiError(404, "找不到已授权的设备档案。")
    if publish and row["origin"] != device["id"]:
        raise core.ApiError(403, "仅来源设备可登记和上传本机记录。")
    return row


def profile_json(row, route):
    return dict(profile=row["profile"], sourceId=row["source_id"], base=row["base"],
                username=row["username"], name=row["name"], originDevice=row["origin"],
                upstream=row["upstream"] or "", available=route is not None)


def tombstone(db, profile, sid):
    row = db.execute("SELECT removed FROM device_session_state WHERE profile=? AND sid=?", (profile, sid)).fetchone()
    if row and row[0]:
        raise core.ApiError(409, "会话已移除。")


SESSION_SELECT = """SELECT s.*,COALESCE(d.origin,c.origin) AS origin,
                    CASE WHEN s.ready=1 THEN COALESCE((SELECT MAX(seq) FROM turns t WHERE t.room=s.room),0)
                         ELSE s.imported END AS visible_count
                    FROM profile_sessions s JOIN device_catalog c ON c.profile=s.profile
                    JOIN device_members m ON m.id=c.origin
                    LEFT JOIN device_session_state d ON d.profile=s.profile AND d.sid=s.sid
                    WHERE s.removed=0 AND COALESCE(d.removed,0)=0 AND """


def session_row(db, device, profile, sid):
    row = db.execute(SESSION_SELECT + SCOPE + " AND s.profile=? AND s.sid=? AND (visible_count>0 OR s.total>0)",
                     (*scope(device), profile, sid)).fetchone()
    if not row:
        raise core.ApiError(404, "找不到已授权的设备会话。")
    return row


def session_json(row):
    return dict(profile=row["profile"], sid=row["sid"], title=row["title"], activity=row["activity"],
                total=max(row["total"], row["visible_count"]), imported=row["visible_count"],
                reason=row["reason"], originDevice=row["origin"])


def operation(db, device, identifier):
    row = db.execute("SELECT * FROM device_operations WHERE device=? AND id=?", (device["id"], identifier)).fetchone()
    if row:
        session_row(db, device, row["profile"], row["sid"])
        return row, dict(profile=row["profile"], sid=row["sid"], turn=core.find_turn(db, row["room"], identifier))
    return None, None


class DeviceApi:
    def __init__(self, service):
        self.service = service
        self.path = service.path
        self.routes = service.routes

    def read(self, action, token, params):
        with core.database(self.path) as db:
            if action == "health":
                core.fields(params, ())
                return {"instance": db.execute("SELECT instance FROM config").fetchone()[0], "version": 1}
            device = authorize(db, token, owner=action == "devices")
            if action in ("me", "profiles", "devices"):
                core.fields(params, ())
                if action == "me":
                    return me(db, device)
                if action == "devices":
                    rows = db.execute("SELECT id FROM device_members WHERE owner=0 AND revoked=0 ORDER BY rowid")
                    return {"devices": [dict(device=r[0], name="手机 " + r[0][:8]) for r in rows]}
                rows = db.execute(PROFILE_SELECT + " WHERE " + SCOPE + " ORDER BY c.rowid", scope(device))
                return {"profiles": [profile_json(r, self.route(db, r)) for r in rows]}
            if action == "operation":
                core.fields(params, ("id",))
                _, result = operation(db, device, core.request_id(params["id"][0]))
                if result is None:
                    raise core.ApiError(404, "此设备没有该操作。")
                return result
            if action == "sessions":
                core.fields(params, params.keys() & {"offset", "limit", "revision"})
                offset = core.number(params, "offset", 0, 0, 2**63-1)
                limit = core.number(params, "limit", 50, 1, 100)
                revision = db.execute("SELECT COALESCE(SUM(p.revision+1),0) FROM device_catalog c "
                                      "JOIN profiles p ON p.id=c.profile JOIN device_members m ON m.id=c.origin "
                                      "WHERE " + SCOPE, scope(device)).fetchone()[0]
                if (offset or "revision" in params) and params.get("revision") != [str(revision)]:
                    raise core.ApiError(409, "目录已变动，请从第一页重新读取。")
                rows = db.execute(SESSION_SELECT + SCOPE + " AND (s.total>0 OR visible_count>0) "
                                  "ORDER BY s.activity DESC,s.profile,s.sid LIMIT ? OFFSET ?",
                                  (*scope(device), limit+1, offset)).fetchall()
                return dict(revision=revision, more=len(rows)>limit, sessions=[session_json(r) for r in rows[:limit]])
            if action not in ("turn", "turns"):
                raise core.ApiError(404, "接口不存在。")
            keys = {"profile", "sid"}
            core.fields(params, keys | ({"id"} if action == "turn" else params.keys() & {"after", "limit"}))
            profile, sid = params["profile"][0], params["sid"][0]
            entry = session_row(db, device, profile, sid)
            if entry["ready"]:
                query = f"SELECT {core.TURN_FIELDS} FROM turns WHERE room=?"
                args = (entry["room"],)
            else:
                query = f"SELECT {core.TURN_FIELDS} FROM profile_imports WHERE profile=? AND sid=?"
                args = (profile, sid)
            if action == "turns":
                after = core.number(params, "after", 0, 0, 2**63-1)
                limit = core.number(params, "limit", 50, 1, 100)
                rows = db.execute(query + " AND seq>? ORDER BY seq LIMIT ?", (*args, after, limit+1))
                return core.turn_page(rows, limit)
            identifier = core.request_id(params["id"][0])
            row = db.execute(query + " AND id=?", (*args, identifier)).fetchone()
            if not row and entry["ready"]:
                row = db.execute("""SELECT t.seq,p.id,t.question,t.answer,t.status FROM profile_imports p
                                    JOIN turns t ON t.room=? AND t.seq=p.seq
                                    WHERE p.profile=? AND p.sid=? AND p.id=?""",
                                 (entry["room"], profile, sid, identifier)).fetchone()
            if not row:
                raise core.ApiError(404, "找不到此轮对话。")
            return dict(row)

    def write(self, action, token, body):
        if action == "session":
            return 200, self.publish_session(token, body)
        if action in ("start", "send", "media-begin"):
            return self.submit(action, token, body)
        with core.database(self.path, write=action not in ("prove", "preview")) as db:
            if action == "prove":
                core.fields(body, ("device", "nonce"))
                identifier = core.request_id(body["device"])
                core.request_id(body["nonce"])
                row = db.execute("SELECT * FROM device_members WHERE id=?", (identifier,)).fetchone()
                if not row:
                    raise core.ApiError(404, "找不到此设备。")
                identity = me(db, row)
                del identity["owner"]
                message = "\n".join(("chatbox-device-address-v1", identity["instance"], identifier,
                                     identity["address"], body["nonce"])).encode("utf-8")
                return 200, dict(identity, proof=hmac.digest(row["digest"], message, "sha256").hex())
            if action in ("preview", "join"):
                if action == "preview":
                    core.fields(body, ("code",))
                    row = invitation(db, body)
                    return 200, dict(instance=db.execute("SELECT instance FROM config").fetchone()[0],
                                     expiresIn=row["expires"]-int(time.time()), pcProfiles=row["pc_profiles"],
                                     pcSessions=row["pc_sessions"])
                core.fields(body, ("code", "device", "token", "username", "phoneProfiles", "phoneSessions"))
                invitation(db, body)
                identifier, digest = credentials(body)
                separate_credential(db, digest)
                username = text_id(body, "username")
                core.nonnegative(body, "phoneProfiles")
                core.nonnegative(body, "phoneSessions")
                try:
                    db.execute("INSERT INTO device_members(id,digest,owner,username) VALUES(?,?,0,?)",
                               (identifier, digest, username))
                except sqlite3.IntegrityError as exc:
                    raise core.ApiError(409, "设备身份已登记。") from exc
                db.execute("DELETE FROM device_invite")
                return 200, me(db, {"id": identifier, "owner": False})
            device = authorize(db, token, owner=action in ("invite", "endpoint", "revoke", "remove"))
            if action == "invite":
                core.fields(body, ("pcProfiles", "pcSessions"))
                code = secrets.token_urlsafe(20)
                db.execute("INSERT OR REPLACE INTO device_invite VALUES(1,?,?,?,?)",
                           (core.digest(code), int(time.time())+core.CODE_LIFETIME,
                            core.nonnegative(body, "pcProfiles"), core.nonnegative(body, "pcSessions")))
                return 200, {"code": code, "expiresIn": core.CODE_LIFETIME}
            if action == "endpoint":
                core.fields(body, ("address",))
                address = core.normalized_base(text_id(body, "address"))
                if not address.startswith("https://") or urlsplit(address).path:
                    raise core.ApiError(400, "设备访问地址须为 HTTPS 站点。")
                db.execute("UPDATE device_endpoint SET address=?", (address,))
                return 200, {"address": address}
            if action == "revoke":
                core.fields(body, ("device",))
                identifier = core.request_id(body["device"])
                if not db.execute("UPDATE device_members SET revoked=1 WHERE id=? AND owner=0 AND revoked=0",
                                  (identifier,)).rowcount:
                    raise core.ApiError(404, "找不到来宾设备。")
                reservations = db.execute("""SELECT t.room,t.id FROM device_operations op JOIN turns t
                                             ON t.room=op.room AND t.id=op.id WHERE op.device=?
                                             AND op.action='media-begin' AND t.status='running'""",
                                          (identifier,)).fetchall()
                for room, turn_id in reservations:
                    db.execute("UPDATE turns SET status='unknown',needs_review=1 WHERE room=? AND id=?", (room, turn_id))
                    core.profile_activity(db, room)
                return 200, {}
            if action == "profile":
                return 200, self.publish_profile(db, device, body)
            if action == "import":
                core.fields(body, ("profile", "sid", "start", "turns"))
                profile = text_id(body, "profile")
                profile_row(db, device, profile, publish=True)
                entry = session_row(db, device, profile, text_id(body, "sid"))
                if entry["origin"] != device["id"]:
                    raise core.ApiError(403, "仅来源设备可上传本机会话。")
                result = core.ProfileApi.import_batch(db, profile, {k:v for k,v in body.items() if k != "profile"})
                return 200, result
            if action == "remove":
                core.fields(body, ("profile", "sid"))
                profile, sid = text_id(body, "profile"), text_id(body, "sid")
                row = profile_row(db, device, profile)
                db.execute("""INSERT INTO device_session_state(profile,sid,origin,removed) VALUES(?,?,?,1)
                              ON CONFLICT(profile,sid) DO UPDATE SET removed=1""", (profile, sid, row["origin"]))
                db.execute("UPDATE profiles SET revision=revision+1 WHERE id=?", (profile,))
                return 200, {}
            if action == "media-end":
                return 200, self.media_end(db, device, body)
            raise core.ApiError(404, "接口不存在。")

    def publish_profile(self, db, device, body):
        required = {"sourceId", "base", "username", "name"}
        core.fields(body, required | (body.keys() & {"upstream", "legacyToken"}))
        source, username = text_id(body, "sourceId"), text_id(body, "username")
        native_base = text_id(body, "base")
        normalized = core.normalized_base(native_base)
        # Android's native profile key can retain an explicit default port. Preserve that
        # source identity while the route resolver compares normalized network destinations.
        base = normalized if device["owner"] else native_base
        name = core.string(body, "name", max_bytes=4096)
        requested = text_id(body, "upstream") if "upstream" in body else None
        proof = core.string(body, "legacyToken", max_bytes=128) if "legacyToken" in body else None
        if not device["owner"] and (username != device["username"] or source != "android:" + base or requested or proof):
            raise core.ApiError(403, "手机只能登记本机用户和来源。")
        upstream = self.routes.resolve(db, source, base, requested)
        if requested and not upstream:
            raise core.ApiError(409, "来源和已登记的上游不匹配。")
        proven = None
        if proof:
            proven = self.routes.prove_profile(db, upstream or self.service.default_upstream, username, source, base, proof)
        if proof and not proven:
            raise core.ApiError(403, "无法确认原档案的主人及来源身份。")
        key = (device["id"], source, base, username)
        existing = db.execute("SELECT profile,upstream FROM device_catalog WHERE "
                              "(origin,source_id,base,username)=(?,?,?,?)", key).fetchone()
        if not existing and upstream and source.startswith("launcher:"):
            existing = db.execute("SELECT profile,upstream FROM device_catalog WHERE origin=? AND source_id=? "
                                  "AND username=? AND upstream=?", (device["id"], source, username, upstream)).fetchone()
        if existing:
            profile = existing["profile"]
            if (proven and profile != proven) or (existing["upstream"] and upstream and upstream != existing["upstream"]):
                raise core.ApiError(409, "此来源的档案或上游已变动，不能合并。")
            db.execute("UPDATE device_catalog SET base=?,upstream=COALESCE(upstream,?) WHERE profile=?", (base, upstream, profile))
        else:
            profile = proven or str(uuid.uuid4())
            if proven:
                previous = db.execute("SELECT 1 FROM device_catalog WHERE profile=?", (profile,)).fetchone()
                if previous:
                    raise core.ApiError(409, "原档案已登记为其他来源。")
            else:
                # Native profiles use the existing transcript/reconciliation storage. Their private
                # source namespace cannot collide with an independently enrolled legacy profile.
                db.execute("INSERT INTO profiles(id,name,source_id,base,username) VALUES(?,?,?,?,?)",
                           (profile, name, "device:" + profile, base, username))
            db.execute("INSERT INTO device_catalog(profile,origin,source_id,base,username,upstream) VALUES(?,?,?,?,?,?)",
                       (profile, *key, upstream))
        db.execute("UPDATE profiles SET name=? WHERE id=? AND name<>?", (name, profile, name))
        row = profile_row(db, device, profile)
        return profile_json(row, self.route(db, row))

    def route(self, db, profile):
        registered = profile["upstream"]
        if registered and registered == self.routes.resolve(db, profile["source_id"], profile["base"], registered):
            return registered
        return None

    def publish_session(self, token, body):
        required = {"profile", "sid", "title", "activity", "total"}
        core.fields(body, required | (body.keys() & {"legacyToken"}))
        profile, sid = text_id(body, "profile"), text_id(body, "sid")
        title = core.string(body, "title", empty=True, max_bytes=4096)
        total, activity = core.nonnegative(body, "total"), core.nonnegative(body, "activity")
        proof = core.string(body, "legacyToken", max_bytes=128) if "legacyToken" in body else None
        with core.database(self.path) as db:
            device = authorize(db, token)
            catalog = profile_row(db, device, profile, publish=True)
            tombstone(db, profile, sid)
            upstream, username = self.route(db, catalog), catalog["username"]
        checked = self.routes.check_origin(upstream, username, sid) if upstream and proof != "unverified" else (False, "")
        exists, failure = checked
        with core.database(self.path, write=True) as db:
            device = authorize(db, token)
            catalog = profile_row(db, device, profile, publish=True)
            if self.route(db, catalog) != upstream:
                raise core.ApiError(409, "上游登记已变动，请刷新。")
            tombstone(db, profile, sid)
            state = db.execute("SELECT origin,unverified FROM device_session_state WHERE profile=? AND sid=?",
                               (profile, sid)).fetchone()
            previous = db.execute("SELECT * FROM profile_sessions WHERE profile=? AND sid=?", (profile, sid)).fetchone()
            if state and state[0] != device["id"]:
                raise core.ApiError(403, "仅来源设备可登记本机会话。")
            if previous and previous["removed"]:
                raise core.ApiError(409, "原会话已移除。")
            if previous and total < previous["total"]:
                raise core.ApiError(409, "本机历史范围缩短，无法覆盖原记录。")
            room = previous["room"] if previous else None
            bound = self.routes.find_room(db, upstream, username, sid) if upstream else None
            valid_proof = bool(bound and proof and self.routes.prove_room(db, bound, proof))
            reason = ""
            if room and upstream and bound != room:
                raise core.ApiError(409, "原房间的来源与当前档案不匹配。")
            if not total:
                # A native client may have no local transcript for an already-authorized legacy room.
                owned = room or (bound if valid_proof else None)
                if not owned or not db.execute("SELECT 1 FROM turns WHERE room=? LIMIT 1", (owned,)).fetchone():
                    raise core.ApiError(409, "没有已提交的原生对话。")
            denied = bool(proof or (state and state["unverified"])) and not valid_proof
            if not upstream:
                reason = "来源尚未在电脑上登记。已上传的历史可读。"
            elif denied:
                reason = "无法核实原共享房间的主人权限。已上传的历史可读。"
            elif bound and not room:
                if not valid_proof:
                    reason = "缺少原共享房间的主人授权。已上传的历史可读。"
                else:
                    claimed = db.execute("SELECT profile,sid FROM profile_sessions WHERE room=?", (bound,)).fetchone()
                    if claimed:
                        # A proven room already shared in this device's scope has one authoritative key.
                        # It remains private if only the legacy profile has been enrolled.
                        tombstone(db, *claimed)
                        try:
                            canonical = session_row(db, device, *claimed)
                        except core.ApiError:
                            reason = "原共享房间已关联其他档案。已上传的历史可读。"
                        else:
                            return session_json(canonical)
                    else:
                        room = bound
            unavailable = "" if exists else failure or "AstrBot 未找到属于该用户的原会话。已上传的历史可读。"
            if not room and not reason and not unavailable:
                room = self.routes.ensure_room(db, upstream, username, sid)
            if not reason and not room:
                reason = unavailable
            # An existing authority keeps its transcript readable if the upstream index is offline.
            # A new or growing prefix must be reconciled before it becomes authoritative.
            db.execute("""INSERT INTO profile_sessions(profile,sid,title,activity,total,room,reason)
                          VALUES(?,?,?,?,?,?,?) ON CONFLICT(profile,sid) DO UPDATE SET
                          title=excluded.title,activity=MAX(activity,excluded.activity),
                          ready=(ready AND total=excluded.total),total=excluded.total,room=excluded.room,
                          reason=excluded.reason""", (profile, sid, title, activity, total, room, reason))
            db.execute("""INSERT INTO device_session_state(profile,sid,origin,unverified) VALUES(?,?,?,?)
                          ON CONFLICT(profile,sid) DO UPDATE SET unverified=excluded.unverified""",
                       (profile, sid, device["id"], int(bool(denied))))
            core.reconcile(db, core.get_member(db, profile, sid))
            if room and not reason and unavailable:
                # A failed upstream presence check blocks dispatch. A proven room's already
                # committed transcript stays readable, including its authoritative suffix.
                db.execute("UPDATE profile_sessions SET reason=? WHERE profile=? AND sid=? AND ready=1",
                           (unavailable, profile, sid))
            return session_json(session_row(db, device, profile, sid))

    def prepare(self, db, action, token, profile, sid, identifier, question):
        device = authorize(db, token)
        catalog = profile_row(db, device, profile)
        tombstone(db, profile, sid)
        previous, op = operation(db, device, identifier)
        if previous:
            if (previous["action"], previous["profile"], previous["sid"], op["turn"]["question"]) != (
                    action, profile, sid, question):
                raise core.ApiError(409, "操作编号已用于其他目标或内容。")
            if action == "media-begin":
                op["dispatch"] = False
            return device, None, None, op
        if db.execute("SELECT 1 FROM device_operations WHERE id=?", (identifier,)).fetchone():
            raise core.ApiError(409, "操作编号已使用。")
        if action == "media-begin" and device["id"] != catalog["origin"]:
            raise core.ApiError(403, "仅档案来源设备可发送本机媒体。")
        if db.execute("SELECT 1 FROM device_session_state WHERE profile=? AND sid=? AND unverified=1",
                      (profile, sid)).fetchone():
            raise core.ApiError(409, "无法核实原共享房间的主人权限。")
        upstream = self.route(db, catalog)
        if not upstream:
            raise core.ApiError(409, "此来源尚无可用的已登记上游。")
        return device, upstream, catalog["username"], None

    def submit(self, action, token, body):
        core.fields(body, ("profile", "sid", "id", "question"))
        profile, sid = text_id(body, "profile"), text_id(body, "sid")
        identifier = core.request_id(body["id"])
        question = core.string(body, "question", max_bytes=core.MAX_QUESTION)
        with core.database(self.path) as db:
            device, upstream, username, op = self.prepare(db, action, token, profile, sid, identifier, question)
            if op:
                return 200, op
            entry = db.execute("SELECT * FROM profile_sessions WHERE profile=? AND sid=?", (profile, sid)).fetchone()
            creating = action == "start" or (action == "media-begin" and entry is None)
            if creating:
                core.request_id(sid)
                if entry:
                    raise core.ApiError(409, "会话编号已存在。")
            else:
                session_row(db, device, profile, sid)
                if not entry["ready"] or entry["total"] != entry["imported"]:
                    raise core.ApiError(409, entry["reason"] or "历史尚未同步完整。")
        exists, failure = self.routes.check_origin(upstream, username, sid)
        with core.database(self.path, write=True) as db:
            device, current, user, op = self.prepare(db, action, token, profile, sid, identifier, question)
            if op:
                return 200, op
            if current != upstream or user != username:
                raise core.ApiError(409, "会话来源已变动。")
            if failure:
                raise core.ApiError(409, failure)
            room = self.routes.find_room(db, upstream, username, sid)
            if creating:
                if exists or room or db.execute("SELECT 1 FROM profile_sessions WHERE profile=? AND sid=?",
                                                (profile, sid)).fetchone():
                    raise core.ApiError(409, "会话编号已存在。")
                room = self.routes.ensure_room(db, upstream, username, sid)
                db.execute("""INSERT INTO profile_sessions(profile,sid,title,activity,total,room,ready,fresh)
                              VALUES(?,?,?,?,0,?,1,1)""", (profile, sid, question[:80], int(time.time()*1000), room))
                db.execute("INSERT INTO device_session_state(profile,sid,origin) VALUES(?,?,?)", (profile, sid, device["id"]))
            else:
                entry = session_row(db, device, profile, sid)
                if not (entry["ready"] and entry["total"] == entry["imported"] and room and entry["room"] == room):
                    raise core.ApiError(409, entry["reason"] or "历史尚未同步完整。")
                if not exists and not entry["fresh"]:
                    raise core.ApiError(409, "AstrBot 未找到属于该用户的原会话。")
            if (core.find_turn(db, room, identifier) or
                    db.execute("SELECT 1 FROM profile_imports WHERE profile=? AND sid=? AND id=?",
                               (profile, sid, identifier)).fetchone()):
                raise core.ApiError(409, "操作编号已用于原有历史。")
            result = self.service.enqueue(db, room, identifier, question)
            db.execute("INSERT INTO device_operations VALUES(?,?,?,?,?,?)",
                       (identifier, device["id"], action, profile, sid, room))
            if exists:
                db.execute("UPDATE profile_sessions SET fresh=0,reason='' WHERE profile=? AND sid=?", (profile, sid))
        op = dict(profile=profile, sid=sid, turn=result)
        if action == "media-begin":
            return 202, dict(op, dispatch=True)
        status, op["turn"] = self.service.dispatch_turn(room, result, username, sid)
        return status, op

    @staticmethod
    def media_end(db, device, body):
        core.fields(body, ("profile", "sid", "id", "answer", "status"))
        identifier = core.request_id(body["id"])
        profile, sid = text_id(body, "profile"), text_id(body, "sid")
        answer = core.string(body, "answer", empty=True, max_bytes=core.MAX_REPLY)
        status = body["status"]
        if status not in ("complete", "unknown"):
            raise core.ApiError(400, "媒体结果状态无效。")
        row, op = operation(db, device, identifier)
        if not row or (row["action"], row["profile"], row["sid"]) != ("media-begin", profile, sid):
            raise core.ApiError(404, "找不到此设备的媒体操作。")
        if op["turn"]["status"] == "running":
            db.execute("UPDATE turns SET answer=?,status=?,needs_review=? WHERE room=? AND id=?",
                       (answer, status, status=="unknown", row["room"], identifier))
            if status == "complete":
                db.execute("UPDATE profile_sessions SET fresh=0 WHERE room=?", (row["room"],))
            core.profile_activity(db, row["room"])
            op["turn"] = core.find_turn(db, row["room"], identifier)
        elif (op["turn"]["status"], op["turn"]["answer"]) != (status, answer):
            raise core.ApiError(409, "此媒体操作已有不同的最终结果。")
        return op
