#!/usr/bin/env python3
import unittest
import performance_soak as soak


def fixture(duration=910, fps=None):
    clock = (4194304, 1) if fps is None else (47250000, 11)
    hz = clock[0]/clock[1]
    fps = hz/70224
    records = [dict(schema=soak.SCHEMA, kind='metadata', artifactId='a'*64,
                    deviceId='b'*64, powerPolicy='battery', minimumSeconds=900, maximumSeconds=1800)]
    for t in range(duration+1):
        a = dict.fromkeys(soak.APP_INTS, 0)
        a.update(dict.fromkeys(soak.APP_BOOLS, False))
        a.update(schema=soak.APP_SCHEMA, recordingGeneration=1, sessionGeneration=1,
                 hostTimeNanos=(t+1)*1000000000, masterTicks=int(t*hz), nativeFrames=int(t*fps),
                 renderedFrames=int(t*fps), submittedFrames=int(t*fps), workP95Nanos=8000000,
                 workMaxNanos=12000000, clockNumerator=clock[0], clockDenominator=clock[1],
                 speed=1, hardwareProfile='dmg' if clock[1] == 1 else 'sgb', executionMode='PERFORMANCE',
                 audioSampleRate=48000, audioVolume=100, systemVolume=5, audioPlaybackFrames=48000*t,
                 audioWrittenFrames=48000*t, audioOutputIdentity=1, audioQueueIdentity=2,
                 visible=True, audioAvailable=True, audioActive=True, audioPlaying=True, audioOpen=True,
                 audioSnapshotSequence=t+1, audioSnapshotDropped=0,
                 audioSnapshotRequestedAtNanos=(t+1)*1000000000,
                 audioSnapshotCapturedAtNanos=(t+1)*1000000000+1000000,
                 audioSnapshotCompletedAtNanos=(t+1)*1000000000+2000000,
                 audioSnapshotRouteGeneration=1, audioSnapshotStatus='FRESH')
        records.append(dict(kind='app', at=float(t), sample=a))
        if t % 5 == 0:
            records.append(dict(kind='device', at=float(t), displayRefreshHz=60.0 if clock[1] == 1 else 120.0,
                                surface=dict(layerId='c'*64, totalFrames=int(t*fps), droppedFrames=0,
                                             histogram={'16': int(t*fps)}),
                                thermal=dict(status=0, sensors={'d'*64: 40.0}, batteryC=30.0, plugged=False)))
    return records


