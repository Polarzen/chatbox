"""Phone consent, historical record storage and scoped plugin credentials.

Phone API: phone_action(db, action, token, body), actions with wearable- prefix.
wearable-config {revision, profile, sid, metrics:[str], source, timezone,
 morningTime:'HH:MM', waitMinutes:int, workoutEnabled:bool, morningEnabled:bool,
 notifyWorkout:bool, notifyMorning:bool}. revision is a phone-persisted monotonic
 configuration version (starts at 1). Same version and content is a replay.
 An empty metrics list or profile revokes sharing. Returns {revision,cursor}.
wearable-sync {revision, cursor:int, initial:bool, records:[normalized record],
 deletes:[{metric,source,id,version}], statuses:{metric:status}}. Cursor starts
 at 1 per config revision, advances by one per committed batch. Repeated bytes
 at the current cursor replay safely. Returns {revision,cursor,lastSyncMs}.
wearable-credential {revision} explicitly rotates; returns {token}.
wearable-clear {revision} clears cached data and invalidates outstanding sync;
 callers supply a NEW config revision before resuming upload.

Plugin API: plugin_grant(db, token) returns a dictionary with every config
field plus device, username, revision, lastSyncMs, statuses. read_metric(db,
grant, query, now_ms) returns a validated, permission-filtered metrics result.
The route verifies chat event identity separately. run_job_action accepts
grant['device'], grant and lambda q, ms: read_metric(db, grant, q, ms).

The caller owns the SQLite transaction. Mutations and credential checks belong
to the same immediate transaction as any resulting job or assistant turn.
"""

import json
import hashlib
import re
import secrets
import time

if __package__:
    from . import device_service, service as core, wearable_jobs, wearable_metrics
else:
    import device_service
    import service as core
    import wearable_jobs
    import wearable_metrics


def _phone(db, token):
    device = device_service.authorize(db, token)
    if device['owner']:
        raise core.ApiError(403, '仅已配对的手机可管理健康共享。')
    return device


def _profile(db, phone, profile):
    row = device_service.profile_row(db, phone, profile, publish=True)
    if row['username'] != phone['username']:
        raise core.ApiError(403, '仅可向手机主人自己的档案共享。')
    return row


def _json(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True, allow_nan=False)


def _now_ms():
    return time.time_ns() // 1_000_000


def _identity(value, name):
    if not isinstance(value, str) or not value.strip() or value != value.strip() or len(value) > 256:
        raise core.ApiError(400, f'无效的 {name}。')
    return value


def _normalize_delete(item):
    core.fields(item, ('metric', 'source', 'id', 'version'))
    if item['metric'] not in wearable_metrics.METRICS or type(item['version']) is not int or not 0 <= item['version'] < 2**63:
        raise core.ApiError(400, '删除记录的指标或版本无效。')
    return dict(metric=item['metric'], source=_identity(item['source'], 'source'),
                id=_identity(item['id'], 'id'), version=item['version'])


def _normalize_record(item):
    try:
        return wearable_metrics.normalize_record(item)
    except ValueError as exc:
        raise core.ApiError(400, str(exc)) from exc


RETENTION_MS = 30 * 86_400_000
SYNC_LEASE_MS = 45 * 60_000
CONFIG_FIELDS = {'profile', 'sid', 'metrics', 'timezone', 'source', 'morningTime', 'waitMinutes',
                 'workoutEnabled', 'morningEnabled', 'notifyWorkout', 'notifyMorning'}


def migrate_wearable(db):
    db.execute('''CREATE TABLE IF NOT EXISTS wearable_grants(
        device TEXT PRIMARY KEY REFERENCES device_members, revision INTEGER NOT NULL,
        settings TEXT NOT NULL, plugin_digest BLOB UNIQUE, cursor INTEGER NOT NULL DEFAULT 0,
        batch_digest BLOB, last_sync_ms INTEGER, statuses TEXT NOT NULL DEFAULT '{}',
        metric_sync TEXT NOT NULL DEFAULT '{}',
        cleared INTEGER NOT NULL DEFAULT 0)''')
    db.execute('''CREATE TABLE IF NOT EXISTS wearable_records(
        device TEXT NOT NULL REFERENCES wearable_grants ON DELETE CASCADE,
        metric TEXT NOT NULL, source TEXT NOT NULL, id TEXT NOT NULL, version INTEGER NOT NULL,
        start_ms INTEGER, end_ms INTEGER, expires_ms INTEGER NOT NULL, payload TEXT,
        PRIMARY KEY(device,metric,source,id))''')
    db.execute('CREATE INDEX IF NOT EXISTS wearable_expiry ON wearable_records(expires_ms)')
    wearable_jobs.migrate_jobs(db)
    prune(db, _now_ms())
    db.execute('''CREATE TRIGGER IF NOT EXISTS wearable_revoke AFTER UPDATE OF revoked ON device_members
        WHEN NEW.revoked=1 BEGIN
          DELETE FROM wearable_records WHERE device=NEW.id;
          DELETE FROM wearable_jobs WHERE device=NEW.id;
          DELETE FROM wearable_grants WHERE device=NEW.id;
        END''')


