#!/usr/bin/env python3
"""Explicit local observer of the pinned release archive; no build or upload."""
import argparse
import contextlib
import hashlib
import io
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

import apk_benchmark as benchmark
import apk_benchmark_artifacts as artifacts

ACTION_SHA = '330a01c490aca151604b8cf639adc76d48f6c5d4'
BUNDLE_SHA256 = '168c44946cba03564808c19b83a72601e6d1d6f57081dbd2d6e2ea1cedc522f5'
LEVELS = (0, 1, 6)
PREFIXES = {'androidApp': 'silo-android', 'androidTvApp': 'silo-android-tv'}


def digest(path):
    return artifacts._file_digest(Path(path))


def ordinary(path):
    path = Path(path).absolute()
    if any(item.is_symlink() for item in (path, *path.parents)) or not path.is_file():
        raise ValueError('Observer input must be an ordinary file without symlink ancestors')
    return path


def read_object(path, limit):
    path = ordinary(path)
    if path.stat().st_size > limit:
        raise ValueError('Observer JSON receipt exceeds its bounded size')
    data = path.read_bytes()
    if len(data) > limit:
        raise ValueError('Observer JSON receipt exceeds its bounded size')
    result = json.loads(data)
    if not isinstance(result, dict):
        raise ValueError('Observer JSON receipt must be an object')
    return result, data


def qualified_toolchains(current, seed):
    # Reuse the accepted safe dictionary validator without another log emission.
    if not isinstance(current, dict) or not isinstance(seed, dict):
        raise ValueError('Observer requires complete seed and current toolchains')
    with contextlib.redirect_stdout(io.StringIO()) as output:
        benchmark.emit_toolchain_observation(current, seed)
    observation = json.loads(output.getvalue())['toolchain_observation']
    if observation['differing_guard_fields']:
        raise ValueError('Observer seed and build toolchain guards mismatch')
    return observation


def snapshot(paths):
    result = {}
    for label, path in paths.items():
        ordinary(path)
        state = path.stat()
        result[label] = {'signed_sha256': digest(path), 'size_bytes': state.st_size,
                         'device': state.st_dev, 'inode': state.st_ino,
                         'mtime_ns': state.st_mtime_ns, 'ctime_ns': state.st_ctime_ns}
    return result


def unchanged(paths, expected):
    if snapshot(paths) != expected:
        raise ValueError('Signed APK inputs changed during archive observation')


def reject_production_environment():
    if any(key.startswith(('SILO_RELEASE_', 'SILO_GOOGLE_SERVICES_')) for key in os.environ):
        raise ValueError('Production release environment must not be present')


def controller_identity():
    """Bind this optional same-job observer to its actual sealed controller."""
    revision = os.environ.get('GITHUB_WORKFLOW_SHA', '')
    controller = Path(__file__).resolve().parents[1]
    if (not re.fullmatch(r'[0-9a-f]{40}', revision)
            or subprocess.check_output(['git', '-C', str(controller), 'rev-parse', 'HEAD'], text=True).strip() != revision
            or subprocess.check_output(['git', '-C', str(controller), 'status', '--porcelain', '--untracked-files=no'], text=True).strip()):
        raise ValueError('Observer controller identity or tracked files mismatch')
    names = ['.github/workflows/android-build.yml', '.github/actions/apk-compression-runtime/action.yml',
             '.github/actions/apk-compression-runtime/index.cjs', 'docs/ci/apk-compression-observer.md']
    names += ['scripts/' + name for name in ('apk_benchmark.py', 'apk_benchmark_seed.py', 'apk_benchmark_artifacts.py',
              'test_apk_benchmark.py', 'test_apk_benchmark_seed.py', 'test_apk_benchmark_artifacts.py',
              'apk_benchmark_compression.py', 'apk_benchmark_archive.cjs', 'test_apk_benchmark_compression.py')]
    seals = {}
    for name in names:
        committed = subprocess.check_output(['git', '-C', str(controller), 'show', revision + ':' + name])
        seals[name] = hashlib.sha256(committed).hexdigest()
        if digest(controller / name) != seals[name]:
            raise ValueError('Observer committed file seal mismatch')
    return revision, seals


