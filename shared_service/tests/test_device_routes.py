"""Route migration, local provisioning, credential separation and Launcher re-resolution."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from shared_service import device_routes, device_runtime, device_service, launcher, service

if __package__:
    from .test_service import ReplyHandler, Upstream, uid
else:
    from test_service import ReplyHandler, Upstream, uid


class RootReplyHandler(ReplyHandler):
    def do_POST(self):
        self.path = "/fixture" + self.path
        super().do_POST()


class DeviceRouteTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "routes.sqlite"

    def bot(self, key, *, launcher_root=False):
        bot = Upstream()
        bot.expected_key = key
        if launcher_root:
            bot.RequestHandlerClass = RootReplyHandler
            bot.base = bot.base.removesuffix("/fixture")
        thread = threading.Thread(target=bot.serve_forever, kwargs={"poll_interval": 0.01})
        thread.start()
        self.addCleanup(bot.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(bot.shutdown)
        return bot

    def provision(self, source, base, key, **extra):
        return device_routes.provision_upstream(self.path, dict(sourceId=source, base=base, key=key, **extra))["upstream"]

    def test_legacy_fk_migration_is_atomic_and_repeatable(self):
        fixture = Path(__file__).with_name("fixtures") / "service_0_1_17.sql"
        instance, room, profile, turn = uid(), uid(), uid(), uid()
        with closing(sqlite3.connect(self.path)) as db, db:
            db.executescript(fixture.read_text(encoding="utf8"))
            service.migrate_profiles(db)
            db.execute("INSERT INTO config VALUES(?,'https://example.test',NULL)", (instance,))
            db.execute("INSERT INTO rooms VALUES(?,'a','sid')", (room,))
            db.execute("INSERT INTO turns VALUES(?,1,?,'question','answer','complete',0)", (room, turn))
            db.execute("INSERT INTO devices VALUES(?,?,?,1)", (uid(), service.digest("room-owner"), room))
            db.execute("INSERT INTO devices VALUES(?,?,?,0)", (uid(), service.digest("orphan"), "absent"))
            db.execute("INSERT INTO profiles(id,source_id,base,username,name) VALUES(?,'s','https://example.test','a','n')",
                       (profile,))
            db.execute("INSERT INTO profile_devices VALUES(?,?,?,'owner',1)", (uid(), service.digest("pc"), profile))
            db.execute("INSERT INTO profile_sessions(profile,sid,title,activity,total,room) VALUES(?,'sid','t',1,0,?)",
                       (profile, room))
        with self.assertRaises(sqlite3.IntegrityError):
            service.migrate_database(self.path)
        with service.database(self.path, write=True) as db:
            self.assertNotIn("upstream", [r[1] for r in db.execute("PRAGMA table_info(rooms)")])
            self.assertIsNone(db.execute("SELECT 1 FROM sqlite_master WHERE name='route_upstreams'").fetchone())
            db.execute("DELETE FROM devices WHERE digest=?", (service.digest("orphan"),))
        service.migrate_database(self.path)
        service.migrate_database(self.path)
        with service.database(self.path, write=True) as db:
            self.assertIsNone(db.execute("PRAGMA foreign_key_check").fetchone())
            self.assertEqual(service.authorize(db, "room-owner", owner=True), room)
            self.assertEqual(service.profile_auth(db, "pc", owner=True), profile)
            self.assertEqual(service.get_member(db, profile, "sid")["room"], room)
            self.assertEqual(service.find_turn(db, room, turn)["answer"], "answer")
            self.assertEqual(device_routes.DeviceRoutes.find_room(db, instance, "a", "sid"), room)
            db.execute("INSERT INTO rooms VALUES(?,'b','sid',?,0)", (uid(), instance))
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO rooms VALUES(?,'a','sid',?,0)", (uid(), instance))
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO rooms VALUES(?,'a','sid','absent',0)", (uid(),))

    def test_registry_cli_rotates_only_its_own_key(self):
        service.initialize(self.path, "https://default.example")
        script = Path(service.__file__).resolve()
        args = [sys.executable, script, "--db", str(self.path), "device-upstream"]
        key = "test-" + uid()
        body = dict(sourceId="manual:test", base="https://separate.example", key=key)
        first = subprocess.run(args, input=json.dumps(body).encode(), capture_output=True, check=True)
        second = subprocess.run(args, input=json.dumps(dict(body, key="updated-" + uid())).encode(),
                                capture_output=True, check=True)
        self.assertEqual(json.loads(first.stdout), json.loads(second.stdout))
        self.assertNotIn(key.encode(), first.stdout + first.stderr)
        if os.name == "nt":
            self.assertNotIn(key.encode(), self.path.read_bytes())
        else:
            self.assertEqual(self.path.stat().st_mode & 0o077, 0)
        with self.assertRaises(service.ApiError):
            self.provision("manual:test", "https://different.example", key)

    def test_reviewed_phone_aliases_survive_018_migration_reregistration_and_restart(self):
        bot = self.bot("local-key")
        default = uid()
        legacy = "https://existing.example/bot"
        first, second = "https://phone-one.example/bot", "https://phone-two.example"
        fixture = Path(__file__).with_name("fixtures") / "service_0_1_17.sql"
        self.path.touch(mode=0o600)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.executescript(fixture.read_text(encoding="utf8"))
            service.migrate_profiles(db)
            db.execute("INSERT INTO config VALUES(?,?,NULL)", (default, bot.base))
            db.execute("INSERT INTO sources VALUES(?)", (legacy,))

        root = self.path.parent
        device_runtime.write_json(root / "config.json", {"dbPath": str(self.path)})

        def register(**options):
            result = device_runtime.dispatch(root, dict(action="upstream", sourceId="pc", base=bot.base,
                                                        key="local-key", **options))
            self.assertEqual(result["upstream"], default)

        def mapped(bridge, address):
            with service.database(self.path) as db:
                return bridge.routes.resolve(db, "android:" + address, address)

        register()
        with service.open_server(self.path, "local-key", 0) as server:
            self.assertEqual(mapped(server.service, legacy), default)
            self.assertIsNone(mapped(server.service, first))
            register(addAlias=first)
            register(addAlias=second)
            register(addAlias="https://PHONE-ONE.example:443/bot/")
        register()  # Ordinary desktop synchronization registers the current key again.
        with service.open_server(self.path, "local-key", 0) as server:
            bridge = server.service
            self.assertEqual([mapped(bridge, a) for a in (legacy, first, second)], [default] * 3)
            self.assertIsNone(mapped(bridge, "https://unreviewed.example/bot"))
            route = mapped(bridge, first)
            self.assertEqual(bridge.routes.check_origin(route, "alice", "chat-42"), (True, ""))
            with service.database(self.path, write=True) as db:
                room = bridge.routes.ensure_room(db, route, "alice", "chat-42")
                turn = bridge.enqueue(db, room, uid(), "through reviewed phone address")
            bridge.generate(room, turn["id"], "alice", "chat-42", turn["question"])
            self.assertEqual(bot.messages(), [turn["question"]])
            register(aliases=[first])
            self.assertEqual([mapped(bridge, a) for a in (legacy, first, second)], [default, default, None])
            register(aliases=[])
            self.assertEqual([mapped(bridge, a) for a in (legacy, first, second)], [default, None, None])

    def test_alias_conflicts_and_invalid_changes_preserve_routes_and_keys(self):
        bot = self.bot("authorized-key")
        default = service.initialize(self.path, bot.base)
        kept, blocked = "https://keep.example", "https://second-phone.example"
        second_base = "https://second-upstream.example"
        self.provision("pc", bot.base, "authorized-key", aliases=[kept])
        self.provision("other", second_base, "second-key", aliases=[blocked])
        for source, base, options, status in (
                ("pc", bot.base, {"addAlias": blocked}, 409),
                ("pc", bot.base, {"addAlias": second_base + "/"}, 409),
                ("pc", bot.base, {"aliases": ["https://partial.example", blocked]}, 409),
                ("pc", bot.base, {"addAlias": kept, "aliases": []}, 400),
                ("pc", bot.base, {"addAlias": "https://user@phone.example"}, 400),
                ("pc", bot.base, {"addAlias": "http://127.0.0.1:10000"}, 400),
                ("pc", bot.base, {"addAlias": None}, 400),
                ("new", "https://unknown.example", {"addAlias": "https://unreviewed.example"}, 409),
                ("new", kept, {}, 409)):
            with self.subTest(source=source, base=base, options=options):
                with self.assertRaises(service.ApiError) as caught:
                    self.provision(source, base, "rejected-key", **options)
                self.assertEqual(caught.exception.status, status)
        with service.open_server(self.path, "legacy-key", 0) as server:
            routes = server.service.routes
            with service.database(self.path) as db:
                self.assertEqual(routes.resolve(db, "android:" + kept, kept), default)
                self.assertIsNone(routes.resolve(db, "android:https://partial.example", "https://partial.example"))
                self.assertIsNone(routes.resolve(db, "new", "https://unknown.example"))
            self.assertEqual(routes.check_origin(default, "alice", "chat-42"), (True, ""))

    def test_same_sid_multiple_sources_users_and_legacy_send(self):
        first, second = self.bot("old-key"), self.bot("independent-key")
        default = service.initialize(self.path, first.base)
        self.assertEqual(self.provision("source-a", first.base, "device-key"), default)
        other = self.provision("source-b", second.base, "independent-key", aliases=["https://mirror.example/"])
        with ThreadPoolExecutor(max_workers=2) as workers:
            bridge = service.Service(self.path, "old-key", workers)
            with service.database(self.path, write=True) as db:
                routes = bridge.routes
                self.assertEqual(routes.resolve(db, "source-b", "https://mirror.example"), other)
                self.assertEqual(routes.resolve(db, "android:https://mirror.example", "https://mirror.example"), other)
                self.assertIsNone(routes.resolve(db, "unregistered", second.base))
                self.assertIsNone(routes.resolve(db, "source-a", second.base))
                self.assertIsNone(routes.resolve(db, "source-b", "https://unknown.example", other))
                self.assertIsNone(routes.resolve(db, "source-b", second.base, default))
                sid = "chat-42"
                room_a = routes.ensure_room(db, default, "alice", sid, legacy=True)
                room_b = routes.ensure_room(db, other, "alice", sid)
                room_c = routes.ensure_room(db, other, "bob", sid)
                self.assertEqual(len({room_a, room_b, room_c}), 3)
                self.assertEqual(routes.ensure_room(db, other, "alice", sid), room_b)
                with self.assertRaises(service.ApiError):
                    service.grant(db, room_b, True)
                with self.assertRaises(service.ApiError):
                    service.grant(db, routes.ensure_room(db, default, "bob", sid), True)
                grant = service.grant(db, room_a, True)
                self.assertTrue(routes.prove_room(db, room_a, grant["token"]))
                self.assertFalse(routes.prove_room(db, room_b, grant["token"]))
                queued = bridge.enqueue(db, room_b, uid(), "second")
            self.assertEqual(routes.check_origin(other, "alice", sid), (True, ""))
            self.assertEqual(routes.check_origin(other, "bob", sid), (False, ""))
            self.assertEqual(bridge.dispatch_turn(room_b, queued, "alice", sid)[0], 202)
            self.assertEqual(bridge.write("/v1/send", grant["token"], dict(id=uid(), question="legacy"))[0], 202)
        self.assertEqual(first.messages(), ["legacy"])
        self.assertEqual(second.messages(), ["second"])
        self.assertEqual(first.rejected_messages + second.rejected_messages, [])
        self.assertEqual(second.calls[0][2]["session_id"], sid)
        with service.database(self.path, write=True) as db:
            denied = bridge.enqueue(db, room_a, uid(), "device key for default")
        bridge.generate(room_a, denied["id"], "alice", sid, denied["question"])
        self.assertEqual(first.rejected_messages, ["device key for default"])
        self.assertEqual(first.messages(), ["legacy"])
        with service.database(self.path) as db:
            self.assertEqual(service.find_turn(db, room_a, denied["id"])["status"], "unknown")

    def test_legacy_recovery_cli_preserves_original_scope(self):
        base = "https://default.example"
        upstream = service.initialize(self.path, base)
        self.provision("source", base, "device-key")
        owner = dict(device=uid(), token=uid())
        device_service.provision_owner(self.path, owner)
        with ThreadPoolExecutor(max_workers=1) as workers:
            bridge = service.Service(self.path, "legacy-key", workers)
            _, profile = bridge.write("/v1/device/profile", owner["token"],
                                      dict(sourceId="source", username="user", base=base, name="PC", upstream=upstream))
            with service.database(self.path, write=True) as db:
                private_room = bridge.routes.ensure_room(db, upstream, "user", "device-only")
                old_room = bridge.routes.ensure_room(db, upstream, "user", "old", legacy=True)
        def cli(*arguments):
            return subprocess.run([sys.executable, service.__file__, "--db", str(self.path), *arguments],
                                  capture_output=True)
        self.assertEqual(json.loads(cli("rooms").stdout)["id"], old_room)
        self.assertEqual(cli("profiles").stdout.strip(), b"")
        self.assertNotEqual(cli("recover-owner", "--room", private_room).returncode, 0)
        self.assertNotEqual(cli("profile-recover", "--profile", profile["profile"]).returncode, 0)
        self.assertEqual(cli("recover-owner", "--room", old_room).returncode, 0)

    def test_launcher_new_port_is_used_and_unverified_port_gets_no_key(self):
        new = self.bot("launcher-key", launcher_root=True)
        stale = self.bot("stale-independent-key", launcher_root=True)
        service.initialize(self.path, "https://default.example")
        identity = uid()
        source = "launcher:" + identity
        with patch.object(launcher, "identity_for_url", return_value=identity):
            route = self.provision(source, stale.base, "launcher-key", launcherId=identity)
            self.assertEqual(self.provision(source, new.base, "launcher-key", launcherId=identity), route)
        with ThreadPoolExecutor(max_workers=1) as workers:
            bridge = service.Service(self.path, "default-key", workers)
            with service.database(self.path, write=True) as db:
                self.assertEqual(bridge.routes.resolve(db, source, stale.base), route)
                self.assertEqual(bridge.routes.resolve(db, source, new.base), route)
                self.assertIsNone(bridge.routes.resolve(db, "other", stale.base))
                room = bridge.routes.ensure_room(db, route, "alice", "chat-42")
                sent = bridge.enqueue(db, room, uid(), "new port")
            with patch.object(launcher, "url_for_identity", return_value=new.base) as lookup:
                bridge.generate(room, sent["id"], "alice", "chat-42", sent["question"])
            self.assertGreaterEqual(lookup.call_count, 2)
            self.assertTrue(all(call.args == (identity,) for call in lookup.call_args_list))
            with service.database(self.path, write=True) as db:
                self.assertEqual(service.find_turn(db, room, sent["id"])["status"], "complete")
                stopped = bridge.enqueue(db, room, uid(), "socket identity changed")
            with patch.object(launcher, "url_for_identity", side_effect=[stale.base, new.base]):
                bridge.generate(room, stopped["id"], "alice", "chat-42", stopped["question"])
            with service.database(self.path) as db:
                self.assertEqual(tuple(db.execute("SELECT status,needs_review FROM turns WHERE id=?",
                                                  (stopped["id"],)).fetchone()), ("unknown", 0))
        self.assertEqual(new.messages(), ["new port"])
        self.assertEqual(stale.messages() + stale.rejected_messages, [])
