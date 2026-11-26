"""Run from a v4.29.4 source tree with the Chat Box inbox patch applied."""
import json
import runpy
import sqlite3
import tempfile
import unittest
from pathlib import Path

MODULE = Path("astrbot/core/db/webchat_inbox.py")
inbox = runpy.run_path(str(MODULE))
config = runpy.run_path(str(Path(__file__).resolve().parents[1] / "configure_chatbox_inbox.py"))

OWNER = "chatbox_" + "d" * 32
OTHER = "chatbox_" + "b" * 32
KEY = "63db1422-7fae-4c94-b7e2-cdd86d45142a"
FOREIGN = "20d27cce-dac0-4058-aa36-b96a1f5a161c"


class InboxTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.data = Path(temp.name)
        self.grants = self.data / "chatbox-inbox-grants.json"
        self.db = sqlite3.connect(self.data / "data_v4.db")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE api_keys(key_id TEXT, name TEXT, revoked_at DATETIME, expires_at DATETIME);
            CREATE TABLE platform_sessions(session_id TEXT UNIQUE, platform_id TEXT, is_group INTEGER, creator TEXT);
            CREATE TABLE platform_message_history(id INTEGER PRIMARY KEY, user_id TEXT, platform_id TEXT,
                                                 sender_id TEXT, content JSON);
        """)
        self.db.execute(inbox["CREATE_INBOX_INDEX"])
        self.db.executemany("INSERT INTO api_keys VALUES (?,? ,NULL,NULL)", [(KEY, "mine"), (FOREIGN, "other")])
        self.db.executemany("INSERT INTO platform_sessions VALUES (?,?,?,?)", [
            ("a", "webchat", 0, OWNER), ("b", "webchat", 0, OTHER), ("o", "qq", 0, OWNER),
            ("c", "webchat", 1, OWNER), ("empty", "webchat", 0, OWNER),
        ])
        self.db.commit()

    def insert(self, room, text, *, marked=True, sender="bot", platform="webchat", extra=()):
        content = {"type": "bot", "message": [{"type": "plain", "text": text}, *extra]}
        if marked:
            content["chatbox_delivery"] = "passive_v1"
        return self.db.execute("INSERT INTO platform_message_history VALUES (NULL, ?, ?, ?, ?)",
                               (room, platform, sender, json.dumps(content, ensure_ascii=False))).lastrowid

    def page(self, room="a", creator=OWNER, after=0, limit=50):
        return inbox["inbox_page"](self.db.execute(inbox["READ_INBOX"], {
            "sid": room, "creator": creator, "after": after, "size": limit + 1,
        }).fetchall(), limit)

    def test_grant_requires_exact_local_pair_and_revocation_is_immediate(self):
        allows = inbox["allows"]
        self.assertFalse(allows(self.grants, KEY, OWNER))
        config["grant"](self.data, KEY, [OWNER])
        self.assertTrue(allows(self.grants, KEY, OWNER))
        for key, username in [(KEY, OTHER), (FOREIGN, OWNER), (None, OWNER), (KEY, "*")]:
            self.assertFalse(allows(self.grants, key, username))
        before = self.grants.read_bytes()
        with self.assertRaises(ValueError):
            config["grant"](self.data, KEY, ["chatbox_" + "e" * 32])
        self.assertEqual(self.grants.read_bytes(), before)
        self.grants.write_text("{", encoding="utf-8")
        self.assertFalse(allows(self.grants, KEY, OWNER))
        with self.assertRaises(ValueError):
            config["grant"](self.data, KEY, [OWNER])
        self.assertEqual(self.grants.read_text(), "{")
        self.grants.write_text(json.dumps({KEY: OWNER}), encoding="utf-8")
        self.assertFalse(allows(self.grants, KEY, OWNER))

    def test_only_private_owned_marked_passive_rows_visible(self):
        self.insert("a", "old or active replies", marked=False)
        self.insert("a", "another sender", sender="guest")
        self.insert("a", "another platform", platform="qq")
        self.insert("b", "another caller's message")
        for room in ("b", "o", "c", "unknown"):
            self.assertIsNone(self.page(room))
        self.assertEqual(self.page("empty"), {"messages": [], "more": False})
        # Multiple components use native order; media are represented without paths or credentials.
        record = self.insert("a", "你好", extra=[{"type": "image", "path": "secret"},
                                                   {"type": "record", "attachment_id": "secret"},
                                                   {"type": "actionrow", "callback_data": "secret"}])
        self.assertEqual(self.page(), {"messages": [{"id": record, "text": "你好〔图片〕〔语音〕〔交互按钮〕"}],
                                       "more": False})

    def test_stable_cursor_replay_skip_gaps_and_paginate(self):
        ids = [self.insert("a", "重复"), self.insert("a", "ordinary", marked=False),
               self.insert("a", "重复"), self.insert("a", "结尾")]
        first = self.page(limit=2)
        self.assertEqual([m["id"] for m in first["messages"]], [ids[0], ids[2]])
        self.assertTrue(first["more"])
        self.assertEqual(self.page(limit=2), first)
        self.assertEqual(self.page(after=ids[2], limit=2), {"messages": [{"id": ids[3], "text": "结尾"}],
                                                           "more": False})
        self.assertEqual(self.page(after=ids[3])["messages"], [])

    def test_bounded_page_leaves_undelivered_row_for_next_read(self):
        ids = [self.insert("a", "\x01汉" * 140000) for _ in range(5)]
        page = self.page()
        self.assertTrue(page["more"])
        self.assertLess(inbox["_json_size"](page), inbox["MAX_PAGE"])
        last = 0
        seen = []
        while True:
            page = self.page(after=last)
            for item in page["messages"]:
                seen.append(item["id"])
                self.assertTrue(item["text"].endswith(inbox["TRUNCATED"]))
            if not page["more"]:
                break
            self.assertTrue(page["messages"])
            last = page["messages"][-1]["id"]
        self.assertEqual(seen, ids)

    def test_seeks_filtered_index(self):
        plan = self.db.execute("EXPLAIN QUERY PLAN " + inbox["READ_INBOX"],
                               {"sid": "a", "creator": OWNER, "after": 100000, "size": 51}).fetchall()
        self.assertTrue(any("ix_chatbox_passive_room_id" in row[-1] and "SEARCH" in row[-1] for row in plan))


if __name__ == "__main__":
    unittest.main()