def qualified_inputs(sources, reports, apksigner, aapt, seed_receipt):
    """Reverify the complete strict inventory against qualified local reports."""
    reject_production_environment()
    if os.environ.get('BENCH_OBSERVE_COMPRESSION') != 'true' or os.environ.get('BENCH_PROFILE') != 'C0':
        raise ValueError('Observer requires the separately enabled C0 dispatch')
    seed = {key: os.environ.get(variable, '') for key, variable in (
        ('seed_run_id', 'BENCH_SEED_RUN'), ('seed_artifact_id', 'BENCH_SEED_ARTIFACT'),
        ('seed_manifest_sha256', 'BENCH_SEED_DIGEST'))}
    benchmark.validate(os.environ.get('BENCH_SOURCE_SHA'), 'C0', os.environ.get('BENCH_CACHE'), 'combined',
                       seed['seed_run_id'], seed['seed_artifact_id'], seed['seed_manifest_sha256'])
    controller_revision, controller_seals = controller_identity()
    prior, seed_receipt_bytes = read_object(seed_receipt, 65536)
    args = argparse.Namespace(seed_run=seed['seed_run_id'], seed_artifact=seed['seed_artifact_id'],
                              seed_manifest_sha256=seed['seed_manifest_sha256'])
    if (prior.get('manifest_sha256') != seed['seed_manifest_sha256']
            or prior.get('provenance') != benchmark.seed_provenance(args)):
        raise ValueError('Same-job approved seed restore receipt mismatch')
    for name, tool in (('apksigner', apksigner), ('aapt', aapt)):
        if Path(tool).name != name or Path(tool).parent.name != '36.0.0':
            raise ValueError('Observer requires the pinned SDK build-tools 36.0.0 verifier paths')
    if len(sources) != len(reports) or len(reports) != 1:
        raise ValueError('Expected the same-job combined C0 source/report pair')
    records, paths, provenance, expected_inputs = [], {}, [], {}
    for source, report_path in zip(sources, reports):
        source = Path(source).resolve(strict=True)
        report, report_bytes = read_object(report_path, 64*1024*1024)
        if (type(report.get('schema_version')) is not int or report['schema_version'] != 1 or report.get('qualified') is not True
                or type(report.get('returncode')) is not int or report['returncode'] != 0
                or report.get('source_sha') != benchmark.SEALED_SOURCE_SHA
                or report.get('controller_sha') != controller_revision
                or report.get('fixture_sha256') != benchmark.FIXTURE_SHA256
                or report.get('profile') != 'C0' or report.get('target') != 'combined'
                or report.get('cache_condition') != os.environ['BENCH_CACHE']
                or any(report.get(key) != value for key, value in seed.items())):
            raise ValueError('Qualified fixture source or seed provenance mismatch')
        observation = qualified_toolchains(report.get('toolchain'), prior.get('toolchain'))
        target = report.get('target')
        if target != 'combined':
            raise ValueError('Qualified report layout mismatch')
        source_revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
        dirty = subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--untracked-files=no'], text=True).strip()
        if source_revision != benchmark.SEALED_SOURCE_SHA or dirty:
            raise ValueError('Sealed application source must have no tracked changes')
        modules = benchmark.MODULES[target]
        if 'androidApp' in modules:
            benchmark.verify_google_services(source)
        declared = report.get('artifacts', {})
        if not isinstance(declared, dict):
            raise ValueError('Qualified payload inventory must be an object')
        actual = artifacts.inventory(source, modules, declared.get('certificate_sha256'), Path(apksigner), Path(aapt))
        # Compare every signed container hash and every collected payload field.
        # compare_profiles below additionally rejects incomplete/invalid reports.
        if actual != declared:
            raise ValueError('Signed APK bytes or complete payload inventory changed after qualification')
        records.append(actual)
        for module in modules:
            for abi, apk in actual['modules'][module]['apks'].items():
                label = module + '/' + abi
                if label in paths:
                    raise ValueError('Duplicate module in qualified reports')
                paths[label] = source / module / 'build/outputs/apk/release' / apk['output_file']
                expected_inputs[label] = {key: apk[key] for key in ('signed_sha256', 'size_bytes')}
        provenance.append({'report_sha256': hashlib.sha256(report_bytes).hexdigest(),
                          'seed_restore_receipt_sha256': hashlib.sha256(seed_receipt_bytes).hexdigest(), 'controller_file_sha256': controller_seals,
                          'toolchain': report['toolchain'], 'toolchain_observation': observation,
                          **{key: report[key] for key in ('source_sha', 'controller_sha', 'profile', 'target',
                          'cache_condition', 'fixture_sha256', 'seed_run_id', 'seed_artifact_id', 'seed_manifest_sha256')}})
    artifacts.compare_profiles(records, records)
    if len(paths) != 8 or len({record['profile'] for record in provenance}) != 1:
        raise ValueError('Observer requires one complete qualified layout of eight APKs')
    if len({record['cache_condition'] for record in provenance}) != 1:
        raise ValueError('Observer requires one cache condition')
    return paths, provenance, expected_inputs