def _positive(body, key):
    value = body.get(key)
    if type(value) is not int or not 0 < value < 2**63:
        raise core.ApiError(400, f'无效的 {key}。')
    return value


def _settings(db, device, body):
    core.fields(body, CONFIG_FIELDS | {'revision'})
    metrics = body['metrics']
    if not isinstance(metrics, list) or any(m not in wearable_metrics.METRICS for m in metrics) or len(metrics) != len(set(metrics)):
        raise core.ApiError(400, '健康共享指标无效。')
    result = {k: body[k] for k in CONFIG_FIELDS}
    for name in ('source', 'sid', 'profile'):
        core.string(body, name, empty=True, max_bytes=4096)
    if metrics and body['profile']:
        _profile(db, device, body['profile'])
        _identity(body['source'], 'source')
    else:
        result.update(profile='', sid='', source='')
        metrics = []
    result['metrics'] = sorted(metrics)
    for name in ('workoutEnabled', 'morningEnabled', 'notifyWorkout', 'notifyMorning'):
        if type(body[name]) is not bool:
            raise core.ApiError(400, '提醒设置无效。')
    if not isinstance(body['morningTime'], str) or not re.fullmatch(r'([01]\d|2[0-3]):[0-5]\d', body['morningTime']):
        raise core.ApiError(400, '晨间时间无效。')
    if type(body['waitMinutes']) is not int or not 1 <= body['waitMinutes'] <= 1440:
        raise core.ApiError(400, '晨间等待时间无效。')
    try:
        wearable_metrics._zone(body['timezone'])
    except ValueError as exc:
        raise core.ApiError(400, '无效时区。') from exc
    return result


def _configure(db, phone, row, body, revision):
    settings = _settings(db, phone, body)
    previous = json.loads(row['settings']) if row else {}
    if row and revision <= row['revision']:
        if revision == row['revision'] and settings == previous:
            return dict(revision=revision, cursor=row['cursor'])
        raise core.ApiError(409, '设置版本冲突。')
    device = phone['id']
    binding_changed = any(previous.get(k) != settings[k] for k in ('source', 'profile'))
    if binding_changed or not settings['metrics']:
        db.execute('DELETE FROM wearable_records WHERE device=?', (device,))
        db.execute('DELETE FROM wearable_jobs WHERE device=?', (device,))
    else:
        removed = set(previous.get('metrics', [])) - set(settings['metrics'])
        db.executemany('DELETE FROM wearable_records WHERE device=? AND metric=?', [(device, kind) for kind in removed])
        for kind, metric, enabled in [('workout', 'exercise', 'workoutEnabled'), ('morning', 'sleep', 'morningEnabled')]:
            if metric in removed or not settings[enabled] or previous.get('sid') != settings['sid']:
                db.execute('DELETE FROM wearable_jobs WHERE device=? AND kind=? AND state NOT IN (\'delivered\',\'silent\')',
                           (device, kind))
    digest = row['plugin_digest'] if row and not binding_changed and settings['metrics'] else None
    def retained(field):
        previous_values = json.loads(row[field]) if row and not binding_changed else {}
        return _json({m: v for m, v in previous_values.items() if m in settings['metrics']})
    db.execute('''INSERT INTO wearable_grants(device,revision,settings,plugin_digest,last_sync_ms,statuses,metric_sync)
        VALUES(?,?,?,?,?,?,?) ON CONFLICT(device) DO UPDATE SET revision=excluded.revision,settings=excluded.settings,
        plugin_digest=excluded.plugin_digest,cursor=0,batch_digest=NULL,last_sync_ms=excluded.last_sync_ms,
        statuses=excluded.statuses,metric_sync=excluded.metric_sync,cleared=0''',
               (device, revision, _json(settings), digest, row['last_sync_ms'] if row and not binding_changed else None,
                retained('statuses'), retained('metric_sync')))
    return dict(revision=revision, cursor=0)


def prune(db, now_ms):
    db.execute('DELETE FROM wearable_records WHERE expires_ms<=?', (now_ms,))
    db.execute('DELETE FROM wearable_jobs WHERE due_ms<=?', (now_ms - RETENTION_MS,))


