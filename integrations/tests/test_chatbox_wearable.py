"""Run the installed AstrBot code with only synthetic health data and a controlled provider."""

import asyncio
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from types import SimpleNamespace
from uuid import uuid4


NAME = "astrbot_plugin_chatbox_wearable"
PROMPT = "今天训练完有点累。"


class AstrBotPluginTests(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec("astrbot"), "requires the AstrBot environment")
    def test_isolated_real_runtime(self):
        with tempfile.TemporaryDirectory(prefix="chatbox-wearable-astrbot-") as directory:
            env = dict(os.environ, ASTRBOT_ROOT=directory, PYTHONDONTWRITEBYTECODE="1")
            subprocess.run([sys.executable, str(Path(__file__).resolve()), "--isolated"],
                           cwd=directory, env=env, timeout=90, check=True)


async def exercise_plugin(base, calls, run_id):
    from astrbot.core import astrbot_config, db_helper
    from astrbot.core.agent.hooks import BaseAgentRunHooks
    from astrbot.core.agent.run_context import ContextWrapper
    from astrbot.core.agent.runners.tool_loop_agent_runner import ToolLoopAgentRunner
    from astrbot.core.agent.tool import ToolSet
    from astrbot.core.astr_agent_tool_exec import FunctionToolExecutor
    from astrbot.core.cron.manager import CronJobManager
    from astrbot.core.platform.message_type import MessageType
    from astrbot.core.platform.sources.webchat.webchat_adapter import WebChatAdapter
    from astrbot.core.platform.sources.webchat.webchat_queue_mgr import webchat_queue_mgr
    from astrbot.core.provider.entities import LLMResponse, ProviderRequest
    from astrbot.core.provider.register import llm_tools
    from astrbot.core.star.star import star_map
    from astrbot.core.star.star_manager import PluginManager

    root = Path(os.environ["ASTRBOT_ROOT"])
    assert Path(db_helper.db_path).resolve().is_relative_to(root)
    await db_helper.initialize()
    sys.path.insert(0, str(root))
    source = Path(__file__).resolve().parents[1] / NAME
    shutil.copytree(source, root / "data" / "plugins" / NAME, ignore=shutil.ignore_patterns("__pycache__"))
    config_dir = root / "data" / "config"
    config_dir.mkdir(exist_ok=True)
    (config_dir / (NAME + "_config.json")).write_text(json.dumps({
        "service_url": base, "plugin_token": "test-credential",
    }), encoding="utf-8")

    scheduler = CronJobManager(db_helper)
    context = SimpleNamespace(cron_manager=scheduler)
    await scheduler.start(context)
    manager = PluginManager(context, astrbot_config)
    ok, error = await manager.load(specified_dir_name=NAME)
    assert ok, error
    plugin = star_map["data.plugins." + NAME + ".main"].star_cls
    try:
        # Advance a real APScheduler deadline; no WebChat event exists yet.
        scheduler.scheduler.modify_job(plugin.job_id, next_run_time=datetime.now(timezone.utc))
        async with asyncio.timeout(6):
            while not any(path == "job-finish" for path, _ in calls):
                await asyncio.sleep(.02)
        assert calls[:3] == [("jobs", {}), ("job-query", {"id": "workout"}), ("job-finish", {"id": "workout"})]

        adapter = WebChatAdapter({"id": "webchat"}, {}, asyncio.Queue())
        payload = {"message_id": run_id, "message": [{"type": "plain", "text": PROMPT}]}
        message = await adapter.convert_message(("member", "session", payload))
        event = adapter.create_event(message)
        back_queue = webchat_queue_mgr.get_or_create_back_queue(run_id, "session")
        tool = llm_tools.get_func("query_wearable_data")
        assert set(tool.parameters["properties"]) == {"metric", "period", "granularity"}

        class ControlledProvider:
            provider_config = {"id": "local-fixture"}

            async def text_chat(self, contexts, func_tool, **kwargs):
                last = contexts[-1]
                if last.role == "user":
                    assert PROMPT in str(last.content)
                    assert "query_wearable_data" in func_tool.names()
                    return LLMResponse(role="assistant", tools_call_name=["query_wearable_data"],
                                       tools_call_args=[{"metric": "exercise"}], tools_call_ids=["call1"])
                assert last.role == "tool", str(last)
                body = last.content if isinstance(last.content, str) else last.content[0].text
                result = json.loads(body)
                assert "heart_rate" in result["interpretationHint"] and "不能判定强度" in result["interpretationHint"]
                seconds = result["data"]["durationSeconds"]
                return LLMResponse(role="assistant", completion_text=f"运动记录为 {seconds} 秒。")

        runner = ToolLoopAgentRunner()
        await runner.reset(provider=ControlledProvider(), streaming=False,
                           request=ProviderRequest(prompt=PROMPT, func_tool=ToolSet(tools=[tool]), contexts=[]),
                           run_context=ContextWrapper(context=SimpleNamespace(event=event)),
                           tool_executor=FunctionToolExecutor, agent_hooks=BaseAgentRunHooks())
        async for _ in runner.step_until_done(3):
            pass
        assert runner.get_final_llm_resp().completion_text == "运动记录为 2340 秒。"
        signal = await asyncio.wait_for(back_queue.get(), 2)
        assert signal["type"] == "run_started" and signal["data"]["run_id"] == run_id
        assert ("query", {"origin": "webchat:FriendMessage:webchat!member!session", "sender": "member",
                          "messageId": run_id, "metric": "exercise", "granularity": "summary"}) in calls
        assert [body["metric"] for path, body in calls if path == "query"] == ["exercise"]
        await tool.handler(event, metric="exercise", period="latest")
        assert calls[-1][1]["relative"] == "latest"
        query_count = sum(path == "query" for path, _ in calls)
        for invalid in ("latest/date", "today", "2026-11-01", "2026-11-01/2026-11-02",
                        "2026-11-02T06:00Z/2026-11-01T07:00Z", "2026-09-01T00:00Z/2026-11-01T00:00Z"):
            error = json.loads(await tool.handler(event, metric="exercise", period=invalid))
            assert error["status"] == "invalid_query" and '{"metric":"exercise"}' in error["hint"]
        event.message_obj.type = MessageType.GROUP_MESSAGE
        assert json.loads(await tool.handler(event, metric="steps"))["status"] == "no_permission"
        event.message_obj.type = MessageType.FRIEND_MESSAGE
        event.message_obj.sender.user_id = "different"
        assert json.loads(await tool.handler(event, metric="steps"))["status"] == "no_permission"
        assert query_count == sum(path == "query" for path, _ in calls)
        event.message_obj.sender.user_id = "member"
        denied = await tool.handler(event, metric="sleep", period="2026-11-24T18:00:00+08:00/2026-11-25T07:00:00+08:00",
                                    granularity="detail")
        assert json.loads(denied) == {"status": "no_permission"}
        query = calls[-1][1]
        assert query["endMs"] - query["startMs"] == 13 * 3_600_000 and query["granularity"] == "detail"
        webchat_queue_mgr.remove_back_queue(run_id)
    finally:
        await plugin.terminate()
        assert await scheduler.list_jobs() == []
        await scheduler.shutdown()
        await db_helper.engine.dispose()


def isolated():
    calls = []
    run_id = str(uuid4())

    class ServiceFixture(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            assert self.headers["Authorization"] == "Bearer test-credential"
            op = self.path.removeprefix("/v1/device/wearable-")
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            calls.append((op, body))
            replies = {"jobs": {"jobs": [{"id": "workout"}]}, "job-query": {"status": "ok"},
                       "job-finish": {"status": "silent"}, "query": {"status": "ok", "source": "test",
                       "metric": "exercise", "data": {"durationSeconds": 2340}}}
            if op == "query" and body["metric"] == "sleep":
                self.send_error(403)
                return
            data = json.dumps(replies[op]).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    with ThreadingHTTPServer(("127.0.0.1", 0), ServiceFixture) as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            asyncio.run(exercise_plugin(f"http://127.0.0.1:{server.server_port}", calls, run_id))
        finally:
            server.shutdown()
            thread.join()


if __name__ == "__main__":
    if sys.argv[1:] == ["--isolated"]:
        isolated()
    else:
        unittest.main()