def runtime_input(receipt, node):
    """Use the actual local Node24 action's binary, version and zlib receipt."""
    runtime, _ = read_object(receipt, 8192)
    expected = {'schema_version', 'controller_sha', 'run_id', 'run_attempt', 'runner_image',
                'node_executable', 'executable_sha256', 'node', 'zlib', 'platform', 'arch'}
    patterns = {'controller_sha': r'[0-9a-f]{40}', 'run_id': r'[1-9][0-9]{0,19}',
                'runner_image': r'[0-9]{8}\.[0-9]+\.[0-9]+', 'executable_sha256': r'[0-9a-f]{64}',
                'node': r'v24\.[0-9]+\.[0-9]+', 'zlib': r'[0-9]+\.[0-9]+\.[0-9]+(?:[-.+][0-9A-Za-z_.+\-]+)?'}
    if (set(runtime) != expected or type(runtime['schema_version']) is not int or runtime['schema_version'] != 1
            or any(not isinstance(runtime.get(key), str) or len(runtime[key]) > 80
                   or not re.fullmatch(pattern, runtime[key]) for key, pattern in patterns.items())
            or runtime.get('platform') not in ('linux', 'darwin', 'win32') or runtime.get('arch') not in ('x64', 'arm64')
            or not isinstance(runtime.get('node_executable'), str) or len(runtime['node_executable']) > 4096
            or re.search(r'[\r\n\0]', runtime['node_executable'])
            or runtime.get('controller_sha') != os.environ.get('GITHUB_WORKFLOW_SHA')
            or runtime.get('run_id') != os.environ.get('GITHUB_RUN_ID') or runtime.get('run_attempt') != '1'
            or os.environ.get('GITHUB_RUN_ATTEMPT') != '1'
            or runtime.get('runner_image') != os.environ.get('ImageVersion')
            or str(ordinary(node).resolve()) != runtime.get('node_executable')
            or digest(node) != runtime.get('executable_sha256')):
        raise ValueError('Actual selected action runtime receipt mismatch')
    return runtime


def unchanged_runtime(node, runtime):
    if str(ordinary(node).resolve()) != runtime['node_executable'] or digest(node) != runtime['executable_sha256']:
        raise ValueError('Observed action runtime executable changed')


