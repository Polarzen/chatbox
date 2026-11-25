from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from shared_service import device_service, service, wearable_api, wearable_jobs, wearable_store


def instant(iso):
    return int(datetime.fromisoformat(iso).timestamp() * 1000)


class WearableJobTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "service.sqlite"
        upstream = service.initialize(self.path, "http://127.0.0.1:8000")
        with service.database(self.path, write=True) as db:
            device_service.migrate_device(db)
            wearable_store.migrate_wearable(db)
            db.execute("INSERT INTO device_members VALUES('pc',?,1,'',0)", (service.digest("pc-key"),))
            db.execute("INSERT INTO device_members VALUES('phone',?,0,'alice',0)", (service.digest("phone-key"),))
            db.execute("""INSERT INTO profiles(id,source_id,base,username,name)
                VALUES('profile','device:profile','https://example.test','alice','Phone')""")
            db.execute("INSERT INTO device_catalog VALUES('profile','phone','android:base','base','alice',?)", (upstream,))
            db.execute("INSERT INTO rooms VALUES('room','alice','chat',?,0)", (upstream,))
            db.execute("""INSERT INTO profile_sessions(profile,sid,title,activity,total,ready,room)
                VALUES('profile','chat','Personal',1,0,1,'room')""")
            db.execute("INSERT INTO device_session_state(profile,sid,origin) VALUES('profile','chat','phone')")
            db.execute("INSERT INTO turns VALUES('room',1,'first','hello','hi','complete',0)")
        self.now = instant("2026-03-05T13:00Z")
        self.api = SimpleNamespace(path=self.path, route=lambda db, row: row["upstream"],
                                   routes=SimpleNamespace(check_origin=lambda upstream, username, sid: (True, "")))
        api_clock = patch.object(wearable_api, "time", SimpleNamespace(time_ns=lambda: self.now * 1_000_000))
        api_clock.start()
        self.addCleanup(api_clock.stop)
        clock = patch.object(wearable_store, "_now_ms", side_effect=lambda: self.now)
        clock.start()
        self.addCleanup(clock.stop)
        self.settings = dict(revision=1, profile="profile", sid="chat", metrics=["exercise", "sleep"],
                             source="fixture.health", timezone="America/New_York", morningTime="08:00",
                             waitMinutes=90, workoutEnabled=True, morningEnabled=False,
                             notifyWorkout=True, notifyMorning=True)
        self.phone("config", self.settings)
        self.token = self.phone("credential", {"revision": 1})["token"]
        self.cursor = 0
        self.sync()

    def phone(self, action, body):
        with service.database(self.path, write=True) as db:
            return wearable_store.phone_action(db, "wearable-" + action, "phone-key", body)

    def settings_update(self, **changes):
        self.settings.update(changes, revision=self.settings["revision"]+1)
        self.phone("config", self.settings)
        self.cursor = 0
        self.sync()

    def sync(self, records=(), deletes=(), statuses=None):
        self.cursor += 1
        self.phone("sync", dict(revision=self.settings["revision"], cursor=self.cursor, initial=self.cursor == 1,
                                records=list(records), deletes=list(deletes), statuses=statuses or {}))

    def job(self, action, job_id=None, token=None):
        code, result = wearable_api.handle(self.api, "wearable-" + action, token or self.token,
                                           {} if job_id is None else {"id": job_id})
        self.assertEqual(code, 200)
        return result

    def workout(self, **extra):
        return dict(dict(metric="exercise", id="run", source="fixture.health", version=1,
                         startMs=self.now-1_800_000, endMs=self.now-1_000, exerciseType="running"), **extra)

    def pending(self):
        jobs = self.job("jobs")["jobs"]
        self.assertEqual(len(jobs), 1)
        return jobs[0]["id"]

    def appended(self):
        with service.database(self.path) as db:
            return [tuple(row) for row in db.execute("SELECT question,answer FROM turns WHERE seq>1")]

    def test_plugin_queries_before_atomic_idempotent_delivery(self):
        self.sync([self.workout()])
        self.sync([self.workout()])
        job = self.pending()
        with self.assertRaises(service.ApiError):
            self.job("job-finish", job)
        with self.assertRaises(service.ApiError):
            self.job("job-query", job, token="phone-key")
        self.assertEqual(self.job("job-query", job)["data"]["id"], "run")
        self.sync([self.workout(version=2, distanceMeters=4200)])
        self.assertEqual(self.job("job-finish", job)["status"], "pending")
        self.job("job-query", job)
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda _: self.job("job-finish", job), range(2)))
        self.assertEqual(results, [{"status": "delivered"}]*2)
        [(question, answer)] = self.appended()
        self.assertEqual(question, "")
        self.assertIn("4200 米", answer)
        self.assertEqual(self.job("jobs")["jobs"], [])

    def test_silent_when_notifications_switched_off_after_query(self):
        self.sync([self.workout()])
        job = self.pending()
        self.job("job-query", job)
        self.settings_update(notifyWorkout=False)
        self.assertEqual(self.job("job-finish", job)["status"], "silent")
        self.assertEqual(self.appended(), [])

    def test_destination_removed_after_query(self):
        self.sync([self.workout()])
        job = self.pending()
        self.job("job-query", job)
        with service.database(self.path, write=True) as db:
            db.execute("UPDATE device_session_state SET removed=1")
        self.assertEqual(self.job("job-finish", job)["status"], "destination_unavailable")
        self.assertEqual(self.appended(), [])

    def test_upstream_unavailable_or_deleted_after_query(self):
        self.sync([self.workout()])
        job = self.pending()
        self.job("job-query", job)
        self.api.routes.check_origin = lambda *args: (False, "unreachable")
        self.assertEqual(self.job("job-finish", job)["status"], "waiting")
        self.api.routes.check_origin = lambda *args: (False, "")
        self.assertEqual(self.job("job-finish", job)["status"], "destination_unavailable")
        self.assertEqual(self.appended(), [])

    def test_reject_shared_or_unverified_destinations(self):
        changes = ["UPDATE rooms SET legacy=1", "UPDATE device_catalog SET origin='pc'",
                   "UPDATE device_session_state SET unverified=1", "UPDATE rooms SET username='other'",
                   "UPDATE profile_sessions SET removed=1",
                   "INSERT INTO devices VALUES('guest',X'01','room',0)",
                   "INSERT INTO profile_devices VALUES('guest',X'02','profile','guest',0)"]
        with service.database(self.path, write=True) as db:
            self.assertEqual(wearable_jobs.private_room(db, "phone", "profile", "chat"), "room")
            for statement in changes:
                with self.subTest(change=statement):
                    db.execute("SAVEPOINT original")
                    db.execute(statement)
                    self.assertIsNone(wearable_jobs.private_room(db, "phone", "profile", "chat"))
                    db.execute("ROLLBACK TO original")
                    db.execute("RELEASE original")

    def test_exercise_deleted_before_delivery(self):
        self.sync([self.workout()])
        job = self.pending()
        self.job("job-query", job)
        self.sync(deletes=[dict(metric="exercise", source="fixture.health", id="run", version=2)])
        self.assertEqual(self.job("job-finish", job)["status"], "missing")
        self.assertEqual(self.appended(), [])

    def test_source_recovers_while_workout_is_pending(self):
        self.sync([self.workout()])
        job = self.pending()
        self.sync(statuses={"exercise": "source_unavailable"})
        self.assertEqual(self.job("job-query", job)["dataStatus"], "source_unavailable")
        self.assertEqual(self.job("job-finish", job)["status"], "waiting")
        self.assertEqual(self.appended(), [])
        self.sync(statuses={"exercise": "ok"})
        self.assertEqual(self.pending(), job)
        self.assertEqual(self.job("job-query", job)["data"]["id"], "run")
        self.assertEqual(self.job("job-finish", job)["status"], "delivered")
        self.assertEqual(len(self.appended()), 1)

    def test_revocation_blocks_retries_and_future_morning_jobs(self):
        self.settings_update(morningEnabled=True)
        job = self.pending()
        self.job("job-query", job)
        with service.database(self.path, write=True) as db:
            db.execute("UPDATE device_members SET revoked=1 WHERE id='phone'")
        self.now += 24*3_600_000
        for action, identifier in [("jobs", None), ("job-query", job), ("job-finish", job)]:
            with self.subTest(action=action), self.assertRaises(service.ApiError) as refused:
                self.job(action, identifier)
            self.assertEqual(refused.exception.status, 401)
        with service.database(self.path) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM wearable_jobs").fetchone()[0], 0)
        self.assertEqual(self.appended(), [])

    def test_late_sleep_then_deadline(self):
        self.settings_update(morningEnabled=True)
        job = self.pending()
        self.assertEqual(self.job("job-query", job)["dataStatus"], "no_records")
        self.assertEqual(self.job("job-finish", job)["status"], "waiting")
        self.now += 30*60_000
        self.sync([dict(metric="sleep", source="fixture.health", id="night", version=1,
                        startMs=self.now-8*3_600_000, endMs=self.now-45*60_000)])
        self.assertEqual(self.job("job-query", job)["data"]["id"], "night")
        self.assertEqual(self.job("job-finish", job)["status"], "delivered")
        self.assertIn("无法确定实际睡眠", self.appended()[0][1])
        self.now += 24*3_600_000
        tomorrow = self.pending()
        self.assertNotEqual(tomorrow, job)
        self.job("job-query", tomorrow)
        self.now += 60*60_000
        self.assertEqual(self.job("job-finish", tomorrow)["status"], "expired")
        self.assertEqual(self.job("jobs")["jobs"], [])
        self.assertEqual(len(self.appended()), 1)

    def test_timezone_change_moves_pending_morning_and_cache_clear_preserves_delivered_turn(self):
        self.settings_update(morningEnabled=True)
        east = self.pending()
        self.settings_update(timezone="America/Los_Angeles")
        self.assertEqual(self.job("jobs")["jobs"], [])
        self.assertEqual(self.job("job-query", east)["status"], "waiting")
        self.now = instant("2026-03-05T16:00Z")
        west = self.pending()
        sleep = dict(metric="sleep", source="fixture.health", id="night", version=1,
                     startMs=self.now-8*3_600_000, endMs=self.now-30*60_000)
        self.sync([sleep])
        self.job("job-query", west)
        self.assertEqual(self.job("job-finish", west)["status"], "delivered")
        self.phone("clear", {"revision": self.settings["revision"]})
        self.settings_update()
        self.sync([sleep])
        replay = self.pending()
        self.job("job-query", replay)
        self.assertEqual(self.job("job-finish", replay)["status"], "delivered")
        self.assertEqual(len(self.appended()), 1)

    def test_spring_gap_and_autumn_fold(self):
        self.settings_update(morningEnabled=True, morningTime="02:30")
        self.now = instant("2026-03-08T07:29Z")
        self.assertEqual(self.job("jobs")["jobs"], [])
        self.now += 60_000
        first = self.pending()
        self.settings_update(morningTime="01:30")
        self.now = instant("2026-11-01T05:30Z")
        second = self.pending()
        self.assertNotEqual(second, first)
        self.now = instant("2026-11-01T06:30Z")
        self.assertEqual(self.pending(), second)
