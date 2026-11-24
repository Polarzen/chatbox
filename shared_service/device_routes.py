"""Locally approved AstrBot routes and the canonical rooms they own."""

import ctypes
from functools import partial
import http.client
import os
from pathlib import Path
import sqlite3
import time
import uuid

if __package__:
    from . import service as core
else:
    import service as core


def migrate_routes(db):
    """Rebuild the old globally unique SID table inside the caller's migration transaction."""
    if "upstream" in {row[1] for row in db.execute("PRAGMA table_info(rooms)")}:
        return
    db.execute("""CREATE TABLE route_upstreams(
               id TEXT PRIMARY KEY, base TEXT NOT NULL, launcher_id TEXT UNIQUE, secret BLOB)""")
    db.execute("CREATE UNIQUE INDEX route_manual_base ON route_upstreams(base) WHERE launcher_id IS NULL")
    db.execute("""CREATE TABLE route_sources(
               source_id TEXT PRIMARY KEY, upstream TEXT NOT NULL REFERENCES route_upstreams)""")
    db.execute("""CREATE TABLE route_aliases(
               upstream TEXT NOT NULL REFERENCES route_upstreams, base TEXT NOT NULL, permanent INTEGER NOT NULL,
               PRIMARY KEY(upstream,base))""")
    config = dict(db.execute("SELECT * FROM config").fetchone())
    db.execute("INSERT INTO route_upstreams VALUES(?,?,?,NULL)",
               (config["instance"], config["upstream"], config.get("launcher_id")))
    db.execute("INSERT INTO route_aliases SELECT ?,base,1 FROM sources", (config["instance"],))
    if config.get("launcher_id"):
        db.execute("INSERT OR IGNORE INTO route_aliases VALUES(?,?,1)", (config["instance"], config["upstream"]))
    db.execute("""CREATE TABLE rooms_new(
               id TEXT PRIMARY KEY, username TEXT NOT NULL, session TEXT NOT NULL,
               upstream TEXT NOT NULL REFERENCES route_upstreams, legacy INTEGER NOT NULL CHECK(legacy IN (0,1)),
               UNIQUE(upstream,username,session))""")
    db.execute("INSERT INTO rooms_new SELECT id,username,session,?,1 FROM rooms", (config["instance"],))
    db.execute("DROP TABLE rooms")
    db.execute("ALTER TABLE rooms_new RENAME TO rooms")
    db.execute("CREATE INDEX route_sid ON rooms(upstream,session)")
    if db.execute("PRAGMA foreign_key_check").fetchone():
        raise sqlite3.IntegrityError("共享服务数据库含有无效的外键，迁移已取消。")


def credential(path, upstream, value, *, decrypt=False):
    if os.name != "nt":
        # SQLite creates journals, WAL and shared memory with the database's permissions.
        for item in (Path(path), *(Path(str(path) + ext) for ext in ("-wal", "-shm", "-journal"))):
            try:
                stat = item.stat()
            except FileNotFoundError:
                continue
            if stat.st_uid != os.geteuid() or stat.st_mode & 0o077:
                raise core.ApiError(403, "当前用户需要独占此数据库及其日志文件以保存上游凭据。")
        return value

    class Blob(ctypes.Structure):
        _fields_ = [("size", ctypes.c_uint32), ("data", ctypes.c_void_p)]

    def blob(data):
        return Blob(len(data), ctypes.cast(ctypes.create_string_buffer(data), ctypes.c_void_p))

    out = Blob()
    fn = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    if not fn(ctypes.byref(blob(value)), None, ctypes.byref(blob(upstream.encode("ascii"))),
              None, None, 1, ctypes.byref(out)):
        raise core.ApiError(503, "当前 Windows 用户无法访问此上游的受保护凭据。")
    try:
        return ctypes.string_at(out.data, out.size)
    finally:
        ctypes.windll.kernel32.LocalFree(ctypes.c_void_p(out.data))