def stage_release(paths, root):
    """Reproduce release.yml's four outputs plus two universal copies per module."""
    staged = {}
    for module, prefix in PREFIXES.items():
        directory = root / module
        directory.mkdir()
        for abi in artifacts.OUTPUTS:
            source = paths[module + '/' + abi]
            if source.name != f'{module}-{abi}-release.apk':
                raise ValueError('APK filename does not match the release collection contract')
            names = [f'{prefix}-0.0.1-{abi}-release.apk']
            if abi == 'universal':
                names += [f'{prefix}-latest-universal-release.apk', f'{prefix}-latest-universal-debug.apk']
            for name in names:
                destination = directory / name
                shutil.copyfile(source, destination)
                if digest(destination) != digest(source):
                    raise ValueError('Release staging did not preserve signed APK bytes')
                staged[module + '/' + name] = destination
    return staged


def verify_archive(path, expected):
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            if len(infos) != 6 or {info.filename for info in infos} != set(expected):
                raise ValueError('Archive paths differ from the release consumer contract')
            for info in infos:
                if info.is_dir() or info.flag_bits & 1:
                    raise ValueError('Unsupported release archive entry')
                hasher = hashlib.sha256()
                size = 0
                with archive.open(info) as stream:
                    while chunk := stream.read(1024 * 1024):
                        hasher.update(chunk)
                        size += len(chunk)
                if (size, hasher.hexdigest()) != expected[info.filename]:
                    raise ValueError('Archive entry changed signed APK bytes')
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        raise ValueError('Release archive verification failed') from error


