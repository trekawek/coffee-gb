#!/usr/bin/env python3
"""Versioned, bounded ordinary Android gameplay soak. Python standard library only."""
import argparse
import hashlib
import json
import math
import queue
import re
import statistics
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

SCHEMA = 'coffee-gb-soak-v1'
APP_V1_SCHEMA = 'coffee-gb-soak-app-v1'
APP_SCHEMA = 'coffee-gb-soak-app-v2'
MIN_SECONDS, STABLE_SECONDS, MAX_SECONDS = 900, 600, 1800
APP_INTS_V1 = '''recordingGeneration sessionGeneration hostTimeNanos masterTicks nativeFrames
renderedFrames suppressedFrames submittedFrames submissionFailures workP95Nanos workMaxNanos
clockNumerator clockDenominator speed controllerPriority audioSampleRate audioVolume systemVolume
audioPlaybackFrames audioWrittenFrames audioUnderruns audioOverruns audioDiscardedBytes
audioWriteFailures audioRouteFailures audioRestarts audioOutputIdentity audioQueueIdentity'''.split()
APP_BOOLS_V1 = '''dmgCompat pacingDebt visible hintsActive audioAvailable audioActive audioPaused audioPlaying
audioOpen audioMuted systemMuted'''.split()
APP_KEYS_V1 = set(APP_INTS_V1 + APP_BOOLS_V1 + ['schema', 'hardwareProfile', 'executionMode'])
APP_INTS = APP_INTS_V1 + '''audioSnapshotSequence audioSnapshotDropped
audioSnapshotRequestedAtNanos audioSnapshotCapturedAtNanos audioSnapshotCompletedAtNanos
audioSnapshotRouteGeneration'''.split()
APP_BOOLS = APP_BOOLS_V1
APP_COUNTERS = '''masterTicks nativeFrames renderedFrames suppressedFrames submittedFrames
submissionFailures audioPlaybackFrames audioWrittenFrames audioUnderruns audioOverruns
audioDiscardedBytes audioWriteFailures audioRouteFailures audioRestarts audioSnapshotDropped'''.split()
APP_KEYS = set(APP_INTS + APP_BOOLS + ['schema', 'hardwareProfile', 'executionMode',
                                         'audioSnapshotStatus'])
AUDIO_UNAVAILABLE_INTS = set(APP_INTS_V1) - {
    'recordingGeneration', 'sessionGeneration', 'hostTimeNanos', 'masterTicks',
    'nativeFrames', 'renderedFrames', 'suppressedFrames', 'submittedFrames',
    'submissionFailures', 'workP95Nanos', 'workMaxNanos', 'clockNumerator',
    'clockDenominator', 'speed', 'controllerPriority'}
SNAPSHOT_STATUSES = {'FRESH', 'STALE', 'INCOHERENT', 'UNAVAILABLE'}
MAX_AUDIO_SNAPSHOT_LATENCY_NANOS = 2_000_000_000
MAX_REQUEST_ENQUEUE_AGE_NANOS = 2_000_000_000
PROFILES = {'DMG', 'MGB', 'CGB', 'CGB0', 'CGB_DMG', 'CGB0_DMG', 'SGB', 'SGB2',
            'dmg', 'mgb', 'cgb', 'cgb0', 'cgb-dmg', 'cgb0-dmg', 'sgb', 'sgb2'}


class EvidenceError(ValueError):
    """Messages must contain no device, ROM, layer or shell output."""


