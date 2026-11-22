import base64
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import httpx
from openai import APITimeoutError
from PIL import Image as PillowImage

# AstrBot derives its data directory from ASTRBOT_ROOT. Keep import-time
# defaults isolated from the repository and live installation.
_TEST_DATA_ROOT = tempfile.TemporaryDirectory(prefix="chatbox-draw-astrbot-")
os.environ["ASTRBOT_ROOT"] = _TEST_DATA_ROOT.name

PLUGIN_DIR = Path(__file__).resolve().parents[1] / "astrbot_plugin_chatbox_draw"
PLUGIN_SPEC = importlib.util.spec_from_file_location(
    "chatbox_draw_test_plugin", PLUGIN_DIR / "main.py"
)
PLUGIN = importlib.util.module_from_spec(PLUGIN_SPEC)
sys.modules[PLUGIN_SPEC.name] = PLUGIN
PLUGIN_SPEC.loader.exec_module(PLUGIN)

import astrbot.core.message.components as Comp
from astrbot.core.provider.register import llm_tools
from astrbot.core.provider.sources.openai_responses_source import (
    ProviderOpenAIResponses,
)


ALLOWED_ORIGIN = "webchat:FriendMessage:webchat!enrolled-user!conversation-id"
RESULT_URL = (
    "https://dashscope-result-sz.oss-cn-shenzhen.aliyuncs.com/output.png"
    "?signature=synthetic"
)


def png_bytes() -> bytes:
    buffer = io.BytesIO()
    PillowImage.new("RGB", (1024, 1024), "white").save(buffer, format="PNG")
    return buffer.getvalue()


def png_data_url() -> str:
    return "data:image/png;base64," + base64.b64encode(png_bytes()).decode("ascii")


def native_response(image: str) -> dict:
    return {
        "output": {
            "choices": [
                {"message": {"content": [{"image": image}]}}
            ]
        }
    }


class FakeNativeApi:
    def __init__(self, response=None, error=None):
        self.response = response if response is not None else native_response(png_data_url())
        self.error = error
        self.calls = []

    async def post(self, path, *, cast_to, body):
        self.calls.append({"path": path, "cast_to": cast_to, "body": body})
        if self.error:
            raise self.error
        return self.response


class FakeClient:
    def __init__(self, api, base_url="https://dashscope.aliyuncs.com/compatible-mode/v1"):
        self.api = api
        self.base_url = httpx.URL(base_url)
        self.api_key = "synthetic-test-key"
        self.options = None

    def with_options(self, **kwargs):
        self.options = kwargs
        return self

    async def post(self, path, *, cast_to, body):
        return await self.api.post(path, cast_to=cast_to, body=body)


class FakeContext:
    def __init__(self, provider):
        self.provider = provider
        self.lookups = []

    def get_provider_by_id(self, provider_id):
        self.lookups.append(provider_id)
        return self.provider


class FakeEvent:
    def __init__(
        self,
        origin=ALLOWED_ORIGIN,
        platform_id="webchat",
        sender="enrolled-user",
        admin=True,
        send_error=None,
    ):
        self.unified_msg_origin = origin
        self.platform_id = platform_id
        self.sender = sender
        self.admin = admin
        self.send_error = send_error
        self.extras = {}
        self.sent = []
        self.stopped = False

    def get_platform_id(self):
        return self.platform_id

    def is_admin(self):
        return self.admin

    def get_sender_id(self):
        return self.sender

    def get_extra(self, key, default=None):
        return self.extras.get(key, default)

    def set_extra(self, key, value):
        self.extras[key] = value

    def chain_result(self, components):
        return components

    def plain_result(self, text):
        return text

    async def send(self, message):
        if self.send_error:
            raise self.send_error
        self.sent.append(message)

    def stop_event(self):
        self.stopped = True


class FakeDownloadResponse:
    def __init__(self, body, status_code=200):
        self.body = body
        self.status_code = status_code
        self.headers = {"content-length": str(len(body))}

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    async def aiter_bytes(self):
        yield self.body


class FakeDownloadClient:
    def __init__(self, body, status_code=200):
        self.response = FakeDownloadResponse(body, status_code)
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    def stream(self, method, url):
        self.calls.append((method, url))
        return self.response


class ChatboxDrawTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.api = FakeNativeApi()
        client = FakeClient(self.api)
        self.provider = object.__new__(ProviderOpenAIResponses)
        self.provider.client = client
        self.context = FakeContext(self.provider)
        self.plugin = PLUGIN.ChatboxDraw(
            self.context,
            {"provider_id": "configured-image-provider", "allowed_origin": ALLOWED_ORIGIN},
        )

    async def test_native_endpoint_wire_and_data_url_send(self):
        event = FakeEvent()

        result = await self.plugin._create_and_send(event, "a small red sailboat")

        self.assertEqual(result, PLUGIN.SUCCESS_MESSAGE)
        self.assertEqual(
            self.api.calls,
            [
                {
                    "path": "services/aigc/multimodal-generation/generation",
                    "cast_to": dict,
                    "body": {
                        "model": "qwen-image-3.0",
                        "input": {
                            "messages": [
                                {
                                    "role": "user",
                                    "content": [{"text": "a small red sailboat"}],
                                }
                            ]
                        },
                        "parameters": {
                            "n": 1,
                            "size": "1024*1024",
                            "response_format": "b64_json",
                        },
                    },
                }
            ],
        )
        self.assertEqual(
            self.provider.client.options,
            {
                "api_key": "synthetic-test-key",
                "base_url": "https://dashscope.aliyuncs.com/api/v1/",
                "max_retries": 0,
                "timeout": 85,
            },
        )
        self.assertEqual(len(event.sent), 1)
        self.assertIsInstance(event.sent[0][0], Comp.Image)
        self.assertTrue(event.sent[0][0].file.startswith("base64://"))
        self.assertEqual(self.context.lookups, ["configured-image-provider"])

    async def test_native_success_logs_safe_receipt_with_valid_or_missing_id(self):
        cases = (
            (
                "7f36f24b-ef0f-4b56-97e0-c7b56f4c21de",
                "7f36f24b-ef0f-4b56-97e0-c7b56f4c21de",
            ),
            (None, "unavailable"),
            ("private-not-a-uuid", "unavailable"),
        )

        for response_id, expected_id in cases:
            with self.subTest(response_id=response_id):
                self.api.response = native_response(png_data_url())
                if response_id is not None:
                    self.api.response["request_id"] = response_id
                event = FakeEvent()

                with patch.object(PLUGIN, "logger") as receipt_logger:
                    result = await self.plugin._create_and_send(
                        event, "private prompt"
                    )

                self.assertEqual(result, PLUGIN.SUCCESS_MESSAGE)
                self.assertEqual(len(event.sent), 1)
                receipt_logger.info.assert_called_once()
                line = receipt_logger.info.call_args.args[0]
                self.assertTrue(line.startswith("[ChatboxDraw Native Receipt] "))
                self.assertIn("model=qwen-image-3.0", line)
                self.assertIn(f"request_id={expected_id}", line)
                elapsed = line.split("elapsed_seconds=", 1)[1].split(" ", 1)[0]
                self.assertGreaterEqual(float(elapsed), 0)
                self.assertNotIn("private prompt", line)
                self.assertNotIn("private-not-a-uuid", line)
                self.assertNotIn(PLUGIN.IMAGE_DATA_PREFIX, line)

    async def test_rejects_wrong_conversation_platform_sender_and_role(self):
        events = [
            FakeEvent(origin="webchat:FriendMessage:webchat!different!conversation"),
            FakeEvent(platform_id="other-platform"),
            FakeEvent(sender="different"),
            FakeEvent(admin=False),
        ]
        for event in events:
            with self.subTest(event=event):
                self.assertIsNone(await self.plugin._create_and_send(event, "new image"))
                self.assertEqual(event.sent, [])
        self.assertEqual(self.api.calls, [])
        self.assertEqual(self.context.lookups, [])

    async def test_one_paid_attempt_per_event_after_api_failure(self):
        self.api.error = RuntimeError("private provider response must not leak")
        event = FakeEvent()

        first = await self.plugin._create_and_send(event, "new image")
        second = await self.plugin._create_and_send(event, "new image")

        self.assertEqual(first, "未收到可确认的图片结果；本条请求不会自动重试。")
        self.assertEqual(second, "本条消息已经尝试过图片生成。")
        self.assertEqual(len(self.api.calls), 1)
        self.assertEqual(event.sent, [])

    async def test_timeout_reports_unknown_outcome_without_retrying(self):
        self.api.error = APITimeoutError(request=None)
        event = FakeEvent()

        first = await self.plugin._create_and_send(event, "new image")
        second = await self.plugin._create_and_send(event, "new image")

        self.assertEqual(first, "图片请求超时，是否已生成无法确认；本条请求不会自动重试。")
        self.assertEqual(second, "本条消息已经尝试过图片生成。")
        self.assertEqual(len(self.api.calls), 1)

    async def test_provider_quota_error_is_sanitized(self):
        error = RuntimeError("private provider response must not leak")
        error.status_code = 403
        error.body = {"code": "Arrearage", "message": "private response detail"}
        self.api.error = error

        result = await self.plugin._create_and_send(FakeEvent(), "secret prompt")

        self.assertEqual(
            result,
            "图片服务额度不足或资源受限（HTTP 403，错误码 Arrearage）；本条请求不会自动重试。",
        )
        self.assertNotIn("private", result)
        self.assertNotIn("secret prompt", result)

    async def test_temporary_result_url_download_is_bounded_and_unauthenticated(self):
        self.api.response = native_response(RESULT_URL)
        event = FakeEvent()
        downloader = FakeDownloadClient(png_bytes())

        with patch.object(PLUGIN.httpx, "AsyncClient", return_value=downloader) as factory:
            result = await self.plugin._create_and_send(event, "a new image")

        self.assertEqual(result, PLUGIN.SUCCESS_MESSAGE)
        self.assertEqual(downloader.calls, [("GET", RESULT_URL)])
        factory.assert_called_once_with(follow_redirects=False, timeout=18)
        self.assertEqual(len(event.sent), 1)
        self.assertIsInstance(event.sent[0][0], Comp.Image)

    async def test_untrusted_result_url_is_not_fetched(self):
        self.api.response = native_response("https://attacker.example/image.png?sig=secret")
        event = FakeEvent()

        with patch.object(PLUGIN.httpx, "AsyncClient") as factory:
            result = await self.plugin._create_and_send(event, "a new image")

        self.assertEqual(result, "图片生成完成，但临时结果下载失败；本条请求不会自动重试。")
        factory.assert_not_called()
        self.assertEqual(event.sent, [])

    async def test_rejects_untrusted_provider_origins_before_post(self):
        for base_url in (
            "http://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com.evil.example/compatible-mode/v1",
            "https://user:password@dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com:8443/compatible-mode/v1",
            "https://api.other-vendor.example/v1",
        ):
            with self.subTest(base_url=base_url):
                self.provider.client.base_url = httpx.URL(base_url)
                event = FakeEvent()
                result = await self.plugin._create_and_send(event, "new image")
                self.assertEqual(result, "图片服务尚未正确配置为受支持的百炼地址。")
                self.assertEqual(event.sent, [])
        self.assertEqual(self.api.calls, [])

    def test_native_endpoint_uses_supported_workspace_domain(self):
        client = SimpleNamespace(
            base_url=httpx.URL(
                "https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
            )
        )

        self.assertEqual(
            PLUGIN.ChatboxDraw._native_base_url(client),
            "https://workspace-123.cn-beijing.maas.aliyuncs.com/api/v1/",
        )

    async def test_send_failure_does_not_repeat_generation(self):
        event = FakeEvent(send_error=RuntimeError("synthetic send failure"))

        first = await self.plugin._create_and_send(event, "new image")
        second = await self.plugin._create_and_send(event, "new image")

        self.assertEqual(first, "图片生成完成，但发送失败；不会自动重复生成。")
        self.assertEqual(second, "本条消息已经尝试过图片生成。")
        self.assertEqual(len(self.api.calls), 1)

    async def test_command_preserves_space_containing_greedy_prompt(self):
        event = FakeEvent()

        await self.plugin.draw_command(event, "a red boat sailing under the moon")

        self.assertTrue(event.stopped)
        self.assertEqual(
            self.api.calls[0]["body"]["input"]["messages"][0]["content"][0]["text"],
            "a red boat sailing under the moon",
        )

    async def test_unmatched_command_does_not_stop_other_conversations(self):
        event = FakeEvent(origin="webchat:FriendMessage:webchat!other!conversation")

        await self.plugin.draw_command(event, "new image")

        self.assertFalse(event.stopped)
        self.assertEqual(self.api.calls, [])

    async def test_non_responses_provider_fails_closed(self):
        self.context.provider = object()
        event = FakeEvent()

        result = await self.plugin._create_and_send(event, "new image")

        self.assertEqual(result, "图片服务尚未正确配置。")
        self.assertEqual(self.api.calls, [])

    def test_configuration_schema_is_empty_by_default(self):
        schema = json.loads((PLUGIN_DIR / "_conf_schema.json").read_text(encoding="utf-8"))

        self.assertEqual(set(schema), {"provider_id", "allowed_origin"})
        self.assertEqual(schema["provider_id"]["default"], "")
        self.assertEqual(schema["allowed_origin"]["default"], "")

    def test_registered_tool_has_one_string_prompt_argument(self):
        tool = llm_tools.get_func("chatbox_draw_image")

        self.assertIsNotNone(tool)
        self.assertEqual(
            tool.parameters["properties"],
            {"prompt": {"type": "string", "description": "Description of the new image to create."}},
        )


if __name__ == "__main__":
    unittest.main()
