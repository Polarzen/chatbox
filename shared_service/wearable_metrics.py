"""Historical Health Connect metrics. All wire timestamps are Unix milliseconds.

normalize_record(record) -> validated JSON dict. Required fields: id, metric,
source (Health Connect dataOrigin package), version (nonnegative integer),
startMs, endMs. Metrics and extra fields:
  steps: count
  sleep: stages [{startMs,endMs,stage}] (optional; lowercase HC stage names)
  exercise: exerciseType (string), distanceMeters (optional)
  heart_rate: samples [{timeMs,bpm}]
Optional startOffsetSeconds and endOffsetSeconds preserve the recording offsets.
Optional device holds the source-reported type (fitness_band, watch, unknown),
manufacturer and model. Query reportedDevices describes only contributing data.
A missing device is reported as unknown. The source package alone identifies no model.

normalize_query(query, *, timezone, now_ms) -> resolved JSON dict.
query_metrics(records, query, *, source, timezone, now_ms, last_sync_ms=None,
              statuses=None, offline=False) -> result for ONE metric.
query: {metric, startMs?, endMs?, relative?, granularity?, limit?, exerciseId?}.
Relative defaults: steps=today, sleep=last_night, exercise/heart_rate=latest.
heart_rate selects historical samples in the selected completed workout. Both
exercise and heart_rate must be granted for a workout heart rate query.
statuses: trusted mapping per metric, e.g. {"sleep":"no_permission"}; the
caller authorizes the request and supplies the explicitly selected origin.
The result has status, dataStatus, data, sampleCount, unit, source, timezone,
startMs/endMs (query), coverageStartMs/coverageEndMs (observed), measuredAtMs,
lastSyncMs, offline and stale. Cache freshness changes at 24 hours. An offline
or stale result retains its dataStatus (ok, partial or no_records).
Invalid records or queries raise ValueError.
"""

from collections import Counter, defaultdict
from datetime import date, datetime, time, timedelta, timezone as dt_timezone
from math import isfinite
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


METRICS = ("steps", "sleep", "exercise", "heart_rate")
DAY_MS = 86_400_000
MAX_RANGE_MS = 30 * DAY_MS
MAX_DETAIL = 100
STALE_AFTER_MS = DAY_MS
# Limit accepted instants to the range also representable by datetime with any offset.
MAX_TIME_MS = 253_402_127_999_999
STAGES = {"unknown", "awake", "sleeping", "out_of_bed", "light", "deep", "rem", "awake_in_bed"}
ASLEEP = {"light", "deep", "rem", "sleeping"}


def _text(value, name, max_length=256):
    if not isinstance(value, str) or not value.strip() or len(value) > max_length or value != value.strip():
        raise ValueError(f"invalid {name}")
    return value


def _integer(value, name, low=0, high=2**63 - 1):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f"invalid {name}")
    return value


def _instant(value, key):
    return _integer(value, key, 0, MAX_TIME_MS)


def _interval(obj, allow_point=False):
    start, end = (_instant(obj.get(key), key) for key in ("startMs", "endMs"))
    if end < start or (end == start and not allow_point):
        raise ValueError("empty or reversed interval")
    return start, end


def _items(value, name, limit):
    if not isinstance(value, list) or len(value) > limit or any(type(item) is not dict for item in value):
        raise ValueError(f"invalid {name}")
    return value


def _metric(value):
    if value not in METRICS:
        raise ValueError("unknown metric")
    return value