def _apply_record(db, device, record, deleted, now_ms):
    key = (device, record['metric'], record['source'], record['id'])
    old = db.execute('SELECT version,payload FROM wearable_records WHERE (device,metric,source,id)=(?,?,?,?)', key).fetchone()
    expires = record.get('endMs', now_ms) + RETENTION_MS
    payload = None if deleted or expires <= now_ms else _json(record)
    if old and old['version'] >= record['version']:
        if old['version'] == record['version'] and old['payload'] != payload:
            raise core.ApiError(409, '同版本的健康记录内容不一致。')
        return False
    # Keep a versioned tombstone for an expired revision that moved a formerly
    # recent interval into the past. An older retry must not restore its data.
    db.execute('''INSERT INTO wearable_records VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(device,metric,source,id)
        DO UPDATE SET version=excluded.version,start_ms=excluded.start_ms,end_ms=excluded.end_ms,
        expires_ms=excluded.expires_ms,payload=excluded.payload''',
               (*key, record['version'], record.get('startMs') if payload else None,
                record.get('endMs') if payload else None, expires if payload else now_ms + RETENTION_MS, payload))
    return old is None and payload is not None


def _sync(db, row, settings, body, now_ms):
    core.fields(body, ('revision', 'cursor', 'initial', 'records', 'deletes', 'statuses'))
    cursor = _positive(body, 'cursor')
    if type(body['initial']) is not bool or any(not isinstance(body[k], list) for k in ('records', 'deletes')):
        raise core.ApiError(400, '同步批次格式无效。')
    if len(body['records']) + len(body['deletes']) > 10000:
        raise core.ApiError(413, '同步批次过大。')
    try:
        digest = hashlib.sha256(_json(body).encode('utf-8')).digest()
    except (ValueError, UnicodeError) as exc:
        raise core.ApiError(400, '无效的同步批次。') from exc
    if cursor == row['cursor'] and digest == row['batch_digest']:
        return dict(revision=row['revision'], cursor=cursor, lastSyncMs=row['last_sync_ms'])
    if cursor != row['cursor'] + 1:
        raise core.ApiError(409, '同步游标冲突，请重新获取当前游标。')
    statuses = body['statuses']
    if (not isinstance(statuses, dict) or set(statuses) - set(settings['metrics']) or
            any(v not in ('ok', 'no_records', 'no_permission', 'source_unavailable') for v in statuses.values())):
        raise core.ApiError(400, '指标同步状态无效。')
    current_statuses = json.loads(row['statuses']) | statuses
    metric_sync = json.loads(row['metric_sync'])
    baseline_exists = row['cursor'] > 0 or 'exercise' in metric_sync
    device = row['device']
    prune(db, now_ms)
    for metric, state in statuses.items():
        if state == 'no_permission':
            metric_sync.pop(metric, None)
            db.execute('DELETE FROM wearable_records WHERE device=? AND metric=?', (device, metric))
            kind = {'exercise': 'workout', 'sleep': 'morning'}.get(metric)
            if kind:
                db.execute("UPDATE wearable_jobs SET state='cancelled',evidence=NULL WHERE device=? AND kind=? AND state='pending'",
                           (device, kind))
        elif state in ('ok', 'no_records'):
            metric_sync[metric] = now_ms
    new_exercises = set()
    for deleted, normalizer, items in [(False, _normalize_record, body['records']), (True, _normalize_delete, body['deletes'])]:
        for item in items:
            record = normalizer(item)
            metric = record['metric']
            if metric not in settings['metrics'] or record['source'] != settings['source']:
                raise core.ApiError(403, '此记录不属于获准共享的指标和来源。')
            if not deleted and current_statuses.get(metric) == 'no_permission':
                raise core.ApiError(403, '手机已撤销此指标的读取权限。')
            if not deleted and record['endMs'] > now_ms:
                raise core.ApiError(400, '记录尚未完成。')
            is_new = _apply_record(db, device, record, deleted, now_ms)
            if current_statuses.get(metric) not in ('source_unavailable', 'no_permission'):
                metric_sync[metric] = now_ms
            if metric == 'exercise' and is_new:
                new_exercises.add(record['id'])
    if baseline_exists and not body['initial'] and settings['workoutEnabled']:
        for eid in new_exercises:
            present = db.execute('''SELECT 1 FROM wearable_records WHERE device=? AND metric='exercise'
                AND source=? AND id=? AND payload IS NOT NULL''', (device, settings['source'], eid)).fetchone()
            if present:
                wearable_jobs.enqueue_workout(db, device, settings['profile'], settings['source'], eid, now_ms)
    db.execute('UPDATE wearable_grants SET cursor=?,batch_digest=?,last_sync_ms=?,statuses=?,metric_sync=? WHERE device=?',
               (cursor, digest, now_ms, _json(current_statuses), _json(metric_sync), device))
    return dict(revision=row['revision'], cursor=cursor, lastSyncMs=now_ms)