def validate_app(sample):
    if not isinstance(sample, dict):
        raise EvidenceError('missing or incompatible app telemetry')
    schema = sample.get('schema')
    legacy = schema == APP_V1_SCHEMA
    ints = APP_INTS_V1 if legacy else APP_INTS
    bools = APP_BOOLS_V1 if legacy else APP_BOOLS
    keys = APP_KEYS_V1 if legacy else APP_KEYS
    if set(sample) != keys or schema not in {APP_V1_SCHEMA, APP_SCHEMA}:
        raise EvidenceError('missing or incompatible app telemetry')
    if any(type(sample[k]) is not int for k in ints):
        raise EvidenceError('invalid app numeric telemetry')
    if any(type(sample[k]) is not bool for k in bools):
        raise EvidenceError('invalid app boolean telemetry')
    if sample['hardwareProfile'] not in PROFILES or sample['executionMode'] not in {'PERFORMANCE', 'ACCURACY'}:
        raise EvidenceError('unknown hardware or execution mode')
    if legacy:
        negative = [k for k in ints if k != 'controllerPriority']
    else:
        optional = AUDIO_UNAVAILABLE_INTS | {
            'audioSnapshotRequestedAtNanos', 'audioSnapshotCapturedAtNanos',
            'audioSnapshotCompletedAtNanos', 'audioSnapshotRouteGeneration'}
        optional = {k for k in optional if k in ints}
        negative = [k for k in ints if k != 'controllerPriority'
                    and not (sample['audioSnapshotStatus'] != 'FRESH' and k in optional)]
        if sample['audioSnapshotStatus'] not in SNAPSHOT_STATUSES:
            raise EvidenceError('unknown audio snapshot status')
    if any(sample[k] < 0 for k in negative):
        raise EvidenceError('unavailable app counters')
    if (sample['clockNumerator'] <= 0 or sample['clockDenominator'] <= 0
            or (legacy and sample['audioSampleRate'] <= 0)
            or (not legacy and sample['audioSnapshotStatus'] == 'FRESH'
                and sample['audioSampleRate'] <= 0)):
        raise EvidenceError('invalid clock or audio sample rate')
    required = ['recordingGeneration', 'sessionGeneration', 'workP95Nanos', 'workMaxNanos']
    if legacy:
        required += ['audioOutputIdentity', 'audioQueueIdentity']
    elif sample['audioSnapshotStatus'] == 'FRESH':
        required += ['audioOutputIdentity', 'audioQueueIdentity', 'audioSnapshotSequence',
                     'audioSnapshotRouteGeneration']
    if min(sample[k] for k in required) <= 0:
        raise EvidenceError('missing live session/audio/work evidence')
    return sample


def validate_snapshot_evidence(apps):
    """Validate the asynchronous request ledger before it can affect a verdict."""
    for row in apps:
        sample = row['sample']
        if sample['audioSnapshotStatus'] != 'FRESH':
            raise EvidenceError('audio snapshot was '+sample['audioSnapshotStatus'].lower())
        if (sample['audioSnapshotRequestedAtNanos'] < sample['hostTimeNanos']
                or sample['audioSnapshotRequestedAtNanos'] - sample['hostTimeNanos']
                > MAX_REQUEST_ENQUEUE_AGE_NANOS):
            raise EvidenceError('audio snapshot request was detached from its owner sample')
        if sample['audioSnapshotDropped'] != 0:
            raise EvidenceError('audio diagnostic requests dropped')
        requested = sample['audioSnapshotRequestedAtNanos']
        captured = sample['audioSnapshotCapturedAtNanos']
        completed = sample['audioSnapshotCompletedAtNanos']
        if not requested <= captured <= completed:
            raise EvidenceError('audio snapshot timestamps are incoherent')
        if completed-requested > MAX_AUDIO_SNAPSHOT_LATENCY_NANOS:
            raise EvidenceError('audio snapshot was stale')
    for previous, current in zip(apps, apps[1:]):
        before, after = previous['sample'], current['sample']
        if after['audioSnapshotSequence'] != before['audioSnapshotSequence'] + 1:
            raise EvidenceError('audio diagnostic request sequence dropped')
        if after['audioSnapshotRequestedAtNanos'] <= before['audioSnapshotRequestedAtNanos']:
            raise EvidenceError('audio snapshot request clock did not advance')
        if after['audioSnapshotCapturedAtNanos'] <= before['audioSnapshotCapturedAtNanos']:
            raise EvidenceError('audio snapshot clock did not advance')
        if after['audioSnapshotCompletedAtNanos'] <= before['audioSnapshotCompletedAtNanos']:
            raise EvidenceError('audio snapshot completion clock did not advance')


