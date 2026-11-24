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

if __package__:
    from . import astrbot
else:
    import astrbot


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
def database(path, write=False):
    uri = Path(path).resolve().as_uri() + "?mode=rw"
    with closing(sqlite3.connect(uri, uri=True, isolation_level=None, timeout=10)) as db:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
        with db:
            yield db


def launcher_module():
    if __package__:
        from . import launcher
    else:
        import launcher
    return launcher


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
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript("BEGIN IMMEDIATE;\n" + SCHEMA)
        db.execute("INSERT INTO config VALUES(?,?,?)", (instance, upstream, launcher_id))
        db.executemany("INSERT INTO sources VALUES(?)", [(base,) for base in sources])
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


def check_unclaimed(db, session):
    if db.execute("SELECT 1 FROM rooms WHERE session=?", (session,)).fetchone():
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


READ_ROUTES = {"/v1/turn", "/v1/turns"}
OWNER_ROUTES = {"/v1/invite", "/v1/cancel-invite", "/v1/revoke-guests"}
WRITE_ROUTES = {"/v1/create", "/v1/join", "/v1/send"} | OWNER_ROUTES


class Service:
    def __init__(self, path, key, workers):
        self.path = path
        self.key = key
        self.workers = workers
        with database(path, write=True) as db:
            config = dict(db.execute("SELECT * FROM config").fetchone())
            self.upstream = config["upstream"]
            self.launcher_id = config.get("launcher_id")
            self.aliases = {row[0] for row in db.execute("SELECT base FROM sources")}
            db.execute("UPDATE turns SET status='unknown',needs_review=1 WHERE status='running'")

    def current_base(self):
        if self.launcher_id is None:
            return self.upstream
        try:
            return normalized_base(launcher_module().url_for_identity(self.launcher_id))
        except Exception as exc:
            raise astrbot.NotDispatched("无法核实已绑定的 Launcher 实例，请确认该实例正在运行。") from exc

    def create(self, body):
        fields(body, ("code", "sourceBase", "username", "session", "history"))
        code = string(body, "code", max_bytes=128)
        with database(self.path) as db:
            check_code(db, code, create=True)
            source, username, session, history = seed(body)
            check_unclaimed(db, session)
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
            check_unclaimed(db, session)
            room = str(uuid.uuid4())
            db.execute("INSERT INTO rooms VALUES(?,?,?)", (room, username, session))
            for seq, turn in enumerate(history, 1):
                unresolved = seq == len(history) and turn["status"] == "unknown"
                db.execute("INSERT INTO turns(room,seq,id,question,answer,status,needs_review) VALUES(?,?,?,?,?,?,?)",
                           (room, seq, str(uuid.uuid4()), turn["question"], turn["answer"], turn["status"], unresolved))
            return grant(db, room, True)

    def read(self, route, token, params):
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

    def write(self, route, token, body):
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
            existing = find_turn(db, room, turn_id)
            if existing:
                if existing["question"] != question:
                    raise ApiError(409, "此请求编号已用于其他内容。")
                return (202 if existing["status"] == "running" else 200), existing
            busy = db.execute("SELECT status FROM turns WHERE room=? AND (status='running' OR needs_review=1) LIMIT 1",
                              (room,)).fetchone()
            if busy:
                if busy["status"] == "running":
                    raise ApiError(409, "会话已有回复正在生成，请稍后再发。")
                raise ApiError(409, "上游执行结果未知，请服务主人确认 AstrBot 已空闲并运行 resolve。")
            seq = db.execute("SELECT COALESCE(MAX(seq),0)+1 FROM turns WHERE room=?", (room,)).fetchone()[0]
            db.execute("INSERT INTO turns(room,seq,id,question,answer,status) VALUES(?,?,?,?,?,'running')",
                       (room, seq, turn_id, question, ""))
            identity = db.execute("SELECT username,session FROM rooms WHERE id=?", (room,)).fetchone()
            result = find_turn(db, room, turn_id)
        # Commit before contacting AstrBot. A lost HTTP response never owns this job's lifetime.
        try:
            self.workers.submit(self.generate, room, turn_id, identity["username"], identity["session"], question)
        except RuntimeError:
            self.finish(room, turn_id, "unknown", needs_review=False)
            raise ApiError(503, "服务正在停止，请查询该轮结果。")
        return 202, result

    def finish(self, room, turn_id, status, needs_review):
        with database(self.path, write=True) as db:
            db.execute("UPDATE turns SET status=?,needs_review=? WHERE room=? AND id=? AND status='running'",
                       (status, needs_review, room, turn_id))

    def generate(self, room, turn_id, username, session, question):
        last_answer = ""

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
            astrbot.send(self.current_base(), self.key, username, session, question, publish,
                         self.current_base if self.launcher_id else None)
            status = "complete"
            needs_review = False
        except astrbot.NotDispatched:
            needs_review = False
        except Exception:
            # Transport errors can contain private upstream data. Keep the last confirmed prefix.
            pass
        finally:
            self.finish(room, turn_id, status, needs_review)


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
            route, _, raw_query = self.path.partition("?")
            if route not in READ_ROUTES | WRITE_ROUTES:
                raise ApiError(404, "接口不存在。")
            token = "" if route in ("/v1/create", "/v1/join") else self.bearer()
            if self.command == "GET" and route in READ_ROUTES:
                try:
                    params = parse_qs(raw_query, keep_blank_values=True, strict_parsing=True, max_num_fields=2)
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