def normalize_record(record):
    if not isinstance(record, dict):
        raise ValueError("record must be an object")
    metric = _metric(record.get("metric"))
    start, end = _interval(record, allow_point=metric == "heart_rate")
    row = dict(id=_text(record.get("id"), "id"), metric=metric, source=_text(record.get("source"), "source"),
               version=_integer(record.get("version"), "version"), startMs=start, endMs=end)
    if "device" in record:
        device = record["device"]
        if not isinstance(device, dict) or device.get("type") not in ("fitness_band", "watch", "unknown"):
            raise ValueError("invalid wearable device")
        row["device"] = dict(type=device["type"])
        for key in ("manufacturer", "model"):
            if key in device:
                row["device"][key] = _text(device[key], key, 128)
    for key in ("startOffsetSeconds", "endOffsetSeconds"):
        if key in record:
            row[key] = _integer(record[key], key, -64_800, 64_800)
    if metric == "steps":
        row["count"] = _integer(record.get("count"), "count", high=1_000_000)
    elif metric == "exercise":
        row["exerciseType"] = _text(record.get("exerciseType"), "exerciseType")
        if "distanceMeters" in record:
            distance = record["distanceMeters"]
            if type(distance) not in (int, float) or not 0 <= distance <= 1e10 or not isfinite(distance):
                raise ValueError("invalid distanceMeters")
            row["distanceMeters"] = distance
    elif metric == "sleep":
        stages = []
        for stage in _items(record.get("stages", []), "stages", 10000):
            a, b = _interval(stage)
            label = _text(stage.get("stage"), "stage")
            if a < start or b > end or label not in STAGES:
                raise ValueError("invalid sleep stage")
            stages.append(dict(startMs=a, endMs=b, stage=label))
        row["stages"] = sorted(stages, key=lambda s: (s["startMs"], s["endMs"], s["stage"]))
        if "isNap" in record:
            if type(record["isNap"]) is not bool:
                raise ValueError("invalid isNap")
            row["isNap"] = record["isNap"]
    else:
        samples = []
        for sample in _items(record.get("samples"), "samples", 10000):
            at = _instant(sample.get("timeMs"), "timeMs")
            if not start <= at <= end:
                raise ValueError("sample outside record")
            samples.append(dict(timeMs=at, bpm=_integer(sample.get("bpm"), "bpm", 1, 300)))
        row["samples"] = sorted(samples, key=lambda sample: (sample["timeMs"], sample["bpm"]))
    return row


def _zone(name):
    if name == "UTC":
        return dt_timezone.utc
    _text(name, "timezone", 128)
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise ValueError("unknown timezone") from exc


def _local_ms(day: date, hour, zone):
    return int(datetime.combine(day, time(hour), tzinfo=zone).timestamp() * 1000)


def normalize_query(query, *, timezone, now_ms):
    if not isinstance(query, dict) or set(query) - {
        "metric", "startMs", "endMs", "relative", "granularity", "limit", "exerciseId"
    }:
        raise ValueError("unsupported query fields")
    metric = _metric(query.get("metric"))
    _instant(now_ms, "now_ms")
    zone = _zone(timezone)
    today = datetime.fromtimestamp(now_ms / 1000, zone).date()
    granularity = query.get("granularity", "summary")
    if granularity not in ("summary", "detail"):
        raise ValueError("unknown granularity")
    result = dict(metric=metric, granularity=granularity, limit=_integer(query.get("limit", 32), "limit", 1, MAX_DETAIL))
    default_relative = {"steps": "today", "sleep": "last_night", "exercise": "latest", "heart_rate": "latest"}
    if "startMs" in query or "endMs" in query:
        if "relative" in query:
            raise ValueError("choose a range or relative time")
        start, end = _interval(query)
        if end - start > MAX_RANGE_MS:
            raise ValueError("query exceeds 30 days")
        if start > now_ms:
            raise ValueError("query begins in the future")
        end = min(now_ms, end)
        relative = None
    else:
        relative = query.get("relative", default_relative[metric])
        if relative != default_relative[metric]:
            raise ValueError("unsupported relative time for metric")
        end = now_ms
        if relative == "today":
            start = _local_ms(today, 0, zone)
        elif relative == "last_night":
            start = _local_ms(today - timedelta(days=1), 18, zone)
            end = min(now_ms, _local_ms(today, 12, zone))
        else:
            start = max(0, end - MAX_RANGE_MS)
    if "exerciseId" in query:
        if metric not in ("exercise", "heart_rate"):
            raise ValueError("exerciseId only applies to exercise queries")
        result["exerciseId"] = _text(query["exerciseId"], "exerciseId")
    return dict(result, relative=relative, startMs=start, endMs=end)


def _details(result, query, entries):
    if query["granularity"] == "detail":
        result["details"] = entries[:query["limit"]]
        result["detailsTruncated"] = len(entries) > query["limit"]


def _coverage(result, start, end):
    result.update(coverageStartMs=start, coverageEndMs=end, measuredAtMs=end)


def _reported_devices(result, rows):
    # All entries describe measurements contributing to this answer. Even a known
    # vendor package may contain records with no hardware information.
    keys = ("type", "manufacturer", "model")
    devices = {tuple(row.get("device", {"type": "unknown"}).get(k, "") for k in keys) for row in rows}
    result["reportedDevices"] = [{k: v for k, v in zip(keys, values) if v} for values in sorted(devices)[:8]]
    if len(devices) > 8:
        result["reportedDevicesTruncated"] = True


