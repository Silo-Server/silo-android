#!/usr/bin/env python3
"""Manual APK pilot with fixed tasks, synthetic fixtures and no release client."""
import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree

from apk_benchmark_seed import SEED_METADATA, export_seed, restore_seed

# Fixed validated source; root reviews this controller commit before dispatch.
SEALED_SOURCE_SHA = '78f6b3e2ec8898755da381365f8f7d21e2e63dd9'
BENCHMARK_BRANCH = 'ci/android-apk-layout-pilot'
REPOSITORY = 'Silo-Server/silo-android'
FIXTURE = {
    'project_info': {'project_number': '100000000001', 'project_id': 'silo-ci-benchmark',
                     'storage_bucket': 'silo-ci-benchmark.invalid'},
    'client': [{'client_info': {'mobilesdk_app_id': '1:100000000001:android:1111111111111111111111',
                              'android_client_info': {'package_name': 'org.siloserver.silo'}},
                'api_key': [{'current_key': 'AIzaSy000000000000000000000000000000000'}],
                'services': {'appinvite_service': {'other_platform_oauth_client': []}}}],
    'configuration_version': '1',
}
FIXTURE_BYTES = (json.dumps(FIXTURE, sort_keys=True, indent=2) + '\n').encode()
FIXTURE_SHA256 = hashlib.sha256(FIXTURE_BYTES).hexdigest()
MODULES = {'phone': ['androidApp'], 'tv': ['androidTvApp'],
           'combined': ['androidApp', 'androidTvApp'], 'seed': ['androidApp', 'androidTvApp']}


def validate(source_sha, profile, cache, target, seed_run='', seed_artifact='', digest='', context=None):
    context = os.environ if context is None else context
    if not re.fullmatch(r'[0-9a-f]{40}', SEALED_SOURCE_SHA):
        raise ValueError('Benchmark blocked: final validated source is not sealed')
    if source_sha != SEALED_SOURCE_SHA:
        raise ValueError('Source does not match the approved batch')
    if (context.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or context.get('GITHUB_REPOSITORY') != REPOSITORY
            or context.get('GITHUB_REF') != 'refs/heads/' + BENCHMARK_BRANCH):
        raise ValueError('Unexpected benchmark workflow context')
    if profile not in ('SEED', 'M', 'C0') or cache not in ('task-cache-off', 'fresh-release-warm'):
        raise ValueError('Unexpected benchmark profile')
    if target not in MODULES or (profile == 'SEED') != (target == 'seed'):
        raise ValueError('Unexpected benchmark target')
    if (profile == 'M' and target not in ('phone', 'tv')) or (profile == 'C0' and target != 'combined'):
        raise ValueError('Profile and target do not match')
    if profile == 'SEED':
        if cache != 'fresh-release-warm' or any((seed_run, seed_artifact, digest)):
            raise ValueError('SEED must create a fresh prior-release seed')
    elif (not re.fullmatch(r'[1-9][0-9]{0,19}', seed_run)
          or not re.fullmatch(r'[1-9][0-9]{0,19}', seed_artifact)
          or not re.fullmatch(r'[0-9a-f]{64}', digest)):
        raise ValueError('Measured profiles require the approved exact seed')


def command(profile, cache, target):
    modules = MODULES[target]
    options = ['./gradlew', '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8',
               '--max-workers=2', '--no-configuration-cache', '--no-parallel',
               '--profile', '--console=plain']
    if profile == 'SEED':
        options += ['--build-cache', '-PsiloVersionName=0.0.0', '-PsiloVersionCode=100000001',
                    '-PsiloBuildNumber=1', '-PsiloReleaseChannel=beta']
        return options + [':' + module + ':bundleRelease' for module in modules]
    options += ['--no-build-cache' if cache == 'task-cache-off' else '--build-cache',
                '-PsiloVersionName=0.0.1', '-PsiloDisplayVersion=0.0.1-ci-benchmark',
                '-PsiloVersionCode=100001001', '-PsiloBuildNumber=1', '-PsiloReleaseChannel=sideload']
    return options + [':' + module + ':assembleRelease' for module in modules]


