import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
import apk_benchmark as bench


class ControllerTests(unittest.TestCase):
    context = {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REPOSITORY': bench.REPOSITORY,
               'GITHUB_REF': 'refs/heads/' + bench.BENCHMARK_BRANCH}
    observed_toolchain = {
        'java': 'openjdk version "21.0.12.1" 2026-08-18 LTS',
        'gradle': '8.12', 'agp': '8.10.1', 'runner_image': '20260927.320.1',
        'cpu_count': 4, 'memory_total_kib': 16373452,
        'sdk_platforms': ['android-36', 'android-37.2-beta3'],
        'build_tools': ['36.0.0', '37.0.0'],
    }

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

    def test_parent_seed_compatibility_requires_the_complete_accepted_tuple(self):
        args = bench.argparse.Namespace(seed_run='37667888296', seed_artifact='11503849598',
                    seed_manifest_sha256='3612997cf57cc12cfdace48e81f01769abb6d28272635fdf1fa5f0495f47c763')
        with patch.dict(bench.os.environ, {'GITHUB_WORKFLOW_SHA': 'c'*40}):
            expected = bench.seed_provenance(args)
            self.assertEqual(expected['controller_sha'], '540d0514d3f795f742c3afc75f19491cb189b1ed')
            self.assertEqual(expected['source_sha'], bench.SEALED_SOURCE_SHA)
            self.assertEqual(expected['fixture_sha256'], bench.FIXTURE_SHA256)
            self.assertEqual(expected['repository'], bench.REPOSITORY)
            self.assertEqual(expected['seed_metadata'], bench.SEED_METADATA)
            for field, value in [('seed_run', '37667888297'), ('seed_artifact', '11503849599'),
                                 ('seed_manifest_sha256', 'b'*64)]:
                with self.subTest(field=field):
                    changed = bench.argparse.Namespace(**vars(args) | {field: value})
                    self.assertEqual(bench.seed_provenance(changed)['controller_sha'], 'c'*40)

    def test_parent_seed_still_requires_actual_current_controller_identity(self):
        args = bench.argparse.Namespace(seed_run='37667888296', seed_artifact='11503849598',
                    seed_manifest_sha256='3612997cf57cc12cfdace48e81f01769abb6d28272635fdf1fa5f0495f47c763')
        with patch.dict(bench.os.environ, {'GITHUB_WORKFLOW_SHA': 'not-a-commit'}):
            with self.assertRaisesRegex(ValueError, 'Controller revision missing'):
                bench.seed_provenance(args)

    def test_parent_seed_api_binding_rejects_a_different_controller(self):
        args = bench.argparse.Namespace(seed_run='37667888296', seed_artifact='11503849598',
                    seed_manifest_sha256='3612997cf57cc12cfdace48e81f01769abb6d28272635fdf1fa5f0495f47c763')
        run = {'id': 37667888296, 'event': 'workflow_dispatch', 'conclusion': 'success',
               'head_sha': '540d0514d3f795f742c3afc75f19491cb189b1ed', 'head_branch': bench.BENCHMARK_BRANCH,
               'path': '.github/workflows/android-build.yml'}
        artifact = {'id': 11503849598, 'expired': False, 'name': 'apk-benchmark-seed-37667888296',
                    'workflow_run': {'id': 37667888296, 'head_sha': run['head_sha']}}
        with patch.dict(bench.os.environ, {'GITHUB_WORKFLOW_SHA': 'c'*40}):
            controller = bench.seed_provenance(args)['controller_sha']
            with patch.object(bench, 'api', side_effect=[run, artifact]):
                bench.verify_seed_run(args.seed_run, args.seed_artifact, controller)
            with patch.object(bench, 'api', side_effect=[run | {'head_sha': 'c'*40}, artifact]):
                with self.assertRaisesRegex(ValueError, 'Seed run'):
                    bench.verify_seed_run(args.seed_run, args.seed_artifact, controller)
            with patch.object(bench, 'api', side_effect=[run, artifact | {'workflow_run': {'id': run['id'], 'head_sha': 'c'*40}}]):
                with self.assertRaisesRegex(ValueError, 'Seed artifact'):
                    bench.verify_seed_run(args.seed_run, args.seed_artifact, controller)

    def test_complete_toolchain_observation_preserves_both_safe_dictionaries(self):
        old = self.observed_toolchain | {'runner_image': '20261004.327.1'}
        with patch('builtins.print') as output:
            bench.emit_toolchain_observation(self.observed_toolchain, old)
        value = json.loads(output.call_args.args[0])['toolchain_observation']
        self.assertEqual(value, {'schema_version': 1, 'current_toolchain': self.observed_toolchain,
                                 'seed_toolchain': old, 'guard_fields': ['java', 'gradle', 'agp', 'runner_image'],
                                 'differing_fields': ['runner_image'], 'differing_guard_fields': ['runner_image']})
        self.assertEqual(output.call_args.kwargs, {'flush': True})
        with patch('builtins.print') as output:
            bench.emit_toolchain_observation(self.observed_toolchain)
        self.assertIsNone(json.loads(output.call_args.args[0])['toolchain_observation']['seed_toolchain'])

    def test_unsafe_or_incomplete_toolchains_reject_before_any_observation_output(self):
        valid = self.observed_toolchain
        invalid = [None, {}, valid | {'credential': 'PRIVATE_TOOLCHAIN_VALUE'},
                   {key: value for key, value in valid.items() if key != 'cpu_count'}]
        invalid += [valid | {key: value} for key, value in (
            ('java', 'PRIVATE_TOOLCHAIN_VALUE'), ('java', valid['java'] + '\nPRIVATE_TOOLCHAIN_VALUE'),
            ('gradle', '/PRIVATE_TOOLCHAIN_VALUE'), ('runner_image', 'PRIVATE_TOOLCHAIN_VALUE'),
            ('cpu_count', True), ('memory_total_kib', -1), ('memory_total_kib', 2**41),
            ('sdk_platforms', ['android-36/PRIVATE_TOOLCHAIN_VALUE']),
            ('sdk_platforms', ['android-36', 'android-36']),
            ('sdk_platforms', ['android-37', 'android-36']),
            ('build_tools', ['36.0.0\nPRIVATE_TOOLCHAIN_VALUE']),
            ('build_tools', ['36.0.0'] * 129), ('build_tools', 'PRIVATE_TOOLCHAIN_VALUE'),
        )]
        for value in invalid:
            for seed_side in (False, True):
                if value is None and seed_side:
                    continue  # None is the explicit SEED observation form.
                with self.subTest(seed_side=seed_side, value=value), patch('builtins.print') as output:
                    with self.assertRaises(ValueError) as rejected:
                        bench.emit_toolchain_observation(valid if seed_side else value, value if seed_side else valid)
                    self.assertNotIn('PRIVATE_TOOLCHAIN_VALUE', str(rejected.exception))
                    output.assert_not_called()

    def test_toolchain_mismatch_emits_full_observation_before_preserved_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            source.mkdir()
            for key, value in [('java', 'openjdk version "21.0.11" 2026-04-21 LTS'),
                               ('gradle', '8.13'), ('agp', '8.10.2'), ('runner_image', '20261004.327.1')]:
                with self.subTest(changed_guard_field=key):
                    receipt = root / 'receipt.json'
                    output_path = root / key
                    args = bench.argparse.Namespace(source=str(source), output=str(output_path), profile='M',
                        cache='task-cache-off', target='phone', seed_receipt=str(receipt),
                        seed_manifest_sha256=bench.APPROVED_PARENT_SEED[2],
                        seed_run=bench.APPROVED_PARENT_SEED[0], seed_artifact=bench.APPROVED_PARENT_SEED[1])
                    environment = {'GRADLE_USER_HOME': str(root / 'gradle'), 'GITHUB_WORKFLOW_SHA': 'a' * 40}
                    prior = self.observed_toolchain | {key: value}
                    with patch.dict(bench.os.environ, environment):
                        receipt.write_text(json.dumps({'manifest_sha256': args.seed_manifest_sha256,
                            'provenance': bench.seed_provenance(args), 'toolchain': prior, 'restore_seconds': 1}))
                        with patch.object(bench.subprocess, 'check_output', side_effect=[bench.SEALED_SOURCE_SHA, '']), \
                                patch.object(bench, 'toolchain', return_value=self.observed_toolchain), \
                                patch.object(bench, 'fixtures') as fixtures, \
                                patch.object(bench.subprocess, 'Popen') as gradle, patch('builtins.print') as output:
                            with self.assertRaisesRegex(ValueError, '^Seed and runner toolchain mismatch$'):
                                bench.run_build(args)
                    self.assertEqual(output.call_count, 1)
                    observation = json.loads(output.call_args.args[0])['toolchain_observation']
                    self.assertEqual(observation['current_toolchain'], self.observed_toolchain)
                    self.assertEqual(observation['seed_toolchain'], prior)
                    self.assertEqual(observation['differing_fields'], [key])
                    self.assertEqual(observation['differing_guard_fields'], [key])
                    fixtures.assert_not_called()
                    gradle.assert_not_called()
                    self.assertFalse((output_path / 'report.json').exists())

    def test_untrusted_seed_receipt_is_rejected_before_toolchain_emission(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            source.mkdir()
            receipt = root / 'receipt.json'
            args = bench.argparse.Namespace(source=str(source), output=str(root / 'output'), profile='M',
                cache='task-cache-off', target='phone', seed_receipt=str(receipt),
                seed_manifest_sha256=bench.APPROVED_PARENT_SEED[2],
                seed_run=bench.APPROVED_PARENT_SEED[0], seed_artifact=bench.APPROVED_PARENT_SEED[1])
            receipt.write_text(json.dumps({'manifest_sha256': 'b' * 64, 'provenance': {},
                                           'toolchain': {'credential': 'PRIVATE_TOOLCHAIN_VALUE'}}))
            with patch.dict(bench.os.environ, {'GRADLE_USER_HOME': str(root / 'gradle')}), \
                    patch.object(bench.subprocess, 'check_output', side_effect=[bench.SEALED_SOURCE_SHA, '']), \
                    patch.object(bench, 'toolchain', return_value=self.observed_toolchain), patch('builtins.print') as output:
                with self.assertRaisesRegex(ValueError, 'Approved seed restore receipt mismatch'):
                    bench.run_build(args)
            output.assert_not_called()

    def test_capacity_difference_rejects_before_fixtures_after_the_four_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            source.mkdir()
            receipt = root / 'receipt.json'
            args = bench.argparse.Namespace(source=str(source), output=str(root / 'output'), profile='M',
                cache='task-cache-off', target='phone', seed_receipt=str(receipt),
                seed_manifest_sha256=bench.APPROVED_PARENT_SEED[2],
                seed_run=bench.APPROVED_PARENT_SEED[0], seed_artifact=bench.APPROVED_PARENT_SEED[1])
            environment = {'GRADLE_USER_HOME': str(root / 'gradle'), 'RUNNER_TEMP': str(root),
                           'GITHUB_WORKFLOW_SHA': 'a' * 40}
            prior = self.observed_toolchain | {'cpu_count': 8}
            sampler = Mock()
            sampler.thread.is_alive.return_value = False
            with patch.dict(bench.os.environ, environment):
                receipt.write_text(json.dumps({'manifest_sha256': args.seed_manifest_sha256,
                    'provenance': bench.seed_provenance(args), 'toolchain': prior, 'restore_seconds': 1}))
                with patch.object(bench.subprocess, 'check_output', side_effect=[bench.SEALED_SOURCE_SHA, '']), \
                        patch.object(bench, 'toolchain', return_value=self.observed_toolchain), \
                        patch.object(bench, 'fixtures', side_effect=ValueError('fixture boundary')) as fixtures, \
                        patch.object(bench, 'MemorySampler', return_value=sampler), \
                        patch.object(bench.subprocess, 'Popen') as gradle, patch('builtins.print') as output:
                    with self.assertRaisesRegex(ValueError, 'four-CPU toolchain capacity'):
                        bench.run_build(args)
            observation = json.loads(output.call_args.args[0])['toolchain_observation']
            self.assertEqual(observation['differing_fields'], ['cpu_count'])
            self.assertEqual(observation['differing_guard_fields'], [])
            fixtures.assert_not_called()
            gradle.assert_not_called()

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

    def google_services_values(self, source, **changes):
        values = source/'androidApp/build/generated/res/processReleaseGoogleServices/values/values.xml'
        values.parent.mkdir(parents=True, exist_ok=True)
        resources = {'google_app_id': bench.FIXTURE['client'][0]['client_info']['mobilesdk_app_id'],
                     'project_id': bench.FIXTURE['project_info']['project_id'],
                     'google_api_key': bench.FIXTURE['client'][0]['api_key'][0]['current_key']}
        resources.update(changes)
        root = bench.ElementTree.Element('resources')
        for name, value in resources.items():
            if value is not None:
                bench.ElementTree.SubElement(root, 'string', name=name).text = value
        bench.ElementTree.ElementTree(root).write(values)
        return values

    def test_google_services_current_producer_layout_is_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            self.google_services_values(source)
            bench.verify_google_services(source)

    def test_google_services_missing_current_output_rejects_legacy_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            values = self.google_services_values(source)
            legacy = source/'androidApp/build/generated/res/google-services/release/values/values.xml'
            legacy.parent.mkdir(parents=True)
            values.rename(legacy)
            with self.assertRaisesRegex(ValueError, 'Missing pinned Google Services'):
                bench.verify_google_services(source)

    def test_google_services_every_fixture_value_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            for name in ('google_app_id', 'project_id', 'google_api_key'):
                for value in (None, 'wrong'):
                    with self.subTest(name=name, value=value):
                        self.google_services_values(source, **{name: value})
                        with self.assertRaisesRegex(ValueError, 'resource contract mismatch'):
                            bench.verify_google_services(source)

    def test_google_services_malformed_xml_has_bounded_failure_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            values = self.google_services_values(source)
            values.write_text('<invalid>')
            with self.assertRaisesRegex(ValueError, '^Cannot read pinned Google Services release values.xml output$'):
                bench.verify_google_services(source)

    def test_missing_google_services_output_retains_unqualified_report_before_seed_export(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root/'source', root/'output'
            (source/'androidApp').mkdir(parents=True)
            args = bench.argparse.Namespace(source=str(source), output=str(output), profile='SEED',
                                            cache='fresh-release-warm', target='seed',
                                            seed_manifest_sha256='', seed_run='', seed_artifact='')
            names = [':' + module + ':' + task for module in bench.MODULES['seed']
                     for task in ('minifyReleaseWithR8', 'signReleaseBundle', 'bundleRelease')]
            process = Mock(stdout=['> Task ' + name + '\n' for name in names]
                           + ['> Task :androidApp:processReleaseGoogleServices\n'])
            process.wait.return_value = 0
            sampler = Mock(result={'samples': 0})
            sampler.thread.is_alive.return_value = False
            environment = {'GRADLE_USER_HOME': str(root/'gradle-home'), 'RUNNER_TEMP': str(root),
                           'GITHUB_WORKFLOW_SHA': 'a'*40}
            with patch.dict(bench.os.environ, environment), \
                    patch.object(bench.subprocess, 'check_output', side_effect=[bench.SEALED_SOURCE_SHA, '']), \
                    patch.object(bench, 'toolchain', return_value=self.observed_toolchain), \
                    patch.object(bench, 'fixtures', return_value=({}, 'ab'*32)), \
                    patch.object(bench, 'MemorySampler', return_value=sampler), \
                    patch.object(bench.subprocess, 'Popen', return_value=process), \
                    patch.object(bench, 'verify_bundle') as verify_bundle, \
                    patch.object(bench, 'export_seed') as export_seed:
                with self.assertRaisesRegex(ValueError, 'Missing pinned Google Services'):
                    bench.run_build(args)
            report = json.loads((output/'report.json').read_text())
            self.assertEqual(report['returncode'], 0)
            self.assertIs(report['qualified'], False)
            self.assertEqual(report['verification_failure'],
                             'Missing pinned Google Services release values.xml output')
            verify_bundle.assert_not_called()
            export_seed.assert_not_called()
            self.assertNotIn('seed', report)

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

    def test_native_contract_failure_retains_unqualified_report_and_actual_controller(self):
        import apk_benchmark_artifacts as artifacts
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root/'source', root/'output'
            source.mkdir()
            receipt = root/'receipt.json'
            args = bench.argparse.Namespace(source=str(source), output=str(output), profile='M',
                                            cache='task-cache-off', target='phone', seed_receipt=str(receipt),
                                            seed_manifest_sha256='3612997cf57cc12cfdace48e81f01769abb6d28272635fdf1fa5f0495f47c763',
                                            seed_run='37667888296', seed_artifact='11503849598')
            names = ['minifyReleaseWithR8', 'lintVitalAnalyzeRelease', 'lintVitalRelease',
                     'assembleRelease', 'processReleaseResources', 'convertShrunkResourcesToBinaryRelease',
                     'optimizeReleaseResources', 'mergeReleaseArtProfile', 'compileReleaseArtProfile',
                     'processReleaseGoogleServices']
            process = Mock(stdout=['> Task :androidApp:' + name + '\n' for name in names])
            process.wait.return_value = 0
            sampler = Mock(result={'samples': 0})
            sampler.thread.is_alive.return_value = False
            toolchain = self.observed_toolchain
            environment = {'GRADLE_USER_HOME': str(root/'gradle-home'), 'RUNNER_TEMP': str(root),
                           'ANDROID_HOME': str(root/'sdk'), 'GITHUB_WORKFLOW_SHA': 'a'*40}
            error = artifacts.NativePayloadContractError('androidApp/universal', 'lib/x86/libdependency.so',
                        {'lib/x86/libdependency.so': {'size': 7, 'sha256': 'c'*64}})
            with patch.dict(bench.os.environ, environment):
                receipt.write_text(json.dumps({'manifest_sha256': args.seed_manifest_sha256, 'restore_seconds': 1,
                                               'provenance': bench.seed_provenance(args), 'toolchain': toolchain}))
                with patch.object(bench.subprocess, 'check_output', side_effect=[bench.SEALED_SOURCE_SHA, '']), \
                        patch.object(bench, 'toolchain', return_value=toolchain), \
                        patch.object(bench, 'fixtures', return_value=({}, 'ab'*32)), \
                        patch.object(bench, 'MemorySampler', return_value=sampler), \
                        patch.object(bench.subprocess, 'Popen', return_value=process), \
                        patch.object(bench, 'verify_google_services'), \
                        patch.object(artifacts, 'inventory', side_effect=error):
                    with self.assertRaises(artifacts.NativePayloadContractError):
                        bench.run_build(args)
            report = json.loads((output/'report.json').read_text())
            self.assertEqual(report['returncode'], 0)
            self.assertIs(report['qualified'], False)
            self.assertEqual(report['native_verification_failure'], error.details)
            self.assertEqual(report['controller_sha'], 'a'*40)
            self.assertEqual(report['seed_run_id'], '37667888296')
            self.assertEqual(report['seed_manifest_sha256'], args.seed_manifest_sha256)
            self.assertNotIn('artifacts', report)
            self.assertNotIn('seed', report)

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


class BoundedMemoryCohortTests(unittest.TestCase):
    tools = ControllerTests.observed_toolchain

    def test_actual_small_variation_and_inclusive_sixteen_mib_boundary_pass(self):
        base = self.tools['memory_total_kib']
        bench.check_toolchain_cohort([self.tools, self.tools | {'memory_total_kib': 16372440}, self.tools | {'memory_total_kib': 16373448}])
        for delta in (-16384, 16384):
            with self.subTest(delta=delta):bench.check_toolchain_cohort([self.tools, self.tools | {'memory_total_kib': base + delta}])

    def test_whole_seed_m_c0_spread_rejects_pairwise_compatible_arms(self):
        low = self.tools | {'memory_total_kib': self.tools['memory_total_kib'] - 10000}
        high = self.tools | {'memory_total_kib': self.tools['memory_total_kib'] + 10000}
        bench.check_toolchain_cohort([self.tools, low])
        bench.check_toolchain_cohort([self.tools, high])
        with self.assertRaisesRegex(ValueError, 'spread'):bench.check_toolchain_cohort([self.tools, low, self.tools, high])

    def test_capacity_bools_missing_nonpositive_float_and_material_drift_reject(self):
        invalid = [None, {}, self.tools | {'extra': 'unexpected'}]
        invalid += [self.tools | {key:value} for key,value in [('cpu_count',True),('cpu_count',8),('cpu_count',0),
            ('memory_total_kib',True),('memory_total_kib',None),('memory_total_kib','16373452'),('memory_total_kib',16373452.0),
            ('memory_total_kib',0),('memory_total_kib',-1),('memory_total_kib',2**41),('memory_total_kib',8*1024*1024),
            ('memory_total_kib',self.tools['memory_total_kib']+16385),('memory_total_kib',self.tools['memory_total_kib']-16385)]]
        invalid += [{key:value for key,value in self.tools.items() if key!=missing} for missing in self.tools]
        for value in invalid:
            with self.subTest(value=value),self.assertRaises(ValueError):bench.check_toolchain_cohort([self.tools,value])
        for values in (None, [], [self.tools]*5, (self.tools,self.tools)):
            with self.subTest(cohort=values),self.assertRaises(ValueError):bench.check_toolchain_cohort(values)

    def test_each_nonmemory_difference_is_rejected(self):
        changes = {'java':'openjdk version "21.0.11" 2026-04-21 LTS','gradle':'8.13','agp':'8.10.2',
            'runner_image':'20261004.327.1','cpu_count':8,'sdk_platforms':['android-99'],'build_tools':['99.0.0']}
        for key,value in changes.items():
            with self.subTest(key=key),self.assertRaises(ValueError):bench.check_toolchain_cohort([self.tools,self.tools | {key:value}])

    def test_sdk_mismatch_rejects_before_fixtures_and_gradle_with_raw_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory);source = root/'source';source.mkdir();receipt = root/'receipt.json'
            args = bench.argparse.Namespace(source=str(source),output=str(root/'output'),profile='M',cache='task-cache-off',target='phone',
                seed_receipt=str(receipt),seed_manifest_sha256=bench.APPROVED_PARENT_SEED[2],seed_run=bench.APPROVED_PARENT_SEED[0],seed_artifact=bench.APPROVED_PARENT_SEED[1])
            with patch.dict(bench.os.environ,{'GRADLE_USER_HOME':str(root/'gradle'),'GITHUB_WORKFLOW_SHA':'a'*40}):
                receipt.write_text(json.dumps({'manifest_sha256':args.seed_manifest_sha256,'provenance':bench.seed_provenance(args),
                    'toolchain':self.tools | {'sdk_platforms':['android-99']},'restore_seconds':1}))
                with patch.object(bench.subprocess,'check_output',side_effect=[bench.SEALED_SOURCE_SHA,'']),patch.object(bench,'toolchain',return_value=self.tools), \
                        patch.object(bench,'fixtures') as fixtures,patch.object(bench.subprocess,'Popen') as gradle,patch('builtins.print') as output:
                    with self.assertRaisesRegex(ValueError,'Nonmemory'):bench.run_build(args)
            self.assertEqual(output.call_count,1)
            self.assertEqual(json.loads(output.call_args.args[0])['toolchain_observation']['differing_fields'],['sdk_platforms'])
            fixtures.assert_not_called();gradle.assert_not_called()


if __name__ == '__main__':
    unittest.main()
