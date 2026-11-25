"""Wearable sharing uses existing device membership and separate opt-in grants."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from shared_service import device_service, service, wearable_store as store


class WearableStoreTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name, 'service.db')
        service.initialize(self.path, 'https://chat.example')
        service.migrate_database(self.path)
        with service.database(self.path, write=True) as db:
            store.migrate_wearable(db)
            for identity, owner, username in [('pc', 1, ''), ('phone', 0, 'alice'), ('guest', 0, 'bob')]:
                db.execute('INSERT INTO device_members(id,digest,owner,username) VALUES(?,?,?,?)',
                           (identity, service.digest(identity + '-token'), owner, username))
            for profile, origin, username in [('personal', 'phone', 'alice'), ('foreign', 'guest', 'bob'),
                                               ('desktop', 'pc', 'alice'), ('other', 'pc', 'bob')]:
                db.execute('INSERT INTO profiles(id,source_id,base,username,name) VALUES(?,?,?,?,?)',
                           (profile, profile, 'https://chat.example', username, profile))
                db.execute('INSERT INTO device_catalog(profile,origin,source_id,base,username) VALUES(?,?,?,?,?)',
                           (profile, origin, profile, 'https://chat.example', username))

        self.now = 1732000000000
        clock = patch.object(store, '_now_ms', side_effect=lambda: self.now)
        clock.start()
        self.addCleanup(clock.stop)
        self.config = dict(revision=1, profile='personal', sid='', metrics=['exercise', 'steps'], source='band',
                           timezone='UTC', morningTime='08:00', waitMinutes=60, workoutEnabled=True,
                           morningEnabled=False, notifyWorkout=False, notifyMorning=False)

    def phone(self, action, body, who='phone'):
        with service.database(self.path, write=True) as db:
            return store.phone_action(db, 'wearable-' + action, who + '-token', body)

    def query(self, token, metric):
        with service.database(self.path) as db:
            grant = store.plugin_grant(db, token)
            return store.read_metric(db, grant, {'metric': metric}, self.now)

    def batch(self, cursor, records=(), deletes=(), initial=False, revision=1, **states):
        return dict(cursor=cursor, revision=revision, initial=initial, records=list(records),
                    deletes=list(deletes), statuses=states)

    def record(self, ident, version=1, **fields):
        return dict(dict(id=ident, source='band', metric='exercise', version=version,
                         startMs=self.now-8000, endMs=self.now-1000, exerciseType='running'), **fields)

    def credential(self):
        return self.phone('credential', {'revision': 1})['token']

    def test_only_own_phone_profile_can_grant_and_rotate(self):
        for who, profile in [('pc', 'desktop'), ('phone', 'desktop'), ('phone', 'other'), ('phone', 'foreign'), ('guest', 'personal')]:
            with self.subTest(who=who, profile=profile), self.assertRaises(service.ApiError):
                self.phone('config', dict(self.config, profile=profile), who)
        self.phone('config', self.config)
        with self.assertRaises(service.ApiError):
            self.query('phone-token', 'exercise')
        first, second = self.credential(), self.credential()
        with self.assertRaises(service.ApiError):
            self.query(first, 'exercise')
        self.assertEqual(self.query(second, 'exercise')['dataStatus'], 'no_records')
        with self.assertRaises(service.ApiError):
            self.query(second, 'sleep')
        with service.database(self.path, write=True) as db:
            self.assertEqual(db.execute('SELECT plugin_digest FROM wearable_grants').fetchone()[0], service.digest(second))
            db.execute("UPDATE device_members SET revoked=1 WHERE id='phone'")
        with self.assertRaises(service.ApiError):
            self.query(second, 'exercise')

    def test_cursor_revision_tombstone_and_exercise_job(self):
        self.phone('config', self.config)
        token = self.credential()
        old = self.record('old')
        self.phone('sync', self.batch(1, [old]))
        self.phone('sync', self.batch(2, [self.record('another-old')], initial=True))
        new = self.record('new', endMs=self.now)
        third = self.batch(3, [new])
        response = self.phone('sync', third)
        self.now += 1000
        self.assertEqual(self.phone('sync', third), response)
        self.assertEqual(self.phone('config', self.config)['cursor'], 3)
        with self.assertRaises(service.ApiError):
            self.phone('sync', self.batch(5))
        # The invalid second record rolls back the first insert and cursor.
        with self.assertRaises(service.ApiError):
            self.phone('sync', self.batch(4, [self.record('rollback'), dict(new, exerciseType='bad')]))
        self.assertEqual(self.query(token, 'exercise')['data']['id'], 'new')
        revised = dict(new, version=3, exerciseType='walking')
        self.phone('sync', self.batch(4, [revised, new]))
        self.assertEqual(self.query(token, 'exercise')['data']['exerciseType'], 'walking')
        self.phone('sync', self.batch(5, deletes=[dict(metric='exercise', id='new', source='band', version=4)]))
        self.phone('sync', self.batch(6, [revised]))
        self.assertNotEqual(self.query(token, 'exercise')['data']['id'], 'new')
        with service.database(self.path) as db:
            jobs = list(db.execute('SELECT event_key FROM wearable_jobs'))
            self.assertEqual([r[0] for r in jobs], ['new'])
            self.assertEqual(db.execute("SELECT count(*) FROM wearable_records WHERE id='rollback'").fetchone()[0], 0)

    def test_revoke_clear_and_retention(self):
        self.phone('config', self.config)
        token = self.credential()
        self.phone('sync', self.batch(1, [self.record('old')]))
        self.now += store.RETENTION_MS + 1
        self.assertEqual(self.query(token, 'exercise')['dataStatus'], 'no_records')
        self.phone('sync', self.batch(2, [self.record('new')]))
        self.assertIsNotNone(self.query(token, 'exercise')['data'])
        self.phone('sync', self.batch(3, exercise='no_permission'))
        self.assertEqual(self.query(token, 'exercise')['dataStatus'], 'no_permission')
        with service.database(self.path) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM wearable_records').fetchone()[0], 0)
        self.phone('config', dict(self.config, metrics=['steps'], revision=2))
        with self.assertRaises(service.ApiError):
            self.query(token, 'exercise')
        self.phone('clear', {'revision': 2})
        with self.assertRaises(service.ApiError):
            self.phone('sync', self.batch(1, revision=2))
        self.phone('config', dict(self.config, revision=3, metrics=[]))
        with self.assertRaises(service.ApiError):
            self.query(token, 'steps')
        with self.assertRaises(service.ApiError):
            self.phone('config', self.config)

    def test_heartbeat_lease_and_independent_metric_freshness(self):
        self.phone('config', self.config)
        token = self.credential()
        self.assertEqual(self.query(token, 'exercise')['connectionStatus'], 'never_synced')
        observed = self.now
        self.phone('sync', self.batch(1, [self.record('history')], exercise='ok'))
        self.now = observed + store.SYNC_LEASE_MS - 1
        self.assertFalse(self.query(token, 'exercise')['offlineInferred'])
        self.now += 1
        result = self.query(token, 'exercise')
        self.assertEqual((result['status'], result['connectionStatus'], result['lastContactMs']),
                         ('phone_offline', 'no_recent_sync', observed))
        self.now = observed + store.wearable_metrics.STALE_AFTER_MS
        self.phone('sync', self.batch(2, steps='source_unavailable'))
        result = self.query(token, 'exercise')
        self.assertEqual((result['status'], result['lastSyncMs'], result['lastContactMs']), ('stale', observed, self.now))
        self.assertFalse(result['offlineInferred'])
        self.assertIsNone(self.query(token, 'steps')['lastSyncMs'])
