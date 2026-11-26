import json
import sqlite3
import threading
import time
import unittest
from urllib.parse import parse_qs, urlsplit

if __package__:
    from . import test_device_service as devices
    from . import test_service as fixture
else:
    import test_device_service as devices
    import test_service as fixture

from shared_service import device_inbox


class InboxHandler(fixture.ReplyHandler):
    def do_GET(self):
        url = urlsplit(self.path)
        if url.path != "/fixture/api/v1/chatbox/inbox":
            return super().do_GET()
        if self.headers.get("Authorization") != "Bearer fixture-key":
            return self.send_error(403)
        params = parse_qs(url.query)
        self.server.queries.append(params)
        self.server.fetching.set()
        self.server.deliver.wait(5)
        if self.server.failure:
            return self.send_error(self.server.failure)
        rows = [r for r in self.server.incoming if r["id"] > int(params["after"][0])]
        data = json.dumps(dict(status="ok", data=dict(messages=rows, more=False))).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class DeviceInboxTests(unittest.TestCase):
    def setUp(self):
        self.d = d = devices.DeviceServiceTests()
        self.addCleanup(d.doCleanups)
        d.setUp()
        self.bot = d.first
        self.bot.RequestHandlerClass = InboxHandler
        self.bot.fetching = threading.Event()
        self.bot.deliver = threading.Event()
        self.addCleanup(self.bot.deliver.set)
        self.bot.queries = []
        self.bot.incoming = [dict(id=81, text="第一条主动消息"), dict(id=94, text="第二条主动消息")]
        self.bot.failure = 0
        self.profile = d.profile(d.pc, "A")
        self.target = dict(profile=self.profile, sid="chat-42")
        d.history(d.pc, **self.target)
        self.phone, self.phone_id = d.guest()

    def status(self):
        with sqlite3.connect(self.d.host.db) as db:
            return db.execute("SELECT cursor,status FROM device_inbox_state").fetchone()

    def until(self, action):
        deadline = time.monotonic()+5
        while time.monotonic() < deadline:
            result = action()
            if result:
                return result
            time.sleep(.02)
        self.fail("expected inbox state not reached")

    def test_imported_ids_resume_on_both_devices_without_sending(self):
        d = self.d
        # An inbox message already observed in native mode before the device was paired.
        native = dict(id=device_inbox.turn_id("chat-42", 81), question="", answer=self.bot.incoming[0]["text"],
                      status="complete")
        d.ok(d.post(d.pc, "import", **self.target, start=0, turns=[native]))
        first = d.ok(d.get(self.phone, "sessions"))
        self.assertTrue(self.bot.fetching.wait(2))
        # A blocked upstream inbox never blocks reading or fabricates a user question.
        self.assertEqual(d.ok(d.get(d.pc, "turns", **self.target))["turns"], [dict(seq=1, **native)])
        self.bot.deliver.set()
        self.until(lambda: self.status() == (94, "available"))
        self.assertEqual(self.bot.queries, [dict(after=["0"], limit=["50"], username=["alice"], session_id=["chat-42"])])
        on_pc = d.ok(d.get(d.pc, "turns", **self.target))["turns"]
        self.assertEqual(on_pc, d.ok(d.get(self.phone, "turns", **self.target))["turns"])
        self.assertEqual([r["answer"] for r in on_pc], [r["text"] for r in self.bot.incoming])
        self.assertEqual([r["question"] for r in on_pc], ["", ""])
        self.assertGreater(d.ok(d.get(self.phone, "sessions"))["revision"], first["revision"])
        self.assertEqual(self.bot.messages(), [])
        self.bot.incoming.append(dict(id=103, text="离线期间发出"))
        d.host.restart(clock_offset=10)
        d.ok(d.get(self.phone, "sessions"))
        self.until(lambda: self.status() == (103, "available"))
        self.assertEqual(self.bot.queries[-1]["after"], ["94"])
        self.assertEqual(len(d.ok(d.get(self.phone, "turns", **self.target))["turns"]), 3)

    def test_denied_or_malformed_does_not_advance_or_hide_chat(self):
        d = self.d
        d.upload(d.pc, self.profile, "chat-42", "原问", "原答")
        self.bot.failure = 403
        self.bot.deliver.set()
        d.ok(d.get(self.phone, "turns", **self.target))
        self.until(lambda: self.status() == (0, "denied"))
        row = d.ok(d.get(d.pc, "sessions"))["sessions"][0]
        self.assertEqual((row["inboxStatus"], row["reason"]), ("denied", ""))
        self.bot.failure = 0
        self.bot.incoming.reverse()
        d.host.restart(clock_offset=62)
        d.ok(d.get(self.phone, "sessions"))
        self.until(lambda: self.status() == (0, "unavailable"))
        self.assertEqual(len(d.ok(d.get(d.pc, "turns", **self.target))["turns"]), 1)

    def test_revoked_midflight_drops_reply(self):
        d = self.d
        d.upload(d.pc, self.profile, "chat-42", "原问", "原答")
        d.ok(d.get(self.phone, "sessions"))
        self.assertTrue(self.bot.fetching.wait(2))
        d.ok(d.post(d.pc, "revoke", device=self.phone_id))
        self.bot.deliver.set()
        self.assertEqual(d.get(self.phone, "turns", **self.target)[0], 401)
        time.sleep(.1)
        with sqlite3.connect(d.host.db) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM turns").fetchone()[0], 1)
            self.assertFalse(db.execute("SELECT 1 FROM device_inbox_state").fetchone())
