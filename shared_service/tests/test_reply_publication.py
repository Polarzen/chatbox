"""Check the snapshots visible to concurrent readers while an AstrBot stream runs."""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

from shared_service import service


class ReplyPublicationTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "chat.sqlite"
        upstream = service.initialize(self.path, "https://example.test")
        self.bridge = service.Service(self.path, "key", None)
        self.turn = str(uuid.uuid4())
        with service.database(self.path, write=True) as db:
            self.room = self.bridge.routes.ensure_room(db, upstream, "alice", "sid", legacy=True)
            self.bridge.enqueue(db, self.room, self.turn, "question")
            db.execute("CREATE TABLE writes(answer TEXT, status TEXT)")
            db.execute("""CREATE TRIGGER record_write AFTER UPDATE ON turns BEGIN
                       INSERT INTO writes VALUES(NEW.answer,NEW.status); END""")

    def generate(self, send):
        with patch.object(service.astrbot, "send", side_effect=send):
            self.bridge.generate(self.room, self.turn, "alice", "sid", "question", legacy=True)
        with service.database(self.path) as db:
            return [tuple(row) for row in db.execute("SELECT * FROM writes ORDER BY rowid")]

    def test_first_content_and_spaced_progress_are_visible_before_final_commit(self):
        clock = [1.0]
        during = []

        def stream(base, key, username, sid, question, publish, verify):
            publish("")
            publish("a")
            with service.database(self.path) as db:
                during.append(tuple(db.execute("SELECT answer,status FROM turns").fetchone()))
            for i in range(2, 201):
                publish("a" * i)
            clock[0] += service.REPLY_WRITE_INTERVAL + 0.001
            publish("a" * 200)
            publish("final")

        with patch.object(service.time, "monotonic", side_effect=lambda: clock[0]):
            writes = self.generate(stream)
        self.assertEqual(during, [("a", "running")])
        self.assertEqual(writes, [("a", "running"), ("a" * 200, "running"), ("final", "complete")])

    def test_abrupt_disconnect_retains_last_buffered_snapshot(self):
        def stream(base, key, username, sid, question, publish, verify):
            publish("A")
            publish("A 后续")
            raise OSError("connection lost")

        with patch.object(service.time, "monotonic", return_value=1.0):
            self.assertEqual(self.generate(stream), [("A", "running"), ("A 后续", "unknown")])

    def test_size_limit_applies_to_buffered_multibyte_answers(self):
        def stream(base, key, username, sid, question, publish, verify):
            publish("好")
            publish("好的")
            publish("好的呀")

        with patch.object(service, "MAX_REPLY", 6), patch.object(service.time, "monotonic", return_value=1.0):
            self.assertEqual(self.generate(stream), [("好", "running"), ("好的", "unknown")])
        with service.database(self.path) as db:
            self.assertEqual(db.execute("SELECT needs_review FROM turns").fetchone()[0], 1)
