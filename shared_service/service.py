"""Single-operator Chatbox bridge. Run python shared_service/service.py --help."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing, contextmanager
import getpass
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import sqlite3
import sys
import time
from urllib.parse import parse_qs, quote, urlsplit
import uuid

# Device modules import the common service contracts in both script and package mode.
if __name__ == "__main__":
    sys.modules[(__package__ + "." if __package__ else "") + "service"] = sys.modules[__name__]

if __package__:
    from . import astrbot, device_routes
    from .profile_service import migrate_profiles, profile_activity, reconcile
else:
    import astrbot, device_routes
    from profile_service import migrate_profiles, profile_activity, reconcile


MAX_BODY = 8 * 1024 * 1024
MAX_HISTORY = 10000
MAX_QUESTION = 64 * 1024
MAX_REPLY = 4 * 1024 * 1024
CODE_LIFETIME = 600
TURN_FIELDS = "seq, id, question, answer, status"

SCHEMA = """
CREATE TABLE config(instance TEXT PRIMARY KEY, upstream TEXT NOT NULL, launcher_id TEXT);
CREATE TABLE sources(base TEXT PRIMARY KEY);
CREATE TABLE rooms(id TEXT PRIMARY KEY, username TEXT NOT NULL, session TEXT NOT NULL UNIQUE);
CREATE TABLE devices(id TEXT PRIMARY KEY, digest BLOB NOT NULL UNIQUE, room TEXT NOT NULL REFERENCES rooms,
                     owner INTEGER NOT NULL CHECK(owner IN (0,1)));
CREATE TABLE codes(digest BLOB PRIMARY KEY, room TEXT REFERENCES rooms, expires INTEGER NOT NULL,
                   purpose TEXT NOT NULL CHECK(purpose IN ('create','invite','recover')),
                   CHECK((room IS NULL) = (purpose='create')));
CREATE UNIQUE INDEX one_room_code_per_purpose ON codes(room,purpose) WHERE room IS NOT NULL;
CREATE UNIQUE INDEX one_bootstrap ON codes((1)) WHERE room IS NULL;
CREATE TABLE turns(room TEXT NOT NULL REFERENCES rooms, seq INTEGER NOT NULL, id TEXT NOT NULL,
                   question TEXT NOT NULL, answer TEXT NOT NULL,
                   status TEXT NOT NULL CHECK(status IN ('running','complete','unknown')),
                   needs_review INTEGER NOT NULL DEFAULT 0,
                   PRIMARY KEY(room,seq), UNIQUE(room,id));
