"""Strict same-job guards and Node24 archive mechanics; fixture APKs are unsigned."""
import argparse
import copy
import json
import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import apk_benchmark as benchmark
import apk_benchmark_artifacts as artifacts
import apk_benchmark_compression as observer
import test_apk_benchmark as controller_fixtures
import test_apk_benchmark_artifacts as artifact_fixtures

CONTROLLER = 'f'*40  # Fixture identity only, never an approved hosted controller.
CONTEXT = {**controller_fixtures.ControllerTests.context, 'GITHUB_WORKFLOW_SHA': CONTROLLER,
           'GITHUB_RUN_ID': '12345', 'GITHUB_RUN_ATTEMPT': '1', 'ImageVersion': '20260927.320.1',
           'BENCH_PROFILE': 'C0', 'BENCH_CACHE': 'task-cache-off', 'BENCH_OBSERVE_COMPRESSION': 'true',
           'BENCH_SOURCE_SHA': benchmark.SEALED_SOURCE_SHA,
           'BENCH_SEED_RUN': benchmark.APPROVED_PARENT_SEED[0],
           'BENCH_SEED_ARTIFACT': benchmark.APPROVED_PARENT_SEED[1],
           'BENCH_SEED_DIGEST': benchmark.APPROVED_PARENT_SEED[2]}
HAS_ARCHIVE = bool(os.environ.get('APK_BENCHMARK_UPLOAD_BUNDLE') and os.environ.get('APK_BENCHMARK_NODE'))


def runtime_fixture(root):
    """Run the actual probe on the local fixture runtime; no hosted proof."""
    node = Path(os.environ['APK_BENCHMARK_NODE']).resolve(strict=True)
    output = root/'github-output'
    output.touch()
    result = subprocess.run([str(node), str(Path(observer.__file__).parents[1]/'.github/actions/apk-compression-runtime/index.cjs')],
                            env={**os.environ, **CONTEXT, 'RUNNER_TEMP': str(root), 'GITHUB_OUTPUT': str(output)},
                            capture_output=True, text=True, check=True)
    receipt = root/'apk-compression-node-runtime.json'
    with patch.dict(os.environ, CONTEXT):
        runtime = observer.runtime_input(receipt, node)
    assert result.stdout == 'Recorded actual selected Node24 action runtime.\n'
    assert output.read_text() == f'node={node}\nreceipt={receipt}\n'
    return node, receipt, runtime


