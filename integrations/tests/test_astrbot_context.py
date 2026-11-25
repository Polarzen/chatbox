"""Run with AstrBot 4.29.4's dependencies and patched source on PYTHONPATH."""

import json
import os
import tempfile
import unittest
from types import SimpleNamespace as Obj
from unittest.mock import AsyncMock

# Importing AstrBot initializes its config. Tests always use a disposable data root.
_TEST_ROOT = tempfile.TemporaryDirectory(prefix="chatbox-context-unit-")
os.environ["ASTRBOT_ROOT"] = _TEST_ROOT.name

from astrbot.core.agent.tool import FunctionTool, ToolSet
from astrbot.core.agent.message import Message, bind_checkpoint_messages
from astrbot.core.agent.webchat_selection import (
    ContextSelection,
    SelectionError,
    digest,
    provider_identity,
    select_history,
    verify_wire_budget,
)
from astrbot.core.provider.sources.openai_source import ProviderOpenAIOfficial
from astrbot.dashboard.api.auth import AuthContext
from astrbot.dashboard.responses import ApiError
from astrbot.dashboard.services.chatbox_context import context_request


def message(role, text):
    return {"role": role, "content": text}


def checkpoint(key):
    return {"role": "_checkpoint", "content": {"id": key}}


def visible(key, role, text):
    return Obj(llm_checkpoint_id=key, content={"type": role, "message": [{"type": "plain", "text": text}]})


def pin(key, role):
    return {"seq": 999, "role": role, "question": f"Q{key}", "answer": f"A{key}"}


def provider():
    # Only provider identity, model metadata and its native wire sanitizer are required here.
    result = ProviderOpenAIOfficial.__new__(ProviderOpenAIOfficial)
    result.provider_config = {"id": "fixture", "max_context_tokens": 16000}
    result.set_model("fixture")
    return result


class NativeSelectionTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.history = []
        self.records = []
        tool = {"type": "function", "id": "t1", "function": {"name": "temperature", "arguments": "{}"}}
        for key in "abc":
            self.history.append(message("user", "Q" + key))
            if key == "a":
                self.history.extend([dict(message("assistant", None), tool_calls=[tool]),
                                     dict(message("tool", "result"), tool_call_id="t1")])
            self.history.extend([message("assistant", "A" + key), checkpoint(key)])
            self.records.extend([visible(key, "user", "Q" + key), visible(key, "bot", "A" + key)])
        self.prov = provider()
        self.conv = Obj(cid="cid", history=json.dumps(self.history), persona_id=None)
        self.req = Obj(contexts=self.history, conversation=self.conv, prompt="NOW", image_urls=[],
                       audio_urls=[], model=None, system_prompt="runtime persona")
        self.tool_manager = Obj(get_full_tool_set=lambda: ToolSet(), get_builtin_tool=lambda cls: cls())
        self.manager = Obj(get_curr_conversation_id=AsyncMock(return_value="cid"),
                           get_conversation=AsyncMock(return_value=self.conv))

    def select(self, recent=1, pins=(), hints=()):
        return select_history(self.history, self.records, {"recent": recent, "pins": list(pins), "hints": list(hints)})

    def make_plan(self, keep):
        return ContextSelection("rev", "fixture-umo", "cid", digest(self.history), "NOW",
                                provider_identity(self.prov), keep)

    def runtime(self):
        return [Message(**message("system", self.req.system_prompt)),
                *bind_checkpoint_messages(self.history), Message(**message("user", "NOW"))]

    def test_projection_preserves_native_bundle_and_full_history(self):
        keep, selected, _, _ = self.select(1, [pin("a", "assistant"), pin("c", "assistant")])
        self.assertEqual([(m["role"], m["text"], m["checkpoint"]) for m in selected],
                         [("assistant", "Aa", "a"), ("user", "Qc", "c"), ("assistant", "Ac", "c")])
        full = self.runtime()
        view = self.make_plan(keep).bind(self.req, self.prov, "fixture-umo", full)
        projected = view.project(full)
        self.assertEqual([m.role for m in projected], ["system", "assistant", "tool",
                                                       "assistant", "user", "assistant", "user"])
        self.assertEqual(projected[1].tool_calls[0].id, projected[2].tool_call_id)
        self.assertEqual([m.content for m in projected if m.role == "user"], ["Qc", "NOW"])
        self.assertTrue(any(m.content == "Qb" for m in full))
        self.assertEqual(self.select(3)[1][2], {"role": "user", "text": "", "checkpoint": "b"})
        new_tool_step = Message(**message("assistant", "result"))
        self.assertIs(view.project(full + [new_tool_step])[-1], new_tool_step)
        full[-1] = Message(**message("user", "modified by a hook"))
        with self.assertRaises(SelectionError):
            view.project(full)

    def test_requires_both_texts_and_a_unique_durable_checkpoint(self):
        hint = pin("a", "user")
        hint["answer"] = "wrong"
        with self.assertRaises(SelectionError):
            self.select(0, [hint])
        self.records.extend([visible("extra", "user", "Qa"), visible("extra", "bot", "Aa")])
        with self.assertRaises(SelectionError):
            self.select(0, [pin("a", "user")])
        self.records[:] = self.records[:-2]
        self.history[:] = self.history[self.history.index(checkpoint("a")) + 1:]
        with self.assertRaises(SelectionError):
            self.select(0, [pin("a", "user")])

    def test_hints_prove_display_text_without_extending_model_selection(self):
        known = dict(seq=2, question="Qb", answer="Ab")
        unselected = dict(seq=1, question="Qa", answer="Aa")
        keep, visible_items, projected, _ = self.select(2, [], [known, unselected])
        anonymous = self.select(2)
        self.assertEqual((keep, projected), (anonymous[0], anonymous[2]))
        self.assertEqual(visible_items, [
            {"role": "user", "text": "Qb", "checkpoint": "b", "seq": 2},
            {"role": "assistant", "text": "Ab", "checkpoint": "b", "seq": 2},
            {"role": "user", "text": "", "checkpoint": "c"},
            {"role": "assistant", "text": "", "checkpoint": "c"},
        ])
        self.records[2].content["message"].append({"type": "image", "attachment_id": "x"})
        with self.assertRaises(SelectionError):
            self.select(0, [pin("b", "user")])
        self.assertEqual(self.select(0, [{**pin("b", "user"), "checkpoint": "b"}])[1][0]["text"], "Qb")
        self.assertEqual(self.select(2, [], [{**known, "checkpoint": "a"}])[1][0]["text"], "")

    def test_file_extract_follows_user_pin_and_unknown_system_requires_whole_turn(self):
        extracted = message("system", "File Extract Results of user uploaded files:\nprivate notes\nFile Name: test.txt")
        self.history.insert(0, extracted)
        self.records[0].content["message"].append({"type": "file", "filename": "test.txt", "attachment_id": "x"})
        def side(role):
            return self.select(0, [{**pin("a", role), "checkpoint": "a"}])[2]
        self.assertNotIn(extracted, side("assistant"))
        self.assertIn(extracted, side("user"))
        self.history[0] = message("system", "unattributed note")
        for role in ("assistant", "user"):
            with self.assertRaisesRegex(SelectionError, "完整问答"):
                side(role)
        self.assertIn(self.history[0], self.select(3)[2])

    def test_persona_examples_preserved_before_selected_history(self):
        from dataclasses import replace

        keep = self.select(0)[0]
        examples = [dict(message("user", "example"), _no_save=True),
                    dict(message("assistant", "response"), _no_save=True)]
        plan = replace(self.make_plan(keep), begin_count=2, begin_hash=digest(examples))
        self.req.contexts = examples + self.history
        runtime = self.runtime()
        runtime[1:1] = bind_checkpoint_messages(examples)
        view = plan.bind(self.req, self.prov, "fixture-umo", runtime)
        self.assertEqual([m.content for m in view.project(runtime)], ["runtime persona", "example", "response", "NOW"])

    def test_rejects_incomplete_tool_bundle(self):
        self.history[:] = [m for m in self.history if m["role"] != "tool"]
        with self.assertRaises(SelectionError):
            self.select(3)

    async def test_rechecks_native_history_immediately_before_dispatch(self):
        plan = self.make_plan(self.select()[0])
        await plan.verify_current(self.manager, self.req)
        self.conv.history = json.dumps(self.history[:-1])
        with self.assertRaises(SelectionError):
            await plan.verify_current(self.manager, self.req)

    def test_rejects_hook_changes_and_provider_wire_overflow(self):
        plan = self.make_plan(self.select()[0])
        self.req.contexts = [message("user", "unexpected")] + self.history
        with self.assertRaises(SelectionError):
            plan.bind(self.req, self.prov, "fixture-umo", self.runtime())
        self.prov.provider_config["max_context_tokens"] = 40
        payload = {"messages": [message("user", "short")], "tools": [{"description": "X" * 600}]}
        with self.assertRaises(SelectionError):
            verify_wire_budget(self.prov, payload, {})

    async def test_replay_requires_completed_native_answer_and_matching_receipt(self):
        auth = AuthContext(username="caller", api_key_id="one", via="api_key", scopes=["chat"])
        turn_id = "00000000-0000-4000-a000-000000000001"
        request = {"username": "user", "session_id": "sid", "message": "NOW",
                   "context": {"recent": 0, "pins": []}, "turn_id": turn_id, "revision": "r"}
        umo = "webchat:FriendMessage:webchat!user!sid"
        receipt = digest([umo, "one", "NOW", request["context"], "r"])
        prompt = visible(turn_id, "user", "NOW")
        prompt.content["chatbox_receipt"] = receipt
        reply = visible(turn_id, "bot", "partial")
        conv = Obj(history=json.dumps([message("user", "NOW"), message("assistant", "partial"), checkpoint(turn_id)]))
        chat = Obj(db=Obj(get_platform_session_by_id=AsyncMock(return_value=Obj(
            creator="user", session_id="sid", is_group=False, platform_id="webchat"))),
            get_sorted_platform_history=AsyncMock(return_value=[prompt, reply]), get_active_chat_runs=lambda *args: [],
            conv_mgr=Obj(get_curr_conversation_id=AsyncMock(return_value="cid"),
                         get_conversation=AsyncMock(return_value=conv)), build_chat_stream=AsyncMock())
        service = Obj(validate_chat_username=AsyncMock())
        with self.assertRaises(ApiError) as rejected:
            await context_request(request, auth, service, chat, sending=True)
        self.assertEqual(rejected.exception.status_code, 409)
        completed = json.loads(conv.history)
        completed[-1]["_chatbox_completed"] = digest("complete")
        conv.history = json.dumps(completed)
        with self.assertRaises(ApiError):
            await context_request(request, auth, service, chat, sending=True)
        reply.content["message"] = [{"type": "plain", "text": "complete"}]
        response = await context_request(request, auth, service, chat, sending=True)
        self.assertIn('"data": "complete"', "".join([line async for line in response.body_iterator]))
        chat.build_chat_stream.assert_not_awaited()

    def endpoint_fixture(self):
        chat = Obj(
            db=Obj(get_platform_session_by_id=AsyncMock(return_value=Obj(
                creator="user", session_id="sid", is_group=False, platform_id="webchat"))),
            get_sorted_platform_history=AsyncMock(return_value=self.records),
            conv_mgr=self.manager, get_active_chat_runs=lambda *args: [],
            core_lifecycle=Obj(star_context=Obj(
                get_config=lambda **kwargs: {"provider_settings": {"enable": True},
                                              "agent_runner": {"runner_type": "local"}},
                subagent_orchestrator=None, get_llm_tool_manager=lambda: self.tool_manager,
                persona_manager=Obj(resolve_selected_persona=AsyncMock(return_value=("default", None, None, True))),
                get_using_provider_async=AsyncMock(return_value=self.prov))),
            build_chat_stream=AsyncMock())
        service = Obj(validate_chat_username=AsyncMock())
        auth = AuthContext(username="caller", api_key_id="one", via="api_key", scopes=["chat"])
        request = {"username": "user", "session_id": "sid", "message": "NOW",
                   "context": {"recent": 1, "pins": []}}
        return request, auth, service, chat

    async def test_large_effective_tool_schema_blocks_preview_before_storage_or_dispatch(self):
        request, auth, service, chat = self.endpoint_fixture()
        request["context"] = {"recent": 0, "pins": []}
        large = FunctionTool(name="catalog", description="known schema " * 9000, parameters={"type": "object"})
        self.tool_manager.get_full_tool_set = lambda: ToolSet(tools=[large])
        result = (await context_request(request, auth, service, chat, sending=False))["data"]
        self.assertFalse(result["canSend"])
        self.assertGreater(result["estimatedTokens"], result["maxTokens"])
        self.assertEqual(result["selected"], [])
        for adjustment in ("缩短当前问题", "工具", "输出预留", "窗口更大"):
            self.assertIn(adjustment, result["reason"])
        self.assertNotIn("减少轮数", result["reason"])
        request.update(revision=result["revision"], turn_id="00000000-0000-4000-a000-000000000002")
        with self.assertRaises(ApiError) as rejected:
            await context_request(request, auth, service, chat, sending=True)
        self.assertEqual(rejected.exception.status_code, 409)
        chat.build_chat_stream.assert_not_awaited()
        large.active = False
        self.assertTrue((await context_request(request, auth, service, chat, sending=False))["data"]["canSend"])

    async def test_read_only_preview_and_stale_admission(self):
        request, auth, service, chat = self.endpoint_fixture()
        result = (await context_request(request, auth, service, chat, sending=False))["data"]
        self.assertTrue(result["canSend"], result["reason"])
        self.assertTrue(all(m["text"] == "" for m in result["selected"]))
        self.prov.provider_config["custom_extra_body"] = {"max_tokens": 15999}
        budget = (await context_request(request, auth, service, chat, sending=False))["data"]
        self.assertFalse(budget["canSend"])
        self.assertEqual(budget["fittingVisibleMessagesEstimate"], [])
        self.prov.provider_config.pop("custom_extra_body")
        request.update(turn_id="00000000-0000-4000-a000-000000000000", revision=result["revision"])
        request["message"] = "another"
        with self.assertRaises(ApiError) as rejected:
            await context_request(request, auth, service, chat, sending=True)
        self.assertEqual(rejected.exception.status_code, 409)
        chat.build_chat_stream.assert_not_awaited()
        chat.core_lifecycle.star_context.get_using_provider_async.return_value = None
        unavailable = (await context_request(request, auth, service, chat, sending=False))["data"]
        self.assertFalse(unavailable["supported"])
        self.assertFalse(unavailable["canSend"])
        request["username"] = "different"
        with self.assertRaises(ApiError) as rejected:
            await context_request(request, auth, service, chat, sending=False)
        self.assertEqual(rejected.exception.status_code, 403)


if __name__ == "__main__":
    unittest.main()