def _steps(rows, q, result):
    intervals = set()
    for row in rows:
        if row["endMs"] > q["startMs"] and row["startMs"] < q["endMs"]:
            intervals.add((row["startMs"], row["endMs"], row["count"]))
    if not intervals:
        return
    # A total for part of a source interval cannot be recovered from its count.
    # Exact copies have already coalesced. Withhold ambiguous overlapping groups.
    groups = []
    group_end = -1
    for interval in sorted(intervals):
        if interval[0] >= group_end:
            groups.append([])
        groups[-1].append(interval)
        group_end = max(group_end, interval[1])
    accepted = [g[0] for g in groups if len(g) == 1 and q["startMs"] <= g[0][0] and g[0][1] <= q["endMs"]]
    omitted = len(intervals) - len(accepted)
    subtotal = sum(count for _, _, count in accepted)
    result.update(dataStatus="partial" if omitted else "ok", sampleCount=len(accepted),
                  data=dict(count=None if omitted else subtotal, knownSubtotal=subtotal if accepted else None,
                            omittedIntervals=omitted))
    if accepted:
        _coverage(result, accepted[0][0], accepted[-1][1])
        included = set(accepted)
        _reported_devices(result, (r for r in rows if (r["startMs"], r["endMs"], r["count"]) in included))
    _details(result, q, [dict(startMs=a, endMs=b, count=c) for a, b, c in accepted])


