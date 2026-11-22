from __future__ import annotations

import asyncio
import base64
import binascii
import io
import re
import time
from uuid import UUID

import httpx
from openai import APITimeoutError
from PIL import Image as PillowImage

from astrbot.api import logger
import astrbot.core.message.components as Comp
from astrbot.api.event import filter
from astrbot.api.star import Context, Star
from astrbot.core.platform import AstrMessageEvent
from astrbot.core.provider.sources.openai_responses_source import (
    ProviderOpenAIResponses,
)
from astrbot.core.star.filter.command import GreedyStr

IMAGE_MODEL = "qwen-image-3.0"
NATIVE_PATH = "services/aigc/multimodal-generation/generation"
API_TIMEOUT_SECONDS = 85
CALL_DEADLINE_SECONDS = 90
DOWNLOAD_TIMEOUT_SECONDS = 18
DOWNLOAD_DEADLINE_SECONDS = 20
MAX_IMAGE_BYTES = 20 * 1024 * 1024
CLAIM_EXTRA = "_chatbox_draw_generation_claimed"
SUCCESS_MESSAGE = "图片已生成并发送。"
SAFE_ERROR_CODE = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")
WORKSPACE_HOST = re.compile(
    r"[a-z0-9][a-z0-9-]*\.(?:cn-beijing|ap-southeast-1|us-east-1|"
    r"eu-central-1|ap-northeast-1|cn-hongkong)\.maas\.aliyuncs\.com\Z"
)
RESULT_BUCKET_HOST = re.compile(
    r"dashscope-[a-z0-9-]+\.oss-(?:accelerate|cn-[a-z0-9-]+)\.aliyuncs\.com\Z"
)
IMAGE_DATA_PREFIX = "data:image/png;base64,"
NATIVE_HOSTS = {"dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com"}
QUOTA_ERROR_CODES = {
    "Arrearage",
    "Throttling.AllocationQuota",
    "insufficient_quota",
}


class _ImageDownloadError(Exception):
    """A temporary provider image URL could not be downloaded safely."""


