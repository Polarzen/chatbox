"""Exercise a real major upgrade only on a disposable hosted Windows runner."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import subprocess


# Both writes and reads use DesktopStore from the MSI installed at this stage.
STORE_PROBE = r"""
import dev.polarzen.chatbox.*;
import org.json.JSONObject;
import java.nio.file.*;
import java.util.*;
class StoreProbe {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(System.getenv("LOCALAPPDATA"), "Chatbox", "history.json");
        DesktopStore store = new DesktopStore(file);
        String id = "manual:installer-qa";
        Entry entry = new Entry("Installer QA question", "Installer QA answer", "\u5df2\u5b8c\u6210",
                                null, null, List.of(), List.of());
        ComposerDraft draft = new ComposerDraft("Unsent installer QA draft", List.of());
        if (args[0].equals("seed")) {
            Profile profile = store.ensureProfile(id, "http://127.0.0.1:1");
            store.selectProfile(id);
            SessionKey key = new SessionKey(id, profile.getCurrentSession());
            store.saveProfile(id, new Chat(key.getSessionId(), List.of(entry)));
            store.saveDraft(key, store.nextDraftRevision(), draft);
        }
        DesktopStore restored = new DesktopStore(file);
        Profile profile = Objects.requireNonNull(restored.profile(id));
        SessionKey key = new SessionKey(id, profile.getCurrentSession());
        List<Entry> entries = restored.sessionContent(id, key.getSessionId()).getEntries();
        ComposerDraft savedDraft = restored.readDraft(key);
        if (!restored.getActiveProfileId().equals(id) || restored.getProfiles().size() != 1 ||
            restored.sessions(id).size() != 1 || !restored.sessions(id).get(0).getId().equals(key.getSessionId()) ||
            !entries.equals(List.of(entry)) || !savedDraft.equals(draft))
            throw new AssertionError("Stored profile, session, history or draft differs");
        System.out.print(new JSONObject().put("profile", id).put("session", key.getSessionId())
            .put("question", entries.get(0).getQuestion()).put("answer", entries.get(0).getAnswer())
            .put("draft", savedDraft.getText()));
    }
}
"""


def registrations():
    upgrade_code = "{204B3F78-64EC-4071-9A66-A535773540EB}"
    products = {}
    while True:
        code = ctypes.create_unicode_buffer(39)
        rc = ctypes.windll.msi.MsiEnumRelatedProductsW(upgrade_code, 0, len(products), code)
        if rc == 259:  # ERROR_NO_MORE_ITEMS
            return products
        if rc:
            raise ctypes.WinError(rc)
        version = ctypes.create_unicode_buffer(64)
        size = ctypes.c_ulong(len(version))
        rc = ctypes.windll.msi.MsiGetProductInfoW(code.value, "VersionString", version, ctypes.byref(size))
        if rc:
            raise ctypes.WinError(rc)
        products[code.value] = version.value


def fingerprint(path):
    return {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    if os.name != "nt" or os.getenv("GITHUB_ACTIONS") != "true" or os.getenv("RUNNER_ENVIRONMENT") != "github-hosted":
        raise RuntimeError("Requires a disposable GitHub-hosted Windows runner")
    root = Path(os.environ["RUNNER_TEMP"]) / "chatbox-msi-transaction"
    root.mkdir()
    evidence = root / "evidence"
    evidence.mkdir()
    probe = root / "StoreProbe.java"
    probe.write_text(STORE_PROBE, encoding="ascii")
    workspace = Path(os.environ["GITHUB_WORKSPACE"])
    install = root / "install"
    history = Path(os.environ["LOCALAPPDATA"]) / "Chatbox/history.json"
    if registrations() or history.exists():
        raise RuntimeError("Runner already has Chatbox registration or history")
    report = {}
    try:
        for checkout, version in (("v18", "0.1.18"), ("v19", "0.1.19")):
            source = workspace / checkout
            msi = source / f"desktop/build/compose/binaries/main/msi/Chatbox-{version}.msi"
            row = report[version] = {"commit": subprocess.check_output(["git", "-C", source, "rev-parse", "HEAD"], text=True).strip(), "msi": fingerprint(msi)}
            log = root / f"install-{version}.log"
            rc = subprocess.run(["msiexec.exe", "/i", msi, "/qn", "/norestart", f"INSTALLDIR={install}", "/L*v", log], timeout=300).returncode
            row["msiexec_exit"] = rc
            if rc:
                if log.exists():
                    data = log.read_bytes()
                    encoding = "utf-16" if data.startswith(b"\xff\xfe") else "utf-8"
                    lines = data.decode(encoding, errors="replace").splitlines()
                    for line in lines:
                        if "Return value 3" in line or "Error 1" in line:
                            print(line)
                raise RuntimeError(f"msiexec exit {rc}; see {log}")
            products = row["registered_products"] = registrations()
            if list(products.values()) != [version]:
                raise RuntimeError(f"Unexpected registered products: {products}")
            exe = install / "Chatbox.exe"
            row["installed_exe"] = fingerprint(exe)
            row["installed_version"] = subprocess.check_output(
                ["pwsh", "-NoProfile", "-Command",
                 "$v = (Get-Item -LiteralPath ./Chatbox.exe).VersionInfo; "
                 "'{0}.{1}.{2}' -f $v.FileMajorPart, $v.FileMinorPart, $v.FileBuildPart"],
                cwd=install, text=True, timeout=30,
            ).strip()
            if row["installed_version"] != version:
                raise RuntimeError(f"Installed launcher version differs from {version}")
            previous = report["0.1.18"]
            if checkout == "v19":
                if products.keys() & previous["registered_products"].keys():
                    raise RuntimeError("Old product registration survived upgrade")
                row["history_after_msiexec"] = fingerprint(history)
                if row["history_after_msiexec"] != previous["history"]:
                    raise RuntimeError("Installer changed user history or draft")
            marker = root / f"smoke-{version}.txt"
            row["smoke_exit"] = subprocess.run([exe, "--smoke-test", marker], timeout=60).returncode
            if row["smoke_exit"] or marker.read_text(encoding="utf-8") != "chatbox-smoke-ok":
                raise RuntimeError(f"Installed {version} smoke test failed")
            mode = "seed" if checkout == "v18" else "verify"
            row["store"] = json.loads(subprocess.check_output(
                ["java", "-cp", str(install / "app/*"), probe, mode], text=True, timeout=60,
            ))
            row["history"] = fingerprint(history)
            if checkout == "v19" and row["store"] != previous["store"]:
                raise RuntimeError("Restored profile, session, history or draft changed during upgrade")
    finally:
        summary = json.dumps(report, indent=2)
        (evidence / "transaction.json").write_text(summary, encoding="utf-8")
        print(summary)


if __name__ == "__main__":
    main()
