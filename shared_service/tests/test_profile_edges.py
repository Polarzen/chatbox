"""Exercise profile ownership and recovery through the same wire and CLI as the clients."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import json
import threading
import unittest
from unittest.mock import patch

from shared_service import service

if __package__:
    from . import test_launcher as launcher_fixture
    from . import test_profile_service as fixture
    from .test_service import ReplyHandler, uid
else:
    import test_launcher as launcher_fixture
    import test_profile_service as fixture
    from test_service import ReplyHandler, uid


class ProfileEdgeTests(unittest.TestCase):
    def setUp(self):
        self.wire = fixture.ProfileServiceTests()
        self.wire.setUp()
        self.addCleanup(self.wire.doCleanups)
        self.wire.start()

    def test_late_attachment_alias_one_profile_and_explicit_restore(self):
        w = self.wire
        sid = "chat-42"
        seed = dict(question="原问", answer="原答", status="complete")
        suffix = dict(question="原共享房间后缀", answer="内容", status="complete")
        legacy = w.ok(w.public.request("create", dict(code=w.service.pair(), sourceBase=w.upstream.base,
                           username="alice", session=sid, history=[seed, suffix])))
        pc, _ = w.create_profile()
        other, _ = w.create_profile("B")
        local = dict(seed, id=uid())
        w.publish(pc, sid, "原会话", 1, 1)
        w.ok(w.import_page(pc, sid, 0, [local]))
        first = w.turns(pc, sid)["turns"][0]
        self.assertEqual(first["id"], local["id"])
        w.publish(pc, sid, "原会话", 1, 1, legacyToken=legacy["token"])
        linked = w.turns(pc, sid)["turns"]
        self.assertEqual(len(linked), 2)
        self.assertNotEqual(linked[0]["id"], local["id"])
        self.assertEqual(w.ok(pc.request("profile/turn?sid=" + sid + "&id=" + local["id"])), first)
        foreign = fixture.turn("B 的本地问", "B 的本地答")
        self.assertTrue(w.publish(other, sid, "B 的记录", 1, 1, legacyToken=legacy["token"])["reason"])
        w.ok(w.import_page(other, sid, 0, [foreign]))
        w.ok(pc.request("profile/remove", {"sid": sid}))
        w.service.restart()
        self.assertTrue(w.publish(other, sid, "B 的记录", 1, 1, legacyToken=legacy["token"])["reason"])
        self.assertEqual(w.turns(other, sid)["turns"], [dict(seq=1, **foreign)])
        self.assertEqual(other.request("profile/turn?sid=" + sid + "&id=" + linked[1]["id"])[0], 404)
        w.denied(other.request("profile/send", dict(sid=sid, id=uid(), question="无法接续")))
        body = dict(sid=sid, title="还原的原会话", activity=2, total=1)
        self.assertEqual(pc.request("profile/session", body)[0], 409)
        self.assertEqual(w.directory(pc)["sessions"], [])
        w.ok(pc.request("profile/session", dict(body, restore=True)))
        self.assertEqual(w.turns(pc, sid)["turns"], linked)
        self.assertEqual(w.upstream.messages(), [])

    def test_delayed_publication_cannot_undo_removal(self):
        w = self.wire
        pc, _ = w.create_profile()
        sid = "chat-42"
        body = dict(sid=sid, title="待发布", activity=1, total=1)
        reached, release = threading.Event(), threading.Event()
        original = ReplyHandler.do_GET

        def wait_for_removal(handler):
            reached.set()
            release.wait(4)
            original(handler)

        with patch.object(ReplyHandler, "do_GET", wait_for_removal), ThreadPoolExecutor(1) as jobs:
            for explicit in (False, True):
                reached.clear()
                release.clear()
                pending = jobs.submit(pc.request, "profile/session", dict(body, restore=explicit))
                try:
                    self.assertTrue(reached.wait(3))
                    w.ok(pc.request("profile/remove", {"sid": sid}))
                finally:
                    release.set()
                self.assertEqual(pending.result()[0], 409)
        self.assertEqual(w.directory(pc)["sessions"], [])
        self.assertEqual(pc.request("profile/session", body)[0], 409)
        self.assertEqual(pc.request("profile/turns?sid=" + sid)[0], 404)

    def test_origin_restores_and_rejected_first_send_can_retry(self):
        w = self.wire
        pc, _ = w.create_profile()
        w.upstream.expected_key = "temporarily-rejected"
        self.assertTrue(w.publish(pc, "chat-42", "旧记录", 1, 1)["reason"])
        w.ok(w.import_page(pc, "chat-42", 0, [fixture.turn("历史", "内容")]))
        w.upstream.expected_key = "fixture-key"
        self.assertEqual(w.publish(pc, "chat-42", "旧记录", 1, 1)["reason"], "")
        w.publish(pc, "new-empty", "新建", 0, 0)
        first = dict(sid="new-empty", id=uid(), question="首次发送")

        def reject(handler):
            handler.rfile.read(int(handler.headers["Content-Length"]))
            handler.send_error(403)

        with patch.object(ReplyHandler, "do_POST", reject):
            w.ok(pc.request("profile/send", first))
            w.wait_turn(pc, first["sid"], first["id"], "unknown")
        w.service.restart()
        self.assertEqual(w.publish(pc, "new-empty", "新建", 0, 0)["reason"], "")
        self.assertEqual(w.ok(pc.request("profile/send", first))["status"], "unknown")
        second = dict(first, id=uid(), question="再次明确发送")
        w.ok(pc.request("profile/send", second))
        w.wait_turn(pc, second["sid"], second["id"], "complete")
        # The fixture doesn't create an upstream index row. Once the first dispatch succeeded,
        # disappearance must block creating a new context under that same identifier.
        self.assertEqual(pc.request("profile/send", dict(second, id=uid()))[0], 409)
        self.assertEqual(w.upstream.messages(), [second["question"]])

    def test_deleted_originally_empty_session_cannot_be_recreated(self):
        w = self.wire
        pc, _ = w.create_profile()
        self.assertEqual(w.publish(pc, "chat-42", "原先已存在的空会话", 0, 0)["reason"], "")

        def empty(handler):
            data = json.dumps(dict(status="ok", data=dict(page=1, page_size=100, total=0, sessions=[]))).encode()
            handler.send_response(200)
            handler.send_header("Content-Length", str(len(data)))
            handler.end_headers()
            handler.wfile.write(data)

        with patch.object(ReplyHandler, "do_GET", empty):
            self.assertTrue(w.publish(pc, "chat-42", "原先已存在的空会话", 0, 0)["reason"])
            self.assertEqual(pc.request("profile/send", dict(sid="chat-42", id=uid(), question="已失效"))[0], 409)
        self.assertEqual(w.upstream.messages(), [])

    def test_lost_creation_response_recover_only_matching_original(self):
        w = self.wire
        body = w.create_body(w.service.cli("profile-bootstrap"))
        info = w.ok(w.public.request("profile/create", body))
        pc = fixture.Client(w.service.port, info["token"])
        guest = w.pair(pc, info)
        w.publish(pc, "chat-42", "记录", 0, 1)
        saved = fixture.turn("PC 历史", "原答")
        w.ok(w.import_page(pc, "chat-42", 0, [saved]))
        self.assertEqual(json.loads(w.service.cli("profiles"))["id"], info["profile"])
        recovery = w.service.cli("profile-recover", "--profile", info["profile"])
        self.assertEqual(w.public.request("profile/join", dict(code=recovery))[0], 403)
        self.assertEqual(w.public.request("profile/create", dict(body, code=recovery, sourceId=uid()))[0], 403)
        self.assertEqual(w.directory(pc)["sessions"], w.directory(guest)["sessions"])
        restored = w.ok(w.public.request("profile/create", dict(body, code=recovery)))
        self.assertEqual((restored["profile"], restored["owner"]), (info["profile"], True))
        self.assertNotEqual(restored["token"], info["token"])
        self.assertEqual(pc.request("profile/sessions")[0], 401)
        self.assertEqual(w.turns(guest, "chat-42")["turns"], [dict(seq=1, **saved)])
        self.assertEqual(w.public.request("profile/create", dict(body, code=recovery))[0], 403)


class LauncherProfileRecoveryTests(unittest.TestCase):
    original = launcher_fixture.URL
    moved = "http://127.0.0.1:24000"
    source_id = "launcher:" + launcher_fixture.FIRST

    def setUp(self):
        # Exercise the actual UUID/port resolver with its existing simulated OS
        # process tree, listeners and Dashboard. The HTTP service and CLI use SQLite.
        self.system = launcher_fixture.LauncherTests()
        self.system.setUp()
        self.addCleanup(self.system.doCleanups)
        self.host = fixture.ServiceProcess(self, self.original)

    def start(self, aliases=(), historical=()):
        service.initialize(self.host.db, self.original, aliases, launcher_bound=True)
        if historical:
            with service.database(self.host.db, write=True) as db:
                db.execute("ALTER TABLE profiles DROP COLUMN launcher_id")
                db.executemany("INSERT INTO profiles(id,source_id,base,username,name) VALUES(?,?,?,'alice','PC')",
                               historical)
        stack = ExitStack()
        self.addCleanup(stack.close)
        server = stack.enter_context(service.open_server(self.host.db, "fixture-key", 0))
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        self.public = fixture.Client(server.server_port)

    def move(self):
        self.system.owners.update({18759: 50, 24000: 30})

    def request(self, code, base, source=None, username="alice"):
        return self.public.request("profile/create", dict(code=code, sourceBase=base, username=username,
                                                          sourceId=source or self.source_id, name="PC"))

    def test_verified_profile_moves_without_duplicate_or_access_from_reused_port(self):
        self.start(aliases=(self.original, self.moved))
        status, first = self.request(self.host.cli("profile-bootstrap"), self.original)
        self.assertEqual(status, 200)
        owner = fixture.Client(self.public.port, first["token"])
        _, invite = owner.request("profile/invite", {})
        _, phone = self.public.request("profile/join", dict(code=invite["code"]))
        code = self.host.cli("profile-recover", "--profile", first["profile"])
        self.move()
        self.assertEqual(self.request(self.host.cli("profile-bootstrap"), self.moved)[0], 409)
        self.assertEqual(self.request(code, self.original)[0], 409)
        self.assertEqual(self.request(code, self.original, source="launcher:" + launcher_fixture.SECOND)[0], 409)
        self.assertEqual(self.request(code, self.moved, username="other")[0], 403)
        del self.system.owners[24000]
        self.assertEqual(self.request(code, self.moved)[0], 409)
        self.system.owners[24000] = 30
        status, recovered = self.request(code, self.moved)
        self.assertEqual(status, 200)
        self.assertEqual(recovered["profile"], first["profile"])
        self.assertEqual(json.loads(self.host.cli("profiles"))["base"], self.moved)
        self.assertEqual(owner.request("profile/sessions")[0], 401)
        self.assertEqual(fixture.Client(self.public.port, phone["token"]).request("profile/sessions")[0], 200)
        self.assertEqual(self.request(code, self.moved)[0], 403)

    def test_existing_verified_registration_migrates_but_manual_source_stays_exact(self):
        original, manual = uid(), uid()
        manual_source = "manual:" + self.original
        self.start(historical=((original, self.source_id, self.original), (manual, manual_source, self.original)))
        self.move()
        code = self.host.cli("profile-recover", "--profile", manual)
        self.assertEqual(self.request(code, self.moved, source=manual_source)[0], 403)
        code = self.host.cli("profile-recover", "--profile", original)
        status, recovery = self.request(code, self.moved)
        self.assertEqual(status, 200)
        self.assertEqual(recovery["profile"], original)

    def test_historical_alias_without_identity_proof_cannot_move(self):
        original = uid()
        self.start(aliases=(self.original,), historical=((original, self.source_id, self.original),))
        self.move()
        code = self.host.cli("profile-recover", "--profile", original)
        self.assertEqual(self.request(code, self.moved)[0], 403)
        self.assertEqual(self.request(code, self.original)[0], 409)
        self.assertEqual(json.loads(self.host.cli("profiles"))["base"], self.original)
        # If the original UUID runs at the historical address again, exact recovery
        # can now record positive proof for a subsequent relocation.
        self.system.owners.clear()
        self.system.owners[18759] = 30
        self.assertEqual(self.request(code, self.original)[0], 200)
        self.move()
        code = self.host.cli("profile-recover", "--profile", original)
        self.assertEqual(self.request(code, self.moved)[0], 200)

    def test_recovery_preserves_both_existing_profiles_if_target_base_is_occupied(self):
        old, other = uid(), uid()
        self.start(historical=((old, self.source_id, self.original), (other, self.source_id, self.moved)))
        self.move()
        code = self.host.cli("profile-recover", "--profile", old)
        self.assertEqual(self.request(code, self.moved)[0], 409)
        catalog = {p["id"]: p["base"] for p in map(json.loads, self.host.cli("profiles").splitlines())}
        self.assertEqual(catalog, {old: self.original, other: self.moved})
