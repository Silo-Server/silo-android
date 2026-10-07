#!/usr/bin/env node
'use strict';

// Local archive phase only. Never execute the upload action's entry module.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const { pipeline } = require('node:stream/promises');

const ACTION_SHA = '330a01c490aca151604b8cf639adc76d48f6c5d4';
const BUNDLE_SHA256 = '168c44946cba03564808c19b83a72601e6d1d6f57081dbd2d6e2ea1cedc522f5';
const STARTUP = 'var __webpack_exports__ = __nccwpck_require__(36664);';

function archiveImplementation(bundlePath) {
  const bytes = fs.readFileSync(bundlePath);
  if (crypto.createHash('sha256').update(bytes).digest('hex') !== BUNDLE_SHA256) {
    throw new Error('Pinned upload action bundle digest mismatch');
  }
  const source = bytes.toString('utf8');
  if (source.split(STARTUP).length !== 2) throw new Error('Pinned startup marker mismatch');
  // The logging facade avoids loading @actions/core HTTP support. All archive,
  // glob, path, stream, CRC and compression modules retain their bundled bytes.
  const startup = `
    __webpack_module_cache__[42186] = { exports: {
      debug() {}, info() {},
      warning() { throw new Error('Pinned archive warning'); },
      error() { throw new Error('Pinned archive error'); }
    } };
    var __webpack_exports__ = {
      search: __nccwpck_require__(8725),
      specification: __nccwpck_require__(17837),
      archive: __nccwpck_require__(69186)
    };`;
  const allowed = new Set([
    'assert', 'buffer', 'constants', 'crypto', 'events', 'fs', 'fs/promises',
    'os', 'path', 'stream', 'stream/web', 'string_decoder', 'timers',
    'url', 'util', 'util/types', 'zlib'
  ]);
  const localRequire = name => {
    if (!allowed.has(name.replace(/^node:/, ''))) {
      throw new Error('Archive attempted a blocked external module');
    }
    return require(name);
  };
  const module = { exports: {} };
  const context = vm.createContext({
    module, exports: module.exports, require: localRequire,
    __dirname: path.dirname(path.resolve(bundlePath)), __filename: path.resolve(bundlePath),
    Buffer, URL, TextEncoder, TextDecoder, setTimeout, clearTimeout, setImmediate, clearImmediate,
    process: {
      env: {}, platform: process.platform, versions: process.versions,
      version: process.version, nextTick: process.nextTick.bind(process),
      cwd: () => process.cwd(), stdout: { write() {} }, stderr: { write() {} }
    }
  });
  context.global = context;
  new vm.Script(source.replace(STARTUP, startup), { filename: 'pinned-upload-archive.js' })
    .runInContext(context, { timeout: 10000 });
  return module.exports;
}

async function measure(bundlePath, inputDirectory, outputPath, level) {
  if (!['0', '1', '6'].includes(String(level))) throw new Error('Unsupported archive level');
  if (process.versions.node.split('.')[0] !== '24') throw new Error('Hosted JavaScript actions require Node 24');
  const implementation = archiveImplementation(bundlePath);
  const search = await implementation.search.findFilesToUpload(path.join(inputDirectory, '*.apk'), false);
  if (search.filesToUpload.length !== 6 || path.resolve(search.rootDirectory) !== path.resolve(inputDirectory)) {
    throw new Error('Expected six release artifact paths in one module');
  }
  implementation.specification.validateRootDirectory(search.rootDirectory);
  const specification = implementation.specification.getUploadZipSpecification(search.filesToUpload, search.rootDirectory);
  const cpuStart = process.cpuUsage();
  const started = process.hrtime.bigint();
  const archive = await implementation.archive.createZipUploadStream(specification, Number(level));
  await pipeline(archive, fs.createWriteStream(outputPath, { flags: 'wx', mode: 0o600 }));
  const wallSeconds = Number(process.hrtime.bigint() - started) / 1e9;
  const cpu = process.cpuUsage(cpuStart);
  return {
    action_sha: ACTION_SHA, bundle_sha256: BUNDLE_SHA256, compression_level: Number(level),
    archive_bytes: fs.statSync(outputPath).size, wall_seconds: wallSeconds,
    cpu_user_seconds: cpu.user / 1e6, cpu_system_seconds: cpu.system / 1e6,
    node: process.version, zlib: process.versions.zlib, platform: process.platform, arch: process.arch,
    input_entries: search.filesToUpload.map(file => path.basename(file)),
    archive_sha256: crypto.createHash('sha256').update(fs.readFileSync(outputPath)).digest('hex')
  };
}

module.exports = { archiveImplementation, measure, ACTION_SHA, BUNDLE_SHA256 };
if (require.main === module) {
  if (process.argv.length !== 6) {
    process.stderr.write('Usage: apk_benchmark_archive.cjs PINNED_BUNDLE INPUT_DIR OUTPUT_ZIP LEVEL\n');
    process.exitCode = 1;
  } else {
    measure(...process.argv.slice(2)).then(result => {
      process.stdout.write(JSON.stringify(result) + '\n');
    }).catch(() => {
      // Never expose input paths, archive content, environment or raw errors.
      process.stderr.write('APK archive phase rejected\n');
      process.exitCode = 1;
    });
  }
}
