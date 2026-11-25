"""Exercise context previews and admitted sends through the device HTTP API and a counted adapter."""

import json
import threading
import time
import unittest

if __package__:
    from . import test_device_service as devices
    from . import test_service as fixture
else:
    import test_device_service as devices
    import test_service as fixture


class ContextHandler(fixture.ReplyHandler):
    def do_POST(self):
        if not self.path.startswith("/fixture/api/v1/chat/context/"):
            return super().do_POST()
        if self.headers.get("Authorization") != "Bearer " + self.server.expected_key:
            return self.send_error(403)
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path.endswith("/preview"):
            self.server.previews.append(body)
            if self.server.revision is None:
                return self.send_error(404)
            selected = [dict(role="assistant", seq=1, text="原答", checkpoint="native-receipt"),
                        dict(role="user", checkpoint="unavailable"), dict(role="assistant", seq=2, text="未授权原文")]
            result = dict(supported=True, revision=self.server.revision, canSend=True, reason="",
                          selected=selected, estimatedTokens=522, maxTokens=2048, private="never forward")
            data = json.dumps(dict(status="ok", data=result)).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        elif self.path.endswith("/send") and body.get("revision") == self.server.revision:
            self.server.send_attempts.append(body["turn_id"])
            self.server.send_started.set()
            if self.server.admission_mode != "accept":
                self.server.release_admission.wait(12)
                if self.server.admission_mode in ("reject", "already_accepted"):
                    message = ("预览已过期，请重新预览。" if self.server.admission_mode == "reject"
                               else "此请求已受理，尚无完整可重放的回复。")
                    data = json.dumps({"status": "error", "message": message}).encode()
                    self.send_response(409)
                    self.send_header("Content-Type","application/json")
                    self.send_header("Content-Length",str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                    return
                self.close_connection = True
                return
            self.server.calls.append((self.path, self.headers["Authorization"], body))
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            self.event(type="plain", data="完成")
            self.event(type="end")
        else:
            self.send_error(409)


class ContextTransportTests(unittest.TestCase):
    def setUp(self):
        self.d = d = devices.DeviceServiceTests()
        self.addCleanup(d.doCleanups)
        d.setUp()
        self.second = d.bot("other-key")
        d.configure("device-upstream", sourceId="B", base=self.second.base, key="other-key")
        self.second.RequestHandlerClass = ContextHandler
        self.second.previews = []
        self.second.revision = "context-v1"
        self.second.admission_mode = "accept"
        self.second.send_attempts = []
        self.second.send_started = threading.Event()
        self.second.release_admission = threading.Event()
        self.addCleanup(self.second.release_admission.set)
        self.profile = d.profile(d.pc, "B", self.second.base)
        self.target = dict(profile=self.profile, sid="chat-42")
        d.history(d.pc, **self.target)
        self.turn = d.upload(d.pc, self.profile, "chat-42", "原问", "原答")
        self.phone, _ = d.guest()
        self.q = dict(**self.target, question="新问题")
        self.submission = dict(**self.q, id=fixture.uid())
        self.configure(self.target)

    def configure(self, target):
        self.d.ok(self.d.post(self.d.pc, "chat-preferences", **target,
                              expected=dict(recentTurns=None, pins=[]),
                              changes=dict(recentTurns=0, pins=[dict(seq=1, role="assistant")])))

    def operation(self, predicate):
        deadline = time.monotonic() + 5
        while True:
            status, receipt = self.d.get(self.phone, "operation", id=self.submission["id"])
            if predicate(status, receipt):
                return status, receipt
            self.assertLess(time.monotonic(), deadline, receipt)
            time.sleep(0.02)

    def test_canonical_send_uses_approved_target_and_preserves_idempotency(self):
        d = self.d
        preview = d.ok(d.post(self.phone, "context-preview", **self.q))
        self.assertTrue(preview["canSend"])
        self.assertEqual(preview["selected"], [dict(role="assistant", seq=1, text="原答", checkpoint="native-receipt"),
                                               dict(role="user", text="", checkpoint="unavailable"),
                                               dict(role="assistant", text="")])
        self.assertEqual((preview["estimatedTokens"], preview["maxTokens"]), (522, 2048))
        self.assertNotIn("private", preview)
        context = dict(recent=0, pins=[dict(seq=1, role="assistant", question="原问", answer="原答")])
        expected = dict(username="alice", session_id="chat-42", message="新问题", context=context)
        self.assertEqual(self.second.previews, [expected])
        self.assertEqual(self.second.calls, [])
        body = dict(**self.submission, revision=preview["revision"])
        d.ok(d.post(self.phone, "send", **body))
        self.operation(lambda status, receipt: status == 200 and "turn" in receipt)
        completed = d.wait_turn(self.phone, self.profile, "chat-42", body["id"])
        self.assertEqual(completed["answer"], "完成")
        self.assertEqual(self.second.calls, [
            ("/fixture/api/v1/chat/context/send", "Bearer other-key",
             dict(expected, enable_streaming=True, revision=self.second.revision, turn_id=body["id"]))])
        self.second.revision = None
        self.assertEqual(d.ok(d.post(self.phone, "send", **body))["turn"], completed)
        self.assertEqual(len(self.second.previews), 2)
        self.assertEqual(d.first.calls, [])

    def test_rejections_leave_no_turn_or_dispatch(self):
        d = self.d
        preview = d.ok(d.post(self.phone, "context-preview", **self.q))
        body = dict(**self.submission, revision=preview["revision"])
        self.assertEqual(d.post(self.phone, "send", **self.submission)[0], 409)
        self.assertEqual(d.post(d.pc, "media-begin", **self.submission)[0], 409)
        self.assertEqual(d.post(self.phone, "send", **(body | dict(question="已编辑")))[0], 409)
        self.second.revision = "context-v2"
        self.assertEqual(d.post(self.phone, "send", **body)[0], 409)
        self.second.revision = None
        self.assertFalse(d.ok(d.post(self.phone, "context-preview", **self.q))["canSend"])
        self.assertEqual(d.post(self.phone, "send", **body)[0], 409)
        self.assertEqual(self.second.calls, [])
        self.assertEqual(len(d.ok(d.get(self.phone, "turns", **self.target))["turns"]), 1)
        self.assertEqual(d.get(self.phone, "operation", id=body["id"])[0], 404)
        self.assertEqual(d.post(d.public, "context-preview", **self.q)[0], 401)
        self.assertEqual(d.post(self.phone, "context-preview", **self.q, username="bob")[0], 400)
        private = d.profile(self.phone, username="bob")
        d.history(self.phone, private, sid="chat-43")
        foreign, _ = d.guest("someone else")
        self.assertEqual(d.post(foreign, "context-preview", profile=private, sid="chat-43", question="")[0], 404)

    def test_recent_hints_and_default_capability_probe(self):
        d = self.d
        old = dict(recentTurns=0, pins=[dict(seq=1, role="assistant")])
        d.ok(d.post(d.pc, "chat-preferences", **self.target, expected=old, changes=dict(recentTurns=1, pins=[])))
        recent = d.ok(d.post(self.phone, "context-preview", **self.q))
        self.assertEqual(self.second.previews[-1]["context"],
                         dict(recent=1, pins=[], hints=[dict(seq=1, question="原问", answer="原答")]))
        self.assertEqual(recent["selected"][0]["text"], "原答")
        d.ok(d.post(d.pc, "chat-preferences", **self.target, expected=dict(recentTurns=1), changes=dict(recentTurns=None)))
        probe = d.ok(d.post(self.phone, "context-preview", **self.q))
        self.assertTrue(probe["supported"])
        self.assertFalse(probe["canSend"])
        self.assertEqual(probe["selected"], [])
        self.assertEqual(self.second.calls, [])

    def test_actual_send_rejection_is_durable_and_does_not_create_a_turn(self):
        d = self.d
        self.second.admission_mode = "reject"
        revision = d.ok(d.post(self.phone, "context-preview", **self.q))["revision"]
        body = dict(**self.submission, revision=revision)
        status, receipt = d.post(self.phone, "send", **body)
        self.assertEqual((status, receipt["contextState"], receipt["id"]), (202, "pending", body["id"]))
        self.assertTrue(self.second.send_started.wait(3))
        self.assertEqual(d.ok(d.get(self.phone,"operation",id=body["id"])), receipt)
        self.assertEqual(d.ok(d.post(self.phone,"send",**body)), receipt)
        self.assertEqual(len(d.ok(d.get(self.phone,"turns", **self.target))["turns"]), 1)
        self.assertEqual(d.post(self.phone,"send",**(body | dict(id=fixture.uid())))[0], 409)
        self.second.release_admission.set()
        rejected = self.operation(lambda status, _: status == 409)
        self.assertEqual(rejected[1]["error"], "上游已确认本次上下文未派发，请重新预览。")
        self.assertEqual(rejected[1]["code"], "context_not_dispatched")
        d.host.restart()
        self.assertEqual(d.get(self.phone,"operation",id=body["id"]), rejected)
        self.assertEqual(d.post(self.phone,"send",**body), rejected)
        self.assertEqual(d.get(d.pc,"operation",id=body["id"])[0], 404)
        self.assertEqual(self.second.send_attempts, [body["id"]])
        self.assertEqual(self.second.calls, [])
        self.assertEqual(len(d.ok(d.get(self.phone,"turns", **self.target))["turns"]), 1)
        self.second.admission_mode = "accept"
        fresh_id = fixture.uid()
        fresh = d.ok(d.post(self.phone, "context-preview", **self.q))
        d.ok(d.post(self.phone, "send", **(body | dict(id=fresh_id, revision=fresh["revision"]))))
        self.submission["id"] = fresh_id
        self.operation(lambda status, r: status == 200 and "turn" in r)
        d.wait_turn(self.phone,self.profile,"chat-42",fresh_id)
        self.assertEqual(self.second.send_attempts, [body["id"], fresh_id])

    def test_uncertain_header_and_restart_are_never_replayed(self):
        d = self.d
        self.second.admission_mode = "disconnect"
        revision = d.ok(d.post(self.phone, "context-preview", **self.q))["revision"]
        body = dict(**self.submission, revision=revision)
        d.ok(d.post(self.phone, "send", **body))
        self.assertTrue(self.second.send_started.wait(3))
        d.host.restart()
        self.second.release_admission.set()
        status, receipt = self.operation(lambda _, r: r.get("turn", {}).get("status") == "unknown")
        self.assertEqual(status, 200)
        self.assertEqual(d.ok(d.post(self.phone, "send", **body)), receipt)
        self.assertEqual(self.second.send_attempts, [body["id"]])

    def test_previously_accepted_adapter_conflict_is_not_a_rejection(self):
        d = self.d
        self.second.admission_mode = "already_accepted"
        self.second.release_admission.set()
        revision = d.ok(d.post(self.phone, "context-preview", **self.q))["revision"]
        body = dict(**self.submission, revision=revision)
        d.ok(d.post(self.phone,"send",**body))
        _, receipt = self.operation(lambda _, r: r.get("turn", {}).get("status") == "unknown")
        self.assertEqual(d.ok(d.post(self.phone, "send", **body)), receipt)
        self.assertEqual(self.second.send_attempts, [body["id"]])

    def test_legacy_room_cannot_bypass_custom_mode(self):
        d = self.d
        profile = d.profile(d.pc, "A")
        grant = d.ok(d.public.request("create", dict(code=d.host.pair(), sourceBase=d.first.base,
                         username="alice", session="chat-42", history=[dict(question="问", answer="答", status="complete")])))
        legacy = fixture.Client(d.host.port, grant["token"])
        target = dict(profile=profile, sid="chat-42")
        d.history(d.pc, **target, legacyToken=grant["token"])
        d.upload(d.pc, profile, "chat-42", "问", "答")
        self.configure(target)
        self.assertEqual(legacy.request("send", dict(id=fixture.uid(), question="旧客户端"))[0], 409)
        self.assertEqual(d.first.calls, [])
        d.ok(d.post(d.pc, "chat-preferences", **target, expected=dict(recentTurns=0, pins=[dict(seq=1,role="assistant")]),
                    changes=dict(recentTurns=None, pins=[])))
        turn = d.ok(legacy.request("send", dict(id=fixture.uid(), question="默认发送")))
        d.wait_turn(d.pc, profile, "chat-42", turn["id"])
        self.assertEqual(d.first.calls[0][0], "/fixture/api/v1/chat")
        self.assertNotIn("context", d.first.calls[0][2])
