from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from shared_service import launcher


FIRST = "12345678-0000-4000-8000-00000000000a"
SECOND = "12345678-0000-4000-8000-00000000000b"
URL = "http://127.0.0.1:18759"


class LauncherTests(unittest.TestCase):
    def setUp(self):
        contexts = ExitStack()
        self.addCleanup(contexts.close)
        home = Path(contexts.enter_context(tempfile.TemporaryDirectory()))
        contexts.enter_context(patch.object(Path, "home", return_value=home))
        self.root = home / ".astrbot_launcher" / "instances"
        for identity in (FIRST, SECOND):
            (self.root / identity).mkdir(parents=True)
        self.processes = {
            10: launcher._Process(0, str(home / "astrbot-launcher.exe"), 1),
            20: launcher._Process(10, str(self.root / FIRST / "venv/Scripts/python.exe"), 2),
            30: launcher._Process(20, str(home / "runtime/python.exe"), 3),
            40: launcher._Process(10, str(self.root / SECOND / "venv/Scripts/pythonw.exe"), 4),
            50: launcher._Process(40, str(home / "runtime/pythonw.exe"), 5),
            60: launcher._Process(0, str(home / "manual/python.exe"), 6),
        }
        contexts.enter_context(patch.object(launcher, "_processes", return_value=self.processes))
        self.owners = {18759: 30}
        self.listeners = contexts.enter_context(patch.object(launcher, "_listeners", return_value=self.owners))
        self.dashboard = contexts.enter_context(patch.object(launcher, "_dashboard", side_effect=lambda p: p != 18999))

    def test_rebind_tracks_uuid_when_another_instance_reuses_port(self):
        self.assertEqual(launcher.identity_for_url(URL + "/"), FIRST)
        self.owners.update({18759: 50, 24000: 30, 18999: 30})
        self.assertEqual(launcher.url_for_identity(FIRST.upper()), "http://127.0.0.1:24000")
        self.assertEqual(launcher.identity_for_url(URL), SECOND)
        del self.owners[24000]
        with self.assertRaises(ValueError):
            launcher.url_for_identity(FIRST)

    def test_ambiguous_dashboard_ports_and_owner_changes(self):
        self.owners[20000] = 30
        with self.assertRaises(ValueError):
            launcher.identity_for_url(URL)
        self.listeners.side_effect = [{18759: 30}, {18759: 60}]
        with self.assertRaises(ValueError):
            launcher.url_for_identity(FIRST)

    def test_unrelated_processes_and_invalid_ancestry_are_never_probed(self):
        for parent, replacement in (
                (0, self.processes[20]),
                (20, self.processes[20]._replace(executable=str(self.root / FIRST / "Scripts/python.exe"))),
                (20, self.processes[20]._replace(parent=60)),
                (20, self.processes[20]._replace(born=20)),
                (20, self.processes[20]._replace(executable=str(self.root / ("0" * 36) / "venv/Scripts/python.exe"))),
                (20, self.processes[20]._replace(parent=40, born=5))):
            with self.subTest(parent=parent, replacement=replacement):
                self.processes[20] = replacement
                self.processes[30] = self.processes[30]._replace(parent=parent, born=10)
                with self.assertRaises(ValueError):
                    launcher.identity_for_url(URL)
        self.dashboard.assert_not_called()

    def test_invalid_url_and_identity(self):
        for value in ("http://localhost:18759", "https://127.0.0.1", URL + "/api", URL + "?",
                      URL + "#", "http://a@127.0.0.1:18759", "http://127.0.0.1:65536"):
            with self.subTest(url=value), self.assertRaises(ValueError):
                launcher.identity_for_url(value)
        with self.assertRaises(ValueError):
            launcher.url_for_identity("../" + FIRST)
        self.listeners.assert_not_called()


class WindowsProbeTests(unittest.TestCase):
    def test_netstat_accepts_only_unambiguous_loopback_bindings(self):
        rows = b"""
          TCP 127.0.0.1:8000 0.0.0.0:0 LISTENING 100
          TCP 0.0.0.0:8001 0.0.0.0:0 LISTENING 101
          TCP 127.0.0.1:8001 0.0.0.0:0 LISTENING 100
          TCP 127.0.0.1:8002 0.0.0.0:0 LISTENING 100
          TCP 127.0.0.1:8002 0.0.0.0:0 LISTENING 101
          TCP 127.0.0.1:8003 127.0.0.1:9988 ESTABLISHED 100
        """
        with patch.object(launcher, "_system_output", return_value=rows):
            self.assertEqual(launcher._listeners(), {8000: 100})

    def test_dashboard_title_and_redirects_over_http(self):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append((self.path, self.headers.get("Authorization")))
                status, content_type, body = self.server.reply
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                if status == 302:
                    self.send_header("Location", "/redirect-target")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
            thread.start()
            title = b"<TITLE>  AstrBot Dashboard\n</TITLE>"
            try:
                for status, mime, body, expected in (
                        (200, "text/html; charset=utf-8", title, True),
                        (200, "text/plain", title, False), (200, "text/html", b"other", False),
                        (200, "text/html", b"x" * 8192 + title, False), (302, "text/html", title, False)):
                    with self.subTest(status=status, mime=mime):
                        server.reply = (status, mime, body)
                        self.assertEqual(launcher._dashboard(server.server_port), expected)
            finally:
                server.shutdown()
                thread.join()
        self.assertEqual(requests, [("/", None)] * 5)
