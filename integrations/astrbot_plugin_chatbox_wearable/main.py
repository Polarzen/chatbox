"""Historical wearable reads in Chatbox conversations and AstrBot basic cron jobs."""

import json
from datetime import datetime

import httpx
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star
from astrbot.core.platform.message_type import MessageType
from astrbot.core.platform.sources.webchat.webchat_event import WebChatMessageEvent


def service_origin(value):
    try:
        url = httpx.URL(value)
    except (TypeError, httpx.InvalidURL):
        return None
    if (not url.host or url.userinfo or url.query or url.fragment or url.path != "/"
            or (url.scheme != "https" and not (
                url.scheme == "http" and url.host in ("127.0.0.1", "::1")
            ))):
        return None
    return str(url).rstrip("/")


def chat_context(event):
    if (not isinstance(event, WebChatMessageEvent) or event.get_platform_id() != "webchat"
            or event.message_obj.type != MessageType.FRIEND_MESSAGE or event.get_group_id()):
        return None
    sender = event.get_sender_id()
    session = event.message_obj.session_id
    if not isinstance(session, str) or not sender:
        return None
    parts = session.split("!", 2)
    origin = "webchat:FriendMessage:" + session
    message_id = event.message_obj.message_id
    if (len(parts) != 3 or parts[:2] != ["webchat", sender] or not parts[2]
            or event.unified_msg_origin != origin or not isinstance(message_id, str) or not message_id):
        return None
    return {"origin": origin, "sender": sender, "messageId": message_id}


DEFAULT_PERIODS = {"steps": "today", "sleep": "last_night", "exercise": "latest", "heart_rate": "latest"}
INVALID_QUERY = json.dumps({
    "status": "invalid_query",
    "hint": '查最近运动只需重试 {"metric":"exercise"}。如用户指定了时段，请保留其范围，将 '
            'period 写为“带时区的起点/带时区的终点”，最多 30 天。',
}, ensure_ascii=False)


def query_fields(metric, period, granularity):
    if (not isinstance(metric, str) or metric not in DEFAULT_PERIODS or not isinstance(period, str)
            or granularity not in ("summary", "detail")):
        raise ValueError("invalid query")
    query = {"metric": metric, "granularity": granularity}
    if not period:
        return query
    if period == DEFAULT_PERIODS[metric]:
        query["relative"] = period
        return query
    start, end = period.split("/")
    for field, value in (("startMs", start), ("endMs", end)):
        parsed = datetime.fromisoformat(value)
        if parsed.utcoffset() is None:
            raise ValueError("time zone required")
        query[field] = int(parsed.timestamp() * 1000)
    if not 0 < query["endMs"] - query["startMs"] <= 30 * 86_400_000:
        raise ValueError("invalid interval")
    return query


class ChatboxWearable(Star):
    def __init__(self, context: Context, config: dict | None = None):
        super().__init__(context)
        config = config or {}
        self.origin = service_origin(config.get("service_url", ""))
        self.token = config.get("plugin_token", "")
        self.client = None
        self.job_id = None

    async def initialize(self):
        if not self.token or not self.origin:
            return
        self.client = httpx.AsyncClient(
            base_url=self.origin, headers={"Authorization": "Bearer " + self.token},
            follow_redirects=False, trust_env=False, timeout=15,
        )
        try:
            job = await self.context.cron_manager.add_basic_job(
                name="Chatbox 手环任务", cron_expression="* * * * *", timezone="UTC",
                handler=self.poll_jobs, persistent=False,
            )
            self.job_id = job.job_id
        except BaseException:
            await self.client.aclose()
            self.client = None
            raise

    async def terminate(self):
        try:
            if self.job_id:
                await self.context.cron_manager.delete_job(self.job_id)
                self.job_id = None
        finally:
            if self.client:
                await self.client.aclose()
                self.client = None

    async def post(self, operation, body):
        response = await self.client.post("/v1/device/wearable-" + operation, json=body)
        response.raise_for_status()
        data = response.json()
        if not isinstance(data, dict):
            raise ValueError("invalid Chatbox service response")
        return data

    @filter.llm_tool(name="query_wearable_data")
    async def query_wearable_data(
        self, event: AstrMessageEvent, metric: str, period: str = "", granularity: str = "summary",
    ) -> str:
        """查询当前个人会话获准读取的手环历史数据。讨论训练、疲劳、睡眠等相关话题时可自主查询。

        查最近运动只传 {"metric":"exercise"}。普通闲聊无需查询。仅据返回的数值、来源、单位、样本数及时间回答。
        exercise 只查运动记录。讨论心率或评估强度时应按权限另查同一时段的 heart_rate。未查的心率未知，仅凭时长和距离不能判定强度。

        Args:
            metric (string): steps 步数、sleep 睡眠、exercise 最近运动、heart_rate 运动期间心率。每次一项。
            period (string): 可省略。steps 默认 today，sleep 默认 last_night，运动和心率默认 latest。仅在用户指定时段时传一个“ISO起点/ISO终点”字符串，两端含 UTC 偏移，最多 30 天。
            granularity (string): 默认 summary。用户明确要求时间序列或分段时用 detail。
        """
        context = chat_context(event)
        if not self.client or not context:
            return '{"status":"no_permission"}'
        try:
            fields = query_fields(metric, period, granularity)
        except (ValueError, OSError, OverflowError):
            return INVALID_QUERY
        try:
            await event.send_typing()
            data = await self.post("query", {**context, **fields})
        except httpx.HTTPStatusError as exc:
            code = exc.response.status_code
            if code in (401, 403, 404):
                return '{"status":"no_permission"}'
            if code == 400:
                return INVALID_QUERY
            return '{"status":"service_unavailable"}'
        except (httpx.RequestError, ValueError, RuntimeError):
            return '{"status":"service_unavailable"}'
        if metric == "exercise":
            data["interpretationHint"] = (
                "本次仅查了运动记录。心率未经查询时为未知。需要讨论心率或评估强度时，"
                "请按权限另查同一时段的 heart_rate。仅凭运动时长和距离不能判定强度。"
            )
        return json.dumps(data, ensure_ascii=False, separators=(",", ":"))

    async def poll_jobs(self):
        """Advance only work issued to this credential. Chatbox owns its durable state."""
        try:
            page = await self.post("jobs", {})
            jobs = page["jobs"]
            if not isinstance(jobs, list):
                raise ValueError("invalid jobs")
        except (httpx.HTTPError, ValueError, KeyError):
            self.logger.warning("Chatbox 手环任务列表暂不可用。")
            return
        for job in jobs:
            try:
                identifier = {"id": job["id"]}
                await self.post("job-query", identifier)
                await self.post("job-finish", identifier)
            except (httpx.HTTPError, ValueError, KeyError, TypeError):
                self.logger.warning("Chatbox 将在下次轮询核对手环任务状态。")
