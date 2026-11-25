"""The text and media-hint subset of the Chatbox AstrBot OpenAPI transport."""

from contextlib import contextmanager
import http.client
import json
import time
from urllib.parse import urlencode, urlsplit
from uuid import UUID


MAX_EVENT_BYTES = 4 * 1024 * 1024
HIDDEN_CHAINS = {"reasoning", "tool_call", "tool_call_result", "agent_stats"}
MARKERS = {"image": "[IMAGE]", "record": "[RECORD]"}
HINTS = {"image": "〔图片〕", "record": "〔语音〕"}


def frames(response):
    data = []
    event_size = 0
    while True:
        line = response.readline(MAX_EVENT_BYTES + 1)
        if not line:
            return
        if len(line) > MAX_EVENT_BYTES:
            raise ValueError("事件过大")
        line = line.rstrip(b"\r\n")
        if not line:
            if data:
                yield json.loads(b"\n".join(data).decode("utf-8"))
            data.clear()
            event_size = 0
        elif line == b"data" or line.startswith(b"data:"):
            event_size += len(line)
            if event_size > MAX_EVENT_BYTES:
                raise ValueError("事件过大")
            data.append(line.partition(b":")[2].removeprefix(b" "))


def read_reply(response, publish, on_run=None):
    committed = ""
    pending = ""
    media = None
    count = 0

    def flush_media():
        nonlocal committed, count, media
        if count:
            committed += HINTS[media] * count
            media, count = None, 0

    try:
        for frame in frames(response):
            kind = frame.get("type")
            value = frame.get("data")
            if kind == "run_started" and on_run is not None:
                run_id = frame.get("message_id")
                if isinstance(value, dict) and value.get("run_id") == run_id and isinstance(run_id, str):
                    try:
                        valid = str(UUID(run_id)) == run_id
                    except ValueError:
                        valid = False
                    if valid:
                        on_run(run_id)
                        on_run = None
                continue
            if kind == "plain" and frame.get("chain_type") not in HIDDEN_CHAINS:
                flush_media()
                value = value if isinstance(value, str) else ""
                if frame.get("streaming") in (True, "true"):
                    pending += value
                else:
                    committed += value
                    pending = ""
            elif kind in ("plain", "image", "record", "attachment_saved", "complete",
                          "file", "video", "actionrow"):
                committed += pending
                pending = ""
                if kind in MARKERS:
                    marked = isinstance(value, str) and value.startswith(MARKERS[kind])
                    if media != kind or kind == "record" or not marked:
                        flush_media()
                    if marked:
                        media, count = kind, count + 1
                elif kind == "attachment_saved":
                    detail = value if isinstance(value, dict) else {}
                    saved = detail.get("type")
                    if saved != media:
                        flush_media()
                    valid_id = isinstance(detail.get("id"), str) and detail["id"].strip()
                    if saved in HINTS and (valid_id or saved == "record"):
                        if count:
                            count -= 1
                            if not count:
                                media = None
                        committed += HINTS[saved]
                    else:
                        flush_media()
                elif kind != "plain":
                    flush_media()
            elif kind == "end":
                flush_media()
                publish(committed + pending)
                return
            elif kind == "error":
                raise ValueError("上游报告失败")
            else:
                continue
            publish(committed + pending)
        raise ValueError("上游未确认结束")
    finally:
        if count:
            flush_media()
            publish(committed + pending)


class NotDispatched(Exception):
    """No chat request was sent, or AstrBot explicitly denied authorization."""


class SessionIndexError(Exception):
    """A safe, user-facing explanation of a failed session lookup."""


@contextmanager
def connected(base, read_timeout, verify_base, deadline=None):
    def remaining(limit):
        if deadline is None:
            return limit
        seconds = min(limit, deadline - time.monotonic())
        if seconds <= 0:
            raise SessionIndexError("核实 AstrBot 原会话超时，请检查上游连接。")
        return seconds

    url = urlsplit(base)
    connection_type = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
    connection = connection_type(url.hostname, url.port, timeout=remaining(12))
    try:
        try:
            connection.connect()
        except OSError as exc:
            raise NotDispatched("无法连接到 AstrBot。") from exc
        if verify_base and verify_base() != base:
            raise NotDispatched("Launcher 实例地址已变化，请重试。")
        connection.sock.settimeout(remaining(read_timeout))
        yield connection, url.path
    finally:
        connection.close()


def session_exists(base, key, username, session, verify_base=None, *, deadline=None):
    """Check only the claimed user's webchat index. Never open session history."""
    page = 1
    total = None
    while True:
        query = urlencode({"username": username, "platform_id": "webchat", "page": page, "page_size": 100})
        if page > 1 and verify_base:
            base = verify_base()
        with connected(base, 30, verify_base, deadline) as (conn, prefix):
            conn.request("GET", prefix + "/api/v1/chat/sessions?" + query,
                         headers={"Authorization": "Bearer " + key, "Accept": "application/json"})
            with conn.getresponse() as response:
                if response.status in (401, 403):
                    raise SessionIndexError("AstrBot 拒绝会话查询，请检查密钥和聊天权限。")
                if not 200 <= response.status < 300:
                    raise SessionIndexError("无法访问 AstrBot 会话列表，请检查上游地址和版本。")
                raw = response.read(MAX_EVENT_BYTES + 1)
        if len(raw) > MAX_EVENT_BYTES:
            raise SessionIndexError("AstrBot 会话列表响应过大。")
        try:
            reply = json.loads(raw.decode("utf-8"))
            if not isinstance(reply, dict) or reply.get("status") != "ok":
                raise SessionIndexError("AstrBot 拒绝会话查询，请检查身份和聊天权限。")
            data = reply["data"]
            rows = data["sessions"]
            count = data["total"]
            if (type(count) is not int or count < 0 or data["page"] != page or data["page_size"] != 100
                    or not isinstance(rows, list) or len(rows) > 100 or not all(isinstance(r, dict) for r in rows)):
                raise ValueError
            if total is not None and total != count:
                raise SessionIndexError("AstrBot 会话列表发生变化，请重试。")
            total = count
            if any(r.get("creator") == username and r.get("platform_id") == "webchat"
                   and r.get("session_id") == session for r in rows):
                return True
            if page * 100 >= total:
                return False
            if not rows:
                raise ValueError
        except (KeyError, TypeError, ValueError, RecursionError) as exc:
            raise SessionIndexError("AstrBot 会话列表响应格式无效。") from exc
        page += 1


def send(base, key, username, session, question, publish, verify_base=None, *, on_run=None):
    body = json.dumps(
        {"username": username, "session_id": session, "message": question, "enable_streaming": True},
        ensure_ascii=False,
    ).encode("utf-8")
    with connected(base, 90, verify_base) as (connection, prefix):
        connection.request("POST", prefix + "/api/v1/chat", body, {
            "Authorization": "Bearer " + key,
            "Accept": "text/event-stream",
            "Content-Type": "application/json; charset=utf-8",
        })
        with connection.getresponse() as response:
            if response.status in (401, 403):
                raise NotDispatched("AstrBot 拒绝聊天授权。")
            if not 200 <= response.status < 300 or response.headers.get_content_type() != "text/event-stream":
                raise ValueError("上游对话响应无效")
            read_reply(response, publish, on_run)
