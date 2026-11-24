"""Resolve a running Windows AstrBot Launcher instance by its persistent directory UUID."""

import http.client
import json
import os
from pathlib import Path
import re
import subprocess
from typing import NamedTuple


_UUID = re.compile(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", re.I | re.ASCII)
_URL = re.compile(r"http://127\.0\.0\.1(?::0*([0-9]{1,5}))?/?", re.I | re.ASCII)
_TITLE = re.compile(rb"<title>\s*AstrBot Dashboard\s*</title>", re.I)
_UNVERIFIED = "无法唯一验证正在运行的 AstrBot Launcher 实例，请检查实例和 Dashboard 端口"


class _Process(NamedTuple):
    parent: int
    executable: str | None
    born: int | None


def _system_output(executable, *args):
    if os.name != "nt" or not os.environ.get("SystemRoot"):
        raise ValueError("AstrBot Launcher 实例验证需要 Windows")
    program = Path(os.environ["SystemRoot"]) / "System32" / executable
    try:
        return subprocess.run(
            [str(program), *args], capture_output=True, check=True, timeout=10,
            creationflags=subprocess.CREATE_NO_WINDOW,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        raise ValueError("无法读取 Windows 进程或监听端口以验证 Launcher 实例") from None


def _listeners():
    """Track exact and wildcard IPv4 owners so conflicting bindings fail closed."""
    owners = {}
    output = _system_output("netstat.exe", "-ano", "-p", "tcp")
    for line in output.splitlines():
        fields = line.split()
        if len(fields) != 5 or fields[0] != b"TCP" or fields[3] != b"LISTENING":
            continue
        address, _, port = fields[1].rpartition(b":")
        if address in (b"127.0.0.1", b"0.0.0.0") and port.isdigit() and fields[4].isdigit():
            owners.setdefault(int(port), set()).add((address, int(fields[4])))
    listeners = {}
    for port, entries in owners.items():
        if len(entries) == 1:
            address, pid = entries.pop()
            if address == b"127.0.0.1":
                listeners[port] = pid
    return listeners


def _processes():
    # Query just the ancestry metadata. Command lines may contain credentials.
    script = """
        $ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        $rows = Get-CimInstance -Query 'SELECT ProcessId,ParentProcessId,ExecutablePath,CreationDate FROM Win32_Process' |
            Select-Object ProcessId,ParentProcessId,ExecutablePath,@{Name='Born';Expression={$_.CreationDate.Ticks}}
        ConvertTo-Json -InputObject @($rows) -Compress
    """
    output = _system_output("WindowsPowerShell/v1.0/powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
    try:
        return {p["ProcessId"]: _Process(p["ParentProcessId"], p["ExecutablePath"], p["Born"])
                for p in json.loads(output.decode("utf-8-sig"))}
    except (ValueError, TypeError, KeyError):
        raise ValueError("无法解析 Windows 进程信息以验证 Launcher 实例") from None


def _identity(pid, processes, root):
    seen = set()
    identities = set()
    launcher = False
    while pid and pid in processes:
        if pid in seen:
            return None
        seen.add(pid)
        process = processes[pid]
        if process.executable:
            path = Path(os.path.normpath(process.executable))
            if path.name.lower() == "astrbot-launcher.exe":
                launcher = True
            elif path.is_relative_to(root):
                parts = path.relative_to(root).parts
                if (len(parts) == 4 and _UUID.fullmatch(parts[0])
                        and tuple(p.lower() for p in parts[1:3]) == ("venv", "scripts")
                        and parts[3].lower() in ("python.exe", "pythonw.exe")
                        and (root / parts[0]).is_dir()):
                    identities.add(parts[0].lower())
        parent = processes.get(process.parent)
        # Windows retains a parent PID after exit and may reuse it for a new process.
        if not parent or not parent.born or not process.born or parent.born > process.born:
            break
        pid = process.parent
    return identities.pop() if launcher and len(identities) == 1 else None


def _dashboard(port):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
    try:
        connection.request("GET", "/", headers={"Cache-Control": "no-cache"})
        with connection.getresponse() as response:
            return (200 <= response.status < 300 and response.headers.get_content_type() == "text/html"
                    and bool(_TITLE.search(response.read(8192))))
    except (OSError, http.client.HTTPException):
        return False
    finally:
        connection.close()


def _running():
    root = Path.home() / ".astrbot_launcher" / "instances"
    owners = _listeners()
    processes = _processes()
    ports = {}
    for port, pid in owners.items():
        identity = _identity(pid, processes, root)
        if identity and _dashboard(port):
            ports.setdefault(identity, []).append(port)
    # Skip a listener that disappeared or changed owners during the HTTP check.
    current = _listeners() if ports else {}
    return {identity: matches[0] for identity, matches in ports.items()
            if len(matches) == 1 and current.get(matches[0]) == owners[matches[0]]}


def identity_for_url(url: str) -> str:
    match = _URL.fullmatch(url.strip()) if isinstance(url, str) else None
    port = int(match[1] or 80) if match else 0
    if not 1 <= port <= 65535:
        raise ValueError("Launcher 地址必须是有效的 http://127.0.0.1 回环地址")
    for identity, verified_port in _running().items():
        if verified_port == port:
            return identity
    raise ValueError(_UNVERIFIED)


def url_for_identity(identity: str) -> str:
    if not isinstance(identity, str) or not _UUID.fullmatch(identity):
        raise ValueError("无效的 Launcher 实例 UUID")
    port = _running().get(identity.lower())
    if port is None:
        raise ValueError(_UNVERIFIED)
    return f"http://127.0.0.1:{port}"
