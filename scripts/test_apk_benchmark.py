import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import apk_benchmark as bench


class ControllerTests(unittest.TestCase):
    context = {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REPOSITORY': bench.REPOSITORY,
               'GITHUB_REF': 'refs/heads/' + bench.BENCHMARK_BRANCH}

    def inputs(self, profile='M', target='phone', cache='task-cache-off', **changes):
        values = dict(source_sha='a'*40, profile=profile, cache=cache, target=target,
                      seed_run='123', seed_artifact='456', digest='b'*64, context=self.context)
        if profile == 'SEED':
            values.update(seed_run='', seed_artifact='', digest='')
        values.update(changes)
        return values

    def test_unsealed_constant_blocks_every_profile(self):
        with patch.object(bench, 'SEALED_SOURCE_SHA', 'BLOCKED_PENDING_FINAL_VALIDATED_SOURCE'):
            for profile, target, cache in [('SEED', 'seed', 'fresh-release-warm'),
                                           ('M', 'phone', 'task-cache-off'),
                                           ('C0', 'combined', 'fresh-release-warm')]:
                with self.subTest(profile=profile), self.assertRaisesRegex(ValueError, 'not sealed'):
                    bench.validate(**self.inputs(profile, target, cache))
        bench.validate(**self.inputs('SEED', 'seed', 'fresh-release-warm', source_sha=bench.SEALED_SOURCE_SHA))

    def test_sealed_source_and_context_reject_mutations(self):
        with patch.object(bench, 'SEALED_SOURCE_SHA', 'a'*40):
            bench.validate(**self.inputs())
            with self.assertRaisesRegex(ValueError, 'approved batch'):
                bench.validate(**self.inputs(source_sha='c'*40))
            for key, value in [('GITHUB_EVENT_NAME', 'push'), ('GITHUB_REPOSITORY', 'elsewhere/repo'),
                               ('GITHUB_REF', 'refs/heads/main')]:
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'context'):
                    bench.validate(**self.inputs(context=self.context | {key: value}))

    def test_closed_profile_target_and_seed_identity(self):
        with patch.object(bench, 'SEALED_SOURCE_SHA', 'a'*40):
            for changes in [dict(profile='C1'), dict(target='other'), dict(target='combined'),
                            dict(seed_run=''), dict(seed_artifact='1,2'), dict(digest='bad'),
                            dict(seed_run='$(whoami)')]:
                with self.subTest(changes=changes), self.assertRaises(ValueError):
                    bench.validate(**self.inputs(**changes))
            bench.validate(**self.inputs('SEED', 'seed', 'fresh-release-warm'))
            with self.assertRaises(ValueError):
                bench.validate(**self.inputs('SEED', 'seed', 'task-cache-off'))

    def test_fixed_commands_preserve_separate_bundle_and_split_apk_graphs(self):
        for target in ('phone', 'tv', 'combined'):
            command = bench.command('M' if target != 'combined' else 'C0', 'task-cache-off', target)
            self.assertIn('--max-workers=2', command)
            self.assertIn('--no-parallel', command)
            self.assertIn('--no-configuration-cache', command)
            self.assertIn('-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8', command)
            self.assertIn('-PsiloDisplayVersion=0.0.1-ci-benchmark', command)
            self.assertIn('-PsiloReleaseChannel=sideload', command)
            self.assertFalse(any('bundle' in item.lower() for item in command))
            self.assertEqual([item for item in command if item.startswith(':')],
                             [':' + module + ':assembleRelease' for module in bench.MODULES[target]])
        seed = bench.command('SEED', 'fresh-release-warm', 'seed')
        self.assertEqual([item for item in seed if item.startswith(':')],
                         [':androidApp:bundleRelease', ':androidTvApp:bundleRelease'])
        self.assertFalse(any('assemble' in item for item in seed))
        self.assertFalse(any('DisplayVersion' in item for item in seed))
        self.assertIn('-PsiloReleaseChannel=beta', seed)

    def test_only_cache_flag_changes_measured_command(self):
        cold = bench.command('C0', 'task-cache-off', 'combined')
        warm = bench.command('C0', 'fresh-release-warm', 'combined')
        self.assertEqual([x for x in cold if x != '--no-build-cache'],
                         [x for x in warm if x != '--build-cache'])

    def test_live_seed_provenance_requires_exact_successful_controller_run(self):
        run = {'id': 123, 'event': 'workflow_dispatch', 'conclusion': 'success',
               'head_sha': 'c'*40, 'head_branch': bench.BENCHMARK_BRANCH,
               'path': '.github/workflows/android-build.yml'}
        artifact = {'id': 456, 'expired': False, 'name': 'apk-benchmark-seed-123',
                    'workflow_run': {'id': 123, 'head_sha': 'c'*40}}
        with patch.object(bench, 'api', side_effect=[run, artifact]):
            bench.verify_seed_run('123', '456', 'c'*40)
        for field, value in [('conclusion', 'failure'), ('event', 'push'), ('head_sha', 'a'*40),
                             ('head_branch', 'main'), ('path', '.github/workflows/release.yml')]:
            with self.subTest(field=field), patch.object(bench, 'api', side_effect=[run | {field: value}, artifact]):
                with self.assertRaisesRegex(ValueError, 'Seed run'):
                    bench.verify_seed_run('123', '456', 'c'*40)
        for field, value in [('expired', True), ('name', 'other'), ('id', 999),
                             ('workflow_run', {'id': 123, 'head_sha': 'a'*40})]:
            with self.subTest(field=field), patch.object(bench, 'api', side_effect=[run, artifact | {field: value}]):
                with self.assertRaisesRegex(ValueError, 'Seed artifact'):
                    bench.verify_seed_run('123', '456', 'c'*40)

    def test_seed_bundle_requires_actual_verification_and_local_certificate(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory)/'test.aab'
            bundle.write_bytes(b'fixture')
            with patch.object(bench.subprocess, 'check_output', side_effect=['jar verified.\n', 'SHA256: ' + 'AB:'*31 + 'AB']):
                bench.verify_bundle(bundle, 'ab'*32)
            with patch.object(bench.subprocess, 'check_output', return_value='jar is unsigned.\n'):
                with self.assertRaisesRegex(ValueError, 'not verified'):
                    bench.verify_bundle(bundle, 'ab'*32)
            with patch.object(bench.subprocess, 'check_output', side_effect=['jar verified.\n', 'SHA256: ' + 'CD:'*31 + 'CD']):
                with self.assertRaisesRegex(ValueError, 'signer'):
                    bench.verify_bundle(bundle, 'ab'*32)

    def test_task_contract_rejects_omitted_work_and_invalid_cold_r8_reuse(self):
        names = ['minifyReleaseWithR8', 'lintVitalAnalyzeRelease', 'lintVitalRelease',
                 'assembleRelease', 'processReleaseResources', 'convertShrunkResourcesToBinaryRelease',
                 'optimizeReleaseResources', 'mergeReleaseArtProfile', 'compileReleaseArtProfile',
                 'processReleaseGoogleServices']
        tasks = {':androidApp:' + name: 'EXECUTED' for name in names}
        bench.check_task_contract(tasks, 'M', 'phone', 'task-cache-off')
        for name in names:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'missing'):
                bench.check_task_contract({key: value for key, value in tasks.items() if key != ':androidApp:' + name},
                                          'M', 'phone', 'task-cache-off')
        with self.assertRaisesRegex(ValueError, 'producer'):
            bench.check_task_contract(tasks | {':baselineprofile:generateBaselineProfile': 'EXECUTED'},
                                      'M', 'phone', 'task-cache-off')
        for outcome in ('FROM-CACHE', 'UP-TO-DATE', 'NO-SOURCE', 'SKIPPED'):
            with self.subTest(outcome=outcome), self.assertRaisesRegex(ValueError, 'R8 outcome'):
                bench.check_task_contract(tasks | {':androidApp:minifyReleaseWithR8': outcome},
                                          'M', 'phone', 'task-cache-off')
        bench.check_task_contract(tasks | {':androidApp:minifyReleaseWithR8': 'FROM-CACHE'},
                                  'M', 'phone', 'fresh-release-warm')

    def test_fresh_output_check_includes_shared_libraries_and_project_history(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            bench.assert_fresh_outputs(source)
            for name in ('build', '.gradle', 'androidApp/build', 'shared/build',
                         'android-shared/build', 'libass-bridge/build'):
                with self.subTest(name=name):
                    path = source / name
                    path.mkdir(parents=True)
                    with self.assertRaisesRegex(ValueError, 'prior project outputs'):
                        bench.assert_fresh_outputs(source)
                    path.rmdir()

    def test_existing_config_and_production_environment_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)/'source'
            (source/'androidApp').mkdir(parents=True)
            config = source/'androidApp/google-services.json'
            config.write_text('existing')
            with patch.dict(bench.os.environ, {}, clear=True), self.assertRaises(ValueError):
                bench.fixtures(source, Path(directory)/'temporary')
            self.assertEqual(config.read_text(), 'existing')
            config.unlink()
            with patch.dict(bench.os.environ, {'SILO_RELEASE_KEYSTORE': '/production'}, clear=True):
                with self.assertRaisesRegex(ValueError, 'Production'):
                    bench.fixtures(source, Path(directory)/'temporary')
            self.assertFalse(config.exists())


if __name__ == '__main__':
    unittest.main()
