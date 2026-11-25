"""Start an ngrok HTTP endpoint only for the selected loopback gateway."""

import http.client
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import time
from contextlib import closing, contextmanager

if __package__:
    from . import launcher
    from .device_gateway import RuntimeFault, https_origin
else:
    import launcher
    from device_gateway import RuntimeFault, https_origin


def tunnels(port):
    try:
        # Windows can take just over two seconds to report a refused loopback connection.
        with closing(http.client.HTTPConnection("127.0.0.1", port, timeout=4)) as client:
            client.request("GET", "/api/tunnels")
            response = client.getresponse()
            if response.status != 200:
                raise RuntimeFault("ngrok_api_unavailable")
            entries = json.loads(response.read(65537))["tunnels"]
            if not isinstance(entries, list):
                raise ValueError
            return entries
    except ConnectionRefusedError:
        return []
    except (OSError, http.client.HTTPException, KeyError, ValueError, TypeError) as exc:
        raise RuntimeFault("ngrok_api_unavailable") from exc


def selected_url(entries, gateway_url, requested):
    matches = set()
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        config = entry.get("config") or {}
        target = config.get("addr", "").rstrip("/")
        if entry.get("proto") == "http" and target == gateway_url:
            raise RuntimeFault("ngrok_insecure_endpoint")
        if entry.get("proto") != "https":
            continue
        public = https_origin(entry.get("public_url"))
        if requested and public != requested:
            continue
        if target != gateway_url:
            if requested:
                raise RuntimeFault("ngrok_target_mismatch")
            continue
        if config.get("inspect") is not False:
            raise RuntimeFault("ngrok_inspection_enabled")
        matches.add(public)
    if len(matches) > 1:
        raise RuntimeFault("ngrok_target_ambiguous")
    return next(iter(matches), None)


def default_config():
    locations = []
    if os.getenv("LOCALAPPDATA"):
        locations.append(Path(os.environ["LOCALAPPDATA"]) / "ngrok/ngrok.yml")
    locations += [Path.home() / ".config/ngrok/ngrok.yml", Path.home() / ".ngrok2/ngrok.yml"]
    return next((path for path in locations if path.is_file()), None)


def _authtoken(value):
    prefix = "ngrok config add-authtoken "
    if value.startswith(prefix):
        value = value[len(prefix):]
        if len(value) >= 2 and value[0] in ("'", '"') and value[-1] == value[0]:
            value = value[1:-1]
    if not re.fullmatch(r"""[^\s'"\x00]+""", value):
        raise RuntimeFault("ngrok_auth_required")
    return value


def own_child_lifetime():
    """Our dedicated daemon and its descendants share a kill-on-last-handle job.

    Joining before CreateProcess closes the crash window between spawning and
    assigning ngrok. The job handle is non-inheritable and deliberately held by
    this daemon until Windows tears down its handle table.
    """
    if os.name != "nt":
        raise RuntimeFault("windows_required")
    size = ctypes.c_size_t
    class Limits(ctypes.Structure):
        _fields_ = [("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64),
                    ("flags", wintypes.DWORD), ("min_working_set", size), ("max_working_set", size),
                    ("active_process_limit", wintypes.DWORD), ("affinity", size), ("priority", wintypes.DWORD),
                    ("scheduling_class", wintypes.DWORD)]
    class Extended(ctypes.Structure):
        _fields_ = [("basic", Limits), ("io", ctypes.c_uint64 * 6), ("memory", size * 4)]
    kernel = ctypes.windll.kernel32
    kernel.CreateJobObjectW.restype = wintypes.HANDLE
    handle = wintypes.HANDLE(kernel.CreateJobObjectW(None, None))
    settings = Extended()
    settings.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    if (not handle or not kernel.SetInformationJobObject(handle, 9, ctypes.byref(settings), ctypes.sizeof(settings))
            or not kernel.AssignProcessToJobObject(handle, wintypes.HANDLE(-1))):
        if handle:
            kernel.CloseHandle(handle)
        raise RuntimeFault("ngrok_process_ownership_failed")


@contextmanager
def endpoint(root, config, gateway_url, token):
    if config["mode"] == "trusted":
        yield config["phoneUrl"], None
        return
    requested = config.get("phoneUrl", "")
    existing = tunnels(4040)
    previous = selected_url(existing, gateway_url, requested)
    if previous:
        yield previous, None
        return
    if existing and not requested:
        raise RuntimeFault("ngrok_target_mismatch")
    program = config.get("ngrokPath")
    if not program:
        for directory in os.get_exec_path():
            candidate = Path(directory.strip('"')) / "ngrok.exe"
            if candidate.is_absolute() and candidate.is_file():
                program = str(candidate)
                break
    if not program or not Path(program).is_absolute() or not Path(program).is_file():
        raise RuntimeFault("ngrok_missing")
    standard = default_config()
    if standard is None and not token and not os.environ.get("NGROK_AUTHTOKEN"):
        raise RuntimeFault("ngrok_auth_required")
    with socket.socket() as port_picker:
        port_picker.bind(("127.0.0.1", 0))
        api_port = port_picker.getsockname()[1]
    version = "3"
    if standard:
        with standard.open(encoding="utf-8-sig") as src:
            for line in src:
                match = re.fullmatch(r"""version:\s*["']?([23])["']?\s*(?:#.*)?""", line.rstrip())
                if match:
                    version = match[1]
                    break
    overlay = root / "ngrok-gateway.yml"
    prefix = "agent:\n  " if version == "3" else ""
    overlay.write_text(f'version: "{version}"\n{prefix}web_addr: 127.0.0.1:{api_port}\n', encoding="utf-8")
    arguments = [program, "http", gateway_url, "--inspect=false", "--log=stdout", "--log-format=json"]
    if standard:
        arguments.extend(["--config", str(standard)])
    arguments.extend(["--config", str(overlay)])
    if requested:
        arguments.extend(["--url", requested])
    environment = dict(os.environ)
    environment.pop("ASTRBOT_CHAT_KEY", None)
    if token:
        environment["NGROK_AUTHTOKEN"] = _authtoken(token)
    own_child_lifetime()
    process = subprocess.Popen(arguments, env=environment, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    auth_error = threading.Event()

    def diagnostics():
        with process.stdout as stream:
            for line in stream:
                if re.search(rb"ERR_NGROK_(105|106|4018)\b", line):
                    auth_error.set()

    reader = threading.Thread(target=diagnostics, daemon=True)
    reader.start()
    try:
        until = time.monotonic() + 18
        while time.monotonic() < until:
            if process.poll() is not None:
                reader.join(timeout=1)
                raise RuntimeFault("ngrok_auth_required" if auth_error.is_set() else "ngrok_start_failed")
            public = selected_url(tunnels(api_port), gateway_url, requested)
            if public:
                if launcher._listeners().get(api_port) != process.pid:
                    raise RuntimeFault("ngrok_api_owner_mismatch")
                yield public, process
                return
            time.sleep(.15)
        raise RuntimeFault("ngrok_start_timeout")
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        reader.join(timeout=1)
