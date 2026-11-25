"""Synthetic records only. The integration/device layers own consent and live Bluetooth."""

import unittest
from datetime import datetime

from shared_service import wearable_metrics as w


def ms(iso):
    return int(datetime.fromisoformat(iso).timestamp() * 1000)


SOURCE = "fixture.band.app"
NOW = ms("2026-11-25T10:00:00+08:00")
HOUR = 3_600_000


def record(metric, start, end, *, id="a", source=SOURCE, version=1, **fields):
    return dict(metric=metric, id=id, source=source, version=version, startMs=start, endMs=end, **fields)


def query(metric, records, *, now=NOW, zone="Asia/Hong_Kong", options=None, **fields):
    return w.query_metrics(records, dict(metric=metric, **(options or {})),
                           source=SOURCE, timezone=zone, now_ms=now, **fields)


class WearableMetricsTests(unittest.TestCase):
    def test_reports_only_hardware_on_contributing_steps(self):
        band = dict(type="fitness_band", manufacturer="Example", model="Fixture band")
        watch = dict(type="watch", model="Fixture watch")
        first = record("steps", NOW-8, NOW-6, count=12, device=band)
        copy = dict(first, id="copy", device=watch)
        unknown = record("steps", NOW-5, NOW-2, count=3, id="unknown")
        unrelated = dict(first, id="outside", startMs=NOW-2*w.DAY_MS, endMs=NOW-w.DAY_MS, device=dict(type="watch", model="Other"))
        result = query("steps", [first, copy, unknown, unrelated])
        self.assertEqual(result["data"]["count"], 15)
        self.assertEqual(result["reportedDevices"], [band, {"type": "unknown"}, watch])
        with self.assertRaises(ValueError):
            w.normalize_record(dict(first, device=dict(type="phone")))
        with self.assertRaises(ValueError):
            w.normalize_record(dict(first, device=dict(type="watch", model="x"*129)))

    def test_point_sample_preserves_known_and_unknown_hardware(self):
        band = dict(type="fitness_band", model="Fixture band")
        workout = record("exercise", NOW-9, NOW-1, exerciseType="running", device=band)
        point = record("heart_rate", NOW-5, NOW-5, samples=[dict(timeMs=NOW-5, bpm=82)])
        watch = dict(point, id="other", device=dict(type="watch"), samples=[dict(timeMs=NOW-5, bpm=140)])
        result = query("heart_rate", [workout, point, watch])
        self.assertEqual(result["data"]["meanBpm"], 82)
        self.assertEqual(result["reportedDevices"], [dict(type="unknown")])
        self.assertEqual(query("exercise", [workout, watch])["reportedDevices"], [band])
        with self.assertRaises(ValueError):
            w.normalize_record(dict(workout, endMs=workout["startMs"]))

    def test_steps_origin_revision_and_zero_vs_empty(self):
        a, b = NOW-HOUR, NOW-1
        original = record("steps", a, b, count=11)
        revised = dict(original, count=0, version=2)
        other = dict(original, source="fixture.phone", count=33)
        for records in ([revised, other, original], [original, revised, revised]):
            result = query("steps", records)
            self.assertEqual((result["status"], result["sampleCount"], result["data"]["count"]), ("ok", 1, 0))
            self.assertEqual(result["measuredAtMs"], b)
            self.assertNotIn("details", result)
        self.assertEqual(query("steps", [other])["status"], "no_records")
        self.assertIsNone(query("steps", [other])["data"])
        moved = dict(original, version=3, startMs=a-w.DAY_MS, endMs=b-w.DAY_MS)
        self.assertIsNone(query("steps", [moved, original])["data"])

    def test_overlapping_and_day_boundary_steps_do_not_invent_counts(self):
        midnight = NOW - 10*HOUR
        a = record("steps", midnight-1, midnight+5, count=3)
        b = record("steps", midnight+5, midnight+10, count=7, id="b")
        overlap = record("steps", midnight+9, midnight+14, count=9, id="c")
        d = record("steps", NOW-9, NOW-1, count=4, id="d")
        result = query("steps", [a, b, overlap, d, dict(d, id="duplicate")], options={"granularity":"detail"})
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["data"], dict(count=None, knownSubtotal=4, omittedIntervals=3))
        self.assertEqual(len(result["details"]), 1)

    def test_local_day_uses_zone_on_measurement_date_across_dst(self):
        now = ms("2026-11-01T12:00:00-05:00")
        midnight = ms("2026-11-01T00:00:00-04:00")
        row = record("steps", midnight, midnight+HOUR, count=4)
        result = query("steps", [row], now=now, zone="America/New_York")
        self.assertEqual(result["startMs"], midnight)
        self.assertEqual(now-result["startMs"], 13*HOUR)
        self.assertEqual(result["data"]["count"], 4)
        self.assertIsNone(query("steps", [row], now=now, zone="America/Los_Angeles")["data"])

    def test_principal_session_without_nap_or_overlapping_duplicate(self):
        a, b = NOW-11*HOUR, NOW-3*HOUR
        def stage(i, j, label):
            return dict(startMs=a+i*HOUR, endMs=a+j*HOUR, stage=label)
        main = record("sleep", a, b, stages=[stage(0, 1, "awake_in_bed"), stage(1, 7, "sleeping"),
                                            stage(2, 3, "deep"), stage(7, 8, "awake")],
                      startOffsetSeconds=28_800, endOffsetSeconds=28_800)
        nap = record("sleep", b+HOUR, NOW-1, id="nap", isNap=True)
        shorter = record("sleep", a+HOUR, b, id="duplicate")
        result = query("sleep", [shorter, nap, main], options=dict(granularity="detail", limit=2))
        data = result["data"]
        self.assertEqual((result["status"], data["sessionSeconds"], data["asleepSeconds"]), ("ok", 8*3600, 6*3600))
        self.assertEqual(data["stageSeconds"], dict(awake_in_bed=3600, sleeping=5*3600, deep=3600, awake=3600))
        self.assertEqual((data["firstAsleepMs"], data["lastAsleepEndMs"]), (a+HOUR, a+7*HOUR))
        self.assertEqual(data["startOffsetSeconds"], 28_800)
        self.assertEqual(len(result["details"]), 2)
        self.assertTrue(result["detailsTruncated"])
        self.assertIsNone(query("sleep", [nap])["data"])

    def test_unknown_conflicting_or_missing_stages(self):
        a, b = NOW-11*HOUR, NOW-3*HOUR
        row = record("sleep", a, b)
        empty = query("sleep", [row])
        self.assertEqual(empty["status"], "partial")
        for field in ("firstAsleepMs", "lastAsleepEndMs", "asleepSeconds", "recordedAsleepSeconds"):
            self.assertIsNone(empty["data"][field])
        self.assertEqual(empty["data"]["stageSeconds"], {})
        row["stages"] = [dict(startMs=a, endMs=a+4*HOUR, stage="light"),
                         dict(startMs=a+2*HOUR, endMs=a+3*HOUR, stage="awake"),
                         dict(startMs=a+4*HOUR, endMs=b, stage="unknown")]
        result = query("sleep", [row])
        self.assertEqual((result["status"], result["data"]["conflictingSeconds"],
                          result["data"]["recordedAsleepSeconds"]), ("partial", 3600, 3*3600))
        self.assertIsNone(result["data"]["asleepSeconds"])

    def test_workout_and_heart_statistics_use_selected_session_and_sample_origin(self):
        a, b = NOW-2*HOUR, NOW-HOUR
        ex = record("exercise", a, b, exerciseType="running", distanceMeters=4200)
        later = dict(ex, id="later", startMs=b, endMs=NOW-1)
        ongoing = dict(ex, id="unfinished", startMs=NOW-1, endMs=NOW+HOUR)
        samples = [dict(timeMs=at, bpm=bpm) for at, bpm in
                   [(a-1, 210), (a, 80), (a+2, 85), (b-1, 110), (b, 200)]]
        heart = record("heart_rate", a-1, b, samples=samples)
        rows = [ex, later, ongoing, heart, dict(heart, id="copy"),
                dict(heart, id="phone", source="phone"), dict(heart, metric="live_heart_rate")]
        self.assertEqual(query("exercise", rows)["data"]["id"], "later")
        info = query("exercise", rows, options=dict(exerciseId="a"))["data"]
        self.assertEqual((info["durationSeconds"], info["distanceMeters"]), (3600, 4200))
        result = query("heart_rate", rows, options=dict(exerciseId="a", granularity="detail", limit=2))
        self.assertEqual((result["sampleCount"], result["data"]["minBpm"], result["data"]["maxBpm"],
                          result["data"]["meanBpm"]), (3, 80, 110, 91.67))
        self.assertEqual((result["coverageStartMs"], result["coverageEndMs"]), (a, b-1))
        self.assertEqual(len(result["details"]), 2)
        self.assertTrue(result["detailsTruncated"])
        self.assertIsNone(query("heart_rate", [ex])["data"])
        self.assertEqual(query("exercise", [ex])["data"]["durationSeconds"], 3600)
        conflict = record("heart_rate", a, b, id="another", samples=[dict(timeMs=a, bpm=70)])
        result = query("heart_rate", [ex, heart, conflict])
        self.assertEqual((result["status"], result["sampleCount"], result["data"]["conflictingTimes"],
                          result["data"]["meanBpm"]), ("partial", 2, 1, 97.5))

    def test_freshness_and_denied_prerequisite(self):
        row = record("steps", NOW-2, NOW-1, count=1)
        offline = query("steps", [row], offline=True, last_sync_ms=NOW-1)
        self.assertEqual((offline["status"], offline["dataStatus"], offline["data"]["count"]), ("phone_offline", "ok", 1))
        stale = query("steps", [row], offline=True, last_sync_ms=NOW-w.DAY_MS)
        self.assertTrue(stale["offline"])
        self.assertEqual((stale["status"], stale["measuredAtMs"]), ("stale", NOW-1))
        for metric in ("exercise", "heart_rate"):
            denied = query("heart_rate", [], statuses={metric: "no_permission"})
            self.assertEqual(denied["status"], "no_permission")
            self.assertIsNone(denied["data"])
        self.assertEqual(query("steps", [row], statuses={"steps":"source_unavailable"})["status"], "source_unavailable")

    def test_invalid_wire_values_and_bounded_queries(self):
        row = record("steps", NOW-2, NOW-1, count=0)
        for extra in [dict(count=True), dict(count=-1), dict(version=-1), dict(startMs=NOW), dict(id=" "),
                      dict(metric="live_heart_rate")]:
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                w.normalize_record(dict(row, **extra))
        for extra in [dict(limit=w.MAX_DETAIL+1), dict(limit=True), dict(relative="latest"),
                      dict(startMs=NOW-HOUR), dict(startMs=NOW-w.MAX_RANGE_MS-1, endMs=NOW),
                      dict(relative="today", startMs=NOW-2, endMs=NOW), dict(username="other")]:
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                query("steps", [row], options=extra)
        for zone in ("", "not/a/registered/zone"):
            with self.assertRaises(ValueError):
                query("steps", [row], zone=zone)