def parse_surface(dump, layer, uid):
    # A layer is one paragraph; UID may precede or follow layerName. Splitting at
    # layerName can incorrectly borrow the next layer's UID on current Android.
    records = re.split(r'\n[ \t]*\n', dump)
    matches = [r for r in records if re.search(r'^\s*layerName\s*=\s*' + re.escape(layer) + r'\s*$', r, re.M)
               and re.search(r'^\s*uid\s*=\s*' + str(uid) + r'\s*$', r, re.M)]
    if len(matches) != 1:
        raise EvidenceError('no unique compositor presentation record')
    record = matches[0]
    if (len(re.findall(r'^\s*layerName\s*=', record, re.M)) != 1
            or len(re.findall(r'^\s*uid\s*=', record, re.M)) != 1):
        raise EvidenceError('ambiguous compositor identity fields')
    result = {}
    for name in ('totalFrames', 'droppedFrames'):
        found = re.findall(r'^\s*' + name + r'\s*=\s*(\d+)\s*$', record, re.M)
        if len(found) != 1:
            raise EvidenceError('missing compositor counters')
        result[name] = int(found[0])
    histogram = re.search(r'present2present histogram is as below:\s*\n([^\n]+)', record)
    if not histogram:
        raise EvidenceError('missing real presentation interval histogram')
    bins = re.findall(r'(\d+)ms\s*=\s*(\d+)', histogram[1])
    if (not bins or len({ms for ms, _ in bins}) != len(bins)
            or not re.fullmatch(r'\s*(?:\d+ms\s*=\s*\d+\s*)+', histogram[1])):
        raise EvidenceError('empty or malformed presentation interval histogram')
    result['histogram'] = {str(int(ms)): int(count) for ms, count in bins}
    result['layerId'] = hashlib.sha256(layer.encode()).hexdigest()
    return result


def resolve_layer(listing, package):
    # Newer OEMs wrap --list entries in RequestedLayerState{... parentId=...}; the
    # TimeStats identity is only the embedded exact SurfaceView token.
    candidates = re.findall(r'SurfaceView\['+re.escape(package)+r'/[^\]\r\n]*\]\(BLAST\)#\d+', listing)
    if len(candidates) != 1:
        raise EvidenceError('no unique active application SurfaceView')
    return candidates[0]


def resolve_uid(listing, package):
    # `dumpsys package` changed userId to appId on newer releases. Query the actual
    # package UID instead, which also avoids treating an appId as a multi-user UID.
    matches = re.findall(r'^package:'+re.escape(package)+r' uid:(\d+)\s*$', listing, re.M)
    if len(matches) != 1:
        raise EvidenceError('application UID unavailable or ambiguous')
    return int(matches[0])


def parse_thermal(dump, battery):
    if 'IsStatusOverride: true' in dump or 'UPDATES STOPPED' in battery:
        raise EvidenceError('thermal or battery reporting is overridden')
    statuses = re.findall(r'Thermal Status:\s*(\d+)', dump)
    if len(statuses) != 1:
        raise EvidenceError('thermal status unavailable')
    # Only current HAL temperatures, never the cached/event history sections. Prefer SKIN,
    # otherwise CPU, and hash each sensor name to keep a stable sensor identity without names.
    section = re.search(r'Current temperatures from HAL:(.*?)(?:\n\S|\Z)', dump, re.S)
    sensors = {}
    if section:
        for value, kind, name in re.findall(r'Temperature\{mValue=([\d.]+), mType=(\d+), mName=([^,}]+)', section[1]):
            if int(kind) in (0, 3) and 0 < float(value) < 150:
                sensors[hashlib.sha256((kind + ':' + name).encode()).hexdigest()] = float(value)
    temperature = re.search(r'^\s*temperature:\s*(\d+)\s*$', battery, re.M)
    powers = re.findall(r'^\s*(?:AC|USB|Wireless|Dock) powered:\s*(true|false)', battery, re.M)
    if not sensors or not temperature or not powers:
        raise EvidenceError('current CPU/skin temperature or battery evidence unavailable')
    return {'status': int(statuses[0]), 'sensors': sensors,
            'batteryC': int(temperature[1]) / 10, 'plugged': 'true' in powers}


def parse_refresh(dump):
    # SurfaceFlinger reports the active mode separately from the supported-mode inventory.
    patterns = [r'activeMode=\{[^\n}]*?(?:fps|vsyncRate)=([\d.]+)',
                r'mActiveMode=\{[^\n}]*?(?:fps|refreshRate)=([\d.]+)',
                r'activeMode=.*?fps=([\d.]+)']
    for pattern in patterns:
        values = re.findall(pattern, dump)
        if len(values) == 1 and 20 <= float(values[0]) <= 500:
            return float(values[0])
    raise EvidenceError('active display refresh unavailable')


def result(status, reasons, **metrics):
    return dict(schema=SCHEMA, kind='result', status=status, reasons=sorted(set(reasons)), **metrics)