class ObserverGuards(unittest.TestCase):
    def setUp(self):
        # SDK tools and signatures are explicitly mocked throughout this class.
        self.fixture = artifact_fixtures.ArtifactTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.source = self.fixture.source.resolve()
        self.apksigner, self.aapt = '/sdk/build-tools/36.0.0/apksigner', '/sdk/build-tools/36.0.0/aapt'
        for name, value in [('APKSIGNER', self.apksigner), ('AAPT', self.aapt)]:
            tool = patch.object(artifact_fixtures, name, Path(value))
            tool.start()
            self.addCleanup(tool.stop)
        environment = patch.dict(os.environ, CONTEXT)
        environment.start()
        self.addCleanup(environment.stop)
        self.report = {'schema_version': 1, 'qualified': True, 'returncode': 0, 'source_sha': benchmark.SEALED_SOURCE_SHA,
                       'controller_sha': CONTROLLER, 'fixture_sha256': benchmark.FIXTURE_SHA256,
                       'profile': 'C0', 'target': 'combined', 'cache_condition': 'task-cache-off',
                       'seed_run_id': CONTEXT['BENCH_SEED_RUN'], 'seed_artifact_id': CONTEXT['BENCH_SEED_ARTIFACT'],
                       'seed_manifest_sha256': CONTEXT['BENCH_SEED_DIGEST'],
                       'toolchain': copy.deepcopy(controller_fixtures.ControllerTests.observed_toolchain),
                       'artifacts': self.inventory()}
        self.report_path, self.seed_path = self.source.parent/'report.json', self.source.parent/'seed-receipt.json'
        self.seed = self.seed_receipt()

    def inventory(self):
        return artifacts.inventory(self.source, list(artifacts.MODULES), '12'*32, Path(self.apksigner), Path(self.aapt))

    def seed_receipt(self):
        args = argparse.Namespace(seed_run=os.environ['BENCH_SEED_RUN'], seed_artifact=os.environ['BENCH_SEED_ARTIFACT'],
                                  seed_manifest_sha256=os.environ['BENCH_SEED_DIGEST'])
        return {'manifest_sha256': args.seed_manifest_sha256, 'provenance': benchmark.seed_provenance(args),
                'toolchain': copy.deepcopy(controller_fixtures.ControllerTests.observed_toolchain), 'restore_seconds': 1}

    def prepare(self, report=None, seed=None, git=None):
        self.report_path.write_text(json.dumps(self.report if report is None else report))
        self.seed_path.write_text(json.dumps(self.seed if seed is None else seed))
        with patch.object(observer, 'controller_identity', return_value=(CONTROLLER, {'fixture_seal': 'a'*64})), \
                patch.object(observer.subprocess, 'check_output', side_effect=git or [benchmark.SEALED_SOURCE_SHA+'\n', '']), \
                patch.object(benchmark, 'verify_google_services'):
            return observer.qualified_inputs([self.source], [self.report_path], self.apksigner, self.aapt, self.seed_path)

    def test_complete_inventory_twelve_entries_and_receipt_hashes(self):
        paths, provenance, expected = self.prepare()
        self.assertEqual(len(paths), 8)
        self.assertEqual(set(paths), set(expected))
        for field, path in [('report_sha256', self.report_path), ('seed_restore_receipt_sha256', self.seed_path)]:
            self.assertEqual(provenance[0][field], observer.digest(path))
        self.assertEqual(provenance[0]['toolchain_observation']['differing_guard_fields'], [])
        root = self.source.parent/'staging'
        root.mkdir()
        self.assertEqual(len(observer.stage_release(paths, root)), 12)
        for module, prefix in observer.PREFIXES.items():
            for alias in ['release', 'debug']:
                self.assertEqual(observer.digest(root/module/f'{prefix}-latest-universal-{alias}.apk'),
                                 observer.digest(paths[module+'/universal']))

    def test_future_same_controller_tuple_preserves_exact_historic_exception(self):
        self.assertEqual(self.seed['provenance']['controller_sha'], benchmark.APPROVED_PARENT_SEED_CONTROLLER)
        with patch.dict(os.environ, {'BENCH_SEED_RUN': '99999', 'BENCH_SEED_ARTIFACT': '88888', 'BENCH_SEED_DIGEST': 'e'*64}):
            seed = self.seed_receipt()
            self.assertEqual(seed['provenance']['controller_sha'], CONTROLLER)
            report = {**self.report, 'seed_run_id': '99999', 'seed_artifact_id': '88888', 'seed_manifest_sha256': 'e'*64}
            self.prepare(report, seed)
            seed['provenance']['controller_sha'] = benchmark.APPROVED_PARENT_SEED_CONTROLLER
            with self.assertRaisesRegex(ValueError, 'restore receipt mismatch'):
                self.prepare(report, seed)

    def test_provenance_schema_and_complete_payload_required(self):
        cases = [('controller_sha', 'a'*40), ('source_sha', 'b'*40), ('fixture_sha256', 'c'*64),
                 ('seed_artifact_id', '1'), ('seed_run_id', '2'), ('seed_manifest_sha256', 'd'*64), ('qualified', False),
                 ('profile', 'M'), ('target', 'phone'), ('returncode', True), ('returncode', 1), ('schema_version', True),
                 ('cache_condition', 'fresh-release-warm'), ('artifacts', [])]
        for field, value in cases:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.prepare({**self.report, field: value})
        missing = copy.deepcopy(self.report)
        del missing['artifacts']['modules']['androidTvApp']
        with self.assertRaises(ValueError):
            self.prepare(missing)
        self.report_path.write_text('[]')
        with self.assertRaises(ValueError):
            observer.read_object(self.report_path, 65536)

    def test_seed_receipt_and_dispatch_context_guards(self):
        for field, value in [('manifest_sha256', 'd'*64), ('provenance', {}), ('toolchain', None)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.prepare(seed={**self.seed, field: value})
        for field, value in [('BENCH_OBSERVE_COMPRESSION', 'false'), ('BENCH_PROFILE', 'M'), ('BENCH_SOURCE_SHA', 'b'*40),
                             ('GITHUB_EVENT_NAME', 'push'), ('GITHUB_REF', 'refs/heads/main'), ('GITHUB_REPOSITORY', 'elsewhere/repo'),
                             ('BENCH_SEED_RUN', 'invalid'), ('BENCH_CACHE', 'unexpected')]:
            with self.subTest(field=field), patch.dict(os.environ, {field: value}), self.assertRaises(ValueError):
                self.prepare()

    def test_four_toolchain_guards_preserved_and_other_differences_retained(self):
        for key, value in [('java', 'openjdk version "22.0.1"'), ('gradle', '8.13'), ('agp', '8.11.0'), ('runner_image', '20261004.327.1')]:
            seed = copy.deepcopy(self.seed)
            seed['toolchain'][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'toolchain guards mismatch'):
                self.prepare(seed=seed)
        seed = copy.deepcopy(self.seed)
        seed['toolchain']['cpu_count'] = 8
        _, provenance, _ = self.prepare(seed=seed)
        self.assertEqual(provenance[0]['toolchain_observation']['differing_fields'], ['cpu_count'])
        self.assertEqual(provenance[0]['toolchain_observation']['differing_guard_fields'], [])
        report = copy.deepcopy(self.report)
        report['toolchain']['credential'] = 'PRIVATE_VALUE'
        with patch('builtins.print') as output, self.assertRaises(ValueError):
            self.prepare(report)
        output.assert_not_called()

    def test_source_and_generated_fcm_guards_rechecked(self):
        for git in [['a'*40+'\n', ''], [benchmark.SEALED_SOURCE_SHA+'\n', ' M build.gradle.kts\n']]:
            with self.subTest(git=git), self.assertRaisesRegex(ValueError, 'Sealed application source'):
                self.prepare(git=git)
        with patch.object(benchmark, 'verify_google_services', side_effect=ValueError('Fixture FCM mismatch')):
            self.report_path.write_text(json.dumps(self.report))
            self.seed_path.write_text(json.dumps(self.seed))
            with patch.object(observer, 'controller_identity', return_value=(CONTROLLER, {})), \
                    patch.object(observer.subprocess, 'check_output', side_effect=[benchmark.SEALED_SOURCE_SHA+'\n', '']), \
                    self.assertRaisesRegex(ValueError, 'FCM mismatch'):
                observer.qualified_inputs([self.source], [self.report_path], self.apksigner, self.aapt, self.seed_path)

    def test_signature_r8_mapping_and_native_guards_rechecked(self):
        self.fixture.tool_error = 2
        with self.assertRaises(ValueError):
            self.prepare()
        self.fixture.tool_error = None
        mapping = self.source/'androidTvApp/build/outputs/mapping/release/mapping.txt'
        original = mapping.read_bytes()
        mapping.write_bytes(original+b'changed R8 mapping\n')
        with self.assertRaisesRegex(ValueError, 'complete payload inventory changed'):
            self.prepare()
        mapping.write_bytes(original)
        self.fixture.change_zip(self.fixture.apk(source=self.source), updates={'lib/arm64-v8a/unreviewed.so': b'unknown'})
        with self.assertRaises(ValueError):
            self.prepare()

    def test_actual_profile_absence_allowed_and_presence_drift_rejects(self):
        for module in artifacts.MODULES:
            for abi in artifacts.OUTPUTS:
                apk = self.source/module/'build/outputs/apk/release'/f'{module}-{abi}-release.apk'
                self.fixture.change_zip(apk, removals=['assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'])
        self.report['artifacts'] = self.inventory()
        self.prepare()
        self.fixture.change_zip(self.fixture.apk(source=self.source), updates={'assets/dexopt/baseline.prof': b'new profile'})
        with self.assertRaisesRegex(ValueError, 'complete payload inventory changed'):
            self.prepare()

    def test_mutation_symlink_sdk_and_production_environment_reject(self):
        paths, _, _ = self.prepare()
        before = observer.snapshot(paths)
        target = paths['androidApp/arm64-v8a']
        target.write_bytes(target.read_bytes()+b'changed')
        with self.assertRaisesRegex(ValueError, 'inputs changed'):
            observer.unchanged(paths, before)
        linked = self.source.parent/'linked.apk'
        linked.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'ordinary file'):
            observer.snapshot({'linked': linked})
        self.apksigner = '/sdk/build-tools/35.0.0/apksigner'
        with self.assertRaisesRegex(ValueError, 'pinned SDK'):
            self.prepare()
        with patch.dict(os.environ, {'SILO_RELEASE_KEY_ALIAS': 'forbidden-fixture'}), self.assertRaisesRegex(ValueError, 'Production release environment'):
            self.prepare()

    def test_default_disabled(self):
        with patch('builtins.print') as output, patch('sys.argv', ['observer']):
            observer.main()
        self.assertFalse(json.loads(output.call_args.args[0])['enabled'])


