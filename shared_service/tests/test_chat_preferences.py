"""Cross-device preference updates and reminder claims over the device HTTP API."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import secrets
import unittest

from shared_service import chat_preferences, device_runtime, service
from shared_service.device_gateway import Gateway

if __package__:
    from . import test_device_service as device_tests
else:
    import test_device_service as device_tests


DEFAULT = dict(profileName=None, peerName=None, recentTurns=None, pins=[], remindAfter=None)


class ChatPreferencesTests(unittest.TestCase):
    def setUp(self):
        self.devices = d = device_tests.DeviceServiceTests()
        self.addCleanup(d.doCleanups)
        d.setUp()
        self.pc = d.pc
        phone, self.phone_id = d.guest()
        stack = ExitStack()
        self.addCleanup(stack.close)
        gate = stack.enter_context(Gateway(0, d.host.port, device_runtime.PUBLIC_ROUTES, device_runtime.PROBE,
                                           secrets.token_hex(32), service.MAX_BODY))
        stack.enter_context(device_runtime.serving(gate))
        self.phone = device_tests.Client(gate.server_port, phone.token)
        self.profile = d.profile(self.pc, "A")
        self.target = dict(profile=self.profile, sid="chat-42")
        d.history(self.pc, **self.target)

    def read(self, client, target=None):
        return self.devices.get(client, "chat-preferences", **(target or self.target))

    def update(self, client, expected, changes, target=None):
        return self.devices.post(client, "chat-preferences", **(target or self.target),
                                 expected=expected, changes=changes)

    def claim(self, client, threshold, target=None):
        return self.devices.post(client, "claim-context-reminder", **(target or self.target), threshold=threshold)

    def notice(self, client, kind, through, active, target=None):
        return self.devices.post(client, "context-notice", **(target or self.target),
                                 kind=kind, through=through, active=active)

    def test_cas_profile_scope_and_restore(self):
        d = self.devices
        self.assertEqual(self.read(self.phone), (200, DEFAULT))
        with ThreadPoolExecutor(2) as pool:
            first = pool.submit(self.update, self.pc, DEFAULT, {"profileName": "  阿青  "})
            second = pool.submit(self.update, self.phone, DEFAULT, {"peerName": "阿青"})
            self.assertEqual((first.result()[0], second.result()[0]), (200, 200))
        saved = DEFAULT | dict(profileName="阿青", peerName="阿青")
        self.assertEqual(self.read(self.pc), (200, saved))
        self.assertEqual(self.update(self.phone, DEFAULT, {"profileName": "阿青"}), (200, saved))
        self.assertEqual(self.update(self.phone, DEFAULT, dict(recentTurns=8, profileName="覆盖"))[0], 409)
        self.assertEqual(self.read(self.pc), (200, saved))
        other = dict(profile=self.profile, sid="chat-43")
        d.history(self.pc, **other)
        self.assertEqual(self.read(self.phone, other), (200, DEFAULT | dict(profileName="阿青")))
        self.assertEqual(self.update(self.phone, saved, {"profileName": None}, other), (200, DEFAULT))
        self.assertEqual(self.read(self.pc), (200, DEFAULT | dict(peerName="阿青")))
        self.assertEqual(self.update(self.phone, {}, {"recentTurns": 2})[0], 400)
        self.assertEqual(self.update(self.phone, {"x": None}, {"x": 3})[0], 400)
        for value in ("", "a" * 41, "a\x7fb", "\tbad", 3):
            self.assertEqual(self.update(self.phone, saved, {"peerName": value})[0], 400)
        self.assertEqual(self.update(self.phone, saved, {"peerName": "🙂" * 40})[0], 200)

    def test_pin_roles_transcript_sources_and_server_bounds(self):
        d = self.devices
        user = dict(seq=1, role="user")
        bot = dict(seq=1, role="assistant")
        for changes in (dict(pins=[user]), dict(recentTurns=0, pins=[user]),
                        dict(recentTurns=True), dict(recentTurns=501), dict(recentTurns=-1),
                        dict(remindAfter=10001), dict(remindAfter=0), dict(remindAfter=False)):
            self.assertEqual(self.update(self.pc, DEFAULT, changes)[0], 400)
        d.upload(self.pc, self.profile, "chat-42", "问", "答")
        updated = DEFAULT | dict(recentTurns=0, pins=[user, bot])
        self.assertEqual(self.update(self.phone, DEFAULT, dict(recentTurns=0, pins=[bot, user, bot])), (200, updated))
        with service.database(d.host.db) as db:
            room = db.execute("SELECT room FROM profile_sessions WHERE profile=? AND sid=?",
                              (self.profile, "chat-42")).fetchone()[0]
            self.assertEqual(chat_preferences.preferences_for_room(db, room), updated)
            self.assertEqual(chat_preferences.preferences_for_room(db, "another-room"), DEFAULT)
        for invalid in ([dict(seq=1, role="system")], [dict(seq=2, role="user")],
                        [dict(seq=True, role="assistant")], [user] * 129):
            self.assertEqual(self.update(self.phone, updated, {"pins": invalid})[0], 400)
        self.assertEqual(self.update(self.phone, updated, {"recentTurns": None})[0], 400)
        self.assertEqual(self.update(self.phone, updated, dict(recentTurns=None, pins=[])), (200, DEFAULT))

        # This source has an uploaded prefix but no authoritative upstream room.
        target = dict(profile=d.profile(self.phone, base="https://offline.invalid", username="bob"), sid="offline")
        d.ok(d.post(self.phone, "session", **target, title="离线", activity=1, total=3))
        turns = [dict(id=device_tests.uid(), question="问", answer=answer, status=status)
                 for answer, status in (("", "complete"), ("部分", "unknown"))]
        d.ok(d.post(self.phone, "import", **target, start=0, turns=turns))
        for pin in (bot, dict(seq=2, role="assistant"), dict(seq=3, role="user")):
            self.assertEqual(self.update(self.phone, DEFAULT, dict(recentTurns=500, pins=[pin]), target)[0], 400)
        self.assertEqual(self.update(self.phone, DEFAULT, dict(recentTurns=500, pins=[user]), target)[0], 200)
        d.ok(self.update(self.pc, DEFAULT, {"remindAfter": 2}, target))
        self.assertEqual(self.claim(self.pc, 2, target), (200, {"claimed": False}))

    def test_claim_once_concurrently_after_completed_turn_and_after_restart(self):
        d = self.devices
        d.ok(self.update(self.pc, DEFAULT, {"remindAfter": 1}))
        self.assertEqual(self.claim(self.phone, 1), (200, {"claimed": False}))
        d.upload(self.pc, self.profile, "chat-42", "问", "答")
        self.assertEqual(self.claim(self.phone, 2), (200, {"claimed": False}))
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda client: d.ok(self.claim(client, 1))["claimed"], (self.pc, self.phone)))
        self.assertEqual(sorted(results), [False, True])
        d.host.restart()
        d.ok(self.update(self.phone, DEFAULT, {"peerName": "对方"}))
        self.assertEqual(self.claim(self.phone, 1), (200, {"claimed": False}))

    def test_context_notice_crossings_and_stale_observations(self):
        d = self.devices
        silent = (200, {"claimed": False})
        claimed = (200, {"claimed": True})
        self.assertEqual(self.notice(self.phone, "capacity", 0, True), silent)
        saved = d.ok(self.update(self.pc, DEFAULT, {"recentTurns": 0}))
        for args in (("capacity", -1, True), ("other", 0, True), ("rolling", True, True), ("rolling", 0, 1)):
            self.assertEqual(self.notice(self.phone, *args)[0], 400)
        self.assertEqual(self.notice(self.pc, "capacity", 1, True), silent)
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda c: d.ok(self.notice(c, "capacity", 0, True))["claimed"], (self.pc, self.phone)))
        self.assertEqual(sorted(results), [False, True])
        self.assertEqual(self.notice(self.phone, "rolling", 0, True), claimed)
        self.assertEqual(self.notice(self.phone, "capacity", 0, False), silent)
        self.assertEqual(self.notice(self.pc, "capacity", 0, True), silent)
        d.host.restart()
        d.ok(self.update(self.phone, saved, {"recentTurns": 0, "peerName": "对方"}))
        self.assertEqual(self.notice(self.phone, "rolling", 0, True), silent)

        d.upload(self.pc, self.profile, "chat-42", "第一问", "第一答")
        self.assertEqual(self.notice(self.pc, "capacity", 0, False), silent)
        self.assertEqual(self.notice(self.phone, "capacity", 1, True), silent)
        d.ok(d.post(self.pc, "session", **self.target, title="会话", activity=2, total=3))
        later = [dict(id=device_tests.uid(), question="问", answer="答", status=status)
                 for status in ("unknown", "complete")]
        d.ok(d.post(self.pc, "import", **self.target, start=1, turns=later))
        self.assertEqual(self.notice(self.phone, "capacity", 3, False), silent)
        self.assertEqual(self.notice(self.pc, "capacity", 2, True), silent)
        self.assertEqual(self.notice(self.pc, "capacity", 3, True), claimed)
        self.assertEqual(self.notice(self.phone, "capacity", 3, True), silent)
        self.assertEqual(self.notice(self.phone, "rolling", 3, True), silent)
        saved = d.ok(self.update(self.pc, saved, {"pins": [dict(seq=1, role="user")]}))
        self.assertEqual(self.notice(self.phone, "capacity", 3, True), claimed)
        d.ok(self.update(self.pc, saved, {"recentTurns": 1}))
        self.assertEqual(self.notice(self.pc, "capacity", 3, True), claimed)

    def test_device_session_access_for_all_operations(self):
        d = self.devices
        private = dict(profile=d.profile(self.phone, username="bob"), sid="private")
        d.history(self.phone, **private)
        outsider, _ = d.guest("other")
        for client, target, status in ((outsider, private, 404), (d.public, self.target, 401)):
            self.assertEqual(self.read(client, target)[0], status)
            self.assertEqual(self.update(client, DEFAULT, dict(remindAfter=1), target)[0], status)
            self.assertEqual(self.claim(client, 1, target)[0], status)
            self.assertEqual(self.notice(client, "capacity", 0, True, target)[0], status)
        d.ok(self.update(self.phone, DEFAULT, dict(remindAfter=1), private))
        d.ok(d.post(self.pc, "remove", **private))
        self.assertEqual(self.read(self.pc, private)[0], 404)
        self.assertEqual(self.update(self.phone, DEFAULT, dict(peerName="不可见"), private)[0], 404)
        self.assertEqual(self.claim(self.pc, 1, private)[0], 404)
        self.assertEqual(self.notice(self.pc, "capacity", 0, True, private)[0], 404)
        d.ok(d.post(self.pc, "revoke", device=self.phone_id))
        self.assertEqual(self.read(self.phone)[0], 401)
        self.assertEqual(self.update(self.phone, DEFAULT, dict(remindAfter=1))[0], 401)
        self.assertEqual(self.claim(self.phone, 1)[0], 401)
        self.assertEqual(self.notice(self.phone, "rolling", 0, True)[0], 401)
