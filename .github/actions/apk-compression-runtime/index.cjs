'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function main() {
  if (process.versions.node.split('.')[0] !== '24') throw new Error('Unexpected runtime');
  if (process.env.GITHUB_EVENT_NAME !== 'workflow_dispatch' ||
      process.env.GITHUB_REPOSITORY !== 'Silo-Server/silo-android' ||
      process.env.GITHUB_REF !== 'refs/heads/ci/android-apk-layout-pilot' ||
      process.env.BENCH_PROFILE !== 'C0' || process.env.BENCH_OBSERVE_COMPRESSION !== 'true') {
    throw new Error('Unexpected optional C0 context');
  }
  if (!/^[0-9a-f]{40}$/.test(process.env.GITHUB_WORKFLOW_SHA || '')) throw new Error('Controller identity missing');
  if (!/^[0-9]{8}\.[0-9]+\.[0-9]+$/.test(process.env.ImageVersion || '')) throw new Error('Actual image missing');
  if (!/^[1-9][0-9]{0,19}$/.test(process.env.GITHUB_RUN_ID || '') || process.env.GITHUB_RUN_ATTEMPT !== '1') {
    throw new Error('Actual first attempt missing');
  }
  const executable = fs.realpathSync(process.execPath);
  const temporary = fs.realpathSync(process.env.RUNNER_TEMP);
  const output = fs.realpathSync(process.env.GITHUB_OUTPUT);
  if ([executable, temporary, output].some(value => /[\r\n\0]/.test(value) || value.length > 4096)) {
    throw new Error('Unsafe runtime path');
  }
  const receipt = path.join(temporary, 'apk-compression-node-runtime.json');
  const runtime = {schema_version: 1, controller_sha: process.env.GITHUB_WORKFLOW_SHA,
    run_id: process.env.GITHUB_RUN_ID, run_attempt: process.env.GITHUB_RUN_ATTEMPT,
    runner_image: process.env.ImageVersion, node_executable: executable,
    executable_sha256: crypto.createHash('sha256').update(fs.readFileSync(executable)).digest('hex'),
    node: process.version, zlib: process.versions.zlib, platform: process.platform, arch: process.arch};
  fs.writeFileSync(receipt, JSON.stringify(runtime) + '\n', {flag: 'wx', mode: 0o600});
  fs.appendFileSync(output, `node=${executable}\nreceipt=${receipt}\n`);
  process.stdout.write('Recorded actual selected Node24 action runtime.\n');
}
try { main(); } catch {
  process.stderr.write('APK action runtime observation rejected\n');
  process.exitCode = 1;
}