def validate_device(row):
    surface, thermal = row['surface'], row['thermal']
    if (not re.fullmatch(r'[a-f0-9]{64}', surface['layerId'])
            or any(type(surface[k]) is not int or surface[k] < 0 for k in ('totalFrames', 'droppedFrames'))
            or not isinstance(surface['histogram'], dict) or not surface['histogram']
            or any(not re.fullmatch(r'\d+', k) or type(v) is not int or v < 0
                   for k, v in surface['histogram'].items())):
        raise EvidenceError('invalid real compositor evidence')
    if (type(thermal['status']) is not int or not 0 <= thermal['status'] <= 6
            or type(thermal['plugged']) is not bool or not thermal['sensors']
            or any(not re.fullmatch(r'[a-f0-9]{64}', k) or type(v) not in (int, float)
                   or not math.isfinite(v) or not 0 < v < 150 for k, v in thermal['sensors'].items())
            or not math.isfinite(thermal['batteryC']) or not -30 < thermal['batteryC'] < 100
            or not math.isfinite(row['displayRefreshHz']) or not 20 <= row['displayRefreshHz'] <= 500):
        raise EvidenceError('invalid thermal, power, or active display evidence')


def analyze(records):
    """Fail closed. Only a final continuous, thermally stable ten minutes can PASS."""
    try:
        meta = records[0]
        if meta.get('schema') != SCHEMA or meta.get('kind') != 'metadata':
            raise EvidenceError('missing versioned metadata')
        if any(not re.fullmatch(r'[a-f0-9]{64}', meta.get(k, '')) for k in ('artifactId', 'deviceId')):
            raise EvidenceError('missing artifact/device identity')
        if meta.get('powerPolicy') not in {'battery', 'plugged'}:
            raise EvidenceError('missing power policy')
        if not (MIN_SECONDS <= meta.get('minimumSeconds', 0) <= meta.get('maximumSeconds', 0) <= 3600):
            raise EvidenceError('invalid bounded duration policy')
        if any(r.get('kind') == 'error' for r in records):
            raise EvidenceError('collection lost required evidence')
        apps = [r for r in records if r.get('kind') == 'app']
        devices = [r for r in records if r.get('kind') == 'device']
        if len(apps) < 2 or len(devices) < 2:
            raise EvidenceError('app and compositor samples are both required')
        app_schemas = {r.get('sample', {}).get('schema') for r in apps}
        if len(app_schemas) != 1 or app_schemas - {APP_V1_SCHEMA, APP_SCHEMA}:
            raise EvidenceError('app telemetry schema changed')
        async_audio = app_schemas == {APP_SCHEMA}
        for r in apps:
            validate_app(r['sample'])
        if async_audio:
            validate_snapshot_evidence(apps)
        for r in devices:
            validate_device(r)
        for rows, gap in ((apps, 3.0), (devices, 12.0)):
            if any(type(r['at']) not in (float, int) or not math.isfinite(r['at']) for r in rows):
                raise EvidenceError('invalid observation timestamps')
            if any(not 0 < b['at'] - a['at'] <= gap for a, b in zip(rows, rows[1:])):
                raise EvidenceError('sample gap or nonmonotonic observation clock')
        generations = {(r['sample']['recordingGeneration'], r['sample']['sessionGeneration']) for r in apps}
        if len(generations) != 1:
            raise EvidenceError('recording or session generation changed')
        invariant = ['clockNumerator', 'clockDenominator', 'hardwareProfile', 'dmgCompat',
                     'executionMode', 'audioOutputIdentity', 'audioQueueIdentity', 'audioSampleRate']
        if async_audio:
            invariant.append('audioSnapshotRouteGeneration')
        if any(len({r['sample'][k] for r in apps}) != 1 for k in invariant):
            raise EvidenceError('clock/profile/audio route changed')
        counters = APP_COUNTERS if async_audio else APP_COUNTERS[:-1]
        if any(b['sample'][k] < a['sample'][k] for a, b in zip(apps, apps[1:]) for k in counters):
            raise EvidenceError('cumulative app counters reset')
        if any(b['sample']['hostTimeNanos'] <= a['sample']['hostTimeNanos'] for a, b in zip(apps, apps[1:])):
            raise EvidenceError('app clock did not advance')
        if len({r['surface']['layerId'] for r in devices}) != 1:
            raise EvidenceError('presentation surface changed')
        for a, b in zip(devices, devices[1:]):
            for k in ('totalFrames', 'droppedFrames'):
                if b['surface'][k] < a['surface'][k]:
                    raise EvidenceError('compositor counters reset')
            if any(b['surface']['histogram'].get(k, 0) < v for k, v in a['surface']['histogram'].items()):
                raise EvidenceError('compositor histogram reset')
        start = max(apps[0]['at'], devices[0]['at'])
        end = min(apps[-1]['at'], devices[-1]['at'])
        if end - start < meta['minimumSeconds']:
            return result('INCONCLUSIVE', ['minimum 15-minute duration not reached'], durationSeconds=end-start)
        aa = [r for r in apps if r['at'] >= end-STABLE_SECONDS-2 and r['at'] <= end+2]
        dd = [r for r in devices if r['at'] >= end-STABLE_SECONDS-6 and r['at'] <= end+6]
        if aa[-1]['at']-aa[0]['at'] < STABLE_SECONDS-3 or dd[-1]['at']-dd[0]['at'] < STABLE_SECONDS-6:
            raise EvidenceError('final ten-minute window incomplete')
        sensor_sets = {tuple(sorted(r['thermal']['sensors'])) for r in dd}
        if len(sensor_sets) != 1 or not next(iter(sensor_sets)):
            raise EvidenceError('thermal sensors changed or unavailable')
        unstable = len({r['thermal']['status'] for r in dd}) != 1
        thermal_deltas = []
        for key in next(iter(sensor_sets)):
            early = [r['thermal']['sensors'][key] for r in dd if r['at'] <= dd[0]['at']+60]
            late = [r['thermal']['sensors'][key] for r in dd if r['at'] >= dd[-1]['at']-60]
            delta = statistics.mean(late)-statistics.mean(early)
            thermal_deltas.append(delta)
            unstable |= abs(delta) > 1.0
        if unstable:
            return result('INCONCLUSIVE', ['final ten minutes are not thermally stable'],
                          durationSeconds=end-start, thermalDeltaC=thermal_deltas)
        if any(r['thermal']['plugged'] != (meta['powerPolicy'] == 'plugged') for r in dd):
            raise EvidenceError('power source does not match declared policy')
        s = aa[0]['sample']
        hz = s['clockNumerator']/s['clockDenominator']
        fps = hz/70224
        if any(r['sample']['speed'] not in (1, 2) for r in aa) or s['executionMode'] != 'PERFORMANCE':
            raise EvidenceError('ordinary PERFORMANCE playback required')
        if any(not r['sample']['visible'] or r['sample']['hintsActive'] or r['sample']['controllerPriority'] < 0 for r in aa):
            raise EvidenceError('visible playback without CPU boost/hints required')
        # A refresh below native cadence cannot prove native presentation (notably SGB at 60Hz).
        if any(r['displayRefreshHz'] + 0.05 < fps for r in dd):
            raise EvidenceError('display refresh below native cadence; SGB requires a faster display mode')
        failures = []
        if any(r['thermal']['status'] >= 4 for r in dd):
            failures.append('critical thermal state')
        for r in aa:
            a = r['sample']
            if (not all(a[k] for k in ('audioAvailable', 'audioActive', 'audioPlaying', 'audioOpen'))
                    or any(a[k] for k in ('audioPaused', 'audioMuted', 'systemMuted'))
                    or min(a['audioVolume'], a['systemVolume']) <= 0):
                failures.append('audio was not continuously audible')
        first, last = aa[0]['sample'], aa[-1]['sample']
        dt = (last['hostTimeNanos']-first['hostTimeNanos'])/1e9
        native_ratio = (last['masterTicks']-first['masterTicks'])/dt/hz
        if native_ratio > 1.01:
            raise EvidenceError('playback exceeded native speed')
        if native_ratio < .99:
            failures.append('sustained emulation below 99% native cadence')
        rolling_min, slow_streak, worst_streak = 1.0, 0, 0
        left = 0
        for index, r in enumerate(aa):
            a = r['sample']
            if index:
                p = aa[index-1]['sample']
                interval = (a['hostTimeNanos']-p['hostTimeNanos'])/1e9
                ratio = (a['masterTicks']-p['masterTicks'])/interval/hz
                slow_streak = slow_streak+1 if ratio < .95 else 0
                worst_streak = max(worst_streak, slow_streak)
            while left+1 < index and (a['hostTimeNanos']-aa[left+1]['sample']['hostTimeNanos']) >= 10e9:
                left += 1
            p = aa[left]['sample']
            interval = (a['hostTimeNanos']-p['hostTimeNanos'])/1e9
            if interval >= 10:
                ratio = (a['masterTicks']-p['masterTicks'])/interval/hz
                rolling_min = min(rolling_min, ratio)
                if ratio < .99:
                    failures.append('rolling ten-second emulation below 99% native cadence')
                for key in ('renderedFrames', 'submittedFrames'):
                    if a[key]-p[key]+2 < .99*fps*interval:
                        failures.append('rolling ten-second '+key+' below 99% native cadence')
                audio_interval = ((a['audioSnapshotCapturedAtNanos']
                                   - p['audioSnapshotCapturedAtNanos'])/1e9
                                  if async_audio else interval)
                if audio_interval <= 0:
                    raise EvidenceError('audio observation clock did not advance')
                if (a['audioPlaybackFrames']-p['audioPlaybackFrames']
                        < .99*a['audioSampleRate']*audio_interval):
                    failures.append('audio playback stalled or below native cadence')
        if worst_streak >= 2:
            failures.append('consecutive emulation intervals below 95% native cadence')
        native = last['nativeFrames']-first['nativeFrames']
        if abs(native-(last['renderedFrames']-first['renderedFrames'])
               -(last['suppressedFrames']-first['suppressedFrames'])) > 1:
            raise EvidenceError('native/rendered/suppressed accounting disagrees')
        ratios = {}
        for key in ('renderedFrames', 'submittedFrames'):
            ratios[key] = (last[key]-first[key])/max(1, native)
            if ratios[key] < .99:
                failures.append(key+' below 99% of native frames')
        for key in ('audioUnderruns', 'audioOverruns', 'audioWriteFailures', 'audioRouteFailures',
                    'audioRestarts', 'audioDiscardedBytes', 'submissionFailures'):
            if last[key] > first[key]:
                failures.append(key+' increased in the stable window')
        present_dt = dd[-1]['at']-dd[0]['at']
        presented = dd[-1]['surface']['totalFrames']-dd[0]['surface']['totalFrames']
        presentation_ratio = presented/present_dt/fps
        if presentation_ratio < .99:
            failures.append('compositor presentation below 99% native cadence')
        dropped = dd[-1]['surface']['droppedFrames']-dd[0]['surface']['droppedFrames']
        if dropped > .01*max(1, presented):
            failures.append('compositor dropped more than 1% of frames')
        for index in range(2, len(dd)):
            a, b = dd[index-2], dd[index]
            elapsed = b['at']-a['at']
            if elapsed >= 9 and b['surface']['totalFrames']-a['surface']['totalFrames']+2 < .99*fps*elapsed:
                failures.append('rolling compositor presentation below 99% native cadence')
        hist_a, hist_b = dd[0]['surface']['histogram'], dd[-1]['surface']['histogram']
        hist = {k: v-hist_a.get(k, 0) for k, v in hist_b.items()}
        if sum(hist.values()) < .99*presented:
            raise EvidenceError('presentation histogram does not cover observed frames')
        long_gaps = sum(v for k, v in hist.items() if int(k) >= 50)
        if long_gaps > .01*max(1, sum(hist.values())):
            failures.append('recurring compositor gaps of at least 50ms')
        p95 = max(r['sample']['workP95Nanos'] for r in aa)
        return result('FAIL' if failures else 'PASS', failures, durationSeconds=end-start,
                      stableSeconds=STABLE_SECONDS, nativeFps=fps, nativeRatio=native_ratio,
                      hardwareProfile=s['hardwareProfile'], dmgCompat=s['dmgCompat'],
                      rollingNativeMinimum=rolling_min, presentedRatio=presentation_ratio,
                      frameRatios=ratios, suppressedFrames=last['suppressedFrames']-first['suppressedFrames'],
                      compositorDroppedFrames=dropped,
                      presentationLongGaps=long_gaps, thermalDeltaC=thermal_deltas,
                      workP95MaximumNanos=p95, headroomRisk=p95 > 1e9/fps*2/3,
                      pacingDebtSamples=sum(r['sample']['pacingDebt'] for r in aa),
                      audioPlaybackFrames=last['audioPlaybackFrames']-first['audioPlaybackFrames'],
                      audioUnderruns=last['audioUnderruns']-first['audioUnderruns'],
                      asyncAudioSnapshots=async_audio,
                      maxWorkNanos=max(r['sample']['workMaxNanos'] for r in aa))
    except (EvidenceError, KeyError, IndexError, TypeError, ValueError, ZeroDivisionError) as error:
        reason = str(error) if isinstance(error, EvidenceError) else 'malformed or incomplete evidence'
        return result('INCONCLUSIVE', [reason])