class SoakTest(unittest.TestCase):
    def test_native_and_sgb_pass(self):
        for records in (fixture(), fixture(fps=61)):
            verdict = soak.analyze(records)
            self.assertEqual('PASS', verdict['status'], verdict)
            self.assertFalse(verdict['headroomRisk'])

    def test_sgb_sixty_hz_cannot_pass(self):
        r = fixture(fps=61)
        for row in r:
            if row['kind'] == 'device': row['displayRefreshHz'] = 60
        self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'])

    def test_cpu_double_speed_transition_keeps_native_cadence(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'app' and row['at'] > 400: row['sample']['speed'] = 2
        self.assertEqual('PASS', soak.analyze(r)['status'])
        for row in r:
            if row['kind'] == 'app' and row['at'] > 400: row['sample']['dmgCompat'] = True
        self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'])

    def test_short_run_and_missing_compositor_cannot_pass(self):
        self.assertEqual('INCONCLUSIVE', soak.analyze(fixture(899))['status'])
        self.assertEqual('INCONCLUSIVE', soak.analyze([r for r in fixture() if r['kind'] != 'device'])['status'])

    def test_generation_reset_and_app_counter_reset(self):
        for key, value in [('sessionGeneration', 2), ('masterTicks', 0)]:
            r = fixture()
            r[-2]['sample'][key] = value
            self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'])

    def test_sustained_slowdown(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'app': row['sample']['masterTicks'] = int(row['sample']['masterTicks']*.95)
        self.assertEqual('FAIL', soak.analyze(r)['status'])

    def test_two_one_second_dips_fail_even_with_good_average(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'app':
                t = row['at']
                lost = .1*max(0, min(t-500, 2))*4194304
                row['sample']['masterTicks'] -= int(lost)
        verdict = soak.analyze(r)
        self.assertEqual('FAIL', verdict['status'])
        self.assertIn('consecutive emulation intervals below 95% native cadence', verdict['reasons'])

    def test_occasional_dip_is_allowed(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'app' and row['at'] > 500:
                row['sample']['masterTicks'] -= int(.06*4194304)
        self.assertEqual('PASS', soak.analyze(r)['status'])

    def test_submission_does_not_stand_in_for_presentations(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'device':
                row['surface']['totalFrames'] //= 2
                row['surface']['histogram']['16'] //= 2
        self.assertEqual('FAIL', soak.analyze(r)['status'])

    def test_frame_suppression_is_visible_failure(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'app':
                a = row['sample']
                a['suppressedFrames'] = a['nativeFrames']//10
                a['renderedFrames'] -= a['suppressedFrames']
        verdict = soak.analyze(r)
        self.assertEqual('FAIL', verdict['status'])
        self.assertGreater(verdict['suppressedFrames'], 3000)

    def test_audio_and_hints(self):
        for key, value, expected in [('audioMuted', True, 'FAIL'), ('audioUnderruns', 1, 'FAIL'),
                                      ('audioPlaybackFrames', 0, 'INCONCLUSIVE'),
                                      ('hintsActive', True, 'INCONCLUSIVE')]:
            r = fixture()
            r[-2]['sample'][key] = value
            self.assertEqual(expected, soak.analyze(r)['status'], key)

    def test_async_snapshot_drop_and_sequence_gap_are_inconclusive(self):
        for mutate in (lambda a: a.__setitem__('audioSnapshotDropped', 1),
                       lambda a: a.__setitem__('audioSnapshotSequence', 9001)):
            r = fixture()
            rows = [row for row in r if row['kind'] == 'app']
            mutate(rows[500]['sample'])
            verdict = soak.analyze(r)
            self.assertEqual('INCONCLUSIVE', verdict['status'])
            self.assertTrue(any('audio diagnostic' in reason for reason in verdict['reasons']), verdict)

    def test_async_snapshot_status_and_latency_are_inconclusive(self):
        for status in ('STALE', 'INCOHERENT', 'UNAVAILABLE'):
            r = fixture()
            r[-2]['sample']['audioSnapshotStatus'] = status
            if status == 'STALE':
                r[-2]['sample']['audioSnapshotCompletedAtNanos'] += soak.MAX_AUDIO_SNAPSHOT_LATENCY_NANOS + 1
            self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'], status)

    def test_async_snapshot_request_stays_bound_to_owner_sample(self):
        r = fixture()
        r[-2]['sample']['audioSnapshotRequestedAtNanos'] = (
                r[-2]['sample']['hostTimeNanos'] + soak.MAX_REQUEST_ENQUEUE_AGE_NANOS + 1)
        verdict = soak.analyze(r)
        self.assertEqual('INCONCLUSIVE', verdict['status'])
        self.assertIn('detached', ' '.join(verdict['reasons']))

    def test_async_audio_cadence_uses_audio_observation_timebase(self):
        r = fixture()
        apps = [row for row in r if row['kind'] == 'app']
        # Stretch the controller/logcat timestamp for one interval while preserving the actual
        # audio observation timestamps and counters. The audio cadence check must use the latter.
        for row in apps[501:]:
            row['at'] += 1.0
        self.assertEqual('PASS', soak.analyze(r)['status'])

    def test_audio_cadence_uses_position_timestamp_when_completion_is_delayed(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'app':
                sample = row['sample']
                sample['audioSnapshotCompletedAtNanos'] = (
                        sample['audioSnapshotCapturedAtNanos']
                        + (800_000_000 if int(row['at']) % 2 == 0 else 100_000_000))
        self.assertEqual('PASS', soak.analyze(r)['status'])

    def test_legacy_v1_remains_parseable_without_async_certification(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'app':
                sample = row['sample']
                for key in tuple(soak.APP_KEYS - soak.APP_KEYS_V1):
                    sample.pop(key, None)
                sample['schema'] = soak.APP_V1_SCHEMA
        verdict = soak.analyze(r)
        self.assertEqual('PASS', verdict['status'], verdict)
        self.assertFalse(verdict['asyncAudioSnapshots'])

    def test_thermal_drift_and_status_changes_extend_run(self):
        for change in ('temperature', 'status'):
            r = fixture()
            for row in r:
                if row['kind'] == 'device' and row['at'] > 500:
                    if change == 'temperature': row['thermal']['sensors']['d'*64] += (row['at']-500)/100
                    else: row['thermal']['status'] = 1
            self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'])

    def test_steady_throttled_device_is_measured(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'device': row['thermal']['status'] = 2
        self.assertEqual('PASS', soak.analyze(r)['status'])

    def test_gap_and_unknown_keys_cannot_pass(self):
        r = fixture()
        r = [row for row in r if row.get('at') not in (500, 501, 502, 503)]
        self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'])
        r = fixture()
        r[-2]['sample']['privateUnexpectedField'] = 'redacted'
        self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'])

    def test_device_nan_and_negative_counters_are_ineligible(self):
        for key in ('nan', 'counter', 'histogram'):
            r = fixture()
            if key == 'nan': r[-1]['displayRefreshHz'] = float('nan')
            elif key == 'counter': r[-1]['surface']['totalFrames'] = -1
            else: r[-1]['surface']['histogram']['16'] = -1
            self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'])

    def test_fast_forward_and_cpu_boost_are_ineligible(self):
        for key in ('fast', 'priority'):
            r = fixture()
            for row in r:
                if row['kind'] == 'app':
                    if key == 'fast': row['sample']['masterTicks'] *= 2
                    else: row['sample']['controllerPriority'] = -8
            self.assertEqual('INCONCLUSIVE', soak.analyze(r)['status'])

    def test_presentation_loss_and_headroom_are_reported(self):
        r = fixture()
        for row in r:
            if row['kind'] == 'app': row['sample']['workP95Nanos'] = 15000000
        verdict = soak.analyze(r)
        self.assertEqual('PASS', verdict['status'])
        self.assertTrue(verdict['headroomRisk'])
        for row in r:
            if row['kind'] == 'device': row['surface']['droppedFrames'] = int(row['at']*3)
        self.assertEqual('FAIL', soak.analyze(r)['status'])

    def test_surface_parser_uses_exact_uid_layer_and_real_histogram(self):
        dump = '''layerName = SurfaceView[example/.Main](BLAST)#1
uid = 10001
totalFrames = 600
droppedFrames = 2
present2present histogram is as below:
16ms=598 50ms=2
'''
        parsed = soak.parse_surface(dump, 'SurfaceView[example/.Main](BLAST)#1', 10001)
        self.assertEqual(600, parsed['totalFrames'])
        self.assertNotIn('layerName', parsed)
        with self.assertRaises(soak.EvidenceError):
            soak.parse_surface(dump, 'SurfaceView[example/.Main](BLAST)#1', 10002)
        with self.assertRaises(soak.EvidenceError):
            soak.parse_surface(dump+dump, 'SurfaceView[example/.Main](BLAST)#1', 10001)

    def test_oem_wrapped_layer_list_has_exact_identity(self):
        token = 'SurfaceView[example/.Main](BLAST)#123'
        self.assertEqual(token, soak.resolve_layer('RequestedLayerState{'+token+' parentId=45}', 'example'))
        self.assertEqual(token, soak.resolve_layer(token, 'example'))
        with self.assertRaises(soak.EvidenceError):
            soak.resolve_layer(token+'\n'+token, 'example')
        with self.assertRaises(soak.EvidenceError):
            soak.resolve_layer(token, 'other')

    def test_exact_package_uid_not_app_id(self):
        self.assertEqual(110350, soak.resolve_uid('package:example uid:110350\n', 'example'))
        with self.assertRaises(soak.EvidenceError):
            soak.resolve_uid('package:example.other uid:10001\n', 'example')

    def test_surface_uid_before_name_never_borrows_next_uid(self):
        layer = 'SurfaceView[example/.Main](BLAST)#1'
        dump = ('displayRefreshRate = 60 fps\nuid = 10001\nlayerName = '+layer+
                '\ntotalFrames = 60\ndroppedFrames = 0\npresent2present histogram is as below:\n16ms=60\n\n'+
                'uid = 1000\nlayerName = none\ntotalFrames = 0\ndroppedFrames = 0\n')
        self.assertEqual(60, soak.parse_surface(dump, layer, 10001)['totalFrames'])
        with self.assertRaises(soak.EvidenceError):
            soak.parse_surface(dump, layer, 1000)
        with self.assertRaises(soak.EvidenceError):
            soak.parse_surface(dump.replace('uid = 10001', 'uid = 10001\nuid = 1000'), layer, 10001)
        with self.assertRaises(soak.EvidenceError):
            soak.parse_surface(dump.replace('16ms=60', '16ms=60 16ms=60'), layer, 10001)

    def test_current_hal_only_and_active_mode(self):
        dump = '''Thermal Status: 0
Cached temperatures:
 Temperature{mValue=95.0, mType=0, mName=CPU, mStatus=4}
Current temperatures from HAL:
 Temperature{mValue=40.0, mType=0, mName=CPU, mStatus=0}
 Temperature{mValue=30.0, mType=3, mName=SKIN, mStatus=0}
Current cooling devices from HAL:
'''
        thermal = soak.parse_thermal(dump, 'USB powered: false\nAC powered: false\ntemperature: 300\n')
        self.assertEqual([30, 40], sorted(thermal['sensors'].values()))
        self.assertEqual(60, soak.parse_refresh('    activeMode={id=2, vsyncRate=60.00 Hz, group=0}'))
        with self.assertRaises(soak.EvidenceError):
            soak.parse_refresh('supportedModes={fps=60 fps=120}')


if __name__ == '__main__':
    unittest.main()
