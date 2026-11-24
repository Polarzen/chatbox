"""The phone listener only forwards explicitly classified device operations."""

import hashlib
import hmac
import http.client
import json
import re
import secrets
import socket
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class RuntimeFault(Exception):
    """A stable diagnostic safe to return to the desktop wizard."""


def https_origin(value):
    try:
        url = urlsplit(value)
        if (url.scheme != "https" or not url.hostname or url.username is not None or
                url.password is not None or url.path not in ("", "/") or
                any(c in value for c in "?#\\") or
                any(ord(c) <= 32 or ord(c) >= 127 for c in value) or
                url.port == 0 or url.netloc.endswith(":")):
            raise ValueError
        return "https://" + url.netloc.lower()
    except (AttributeError, TypeError, ValueError) as exc:
        raise RuntimeFault("invalid_phone_origin") from exc


def proof(key, nonce, status, body):
    message = f"{nonce}:{status}:".encode("ascii") + hashlib.sha256(body).digest()
    return hmac.digest(bytes.fromhex(key), message, "sha256").hex()


def check_gateway(origin, route, key, instance, *, loopback=False):
    """Verify a response passed through this process and reached the chosen backend."""
    origin = origin if loopback else https_origin(origin)
    url = urlsplit(origin)
    if loopback and (url.scheme != "http" or url.hostname != "127.0.0.1"):
        raise RuntimeFault("invalid_local_origin")
    connection = http.client.HTTPConnection if loopback else http.client.HTTPSConnection
    nonce = secrets.token_hex(24)
    try:
        with closing(connection(url.hostname, url.port, timeout=5)) as client:
            client.request("GET", route, headers={"X-Chatbox-Probe": nonce, "ngrok-skip-browser-warning": "1"})
            response = client.getresponse()
            data = response.read(16385)
            signature = response.getheader("X-Chatbox-Proof", "")
            if (response.status != 200 or len(data) > 16384 or
                    not hmac.compare_digest(signature, proof(key, nonce, response.status, data))):
                raise RuntimeFault("gateway_identity_mismatch")
            health = json.loads(data)
            if not isinstance(health, dict) or health.get("instance") != instance or health.get("version") != 1:
                raise RuntimeFault("gateway_identity_mismatch")
    except (OSError, http.client.HTTPException, ValueError) as exc:
        raise RuntimeFault("gateway_unreachable") from exc


class Gateway(ThreadingHTTPServer):
    allow_reuse_address = False
    daemon_threads = False

    def __init__(self, port, backend_port, routes, probe_route, key, max_body):
        self.backend_port = backend_port
        self.routes = frozenset(routes)
        self.probe_route = probe_route
        self.key = key
        self.max_body = max_body
        if ("GET", probe_route) not in self.routes:
            raise ValueError("Health probe must be an allowed GET operation")
        super().__init__(("127.0.0.1", port), GatewayHandler)

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    def handle_error(self, request, client_address):
        pass


class GatewayHandler(BaseHTTPRequestHandler):
    timeout = 20

    def log_message(self, *args):
        pass

    def reply(self, status, body, signature=None):
        self.close_connection = True
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.send_header("Content-Length", str(len(body)))
        if signature:
            self.send_header("X-Chatbox-Proof", signature)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_error(self, code, message=None, explain=None):
        self.reply(code, json.dumps({"error": "Request rejected"}).encode())

    def dispatch(self):
        # BaseHTTPRequestHandler normalizes a leading // before dispatch.
        target = self.requestline.split()[1]
        path, sep, query = target.partition("?")
        if (self.command, path) not in self.server.routes:
            return self.send_error(404)
        if ("#" in target or "\\" in target or (sep and self.command != "GET") or
                self.headers.get("Transfer-Encoding") is not None):
            return self.send_error(400)
        headers = {}
        for name in ("Authorization", "Content-Type"):
            values = self.headers.get_all(name, [])
            if len(values) > 1:
                return self.send_error(400)
            if values:
                headers[name] = values[0]
        lengths = self.headers.get_all("Content-Length", [])
        if self.command == "POST":
            if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,9}", lengths[0]):
                return self.send_error(400)
            size = int(lengths[0])
            if size > self.server.max_body:
                return self.send_error(413)
        elif lengths not in ([], ["0"]):
            return self.send_error(400)
        else:
            size = 0
        try:
            body = self.rfile.read(size) if size else None
            if body is not None and len(body) != size:
                return self.send_error(400)
            with closing(http.client.HTTPConnection("127.0.0.1", self.server.backend_port, timeout=30)) as client:
                client.request(self.command, target, body, headers)
                response = client.getresponse()
                data = response.read()
                nonce = self.headers.get("X-Chatbox-Probe", "")
                signature = None
                if self.command == "GET" and path == self.server.probe_route and re.fullmatch("[a-f0-9]{48}", nonce):
                    signature = proof(self.server.key, nonce, response.status, data)
                self.reply(response.status, data, signature)
        except (OSError, ValueError, http.client.HTTPException):
            self.send_error(502)

    do_GET = dispatch
    do_POST = dispatch
