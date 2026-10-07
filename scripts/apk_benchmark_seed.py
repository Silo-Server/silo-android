"""Immutable benchmark cache snapshots; never read production cache identities."""
import hashlib
import json
import re
import tarfile
from pathlib import Path, PurePosixPath

MAX_BYTES = 20 * 1024**3
MAX_ENTRIES = 300_000
IDENTITY = {
    'repository', 'source_sha', 'controller_sha', 'run_id', 'seed_mode',
    'seed_metadata', 'fixture_sha256',
}
SEED_METADATA = {'version_name': '0.0.0', 'base_code': 100000001,
                 'build_number': 1, 'channel': 'beta', 'display_version': None}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def identity(value):
    if not isinstance(value, dict) or not IDENTITY <= value.keys():
        raise ValueError('Incomplete seed provenance')
    result = {key: value[key] for key in IDENTITY}
    if result['repository'] != 'Silo-Server/silo-android':
        raise ValueError('Unexpected seed repository')
    for key, length in [('source_sha', 40), ('controller_sha', 40), ('fixture_sha256', 64)]:
        if not isinstance(result[key], str) or not re.fullmatch('[0-9a-f]{' + str(length) + '}', result[key]):
            raise ValueError('Invalid seed identity')
    if not re.fullmatch(r'[1-9][0-9]{0,19}', str(result['run_id'])):
        raise ValueError('Invalid seed run')
    if result['seed_mode'] != 'prior-release-warm' or result['seed_metadata'] != SEED_METADATA:
        raise ValueError('Unexpected seed metadata')
    return result


def safe_name(name):
    path = PurePosixPath(name)
    if (not name or not path.parts or '\\' in name or path.is_absolute() or '..' in path.parts
            or '.' in name.split('/') or path.parts[0] not in ('caches', 'wrapper')
            or name != str(path)):
        raise ValueError('Unsafe seed archive path')
    return path


def export_seed(gradle_home, destination, provenance):
    provenance = identity(provenance) | {'toolchain': provenance.get('toolchain', {})}
    gradle_home, destination = Path(gradle_home), Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    archive = destination / 'seed.tar.gz'
    total, count = 0, 0
    with tarfile.open(archive, 'w:gz') as output:
        for top in ('caches', 'wrapper'):
            root = gradle_home / top
            if not root.is_dir() or root.is_symlink():
                raise ValueError('Required seed cache directory missing')
            for path in sorted([root, *root.rglob('*')]):
                name = path.relative_to(gradle_home).as_posix()
                safe_name(name)
                if path.is_symlink() or not (path.is_file() or path.is_dir()):
                    raise ValueError('Unsupported seed cache entry')
                if path.is_file() and path.stat().st_nlink != 1:
                    raise ValueError('Hardlinked seed cache entry')
                # Locks are process state, and can vary without changing cached outputs.
                if path.is_file() and path.name.endswith('.lock'):
                    continue
                count += 1
                total += path.stat().st_size if path.is_file() else 0
                if count > MAX_ENTRIES or total > MAX_BYTES:
                    raise ValueError('Seed archive budget exceeded')
                info = output.gettarinfo(str(path), arcname=name)
                info.uid = info.gid = 0
                info.uname = info.gname = ''
                info.mtime = 0
                if path.is_file():
                    with path.open('rb') as stream:
                        output.addfile(info, stream)
                else:
                    output.addfile(info)
    manifest = {'schema_version': 1, 'provenance': provenance,
                'archive_sha256': sha256(archive), 'entries': count, 'bytes': total}
    manifest_path = destination / 'seed.json'
    manifest_path.write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n')
    return manifest | {'manifest_sha256': sha256(manifest_path)}


def restore_seed(directory, gradle_home, expected_manifest_sha256, expected_provenance):
    directory, gradle_home = Path(directory), Path(gradle_home)
    if not re.fullmatch(r'[0-9a-f]{64}', expected_manifest_sha256):
        raise ValueError('Invalid approved seed digest')
    manifest_path, archive = directory / 'seed.json', directory / 'seed.tar.gz'
    if not manifest_path.is_file() or not archive.is_file() or manifest_path.is_symlink() or archive.is_symlink():
        raise ValueError('Seed files missing or linked')
    if manifest_path.stat().st_size > 64 * 1024 or sha256(manifest_path) != expected_manifest_sha256:
        raise ValueError('Seed manifest digest mismatch')
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(manifest, dict):
        raise ValueError('Invalid seed manifest')
    if (manifest.get('schema_version') != 1
            or identity(manifest.get('provenance')) != identity(expected_provenance)):
        raise ValueError('Seed provenance mismatch')
    if archive.stat().st_size > MAX_BYTES + MAX_ENTRIES * 1024:
        raise ValueError('Seed archive budget exceeded')
    if sha256(archive) != manifest.get('archive_sha256'):
        raise ValueError('Seed archive digest mismatch')
    if gradle_home.exists() and (gradle_home.is_symlink() or any(gradle_home.iterdir())):
        raise ValueError('Seed destination must be fresh')
    total, seen, members = 0, set(), []
    with tarfile.open(archive, 'r:gz') as source:
        for member in source:
            safe_name(member.name)
            if member.name in seen or not (member.isfile() or member.isdir()):
                raise ValueError('Duplicate or unsupported seed archive entry')
            if member.size < 0 or (member.isdir() and member.size):
                raise ValueError('Invalid seed entry size')
            seen.add(member.name)
            total += member.size
            if len(seen) > MAX_ENTRIES or total > MAX_BYTES:
                raise ValueError('Seed archive budget exceeded')
            members.append(member)
        if (len(seen) != manifest.get('entries') or total != manifest.get('bytes')
                or not {'caches', 'wrapper'} <= seen):
            raise ValueError('Seed inventory mismatch')
        # Verify all members before writing any file. Tar metadata never controls extraction.
        gradle_home.mkdir(parents=True, exist_ok=True)
        for member in members:
            target = gradle_home.joinpath(*PurePosixPath(member.name).parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as incoming, target.open('xb') as output:
                    for chunk in iter(lambda: incoming.read(1024 * 1024), b''):
                        output.write(chunk)
                target.chmod(member.mode & 0o777)
    return manifest | {'manifest_sha256': expected_manifest_sha256}
