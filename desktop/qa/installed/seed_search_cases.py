"""Clone a fresh Prepare-Fixture run for installed Windows history-search checks."""

import argparse
import json
from pathlib import Path
import shutil
import uuid


def turn(question, answer):
    return {"question": question, "answer": answer, "status": "已完成", "replyImages": []}


def session(number, entries, **names):
    key = f"chatbox-installed-search-010/session/{number}"
    return {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, key)), "entries": entries,
            "lastUsedAt": 10000 - number, **names}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path, help="fresh Prepare-Fixture run root")
    parser.add_argument("destination", type=Path, help="new private fixture directory")
    args = parser.parse_args()
    src, dst = args.fixture, args.destination
    history_path = Path("localappdata/Chatbox/history.json")
    data = json.loads((src / history_path).read_text(encoding="utf-8-sig"))
    profiles = data["profiles"]
    addresses = [f"https://127.0.0.1:{port}" for port in (18443, 18444)]
    if (not data["username"].startswith("ui_fixture_")
            or [p["address"] for p in profiles] != addresses
            or data["activeProfileId"] != profiles[0]["id"]
            or any(len(p["sessions"]) != 1 or p["sessions"][0]["id"] != p["currentSession"]
                   for p in profiles)):
        parser.error("Expected the original synthetic A/B profiles and current sessions.")
    a, b = profiles
    active = a["sessions"][0]
    active["manualName"] = "Active notebook"
    active["lastUsedAt"] = 10001
    active["entries"].append(turn("Reading position", "Current-session checkpoint ACTIVE-EMBER."))
    twin_name = "Twin notebook"
    west = session(1, [turn("Identify TWIN-WEST in the question.", "West shelf, blue volume.")],
                   manualName=twin_name)
    east = session(2, [turn("Where is the east volume?", "Find TWIN-EAST in the answer.")],
                   manualName=twin_name)
    paragraphs = [f"Passage {i:03d}. " + "The sample notebook describes the long reading exercise. " * 9
                  for i in range(1, 91)]
    paragraphs[47] += "The amber bookmark reads WRAP-LANTERN beside the inner margin. "
    paragraphs[47] += "The next sentence extends the same paragraph. " * 8
    wrapped = session(3, [turn("Inspect the wrapped text.", "\n\n".join(paragraphs))],
                      title="Legacy ledger")
    derived = session(4, [turn("Default notebook", "Question-derived display name.")])
    empty = session(5, [])
    a["sessions"].extend([west, east, wrapped, derived, empty])
    for number in range(1, 41):
        entries = [turn(f"Record {number:02d}-{i:03d}?", f"Cobalt sample {number:02d}-{i:03d}.")
                   for i in range(1, 76)]
        a["sessions"].append(session(5 + number, entries, manualName=f"Archive {number:02d}"))
    other = b["sessions"][0]
    other["entries"].append(turn("Other notebook", "The B-only shelf reads B-ONLY-ORCHID."))

    counts = {name: {"sessions": len(p["sessions"]),
                     "turns": sum(len(s["entries"]) for s in p["sessions"])}
              for name, p in zip(("A", "B"), profiles)}
    expectations = {
        "counts": counts, "active_session": active["id"],
        "A_hits": {
            twin_name: [{"session": s["id"], "side": "TITLE"} for s in (west, east)],
            "TWIN-WEST": [{"session": west["id"], "side": "USER", "entry": 0}],
            "TWIN-EAST": [{"session": east["id"], "side": "ASSISTANT", "entry": 0}],
            "WRAP-LANTERN": [{"session": wrapped["id"], "side": "ASSISTANT", "entry": 0,
                              "paragraph": 48}],
            "Legacy ledger": [{"session": wrapped["id"], "side": "TITLE"}],
            "Default notebook": [{"session": derived["id"], "side": side, "entry": idx}
                                 for side, idx in (("TITLE", -1), ("USER", 0))],
            "新会话": [{"session": empty["id"], "side": "TITLE"}],
            "ACTIVE-EMBER": [{"session": active["id"], "side": "ASSISTANT",
                              "entry": len(active["entries"]) - 1}],
            "VOID-NO-MATCH": [], "B-ONLY-ORCHID": [],
        },
        "B_hits": {"B-ONLY-ORCHID": [{"session": other["id"], "side": "ASSISTANT",
                                      "entry": len(other["entries"]) - 1}], "TWIN-WEST": []},
        "timing_workload": [
            {"query": "Cobalt", "expected_A_hits": 3000},
            {"query": "Cobalt sample 40-075", "expected_A_hits": 1},
            {"query": "VOID-NO-MATCH", "expected_A_hits": 0},
        ],
        "stream_prompt": "fixture:long-stream:combo010 fixture:two-text-batches",
        "stream_gates": ["long-stream-combo010." + stage for stage in ("text", "text2", "image", "end")],
    }
    dst.mkdir(parents=True, exist_ok=False)
    shutil.copytree(src / "localappdata", dst / "localappdata")
    for file in ("tls/fixture-app-trust.p12", "private/service.ready"):
        (dst / file).parent.mkdir()
        shutil.copyfile(src / file, dst / file)
    (dst / history_path).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    (dst / "search-expectations.json").write_text(
        json.dumps(expectations, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
