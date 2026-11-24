"""Exercise desktop publication and independent phone/PC clients over the public wire."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import hashlib
from pathlib import Path
import sqlite3
import threading
import time
import unittest
from urllib.parse import urlencode

if __package__:
    from .test_service import Client, ServiceProcess, Upstream, uid
else:
    from test_service import Client, ServiceProcess, Upstream, uid


def turn(question, answer):
    return dict(id=uid(), question=question, answer=answer, status="complete")


def content(rows):
    return [(row["question"], row["answer"], row["status"]) for row in rows]


class ProfileServiceTests(unittest.TestCase):
    def setUp(self):
        self.upstream = Upstream()
        thread = threading.Thread(target=self.upstream.serve_forever, kwargs={"poll_interval": 0.02})
        thread.start()
        self.addCleanup(self.upstream.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(self.upstream.shutdown)
        self.addCleanup(self.upstream.release.set)
        self.service = ServiceProcess(self, self.upstream.base)

    def start(self):
        self.service.cli("init", "--upstream", self.upstream.base)
        self.serve()

    def serve(self):
        self.service.serve()
        self.public = Client(self.service.port)

    def ok(self, response):
        self.assertIn(response[0], (200, 201, 202), response)
        return response[1]

    def denied(self, response):
        self.assertIn(response[0], (401, 403, 404, 409), response)

    def create_body(self, code, name="A", username="alice"):
        return dict(code=code, sourceBase=self.upstream.base, username=username, sourceId=uid(), name=name)

    def create_profile(self, name="A", username="alice"):
        body = self.create_body(self.service.cli("profile-bootstrap"), name, username)
        info = self.ok(self.public.request("profile/create", body))
        self.assertIs(info["owner"], True)
        self.assertEqual(info["name"], name)
        return Client(self.service.port, info["token"]), info

    def pair(self, owner, profile):
        invite = self.ok(owner.request("profile/invite", {}))
        self.assertGreater(invite["expiresIn"], 0)
        info = self.ok(self.public.request("profile/join", {"code": invite["code"]}))
        self.assertIs(info["owner"], False)
        self.assertEqual([info[k] for k in ("profile", "instance", "name")],
                         [profile[k] for k in ("profile", "instance", "name")])
        self.assertNotEqual(info["token"], owner.token)
        self.denied(self.public.request("profile/join", {"code": invite["code"]}))
        return Client(self.service.port, info["token"])

    def publish(self, owner, sid, title, activity, total, **options):
        body = dict(sid=sid, title=title, activity=activity, total=total, **options)
        return self.ok(owner.request("profile/session", body))

    def import_page(self, owner, sid, start, turns):
        return owner.request("profile/import", dict(sid=sid, start=start, turns=turns))

    def directory(self, client, **params):
        query = "?" + urlencode(params) if params else ""
        return self.ok(client.request("profile/sessions" + query))

    def turns(self, client, sid, after=0, limit=100):
        query = urlencode(dict(sid=sid, after=after, limit=limit))
        return self.ok(client.request("profile/turns?" + query))

    def wait_turn(self, client, sid, identifier, status, answer=None):
        query = urlencode(dict(sid=sid, id=identifier))
        deadline = time.monotonic() + 6
        while True:
            response = client.request("profile/turn?" + query)
            item = response[1]
            if (response[0] == 200 and item.get("status") == status
                    and (answer is None or item["answer"] == answer)):
                return item
            if time.monotonic() >= deadline:
                self.fail(f"expected {status} {answer!r}, last response {response!r}")
            time.sleep(0.02)

    def test_pair_after_pc_history_then_continue_and_catch_up(self):
        self.start()
        pc, info = self.create_profile()
        pc_b, info_b = self.create_profile("B", "bob")
        self.assertNotEqual(info["profile"], info_b["profile"])
        sessions = {
            "chat-42": ("同名", 200, [turn("配对前的唯一短句：折纸鲸鱼", "原来的完整回复")]),
            "a-archived": ("同名", 200, [turn("另一段正文", "〔图片〕"), turn("第二问", "第二答")]),
            "a-oldest": ("最早一段", 0, [turn("只有附件提示〔文件〕", "没有原文件")]),
        }
        for sid, (title, activity, rows) in sessions.items():
            self.publish(pc, sid, title, activity, len(rows))
            self.assertEqual(self.ok(self.import_page(pc, sid, 0, rows))["imported"], len(rows))
        b_row = turn("B 的私有问题", "B 的回复")
        self.publish(pc_b, "chat-43", "B", 500, 1)
        self.ok(self.import_page(pc_b, "chat-43", 0, [b_row]))
        self.ok(pc.request("profile/status", dict(complete=True, expected=3, issue="")))

        phone = self.pair(pc, info)
        directory = self.directory(phone)
        self.assertEqual((directory["name"], directory["complete"], directory["expected"], directory["issue"]),
                         ("A", True, 3, ""))
        self.assertEqual([e["sid"] for e in directory["sessions"]], ["a-archived", "chat-42", "a-oldest"])
        for entry in directory["sessions"]:
            sid = entry["sid"]
            title, activity, rows = sessions[sid]
            self.assertEqual((entry["title"], entry["activity"], entry["total"], entry["imported"]),
                             (title, activity, len(rows), len(rows)))
            history = self.turns(phone, sid)["turns"]
            self.assertEqual(content(history), content(rows))
            self.assertEqual([r["id"] for r in history], [r["id"] for r in rows])
        self.assertEqual(self.upstream.messages(), [])
        self.denied(phone.request("profile/turns?sid=chat-43"))
        self.denied(phone.request("profile/turn?sid=chat-43&id=" + b_row["id"]))
        self.denied(phone.request("profile/send", dict(sid="chat-43", id=uid(), question="跨档案")))
        self.assertEqual([e["sid"] for e in self.directory(pc_b)["sessions"]], ["chat-43"])

        sid = "chat-42"
        expected = ["刚才提到哪种鲸鱼？", "电脑正常追问", "离线期间追加", "离线期间追加"]
        identifiers = []
        for client, other, question in ((phone, pc, expected[0]), (pc, phone, expected[1])):
            identifier = uid()
            self.ok(client.request("profile/send", dict(sid=sid, id=identifier, question=question)))
            result = self.wait_turn(other, sid, identifier, "complete")
            self.assertEqual(result["answer"], "片段完成〔图片〕")
            identifiers.append(identifier)
        self.publish(pc, "a-new", "第四段", 700, 0)
        self.publish(pc, "a-new", "改名后的第四段", 800, 0)
        self.ok(pc.request("profile/status", dict(complete=True, expected=4, issue="")))
        # No phone requests until its bearer is restored after the two PC sends.
        for question in expected[2:]:
            identifier = uid()
            self.ok(pc.request("profile/send", dict(sid=sid, id=identifier, question=question)))
            self.wait_turn(pc, sid, identifier, "complete")
            identifiers.append(identifier)
        self.service.restart()
        restored = Client(self.service.port, phone.token)
        current = self.directory(restored)
        self.assertEqual(current["expected"], 4)
        self.assertCountEqual([(e["sid"], e["title"]) for e in current["sessions"]],
                              [(k, v[0]) for k, v in sessions.items()] + [("a-new", "改名后的第四段")])
        rows = self.turns(restored, sid)["turns"]
        self.assertEqual(rows, self.turns(pc, sid)["turns"])
        self.assertEqual(content(rows[:1]), content(sessions[sid][2]))
        self.assertEqual([r["id"] for r in rows[1:]], identifiers)
        self.assertEqual([r["seq"] for r in rows], [1, 2, 3, 4, 5])
        self.assertEqual([r["question"] for r in rows[1:]], expected)
        self.assertEqual(self.upstream.messages(), expected)
        for path, auth, body in self.upstream.calls:
            self.assertEqual((path, auth, body["username"], body["session_id"]),
                             ("/fixture/api/v1/chat", "Bearer fixture-key", "alice", sid))

    def test_import_checkpoint_restart_content_conflict_and_missing_identity(self):
        self.start()
        pc, info = self.create_profile()
        sid = "gone-from-astrbot"
        # Cross the 0.1.17 whole-room import ceiling using modest HTTP batches.
        rows = [turn("重复正文", "同一回复") for _ in range(10_005)]
        self.publish(pc, sid, "旧档案", 0, len(rows))
        self.assertEqual(self.ok(self.import_page(pc, sid, 0, rows[:50]))["imported"], 50)
        issue = f"旧档案：第 51 至 {len(rows)} 轮尚未导入"
        self.ok(pc.request("profile/status", dict(complete=False, expected=1, issue=issue)))
        phone = self.pair(pc, info)
        self.assertIn(self.import_page(pc, sid, 60, rows[60:65])[0], (400, 409))
        self.service.restart()
        state = self.directory(phone)
        self.assertIs(state["complete"], False)
        self.assertEqual(state["issue"], issue)
        self.assertEqual(state["sessions"][0]["imported"], 50)
        self.assertEqual(self.ok(self.import_page(pc, sid, 0, rows[:50]))["imported"], 50)
        conflict = [dict(rows[49], answer="被改写的回复"), rows[50]]
        self.assertIn(self.import_page(pc, sid, 49, conflict)[0], (400, 409))
        first = self.turns(phone, sid)
        self.assertEqual(content(first["turns"]), content(rows[:50]))
        self.assertIs(first["more"], False)
        for start in range(50, len(rows), 100):
            batch = rows[start:start + 100]
            self.assertEqual(self.ok(self.import_page(pc, sid, start, batch))["imported"], start + len(batch))
        self.ok(pc.request("profile/status", dict(complete=True, expected=1, issue="")))
        entry = self.directory(phone)["sessions"][0]
        self.assertEqual((entry["imported"], entry["total"]), (len(rows), len(rows)))
        self.assertTrue(entry["reason"])
        first = self.turns(phone, sid)
        self.assertEqual(len(first["turns"]), 100)
        self.assertIs(first["more"], True)
        full = first["turns"].copy()
        while len(full) < len(rows):
            after = full[-1]["seq"]
            page = self.turns(phone, sid, after)
            self.assertTrue(page["turns"])
            self.assertLessEqual(len(page["turns"]), 100)
            full.extend(page["turns"])
            self.assertEqual(page["more"], len(full) < len(rows))
        self.assertEqual(page, self.turns(phone, sid, after))
        self.assertEqual([t["seq"] for t in full], list(range(1, len(rows) + 1)))
        self.assertEqual([t["id"] for t in full], [t["id"] for t in rows])
        self.assertEqual(content(full), content(rows))
        self.denied(phone.request("profile/send", dict(sid=sid, id=uid(), question="身份失效")))
        self.assertEqual(self.upstream.messages(), [])

    def test_catalogue_revision_remove_and_device_revocation(self):
        self.start()
        pc, info = self.create_profile()
        for sid in ("b", "a", "c"):
            self.publish(pc, sid, sid.upper(), 120, 0)
        previous_devices = {d["id"] for d in self.ok(pc.request("profile/devices"))["devices"]}
        phone = self.pair(pc, info)
        devices = self.ok(pc.request("profile/devices"))["devices"]
        added = [d for d in devices if d["id"] not in previous_devices]
        self.assertEqual(len(added), 1)
        self.assertTrue(added[0]["name"])
        device = added[0]["id"]
        second = self.pair(pc, info)
        first = self.directory(phone, limit=2)
        self.assertEqual([r["sid"] for r in first["sessions"]], ["a", "b"])
        self.assertIs(first["more"], True)
        revision = first["revision"]
        rest = self.directory(phone, offset=2, limit=2, revision=revision)
        self.assertEqual([r["sid"] for r in rest["sessions"]], ["c"])
        self.assertIs(rest["more"], False)
        self.publish(pc, "c", "最新", 121, 0)
        continuation = "profile/sessions?" + urlencode(dict(offset=2, limit=2, revision=revision))
        self.assertEqual(phone.request(continuation)[0], 409)
        self.assertEqual([r["sid"] for r in self.directory(phone)["sessions"]], ["c", "a", "b"])

        for path, body in (
            ("profile/invite", {}), ("profile/devices", None), ("profile/revoke", {"device": device}),
            ("profile/session", dict(sid="c", title="未授权更名", total=0, activity=0)),
            ("profile/import", dict(sid="c", start=0, turns=[])), ("profile/remove", {"sid": "c"}),
            ("profile/status", dict(complete=True, expected=0, issue="")),
        ):
            with self.subTest(path=path):
                self.assertEqual(phone.request(path, body)[0], 403)
        self.ok(pc.request("profile/remove", {"sid": "c"}))
        self.assertEqual([r["sid"] for r in self.directory(phone)["sessions"]], ["a", "b"])
        self.denied(phone.request("profile/turns?sid=c"))
        self.denied(phone.request("profile/send", dict(sid="c", id=uid(), question="已删除")))
        self.ok(pc.request("profile/revoke", {"device": device}))
        for path, body in (("profile/sessions", None), ("profile/turns?sid=a", None),
                           ("profile/turn?sid=a&id=" + uid(), None),
                           ("profile/send", dict(sid="a", id=uid(), question="已撤销"))):
            with self.subTest(path=path):
                self.assertEqual(phone.request(path, body)[0], 401)
        self.assertEqual(self.directory(second)["sessions"], self.directory(pc)["sessions"])
        self.assertEqual(self.upstream.messages(), [])

    def test_concurrent_delivery_lost_response_and_unknown_result(self):
        self.start()
        pc, info = self.create_profile()
        sid = "chat-42"
        self.publish(pc, sid, "可接续", 0, 1)
        self.ok(self.import_page(pc, sid, 0, [turn("旧问", "旧答")]))
        phone = self.pair(pc, info)
        payload = dict(sid=sid, id=uid(), question="等待")
        gate = threading.Barrier(2)

        def submit(client):
            gate.wait(3)
            return client.request("profile/send", payload)

        with ThreadPoolExecutor(max_workers=2) as workers:
            for response in workers.map(submit, (phone, pc)):
                self.ok(response)
        self.wait_turn(phone, sid, payload["id"], "running", "片段")
        self.assertEqual(pc.request("profile/send", dict(sid=sid, id=uid(), question="同房间忙"))[0], 409)
        self.assertEqual(self.upstream.messages(), ["等待"])
        self.upstream.release.set()
        self.wait_turn(pc, sid, payload["id"], "complete")
        self.assertEqual(phone.request("profile/send", dict(payload, question="改写请求"))[0], 409)
        for question, status in (("丢失 HTTP 回执", "complete"), ("上游断流", "unknown")):
            payload = dict(sid=sid, id=uid(), question=question)
            phone.request("profile/send", payload, discard=True)
            original = self.wait_turn(pc, sid, payload["id"], status)
            self.ok(phone.request("profile/send", payload))
            self.assertEqual(self.wait_turn(phone, sid, payload["id"], status), original)
        self.assertEqual(pc.request("profile/send", dict(sid=sid, id=uid(), question="结果未知"))[0], 409)
        self.assertEqual([r["seq"] for r in self.turns(phone, sid)["turns"]], [1, 2, 3, 4])
        self.assertEqual(self.upstream.messages(), ["等待", "丢失 HTTP 回执", "上游断流"])

    def test_legacy_db_migration_credentials_and_room_link(self):
        sid = "chat-42"
        room, instance, owner_token, invite = uid(), uid(), uid(), uid()
        prefix = turn("旧问", "带附件的原回复〔图片〕")
        later = turn("旧房间后来的追问", "旧房间的真实回复")
        schema = Path(__file__).with_name("fixtures") / "service_0_1_17.sql"
        with closing(sqlite3.connect(self.service.db)) as db, db:
            db.executescript(schema.read_text(encoding="utf-8"))
            db.execute("INSERT INTO config VALUES (?,?,NULL)", (instance, self.upstream.base))
            db.execute("INSERT INTO sources VALUES (?)", (self.upstream.base,))
            db.execute("INSERT INTO rooms VALUES (?,?,?)", (room, "alice", sid))
            db.execute("INSERT INTO devices VALUES (?,?,?,1)",
                       (uid(), hashlib.sha256(owner_token.encode()).digest(), room))
            db.execute("INSERT INTO codes VALUES (?,?,?,'invite')",
                       (hashlib.sha256(invite.encode()).digest(), room, int(time.time()) + 600))
            for seq, row in enumerate((prefix, later), 1):
                db.execute("INSERT INTO turns VALUES (?,?,?,?,?,?,0)",
                           (room, seq, row["id"], row["question"], row["answer"], row["status"]))
        profile_code = self.service.cli("profile-bootstrap")
        self.serve()
        owner = Client(self.service.port, owner_token)
        room_code = self.service.pair()
        self.denied(self.public.request("profile/join", {"code": invite}))
        self.denied(self.public.request("profile/create", self.create_body(room_code)))
        self.denied(self.public.request("join", {"code": profile_code}))
        self.denied(self.public.request("create", dict(code=profile_code, sourceBase="", username="",
                                                        session="", history=[])))
        self.ok(self.public.request("create", dict(code=room_code, sourceBase="", username="",
                                                    session="", history=[])))
        legacy_guest = self.ok(self.public.request("join", {"code": invite}))
        self.assertEqual((legacy_guest["owner"], legacy_guest["room"]), (False, room))
        guest = Client(self.service.port, legacy_guest["token"])
        old_rows = self.ok(guest.request("turns"))["turns"]
        self.assertEqual([r["id"] for r in old_rows], [prefix["id"], later["id"]])
        for legacy in (owner, guest):
            self.denied(legacy.request("profile/sessions"))
            self.denied(legacy.request("profile/turns?sid=" + sid))

        request = self.create_body(profile_code)
        info = self.ok(self.public.request("profile/create", request))
        self.denied(self.public.request("profile/create", request))
        self.assertEqual(info["instance"], instance)
        pc = Client(self.service.port, info["token"])
        phone_invite = self.ok(pc.request("profile/invite", {}))["code"]
        self.denied(self.public.request("join", {"code": phone_invite}))
        phone_info = self.ok(self.public.request("profile/join", {"code": phone_invite}))
        phone = Client(self.service.port, phone_info["token"])

        # A claimed SID or the old guest grant alone cannot expose the old room.
        session = dict(sid=sid, title="原房间", activity=10, total=1)
        for proof in ({}, {"legacyToken": guest.token}):
            response = pc.request("profile/session", dict(session, **proof))
            if response[0] in (200, 201):
                entries = self.turns(phone, sid)["turns"]
                self.assertEqual(entries, [])
                self.denied(phone.request("profile/send", dict(sid=sid, id=uid(), question="无主人证明")))
            else:
                self.denied(response)
        self.publish(pc, sid, "原房间", 10, 1, legacyToken=owner.token)
        # The old service allocated its own ID when seeding from the PC.
        local_copy = dict(prefix, id=uid())
        for _ in range(2):
            self.assertEqual(self.ok(self.import_page(pc, sid, 0, [local_copy]))["imported"], 1)
        self.assertEqual(self.turns(phone, sid)["turns"], old_rows)
        self.assertEqual(self.upstream.messages(), [])
        payload = dict(id=uid(), question="手机接续原房间")
        self.ok(phone.request("profile/send", dict(payload, sid=sid)))
        final = self.wait_turn(pc, sid, payload["id"], "complete")
        self.assertEqual(self.ok(owner.request("turns"))["turns"], old_rows + [final])
        self.service.restart()
        self.assertEqual(self.turns(phone, sid)["turns"], self.ok(guest.request("turns"))["turns"])
        self.ok(pc.request("profile/remove", {"sid": sid}))
        self.denied(phone.request("profile/turns?sid=" + sid))
        self.assertEqual(self.ok(guest.request("turns"))["turns"], old_rows + [final])
        # Both tokens can be genuine and still refer to different AstrBot users.
        other, _ = self.create_profile("不同用户", "bob")
        result = other.request("profile/session", dict(session, legacyToken=owner.token))
        if result[0] in (200, 201):
            self.assertEqual(self.turns(other, sid)["turns"], [])
            self.denied(other.request("profile/send", dict(sid=sid, id=uid(), question="身份不符")))
        else:
            self.denied(result)
        self.assertEqual(self.upstream.messages(), ["手机接续原房间"])
