"""Exercise two independent HTTP clients against the service and a counted AstrBot fixture."""

from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from urllib.parse import parse_qs, urlsplit
import uuid


def uid():
    return str(uuid.uuid4())


class Upstream(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 0), ReplyHandler)
        self.calls = []
        self.session_queries = []
        self.rejected_messages = []
        self.expected_key = "fixture-key"
        self.lock = threading.Lock()
        self.release = threading.Event()
        self.base = f"http://127.0.0.1:{self.server_port}/fixture"

    def messages(self):
        with self.lock:
            return [body["message"] for _, _, body in self.calls]


class ReplyHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def event(self, **data):
        payload = json.dumps(data, ensure_ascii=False).encode()
        self.wfile.write(b"data: " + payload + b"\r\n\r\n")
        self.wfile.flush()

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path != "/fixture/api/v1/chat/sessions":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != "Bearer " + self.server.expected_key:
            self.send_error(403)
            return
        params = parse_qs(url.query)
        try:
            username = params["username"][0]
            page = int(params["page"][0])
            size = int(params["page_size"][0])
            if params.get("platform_id") != ["webchat"] or page < 1 or not 1 <= size <= 100:
                raise ValueError
        except (KeyError, ValueError):
            self.send_error(400)
            return
        session = {"alice": "chat-42", "bob": "chat-43"}.get(username)
        if session in getattr(self.server, "removed_sessions", ()):
            session = None
        with self.server.lock:
            self.server.session_queries.append(username)
        rows = [{"session_id": session, "creator": username, "platform_id": "webchat"}] if session else []
        data = dict(page=page, page_size=size, total=len(rows), sessions=rows[(page-1)*size:page*size])
        payload = json.dumps({"status": "ok", "data": data}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path != "/fixture/api/v1/chat":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != "Bearer " + self.server.expected_key:
            with self.server.lock:
                self.server.rejected_messages.append(body["message"])
            self.send_error(403)
            return
        with self.server.lock:
            self.server.calls.append((self.path, self.headers.get("Authorization"), body))
        question = body["message"]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.end_headers()
        try:
            if getattr(self.server, "run_id", None):
                self.event(type="run_started", data={"run_id": self.server.run_id}, message_id=self.server.run_id)
            self.event(type="plain", data="片段", streaming=True)
            if question == "等待":
                self.server.release.wait(20)
            if question == "上游断流":
                return
            self.event(type="plain", data="片段完成", streaming=False)
            self.event(type="plain", data="内部思考", chain_type="reasoning")
            self.event(type="plain", data="工具参数", chain_type="tool_call")
            self.event(type="plain", data="工具结果", chain_type="tool_call_result")
            self.event(type="image", data="[IMAGE]fixture")
            self.event(type="attachment_saved", data={"type": "image", "id": "attachment-fixture"})
            self.event(type="end")
        except (ConnectionError, OSError):
            # A crash-recovery test explicitly terminates the service mid-stream.
            pass


class Client:
    def __init__(self, port, token=None):
        self.port = port
        self.token = token

    def request(self, path, body=None, discard=False):
        conn = HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, ensure_ascii=False).encode()
        try:
            conn.request("POST" if body is not None else "GET", "/v1/" + path, data, headers)
            if discard:
                return
            response = conn.getresponse()
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
        finally:
            conn.close()