def api(path):
    token = os.environ.get('GH_BENCHMARK_READ_TOKEN', '')
    if not token:
        raise ValueError('Read-only seed provenance token missing')
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + path,
                                    headers={'Authorization': 'Bearer ' + token,
                                             'Accept': 'application/vnd.github+json',
                                             'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except (urllib.error.URLError, ValueError) as error:
        raise ValueError('Could not verify seed provenance with GitHub') from None


def verify_seed_run(run_id, artifact_id, controller_sha):
    run, artifact = api('/actions/runs/' + run_id), api('/actions/artifacts/' + artifact_id)
    if (str(run.get('id')) != run_id or run.get('event') != 'workflow_dispatch'
            or run.get('conclusion') != 'success' or run.get('head_sha') != controller_sha
            or run.get('head_branch') != BENCHMARK_BRANCH
            or run.get('path', '').split('@')[0] != '.github/workflows/android-build.yml'):
        raise ValueError('Seed run does not match the approved controller')
    if (str(artifact.get('id')) != artifact_id or artifact.get('expired') is not False
            or artifact.get('name') != 'apk-benchmark-seed-' + run_id
            or str(artifact.get('workflow_run', {}).get('id')) != run_id
            or artifact.get('workflow_run', {}).get('head_sha') != controller_sha):
        raise ValueError('Seed artifact does not match the approved run')


def provenance(run_id):
    controller = os.environ.get('GITHUB_WORKFLOW_SHA', '')
    if not re.fullmatch(r'[0-9a-f]{40}', controller):
        raise ValueError('Controller revision missing')
    return {'repository': REPOSITORY, 'source_sha': SEALED_SOURCE_SHA,
            'controller_sha': controller, 'run_id': run_id, 'seed_mode': 'prior-release-warm',
            'seed_metadata': SEED_METADATA, 'fixture_sha256': FIXTURE_SHA256}


def toolchain(source):
    java = subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True).splitlines()[0]
    wrapper = (source/'gradle/wrapper/gradle-wrapper.properties').read_text()
    catalog = (source/'gradle/libs.versions.toml').read_text()
    sdk = Path(os.environ['ANDROID_HOME'])
    result = {'java': java, 'gradle': re.search(r'gradle-([0-9.]+)-bin', wrapper).group(1),
              'agp': re.search(r'^agp = "([^"]+)"', catalog, re.M).group(1),
              'runner_image': os.environ.get('ImageVersion', 'unavailable'),
              'cpu_count': os.cpu_count(), 'sdk_platforms': sorted(path.name for path in (sdk/'platforms').iterdir()),
              'build_tools': sorted(path.name for path in (sdk/'build-tools').iterdir())}
    for line in Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemTotal:'):
            result['memory_total_kib'] = int(line.split()[1])
    return result


class MemorySampler:
    def __init__(self):
        self.stop = threading.Event()
        self.result = {'scope': 'all Java processes visible in /proc; sampled RSS may double-count shared pages',
                       'samples': 0, 'max_java_rss_kib': 0, 'min_available_kib': None}
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.is_set():
            total = 0
            for entry in Path('/proc').iterdir():
                if not entry.name.isdecimal():
                    continue
                try:
                    if (entry/'comm').read_text().strip() in ('java', 'javaw'):
                        status = (entry/'status').read_text()
                        rss = re.search(r'^VmRSS:\s+(\d+)\s+kB$', status, re.M)
                        total += int(rss.group(1)) if rss else 0
                except OSError:
                    pass
            available = re.search(r'^MemAvailable:\s+(\d+)\s+kB$', Path('/proc/meminfo').read_text(), re.M)
            self.result['samples'] += 1
            self.result['max_java_rss_kib'] = max(total, self.result['max_java_rss_kib'])
            if available:
                previous = self.result['min_available_kib']
                self.result['min_available_kib'] = min(int(available.group(1)), previous) if previous is not None else int(available.group(1))
            self.stop.wait(1)