def _sleep(rows, q, result, zone):
    candidates = [r for r in rows if q["startMs"] < r["endMs"] <= q["endMs"] and not r.get("isNap")]
    if q["relative"] == "last_night":
        evening = datetime.fromtimestamp(q["startMs"] / 1000, zone).date()
        core_start = _local_ms(evening, 21, zone)
        core_end = _local_ms(evening + timedelta(days=1), 8, zone)
        # Prefer the principal overnight session. The source may supply multiple
        # overlapping sessions; summing them would double count stages.
        def rank(r):
            overlap = min(r["endMs"], core_end) - max(r["startMs"], core_start)
            return overlap, r["endMs"] - r["startMs"], r["endMs"], r["id"]
        candidates = [r for r in candidates if rank(r)[0] > 0 and r["startMs"] >= q["startMs"] - DAY_MS // 2]
    else:
        candidates = [r for r in candidates if r["startMs"] >= q["startMs"]]
        def rank(r):
            return r["endMs"], r["endMs"] - r["startMs"], r["id"]
    if not candidates:
        return
    row = max(candidates, key=rank)
    _reported_devices(result, [row])
    a, b = row["startMs"], row["endMs"]
    changes = defaultdict(Counter)
    for stage in row["stages"]:
        changes[stage["startMs"]][stage["stage"]] += 1
        changes[stage["endMs"]][stage["stage"]] -= 1
    active = Counter()
    stages_ms = Counter()
    conflict_ms = 0
    previous = a
    first_asleep = last_asleep = None
    for at in sorted(changes):
        labels = set(active)
        # A broad asleep label and a specific sleep phase agree.
        if "sleeping" in labels and labels & {"light", "deep", "rem"}:
            labels.remove("sleeping")
        if len(labels) == 1:
            label = next(iter(labels))
            stages_ms[label] += at - previous
            if label in ASLEEP:
                if first_asleep is None:
                    first_asleep = previous
                last_asleep = at
        elif len(labels) > 1:
            conflict_ms += at - previous
        active.update(changes[at])
        active = +active
        previous = at
    classified = sum(v for k, v in stages_ms.items() if k != "unknown")
    incomplete = classified < b - a
    recorded = sum(stages_ms[k] for k in ASLEEP)
    result.update(dataStatus="partial" if incomplete else "ok", sampleCount=len(row["stages"]),
                  data=dict(id=row["id"], startMs=a, endMs=b, sessionSeconds=(b-a)/1000,
                            selection="longest_overnight_overlap_21_to_08" if q["relative"] else "latest_completed",
                            firstAsleepMs=first_asleep, lastAsleepEndMs=last_asleep,
                            asleepSeconds=None if incomplete else recorded/1000,
                            recordedAsleepSeconds=recorded/1000 if classified else None,
                            stageSeconds={k: v/1000 for k, v in stages_ms.items()},
                            unclassifiedSeconds=(b-a-classified)/1000, conflictingSeconds=conflict_ms/1000))
    _coverage(result, a, b)
    _record_offsets(row, result["data"])
    _details(result, q, row["stages"])


def _record_offsets(row, data):
    for key in ("startOffsetSeconds", "endOffsetSeconds"):
        if key in row:
            data[key] = row[key]


def _workout(rows, q):
    return max((r for r in rows if r["startMs"] >= q["startMs"] and r["endMs"] <= q["endMs"]
                and ("exerciseId" not in q or r["id"] == q["exerciseId"])),
               default=None, key=lambda r: (r["endMs"], r["startMs"], r["id"]))


def _heart_rate(rows, exercise, q, result):
    samples = defaultdict(set)
    def compatible(row):
        session, heart = exercise.get("device", {}), row.get("device", {})
        return all(not session.get(k) or not heart.get(k) or
                   (k == "type" and "unknown" in (session[k], heart[k])) or
                   session[k].casefold() == heart[k].casefold() for k in ("type", "manufacturer", "model"))
    rows = [r for r in rows if compatible(r)]
    for row in rows:
        for sample in row["samples"]:
            at = sample["timeMs"]
            if exercise["startMs"] <= at < exercise["endMs"]:
                samples[at].add(sample["bpm"])
    if not samples:
        return
    accepted = [dict(timeMs=at, bpm=next(iter(values))) for at, values in sorted(samples.items()) if len(values) == 1]
    conflicts = len(samples) - len(accepted)
    values = [s["bpm"] for s in accepted]
    result.update(dataStatus="partial" if conflicts else "ok", sampleCount=len(values),
                  data=dict(exerciseId=exercise["id"], minBpm=min(values, default=None),
                            maxBpm=max(values, default=None), meanBpm=round(sum(values) / len(values), 2) if values else None,
                            method="arithmetic_mean_of_distinct_sample_times", conflictingTimes=conflicts))
    if accepted:
        _coverage(result, accepted[0]["timeMs"], accepted[-1]["timeMs"])
        times = {sample["timeMs"] for sample in accepted}
        _reported_devices(result, (r for r in rows if any(s["timeMs"] in times for s in r["samples"])))
    _details(result, q, accepted)


def query_metrics(records, query, *, source, timezone, now_ms, last_sync_ms=None, statuses=None, offline=False):
    q = normalize_query(query, timezone=timezone, now_ms=now_ms)
    metric = q["metric"]
    _text(source, "source")
    if last_sync_ms is not None:
        _instant(last_sync_ms, "last_sync_ms")
    stale = last_sync_ms is not None and now_ms - last_sync_ms >= STALE_AFTER_MS
    result = dict(metric=metric, source=source, timezone=timezone, unit={
        "steps": "steps", "sleep": "seconds", "exercise": "seconds", "heart_rate": "bpm"
    }[metric], startMs=q["startMs"], endMs=q["endMs"], coverageStartMs=None, coverageEndMs=None,
                  sampleCount=0, measuredAtMs=None, lastSyncMs=last_sync_ms, offline=offline, stale=stale,
                  dataStatus="no_records", data=None, reportedDevices=[])
    required = (metric, "exercise") if metric == "heart_rate" else (metric,)
    for kind in required:
        state = (statuses or {}).get(kind)
        if state in ("no_permission", "source_unavailable"):
            return dict(result, status=state, dataStatus=state)
    # Only the explicitly selected Health Connect origin participates. Take the
    # newest source revision before applying the window (a revision can move it).
    latest = {}
    for record in records:
        if record.get("source") != source or record.get("metric") not in required:
            continue
        row = normalize_record(record)
        key = (row["metric"], row["id"])
        if key not in latest or row["version"] > latest[key]["version"]:
            latest[key] = row
        elif row["version"] == latest[key]["version"] and row != latest[key]:
            raise ValueError("inconsistent record revision")
    rows = [r for r in latest.values() if r["metric"] == metric]
    if metric == "steps":
        _steps(rows, q, result)
    elif metric == "sleep":
        _sleep(rows, q, result, _zone(timezone))
    else:
        exercise = _workout([r for r in latest.values() if r["metric"] == "exercise"], q)
        if exercise and metric == "exercise":
            _reported_devices(result, [exercise])
            data = {k: exercise[k] for k in ("id", "exerciseType", "startMs", "endMs", "distanceMeters") if k in exercise}
            _record_offsets(exercise, data)
            data["durationSeconds"] = (exercise["endMs"] - exercise["startMs"]) / 1000
            _coverage(result, exercise["startMs"], exercise["endMs"])
            result.update(dataStatus="ok", sampleCount=1, data=data)
        elif exercise:
            result["exerciseId"] = exercise["id"]
            _heart_rate(rows, exercise, q, result)
    result["status"] = "stale" if stale else "phone_offline" if offline else result["dataStatus"]
    return result