class ControllerSeals(unittest.TestCase):
    def test_all_thirteen_committed_file_seals_and_each_changed_hash_rejects(self):
        root = Path(observer.__file__).resolve().parents[1]
        calls = []
        def git(command, **_):
            if command[3:5] == ['rev-parse', 'HEAD']:
                return CONTROLLER+'\n'
            if command[3] == 'status':
                return ''
            if command[3] == 'show':
                name = command[4].split(':', 1)[1]
                calls.append(name)
                return (root/name).read_bytes()
            raise AssertionError(command)
        with patch.dict(os.environ, CONTEXT), patch.object(observer.subprocess, 'check_output', side_effect=git):
            revision, seals = observer.controller_identity()
        self.assertEqual(revision, CONTROLLER)
        selected = list(calls)
        self.assertEqual(len(set(selected)), 13)
        self.assertEqual(set(selected), set(seals))
        for name in selected:
            def mutated(command, **options):
                result = git(command, **options)
                return result+b'changed' if command[3] == 'show' and command[4].endswith(':'+name) else result
            with self.subTest(file=name), patch.dict(os.environ, CONTEXT), \
                    patch.object(observer.subprocess, 'check_output', side_effect=mutated), self.assertRaisesRegex(ValueError, 'file seal mismatch'):
                observer.controller_identity()

    def test_wrong_controller_revision_and_dirty_tree_reject(self):
        for values in [('a'*40+'\n', ''), (CONTROLLER+'\n', ' M scripts/apk_benchmark.py\n')]:
            with self.subTest(values=values), patch.dict(os.environ, CONTEXT), \
                    patch.object(observer.subprocess, 'check_output', side_effect=values), self.assertRaisesRegex(ValueError, 'identity'):
                observer.controller_identity()