def fixtures(source, temporary):
    if any(key.startswith(('SILO_RELEASE_', 'SILO_GOOGLE_SERVICES_')) for key in os.environ):
        raise ValueError('Production release environment must not be present')
    config = source/'androidApp/google-services.json'
    if config.exists() or config.is_symlink():
        raise ValueError('Checkout already contains Google Services configuration')
    temporary.mkdir(parents=True, exist_ok=False)
    config.write_bytes(FIXTURE_BYTES)
    keystore = temporary/'silo-release.jks'
    password = secrets.token_urlsafe(24)
    environment = dict(os.environ, BENCH_STORE_PASSWORD=password, BENCH_KEY_PASSWORD=password)
    subprocess.run(['keytool', '-genkeypair', '-keystore', str(keystore), '-storetype', 'JKS',
                    '-alias', 'apk-benchmark', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
                    '-dname', 'CN=Silo CI Benchmark', '-storepass:env', 'BENCH_STORE_PASSWORD',
                    '-keypass:env', 'BENCH_KEY_PASSWORD', '-noprompt'], env=environment,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    certificate = subprocess.check_output(['keytool', '-list', '-v', '-keystore', str(keystore),
                                          '-alias', 'apk-benchmark', '-storepass:env', 'BENCH_STORE_PASSWORD'],
                                         env=environment, stderr=subprocess.DEVNULL, text=True)
    digest = re.search(r'SHA256:\s*([0-9A-F:]+)', certificate).group(1).replace(':', '').lower()
    environment.update(SILO_RELEASE_KEYSTORE=str(keystore), SILO_RELEASE_KEYSTORE_PASSWORD=password,
                       SILO_RELEASE_KEY_PASSWORD=password, SILO_RELEASE_KEY_ALIAS='apk-benchmark')
    for key in ('GH_BENCHMARK_READ_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN'):
        environment.pop(key, None)
    return environment, digest


def verify_google_services(source):
    # Google Services 4.5.0 delegates this output directory to AGP 8.10.1.
    values = source/'androidApp/build/generated/res/processReleaseGoogleServices/values/values.xml'
    if not values.is_file():
        raise ValueError('Missing pinned Google Services release values.xml output')
    try:
        resources = {item.attrib.get('name'): item.text for item in ElementTree.parse(values).getroot()}
    except (ElementTree.ParseError, OSError):
        raise ValueError('Cannot read pinned Google Services release values.xml output') from None
    if (resources.get('google_app_id') != FIXTURE['client'][0]['client_info']['mobilesdk_app_id']
            or resources.get('project_id') != FIXTURE['project_info']['project_id']
            or resources.get('google_api_key') != FIXTURE['client'][0]['api_key'][0]['current_key']):
        raise ValueError('Generated Google Services resource contract mismatch')


def verify_bundle(bundle, certificate):
    if not bundle.is_file() or not bundle.stat().st_size:
        raise ValueError('Seed bundle missing or empty')
    verification = subprocess.check_output(['jarsigner', '-verify', str(bundle)],
                                           stderr=subprocess.DEVNULL, text=True)
    if not re.search(r'^jar verified\.\s*$', verification, re.M):
        raise ValueError('Seed bundle is not verified as signed')
    details = subprocess.check_output(['keytool', '-printcert', '-jarfile', str(bundle)],
                                     stderr=subprocess.DEVNULL, text=True)
    digests = [value.replace(':', '').lower()
               for value in re.findall(r'SHA256:\s*([0-9A-F:]+)', details)]
    if digests != [certificate]:
        raise ValueError('Seed bundle signer does not match its disposable fixture')


def check_task_contract(tasks, profile, target, cache):
    if any(task.startswith((':baselineprofile:', ':baselineprofile-tv:')) for task in tasks):
        raise ValueError('Unexpected baseline-profile producer task')
    for module in MODULES[target]:
        required = (['minifyReleaseWithR8', 'signReleaseBundle', 'bundleRelease'] if profile == 'SEED'
                    else ['minifyReleaseWithR8', 'lintVitalAnalyzeRelease', 'lintVitalRelease',
                          'assembleRelease', 'processReleaseResources',
                          'convertShrunkResourcesToBinaryRelease', 'optimizeReleaseResources',
                          'mergeReleaseArtProfile', 'compileReleaseArtProfile'])
        if module == 'androidApp':
            required.append('processReleaseGoogleServices')
        if any(':' + module + ':' + name not in tasks for name in required):
            raise ValueError('Release task contract missing')
        r8 = tasks[':' + module + ':minifyReleaseWithR8']
        allowed = {'EXECUTED'} if cache == 'task-cache-off' else {'EXECUTED', 'FROM-CACHE'}
        if r8 not in allowed:
            raise ValueError('R8 outcome does not match the cache/output contract')


def assert_fresh_outputs(source):
    outputs = ('build', '.gradle', 'androidApp/build', 'androidTvApp/build',
               'shared/build', 'android-shared/build', 'libass-bridge/build',
               'baselineprofile/build', 'baselineprofile-tv/build')
    if any((source / name).exists() or (source / name).is_symlink() for name in outputs):
        raise ValueError('Source checkout contains prior project outputs')


def run_build(arguments):
    source, output = Path(arguments.source).resolve(), Path(arguments.output).resolve()
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != SEALED_SOURCE_SHA:
        raise ValueError('Source checkout revision mismatch')
    if subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip():
        raise ValueError('Source checkout must be clean')
    assert_fresh_outputs(source)
    output.mkdir(parents=True, exist_ok=False)
    gradle_home = Path(os.environ['GRADLE_USER_HOME'])
    observations = toolchain(source)
    setup_start = time.monotonic()
    if arguments.profile != 'SEED':
        receipt = json.loads(Path(arguments.seed_receipt).read_text())
        if (receipt.get('manifest_sha256') != arguments.seed_manifest_sha256
                or receipt.get('provenance') != provenance(arguments.seed_run)):
            raise ValueError('Approved seed restore receipt mismatch')
        for key in ('java', 'gradle', 'agp', 'runner_image'):
            if receipt.get('toolchain', {}).get(key) != observations[key]:
                raise ValueError('Seed and runner toolchain mismatch')
    temporary = Path(os.environ['RUNNER_TEMP'])/'silo-apk-benchmark-fixture'
    config = source/'androidApp/google-services.json'
    sampler, tasks, report = MemorySampler(), {}, None
    config_existed, temporary_existed = config.exists() or config.is_symlink(), temporary.exists()
    try:
        environment, certificate = fixtures(source, temporary)
        setup_seconds = time.monotonic() - setup_start
        sampler.thread.start()
        started = time.monotonic()
        process = subprocess.Popen(command(arguments.profile, arguments.cache, arguments.target), cwd=source,
                                   env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        with (output/'gradle.log').open('w') as log:
            for line in process.stdout:
                log.write(line)
                print(line, end='', flush=True)
                match = re.match(r'> Task (:\S+)(?: (FROM-CACHE|UP-TO-DATE|NO-SOURCE|SKIPPED|FAILED))?\s*$', line.strip())
                if match:
                    tasks[match.group(1)] = match.group(2) or 'EXECUTED'
        returncode = process.wait()
        elapsed = time.monotonic() - started
        sampler.stop.set()
        sampler.thread.join()
        report = {'schema_version': 1, 'source_sha': SEALED_SOURCE_SHA,
                  'controller_sha': os.environ['GITHUB_WORKFLOW_SHA'], 'profile': arguments.profile,
                  'target': arguments.target, 'cache_condition': arguments.cache,
                  'toolchain': observations, 'fixture_sha256': FIXTURE_SHA256,
                  'seed_manifest_sha256': arguments.seed_manifest_sha256 or None,
                  'seed_run_id': arguments.seed_run or None, 'seed_artifact_id': arguments.seed_artifact or None,
                  'seed_restore_seconds': receipt['restore_seconds'] if arguments.profile != 'SEED' else None,
                  'fixture_setup_seconds': setup_seconds, 'elapsed_seconds': elapsed, 'returncode': returncode,
                  'memory': sampler.result, 'task_outcomes': tasks}
        if returncode:
            raise ValueError('Gradle build failed; measurements cannot qualify a candidate')
        verification_start = time.monotonic()
        check_task_contract(tasks, arguments.profile, arguments.target, arguments.cache)
        if 'androidApp' in MODULES[arguments.target]:
            try:
                verify_google_services(source)
            except ValueError as error:
                report['verification_failure'] = str(error)[:160]
                raise
        if arguments.profile == 'SEED':
            for module in MODULES['seed']:
                bundle = source/module/'build/outputs/bundle/release'/(module + '-release.aab')
                verify_bundle(bundle, certificate)
            subprocess.run(['./gradlew', '--stop'], cwd=source, env=environment,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            report['seed'] = export_seed(gradle_home, output/'seed',
                                        provenance(os.environ['GITHUB_RUN_ID']) | {'toolchain': observations})
        else:
            from apk_benchmark_artifacts import inventory
            tools = Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
            report['artifacts'] = inventory(source, MODULES[arguments.target], certificate,
                                            tools/'apksigner', tools/'aapt')
        report['artifact_validation_seconds'] = time.monotonic() - verification_start
        report['qualified'] = True
    finally:
        sampler.stop.set()
        if sampler.thread.is_alive():
            sampler.thread.join()
        if report is not None:
            report.setdefault('qualified', False)
            (output/'report.json').write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')
        if not config_existed:
            config.unlink(missing_ok=True)
        if not temporary_existed and temporary.exists():
            for path in temporary.iterdir():
                path.unlink()
            temporary.rmdir()


def summarize(arguments):
    reports = [json.loads(path.read_text()) for path in Path(arguments.reports).rglob('report.json')]
    targets = {'SEED': {'seed'}, 'M': {'phone', 'tv'}, 'C0': {'combined'}}[arguments.profile]
    if len(reports) != len(targets) or {report.get('target') for report in reports} != targets:
        raise ValueError('Selected layout reports missing or duplicated')
    for report in reports:
        if (report.get('qualified') is not True or report.get('returncode') != 0
                or report.get('source_sha') != SEALED_SOURCE_SHA
                or report.get('controller_sha') != os.environ['GITHUB_WORKFLOW_SHA']
                or report.get('profile') != arguments.profile or report.get('cache_condition') != arguments.cache
                or report.get('fixture_sha256') != FIXTURE_SHA256
                or report.get('seed_manifest_sha256') != (arguments.seed_manifest_sha256 or None)):
            raise ValueError('Selected layout report provenance or qualification mismatch')
    observations = reports[0]['toolchain']
    for report in reports[1:]:
        if report['toolchain'] != observations:
            raise ValueError('Matrix runners do not have identical observed toolchains')
    if arguments.profile != 'SEED':
        from apk_benchmark_artifacts import compare_profiles
        # Validate this layout's complete inventory. External M/C0 comparison is
        # required before a performance result can qualify for adoption.
        compare_profiles([report['artifacts'] for report in reports],
                         [report['artifacts'] for report in reports])
    jobs = api('/actions/runs/' + os.environ['GITHUB_RUN_ID'] + '/jobs?per_page=100')['jobs']
    builds = [job for job in jobs if job.get('name') in {'APK ' + target for target in targets}]
    if len(builds) != len(targets) or any(job.get('conclusion') != 'success' for job in builds):
        raise ValueError('Hosted build-job results do not match selected layout')
    intervals = [(datetime.fromisoformat(job['started_at'].replace('Z', '+00:00')),
                  datetime.fromisoformat(job['completed_at'].replace('Z', '+00:00'))) for job in builds]
    summary = {'schema_version': 1, 'source_sha': SEALED_SOURCE_SHA,
               'controller_sha': os.environ['GITHUB_WORKFLOW_SHA'], 'profile': arguments.profile,
               'cache_condition': arguments.cache, 'seed_manifest_sha256': arguments.seed_manifest_sha256 or None,
               'build_phase_seconds': (max(end for _, end in intervals) - min(start for start, _ in intervals)).total_seconds(),
               'assigned_runner_seconds': sum((end-start).total_seconds() for start, end in intervals),
               'build_start_skew_seconds': (max(start for start, _ in intervals) - min(start for start, _ in intervals)).total_seconds(),
               'build_job_seconds': {job['name']: (datetime.fromisoformat(job['completed_at'].replace('Z', '+00:00')) - datetime.fromisoformat(job['started_at'].replace('Z', '+00:00'))).total_seconds() for job in builds},
               'max_gradle_seconds': max(report['elapsed_seconds'] for report in reports),
               'toolchain': observations, 'report_count': len(reports),
               'peak_java_rss_kib': max(report['memory']['max_java_rss_kib'] for report in reports)}
    output = Path(arguments.output)
    output.mkdir(parents=True, exist_ok=False)
    (output/'summary.json').write_text(json.dumps(summary, sort_keys=True, indent=2) + '\n')
    print(json.dumps(summary, sort_keys=True))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=('validate', 'restore', 'build', 'summarize'))
    for key, default in [('source-sha', ''), ('profile', ''), ('cache', ''), ('target', ''),
                         ('seed-run', ''), ('seed-artifact', ''), ('seed-manifest-sha256', ''),
                         ('seed-dir', ''), ('seed-receipt', ''), ('reports', ''), ('source', 'source'), ('output', 'benchmark-output')]:
        parser.add_argument('--' + key, default=default)
    arguments = parser.parse_args()
    validate(arguments.source_sha, arguments.profile, arguments.cache, arguments.target,
             arguments.seed_run, arguments.seed_artifact, arguments.seed_manifest_sha256)
    if arguments.operation == 'build':
        run_build(arguments)
    elif arguments.operation == 'summarize':
        summarize(arguments)
    elif arguments.operation == 'restore':
        if arguments.profile == 'SEED':
            raise ValueError('SEED cannot restore an earlier seed')
        verify_seed_run(arguments.seed_run, arguments.seed_artifact, os.environ['GITHUB_WORKFLOW_SHA'])
        restore_start = time.monotonic()
        report = restore_seed(arguments.seed_dir, Path(os.environ['GRADLE_USER_HOME']),
                              arguments.seed_manifest_sha256, provenance(arguments.seed_run))
        Path(arguments.seed_receipt).write_text(json.dumps({
            'manifest_sha256': arguments.seed_manifest_sha256,
            'provenance': provenance(arguments.seed_run),
            'toolchain': report['provenance'].get('toolchain', {}),
            'restore_seconds': time.monotonic() - restore_start}, sort_keys=True) + '\n')
    else:
        print('Benchmark inputs match the approved batch')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Subprocess failures must not reveal arguments/environment or raw API errors.
        print('APK benchmark rejected: ' + (str(error) if isinstance(error, ValueError) else type(error).__name__))
        raise SystemExit(1)