@contextmanager
def open_server(path, key, port=8765):
    if not isinstance(key, str) or not key or not key.isascii() or any(ord(c) < 33 or ord(c) > 126 for c in key):
        raise ApiError(400, "需要有效的 AstrBot 聊天密钥。")
    if not Path(path).is_file():
        raise ApiError(400, "请先通过 init 初始化数据库。")
    with serving_lock(path), ThreadPoolExecutor(max_workers=8) as workers:
        with Server(("127.0.0.1", port), Handler) as server:
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
    cmds.add_parser("rooms", help="列出房间编号及其绑定的上游用户和会话标识")
    recover = cmds.add_parser("recover-owner", help="为指定房间换发十分钟有效的一次性主人恢复码，在加入界面兑换")
    recover.add_argument("--room", required=True, help="rooms 列出的房间编号")
    revoke = cmds.add_parser("revoke-code", help="撤销当前创建码，或指定房间的邀请码和主人恢复码")
    revoke.add_argument("--room", help="房间编号。省略则撤销创建码")
    devices = cmds.add_parser("devices", help="列出设备编号、权限和房间")
    devices.add_argument("--room", help="仅列出指定房间的设备")
    remove = cmds.add_parser("revoke-device", help="撤销指定设备凭据")
    remove.add_argument("device", help="devices 列出的设备编号")
    resolve = cmds.add_parser("resolve", help="确认上游已空闲，允许该房间接收新消息。保留原轮次为结果未知")
    resolve.add_argument("--room", required=True, help="房间编号")
    resolve.add_argument("--id", required=True, help="结果未知的轮次编号")
    unresolved = cmds.add_parser("unresolved", help="列出需在 AstrBot 确认已停止的房间与轮次")
    unresolved.add_argument("--room", help="仅列出指定房间")
    run = cmds.add_parser("serve", help="启动回环 HTTP 服务，从 ASTRBOT_CHAT_KEY 或隐藏提示读取密钥")
    run.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)
    path = args.db.resolve()
    if args.command == "init":
        print(initialize(path, args.upstream, args.alias, args.launcher))
    elif args.command == "serve":
        key = os.environ.get("ASTRBOT_CHAT_KEY") or getpass.getpass("AstrBot 聊天密钥：")
        with open_server(path, key, args.port) as server:
            print(f"Chatbox 共享服务已启动 http://127.0.0.1:{server.server_port}", flush=True)
            server.serve_forever()
    else:
        with database(path, write=args.command not in ("rooms", "devices", "unresolved")) as db:
            if args.command == "bootstrap":
                print(new_code(db, None, "create")["code"])
            elif args.command == "rooms":
                for row in db.execute("SELECT id,username,session FROM rooms ORDER BY rowid"):
                    print(json.dumps(dict(row), ensure_ascii=True))
            elif args.command == "recover-owner":
                if not db.execute("SELECT 1 FROM rooms WHERE id=?", (args.room,)).fetchone():
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
                rows = db.execute("SELECT room,id FROM turns WHERE needs_review=1 AND (? IS NULL OR room=?)",
                                  (args.room, args.room))
                for row in rows:
                    print(row["room"], row["id"])
            elif args.command == "resolve":
                changed = db.execute("UPDATE turns SET needs_review=0 WHERE room=? AND id=? AND needs_review=1",
                                     (args.room, request_id(args.id))).rowcount
                if not changed:
                    raise ApiError(404, "找不到待确认的轮次。")


if __name__ == "__main__":
    try:
        cli()
    except KeyboardInterrupt:
        pass
    except FileExistsError:
        sys.exit("数据库文件已存在，请使用现有实例或指定新路径。")
    except (OSError, sqlite3.Error, ApiError, EOFError) as error:
        sys.exit(str(error))
