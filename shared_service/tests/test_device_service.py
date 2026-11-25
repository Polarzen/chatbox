"""Device enrollment, canonical room history and exactly-once dispatch over the public HTTP contract."""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import json
import secrets
import subprocess
import sys
import threading
import time
import unittest
from urllib.parse import urlencode

from shared_service import astrbot, device_runtime, service

if __package__:
    from .test_service import Client, ServiceProcess, Upstream, uid
else:
    from test_service import Client, ServiceProcess, Upstream, uid


class DeviceServiceTests(unittest.TestCase):
    def bot(self, key):
        bot = Upstream()
        bot.expected_key = key
        thread = threading.Thread(target=bot.serve_forever, kwargs={"poll_interval": 0.01})
        thread.start()
        self.addCleanup(bot.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(bot.shutdown)
        self.addCleanup(bot.release.set)
        return bot

    def setUp(self):
        self.first = self.bot("fixture-key")
        self.host = ServiceProcess(self, self.first.base)
        self.instance = self.host.cli("init", "--upstream", self.first.base)
        self.device = uid()
        owner = secrets.token_urlsafe(32)
        self.configure("device-owner", device=self.device, token=owner)
        self.route = self.configure("device-upstream", sourceId="A", base=self.first.base, key="fixture-key")["upstream"]
        self.host.serve()
        self.public = Client(self.host.port)
        self.pc = Client(self.host.port, owner)

    def configure(self, command, **body):
        run = subprocess.run([sys.executable, self.host.script, "--db", self.host.db, command],
                             input=json.dumps(body), capture_output=True, encoding="utf-8", timeout=12)
        self.assertEqual(run.returncode, 0, run.stderr)
        return json.loads(run.stdout)

    def ok(self, response):
        status, body = response
        self.assertIn(status, (200, 202), body)
        return body

    def get(self, who, action, **params):
        suffix = ("?" + urlencode(params)) if params else ""
        return who.request("device/" + action + suffix)

    def post(self, who, action, **body):
        return who.request("device/" + action, body)

    def guest(self, username="bob"):
        invite = self.ok(self.post(self.pc, "invite", pcProfiles=2, pcSessions=5))
        preview = self.ok(self.post(self.public, "preview", code=invite["code"]))
        self.assertEqual((preview["pcProfiles"], preview["pcSessions"], preview["instance"]), (2, 5, self.instance))
        body = dict(code=invite["code"], device=uid(), token=secrets.token_urlsafe(32),
                    username=username, phoneProfiles=1, phoneSessions=2)
        self.ok(self.post(self.public, "join", **body))
        self.assertEqual(self.post(self.public, "join", **body)[0], 403)
        phone = Client(self.host.port, body["token"])
        recovered = self.ok(self.get(phone, "me"))
        self.assertEqual((recovered["device"], recovered["owner"]), (body["device"], False))
        return phone, body["device"]

    def profile(self, who, source=None, base=None, username="alice", **extra):
        base = base or self.first.base
        return self.ok(self.post(who, "profile", name="同名", username=username, base=base,
                                 sourceId=source or ("android:" + base), **extra))["profile"]

    def history(self, who, profile, sid="chat-42", **extra):
        return self.ok(self.post(who, "session", profile=profile, sid=sid, title="同名", activity=7, total=1, **extra))

    def upload(self, who, profile, sid, q, a):
        turn = dict(id=uid(), question=q, answer=a, status="complete")
        body = dict(profile=profile, sid=sid, start=0, turns=[turn])
        self.assertEqual(self.ok(self.post(who, "import", **body))["imported"], 1)
        self.assertEqual(self.ok(self.post(who, "import", **body))["imported"], 1)
        self.assertEqual(self.post(who, "import", **dict(body, turns=[dict(turn, answer="不同")]))[0], 409)
        return turn

    def wait_turn(self, client, profile, sid, identifier, status="complete"):
        deadline = time.monotonic()+5
        while True:
            turn = self.ok(self.get(client, "turn", profile=profile, sid=sid, id=identifier))
            if turn["status"] == status:
                return turn
            self.assertLess(time.monotonic(), deadline, turn)
            time.sleep(0.02)

    def test_device_listener_rejects_existing_room_and_profile_grants(self):
        room = self.ok(self.public.request("create", dict(code=self.host.pair(), sourceBase=self.first.base,
                                                         username="alice", session="chat-42", history=[])))
        profile_body = dict(code=self.host.cli("profile-bootstrap"), sourceBase=self.first.base,
                            username="bob", sourceId=uid(), name="B")
        profile = self.ok(self.public.request("profile/create", profile_body))
        profile_owner = Client(self.host.port, profile["token"])
        self.ok(profile_owner.request("profile/session", dict(sid="chat-43", title="B", activity=1, total=0)))
        grants = [("", room, "turns"), ("profile/", profile, "turns?sid=chat-43")]
        invitations = []
        for prefix, grant, _ in grants:
            legacy = Client(self.host.port, grant["token"])
            invitations.append(self.ok(legacy.request(prefix + "invite", {}))["code"])
        self.host.stop()
        with service.open_server(self.host.db, "fixture-key", 0, device_only=True) as server, device_runtime.serving(server):
            for (prefix, grant, listing), invitation in zip(grants, invitations):
                client = Client(server.server_port, grant["token"])
                with self.subTest(prefix=prefix):
                    self.assertEqual(client.request(prefix + "join", {"code": invitation})[0], 404)
                    self.assertEqual(client.request(prefix + listing)[0], 404)
                    body = dict(id=uid(), question="old client")
                    if prefix:
                        body["sid"] = "chat-43"
                    self.assertEqual(client.request(prefix + "send", body)[0], 404)
                    self.assertEqual(client.request("device/me")[0], 401)
        self.host.serve()
        for (prefix, grant, listing), invitation in zip(grants, invitations):
            legacy = Client(self.host.port, grant["token"])
            self.assertEqual(self.ok(legacy.request(prefix + listing))["turns"], [])
            self.ok(legacy.request(prefix + "join", {"code": invitation}))
        self.assertEqual(self.first.messages(), [])

    def test_wearable_query_requires_same_live_webchat_and_private_phone_profile(self):
        phone, device = self.guest()
        other, _ = self.guest("bob")
        profile = self.profile(phone, username="bob")
        sid = "chat-43"
        self.history(phone, profile, sid)
        self.upload(phone, profile, sid, "旧问题", "旧回复")
        config = dict(revision=1, profile=profile, sid=sid, source="fixture.band", timezone="UTC",
                      metrics=["exercise"], morningTime="08:00", waitMinutes=30,
                      workoutEnabled=True, morningEnabled=False, notifyWorkout=True, notifyMorning=False)
        self.assertEqual(self.post(other, "wearable-config", **config)[0], 404)
        shared = self.profile(self.pc, source="A", username="bob")
        self.assertEqual(self.post(phone, "wearable-config", **dict(config, profile=shared))[0], 403)
        self.ok(self.post(phone, "wearable-config", **config))
        plugin = Client(self.host.port, self.ok(self.post(phone, "wearable-credential", revision=1))["token"])
        self.assertEqual(self.get(plugin, "me")[0], 401)
        self.assertEqual(self.post(phone, "wearable-jobs")[0], 401)
        self.assertEqual(self.post(plugin, "wearable-clear", revision=1)[0], 401)
        invite = self.ok(self.post(self.pc, "invite", pcProfiles=0, pcSessions=0))
        self.assertEqual(self.post(self.public, "join", code=invite["code"], device=uid(), token=plugin.token,
                                   username="bob", phoneProfiles=0, phoneSessions=0)[0], 409)
        now = int(time.time() * 1000)
        record = dict(id="ride", metric="exercise", version=1, source=config["source"], exerciseType="cycling",
                      startMs=now-70000, endMs=now-10000)
        batch = dict(revision=1, cursor=1, initial=True, records=[], deletes=[], statuses={"exercise": "ok"})
        self.ok(self.post(phone, "wearable-sync", **batch))
        self.ok(self.post(phone, "wearable-sync", **dict(batch, cursor=2, initial=False, records=[record])))
        self.first.run_id = uid()
        context = dict(origin="webchat:FriendMessage:webchat!bob!chat-43", sender="bob",
                       messageId=self.first.run_id, metric="exercise")
        self.assertEqual(self.post(plugin, "wearable-query", **context)[0], 403)
        op = dict(profile=profile, sid=sid, id=uid(), question="等待")
        self.ok(self.post(self.pc, "send", **op))
        result = self.ok(self.post(plugin, "wearable-query", **context))
        self.assertEqual((result["source"], result["data"]["durationSeconds"]), ("fixture.band", 60))
        for changed in [dict(sender="alice"), dict(origin=context["origin"].replace("FriendMessage", "GroupMessage")),
                        dict(origin=context["origin"] + "-other"), dict(messageId=op["id"]), dict(messageId=uid())]:
            self.assertEqual(self.post(plugin, "wearable-query", **(context | changed))[0], 403)
        self.assertEqual(self.post(plugin, "wearable-query", **context, username="bob")[0], 400)
        self.assertEqual(self.post(plugin, "wearable-query", **dict(context, metric="steps"))[0], 403)
        self.first.release.set()
        self.wait_turn(phone, profile, sid, op["id"])
        self.assertEqual(self.post(plugin, "wearable-query", **context)[0], 403)
        job, = self.ok(self.post(plugin, "wearable-jobs"))["jobs"]
        body = {"id": job["id"]}
        self.assertEqual(self.ok(self.post(plugin, "wearable-job-query", **body))["data"]["id"], "ride")
        self.assertEqual(self.post(plugin, "wearable-job-finish", **body, sid="elsewhere")[0], 400)
        self.first.removed_sessions = {sid}
        self.assertEqual(self.ok(self.post(plugin, "wearable-job-finish", **body))["status"], "destination_unavailable")
        self.assertEqual(len(self.ok(self.get(phone, "turns", profile=profile, sid=sid))["turns"]), 2)
        self.first.removed_sessions = set()
        for _ in range(2):
            self.assertEqual(self.ok(self.post(plugin, "wearable-job-finish", **body))["status"], "delivered")
        for viewer in (phone, self.pc):
            rows = self.ok(self.get(viewer, "turns", profile=profile, sid=sid))["turns"]
            self.assertEqual([row["question"] for row in rows], ["旧问题", "等待", ""])
        self.assertEqual(self.get(other, "turns", profile=profile, sid=sid)[0], 404)
        self.assertEqual(self.first.messages(), ["等待"])
        self.ok(self.post(self.pc, "revoke", device=device))
        self.assertEqual(self.post(plugin, "wearable-jobs")[0], 401)

    def test_pair_address_proof_and_guest_scope(self):
        self.assertEqual(self.ok(self.get(self.public, "health")), dict(instance=self.instance, version=1))
        phone, device = self.guest()
        foreign, _ = self.guest("guest-two")
        for who in (self.public, phone):
            self.assertIn(self.post(who, "endpoint", address="https://attacker.example")[0], (401,403))
        self.assertEqual(self.post(self.pc, "endpoint", address="http://127.0.0.1:90")[0], 400)
        base = "https://phone.example"
        self.ok(self.post(self.pc, "endpoint", address=base))
        nonce = uid()
        def verify():
            proof = self.ok(self.post(self.public, "prove", device=device, nonce=nonce))
            message = "\n".join(("chatbox-device-address-v1", self.instance, device, base, nonce)).encode()
            expected = hmac.digest(hashlib.sha256(phone.token.encode()).digest(), message, "sha256").hex()
            self.assertEqual(proof, dict(instance=self.instance, device=device, address=base, proof=expected))
        verify()
        pc_profile = self.profile(self.pc, source="A", upstream=self.route)
        own = self.profile(phone, username="bob")
        spoof = dict(name="x", username="bob", base=self.first.base, sourceId="android:"+self.first.base)
        for x in (dict(username="alice"), dict(sourceId="A"), dict(upstream=self.route), dict(legacyToken="guess")):
            self.assertEqual(self.post(phone, "profile", **(spoof | x))[0], 403)
        self.assertEqual({p["profile"] for p in self.ok(self.get(foreign, "profiles"))["profiles"]}, {pc_profile})
        self.history(phone, own, "chat-43")
        for other, expected in ((foreign,404),(self.pc,403)):
            self.assertEqual(self.post(other, "import", profile=own, sid="chat-43", start=0, turns=[])[0], expected)
        self.upload(phone, own, "chat-43", "手机", "自己的答案")
        self.assertEqual(self.get(foreign, "turns", profile=own, sid="chat-43")[0], 404)
        self.assertEqual(self.post(phone, "session", profile=pc_profile, sid="chat-42", title="x", total=1, activity=0)[0], 403)
        self.ok(self.post(self.pc, "revoke", device=device))
        verify()
        for action in ("me","profiles","sessions","devices"):
            self.assertEqual(self.get(phone, action)[0], 401)
        self.assertEqual(self.post(phone, "profile", **spoof)[0], 401)

    def test_native_sources_import_paging_and_remove(self):
        second = self.bot("second-key")
        self.configure("device-upstream", sourceId="B", base=second.base, key="second-key")
        phone, _ = self.guest()
        a = self.profile(self.pc, "A")
        b = self.profile(self.pc, "B", second.base)
        self.assertNotEqual(a, b)
        for p, expected in ((a,"甲"), (b,"乙")):
            self.history(self.pc, p)
            self.upload(self.pc, p, "chat-42", "同名", expected)
            got = self.ok(self.get(phone, "turns", profile=p, sid="chat-42", after=0, limit=100))
            self.assertEqual([t["answer"] for t in got["turns"]], [expected])
            body = dict(profile=p, sid="chat-42", id=uid(), question=expected)
            self.ok(self.post(phone, "send", **body))
            self.wait_turn(phone, p, "chat-42", body["id"])
        self.assertEqual(self.first.messages(), ["甲"])
        self.assertEqual(second.messages(), ["乙"])
        self.assertEqual(second.rejected_messages, [])
        page = self.ok(self.get(phone, "sessions", offset=0, limit=1))
        self.assertTrue(page["more"])
        final = self.ok(self.get(phone, "sessions", offset=1, limit=1, revision=page["revision"]))
        self.assertEqual({p["profile"] for p in page["sessions"] + final["sessions"]}, {a,b})
        self.assertEqual(self.post(phone, "remove", profile=a, sid="chat-42")[0], 403)
        self.ok(self.post(self.pc, "remove", profile=a, sid="chat-42"))
        self.assertEqual(self.get(phone, "sessions", offset=1, limit=1, revision=page["revision"])[0], 409)
        self.assertEqual(self.post(self.pc, "session", profile=a, sid="chat-42", title="x", activity=99,total=1)[0], 409)
        self.assertEqual(self.get(phone, "turns", profile=a, sid="chat-42")[0], 404)
        self.assertEqual(self.post(self.pc, "import", profile=a, sid="chat-42", start=0,turns=[])[0], 404)
        unknown_base = "https://unregistered.invalid"
        p = self.profile(phone, base=unknown_base, username="bob")
        self.assertTrue(self.history(phone, p, "chat-43")["reason"])
        self.upload(phone, p, "chat-43", "未映射", "本机答复")
        self.assertEqual(self.post(self.pc, "send", profile=p,sid="chat-43",id=uid(),question="不能发送")[0],409)
        self.assertEqual(self.post(self.pc, "session", profile=b,sid="empty",title="草稿",activity=99,total=0)[0],409)

    def test_start_races_and_service_crash(self):
        phone, _ = self.guest()
        profile = self.profile(self.pc, "A")
        sid = uid()
        op = dict(profile=profile,sid=sid,id=uid(),question="等待")
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda _: self.post(phone,"start",**op), range(2)))
        self.assertEqual([self.ok(r)["turn"]["id"] for r in results], [op["id"]]*2)
        self.wait_turn(phone, profile, sid, op["id"], "running")
        self.assertEqual(self.get(self.pc, "operation", id=op["id"])[0], 404)
        for replacement in (dict(question="different"),dict(sid=uid())):
            self.assertEqual(self.post(phone,"start",**(op|replacement))[0],409)
        self.assertEqual(self.post(self.pc,"send",**(op|dict(id=uid())))[0],409)
        deadline = time.monotonic()+3
        while not self.first.messages() and time.monotonic()<deadline:
            time.sleep(0.02)
        self.assertEqual(self.first.messages(), ["等待"])
        self.host.restart()
        result = self.ok(self.get(phone,"operation",id=op["id"]))
        self.assertEqual(result["turn"]["status"],"unknown")
        self.assertEqual(self.ok(self.post(phone,"start",**op)),result)
        self.assertEqual(self.first.messages(), ["等待"])

    def test_media_reservation_legacy_queue_and_alias(self):
        profile = self.profile(self.pc, "A")
        original = dict(question="旧问题", answer="原答复", status="complete")
        grant = self.ok(self.public.request("create", dict(code=self.host.pair(),sourceBase=self.first.base,
                                                          username="alice",session="chat-42",history=[original])))
        legacy = Client(self.host.port, grant["token"])
        original_id = self.ok(legacy.request("turns"))["turns"][0]["id"]
        self.assertTrue(self.history(self.pc, profile)["reason"])
        self.assertEqual(self.ok(self.get(self.pc,"turns",profile=profile,sid="chat-42"))["turns"],[])
        alias = self.upload(self.pc, profile, "chat-42", original["question"], original["answer"])
        self.assertEqual(self.history(self.pc, profile, legacyToken=grant["token"])["reason"],"")
        rows = self.ok(self.get(self.pc,"turns",profile=profile,sid="chat-42"))["turns"]
        self.assertEqual([r["id"] for r in rows],[original_id])
        self.assertEqual(self.ok(self.get(self.pc,"turn",profile=profile,sid="chat-42",id=alias["id"])),dict(seq=1,**alias))
        phone, _ = self.guest()
        media = dict(profile=profile,sid="chat-42",id=uid(),question="照片记录")
        self.assertEqual(self.post(phone,"media-begin",**media)[0],403)
        first = self.ok(self.post(self.pc, "media-begin", **media))
        self.assertTrue(first["dispatch"])
        self.assertFalse(self.ok(self.post(self.pc, "media-begin", **media))["dispatch"])
        self.assertEqual(legacy.request("send", dict(id=uid(),question="blocked"))[0],409)
        self.assertEqual(self.post(phone,"send",**(media|dict(id=uid())))[0],409)
        end = dict(profile=profile,sid="chat-42",id=media["id"],answer="已处理附件",status="complete")
        self.assertEqual(self.post(phone,"media-end",**end)[0],404)
        self.ok(self.post(self.pc,"media-end",**end))
        self.ok(self.post(self.pc,"media-end",**end))
        self.assertEqual(self.post(self.pc,"media-end",**(end|dict(answer="覆盖")))[0],409)
        self.assertEqual(self.ok(legacy.request("turn?id="+media["id"]))["answer"],"已处理附件")
        self.assertEqual(self.first.messages(),[])

    def test_source_can_finish_media_after_lost_receipt_and_bridge_restart(self):
        phone, _ = self.guest()
        profile = self.profile(phone, username="bob")
        target = dict(profile=profile, sid=uid())
        media = dict(target, id=uid(), question="等待")
        phone.request("device/media-begin", media, discard=True)
        deadline = time.monotonic() + 4
        while self.get(phone, "operation", id=media["id"])[0] == 404:
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.02)
        retry = self.ok(self.post(phone, "media-begin", **media))
        self.assertEqual((retry["dispatch"], retry["turn"]["status"]), (False, "running"))
        for action, changes in (("media-begin", dict(question="冲突")), ("media-begin", dict(sid=uid())), ("send", {})):
            self.assertEqual(self.post(phone, action, **(media | changes))[0], 409)
        self.assertEqual(self.get(self.pc, "operation", id=media["id"])[0], 404)
        # The native client's upstream connection lives outside the bridge process.
        answers = []
        with ThreadPoolExecutor(1) as pool:
            job = pool.submit(astrbot.send, self.first.base, self.first.expected_key, "bob",
                              target["sid"], media["question"], answers.append)
            try:
                deadline = time.monotonic() + 4
                while not self.first.messages():
                    self.assertLess(time.monotonic(), deadline)
                    time.sleep(0.02)
                self.host.restart()
                self.assertFalse(job.done())
                self.assertEqual(self.ok(self.get(phone, "operation", id=media["id"])), {
                    "profile": profile, "sid": target["sid"], "turn": retry["turn"]})
                self.assertFalse(self.ok(self.post(phone, "media-begin", **media))["dispatch"])
                self.assertEqual(self.post(self.pc, "send", **target, id=uid(), question="排他")[0], 409)
            finally:
                self.first.release.set()
            job.result(timeout=5)
        end = dict(target, id=media["id"], status="complete", answer=answers[-1])
        first = self.ok(self.post(phone, "media-end", **end))
        self.assertEqual(self.ok(self.post(phone, "media-end", **end)), first)
        committed = self.ok(self.get(self.pc, "turns", **target))["turns"]
        self.assertEqual(committed, [first["turn"]])
        self.assertEqual(self.host.cli("unresolved"), "")
        self.assertEqual(self.first.messages(), ["等待"])

    def test_first_media_review_releases_only_the_confirmed_reservation(self):
        phone, identifier = self.guest()
        phone_profile = self.profile(phone, username="bob")
        pc_profile = self.profile(self.pc, source="A")
        other = dict(profile=pc_profile, sid=uid(), id=uid(), question="其他来源仍在发送")
        self.ok(self.post(self.pc, "media-begin", **other))
        for outcome in ("unknown", "lost-source", "revoke"):
            with self.subTest(outcome=outcome):
                target = dict(profile=phone_profile, sid=uid())
                media = dict(target, id=uid(), question="附件首发")
                self.assertTrue(self.ok(self.post(phone, "media-begin", **media))["dispatch"])
                if outcome == "unknown":
                    end = dict(target, id=media["id"], status="unknown", answer="结果未确认")
                    self.ok(self.post(phone, "media-end", **end))
                elif outcome == "revoke":
                    self.ok(self.post(self.pc, "revoke", device=identifier))
                    self.assertEqual(self.get(phone, "operation", id=media["id"])[0], 401)
                listing = self.host.cli("unresolved").splitlines()
                records = {fields[1]: fields for fields in (row.split(maxsplit=3) for row in listing)}
                self.assertEqual(set(records), {media["id"], other["id"]})
                self.assertEqual(records[other["id"]][2], "media-running")
                room, _, state, notice = records[media["id"]]
                self.assertEqual(state, "media-running" if outcome == "lost-source" else "media-unknown")
                self.assertIn("来源设备的发送已停止", notice)
                self.assertIn("上游已空闲", notice)
                text = dict(target, id=uid(), question="核实后发送 " + outcome)
                self.assertEqual(self.post(self.pc, "send", **text)[0], 409)
                self.host.cli("resolve", "--room", room, "--id", media["id"])
                self.assertEqual(self.ok(self.get(self.pc, "turn", **target, id=media["id"]))["status"], "unknown")
                self.assertEqual(self.ok(self.get(self.pc, "operation", id=other["id"]))["turn"]["status"], "running")
                # The fixture has no upstream SID for this first reservation. Retaining
                # fresh allows the explicitly requested next send after local confirmation.
                self.ok(self.post(self.pc, "send", **text))
                self.wait_turn(self.pc, phone_profile, target["sid"], text["id"])
        self.assertEqual(self.first.messages(), ["核实后发送 " + reason for reason in ("unknown", "lost-source", "revoke")])

    def test_only_proven_legacy_profile_shares_its_existing_and_future_entries(self):
        def create(source):
            return self.ok(self.public.request("profile/create", dict(code=self.host.cli("profile-bootstrap"),
                           sourceBase=self.first.base, sourceId=source, username="alice", name="原档案")))
        old = create("A")
        other = create("private")
        legacy = Client(self.host.port, old["token"])
        invite = self.ok(legacy.request("profile/invite",{}))
        guest = self.ok(self.public.request("profile/join", dict(code=invite["code"])))
        phone, _ = self.guest()
        self.assertEqual(self.get(legacy,"profiles")[0],401)
        self.assertEqual(self.ok(self.get(phone,"profiles"))["profiles"],[])
        create_profile = dict(sourceId="A",base=self.first.base,username="alice",name="PC")
        for invalid in (guest["token"],other["token"]):
            self.assertEqual(self.post(self.pc,"profile",**create_profile,legacyToken=invalid)[0],403)
        initial = dict(sid="chat-42",title="老会话",activity=10,total=1)
        self.ok(legacy.request("profile/session",initial))
        original = dict(id=uid(),question="保存的问题",answer="原始结果",status="complete")
        self.ok(legacy.request("profile/import",dict(sid="chat-42",start=0,turns=[original])))
        profile = self.profile(self.pc,"A",legacyToken=old["token"])
        self.assertEqual(profile,old["profile"])
        self.assertEqual([p["profile"] for p in self.ok(self.get(phone,"profiles"))["profiles"]],[profile])
        self.assertEqual(self.ok(self.get(phone,"turns",profile=profile,sid="chat-42"))["turns"],[dict(seq=1,**original)])
        later = dict(sid="future",title="后续原档案记录",activity=20,total=1)
        self.ok(legacy.request("profile/session",later))
        self.ok(legacy.request("profile/import",dict(sid="future",start=0,turns=[original])))
        self.assertEqual({r["sid"] for r in self.ok(self.get(phone,"sessions"))["sessions"]},{"chat-42","future"})
        self.assertEqual(self.get(phone,"turns",profile=other["profile"],sid="chat-42")[0],404)
        self.assertEqual(self.first.messages(),[])

    def test_committed_legacy_room_stays_readable_when_origin_is_offline(self):
        profile = self.profile(self.pc, "A")
        original = dict(question="旧问题", answer="旧答复", status="complete")
        grant = self.ok(self.public.request("create", dict(code=self.host.pair(), sourceBase=self.first.base,
                                                          username="alice", session="chat-42", history=[original])))
        self.first.expected_key = "disabled"
        target = dict(profile=profile, sid="chat-42")
        receipt = self.ok(self.post(self.pc, "session", **target, total=0, title="旧房间", activity=1,
                                    legacyToken=grant["token"]))
        self.assertTrue(receipt["reason"])
        self.assertEqual(receipt["imported"], 1)
        self.assertEqual(self.ok(self.get(self.pc, "turns", **target))["turns"][0]["answer"], "旧答复")
        self.assertEqual(self.post(self.pc, "send", **target, id=uid(), question="禁止重建")[0], 409)
        self.assertEqual(self.first.messages(), [])