def provision_upstream(path, body):
    required = {"sourceId", "base", "key"}
    core.fields(body, required | (body.keys() & {"launcherId", "aliases", "addAlias"}))
    source = core.string(body, "sourceId", max_bytes=4096)
    if source != source.strip():
        raise core.ApiError(400, "来源标识含首尾空白。")
    base = core.normalized_base(core.string(body, "base", max_bytes=4096))
    key = core.string(body, "key", max_bytes=8192)
    core.validate_chat_key(key)
    aliases = body.get("aliases", [])
    if not isinstance(aliases, list) or not all(isinstance(a, str) and len(a) <= 4096 for a in aliases):
        raise core.ApiError(400, "上游别名格式无效。")
    aliases = {core.normalized_base(a) for a in aliases}
    if "addAlias" in body:
        if "aliases" in body:
            raise core.ApiError(400, "不能同时添加单个地址和替换全部上游别名。")
        alias = core.normalized_base(core.string(body, "addAlias", max_bytes=4096))
        if not alias.startswith("https://"):
            raise core.ApiError(400, "手机来源地址须使用 HTTPS。")
        aliases.add(alias)
    launcher = body.get("launcherId")
    if launcher is not None:
        launcher = core.request_id(launcher)
        try:
            actual = core.launcher_module().identity_for_url(base)
        except (ValueError, OSError) as exc:
            raise core.ApiError(409, "无法核实此上游的 Launcher 实例。") from exc
        if actual != launcher:
            raise core.ApiError(409, "上游地址与指定的 Launcher 实例不匹配。")
    if source.startswith("launcher:") and (not launcher or source != "launcher:" + launcher):
        raise core.ApiError(409, "来源编号与指定的 Launcher 实例不匹配。")
    with core.database(path, write=True) as db:
        row = db.execute("SELECT id FROM route_upstreams WHERE launcher_id=? OR (launcher_id IS NULL AND ? IS NULL "
                         "AND base=?)", (launcher, launcher, base)).fetchone()
        upstream = row[0] if row else str(uuid.uuid4())
        binding = db.execute("SELECT upstream FROM route_sources WHERE source_id=?", (source,)).fetchone()
        if binding and binding[0] != upstream:
            raise core.ApiError(409, "此来源已登记到另一上游。")
        if "addAlias" in body and not binding:
            raise core.ApiError(409, "请先登记所选的本机上游，再关联手机来源。")
        # A newly registered manual endpoint also cannot take another route's alias.
        # Launcher ports can legitimately move between separately verified instance UUIDs.
        addresses = aliases if launcher else aliases | {base}
        for address in addresses:
            conflict = db.execute("""SELECT 1 FROM route_upstreams u WHERE u.id<>? AND
                                  (u.base=? OR EXISTS (SELECT 1 FROM route_aliases a
                                   WHERE a.upstream=u.id AND a.base=?))""", (upstream, address, address)).fetchone()
            if conflict:
                raise core.ApiError(409, "此地址已登记到另一上游。")
        secret = credential(path, upstream, key.encode("ascii"))
        db.execute("""INSERT INTO route_upstreams(id,base,launcher_id,secret) VALUES(?,?,?,?)
                      ON CONFLICT(id) DO UPDATE SET base=excluded.base,secret=excluded.secret""",
                   (upstream, base, launcher, secret))
        db.execute("INSERT OR IGNORE INTO route_sources VALUES(?,?)", (source, upstream))
        if "aliases" in body:
            db.execute("DELETE FROM route_aliases WHERE upstream=? AND permanent=0", (upstream,))
        if launcher:
            # A previously verified port may change owners. Only an explicitly bound
            # source ID may still use this loopback alias, and dispatch verifies its UUID.
            db.execute("INSERT OR REPLACE INTO route_aliases VALUES(?,?,1)", (upstream, base))
        db.executemany("INSERT OR IGNORE INTO route_aliases VALUES(?,?,0)", [(upstream, alias) for alias in aliases])
    return {"upstream": upstream}