CREATE UNIQUE INDEX one_running ON turns(room) WHERE status='running';
"""


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def digest(secret):
    return hashlib.sha256(secret.encode("utf-8")).digest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def normalized_base(value):
    try:
        value = value.strip()
        url = urlsplit(value)
        if not url.hostname or url.username is not None or url.password is not None:
            raise ValueError
        if any(c in value for c in "?#\\") or any(ord(c) <= 32 or ord(c) == 127 for c in value):
            raise ValueError
        scheme = url.scheme.lower()
        host = url.hostname.lower().encode("idna").decode("ascii")
        if scheme != "https" and not (scheme == "http" and host == "127.0.0.1"):
            raise ValueError
        port = url.port
        if port == 0 or url.netloc.endswith(":"):
            raise ValueError
        if ":" in host:
            host = "[" + host + "]"
        if port and port != {"https": 443, "http": 80}[scheme]:
            host += ":" + str(port)
        path = quote(url.path.rstrip("/"), safe="/%:@-._~!$&'()*+,;=")
        return scheme + "://" + host + path
    except (ValueError, UnicodeError) as exc:
        raise ApiError(400, "服务地址无效，请使用 HTTPS 或 127.0.0.1 回环地址。") from exc


@contextmanager
def database(path, write=False, *, rebuild_rooms=False):
    uri = Path(path).resolve().as_uri() + "?mode=rw"
    with closing(sqlite3.connect(uri, uri=True, isolation_level=None, timeout=10)) as db:
        db.row_factory = sqlite3.Row
        # SQLite's documented parent-table rebuild requires disabling FK actions before BEGIN.
        # migrate_routes runs foreign_key_check before this migration transaction commits.
        db.execute("PRAGMA foreign_keys=OFF" if rebuild_rooms else "PRAGMA foreign_keys=ON")
        db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
        with db:
            yield db


def migrate_database(path):
    with database(path, write=True, rebuild_rooms=True) as db:
        device_routes.migrate_routes(db)
        migrate_profiles(db)
        device_api_module().migrate_device(db)


def device_api_module():
    if __package__:
        from . import device_service
    else:
        import device_service
    return device_service


def launcher_module():
    if __package__:
        from . import launcher
    else:
        import launcher
    return launcher


def verified_launcher_base(identity):
    try:
        return normalized_base(launcher_module().url_for_identity(identity))
    except Exception as exc:
        raise astrbot.NotDispatched("无法核实已绑定的 Launcher 实例，请确认该实例正在运行。") from exc


def initialize(path, upstream, aliases=(), launcher_bound=False):
    upstream = normalized_base(upstream)
    sources = {normalized_base(alias) for alias in aliases}
    launcher_id = None
    if launcher_bound:
        try:
            launcher_id = str(uuid.UUID(launcher_module().identity_for_url(upstream)))
        except Exception as exc:
            raise ApiError(409, "无法将此地址核实为唯一运行的 Launcher 实例。") from exc
    # Refuse to reinitialize or rebind an existing instance or its rooms.
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    instance = str(uuid.uuid4())
    with closing(sqlite3.connect(path)) as db:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript("BEGIN IMMEDIATE;\n" + SCHEMA)
        migrate_profiles(db)
        db.execute("INSERT INTO config VALUES(?,?,?)", (instance, upstream, launcher_id))
        db.executemany("INSERT INTO sources VALUES(?)", [(base,) for base in sources])
        device_routes.migrate_routes(db)
        db.commit()
    return instance


@contextmanager
def serving_lock(path):
    """CLI transactions remain available while exactly one HTTP process owns recovery."""
    lock_path = str(Path(path).resolve()) + ".serve.lock"
    with open(lock_path, "a+b") as handle:
        if os.name == "nt":
            import msvcrt
            handle.seek(0)
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as exc:
                raise ApiError(409, "此数据库已有共享服务正在运行。") from exc
        else:
            import fcntl
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ApiError(409, "此数据库已有共享服务正在运行。") from exc
        yield  # Closing the descriptor releases the OS lock, including after process exit.


def new_code(db, room, purpose):
    code = secrets.token_urlsafe(12)
    now = int(time.time())
    db.execute("DELETE FROM codes WHERE (room IS ? AND purpose=?) OR expires<=?", (room, purpose, now))
    db.execute("INSERT INTO codes VALUES(?,?,?,?)", (digest(code), room, now + CODE_LIFETIME, purpose))
    return {"code": code, "expiresIn": CODE_LIFETIME}


def check_code(db, code, create):
    entry = db.execute("SELECT room,expires,purpose FROM codes WHERE digest=?", (digest(code),)).fetchone()
    if not entry or (entry["purpose"] == "create") != create or entry["expires"] <= int(time.time()):
        raise ApiError(403, "授权码无效、已过期或已使用。")
    return entry


def consume_code(db, code, create):
    entry = check_code(db, code, create)
    db.execute("DELETE FROM codes WHERE digest=?", (digest(code),))
    return entry


def grant(db, room, owner):
    if not db.execute("SELECT 1 FROM rooms JOIN config ON rooms.upstream=config.instance WHERE rooms.id=? AND rooms.legacy=1",
                      (room,)).fetchone():
        raise ApiError(403, "原房间凭据只适用于默认上游。")
    token = secrets.token_urlsafe(32)
    db.execute("INSERT INTO devices VALUES(?,?,?,?)", (str(uuid.uuid4()), digest(token), room, int(owner)))
    instance = db.execute("SELECT instance FROM config").fetchone()[0]
    return {"token": token, "instance": instance, "room": room, "owner": owner}


def authorize(db, token, owner=False):
    device = db.execute("SELECT room,owner FROM devices WHERE digest=?", (digest(token),)).fetchone()
    if not device:
        raise ApiError(401, "设备凭据无效或已撤销。")
    if owner and not device["owner"]:
        raise ApiError(403, "此操作需要会话主人权限。")
    return device["room"]


def fields(body, allowed):
    if not isinstance(body, dict) or body.keys() != set(allowed):
        raise ApiError(400, "请求字段不符合接口要求。")


def string(body, key, *, empty=False, max_bytes=MAX_BODY):
    value = body.get(key)
    if not isinstance(value, str) or (not empty and not value.strip()):
        raise ApiError(400, "请求中的文本字段无效。")
    try:
        size = len(value.encode("utf-8"))
    except UnicodeEncodeError as exc:
        raise ApiError(400, "文本包含无效字符。") from exc
    if size > max_bytes:
        raise ApiError(413, "文本超过大小限制。")
    return value


def request_id(value):
    try:
        return str(uuid.UUID(value))
    except (TypeError, AttributeError, ValueError) as exc:
        raise ApiError(400, "请求编号必须是 UUID。") from exc


def seed(body):
    source = string(body, "sourceBase", empty=True, max_bytes=4096)
    history = body["history"]
    if not isinstance(history, list):
        raise ApiError(400, "历史记录格式无效。")
    if len(history) > MAX_HISTORY:
        raise ApiError(413, "导入上限为 10000 轮。")
    if source:
        source = normalized_base(source)
        username = string(body, "username", max_bytes=4096)
        session = string(body, "session", max_bytes=4096)
        if session != session.strip() or username != username.strip():
            raise ApiError(400, "用户或会话标识含首尾空白。")
    else:
        if history:
            raise ApiError(400, "没有原服务地址的会话仅可从空历史开始。")
        username = "chatbox-" + secrets.token_hex(16)
        session = str(uuid.uuid4())
    for turn in history:
        fields(turn, ("question", "answer", "status"))
        string(turn, "question", empty=True)
        string(turn, "answer", empty=True)
        if turn["status"] not in ("complete", "unknown"):
            raise ApiError(400, "仅可导入已结束或结果未知的轮次。")
    return source, username, session, history


def check_unclaimed(db, upstream, session):
    # Legacy room creation reserves a SID across its single configured instance.
    if db.execute("SELECT 1 FROM rooms WHERE upstream=? AND session=?", (upstream, session)).fetchone():
        raise ApiError(409, "此 AstrBot 会话已绑定共享房间。")


def find_turn(db, room, turn_id):
    row = db.execute(f"SELECT {TURN_FIELDS} FROM turns WHERE room=? AND id=?", (room, turn_id)).fetchone()
    return dict(row) if row else None


def number(params, name, default, minimum, maximum):
    value = params.get(name, [str(default)])[0]
    if not value.isascii() or not value.isdecimal() or len(value) > 19:
        raise ApiError(400, "分页参数无效。")
    result = int(value)
    if not minimum <= result <= maximum:
        raise ApiError(400, "分页参数超出范围。")
    return result


def turn_page(rows, limit):
    turns = []
    page_bytes = 0
    for row in rows:
        turn = dict(row)
        size = len(encoded(turn))
        if turns and (len(turns) >= limit or page_bytes + size > MAX_BODY):
            return {"turns": turns, "more": True}
        turns.append(turn)
        page_bytes += size
    return {"turns": turns, "more": False}


PROFILE_READ = {"/v1/profile/" + action for action in ("sessions", "turns", "turn", "devices")}
PROFILE_WRITE = {"/v1/profile/" + action for action in
                 ("create", "invite", "join", "revoke", "status", "session", "import", "remove", "send")}
DEVICE_READ = {"/v1/device/" + action for action in
               ("health", "me", "profiles", "sessions", "turns", "turn", "operation", "devices")}
DEVICE_WRITE = {"/v1/device/" + action for action in
                ("invite", "preview", "join", "endpoint", "prove", "revoke", "profile", "session", "import",
                 "start", "send", "remove", "media-begin", "media-end", "wearable-config", "wearable-sync",
                 "wearable-clear", "wearable-credential", "wearable-query", "wearable-jobs",
                 "wearable-job-query", "wearable-job-finish")}
READ_ROUTES = {"/v1/turn", "/v1/turns"} | PROFILE_READ | DEVICE_READ
OWNER_ROUTES = {"/v1/invite", "/v1/cancel-invite", "/v1/revoke-guests"}
WRITE_ROUTES = {"/v1/create", "/v1/join", "/v1/send"} | OWNER_ROUTES | PROFILE_WRITE | DEVICE_WRITE

# The source device owns native media delivery, so restarting the bridge does not
# establish whether that upstream request has started or stopped.
MEDIA_RESERVATION = """EXISTS(SELECT 1 FROM device_operations op WHERE op.id=turns.id
                              AND op.room=turns.room AND op.action='media-begin')"""
MEDIA_RESOLVE_NOTICE = "仅在主人核实来源设备的发送已停止且 AstrBot 上游已空闲后，才可解除指定媒体轮次的预留。"


class Service:
    def __init__(self, path, key, workers):
        self.path = path
        self.key = key
        self.workers = workers
        self.profiles = ProfileApi(self)
        self.routes = device_routes.DeviceRoutes(self)
        migrate_database(path)
        with database(path, write=True) as db:
            config = dict(db.execute("SELECT * FROM config").fetchone())
            self.default_upstream = config["instance"]
            self.upstream = config["upstream"]
            self.launcher_id = config.get("launcher_id")
            self.aliases = {row[0] for row in db.execute("SELECT base FROM sources")}
            abandoned = "status='running' AND NOT " + MEDIA_RESERVATION
            db.execute("UPDATE profile_sessions SET fresh=0 WHERE fresh=1 AND room IN "
                       "(SELECT room FROM turns WHERE " + abandoned + ")")
            db.execute("UPDATE turns SET status='unknown',needs_review=1 WHERE " + abandoned)
            db.execute("UPDATE device_operations SET upstream_run_id=NULL WHERE upstream_run_id IS NOT NULL")
        self.devices = device_api_module().DeviceApi(self)

    def current_base(self):
        return verified_launcher_base(self.launcher_id) if self.launcher_id else self.upstream

    def proves_launcher_profile(self, original, source_id):
        if not self.launcher_id or source_id != "launcher:" + self.launcher_id:
            return False
        # Before per-profile UUIDs, only a non-alias loopback address needed
        # verification against this service's immutable Launcher identity.
        return original["launcher_id"] == self.launcher_id or (
            original["launcher_id"] is None and launcher_endpoint(original["base"]) and original["base"] not in self.aliases)

    def create(self, body):
        fields(body, ("code", "sourceBase", "username", "session", "history"))
        code = string(body, "code", max_bytes=128)
        with database(self.path) as db:
            check_code(db, code, create=True)
            source, username, session, history = seed(body)
            check_unclaimed(db, self.default_upstream, session)
        if source:
            try:
                base = self.current_base()
                if source != base and source not in self.aliases:
                    raise ApiError(409, "原对话地址与此共享服务绑定的 AstrBot 不匹配。")
                if history:
                    exists = astrbot.session_exists(base, self.key, username, session,
                                                    self.current_base if self.launcher_id else None)
                    if not exists:
                        raise ApiError(409, "AstrBot 未找到属于该用户的原会话，无法导入历史。")
            except (astrbot.NotDispatched, astrbot.SessionIndexError) as exc:
                raise ApiError(502, str(exc)) from exc
            except (OSError, http.client.HTTPException) as exc:
                raise ApiError(502, "无法读取 AstrBot 会话列表，请检查上游连接。") from exc
        # No database transaction spans network I/O. Recheck grant, expiry, and uniqueness together.
        with database(self.path, write=True) as db:
            consume_code(db, code, create=True)
            check_unclaimed(db, self.default_upstream, session)
            room = self.routes.ensure_room(db, self.default_upstream, username, session, legacy=True)
            for seq, turn in enumerate(history, 1):
                unresolved = seq == len(history) and turn["status"] == "unknown"
                db.execute("INSERT INTO turns(room,seq,id,question,answer,status,needs_review) VALUES(?,?,?,?,?,?,?)",
                           (room, seq, str(uuid.uuid4()), turn["question"], turn["answer"], turn["status"], unresolved))
            return grant(db, room, True)

    def read(self, route, token, params):
        if route in DEVICE_READ:
            return self.devices.read(route.rsplit("/", 1)[1], token, params)
        if route in PROFILE_READ:
            return self.profiles.read(route.rsplit("/", 1)[1], token, params)
        with database(self.path) as db:
            room = authorize(db, token)
            if route == "/v1/turn":
                if set(params) != {"id"}:
                    raise ApiError(400, "需要指定轮次编号。")
                turn = find_turn(db, room, request_id(params["id"][0]))
                if turn is None:
                    raise ApiError(404, "找不到此轮对话。")
                return turn
            if set(params) - {"after", "limit"}:
                raise ApiError(400, "分页参数无效。")
            after = number(params, "after", 0, 0, 2**63 - 1)
            limit = number(params, "limit", 50, 1, 100)
            rows = db.execute(f"SELECT {TURN_FIELDS} FROM turns WHERE room=? AND seq>? ORDER BY seq LIMIT ?",
                              (room, after, limit + 1))
            return turn_page(rows, limit)

    def write(self, route, token, body):
        if route in DEVICE_WRITE:
            return self.devices.write(route.rsplit("/", 1)[1], token, body)
        if route in PROFILE_WRITE:
            return self.profiles.write(route.rsplit("/", 1)[1], token, body)
        if route == "/v1/create":
            return 200, self.create(body)
        with database(self.path, write=True) as db:
            if route == "/v1/join":
                fields(body, ("code",))
                entry = consume_code(db, string(body, "code", max_bytes=128), create=False)
                room = entry["room"]
                recovered = entry["purpose"] == "recover"
                if recovered:
                    db.execute("DELETE FROM devices WHERE room=? AND owner=1", (room,))
                    db.execute("DELETE FROM codes WHERE room=? AND purpose='invite'", (room,))
                return 200, grant(db, room, recovered)
            room = authorize(db, token, owner=route in OWNER_ROUTES)
            if route in OWNER_ROUTES:
                fields(body, ())
                if route == "/v1/invite":
                    return 200, new_code(db, room, "invite")
                if route == "/v1/revoke-guests":
                    db.execute("DELETE FROM devices WHERE room=? AND owner=0", (room,))
                else:
                    db.execute("DELETE FROM codes WHERE room=? AND purpose='invite'", (room,))
                return 200, {}
            fields(body, ("id", "question"))
            turn_id = request_id(body["id"])
            question = string(body, "question", max_bytes=MAX_QUESTION)
            existing = self.retry(db, room, turn_id, question)
            if existing:
                return existing
            result = self.enqueue(db, room, turn_id, question)
            identity = db.execute("SELECT username,session FROM rooms WHERE id=?", (room,)).fetchone()
        return self.dispatch_turn(room, result, identity["username"], identity["session"], legacy=True)

    @staticmethod
    def retry(db, room, turn_id, question):
        existing = find_turn(db, room, turn_id)
        if existing:
            if existing["question"] != question:
                raise ApiError(409, "此请求编号已用于其他内容。")
            return (202 if existing["status"] == "running" else 200), existing

    @staticmethod
    def enqueue(db, room, turn_id, question):
        busy = db.execute("SELECT status FROM turns WHERE room=? AND (status='running' OR needs_review=1) LIMIT 1",
                          (room,)).fetchone()
        if busy:
            if busy["status"] == "running":
                raise ApiError(409, "会话已有回复正在生成，请稍后再发。")
            raise ApiError(409, "上游执行结果未知，请服务主人确认 AstrBot 已空闲并运行 resolve。")
        seq = db.execute("SELECT COALESCE(MAX(seq),0)+1 FROM turns WHERE room=?", (room,)).fetchone()[0]
        db.execute("INSERT INTO turns(room,seq,id,question,answer,status) VALUES(?,?,?,?,?,'running')",
                   (room, seq, turn_id, question, ""))
        profile_activity(db, room)
        return find_turn(db, room, turn_id)

    def dispatch_turn(self, room, result, username, session, *, legacy=False):
        # Commit before contacting AstrBot. A lost HTTP response never owns this job's lifetime.
        turn_id = result["id"]
        try:
            self.workers.submit(self.generate, room, turn_id, username, session, result["question"], legacy=legacy)
        except RuntimeError:
            self.finish(room, turn_id, "unknown", needs_review=False)
            raise ApiError(503, "服务正在停止，请查询该轮结果。")
        return 202, result

    def finish(self, room, turn_id, status, needs_review):
        with database(self.path, write=True) as db:
            if db.execute("UPDATE turns SET status=?,needs_review=? WHERE room=? AND id=? AND status='running'",
                          (status, needs_review, room, turn_id)).rowcount:
                db.execute("UPDATE device_operations SET upstream_run_id=NULL WHERE id=? AND room=? "
                           "AND upstream_run_id IS NOT NULL", (turn_id, room))
                if status == "complete" or needs_review:
                    db.execute("UPDATE profile_sessions SET fresh=0 WHERE room=? AND fresh=1", (room,))
                profile_activity(db, room)

    def generate(self, room, turn_id, username, session, question, *, legacy=False):
        last_answer = ""

        def bind_run(run_id):
            with database(self.path, write=True) as db:
                db.execute("UPDATE device_operations SET upstream_run_id=? WHERE id=? AND room=? "
                           "AND upstream_run_id IS NULL", (run_id, turn_id, room))

        def publish(answer):
            nonlocal last_answer
            if answer == last_answer:
                return
            if len(answer.encode("utf-8")) > MAX_REPLY:
                raise ValueError("回复超过大小限制")
            with database(self.path, write=True) as db:
                db.execute("UPDATE turns SET answer=? WHERE room=? AND id=? AND status='running'",
                           (answer, room, turn_id))
            last_answer = answer

        status = "unknown"
        needs_review = True
        try:
            with database(self.path) as db:
                identity = db.execute("SELECT upstream FROM rooms WHERE id=? AND username=? AND session=?",
                                      (room, username, session)).fetchone()
                enrolled = db.execute("""SELECT op.profile,g.settings FROM device_operations op
                    JOIN device_catalog c ON c.profile=op.profile
                    JOIN wearable_grants g ON g.device=c.origin AND g.plugin_digest IS NOT NULL
                    JOIN device_members m ON m.id=op.device AND m.revoked=0
                    WHERE op.room=? AND op.id=? AND op.action IN ('start','send') AND (m.owner=1 OR m.id=c.origin)""",
                                      (room, turn_id)).fetchone()
            if not identity:
                raise astrbot.NotDispatched("无法确认此房间的上游身份。")
            base, key, verify = self.routes.target(identity[0], legacy=legacy)
            dispatch = {}
            if enrolled and json.loads(enrolled["settings"])["profile"] == enrolled["profile"]:
                dispatch["on_run"] = bind_run
            astrbot.send(base, key, username, session, question, publish, verify, **dispatch)
            status = "complete"
            needs_review = False
        except astrbot.NotDispatched:
            needs_review = False
        except Exception:
            # Transport errors can contain private upstream data. Keep the last confirmed prefix.
            pass
        finally:
            self.finish(room, turn_id, status, needs_review)


def profile_code(db, profile):
    code = secrets.token_urlsafe(12)
    now = int(time.time())
    db.execute("DELETE FROM profile_codes WHERE profile IS ? OR expires<=?", (profile, now))
    db.execute("INSERT INTO profile_codes VALUES(?,?,?)", (digest(code), profile, now + CODE_LIFETIME))
    return {"code": code, "expiresIn": CODE_LIFETIME}


def profile_exchange(db, code, creating, consume=True):
    row = db.execute("SELECT profile FROM profile_codes WHERE digest=? AND expires>?",
                     (digest(code), int(time.time()))).fetchone()
    if not row or (row[0] is None) != creating:
        raise ApiError(403, "档案授权码无效、已过期或已使用。")
    if consume:
        db.execute("DELETE FROM profile_codes WHERE digest=?", (digest(code),))
    return row[0]


def profile_create_code(db, code, consume):
    recovery = db.execute("SELECT profile FROM profile_recovery WHERE digest=? AND expires>?",
                          (digest(code), int(time.time()))).fetchone()
    if recovery:
        if consume:
            db.execute("DELETE FROM profile_recovery WHERE profile=?", (recovery[0],))
        return recovery[0]
    profile_exchange(db, code, creating=True, consume=consume)
    return None


def profile_grant(db, profile, owner):
    token = secrets.token_urlsafe(32)
    device = str(uuid.uuid4())
    name = "Windows 主人" if owner else "手机 " + device[:8]
    db.execute("INSERT INTO profile_devices VALUES(?,?,?,?,?)", (device, digest(token), profile, name, int(owner)))
    return {"token": token, "owner": owner, "profile": profile,
            "instance": db.execute("SELECT instance FROM config").fetchone()[0],
            "name": db.execute("SELECT name FROM profiles WHERE id=?", (profile,)).fetchone()[0]}


def profile_auth(db, token, owner=False):
    device = db.execute("SELECT profile,owner FROM profile_devices WHERE digest=?", (digest(token),)).fetchone()
    if not device:
        raise ApiError(401, "档案设备凭据无效或已撤销。")
    if owner and not device["owner"]:
        raise ApiError(403, "此操作需要档案主人权限。")
    return device["profile"]


def get_member(db, profile, sid):
    row = db.execute("SELECT * FROM profile_sessions WHERE profile=? AND sid=? AND removed=0", (profile, sid)).fetchone()
    if not row:
        raise ApiError(404, "此会话不在已授权的档案目录中。")
    return row


def directory_entry(row):
    return {key: row[key] for key in ("sid", "title", "activity", "total", "imported", "reason")}


def nonnegative(body, key):
    value = body.get(key)
    if type(value) is not int or not 0 <= value <= 2**63 - 1:
        raise ApiError(400, "数量、位置或时间无效。")
    return value


def launcher_endpoint(base):
    url = urlsplit(base)
    return url.scheme == "http" and url.hostname == "127.0.0.1" and not url.path


class ProfileApi:
    def __init__(self, service):
        self.service = service
        self.path = service.path

    def create(self, body):
        fields(body, ("code", "sourceBase", "username", "sourceId", "name"))
        code = string(body, "code", max_bytes=128)
        with database(self.path) as db:
            profile_create_code(db, code, consume=False)
        base = normalized_base(string(body, "sourceBase", max_bytes=4096))
        username = string(body, "username", max_bytes=4096)
        if username != username.strip():
            raise ApiError(400, "用户标识含首尾空白。")
        source_id = string(body, "sourceId", max_bytes=4096)
        name = string(body, "name", max_bytes=4096)
        launcher_id = self.service.launcher_id
        launcher_profile = launcher_id is not None and source_id == "launcher:" + launcher_id
        if launcher_id and source_id.startswith("launcher:") and not launcher_profile:
            raise ApiError(409, "电脑档案的 Launcher 实例与共享服务绑定的实例不同。")
        verify_launcher = launcher_profile and launcher_endpoint(base)
        # An old loopback address can now belong to a different instance, even if
        # it is listed as an alias. Check the bound UUID's live Dashboard endpoint.
        if verify_launcher or base not in self.service.aliases:
            try:
                actual_base = self.service.current_base()
            except astrbot.NotDispatched as exc:
                raise ApiError(409, str(exc)) from exc
            if base != actual_base:
                raise ApiError(409, "原档案地址与此共享服务绑定的 AstrBot 不匹配。")
        with database(self.path, write=True) as db:
            recover = profile_create_code(db, code, consume=True)
            existing = db.execute("SELECT id FROM profiles WHERE base=? AND username=? AND source_id=?",
                                  (base, username, source_id)).fetchone()
            if recover is not None:
                original = db.execute("SELECT base,launcher_id FROM profiles WHERE id=? AND username=? AND source_id=?",
                                      (recover, username, source_id)).fetchone()
                if not original:
                    raise ApiError(403, "恢复码与原电脑档案、地址或用户身份不匹配。")
                if original["base"] != base:
                    if not verify_launcher or not self.service.proves_launcher_profile(original, source_id):
                        raise ApiError(403, "无法核实原档案与新地址属于同一 Launcher 实例，无法更换原地址。")
                    if existing:
                        raise ApiError(409, "此地址已有同来源的其他档案，无法合并。请核对 profiles 列出的编号。")
                if verify_launcher:
                    db.execute("UPDATE profiles SET base=?,launcher_id=? WHERE id=?", (base, launcher_id, recover))
                db.execute("DELETE FROM profile_devices WHERE profile=? AND owner=1", (recover,))
                db.execute("DELETE FROM profile_codes WHERE profile=?", (recover,))
                return profile_grant(db, recover, True)
            if launcher_profile:
                existing = db.execute("SELECT 1 FROM profiles WHERE username=? AND source_id=? LIMIT 1",
                                      (username, source_id)).fetchone()
            if existing:
                raise ApiError(409, "此电脑档案已授权。如主人凭据丢失，请在服务主机运行 profile-recover。")
            profile = str(uuid.uuid4())
            db.execute("INSERT INTO profiles(id,source_id,base,username,name,launcher_id) VALUES(?,?,?,?,?,?)",
                       (profile, source_id, base, username, name, launcher_id if verify_launcher else None))
            return profile_grant(db, profile, True)

    def read(self, action, token, params):
        with database(self.path) as db:
            profile = profile_auth(db, token, owner=action == "devices")
            if action == "devices":
                fields(params, ())
                guests = db.execute("SELECT id,name FROM profile_devices WHERE profile=? AND owner=0 ORDER BY rowid",
                                    (profile,))
                return {"devices": [dict(row) for row in guests]}
            if action == "sessions":
                if set(params) - {"offset", "limit", "revision"}:
                    raise ApiError(400, "目录分页参数无效。")
                offset = number(params, "offset", 0, 0, 2**63 - 1)
                limit = number(params, "limit", 50, 1, 100)
                listing = db.execute("SELECT name,revision,complete,expected,issue FROM profiles WHERE id=?",
                                     (profile,)).fetchone()
                if ("revision" in params and number(params, "revision", 0, 0, 2**63-1) != listing["revision"]
                        or offset and "revision" not in params):
                    raise ApiError(409, "目录已变动，请重新读取第一页。")
                rows = db.execute("SELECT * FROM profile_sessions WHERE profile=? AND removed=0 ORDER BY activity DESC,sid "
                                  "LIMIT ? OFFSET ?", (profile, limit + 1, offset)).fetchall()
                complete = bool(listing["complete"])
                if complete:
                    count, incomplete = db.execute("SELECT count(*),sum(total<>imported) FROM profile_sessions "
                                                   "WHERE profile=? AND removed=0", (profile,)).fetchone()
                    complete = count == listing["expected"] and not incomplete
                return {**dict(listing), "complete": complete, "sessions": [directory_entry(r) for r in rows[:limit]],
                        "more": len(rows) > limit}
            keys = {"sid", "id"} if action == "turn" else {"sid", "after", "limit"}
            if "sid" not in params or set(params) - keys or (action == "turn" and "id" not in params):
                raise ApiError(400, "会话或轮次参数无效。")
            sid = params["sid"][0]
            member = get_member(db, profile, sid)
            if member["ready"]:
                query = f"SELECT {TURN_FIELDS} FROM turns WHERE room=?"
                args = (member["room"],)
            else:
                query = f"SELECT {TURN_FIELDS} FROM profile_imports WHERE profile=? AND sid=?"
                args = (profile, sid)
            if action == "turn":
                turn_id = request_id(params["id"][0])
                row = db.execute(f"SELECT * FROM ({query}) WHERE id=?", (*args, turn_id)).fetchone()
                if not row and member["ready"]:
                    # Cached IDs from before room attachment remain valid for the same
                    # exact immutable prefix position. A new page still uses the room UUID.
                    row = db.execute("""SELECT t.seq,p.id,t.question,t.answer,t.status FROM profile_imports p
                                        JOIN turns t ON t.room=? AND t.seq=p.seq
                                        WHERE p.profile=? AND p.sid=? AND p.id=?""",
                                     (member["room"], profile, sid, turn_id)).fetchone()
                if not row:
                    raise ApiError(404, "找不到此轮对话。")
                return dict(row)
            after = number(params, "after", 0, 0, 2**63 - 1)
            limit = number(params, "limit", 50, 1, 100)
            rows = db.execute(f"SELECT * FROM ({query}) WHERE seq>? ORDER BY seq LIMIT ?", (*args, after, limit + 1))
            return turn_page(rows, limit)

    def write(self, action, token, body):
        if action == "create":
            return 200, self.create(body)
        if action == "session":
            return 200, self.publish_session(token, body)
        if action == "send":
            return self.send(token, body)
        with database(self.path, write=True) as db:
            if action == "join":
                fields(body, ("code",))
                profile = profile_exchange(db, string(body, "code", max_bytes=128), creating=False)
                return 200, profile_grant(db, profile, False)
            profile = profile_auth(db, token, owner=True)
            if action == "invite":
                fields(body, ())
                return 200, profile_code(db, profile)
            if action == "revoke":
                fields(body, ("device",))
                device = string(body, "device", max_bytes=128)
                if not db.execute("DELETE FROM profile_devices WHERE profile=? AND id=? AND owner=0",
                                  (profile, device)).rowcount:
                    raise ApiError(404, "此档案中没有该来宾设备。")
            elif action == "status":
                fields(body, ("complete", "expected", "issue"))
                complete = body["complete"]
                if type(complete) is not bool:
                    raise ApiError(400, "同步状态无效。")
                expected = nonnegative(body, "expected")
                issue = string(body, "issue", empty=True, max_bytes=MAX_QUESTION)
                db.execute("UPDATE profiles SET complete=?,expected=?,issue=?,revision=revision+1 WHERE id=? "
                           "AND (complete<>? OR expected<>? OR issue<>?)",
                           (complete, expected, issue, profile, complete, expected, issue))
            elif action == "remove":
                fields(body, ("sid",))
                sid = string(body, "sid", max_bytes=4096)
                # Persist even a removal that overtakes the very first publication.
                db.execute("""INSERT INTO profile_sessions(profile,sid,title,activity,total,removed,generation)
                              VALUES(?,?,'',0,0,1,1) ON CONFLICT(profile,sid) DO UPDATE
                              SET removed=1,generation=generation+1""", (profile, sid))
            elif action == "import":
                return 200, self.import_batch(db, profile, body)
            return 200, {}

    def check_origin(self, username, sid):
        return self.service.routes.check_origin(self.service.default_upstream, username, sid, legacy=True)

    @staticmethod
    def origin_reason(fresh, exists, failure):
        if failure:
            return failure
        if not exists and not fresh:
            return "AstrBot 未找到属于该用户的原会话。已上传的历史可读，无法接续。"
        return ""

    def publish_session(self, token, body):
        with database(self.path) as db:
            profile = profile_auth(db, token, owner=True)
            username = db.execute("SELECT username FROM profiles WHERE id=?", (profile,)).fetchone()[0]
            sid = string(body, "sid", max_bytes=4096)
            before = db.execute("SELECT generation,removed FROM profile_sessions WHERE profile=? AND sid=?",
                                (profile, sid)).fetchone()
            generation = before["generation"] if before else 0
            restore = body.get("restore", False)
            if type(restore) is not bool:
                raise ApiError(400, "恢复标记无效。")
            if before and before["removed"] and not restore:
                raise ApiError(409, "此会话已从档案移除，请主人明确恢复。")
        allowed = {"sid", "title", "activity", "total"}
        fields(body, allowed | (body.keys() & {"legacyToken", "restore"}))
        if sid != sid.strip():
            raise ApiError(400, "会话标识含首尾空白。")
        title = string(body, "title", empty=True, max_bytes=4096)
        total = nonnegative(body, "total")
        activity = nonnegative(body, "activity")
        legacy = string(body, "legacyToken", empty=True, max_bytes=128) if "legacyToken" in body else ""
        exists, failure = self.check_origin(username, sid)
        with database(self.path, write=True) as db:
            profile_auth(db, token, owner=True)
            previous = db.execute("SELECT * FROM profile_sessions WHERE profile=? AND sid=?", (profile, sid)).fetchone()
            if previous and (previous["generation"] != generation or (previous["removed"] and not restore)):
                raise ApiError(409, "发布期间会话已被移除，请主人明确恢复。")
            if previous and total < previous["total"]:
                raise ApiError(409, f"电脑历史范围缩短。原声明 {previous['total']} 轮，已导入 {previous['imported']} 轮。")
            upstream = self.service.default_upstream
            bound = self.service.routes.find_room(db, upstream, username, sid)
            other_user = not bound and db.execute("SELECT 1 FROM rooms WHERE upstream=? AND session=?",
                                                  (upstream, sid)).fetchone()
            room = previous["room"] if previous else None
            # Only an unclaimed, genuinely new empty conversation can acquire its upstream
            # identity on first send. Once an original is observed, its disappearance is final
            # until AstrBot's index confirms it again, including for an originally empty chat.
            unclaimed = not bound and not other_user and not legacy
            already_fresh = previous is not None and bool(previous["fresh"]) and room is not None
            fresh = not total and not exists and (unclaimed or already_fresh)
            reason = self.origin_reason(fresh, exists, failure)
            if not room and (bound or other_user or legacy):
                claimed = bound and db.execute("SELECT 1 FROM profile_sessions WHERE room=? AND profile<>?",
                                               (bound, profile)).fetchone()
                if not bound or not self.service.routes.prove_room(db, bound, legacy):
                    reason = "无法确认原共享房间的主人授权及会话身份。仅可阅读电脑上传的历史。"
                elif claimed:
                    reason = "原共享房间已授权给另一档案。本档案可阅读自己上传的历史，无法接续该房间。"
                elif not reason:
                    room = bound
            elif not room and not reason:
                room = self.service.routes.ensure_room(db, upstream, username, sid, legacy=True)
            db.execute("""INSERT INTO profile_sessions(profile,sid,title,activity,total,room,fresh) VALUES(?,?,?,?,?,?,?)
                          ON CONFLICT(profile,sid) DO UPDATE SET title=excluded.title,
                            activity=MAX(activity,excluded.activity),ready=(ready AND total=excluded.total),
                            total=excluded.total,room=excluded.room,fresh=excluded.fresh,removed=0""",
                       (profile, sid, title, activity, total, room, fresh))
            entry = get_member(db, profile, sid)
            if not reason and room and not entry["ready"] and entry["total"] == entry["imported"]:
                reconcile(db, {**dict(entry), "reason": ""})
            else:
                db.execute("UPDATE profile_sessions SET reason=? WHERE profile=? AND sid=?", (reason, profile, sid))
            return directory_entry(get_member(db, profile, sid))

    @staticmethod
    def import_batch(db, profile, body):
        fields(body, ("sid", "start", "turns"))
        sid = string(body, "sid", max_bytes=4096)
        entry = get_member(db, profile, sid)
        start = nonnegative(body, "start")
        turns = body["turns"]
        if not isinstance(turns, list):
            raise ApiError(400, "历史分批数据无效。")
        if len(turns) > MAX_HISTORY:
            raise ApiError(413, "单批最多 10000 轮，请缩小批次。")
        if start > entry["imported"] or start + len(turns) > entry["total"]:
            raise ApiError(409, f"历史批次超出范围。已导入 {entry['imported']} 轮，共声明 {entry['total']} 轮。")
        for seq, turn in enumerate(turns, start + 1):
            fields(turn, ("id", "question", "answer", "status"))
            row = (request_id(turn["id"]), string(turn, "question", empty=True), string(turn, "answer", empty=True),
                   turn["status"])
            if row[3] not in ("complete", "unknown"):
                raise ApiError(400, f"第 {seq} 轮的历史状态无效。")
            if seq <= entry["imported"]:
                previous = db.execute("SELECT id,question,answer,status FROM profile_imports "
                                      "WHERE profile=? AND sid=? AND seq=?", (profile, sid, seq)).fetchone()
                if not previous or tuple(previous) != row:
                    raise ApiError(409, f"第 {seq} 轮与先前上传的电脑记录不同。")
            else:
                try:
                    db.execute("INSERT INTO profile_imports VALUES(?,?,?,?,?,?,?)", (profile, sid, seq, *row))
                except sqlite3.IntegrityError as exc:
                    raise ApiError(409, f"第 {seq} 轮的请求编号已在另一位置使用。") from exc
        imported = max(entry["imported"], start + len(turns))
        if imported != entry["imported"]:
            db.execute("UPDATE profile_sessions SET imported=? WHERE profile=? AND sid=?", (imported, profile, sid))
            reconcile(db, get_member(db, profile, sid))
        return {"imported": imported}

    def send(self, token, body):
        with database(self.path) as db:
            profile = profile_auth(db, token)
            fields(body, ("sid", "id", "question"))
            sid = string(body, "sid", max_bytes=4096)
            turn_id = request_id(body["id"])
            question = string(body, "question", max_bytes=MAX_QUESTION)
            entry = get_member(db, profile, sid)
            if not entry["ready"]:
                raise ApiError(409, entry["reason"] or f"历史尚未同步完整（{entry['imported']}/{entry['total']}）。")
            room = entry["room"]
            generation = entry["generation"]
            retry = self.service.retry(db, room, turn_id, question)
            if retry:
                return retry
            username = db.execute("SELECT username FROM profiles WHERE id=?", (profile,)).fetchone()[0]
        exists, failure = self.check_origin(username, sid)
        with database(self.path, write=True) as db:
            profile_auth(db, token)
            entry = get_member(db, profile, sid)
            if (entry["generation"] != generation or entry["room"] != room or not entry["ready"]
                    or entry["total"] != entry["imported"]):
                raise ApiError(409, entry["reason"] or "目录或历史导入状态已变动，请刷新。")
            retry = self.service.retry(db, room, turn_id, question)
            if retry:
                return retry
            if db.execute("SELECT 1 FROM profile_imports WHERE profile=? AND sid=? AND id=?",
                          (profile, sid, turn_id)).fetchone():
                raise ApiError(409, "此请求编号已用于电脑导入的原房间历史。")
            reason = self.origin_reason(entry["fresh"], exists, failure)
            db.execute("UPDATE profile_sessions SET reason=? WHERE profile=? AND sid=?", (reason, profile, sid))
            if not reason:
                result = self.service.enqueue(db, room, turn_id, question)
                if exists:
                    db.execute("UPDATE profile_sessions SET fresh=0 WHERE profile=? AND sid=?", (profile, sid))
        if reason:
            raise ApiError(409, reason)
        return self.service.dispatch_turn(room, result, username, sid, legacy=True)


class Handler(BaseHTTPRequestHandler):
    timeout = 20

    def log_message(self, format, *args):
        # Never log URLs, credentials, conversations or raw peer errors.
        pass

    def json_response(self, status, value):
        data = encoded(value)
        self.close_connection = True
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def send_error(self, code, message=None, explain=None):
        self.json_response(code, {"error": "无效的 HTTP 请求或方法。"})

    def bearer(self):
        values = self.headers.get_all("Authorization", [])
        if len(values) != 1:
            raise ApiError(401, "需要有效的设备凭据。")
        scheme, _, token = values[0].partition(" ")
        if scheme.lower() != "bearer" or not token or len(token) > 128 or not token.isascii():
            raise ApiError(401, "设备凭据格式无效。")
        return token

    def body(self):
        if self.headers.get_content_type() != "application/json":
            raise ApiError(415, "需要 JSON 请求。")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or self.headers.get("Transfer-Encoding") is not None:
            raise ApiError(400, "需要明确且唯一的请求长度。")
        size = lengths[0]
        if not size.isascii() or not size.isdecimal():
            raise ApiError(400, "请求长度无效。")
        if len(size) > 9 or int(size) > MAX_BODY:
            raise ApiError(413, "请求超过 8 MiB，无法导入或发送。")
        raw = self.rfile.read(int(size))
        if len(raw) != int(size):
            raise ApiError(400, "请求未接收完整。")
        try:
            data = json.loads(raw.decode("utf-8"))
        except (ValueError, RecursionError) as exc:
            raise ApiError(400, "JSON 数据无效。") from exc
        if not isinstance(data, dict):
            raise ApiError(400, "请求内容必须是 JSON 对象。")
        return data

    def dispatch(self):
        try:
            route, _, raw_query = self.requestline.split()[1].partition("?")
            if route not in self.server.allowed_routes:
                raise ApiError(404, "接口不存在。")
            public = ("/v1/create", "/v1/join", "/v1/profile/create", "/v1/profile/join",
                      "/v1/device/health", "/v1/device/preview", "/v1/device/join", "/v1/device/prove")
            token = "" if route in public else self.bearer()
            if self.command == "GET" and route in READ_ROUTES:
                try:
                    params = parse_qs(raw_query, keep_blank_values=True, strict_parsing=True,
                                      max_num_fields=4 if route in DEVICE_READ else 3)
                except ValueError as exc:
                    raise ApiError(400, "查询参数无效。") from exc
                if any(len(values) != 1 for values in params.values()):
                    raise ApiError(400, "查询参数重复。")
                status, value = 200, self.server.service.read(route, token, params)
            elif self.command == "POST" and route in WRITE_ROUTES:
                if raw_query:
                    raise ApiError(400, "此接口不接受查询参数。")
                status, value = self.server.service.write(route, token, self.body())
            else:
                raise ApiError(405, "请求方法不适用。")
        except ApiError as exc:
            status, value = exc.status, {"error": str(exc)}
        except TimeoutError:
            status, value = 408, {"error": "接收请求超时。"}
        except Exception:
            status, value = 500, {"error": "共享服务处理请求失败。"}
        self.json_response(status, value)

    do_GET = dispatch
    do_POST = dispatch


class Server(ThreadingHTTPServer):
    # On normal shutdown, wait for requests before closing the generation executor and DB lock.
    daemon_threads = False

    def handle_error(self, request, client_address):
        pass


def validate_chat_key(key):
    if not isinstance(key, str) or not key or not key.isascii() or any(ord(c) < 33 or ord(c) > 126 for c in key):
        raise ApiError(400, "需要有效的 AstrBot 聊天密钥。")


@contextmanager
def open_server(path, key, port=8765, *, device_only=False):
    validate_chat_key(key)
    if not Path(path).is_file():
        raise ApiError(400, "请先通过 init 初始化数据库。")
    with serving_lock(path), ThreadPoolExecutor(max_workers=8) as workers:
        with Server(("127.0.0.1", port), Handler) as server:
            server.allowed_routes = (DEVICE_READ | DEVICE_WRITE) if device_only else (READ_ROUTES | WRITE_ROUTES)
            server.service = Service(path, key, workers)
            yield server


def cli(argv=None):
    parser = argparse.ArgumentParser(description="Chatbox 单用户共享服务")
    parser.add_argument("--db", type=Path, default=Path("chatbox-shared.sqlite3"), help="SQLite 数据库文件")
    cmds = parser.add_subparsers(dest="command", required=True)
    init = cmds.add_parser("init", help="创建实例并绑定固定 AstrBot 地址")
    init.add_argument("--upstream", required=True, help="AstrBot 基础地址")
    init.add_argument("--launcher", action="store_true", help="按 Launcher 实例 UUID 跟随已核实的当前回环端口")
    init.add_argument("--alias", action="append", default=[], help="同一 AstrBot 的已知客户端地址，可重复")
    cmds.add_parser("bootstrap", help="换发十分钟内有效的一次性会话创建码")
    cmds.add_parser("device-upstream", help="从本机标准输入 JSON 登记设备同步使用的 AstrBot 上游")
    cmds.add_parser("device-owner", help="从本机标准输入 JSON 登记设备同步主人")
    cmds.add_parser("profile-bootstrap", help="换发十分钟内有效的一次性档案创建码")
    cmds.add_parser("profiles", help="列出可在本机恢复的档案编号、名称及来源")
    profile_recover = cmds.add_parser("profile-recover", help="为指定档案签发十分钟内有效的一次性主人恢复码")
    profile_recover.add_argument("--profile", required=True, help="profiles 列出的档案编号")
    cmds.add_parser("rooms", help="列出房间编号及其绑定的上游用户和会话标识")
    recover = cmds.add_parser("recover-owner", help="为指定房间换发十分钟有效的一次性主人恢复码，在加入界面兑换")
    recover.add_argument("--room", required=True, help="rooms 列出的房间编号")
    revoke = cmds.add_parser("revoke-code", help="撤销当前创建码，或指定房间的邀请码和主人恢复码")
    revoke.add_argument("--room", help="房间编号。省略则撤销创建码")
    devices = cmds.add_parser("devices", help="列出设备编号、权限和房间")
    devices.add_argument("--room", help="仅列出指定房间的设备")
    remove = cmds.add_parser("revoke-device", help="撤销指定设备凭据")
    remove.add_argument("device", help="devices 列出的设备编号")
    resolve = cmds.add_parser("resolve", help="确认指定轮次已停止，允许该房间接收新消息。",
                              description="确认上游已空闲，保留指定轮次为结果未知。" + MEDIA_RESOLVE_NOTICE)
    resolve.add_argument("--room", required=True, help="房间编号")
    resolve.add_argument("--id", required=True, help="结果未知或来源已停止的媒体轮次编号")
    unresolved = cmds.add_parser("unresolved", help="列出待确认轮次与仍由来源设备持有的媒体预留")
    unresolved.add_argument("--room", help="仅列出指定房间")
    run = cmds.add_parser("serve", help="启动回环 HTTP 服务，从 ASTRBOT_CHAT_KEY 或隐藏提示读取密钥")
    run.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)
    path = args.db.resolve()
    if args.command == "init":
        print(initialize(path, args.upstream, args.alias, args.launcher))
    elif args.command in ("device-upstream", "device-owner"):
        try:
            raw = sys.stdin.buffer.read(MAX_BODY + 1)
            if len(raw) > MAX_BODY:
                raise ApiError(413, "本机配置请求过大。")
            body = json.loads(raw.decode("utf-8"))
            if not isinstance(body, dict):
                raise ValueError
        except (ValueError, RecursionError) as exc:
            raise ApiError(400, "本机配置需要 UTF-8 JSON 对象。") from exc
        migrate_database(path)
        result = (device_routes.provision_upstream(path, body) if args.command == "device-upstream"
                  else device_api_module().provision_owner(path, body))
        print(json.dumps(result, ensure_ascii=True))
    elif args.command == "serve":
        key = os.environ.get("ASTRBOT_CHAT_KEY") or getpass.getpass("AstrBot 聊天密钥：")
        with open_server(path, key, args.port) as server:
            print(f"Chatbox 共享服务已启动 http://127.0.0.1:{server.server_port}", flush=True)
            server.serve_forever()
    else:
        with database(path, write=args.command not in ("rooms", "devices", "unresolved")) as db:
            if args.command in ("profiles", "profile-recover"):
                migrate_profiles(db)
                has_catalog = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='device_catalog'").fetchone()
                legacy_profile = ("NOT EXISTS(SELECT 1 FROM device_catalog c WHERE c.profile=profiles.id "
                                  "AND c.source_id<>profiles.source_id)") if has_catalog else "1"
            if args.command in ("rooms", "recover-owner"):
                columns = {row[1] for row in db.execute("PRAGMA table_info(rooms)")}
                legacy_room = "legacy=1 AND upstream=(SELECT instance FROM config)" if "upstream" in columns else "1"
            if args.command in ("unresolved", "resolve"):
                has_devices = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='device_operations'").fetchone()
                media = MEDIA_RESERVATION if has_devices else "0"
                reviewable = "(needs_review=1 OR (status='running' AND " + media + "))"
            if args.command == "bootstrap":
                print(new_code(db, None, "create")["code"])
            elif args.command == "profile-bootstrap":
                migrate_profiles(db)
                print(profile_code(db, None)["code"])
            elif args.command == "profiles":
                for row in db.execute("SELECT id,name,base,username,source_id FROM profiles WHERE " + legacy_profile +
                                      " ORDER BY rowid"):
                    print(json.dumps(dict(row), ensure_ascii=True))
            elif args.command == "profile-recover":
                if not db.execute("SELECT 1 FROM profiles WHERE id=? AND " + legacy_profile, (args.profile,)).fetchone():
                    raise ApiError(404, "找不到指定的档案。")
                code = secrets.token_urlsafe(12)
                expires = int(time.time()) + CODE_LIFETIME
                db.execute("INSERT OR REPLACE INTO profile_recovery(profile,digest,expires) VALUES(?,?,?)",
                           (args.profile, digest(code), expires))
                print(code)
            elif args.command == "rooms":
                for row in db.execute("SELECT id,username,session FROM rooms WHERE " + legacy_room +
                                      " ORDER BY rowid"):
                    print(json.dumps(dict(row), ensure_ascii=True))
            elif args.command == "recover-owner":
                if not db.execute("SELECT 1 FROM rooms WHERE id=? AND " + legacy_room,
                                  (args.room,)).fetchone():
                    raise ApiError(404, "找不到指定的房间。")
                print(new_code(db, args.room, "recover")["code"])
            elif args.command == "revoke-code":
                db.execute("DELETE FROM codes WHERE room IS ?", (args.room,))
            elif args.command == "devices":
                rows = db.execute("SELECT id,room,owner FROM devices WHERE (? IS NULL OR room=?) ORDER BY room,id",
                                  (args.room, args.room))
                for row in rows:
                    print(row["id"], row["room"], "owner" if row["owner"] else "guest")
            elif args.command == "revoke-device":
                if not db.execute("DELETE FROM devices WHERE id=?", (args.device,)).rowcount:
                    raise ApiError(404, "设备不存在或已撤销。")
            elif args.command == "unresolved":
                rows = db.execute("SELECT room,id,status," + media + " AS media FROM turns WHERE " + reviewable +
                                  " AND (? IS NULL OR room=?) ORDER BY room,seq", (args.room, args.room))
                for row in rows:
                    if row["media"]:
                        print(row["room"], row["id"], "media-" + row["status"], MEDIA_RESOLVE_NOTICE)
                    else:
                        print(row["room"], row["id"])
            elif args.command == "resolve":
                key = (args.room, request_id(args.id))
                row = db.execute("SELECT status FROM turns WHERE room=? AND id=? AND " + reviewable, key).fetchone()
                if not row:
                    raise ApiError(404, "找不到待确认的轮次。")
                db.execute("UPDATE turns SET status='unknown',needs_review=0 WHERE room=? AND id=?", key)
                if row["status"] == "running":
                    profile_activity(db, args.room)


if __name__ == "__main__":
    try:
        cli()
    except KeyboardInterrupt:
        pass
    except FileExistsError:
        sys.exit("数据库文件已存在，请使用现有实例或指定新路径。")
    except (OSError, sqlite3.Error, ApiError, EOFError) as error:
        sys.exit(str(error))
