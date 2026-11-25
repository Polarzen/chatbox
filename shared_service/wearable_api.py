"""Health reads are scoped by phone consent and the originating WebChat run."""

import time

if __package__:
    from . import service as core, wearable_jobs, wearable_store
else:
    import service as core
    import wearable_jobs
    import wearable_store


PHONE = {"wearable-config", "wearable-sync", "wearable-clear", "wearable-credential"}
JOBS = {"wearable-jobs", "wearable-job-query", "wearable-job-finish"}
ACTIONS = PHONE | JOBS | {"wearable-query"}


def _catalog(db, api, device, profile):
    row = db.execute("""SELECT c.* FROM device_catalog c JOIN device_members m ON m.id=c.origin
                        WHERE c.profile=? AND c.origin=? AND m.owner=0 AND m.revoked=0
                          AND m.username=c.username""", (profile, device)).fetchone()
    if not row or not api.route(db, row):
        raise core.ApiError(403, "手环共享需要来源手机自己的已登记个人档案。")
    return row


def _chat_query(api, token, body):
    origin = core.string(body, "origin", max_bytes=16384)
    sender = core.string(body, "sender", max_bytes=4096)
    run_id = core.string(body, "messageId", max_bytes=128)
    prefix = "webchat:FriendMessage:webchat!" + sender + "!"
    if "!" in sender or not origin.startswith(prefix) or not origin[len(prefix):]:
        raise core.ApiError(403, "只允许当前配对个人会话读取。")
    sid = origin[len(prefix):]
    query = {k: v for k, v in body.items() if k not in ("origin", "sender", "messageId")}
    # The plugin's send_typing signal travels through AstrBot's SSE connection;
    # its HTTPS request may arrive first. Release the DB lock between checks.
    deadline = time.monotonic() + 2
    while True:
        with core.database(api.path, write=True) as db:
            grant = wearable_store.plugin_grant(db, token)
            catalog = _catalog(db, api, grant["device"], grant["profile"])
            room = wearable_jobs.private_room(db, grant["device"], grant["profile"], sid)
            if not room or sender != catalog["username"]:
                raise core.ApiError(403, "此会话没有手环读取权限。")
            op = db.execute("""SELECT op.upstream_run_id FROM device_operations op
                JOIN turns t ON t.room=op.room AND t.id=op.id AND t.status='running'
                JOIN device_members m ON m.id=op.device AND m.revoked=0
                WHERE op.profile=? AND op.sid=? AND op.room=? AND op.action IN ('start','send')
                  AND (op.device=? OR m.owner=1)""", (grant["profile"], sid, room, grant["device"])).fetchone()
            if op and op[0] == run_id:
                return wearable_store.read_metric(db, grant, query, time.time_ns() // 1_000_000)
            if not op or op[0] is not None or time.monotonic() >= deadline:
                raise core.ApiError(403, "无法核实此工具调用来自当前配对对话。")
        time.sleep(0.05)


def _delivery_target(db, grant, catalog, body):
    core.fields(body, ("id",))
    job = db.execute("""SELECT kind FROM wearable_jobs WHERE id=? AND device=? AND profile=?
                        AND state='pending' AND evidence IS NOT NULL""",
                     (core.request_id(body["id"]), grant["device"], grant["profile"])).fetchone()
    if not job or not grant["notifyMorning" if job["kind"] == "morning" else "notifyWorkout"]:
        return None
    if wearable_jobs.private_room(db, grant["device"], grant["profile"], grant["sid"]):
        return catalog["upstream"], catalog["username"], grant["sid"]


def handle(api, action, token, body):
    if action == "wearable-query":
        return 200, _chat_query(api, token, body)
    if action == "wearable-job-finish":
        # The local directory can lag an upstream session deletion. Check its
        # creator on the registered AstrBot and recheck the binding after I/O.
        with core.database(api.path, write=True) as db:
            grant = wearable_store.plugin_grant(db, token)
            catalog = _catalog(db, api, grant["device"], grant["profile"])
            target = _delivery_target(db, grant, catalog, body)
        exists, failure = api.routes.check_origin(*target) if target else (False, "")
    with core.database(api.path, write=True) as db:
        if action in PHONE:
            return 200, wearable_store.phone_action(db, action, token, body)
        if action not in JOBS:
            raise core.ApiError(404, "未知手环接口。")
        grant = wearable_store.plugin_grant(db, token)
        catalog = _catalog(db, api, grant["device"], grant["profile"])
        if action == "wearable-job-finish":
            current = _delivery_target(db, grant, catalog, body)
            if current and (current != target or failure):
                return 200, {"status": "waiting"}
            if current and not exists:
                return 200, {"status": "destination_unavailable"}

        def read(query, as_of_ms):
            return wearable_store.read_metric(db, grant, query, as_of_ms)

        result = wearable_jobs.run_job_action(db, action, body, grant["device"], grant, read,
                                              time.time_ns() // 1_000_000)
        return 200, result
