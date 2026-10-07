#!/usr/bin/env python3
"""Actual inert upload lane/manifest/Git cases, without Gradle or network."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
RUBY = '/opt/homebrew/opt/ruby@3.4/bin/ruby' if Path('/opt/homebrew/opt/ruby@3.4/bin/ruby').exists() else 'ruby'
SCRIPT = ROOT/'scripts/ci-play-bundle-inert-upload.rb'
FILES = ['.github/workflows/release.yml', 'fastlane/Fastfile', 'fastlane/play_bundle_manifest.rb',
         'scripts/test-play-bundle-lanes.rb', 'scripts/test-release-workflow.sh',
         'androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesViewModelTest.kt']
cases = 0
def sha(data): return hashlib.sha256(data).hexdigest()
with tempfile.TemporaryDirectory(prefix='silo-inert-dag-') as temporary:
    area = Path(temporary)
    repo = area/'repo'
    repo.mkdir()
    for name in FILES:
        target = repo/name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/name, target)
    (repo/'build-input.txt').write_text('original\n')
    env = {'PATH': os.environ['PATH'], 'HOME': str(area/'home'), 'GIT_CONFIG_NOSYSTEM': '1',
           'GIT_CONFIG_GLOBAL': '/dev/null', 'SILO_DIAGNOSTIC_ROOT': str(repo),
           'SILO_DIAGNOSTIC_RECEIPT': str(area/'receipt.json'),
           'GITHUB_REPOSITORY': 'Silo-Server/silo-android', 'GITHUB_REPOSITORY_ID': '1247142403',
           'GITHUB_RUN_ID': '9001', 'GITHUB_RUN_ATTEMPT': '1',
           'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_WORKFLOW': 'Android Builds',
           'GITHUB_WORKFLOW_REF': 'Silo-Server/silo-android/.github/workflows/android-build.yml@refs/heads/inert-fixture',
           'SILO_VERSION_NAME': '0.0.2', 'SILO_BUILD_NUMBER': '1', 'PLAY_TRACK': 'beta',
           'PLAY_RELEASE_NOTES': 'Synthetic diagnostic fixture', 'SILO_PLAY_BUNDLE_ATTEMPT': '1'}
    Path(env['HOME']).mkdir()
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=repo, env=env, stderr=subprocess.DEVNULL).decode().strip()
    git('init', '-q')
    git('add', '.')
    git('-c', 'user.name=Inert fixture', '-c', 'user.email=inert@example.invalid', '-c', 'commit.gpgsign=false',
        'commit', '-qm', 'Disposable diagnostic fixture')
    env['GITHUB_SHA'] = git('rev-parse', 'HEAD')
    paths = ['androidApp/build/outputs/bundle/release/androidApp-release.aab',
             'androidTvApp/build/outputs/bundle/release/androidTvApp-release.aab']
    original = [b'phone inert bytes\0', b'TV inert bytes\0']
    def restore():
        for name, data in zip(paths, original):
            target = repo/name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    restore()
    producer = "require './fastlane/play_bundle_manifest'; PlayBundleManifest.write!(root: Dir.pwd, version: '0.0.2', base_code: 100002001, build: 1, track: 'beta')"
    subprocess.run([RUBY, '-e', producer], cwd=repo, env=env, check=True, stdout=subprocess.DEVNULL)
    manifest_path = repo/'build/play-bundles-manifest.json'
    manifest_bytes = manifest_path.read_bytes()
    env['SILO_PLAY_BUNDLE_MANIFEST_SHA256'] = sha(manifest_bytes)
    def invoke(expected_exit, entries, **changes):
        global cases
        context = dict(env, **changes)
        result = subprocess.run([RUBY, str(SCRIPT)], cwd=repo, env=context, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert result.returncode == expected_exit, (result.returncode, result.stderr.decode())
        receipt = json.loads((area/'receipt.json').read_text())
        assert receipt['exit_code'] == expected_exit and receipt['publisher_stub_entries'] == entries, (receipt, result.stderr.decode())
        assert receipt['inert_action_entries'] == entries and receipt['production_publish'] is False
        assert receipt['play_credentials_provided'] == ('PLAY_SERVICE_ACCOUNT_JSON' in context)
        if entries:
            assert [item['sha256'] for item in receipt['bundles']] == [sha(data) for data in original]
        cases += 1
        return receipt
    first = invoke(1, 0, SILO_DIAGNOSTIC_REJECT_FIRST_DOWNLOAD='true')
    assert first['deliberate_first_download_interruption'] is True and not (repo/paths[1]).exists(), first
    restore()
    recovered = invoke(0, 1, GITHUB_RUN_ATTEMPT='2', SILO_DIAGNOSTIC_REJECT_FIRST_DOWNLOAD='true')
    assert recovered['producer_attempt'] == '1' and recovered['consumer_attempt'] == '2'
    invoke(0, 1, SILO_DIAGNOSTIC_REJECT_FIRST_DOWNLOAD='false')
    (repo/paths[1]).unlink()
    invoke(1, 0, GITHUB_RUN_ATTEMPT='2')
    restore()
    invoke(1, 0, GITHUB_SHA='c'*40)
    invoke(1, 0, SILO_PLAY_BUNDLE_ATTEMPT='2')
    invoke(1, 0, SILO_PLAY_BUNDLE_MANIFEST_SHA256='0'*64)
    altered = json.loads(manifest_bytes)
    (repo/paths[0]).write_bytes(b'altered artifact')
    altered['bundles'][0].update(bytes=len(b'altered artifact'), sha256=sha(b'altered artifact'))
    manifest_path.write_text(json.dumps(altered))
    invoke(1, 0)
    restore()
    manifest_path.write_bytes(manifest_bytes)
    for staged in [False, True]:
        (repo/'build-input.txt').write_text('modified tracked input\n')
        if staged: git('add', 'build-input.txt')
        invoke(1, 0)
        git('reset', '--hard', '-q', 'HEAD')
    invoke(1, 0, GITHUB_EVENT_NAME='push')
    invoke(1, 0, PLAY_SERVICE_ACCOUNT_JSON='inert value that must never be read')
    metadata = {'id': 7001, 'name': 'android-play-bundles-9001-1', 'expired': False, 'size_in_bytes': 1000,
                'workflow_run': {'id': 9001, 'repository_id': 1247142403,
                                 'head_repository_id': 1247142403, 'head_sha': env['GITHUB_SHA']}}
    artifact_env = dict(env, GITHUB_RUN_ATTEMPT='2', PLAY_BUNDLE_ARTIFACT_ID='7001',
                        PLAY_BUNDLE_ARTIFACT_NAME='android-play-bundles-9001-1')
    for mutation, expected in [({}, 0), ({'id': 7002}, 1), ({'name': 'android-play-bundles-9002-1'}, 1),
                               ({'expired': True}, 1), ({'workflow_run': dict(metadata['workflow_run'], id=9002)}, 1)]:
        result = subprocess.run([RUBY, 'fastlane/play_bundle_manifest.rb', 'verify-artifact'], cwd=repo,
                                env=artifact_env, input=json.dumps(dict(metadata, **mutation)).encode(),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert result.returncode == expected
        cases += 1
workflow = ROOT/'.github/workflows/play-bundle-diagnostic.yml'
parsed = subprocess.check_output([RUBY, '-ryaml', '-rjson', '-e',
    'puts JSON.generate(YAML.safe_load(File.read(ARGV.fetch(0))))', str(workflow)])
document = json.loads(parsed)
jobs = document['jobs']
assert set(jobs) == {'unit-tests', 'lint', 'prepare', 'inert-upload'}
assert 'needs' not in jobs['prepare'] and 'needs' not in jobs['unit-tests'] and 'needs' not in jobs['lint']
assert jobs['inert-upload']['needs'] == ['unit-tests', 'lint', 'prepare'] and 'if' not in jobs['inert-upload']
assert not any('continue-on-error' in step for job in jobs.values() for step in job['steps'])
assert 'secrets.' not in workflow.read_text() and 'PLAY_SERVICE_ACCOUNT_JSON' not in workflow.read_text()
cleanup = next(step for step in jobs['prepare']['steps'] if step.get('name') == 'Remove owned synthetic inputs')
assert cleanup['if'] == 'always()' and '${RUNNER_TEMP}/silo-play-diagnostic-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}.jks' in cleanup['run']
test_step = next(step for step in jobs['unit-tests']['steps'] if step.get('name') == 'Run full unit tests')
assert test_step['run'] == './gradlew -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" test --max-workers=2'
lint_step = next(step for step in jobs['lint']['steps'] if step.get('name') == 'Run fatal lint')
assert all(task in lint_step['run'] for task in [':android-shared:lintDebug', ':androidApp:lintDebug',
    ':androidTvApp:lintDebug', ':androidApp:lintVitalRelease', ':androidTvApp:lintVitalRelease'])
caller = json.loads(subprocess.check_output([RUBY, '-ryaml', '-rjson', '-e',
    'puts JSON.generate(YAML.safe_load(File.read(ARGV.fetch(0))))', str(ROOT/'.github/workflows/android-build.yml')]))
assert caller['jobs']['ci']['if'] == "github.event_name != 'workflow_dispatch' || !inputs.play_bundle_diagnostic"
assert caller['jobs']['play-bundle-diagnostic']['if'] == "github.event_name == 'workflow_dispatch' && inputs.play_bundle_diagnostic"
assert caller['jobs']['play-bundle-diagnostic']['uses'] == './.github/workflows/play-bundle-diagnostic.yml'
print(json.dumps({'actual_inert_cases': cases, 'workflow_dependencies_and_full_gates_verified': True,
                  'production_publish': False, 'native_or_network_execution': False,
                  'qualification': 'Disposable actual Git, unchanged upload lane and manifest; initial interruption then same producer recovery'}))
