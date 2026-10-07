import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import apk_benchmark_seed as seed


class SeedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / 'home'
        (self.home / 'caches').mkdir(parents=True)
        (self.home / 'wrapper').mkdir()
        (self.home / 'caches/task.bin').write_bytes(b'task-output')
        (self.home / 'wrapper/distribution.bin').write_bytes(b'wrapper')
        (self.home / 'daemon').mkdir()
        (self.home / 'daemon/private.log').write_text('excluded')
        self.provenance = {
            'repository': 'Silo-Server/silo-android', 'source_sha': 'a'*40,
            'controller_sha': 'b'*40, 'run_id': '123', 'seed_mode': 'prior-release-warm',
            'seed_metadata': seed.SEED_METADATA, 'fixture_sha256': 'c'*64,
            'toolchain': {'java': 'test21'},
        }
        self.bundle = self.root / 'bundle'
        self.manifest = seed.export_seed(self.home, self.bundle, self.provenance)

    def restore(self, provenance=None, digest=None):
        return seed.restore_seed(self.bundle, self.root / 'restored',
                                 digest or self.manifest['manifest_sha256'],
                                 provenance or self.provenance)

    def rewrite_archive(self, members):
        with tarfile.open(self.bundle/'seed.tar.gz', 'w:gz') as stream:
            for name, kind, size in members:
                entry = tarfile.TarInfo(name)
                entry.type, entry.size = kind, size
                if kind == tarfile.SYMTYPE:
                    entry.linkname = '/tmp/unsafe'
                stream.addfile(entry, io.BytesIO(b'x'*size) if kind == tarfile.REGTYPE else None)
        self.manifest['archive_sha256'] = seed.sha256(self.bundle/'seed.tar.gz')
        self.manifest['entries'] = len(members)
        self.manifest['bytes'] = sum(item[2] for item in members)
        (self.bundle/'seed.json').write_text(json.dumps(self.manifest))
        return seed.sha256(self.bundle/'seed.json')

    def test_round_trip_exact_seed_and_exclusions(self):
        report = self.restore()
        self.assertEqual(report['manifest_sha256'], self.manifest['manifest_sha256'])
        self.assertEqual((self.root/'restored/caches/task.bin').read_bytes(), b'task-output')
        self.assertFalse((self.root/'restored/daemon').exists())

    def test_manifest_mutation_and_missing_identity_rejected(self):
        for field in seed.IDENTITY:
            with self.subTest(field=field):
                expected = dict(self.provenance)
                del expected[field]
                with self.assertRaises(ValueError):
                    self.restore(provenance=expected)
        (self.bundle/'seed.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            self.restore()

    def test_provenance_mismatch_and_archive_corruption(self):
        expected = self.provenance | {'source_sha': 'd'*40}
        with self.assertRaisesRegex(ValueError, 'provenance mismatch'):
            self.restore(provenance=expected)
        (self.bundle/'seed.tar.gz').write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'archive digest mismatch'):
            self.restore()

    def test_nonfresh_destination_rejected(self):
        destination = self.root/'restored'
        destination.mkdir()
        (destination/'unexpected').write_text('existing')
        with self.assertRaisesRegex(ValueError, 'fresh'):
            self.restore()

    def test_unsafe_entries_rejected_before_extraction(self):
        roots = [('caches', tarfile.DIRTYPE, 0), ('wrapper', tarfile.DIRTYPE, 0)]
        bad = [('caches/../escape', tarfile.REGTYPE, 1),
               ('/caches/absolute', tarfile.REGTYPE, 1),
               ('daemon/private', tarfile.REGTYPE, 1),
               ('caches/link', tarfile.SYMTYPE, 0),
               ('caches/hard', tarfile.LNKTYPE, 0),
               ('caches/special', tarfile.FIFOTYPE, 0),
               ('caches', tarfile.DIRTYPE, 0)]
        for member in bad:
            with self.subTest(member=member):
                digest = self.rewrite_archive(roots + [member])
                with self.assertRaises(ValueError):
                    self.restore(digest=digest)
                self.assertFalse((self.root/'restored').exists())

    def test_archive_budget_and_inventory_mismatch(self):
        digest = self.rewrite_archive([('caches', tarfile.DIRTYPE, 0),
                                       ('wrapper', tarfile.DIRTYPE, 0),
                                       ('caches/large', tarfile.REGTYPE, 5)])
        with patch.object(seed, 'MAX_BYTES', 4), self.assertRaisesRegex(ValueError, 'budget'):
            self.restore(digest=digest)
        self.manifest['entries'] = 100
        (self.bundle/'seed.json').write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, 'inventory mismatch'):
            self.restore(digest=seed.sha256(self.bundle/'seed.json'))

    def test_export_refuses_links(self):
        (self.home/'caches/symlink').symlink_to('/tmp/unsafe')
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            seed.export_seed(self.home, self.root/'second', self.provenance)


if __name__ == '__main__':
    unittest.main()