def phone_action(db, action, token, body):
    phone = _phone(db, token)
    if not isinstance(body, dict):
        raise core.ApiError(400, '请求格式无效。')
    revision = _positive(body, 'revision')
    row = db.execute('SELECT * FROM wearable_grants WHERE device=?', (phone['id'],)).fetchone()
    if action == 'wearable-config':
        return _configure(db, phone, row, body, revision)
    if not row or revision != row['revision']:
        raise core.ApiError(409, '健康共享设置已变更。')
    if action == 'wearable-clear':
        core.fields(body, ('revision',))
        db.execute('DELETE FROM wearable_records WHERE device=?', (phone['id'],))
        db.execute('DELETE FROM wearable_jobs WHERE device=?', (phone['id'],))
        db.execute('''UPDATE wearable_grants SET cleared=1,cursor=0,last_sync_ms=NULL,
            statuses='{}',metric_sync='{}',batch_digest=NULL WHERE device=?''', (phone['id'],))
        return dict(revision=revision, cursor=0)
    settings = json.loads(row['settings'])
    if not settings['metrics']:
        raise core.ApiError(403, '手机尚未授权健康共享。')
    _profile(db, phone, settings['profile'])
    if action == 'wearable-credential':
        core.fields(body, ('revision',))
        plugin_token = secrets.token_urlsafe(32)
        db.execute('UPDATE wearable_grants SET plugin_digest=? WHERE device=?', (core.digest(plugin_token), phone['id']))
        return {'token': plugin_token}
    if action == 'wearable-sync':
        if row['cleared']:
            raise core.ApiError(409, '缓存已清除，请先更新共享设置版本。')
        return _sync(db, row, settings, body, _now_ms())
    raise core.ApiError(404, '未知健康数据接口。')


def plugin_grant(db, token):
    row = db.execute('''SELECT g.*,m.username FROM wearable_grants g JOIN device_members m ON m.id=g.device
        WHERE g.plugin_digest=? AND m.revoked=0 AND m.owner=0''', (core.digest(token),)).fetchone()
    if not row:
        raise core.ApiError(401, '健康查询凭据无效或已轮换。')
    prune(db, _now_ms())
    settings = json.loads(row['settings'])
    if not settings['metrics']:
        raise core.ApiError(403, '手机尚未授权健康共享。')
    _profile(db, {'id': row['device'], 'username': row['username'], 'owner': 0}, settings['profile'])
    return dict(settings, device=row['device'], username=row['username'], revision=row['revision'],
                lastSyncMs=row['last_sync_ms'], statuses=json.loads(row['statuses']), metricSync=json.loads(row['metric_sync']))


def read_metric(db, grant, query, now_ms):
    try:
        q = wearable_metrics.normalize_query(query, timezone=grant['timezone'], now_ms=now_ms)
        metric = q['metric']
        required = (metric, 'exercise') if metric == 'heart_rate' else (metric,)
        if not all(kind in grant['metrics'] for kind in required):
            raise core.ApiError(403, '未获准查询该指标。')
        server_now = _now_ms()
        contact = grant['lastSyncMs']
        offline = contact is not None and server_now - contact >= SYNC_LEASE_MS
        synced = grant['metricSync']
        last_sync = min(synced[m] for m in required) if all(m in synced for m in required) else None
        rows = db.execute('''SELECT payload FROM wearable_records WHERE device=? AND source=? AND metric IN (?,?)
            AND expires_ms>? AND end_ms>=? AND start_ms<=? AND payload IS NOT NULL''',
                          (grant['device'], grant['source'], required[0], required[-1], server_now, q['startMs'], q['endMs']))
        result = wearable_metrics.query_metrics((json.loads(r[0]) for r in rows), query, source=grant['source'],
                                               timezone=grant['timezone'], now_ms=now_ms, last_sync_ms=last_sync,
                                               statuses=grant['statuses'], offline=offline)
        result.update(lastContactMs=contact, offlineInferred=offline,
                      connectionStatus='never_synced' if contact is None else 'no_recent_sync' if offline else 'recent_sync')
        result['stale'] = last_sync is not None and server_now - last_sync >= wearable_metrics.STALE_AFTER_MS
        if result['stale'] and result['dataStatus'] not in ('no_permission', 'source_unavailable'):
            result['status'] = 'stale'
        return result
    except ValueError as exc:
        raise core.ApiError(400, str(exc)) from exc
