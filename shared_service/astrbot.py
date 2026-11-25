"""The text and media-hint subset of the Chatbox AstrBot OpenAPI transport."""

from contextlib import contextmanager
import http.client
import json
import time
from urllib.parse import urlencode, urlsplit


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


def read_reply(response, publish):
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


def unavailable(reason="此 AstrBot 服务未提供可用的上下文预览。"):
    return dict(supported=False, canSend=False, revision="", reason=reason, selected=[])


def preview_context(base, key, username, session, question, context, verify_base=None):
    body = json.dumps(dict(username=username, session_id=session, message=question, context=context),
                      ensure_ascii=False).encode("utf-8")
    try:
        with connected(base, 10, verify_base) as (connection, prefix):
            connection.request("POST", prefix + "/api/v1/chat/context/preview", body, {
                "Authorization": "Bearer " + key, "Accept": "application/json",
                "Content-Type": "application/json; charset=utf-8",
            })
            with connection.getresponse() as response:
                if response.status in (401, 403):
                    return unavailable("服务拒绝上下文预览，请检查聊天权限。")
                if response.status != 200 or response.headers.get_content_type() != "application/json":
                    return unavailable()
                raw = response.read(MAX_EVENT_BYTES + 1)
        if len(raw) > MAX_EVENT_BYTES:
            return unavailable("上下文预览超过设备服务的大小限制。")
        reply = json.loads(raw.decode("utf-8"))
        if reply["status"] != "ok":
            return unavailable()
        data = reply["data"]
        if not isinstance(data["reason"], str):
            return unavailable()
        if data["supported"] is False:
            return unavailable(data["reason"] or "此服务不支持自定义上下文。")
        if (data["supported"] is not True or type(data["canSend"]) is not bool
                or not isinstance(data["revision"], str) or (data["canSend"] and not data["revision"].strip())
                or not isinstance(data["selected"], list)):
            return unavailable()
        hints = {item["seq"]: item for field in ("pins", "hints") for item in context.get(field, [])}
        selected = []
        for item in data["selected"]:
            if item["role"] not in ("assistant", "user") or not isinstance(item.get("text", ""), str):
                return unavailable()
            result_item = dict(role=item["role"], text="")
            seq = item.get("seq")
            known = hints.get(seq) if type(seq) is int else None
            if known and item.get("text") and known["question" if item["role"] == "user" else "answer"] == item["text"]:
                result_item.update(seq=seq, text=item["text"])
            if isinstance(item.get("checkpoint"), str) and item["checkpoint"]:
                result_item["checkpoint"] = item["checkpoint"]
            selected.append(result_item)
        result = dict(supported=True, canSend=data["canSend"], revision=data["revision"],
                      reason=data["reason"], selected=selected)
        for key_name in ("estimatedTokens", "maxTokens"):
            if type(data.get(key_name)) is int and data[key_name] >= 0:
                result[key_name] = data[key_name]
        if type(data.get("nearLimit")) is bool:
            result["nearLimit"] = data["nearLimit"]
        fitting = data.get("fittingVisibleMessagesEstimate")
        if isinstance(fitting, list) and fitting == data["selected"][:len(fitting)]:
            result["fittingVisibleMessagesEstimate"] = selected[:len(fitting)]
        return result
    except (OSError, http.client.HTTPException, NotDispatched, ValueError, TypeError, KeyError, RecursionError):
        return unavailable()


def send(base, key, username, session, question, publish, verify_base=None, *,
         context=None, revision=None, turn_id=None, accepted=None):
    payload = dict(username=username, session_id=session, message=question, enable_streaming=True)
    path = "/api/v1/chat"
    if context is not None:
        if not revision or not turn_id:
            raise NotDispatched("缺少已确认的上下文预览。")
        path += "/context/send"
        payload.update(context=context, revision=revision, turn_id=turn_id)
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    with connected(base, 90, verify_base) as (connection, prefix):
        connection.request("POST", prefix + path, body, {
            "Authorization": "Bearer " + key,
            "Accept": "text/event-stream",
            "Content-Type": "application/json; charset=utf-8",
        })
        with connection.getresponse() as response:
            if response.status in (401, 403):
                raise NotDispatched("AstrBot 拒绝聊天授权。")
            if context is not None and response.status == 409:
                # The adapter also uses 409 for a previously accepted ID without a replayable result.
                # Only an explicit pre-dispatch refusal can release a draft for a new attempt.
                if response.getheader("X-Chatbox-Dispatch") == "not-dispatched":
                    raise NotDispatched("上下文请求未获接受，请重新预览。")
                raw = response.read(8193)
                if len(raw) <= 8192 and response.headers.get_content_type() == "application/json":
                    detail = json.loads(raw.decode("utf-8"))
                    if isinstance(detail, dict) and detail.get("message") == "预览已过期，请重新预览。":
                        raise NotDispatched("上下文请求未获接受，请重新预览。")
            if context is not None and response.status in (400, 404, 413, 422):
                raise NotDispatched("上下文请求未获接受，请重新预览。")
            if not 200 <= response.status < 300 or response.headers.get_content_type() != "text/event-stream":
                raise ValueError("上游对话响应无效")
            if accepted is not None:
                accepted()
            read_reply(response, publish)