@unittest.skipUnless(HAS_ARCHIVE, 'Provide the pinned upload bundle and Node24 binary for integration')
class PinnedArchiveFixtures(unittest.TestCase):
    def inputs(self, root):
        paths = {}
        for module in observer.PREFIXES:
            directory = root/module
            directory.mkdir()
            for index, abi in enumerate(artifacts.OUTPUTS):
                path = directory/f'{module}-{abi}-release.apk'
                path.write_bytes((f'{module}/{abi}\n'.encode()*12000)+bytes(range(256))*100+os.urandom(2048+index))
                paths[module+'/'+abi] = path
        expected = {label: {key: value[key] for key in ('signed_sha256', 'size_bytes')}
                    for label, value in observer.snapshot(paths).items()}
        return paths, expected

    def test_all_levels_preserve_bytes_aliases_and_actual_selected_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            node, _, runtime = runtime_fixture(root)
            paths, expected = self.inputs(root)
            before = observer.snapshot(paths)
            receipt = observer.observe(paths, [{'fixture_tests_only': True}], expected,
                                       Path(os.environ['APK_BENCHMARK_UPLOAD_BUNDLE']), node, runtime)
            observer.unchanged(paths, before)
            self.assertEqual([row['compression_level'] for row in receipt['totals']], [0, 1, 6])
            self.assertEqual(len(receipt['archives']), 6)
            self.assertFalse(receipt['primary_whole_job_timing_eligible'])
            self.assertLess(receipt['totals'][2]['archive_bytes'], receipt['totals'][0]['archive_bytes'])
            for row in receipt['archives']:
                self.assertGreater(row['archive_bytes'], 0)
                self.assertGreater(row['wall_seconds'], 0)
                self.assertGreaterEqual(row['cpu_user_seconds']+row['cpu_system_seconds'], 0)
                for key in ['node', 'zlib', 'platform', 'arch']:
                    self.assertEqual(row[key], runtime[key])
            if os.environ.get('APK_BENCHMARK_FIXTURE_RECEIPT'):
                receipt.update(fixture_tests_only=True, actual_signed_apk_qualification=False, qualified_input_verified=False)
                Path(os.environ['APK_BENCHMARK_FIXTURE_RECEIPT']).write_text(json.dumps(receipt, indent=2, sort_keys=True)+'\n')

    def test_runtime_receipt_mutations_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            node, receipt, runtime = runtime_fixture(root)
            cases = [('node', 'v20.19.0'), ('node', None), ('controller_sha', 'a'*40), ('run_id', '2'), ('run_attempt', '2'),
                     ('runner_image', '20261004.327.1'), ('executable_sha256', 'a'*64), ('node_executable', str(root/'elsewhere')),
                     ('schema_version', True), ('zlib', 'PRIVATE\nVALUE'), ('platform', 'PRIVATE\nVALUE'), ('arch', 'PRIVATE\nVALUE')]
            for key, value in cases:
                receipt.write_text(json.dumps({**runtime, key: value}))
                with self.subTest(key=key), patch.dict(os.environ, CONTEXT), self.assertRaises(ValueError):
                    observer.runtime_input(receipt, node)
            for value in [[], None, {**runtime, 'extra': 'unexpected'}]:
                receipt.write_text(json.dumps(value))
                with self.subTest(type=type(value)), patch.dict(os.environ, CONTEXT), self.assertRaises(ValueError):
                    observer.runtime_input(receipt, node)
            receipt.write_text(json.dumps(runtime))
            with patch.dict(os.environ, {**CONTEXT, 'GITHUB_RUN_ATTEMPT': '2'}), self.assertRaises(ValueError):
                observer.runtime_input(receipt, node)
            linked = root/'node-link'
            linked.symlink_to(node)
            with patch.dict(os.environ, CONTEXT), self.assertRaisesRegex(ValueError, 'ordinary file'):
                observer.runtime_input(receipt, linked)

    def test_actual_runtime_probe_rejects_bad_context_and_existing_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            node = Path(os.environ['APK_BENCHMARK_NODE']).resolve()
            output = root/'github-output'
            action = Path(observer.__file__).parents[1]/'.github/actions/apk-compression-runtime/index.cjs'
            for key, value in [('BENCH_PROFILE', 'M'), ('BENCH_OBSERVE_COMPRESSION', 'false'), ('GITHUB_WORKFLOW_SHA', 'invalid'),
                               ('GITHUB_EVENT_NAME', 'push'), ('GITHUB_RUN_ATTEMPT', '2'), ('ImageVersion', 'unsafe\nvalue')]:
                output.write_text('')
                result = subprocess.run([str(node), str(action)], capture_output=True, text=True,
                                        env={**os.environ, **CONTEXT, key: value, 'RUNNER_TEMP': str(root), 'GITHUB_OUTPUT': str(output)})
                with self.subTest(key=key):
                    self.assertEqual(result.returncode, 1)
                    self.assertEqual(result.stderr, 'APK action runtime observation rejected\n')
                    self.assertEqual(output.read_text(), '')
                    self.assertFalse((root/'apk-compression-node-runtime.json').exists())
            runtime_fixture(root)
            original = (root/'apk-compression-node-runtime.json').read_bytes()
            result = subprocess.run([str(node), str(action)], capture_output=True, text=True,
                                    env={**os.environ, **CONTEXT, 'RUNNER_TEMP': str(root), 'GITHUB_OUTPUT': str(output)})
            self.assertEqual(result.returncode, 1)
            self.assertEqual((root/'apk-compression-node-runtime.json').read_bytes(), original)

    def test_bundle_and_changed_qualified_bytes_reject_before_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            node, _, runtime = runtime_fixture(root)
            bundle = root/'bundle.js'
            bundle.write_bytes(Path(os.environ['APK_BENCHMARK_UPLOAD_BUNDLE']).read_bytes()+b'\n')
            with self.assertRaisesRegex(ValueError, 'bundle digest mismatch'):
                observer.observe({}, [], {}, bundle, node, runtime)
            path = root/'fixture.apk'
            path.write_bytes(b'qualified fixture')
            expected = {'fixture': {'signed_sha256': observer.digest(path), 'size_bytes': path.stat().st_size}}
            path.write_bytes(b'changed after qualification')
            with self.assertRaisesRegex(ValueError, 'changed after input qualification'):
                observer.observe({'fixture': path}, [], expected, Path(os.environ['APK_BENCHMARK_UPLOAD_BUNDLE']), node, runtime)

    def test_child_runtime_drift_and_changed_executable_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            node, _, runtime = runtime_fixture(root)
            paths, expected = self.inputs(root)
            with self.assertRaisesRegex(ValueError, 'child runtime differs'):
                observer.observe(paths, [], expected, Path(os.environ['APK_BENCHMARK_UPLOAD_BUNDLE']), node, {**runtime, 'zlib': '9.9.9'})
            copied = root/'node-copy'
            copied.write_bytes(node.read_bytes()+b'changed')
            with self.assertRaisesRegex(ValueError, 'runtime executable changed'):
                observer.observe(paths, [], expected, Path(os.environ['APK_BENCHMARK_UPLOAD_BUNDLE']), copied,
                                 {**runtime, 'node_executable': str(copied)})


class ArchiveRejections(unittest.TestCase):
    def test_corrupted_archive_rejects(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/'corrupt.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                for index in range(6):
                    archive.writestr(f'{index}.apk', b'altered')
            expected = {f'{index}.apk': (8, 'a'*64) for index in range(6)}
            with self.assertRaisesRegex(ValueError, 'changed signed APK bytes'):
                observer.verify_archive(path, expected)

    def test_nonobject_and_oversized_receipts_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/'receipt.json'
            for value in ['[]', 'null', ' '*8193]:
                path.write_text(value)
                with self.subTest(value=value[:4]), self.assertRaises(ValueError):
                    observer.read_object(path, 8192)


if __name__ == '__main__':
    unittest.main()