class ServiceProcess:
    script = Path(__file__).resolve().parents[1] / "service.py"

    def __init__(self, test, base):
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.db = self.directory / "service.sqlite"
        self.log = self.directory / "console.txt"
        self.env = dict(os.environ, ASTRBOT_CHAT_KEY="fixture-key", PYTHONIOENCODING="utf-8")
        self.base = base
        self.port = 0
        self.process = None
        test.addCleanup(self.stop)

    def cli(self, *args):
        result = subprocess.run([sys.executable, self.script, "--db", self.db, *args],
                                capture_output=True, encoding="utf-8", env=self.env, timeout=10)
        if result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    def start(self):
        self.cli("init", "--upstream", self.base)
        self.serve()
        return self.pair()

    def pair(self):
        return self.cli("bootstrap")

    def serve(self, clock_offset=0):
        command = [sys.executable]
        if clock_offset:
            # Advance only the child's wall clock. All token issuance and expiry checks still
            # pass through the normal CLI and HTTP service with the same database.
            command += ["-c", """
import runpy, sys, time
from pathlib import Path
offset = int(sys.argv[1])
del sys.argv[:2]
wall_time = time.time
time.time = lambda: wall_time() + offset
sys.path.insert(0, str(Path(sys.argv[0]).parent))
runpy.run_path(sys.argv[0], run_name="__main__")
""", str(clock_offset)]
        command += [self.script, "--db", self.db, "serve", "--port", str(self.port)]
        with self.log.open("wb") as output:
            self.process = subprocess.Popen(command, env=self.env, stdin=subprocess.DEVNULL,
                                            stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 8
        while True:
            output = self.log.read_text(encoding="utf-8")
            if "http://127.0.0.1:" in output and output.endswith("\n"):
                self.port = int(output.strip().rsplit(":", 1)[1])
                return
            if self.process.poll() is not None or time.monotonic() >= deadline:
                raise AssertionError(f"service failed to start: {output}")
            time.sleep(0.02)

    def stop(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.kill()
            self.process.wait(timeout=10)
            self.process = None

    def restart(self, clock_offset=0):
        self.stop()
        self.serve(clock_offset)


class SharedServiceTests(unittest.TestCase):
    def setUp(self):
        self.upstream = Upstream()
        thread = threading.Thread(target=self.upstream.serve_forever, kwargs={"poll_interval": 0.02})
        thread.start()
        self.addCleanup(self.upstream.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(self.upstream.shutdown)
        self.addCleanup(self.upstream.release.set)
        self.service = ServiceProcess(self, self.upstream.base)
        self.code = self.service.start()
        self.public = Client(self.service.port)

    def create_body(self, code, history=None):
        return dict(code=code, sourceBase=self.upstream.base, username="alice", session="chat-42",
                    history=history or [])

    def success(self, response):
        status, body = response
        self.assertTrue(200 <= status < 300, response)
        return body

    def clients(self, history=None):
        self.owner_info = self.success(self.public.request("create", self.create_body(self.code, history)))
        self.assertIs(self.owner_info["owner"], True)
        owner = Client(self.service.port, self.owner_info["token"])
        invite = self.success(owner.request("invite", {}))
        self.assertGreater(invite["expiresIn"], 0)
        self.guest_info = self.success(self.public.request("join", {"code": invite["code"]}))
        self.assertIs(self.guest_info["owner"], False)
        self.assertEqual(self.owner_info["room"], self.guest_info["room"])
        self.assertEqual(self.owner_info["instance"], self.guest_info["instance"])
        self.assertNotEqual(self.owner_info["token"], self.guest_info["token"])
        return owner, Client(self.service.port, self.guest_info["token"])

    def page(self, client, after=0, limit=50):
        return self.success(client.request(f"turns?after={after}&limit={limit}"))

    def await_turn(self, client, identifier, status, answer=None):
        deadline = time.monotonic() + 6
        while True:
            response = client.request("turn?id=" + identifier)
            body = response[1]
            turn = body.get("turn", body)
            if response[0] == 200 and turn.get("status") == status:
                if answer is None or turn["answer"] == answer:
                    return turn
            if time.monotonic() >= deadline:
                self.fail(f"expected {status} {answer!r}, last response {response!r}")
            time.sleep(0.02)

    def test_seed_page_resume_and_offline_catchup(self):
        history = [
            {"question": "文件〔附件：草稿.txt〕", "answer": "〔图片〕", "status": "complete"},
            {"question": "第二问", "answer": "未完成", "status": "unknown"},
            {"question": "第三问", "answer": "旧回复", "status": "complete"},
        ]
        owner, guest = self.clients(history)
        first = self.page(guest, limit=2)
        self.assertIs(first["more"], True)
        self.assertEqual([t["seq"] for t in first["turns"]], [1, 2])
        guest.request("turns?after=2&limit=2", discard=True)
        self.assertEqual(self.page(guest, limit=2), first)
        self.assertEqual(self.upstream.messages(), [])
        for _ in range(2):
            identifier = uid()
            self.success(owner.request("send", {"id": identifier, "question": "重复文字"}))
            self.await_turn(owner, identifier, "complete")
        rest = self.page(guest, after=2)
        self.assertIs(rest["more"], False)
        merged = first["turns"] + rest["turns"]
        self.assertEqual(merged, self.page(owner)["turns"])
        self.assertEqual([t["seq"] for t in merged], list(range(1, 6)))
        self.assertEqual(len({t["id"] for t in merged}), 5)
        self.assertEqual([{k: t[k] for k in ("question", "answer", "status")} for t in merged[:3]], history)
        self.assertEqual(self.page(guest, after=5)["turns"], [])
        self.assertEqual(self.upstream.messages(), ["重复文字", "重复文字"])

    def test_import_checks_source_and_holds_only_the_unresolved_tail(self):
        history = [
            {"question": "旧记录", "answer": "已有结果", "status": "complete"},
            {"question": "末轮", "answer": "部分内容", "status": "unknown"},
        ]
        missing = self.create_body(self.code, history)
        missing["session"] = "missing-session"
        self.assertEqual(self.public.request("create", missing)[0], 409)
        self.assertEqual(self.service.cli("rooms"), "")
        wrong_creator = self.create_body(self.code, history)
        wrong_creator["username"] = "bob"
        self.assertEqual(self.public.request("create", wrong_creator)[0], 409)
        self.assertEqual(self.service.cli("rooms"), "")
        owner, guest = self.clients(history)
        self.assertEqual(self.upstream.session_queries, ["alice", "bob", "alice"])
        room = self.owner_info["room"]
        latest = self.page(guest)["turns"][-1]
        self.assertEqual(latest["status"], "unknown")
        self.assertEqual(self.service.cli("unresolved", "--room", room).split(), [room, latest["id"]])
        payload = {"id": uid(), "question": "确认后继续"}
        self.assertEqual(owner.request("send", payload)[0], 409)
        self.assertEqual(self.upstream.messages(), [])
        self.service.cli("resolve", "--room", room, "--id", latest["id"])
        self.success(owner.request("send", payload))
        self.await_turn(guest, payload["id"], "complete")
        self.assertEqual(self.page(owner)["turns"][1], latest)
        self.assertEqual(self.upstream.messages(), ["确认后继续"])

    def test_explicit_upstream_auth_rejection_allows_next_send(self):
        owner, guest = self.clients()
        self.upstream.expected_key = "rotated-key"
        denied = {"id": uid(), "question": "上游拒绝授权"}
        self.success(owner.request("send", denied))
        self.await_turn(guest, denied["id"], "unknown")
        self.assertEqual(self.service.cli("unresolved", "--room", self.owner_info["room"]), "")
        self.assertEqual(self.upstream.rejected_messages, [denied["question"]])
        self.assertEqual(self.upstream.messages(), [])
        self.upstream.expected_key = "fixture-key"
        fresh = {"id": uid(), "question": "凭据恢复"}
        self.success(guest.request("send", fresh))
        self.await_turn(owner, fresh["id"], "complete")
        self.assertEqual(self.upstream.messages(), ["凭据恢复"])

    def test_simultaneous_duplicate_busy_and_visible_stream(self):
        owner, guest = self.clients()
        identifier = uid()
        payload = {"id": identifier, "question": "等待"}
        gate = threading.Barrier(2)

        def submit(client):
            gate.wait(3)
            return client.request("send", payload)

        with ThreadPoolExecutor(max_workers=2) as workers:
            for response in workers.map(submit, (owner, guest)):
                self.success(response)
        self.await_turn(guest, identifier, "running", "片段")
        self.assertEqual(owner.request("send", {"id": uid(), "question": "其他"})[0], 409)
        self.assertEqual(self.upstream.messages(), ["等待"])
        self.upstream.release.set()
        final = self.await_turn(guest, identifier, "complete", "片段完成〔图片〕")
        self.success(guest.request("send", payload))
        self.assertEqual(guest.request("send", dict(payload, question="改写"))[0], 409)
        self.assertEqual(self.page(owner)["turns"], [final])
        self.assertEqual(self.upstream.messages(), ["等待"])
        self.assertEqual(self.upstream.calls[0][2], dict(
            username="alice", session_id="chat-42", message="等待", enable_streaming=True))

    def test_lost_acceptance_and_upstream_eof(self):
        owner, guest = self.clients()
        for question, status, answer in (
            ("响应丢失", "complete", "片段完成〔图片〕"),
            ("上游断流", "unknown", "片段"),
        ):
            payload = {"id": uid(), "question": question}
            owner.request("send", payload, discard=True)
            self.await_turn(guest, payload["id"], status, answer)
            self.success(guest.request("send", payload))
            self.await_turn(owner, payload["id"], status, answer)
        self.assertEqual(self.upstream.messages(), ["响应丢失", "上游断流"])
        self.assertEqual(len(self.page(owner)["turns"]), 2)

    def test_invite_lifecycle_and_room_access(self):
        self.assertEqual(self.public.request("join", {"code": self.code})[0], 403)
        owner, guest = self.clients()
        self.assertEqual(self.public.request("create", self.create_body(self.code))[0], 403)
        identifier = uid()
        payload = {"id": identifier, "question": "授权设备"}
        self.success(guest.request("send", payload))
        self.await_turn(owner, identifier, "complete")

        for credential in (None, self.owner_info["room"], self.owner_info["instance"], identifier):
            with self.subTest(credential=credential):
                client = Client(self.service.port, credential)
                self.assertEqual(client.request("turns")[0], 401)
                self.assertEqual(client.request("turn?id=" + identifier)[0], 401)
                self.assertEqual(client.request("send", payload)[0], 401)
        for action in ("invite", "cancel-invite", "revoke-guests"):
            self.assertEqual(guest.request(action, {})[0], 403)

        cancelled = self.success(owner.request("invite", {}))["code"]
        self.success(owner.request("cancel-invite", {}))
        self.assertEqual(self.public.request("join", {"code": cancelled})[0], 403)
        invite = self.success(owner.request("invite", {}))["code"]
        second = self.success(self.public.request("join", {"code": invite}))
        self.assertEqual(self.public.request("join", {"code": invite})[0], 403)
        self.success(owner.request("revoke-guests", {}))
        for client in (guest, Client(self.service.port, second["token"])):
            self.assertEqual(client.request("turns")[0], 401)
            self.assertEqual(client.request("send", payload)[0], 401)
        self.assertEqual(self.page(owner)["turns"][0]["id"], identifier)
        self.assertEqual(self.upstream.messages(), ["授权设备"])

    def test_terminal_owner_recovery_preserves_paired_guest(self):
        owner, guest = self.clients([{"question": "先前记录", "answer": "已保存", "status": "complete"}])
        previous = self.page(owner)["turns"]
        room = json.loads(self.service.cli("rooms"))
        self.assertEqual(room, {"id": self.owner_info["room"], "username": "alice", "session": "chat-42"})
        recovery = self.service.cli("recover-owner", "--room", room["id"])
        outstanding = self.success(owner.request("invite", {}))["code"]

        info = self.success(self.public.request("join", {"code": recovery}))
        self.assertIs(info["owner"], True)
        self.assertEqual(info["room"], room["id"])
        self.assertEqual(info["instance"], self.owner_info["instance"])
        self.assertNotEqual(info["token"], owner.token)
        restored = Client(self.service.port, info["token"])
        self.assertEqual(self.page(restored)["turns"], previous)
        self.assertEqual(owner.request("turns")[0], 401)
        self.assertEqual(owner.request("invite", {})[0], 401)
        for spent in (recovery, outstanding):
            self.assertEqual(self.public.request("join", {"code": spent})[0], 403)
        self.success(restored.request("invite", {}))

        self.assertEqual(guest.request("invite", {})[0], 403)
        self.assertEqual(self.page(guest)["turns"], previous)
        payload = {"id": uid(), "question": "原访客继续"}
        self.success(guest.request("send", payload))
        self.await_turn(restored, payload["id"], "complete")
        self.assertEqual(self.upstream.messages(), ["原访客继续"])

    def test_binding_and_cross_room_isolation(self):
        wrong = self.create_body(self.code)
        wrong["sourceBase"] += "/other-profile"
        self.assertEqual(self.public.request("create", wrong)[0], 409)
        owner, _ = self.clients()
        code = self.service.pair()
        second = self.create_body(code)
        second["username"] = "bob"
        self.assertEqual(self.public.request("create", second)[0], 409)
        second["session"] = "chat-43"
        new_room = self.success(self.public.request("create", second))
        other = Client(self.service.port, new_room["token"])
        identifier = uid()
        for client, user in ((owner, "alice"), (other, "bob")):
            self.success(client.request("send", {"id": identifier, "question": user}))
            self.await_turn(client, identifier, "complete")
            if client is owner:
                self.assertEqual(self.page(other)["turns"], [])
                self.assertEqual(other.request("turn?id=" + identifier)[0], 404)
        self.assertEqual([t["question"] for t in self.page(owner)["turns"]], ["alice"])
        self.assertEqual([t["question"] for t in self.page(other)["turns"]], ["bob"])
        self.assertEqual([(body["username"], body["session_id"]) for _, _, body in self.upstream.calls],
                         [("alice", "chat-42"), ("bob", "chat-43")])

    def test_expired_codes(self):
        owner, _ = self.clients()
        invite = self.success(owner.request("invite", {}))
        bootstrap = self.service.pair()
        self.service.restart(clock_offset=invite["expiresIn"] + 1)
        self.assertEqual(self.public.request("join", {"code": invite["code"]})[0], 403)
        self.assertEqual(self.public.request("create", self.create_body(bootstrap))[0], 403)
        fresh = self.success(owner.request("invite", {}))
        self.success(self.public.request("join", {"code": fresh["code"]}))
        self.assertEqual(self.upstream.messages(), [])

    def test_crash_during_generation_recovers_without_replay(self):
        owner, guest = self.clients()
        payload = {"id": uid(), "question": "等待"}
        self.success(owner.request("send", payload))
        before = self.await_turn(guest, payload["id"], "running", "片段")
        self.service.restart()
        after = self.await_turn(guest, payload["id"], "unknown", "片段")
        self.assertEqual(after, dict(before, status="unknown"))
        self.success(guest.request("send", payload))
        fresh = {"id": uid(), "question": "继续"}
        self.assertEqual(owner.request("send", fresh)[0], 409)
        self.assertEqual(self.page(owner)["turns"], [after])
        self.assertEqual(self.upstream.messages(), ["等待"])
        self.upstream.release.set()
        room = self.owner_info["room"]
        self.assertEqual(self.service.cli("unresolved", "--room", room).split(), [room, payload["id"]])
        self.service.cli("resolve", "--room", room, "--id", payload["id"])
        self.success(owner.request("send", fresh))
        self.await_turn(guest, fresh["id"], "complete")
        self.assertEqual(self.page(owner)["turns"][0], after)
        self.assertEqual(self.upstream.messages(), ["等待", "继续"])


if __name__ == "__main__":
    unittest.main()
