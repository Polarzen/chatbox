"""Per-user Windows shared-device host; the desktop passes JSON over private pipes."""

from contextlib import closing, contextmanager, ExitStack
import http.client
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import uuid

if __package__:
    from . import service, device_routes, device_tunnel
    from .device_gateway import Gateway, RuntimeFault, check_gateway, https_origin
else:
    import service
    import device_routes
    import device_tunnel
    from device_gateway import Gateway, RuntimeFault, check_gateway, https_origin


# Explicit mobile operations from DEVICE_SYNC_PROTOCOL.md. Additions to service.py
# never implicitly expand the gateway. In particular no owner operation appears here.
PUBLIC_ROUTES = frozenset((method, "/v1/device/" + path) for method, paths in (
    ("GET", "health me profiles sessions turns turn operation"),
    ("POST", "preview join prove profile session import start send media-begin media-end"),
) for path in paths.split())
PROBE = "/v1/device/health"
SCRIPT = Path(__file__).resolve()


def save(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temp:
        try:
            temp.write(data)
            temp.close()
            os.replace(temp.name, path)
        finally:
            temp.close()
            Path(temp.name).unlink(missing_ok=True)


def write_json(path, value):
    save(path, json.dumps(value, ensure_ascii=True).encode("ascii"))


def read_json(path):
    return json.loads(path.read_bytes()) if path.exists() else {}


def credentials(root, data=None):
    path = root / "credentials.dpapi"
    if os.name != "nt":
        raise RuntimeFault("windows_required")
    purpose = "chatbox-device-runtime"
    if data is not None:
        save(path, device_routes.credential(path, purpose, json.dumps(data).encode()))
    elif path.exists():
        return json.loads(device_routes.credential(path, purpose, path.read_bytes(), decrypt=True))
    else:
        return {}


def autostart(root, enabled, directory=None):
    # A shortcut keeps the executable and its arguments separate. A Launcher
    # venv plus an installed module path can exceed the 260-character Run limit.
    script = """
    $ErrorActionPreference = 'Stop'
    $data = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $shell = New-Object -ComObject WScript.Shell
    $dir = if ($data.directory) { $data.directory } else { $shell.SpecialFolders.Item('Startup') }
    $path = Join-Path $dir 'Polarzen Chatbox Device Host.lnk'
    if ($data.enabled) {
        $link = $shell.CreateShortcut($path)
        $link.TargetPath = $data.program
        $link.Arguments = $data.arguments
        $link.WorkingDirectory = $data.root
        $link.Save()
    } elseif ([IO.File]::Exists($path)) { [IO.File]::Delete($path) }
    """
    powershell = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    if enabled and not pythonw.is_file():
        raise RuntimeFault("autostart_unavailable")
    arguments = subprocess.list2cmdline(["-E", "-s", str(SCRIPT), "--root", str(root), "resume"])
    data = {"enabled": enabled, "directory": str(directory) if directory else "", "program": str(pythonw),
            "arguments": arguments, "root": str(root)}
    result = subprocess.run([str(powershell), "-NoProfile", "-NonInteractive", "-Command", script],
                            input=json.dumps(data, ensure_ascii=True).encode(), capture_output=True, timeout=15,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise RuntimeFault("autostart_unavailable")


@contextmanager
def lock(path, code):
    try:
        with service.serving_lock(path):
            yield
    except service.ApiError as exc:
        if exc.status == 409 and "已有共享服务正在运行" in str(exc):
            raise RuntimeFault(code) from exc
        raise


def is_running(root):
    try:
        with lock(root / "device-host", "running"):
            return False
    except RuntimeFault as exc:
        if str(exc) != "running":
            raise
        return True


@contextmanager
def serving(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield
    finally:
        server.shutdown()
        thread.join()


def cli(db, action, body):
    program = [sys.executable, "-E", "-s", "-X", "utf8", str(SCRIPT.with_name("service.py")), "--db", str(db), action]
    result = subprocess.run(program, input=json.dumps(body, ensure_ascii=True).encode(),
                            capture_output=True, timeout=30, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode:
        raise RuntimeFault(action.replace("-", "_") + "_failed")
    try:
        return json.loads(result.stdout.decode("utf-8")) if result.stdout.strip() else {}
    except (ValueError, UnicodeError) as exc:
        raise RuntimeFault("invalid_service_response") from exc


def local_json(port, method, route, token=None, body=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    with closing(http.client.HTTPConnection("127.0.0.1", port, timeout=5)) as client:
        client.request(method, "/v1/device/" + route, json.dumps(body).encode() if body is not None else None, headers)
        response = client.getresponse()
        if not 200 <= response.status < 300:
            raise RuntimeFault("device_api_failed")
        return json.loads(response.read(65537))


def run(root, generation):
    with lock(root / "device-host", "already_running"):
        config = read_json(root / "config.json")
        if not config or config["generation"] != generation:
            return
        try:
            secret = credentials(root)
            key = secret["probeKey"]
            # open_server is the same serving lock, recovery, executor, and HTTP
            # implementation as service.py serve. An ephemeral management port
            # keeps it off old published endpoints and avoids foreign port reuse.
            with ExitStack() as stack:
                backend = stack.enter_context(service.open_server(config["dbPath"], secret["key"], 0, device_only=True))
                stack.enter_context(serving(backend))
                port = backend.server_port
                identity = local_json(port, "GET", "me", secret["token"])
                if identity.get("device") != secret["device"] or identity.get("owner") is not True:
                    raise RuntimeFault("owner_identity_mismatch")
                try:
                    gateway = stack.enter_context(Gateway(config["gatewayPort"], port, PUBLIC_ROUTES, PROBE, key, service.MAX_BODY))
                except OSError as exc:
                    raise RuntimeFault("gateway_port_in_use") from exc
                stack.enter_context(serving(gateway))
                gateway_url = f"http://127.0.0.1:{gateway.server_port}"
                check_gateway(gateway_url, PROBE, key, identity["instance"], loopback=True)
                phone, agent = stack.enter_context(device_tunnel.endpoint(root, config, gateway_url, secret.get("ngrokToken")))
                check_gateway(phone, PROBE, key, identity["instance"])
                local_json(port, "POST", "endpoint", secret["token"], {"address": phone})
                state = {"generation": generation, "localUrl": f"http://127.0.0.1:{port}", "phoneUrl": phone,
                         "instance": identity["instance"], "gatewayUrl": gateway_url}
                write_json(root / "running.json", state)
                while not (root / ("stop-" + generation)).exists():
                    if agent is not None and agent.poll() is not None:
                        raise RuntimeFault("ngrok_stopped")
                    time.sleep(.25)
        except Exception as exc:
            code = str(exc) if isinstance(exc, RuntimeFault) else "service_start_failed"
            if isinstance(exc, service.ApiError) and exc.status == 409:
                code = "db_in_use"
            write_json(root / "running.json", {"generation": generation, "error": code})
            return
        write_json(root / "running.json", {"generation": generation, "error": "service_offline"})
        (root / ("stop-" + generation)).unlink(missing_ok=True)


def status(root, config, public=True):
    if not config:
        raise RuntimeFault("not_configured")
    state = read_json(root / "running.json")
    if state.get("generation") != config["generation"]:
        raise RuntimeFault("service_offline")
    if state.get("error"):
        raise RuntimeFault(state["error"])
    if not is_running(root):
        raise RuntimeFault("service_offline")
    secret = credentials(root)
    check_gateway(state["gatewayUrl"], PROBE, secret["probeKey"], state["instance"], loopback=True)
    if public:
        check_gateway(state["phoneUrl"], PROBE, secret["probeKey"], state["instance"])
    return {**state, "mode": config["mode"], "device": secret["device"], "token": secret["token"],
            "autostart": config["autostart"]}


def start(root, config):
    if not config:
        raise RuntimeFault("not_configured")
    if is_running(root):
        return status(root, config)
    config["generation"] = str(uuid.uuid4())
    write_json(root / "config.json", config)
    command = [sys.executable, "-E", "-s", str(SCRIPT), "--root", str(root), "serve", config["generation"]]
    with open(os.devnull, "rb+") as null:
        child = subprocess.Popen(command, stdin=null, stdout=null, stderr=null, close_fds=True,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    until = time.monotonic() + 43
    while time.monotonic() < until:
        if read_json(root / "running.json").get("generation") == config["generation"]:
            return status(root, config, public=False)
        if child.poll() is not None:
            raise RuntimeFault("service_start_failed")
        time.sleep(.1)
    (root / ("stop-" + config["generation"])).touch()
    raise RuntimeFault("service_start_timeout")


def stop(root, config):
    if not is_running(root):
        return
    (root / ("stop-" + config["generation"])).touch()
    until = time.monotonic() + 15
    while is_running(root):
        if time.monotonic() >= until:
            raise RuntimeFault("service_stopping")
        time.sleep(.1)


def configure(root, request, current):
    mode = request.get("mode")
    if mode not in ("trusted", "ngrok"):
        raise RuntimeFault("invalid_mode")
    phone = https_origin(request.get("phoneUrl", "")) if mode == "trusted" or request.get("phoneUrl") else ""
    base = service.normalized_base(request.get("base", ""))
    key = request.get("key", "")
    if not isinstance(key, str) or not key or any(ord(c) < 33 or ord(c) > 126 for c in key):
        raise RuntimeFault("astrbot_key_required")
    port = request.get("gatewayPort", 18766)
    if type(port) is not int or not 1 <= port <= 65535:
        raise RuntimeFault("invalid_gateway_port")
    db = Path(request.get("dbPath") or (current.get("dbPath") or root / "device.sqlite3")).resolve()
    if (request.get("dbPath") and not db.is_file()) or (current and str(db) != current["dbPath"]):
        raise RuntimeFault("invalid_database")
    program = request.get("ngrokPath", "")
    if program and (not Path(program).is_absolute() or not Path(program).is_file() or Path(program).name.lower() != "ngrok.exe"):
        raise RuntimeFault("ngrok_missing")
    config = {"dbPath": str(db), "mode": mode, "phoneUrl": phone, "ngrokPath": program, "gatewayPort": port,
              "autostart": request.get("autostart", False) is True}
    secret = credentials(root)
    if not secret:
        secret = {"device": str(uuid.uuid4()), "token": secrets.token_urlsafe(32), "probeKey": secrets.token_hex(32)}
    new_secret = {**secret, "key": key}
    if request.get("ngrokToken"):
        new_secret["ngrokToken"] = request["ngrokToken"]
    launcher = request.get("launcherId")
    if launcher:
        try:
            verified = service.launcher_module().identity_for_url(base)
        except (OSError, ValueError) as exc:
            raise RuntimeFault("astrbot_unavailable") from exc
        if verified != launcher:
            raise RuntimeFault("astrbot_identity_mismatch")
    if db.exists():
        with service.database(db) as connection:
            row = connection.execute("SELECT upstream,launcher_id FROM config").fetchone()
        if not row or (row["launcher_id"] != launcher if row["launcher_id"] else row["upstream"] != base):
            raise RuntimeFault("database_upstream_mismatch")
    if current and all(current.get(k) == v for k, v in config.items()) and secret == new_secret and is_running(root):
        return status(root, current)
    if current:
        stop(root, current)
    with lock(db, "db_in_use"):
        if not db.exists():
            service.initialize(db, base, launcher_bound=bool(launcher))
        # Persist before first registration so a retry can recover the same owner.
        credentials(root, new_secret)
        cli(db, "device-owner", {"device": secret["device"], "token": secret["token"]})
    result = start(root, config)
    if config["autostart"] or (current and current.get("autostart")):
        autostart(root, config["autostart"])
    return result


def dispatch(root, request):
    action = request.get("action")
    config = read_json(root / "config.json")
    if action in ("status", "local"):
        return status(root, config, public=action == "status")
    if not config and action != "configure":
        raise RuntimeFault("not_configured")
    root.mkdir(parents=True, exist_ok=True)
    with lock(root / "controller", "runtime_busy"):
        config = read_json(root / "config.json")
        if action == "configure":
            return configure(root, request, config)
        if action == "start":
            return start(root, config)
        if action == "stop":
            if config["autostart"]:
                autostart(root, False)
            config["autostart"] = False
            write_json(root / "config.json", config)
            stop(root, config)
            return {}
        if action == "autostart":
            enabled = request.get("enabled")
            if type(enabled) is not bool:
                raise RuntimeFault("invalid_autostart")
            autostart(root, enabled)
            config["autostart"] = enabled
            write_json(root / "config.json", config)
            return {"autostart": enabled}
        if action == "upstream":
            body = {k: request[k] for k in ("sourceId", "base", "key", "launcherId", "aliases", "addAlias") if k in request}
            return cli(config["dbPath"], "device-upstream", body)
        raise RuntimeFault("unknown_action")


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("action", nargs="?", default="request", choices=("request", "serve", "resume"))
    parser.add_argument("generation", nargs="?")
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        if args.action == "serve":
            run(root, str(uuid.UUID(args.generation)))
            return
        if args.action == "resume":
            while read_json(root / "config.json").get("autostart") is True:
                try:
                    with lock(root / "controller", "runtime_busy"):
                        config = read_json(root / "config.json")
                        if config.get("autostart") is True:
                            start(root, config)
                    return
                except RuntimeFault as exc:
                    if str(exc) not in ("runtime_busy", "gateway_unreachable", "ngrok_start_timeout"):
                        return
                    time.sleep(10)
            return
        request = json.loads(sys.stdin.buffer.readline(2097152))
        result = {"ok": True, **dispatch(root, request)}
    except RuntimeFault as exc:
        result = {"ok": False, "error": str(exc)}
    except Exception:
        result = {"ok": False, "error": "runtime_failed"}
    if args.action == "request":
        print(json.dumps(result, ensure_ascii=True), flush=True)


if __name__ == "__main__":
    main()
