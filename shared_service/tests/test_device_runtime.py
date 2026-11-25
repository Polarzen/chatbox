"""Loopback-only coverage for the Windows host and the phone trust boundary."""

from contextlib import closing, ExitStack
import ctypes
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import unittest

from shared_service import device_runtime as runtime
from shared_service.device_gateway import Gateway, RuntimeFault, check_gateway
from shared_service.device_tunnel import selected_url


class RecordingBackend(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def answer(self):
        self.server.requests.append((self.command, self.path))
        health = self.command == "GET" and self.path == runtime.PROBE
        body = b'{"instance":"fixture","version":1}' if health else b"{}"
        self.send_response(200 if health else 401)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = answer
    do_POST = answer


class RuntimeBoundaryTest(unittest.TestCase):
    def test_gateway_policy_and_identity_over_http(self):
        with ExitStack() as stack:
            backend = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), RecordingBackend))
            backend.requests = []
            stack.enter_context(runtime.serving(backend))
            key = secrets.token_hex(32)
            gate = stack.enter_context(Gateway(0, backend.server_port, runtime.PUBLIC_ROUTES, runtime.PROBE, key, 1024))
            stack.enter_context(runtime.serving(gate))
            origin = f"http://127.0.0.1:{gate.server_port}"
            check_gateway(origin, runtime.PROBE, key, "fixture", loopback=True)
            with self.assertRaisesRegex(RuntimeFault, "gateway_identity_mismatch"):
                check_gateway(origin, runtime.PROBE, key, "different-instance", loopback=True)
            with self.assertRaisesRegex(RuntimeFault, "gateway_identity_mismatch"):
                check_gateway(origin, runtime.PROBE, secrets.token_hex(32), "fixture", loopback=True)
            with self.assertRaisesRegex(RuntimeFault, "gateway_identity_mismatch"):
                check_gateway(f"http://127.0.0.1:{backend.server_port}", runtime.PROBE, key, "fixture", loopback=True)
            before = len(backend.requests)
            denied = [("POST", "/v1/device/" + route) for route in ("invite", "revoke", "endpoint", "remove")]
            denied += [("GET", path) for path in ("/", "/v1/profile", "/api/tunnels", "/v1/device/devices",
                                                  "/v1/device/me/", "/v1/device/join", "//v1/device/me")]
            for method, path in denied:
                with self.subTest(method=method, path=path), closing(http.client.HTTPConnection("127.0.0.1", gate.server_port)) as client:
                    client.request(method, path, b"{}" if method == "POST" else None)
                    self.assertEqual(client.getresponse().status, 404)
            self.assertEqual(before, len(backend.requests))
            for method, path in runtime.PUBLIC_ROUTES:
                with closing(http.client.HTTPConnection("127.0.0.1", gate.server_port)) as client:
                    client.request(method, path, b"{}" if method == "POST" else None)
                    self.assertEqual(client.getresponse().status, 200 if path == runtime.PROBE else 401)
            self.assertEqual(len(backend.requests), before + len(runtime.PUBLIC_ROUTES))

    def test_tunnel_selection_requires_target_https_and_no_inspection(self):
        target, public = "http://127.0.0.1:18766", "https://example.invalid"
        def entry(addr, inspect=False):
            return {"public_url": public, "proto": "https", "config": {"addr": addr, "inspect": inspect}}
        self.assertEqual(selected_url([entry(target)], target, public), public)
        with self.assertRaisesRegex(RuntimeFault, "ngrok_target_mismatch"):
            selected_url([entry("http://127.0.0.1:8765")], target, public)
        with self.assertRaisesRegex(RuntimeFault, "ngrok_inspection_enabled"):
            selected_url([entry(target, True)], target, public)
        with self.assertRaisesRegex(RuntimeFault, "ngrok_insecure_endpoint"):
            selected_url([entry(target), dict(entry(target), proto="http")], target, public)

    def test_unconfigured_read_has_no_side_effects(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "never-started"
            with self.assertRaisesRegex(RuntimeFault, "not_configured"):
                runtime.dispatch(root, {"action": "status"})
            self.assertFalse(root.exists())

    @unittest.skipUnless(os.name == "nt", "Windows job objects")
    def test_child_ends_on_unexpected_host_exit(self):
        script = ("from shared_service.device_tunnel import own_child_lifetime\n"
                  "import subprocess,sys,time\nown_child_lifetime()\n"
                  "child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(20)'],stdout=subprocess.DEVNULL)\n"
                  "print(child.pid,flush=True)\ntime.sleep(20)")
        with subprocess.Popen([sys.executable, "-c", script], stdout=subprocess.PIPE, text=True) as host:
            try:
                pid = int(host.stdout.readline())
                kernel = ctypes.windll.kernel32
                kernel.OpenProcess.restype = ctypes.c_void_p
                child = ctypes.c_void_p(kernel.OpenProcess(0x100000, False, pid))
                self.assertTrue(child.value)
                try:
                    host.kill()
                    host.wait(5)
                    self.assertEqual(kernel.WaitForSingleObject(child, 5000), 0)
                finally:
                    kernel.CloseHandle(child)
            finally:
                if host.poll() is None:
                    host.kill()


class LocalTlsProxy(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        with closing(http.client.HTTPConnection("127.0.0.1", self.server.gateway)) as client:
            client.request("GET", self.path, headers={"X-Chatbox-Probe": self.headers.get("X-Chatbox-Probe", "")})
            response = client.getresponse()
            self.send_response(response.status)
            if signature := response.getheader("X-Chatbox-Proof"):
                self.send_header("X-Chatbox-Proof", "wrong-host" if self.server.wrong_host else signature)
            data = response.read()
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)


@unittest.skipUnless(os.name == "nt", "Windows current-user DPAPI and private runtime")
class RuntimeLifecycleTest(unittest.TestCase):
    def test_managed_tunnel_process_and_local_agent_api(self):
        # Python interprets the "http" subcommand as a script relative to cwd.
        # This exercises the real argv, environment, pipe, process and HTTP check
        # without an external agent, account, or modification of provider config.
        fake_agent = """
import http.server,json,os,pathlib,re,sys
args = sys.argv[1:]
assert os.environ['NGROK_AUTHTOKEN'] == 'fixture-token' and 'fixture-token' not in args
assert '--inspect=false' in args
config = pathlib.Path(args[args.index('--config')+1]).read_text()
api_port = int(re.search(r'web_addr: 127[.]0[.]0[.]1:(\\d+)',config)[1])
class Api(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        assert self.path == '/api/tunnels'
        data = json.dumps({'tunnels':[{'proto':'https','public_url':args[args.index('--url')+1],
                          'config':{'addr':args[0],'inspect':False}}]}).encode()
        self.send_response(200)
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)
http.server.HTTPServer(('127.0.0.1',api_port),Api).serve_forever()
"""
        host = """
import os,sys
from pathlib import Path
from shared_service import device_tunnel as t
root=Path(sys.argv[1])
os.chdir(root)
read=t.tunnels
t.tunnels=lambda port: [] if port==4040 else read(port)
t.default_config=lambda: None
config={'mode':'ngrok','ngrokPath':sys.executable,'phoneUrl':'https://fixture.invalid'}
with t.endpoint(root,config,'http://127.0.0.1:18766','fixture-token') as (url,process):
    assert url == config['phoneUrl'] and process.poll() is None
assert process.poll() is not None
"""
        with tempfile.TemporaryDirectory() as temp:
            (Path(temp) / "http").write_text(fake_agent, encoding="utf-8")
            subprocess.run([sys.executable, "-c", host, temp], check=True, timeout=35)

    def test_selected_database_in_use_is_left_with_its_owner(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / "existing.sqlite3"
            root = Path(temp) / "wizard"
            instance = runtime.service.initialize(db, "http://127.0.0.1:1")
            with runtime.service.serving_lock(db):
                with self.assertRaisesRegex(RuntimeFault, "db_in_use"):
                    runtime.dispatch(root, {"action": "configure", "mode": "trusted", "phoneUrl": "https://localhost",
                                           "base": "http://127.0.0.1:1", "key": "fixture", "dbPath": str(db)})
            self.assertFalse((root / "config.json").exists())
            with runtime.service.database(db) as connection:
                self.assertEqual(connection.execute("SELECT instance FROM config").fetchone()[0], instance)

    def test_login_shortcut_in_isolated_folder(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            shortcut = root / "Polarzen Chatbox Device Host.lnk"
            runtime.autostart(root, True, directory=root)
            self.assertTrue(shortcut.is_file())
            runtime.autostart(root, True, directory=root)
            self.assertEqual(len(list(root.glob("*.lnk"))), 1)
            runtime.autostart(root, False, directory=root)
            self.assertFalse(shortcut.exists())

    def test_configure_repeat_stop_restart_via_pipe_and_tls(self):
        openssl = shutil.which("openssl") or str(Path(os.environ["ProgramFiles"], "Git", "usr", "bin", "openssl.exe"))
        if not Path(openssl).is_file():
            self.skipTest("OpenSSL fixture certificate generator unavailable")
        with tempfile.TemporaryDirectory() as temp, ExitStack() as stack:
            root = Path(temp) / "host"
            cert, pem = Path(temp) / "cert.pem", Path(temp) / "key.pem"
            subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj",
                            "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1", "-keyout", str(pem), "-out", str(cert)],
                           check=True, capture_output=True)
            with socket.socket() as spare:
                spare.bind(("127.0.0.1", 0))
                gateway_port = spare.getsockname()[1]
            proxy = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), LocalTlsProxy))
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(cert, pem)
            proxy.socket = tls.wrap_socket(proxy.socket, server_side=True)
            proxy.gateway = gateway_port
            proxy.wrong_host = False
            stack.enter_context(runtime.serving(proxy))
            def call(action, **fields):
                args = [sys.executable, str(runtime.SCRIPT), "--root", str(root)]
                reply = subprocess.run(args, input=json.dumps({"action": action, **fields}).encode(),
                                       capture_output=True, check=True, timeout=60, env={**os.environ, "SSL_CERT_FILE": str(cert)})
                return json.loads(reply.stdout)
            stack.callback(call, "stop")
            config = {"mode": "trusted", "base": "http://127.0.0.1:1", "key": secrets.token_hex(32),
                      "gatewayPort": gateway_port, "phoneUrl": f"https://127.0.0.1:{proxy.server_port}"}
            first = call("configure", **config)
            self.assertTrue(first["ok"], first.get("error"))
            with closing(http.client.HTTPConnection(first["localUrl"].removeprefix("http://"))) as client:
                client.request("GET", "/v1/turns")
                self.assertEqual(client.getresponse().status, 404)
            second = call("configure", **config)
            self.assertTrue(second["ok"], second.get("error"))
            self.assertEqual(first["generation"], second["generation"])
            self.assertTrue(call("status")["ok"])
            proxy.wrong_host = True
            self.assertEqual(call("status").get("error"), "gateway_identity_mismatch")
            self.assertTrue(call("local")["ok"])
            proxy.wrong_host = False
            self.assertTrue(call("stop")["ok"])
            self.assertFalse(call("status")["ok"])
            with socket.socket() as foreign:
                foreign.bind(("127.0.0.1", gateway_port))
                foreign.listen()
                self.assertEqual(call("start").get("error"), "gateway_port_in_use")
                with socket.create_connection(("127.0.0.1", gateway_port), timeout=2):
                    peer, _ = foreign.accept()
                    peer.close()
            third = call("start")
            self.assertTrue(third["ok"], third.get("error"))
            self.assertNotEqual(first["generation"], third["generation"])
            self.assertEqual(first["instance"], third["instance"])
            self.assertEqual(first["device"], third["device"])
            self.assertTrue(secrets.compare_digest(first["token"], third["token"]))
            self.assertFalse(third["autostart"])
            for filename in ("config.json", "running.json", "credentials.dpapi"):
                raw = (root / filename).read_bytes()
                self.assertFalse(config["key"].encode() in raw or first["token"].encode() in raw)


if __name__ == "__main__":
    unittest.main()