def observe(paths, provenance, expected_inputs, bundle, node, runtime):
    ordinary(bundle)
    if digest(bundle) != BUNDLE_SHA256:
        raise ValueError('Pinned upload action bundle digest mismatch')
    unchanged_runtime(node, runtime)
    inputs = snapshot(paths)
    if ({label: {key: value[key] for key in ('signed_sha256', 'size_bytes')}
         for label, value in inputs.items()} != expected_inputs):
        raise ValueError('Signed APK bytes changed after input qualification')
    rows = []
    with tempfile.TemporaryDirectory(prefix='silo-apk-compression-') as temporary:
        root = Path(temporary).resolve()
        staged = stage_release(paths, root)
        staged_snapshot = snapshot(staged)
        for level in LEVELS:
            for module in PREFIXES:
                unchanged_runtime(node, runtime)
                unchanged(paths, inputs)
                unchanged(staged, staged_snapshot)
                archive = root / f'{module}-{level}.zip'
                result = subprocess.run([str(node), str(Path(__file__).with_name('apk_benchmark_archive.cjs')),
                                         str(bundle), str(root/module), str(archive), str(level)],
                                        capture_output=True, text=True, timeout=600, check=False,
                                        env={'PATH': os.defpath, 'LANG': 'C.UTF-8'})
                if result.returncode:
                    raise ValueError('Pinned local archive phase failed')
                if len(result.stdout) > 65536:
                    raise ValueError('Archive phase receipt exceeds its bounded size')
                row = json.loads(result.stdout)
                expected = {path.name: (staged_snapshot[label]['size_bytes'], staged_snapshot[label]['signed_sha256'])
                            for label, path in staged.items() if label.startswith(module + '/')}
                verify_archive(archive, expected)
                fields = {'action_sha', 'bundle_sha256', 'compression_level', 'archive_bytes', 'archive_sha256',
                          'wall_seconds', 'cpu_user_seconds', 'cpu_system_seconds', 'input_entries',
                          'node', 'zlib', 'platform', 'arch'}
                if (not isinstance(row, dict) or set(row) != fields
                        or row.get('action_sha') != ACTION_SHA or row.get('bundle_sha256') != BUNDLE_SHA256
                        or type(row.get('compression_level')) is not int or row['compression_level'] != level
                        or type(row.get('archive_bytes')) is not int or row['archive_bytes'] != archive.stat().st_size
                        or row.get('archive_sha256') != digest(archive)
                        or not isinstance(row.get('input_entries'), list) or len(row['input_entries']) != 6
                        or any(not isinstance(entry, str) for entry in row['input_entries'])
                        or set(row['input_entries']) != set(expected)
                        or any(type(row.get(key)) not in (float, int) or not math.isfinite(row[key])
                               or row[key] < 0 for key in ('wall_seconds', 'cpu_user_seconds', 'cpu_system_seconds'))
                        or row['wall_seconds'] <= 0):
                    raise ValueError('Archive phase receipt mismatch')
                if any(row.get(key) != runtime[key] for key in ('node', 'zlib', 'platform', 'arch')):
                    raise ValueError('Archive child runtime differs from actual action observation')
                unchanged(paths, inputs)
                unchanged(staged, staged_snapshot)
                unchanged_runtime(node, runtime)
                rows.append({'module': module, **row})
    unchanged_runtime(node, runtime)
    return {'schema_version': 1, 'enabled': True, 'archive_payload_verified': True, 'apk_count': 8,
            'actual_selected_action_runtime': runtime, 'primary_whole_job_timing_eligible': False,
            'release_archive_entry_count': 12, 'provenance': provenance,
            'scope': 'local archive stream creation and drain only; outside assemble and upload',
            'action_sha': ACTION_SHA, 'bundle_sha256': BUNDLE_SHA256,
            'inputs': {label: {key: value[key] for key in ('signed_sha256', 'size_bytes')} for label, value in inputs.items()},
            'levels_in_order': list(LEVELS), 'archives': rows,
            'totals': [{'compression_level': level,
                        **{key: sum(row[key] for row in rows if row['compression_level'] == level)
                           for key in ('archive_bytes', 'wall_seconds', 'cpu_user_seconds', 'cpu_system_seconds')}}
                       for level in LEVELS]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--enable', action='store_true', help='Explicitly enable the manual local observer')
    for key in ('source', 'report'):
        parser.add_argument('--' + key, action='append', default=[])
    for key in ('upload-bundle', 'node', 'apksigner', 'aapt', 'receipt', 'runtime-receipt', 'seed-receipt'):
        parser.add_argument('--' + key, default='')
    arguments = parser.parse_args()
    if not arguments.enable:
        print(json.dumps({'enabled': False, 'scope': 'manual observer defaults off'}))
        return
    if not all((arguments.source, arguments.report, arguments.upload_bundle, arguments.node,
                arguments.apksigner, arguments.aapt, arguments.receipt, arguments.runtime_receipt, arguments.seed_receipt)):
        raise ValueError('Enabled observer requires qualified sources, tools, pinned bundle and receipt path')
    receipt = Path(arguments.receipt)
    if receipt.exists() or receipt.is_symlink():
        raise ValueError('Observer receipt must be a new local file')
    paths, provenance, expected_inputs = qualified_inputs(arguments.source, arguments.report, arguments.apksigner,
                                                         arguments.aapt, arguments.seed_receipt)
    runtime = runtime_input(arguments.runtime_receipt, arguments.node)
    if runtime['runner_image'] != provenance[0]['toolchain']['runner_image']:
        raise ValueError('Action runtime image differs from same-job qualified build')
    result = observe(paths, provenance, expected_inputs, Path(arguments.upload_bundle), Path(arguments.node), runtime)
    result.update(verified=True, qualified_input_verified=True)
    encoded = json.dumps(result, sort_keys=True)
    if len(encoded) > 262144:
        raise ValueError('Observer text receipt exceeds its bounded size')
    with receipt.open('x') as output:
        output.write(json.dumps(result, sort_keys=True, indent=2) + '\n')
    # The normal job log retains this bounded receipt; no archive/artifact upload.
    print('APK_COMPRESSION_RECEIPT=' + encoded)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError):
        print('APK compression observer rejected')
        raise SystemExit(1)