class Adb:
    def __init__(self, serial):
        self.command = ['adb'] + (['-s', serial] if serial else [])

    def run(self, *args):
        try:
            done = subprocess.run(self.command+list(args), capture_output=True, text=True, timeout=8, check=True)
            return done.stdout
        except (subprocess.SubprocessError, OSError):
            raise EvidenceError('device command failed or timed out') from None


def collect(args):
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]*', args.package):
        raise EvidenceError('invalid application package')
    if not MIN_SECONDS <= args.minimum_seconds <= args.maximum_seconds <= 3600:
        raise EvidenceError('duration must be bounded between 900 and 3600 seconds')
    adb = Adb(args.serial)
    selected_serial = adb.run('get-serialno').strip()
    if (not selected_serial or selected_serial == 'unknown' or len(selected_serial) > 256
            or any(c.isspace() for c in selected_serial)):
        raise EvidenceError('a single identified device is required')
    # Pin every subsequent command, including cleanup, to the identified device. A disconnect
    # must not cause a later default-adb command to select some other connected phone.
    adb = Adb(selected_serial)
    if not re.fullmatch(r'\d+\s*', adb.run('shell', 'pidof', args.package)):
        raise EvidenceError('start a single ordinary app process and game before collecting')
    uid = resolve_uid(adb.run('shell', 'cmd', 'package', 'list', 'packages', '-U', args.package), args.package)
    paths = adb.run('shell', 'pm', 'path', args.package).splitlines()
    if len(paths) != 1 or not re.fullmatch(r'package:/[A-Za-z0-9_./=+~-]+\.apk', paths[0]):
        raise EvidenceError('single installed APK identity unavailable')
    installed_fields = adb.run('shell', 'sha256sum', paths[0][8:]).split()
    if not installed_fields or not re.fullmatch(r'[a-f0-9]{64}', installed_fields[0]):
        raise EvidenceError('installed artifact hash unavailable')
    installed = installed_fields[0]
    artifact = hashlib.sha256(Path(args.apk).read_bytes()).hexdigest()
    if installed != artifact:
        raise EvidenceError('installed APK differs from the supplied artifact')
    device = hashlib.sha256((selected_serial+'\n'+adb.run('shell', 'getprop', 'ro.build.fingerprint').strip()).encode()).hexdigest()
    started = time.monotonic()
    records = [dict(schema=SCHEMA, kind='metadata', runId=uuid.uuid4().hex,
                    artifactId=artifact, deviceId=device, powerPolicy=args.power,
                    minimumSeconds=args.minimum_seconds, maximumSeconds=args.maximum_seconds)]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Open before spawning anything; an existing/unwritable file must leave no background process.
    handle = output.open('x', encoding='utf-8')
    incoming = queue.Queue(maxsize=4096)
    try:
        stream = subprocess.Popen(adb.command+['logcat', '-v', 'raw', '-T', '1', '-s', 'CoffeeGbSoak:I', '*:S'],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    except OSError:
        handle.close()
        raise EvidenceError('unable to start app telemetry stream') from None
    def read_log():
        for line in stream.stdout:
            if line.startswith('{'):
                try:
                    incoming.put_nowait((time.monotonic()-started, json.loads(line)))
                except (ValueError, queue.Full):
                    incoming.put((time.monotonic()-started, {'schema': 'invalid'}))
    reader = threading.Thread(target=read_log, daemon=True)
    reader.start()
    # TimeStats may not have any layer samples immediately after clearing.
    # Give the real surface its first five-second collection interval.
    last_device = 0.0
    last_progress = -60.0
    layer = None
    armed_at = None
    last_app_at = None
    def emit(record):
        records.append(record)
        handle.write(json.dumps(record, separators=(',', ':'))+'\n')
        handle.flush()
    try:
        handle.write(json.dumps(records[0])+'\n')
        # This only instruments an existing live app. No install, force-stop, recent-ROM launch,
        # power/refresh override, baseline-profile changes, or strict benchmark arm occurs here.
        adb.run('shell', 'am', 'start', '-n', args.package+'/.MainActivity',
                '--ez', 'coffee_gb_benchmark', 'false', '--ez', 'coffee_gb_soak', 'true')
        armed_at = time.monotonic()-started
        adb.run('shell', 'dumpsys', 'SurfaceFlinger', '--timestats', '-enable')
        adb.run('shell', 'dumpsys', 'SurfaceFlinger', '--timestats', '-clear')
        while time.monotonic()-started < args.maximum_seconds:
            now = time.monotonic()-started
            while not incoming.empty():
                at, sample = incoming.get_nowait()
                if at < armed_at:
                    continue
                checked = validate_app(sample)
                if checked['schema'] != APP_SCHEMA:
                    raise EvidenceError('live collector requires asynchronous app-v2 telemetry')
                emit(dict(kind='app', at=at, sample=checked))
                last_app_at = at
            if last_app_at is None and now > 30:
                raise EvidenceError('no compatible app telemetry within 30 seconds')
            if last_app_at is not None and now-last_app_at > 5:
                raise EvidenceError('app telemetry stopped for more than five seconds')
            if stream.poll() is not None:
                raise EvidenceError('app telemetry stream ended')
            if now-last_device >= 5:
                current_layer = resolve_layer(adb.run('shell', 'dumpsys', 'SurfaceFlinger', '--list'), args.package)
                if layer is not None and layer != current_layer:
                    raise EvidenceError('active presentation surface changed')
                layer = current_layer
                before = time.monotonic()-started
                surface = parse_surface(adb.run('shell', 'dumpsys', 'SurfaceFlinger', '--timestats', '-dump'), layer, uid)
                at = (before+time.monotonic()-started)/2
                thermal = parse_thermal(adb.run('shell', 'dumpsys', 'thermalservice'), adb.run('shell', 'dumpsys', 'battery'))
                refresh = parse_refresh(adb.run('shell', 'dumpsys', 'SurfaceFlinger'))
                emit(dict(kind='device', at=at, surface=surface, thermal=thermal, displayRefreshHz=refresh))
                last_device = now
                verdict = analyze(records)
                if now-last_progress >= 60:
                    print('Soak %.0fs: %s (%s)' % (now, verdict['status'],
                          ', '.join(verdict['reasons']) or 'stable window eligible'), file=sys.stderr, flush=True)
                    last_progress = now
                if verdict['status'] in {'PASS', 'FAIL'}:
                    break
                recoverable = {'app and compositor samples are both required',
                               'minimum 15-minute duration not reached',
                               'final ten-minute window incomplete',
                               'final ten minutes are not thermally stable'}
                if set(verdict['reasons'])-recoverable:
                    break
                if now >= args.maximum_seconds:
                    break
            time.sleep(.2)
    except (EvidenceError, KeyboardInterrupt) as error:
        emit(dict(kind='error', reason=str(error) if isinstance(error, EvidenceError) else 'collection interrupted'))
    finally:
        stream.terminate()
        try:
            stream.wait(timeout=3)
        except subprocess.TimeoutExpired:
            stream.kill()
        try:
            adb.run('shell', 'am', 'start', '-n', args.package+'/.MainActivity',
                    '--ez', 'coffee_gb_benchmark', 'false', '--ez', 'coffee_gb_soak', 'false')
            adb.run('shell', 'dumpsys', 'SurfaceFlinger', '--timestats', '-disable')
        except EvidenceError:
            emit(dict(kind='error', reason='could not stop device recording cleanly'))
        verdict = analyze(records)
        handle.write(json.dumps(verdict)+'\n')
        handle.close()
    return verdict


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    run = commands.add_parser('collect', help='arm an already running visible game and collect a bounded soak')
    run.add_argument('--serial')
    run.add_argument('--package', default='eu.rekawek.coffeegb.android')
    run.add_argument('--apk', required=True)
    run.add_argument('--output', required=True)
    run.add_argument('--power', choices=['battery', 'plugged'], default='battery')
    run.add_argument('--minimum-seconds', type=int, default=MIN_SECONDS)
    run.add_argument('--maximum-seconds', type=int, default=MAX_SECONDS)
    check = commands.add_parser('analyze')
    check.add_argument('input')
    args = parser.parse_args()
    try:
        if args.command == 'collect':
            verdict = collect(args)
        else:
            with open(args.input, encoding='utf-8') as handle:
                verdict = analyze([json.loads(line) for line in handle if line.strip()])
    except (OSError, ValueError) as error:
        verdict = result('INCONCLUSIVE', [str(error) if isinstance(error, EvidenceError) else 'unable to read evidence or artifact'])
    print(json.dumps(verdict, indent=2))
    return {'PASS': 0, 'FAIL': 1, 'INCONCLUSIVE': 2}[verdict['status']]


if __name__ == '__main__':
    sys.exit(main())