class DeviceRoutes:
    def __init__(self, service):
        self.service = service

    def resolve(self, db, source_id, base, upstream=None):
        """Return one locally approved route for this source, or None for read-only."""
        base = core.normalized_base(base)
        bound = db.execute("SELECT upstream FROM route_sources WHERE source_id=?", (source_id,)).fetchone()
        # Windows profiles have their own local registration. A phone identifies
        # its native source by normalized address and can use an unambiguous approved alias.
        if not bound and source_id != "android:" + base:
            return None
        rows = db.execute("""SELECT u.id,u.launcher_id FROM route_upstreams u
                          WHERE u.secret IS NOT NULL AND (u.base=? OR EXISTS
                            (SELECT 1 FROM route_aliases a WHERE a.upstream=u.id AND a.base=?))""", (base, base))
        candidates = [row["id"] for row in rows
                      if (not bound or row["id"] == bound[0])
                      and (not source_id.startswith("launcher:") or source_id == "launcher:" + (row["launcher_id"] or ""))
                      and (bound or not row["launcher_id"] or not core.launcher_endpoint(base))]
        if len(candidates) != 1 or (upstream is not None and candidates[0] != upstream):
            return None
        return candidates[0]

    @staticmethod
    def find_room(db, upstream, username, sid):
        row = db.execute("SELECT id FROM rooms WHERE upstream=? AND username=? AND session=?",
                         (upstream, username, sid)).fetchone()
        return row[0] if row else None

    def ensure_room(self, db, upstream, username, sid, *, legacy=False):
        """Callers must establish authority before attaching any existing room."""
        room = self.find_room(db, upstream, username, sid)
        if room is None:
            room = str(uuid.uuid4())
            db.execute("INSERT INTO rooms(id,username,session,upstream,legacy) VALUES(?,?,?,?,?)",
                       (room, username, sid, upstream, int(legacy)))
        return room

    @staticmethod
    def prove_room(db, room, token):
        return db.execute("SELECT 1 FROM devices WHERE owner=1 AND digest=? AND room=?",
                          (core.digest(token), room)).fetchone() is not None

    def prove_profile(self, db, upstream, username, source_id, base, token):
        if upstream != self.service.default_upstream:
            return None
        row = db.execute("""SELECT p.id,p.base,p.launcher_id FROM profiles p JOIN profile_devices d ON d.profile=p.id
                          WHERE d.owner=1 AND d.digest=? AND p.username=? AND p.source_id=?""",
                         (core.digest(token), username, source_id)).fetchone()
        base = core.normalized_base(base)
        if row and (row["base"] == base or
                    (core.launcher_endpoint(base) and self.service.proves_launcher_profile(row, source_id))):
            return row["id"]
        return None

    def target(self, upstream, *, legacy=False):
        """Snapshot the credential and immutable identity. Verify Launcher again on each connected socket."""
        if legacy:
            if upstream != self.service.default_upstream:
                raise core.astrbot.NotDispatched("原房间凭据只适用于默认上游。")
            verify = self.service.current_base if self.service.launcher_id else None
            return self.service.current_base(), self.service.key, verify
        with core.database(self.service.path) as db:
            row = db.execute("SELECT * FROM route_upstreams WHERE id=?", (upstream,)).fetchone()
        if not row:
            raise core.astrbot.NotDispatched("无法找到已登记的上游。")
        if row["secret"] is None:
            raise core.astrbot.NotDispatched("此上游尚未配置凭据。")
        try:
            key = credential(self.service.path, upstream, row["secret"], decrypt=True).decode("ascii")
        except (core.ApiError, OSError, UnicodeError) as exc:
            raise core.astrbot.NotDispatched("无法读取此上游的本机凭据。") from exc
        if row["launcher_id"] is None:
            return row["base"], key, None
        verify = partial(core.verified_launcher_base, row["launcher_id"])
        return verify(), key, verify

    def check_origin(self, upstream, username, sid, *, legacy=False):
        try:
            # Leave time to persist the read-only reason within the client's 12 second timeout.
            deadline = time.monotonic() + 8
            base, key, verify = self.target(upstream, legacy=legacy)
            return core.astrbot.session_exists(base, key, username, sid, verify, deadline=deadline), ""
        except (core.astrbot.NotDispatched, core.astrbot.SessionIndexError) as exc:
            return False, str(exc)
        except (OSError, http.client.HTTPException):
            return False, "无法核实 AstrBot 原会话，请检查上游连接。"