class ChatboxDraw(Star):
    """Generate and send new images only in one enrolled WebChat conversation."""

    def __init__(self, context: Context, config: dict | None = None):
        super().__init__(context)
        config = config or {}
        self.provider_id = config.get("provider_id", "")
        self.allowed_origin = config.get("allowed_origin", "")

    def _is_authorized(self, event: AstrMessageEvent) -> bool:
        """Require exact UMO, WebChat, admin role, and matching embedded sender."""
        if (
            not isinstance(self.allowed_origin, str)
            or not self.allowed_origin
            or not isinstance(self.provider_id, str)
            or not self.provider_id
        ):
            return False

        try:
            origin = event.unified_msg_origin
            if (
                not isinstance(origin, str)
                or origin != self.allowed_origin
                or event.get_platform_id() != "webchat"
                or not event.is_admin()
            ):
                return False
            platform_id, message_type, session_id = origin.split(":", 2)
            session_platform, enrolled_sender, conversation_id = session_id.split(
                "!", 2
            )
            return bool(
                platform_id == "webchat"
                and message_type == "FriendMessage"
                and session_platform == "webchat"
                and enrolled_sender
                and conversation_id
                and event.get_sender_id() == enrolled_sender
            )
        except (AttributeError, TypeError, ValueError):
            return False

    @staticmethod
    def _validate_png(image_bytes: bytes) -> bytes:
        """Validate the file bytes and dimensions of one generated PNG."""
        if not isinstance(image_bytes, bytes) or not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
            raise ValueError("invalid image size")

        with PillowImage.open(io.BytesIO(image_bytes)) as image:
            if image.format != "PNG" or image.size != (1024, 1024):
                raise ValueError("unexpected image format or dimensions")
            image.verify()
        with PillowImage.open(io.BytesIO(image_bytes)) as image:
            image.load()
        return image_bytes

    @classmethod
    def _decode_png_data_url(cls, image: str) -> bytes:
        """Decode and validate the DashScope PNG data URL result."""
        if not isinstance(image, str) or not image.startswith(IMAGE_DATA_PREFIX):
            raise ValueError("invalid image data URL")
        encoded = image[len(IMAGE_DATA_PREFIX) :]
        if not encoded or len(encoded) > MAX_IMAGE_BYTES * 2:
            raise ValueError("invalid image data")
        try:
            image_bytes = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ValueError("invalid image data") from exc
        return cls._validate_png(image_bytes)

    @staticmethod
    def _native_base_url(client) -> str | None:
        """Build the native DashScope API root from a vetted provider URL."""
        try:
            url = httpx.URL(str(client.base_url))
        except (httpx.InvalidURL, AttributeError, TypeError, ValueError):
            return None
        if (
            url.scheme != "https"
            or url.username
            or url.password
            or url.port not in (None, 443)
            or url.query
            or url.fragment
            or url.path not in ("/", "/compatible-mode/v1", "/compatible-mode/v1/")
        ):
            return None

        host = url.host.lower().rstrip(".")
        if host not in NATIVE_HOSTS and not WORKSPACE_HOST.fullmatch(host):
            return None
        return f"https://{host}/api/v1/"

    @staticmethod
    def _result_bucket_url(image: str) -> httpx.URL | None:
        """Accept only HTTPS signed-result URLs from documented DashScope buckets."""
        try:
            url = httpx.URL(image)
        except (httpx.InvalidURL, TypeError, ValueError):
            return None
        host = url.host.lower().rstrip(".")
        if (
            url.scheme != "https"
            or url.username
            or url.password
            or url.port not in (None, 443)
            or url.fragment
            or not RESULT_BUCKET_HOST.fullmatch(host)
        ):
            return None
        return url

    async def _download_result_url(self, image: str) -> bytes:
        """Fetch a bounded signed result URL without API credentials or redirects."""
        url = self._result_bucket_url(image)
        if url is None:
            raise _ImageDownloadError from None

        async def read_result() -> bytes:
            async with httpx.AsyncClient(
                follow_redirects=False,
                timeout=DOWNLOAD_TIMEOUT_SECONDS,
            ) as client:
                async with client.stream("GET", str(url)) as response:
                    if response.status_code != 200:
                        raise _ImageDownloadError
                    content_length = response.headers.get("content-length")
                    if content_length:
                        try:
                            if int(content_length) > MAX_IMAGE_BYTES:
                                raise _ImageDownloadError
                        except ValueError:
                            raise _ImageDownloadError from None
                    image_bytes = bytearray()
                    async for chunk in response.aiter_bytes():
                        image_bytes.extend(chunk)
                        if len(image_bytes) > MAX_IMAGE_BYTES:
                            raise _ImageDownloadError
                    return bytes(image_bytes)

        try:
            image_bytes = await asyncio.wait_for(
                read_result(), timeout=DOWNLOAD_DEADLINE_SECONDS
            )
        except Exception:
            raise _ImageDownloadError from None
        return self._validate_png(image_bytes)

    @staticmethod
    def _image_from_response(response: dict) -> str:
        """Extract the first returned image data URL or temporary result URL."""
        if not isinstance(response, dict):
            raise ValueError("invalid native response")
        output = response.get("output")
        choices = output.get("choices") if isinstance(output, dict) else None
        if not isinstance(choices, list) or not choices:
            raise ValueError("missing generated image")
        message = choices[0].get("message") if isinstance(choices[0], dict) else None
        content = message.get("content") if isinstance(message, dict) else None
        if isinstance(content, list):
            for item in content:
                if isinstance(item, dict) and isinstance(item.get("image"), str):
                    return item["image"]
        raise ValueError("missing generated image")

    def _claim_event(self, event: AstrMessageEvent) -> bool:
        """Claim this event before any paid work; failures remain non-retryable."""
        if event.get_extra(CLAIM_EXTRA, False):
            return False
        event.set_extra(CLAIM_EXTRA, True)
        return True

    @staticmethod
    def _provider_failure(exc: Exception) -> str:
        """Return a sanitized status/code without exposing provider messages."""
        status = getattr(exc, "status_code", None)
        if isinstance(status, bool) or not isinstance(status, int) or not 100 <= status <= 599:
            status = None

        code = getattr(exc, "code", None)
        body = getattr(exc, "body", None)
        if isinstance(body, dict):
            error = body.get("error")
            if isinstance(error, dict):
                code = code or error.get("code")
            code = code or body.get("code")
        if not isinstance(code, str) or not SAFE_ERROR_CODE.fullmatch(code):
            code = None

        return ChatboxDraw._provider_failure_details(status, code)

    @staticmethod
    def _provider_failure_details(status: int | None, code: str | None) -> str:
        """Format only bounded status and safe error-code details."""
        if not isinstance(code, str) or not SAFE_ERROR_CODE.fullmatch(code):
            code = None

        suffix = f"，错误码 {code}" if code else ""
        status_text = f"HTTP {status}" if status else "图片服务错误"
        if code in QUOTA_ERROR_CODES:
            return f"图片服务额度不足或资源受限（{status_text}{suffix}）；本条请求不会自动重试。"
        if status == 429:
            return f"图片服务触发限流或额度限制（{status_text}{suffix}）；本条请求不会自动重试。"
        if status == 400 or code == "InvalidParameter":
            return f"图片请求参数被服务拒绝（{status_text}{suffix}）；本条请求不会自动重试。"
        if status is not None:
            return f"图片服务返回错误（{status_text}{suffix}）；本条请求不会自动重试。"
        return "未收到可确认的图片结果；本条请求不会自动重试。"

    async def _create_and_send(
        self,
        event: AstrMessageEvent,
        prompt: str,
    ) -> str | None:
        """Generate one image and send it as a native AstrBot image component."""
        if not self._is_authorized(event):
            return None
        if not isinstance(prompt, str) or not prompt.strip():
            return "请在 /画图 后写下要生成的画面。"
        if not self._claim_event(event):
            return "本条消息已经尝试过图片生成。"

        try:
            provider = self.context.get_provider_by_id(self.provider_id)
        except Exception:
            return "图片服务尚未正确配置。"
        if not isinstance(provider, ProviderOpenAIResponses):
            return "图片服务尚未正确配置。"

        native_base_url = self._native_base_url(provider.client)
        if native_base_url is None:
            return "图片服务尚未正确配置为受支持的百炼地址。"

        try:
            # Reuse the provider's existing transport and currently selected
            # key, while routing directly to DashScope's native synchronous API.
            client = provider.client.with_options(
                api_key=provider.get_current_key(),
                base_url=native_base_url,
                max_retries=0,
                timeout=API_TIMEOUT_SECONDS,
            )
            request_started_at = time.monotonic()
            response = await asyncio.wait_for(
                client.post(
                    NATIVE_PATH,
                    cast_to=dict,
                    body={
                        "model": IMAGE_MODEL,
                        "input": {
                            "messages": [
                                {
                                    "role": "user",
                                    "content": [{"text": prompt.strip()}],
                                }
                            ]
                        },
                        "parameters": {
                            "n": 1,
                            "size": "1024*1024",
                            "response_format": "b64_json",
                        },
                    },
                ),
                timeout=CALL_DEADLINE_SECONDS,
            )
            request_elapsed_seconds = time.monotonic() - request_started_at
            request_id = (
                response.get("request_id") if isinstance(response, dict) else None
            )
            if isinstance(request_id, str):
                try:
                    request_id = str(UUID(request_id))
                except ValueError:
                    request_id = "unavailable"
            else:
                request_id = "unavailable"
            logger.info(
                f"[ChatboxDraw Native Receipt] model={IMAGE_MODEL} "
                f"elapsed_seconds={request_elapsed_seconds:.3f} "
                f"request_id={request_id}"
            )
        except (asyncio.TimeoutError, APITimeoutError):
            return "图片请求超时，是否已生成无法确认；本条请求不会自动重试。"
        except Exception as exc:
            # Keep provider request data, credentials, and prompt out of
            # user-visible output and plugin logs.
            return self._provider_failure(exc)

        try:
            if isinstance(response, dict) and (
                isinstance(response.get("error"), dict) or response.get("code")
            ):
                error = response.get("error")
                code = (
                    error.get("code")
                    if isinstance(error, dict)
                    else response.get("code")
                )
                return self._provider_failure_details(None, code)
            image = self._image_from_response(response)
            if image.startswith(IMAGE_DATA_PREFIX):
                image_bytes = self._decode_png_data_url(image)
            elif image.startswith("https://"):
                image_bytes = await self._download_result_url(image)
            else:
                raise ValueError("unexpected image result")
        except _ImageDownloadError:
            return "图片生成完成，但临时结果下载失败；本条请求不会自动重试。"
        except Exception:
            return "图片结果格式无效；本条请求不会自动重试。"

        try:
            image = Comp.Image.fromBytes(image_bytes)
            await event.send(event.chain_result([image]))
        except Exception:
            return "图片生成完成，但发送失败；不会自动重复生成。"
        return SUCCESS_MESSAGE

    @filter.command("画图")
    async def draw_command(self, event: AstrMessageEvent, prompt: GreedyStr) -> None:
        """Direct command for explicitly requested new image generation.

        Args:
            prompt: Description of the new image to create.
        """
        if not self._is_authorized(event):
            return
        event.stop_event()
        result = await self._create_and_send(event, prompt)
        if result and result != SUCCESS_MESSAGE:
            try:
                await event.send(event.plain_result(result))
            except Exception:
                pass

    @filter.llm_tool(name="chatbox_draw_image")
    async def draw_image_tool(self, event: AstrMessageEvent, prompt: str) -> str:
        """Create and send a new image only when the user explicitly asks for one.

        Do not use this for stickers, memes, existing images, or ordinary
        conversation. The image is sent directly; return no image data.

        Args:
            prompt (string): Description of the new image to create.
        """
        result = await self._create_and_send(event, prompt)
        return result or "当前会话未获准生成图片。"
