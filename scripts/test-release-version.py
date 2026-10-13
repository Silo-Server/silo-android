#!/usr/bin/env python3

import importlib.util
from pathlib import Path
import unittest


SCRIPT_DIR = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("release_version", SCRIPT_DIR / "release-version.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

# The repository's tags before the first vX.Y.Z-beta.B release.
LEGACY_TAGS = [
    "v0.4.1", "v1.0.1", "v1.0.0", "v1.0.0+3", "v1.0.0-rc.1+4", "v1.0.0-rc.6+9",
    "v1.0.0+11", "v1.0.0+17", "v1.0.1-rc.1+2", "v1.0.0+18", "v1.0.1-rc.2+3",
    "v1.0.0+19", "v1.0.0+20", "v1.0.0+21",
]


class NextReleaseTest(unittest.TestCase):
    def test_first_minor_release_continues_the_build_counter(self):
        result = release.next_release(LEGACY_TAGS, "minor")
        self.assertEqual("1.1.0", result["version"])
        self.assertEqual("22", result["build"])
        self.assertEqual("v1.1.0-beta.22", result["tag"])
        self.assertEqual("v1.0.0+21", result["previous_tag"])
        self.assertEqual(str(100_000_000 + 10_100_000 + 22), result["version_code"])

    def test_first_build_outranks_every_older_sideload_code(self):
        result = release.next_release(LEGACY_TAGS, "minor")
        # v1.0.1-rc.2+3 shipped as a sideload APK with base 110001003.
        self.assertGreater(int(result["version_code"]), 110_001_003)

    def test_build_continues_the_unreleased_version(self):
        tags = LEGACY_TAGS + ["v1.1.0-beta.22", "v1.1.0-beta.23"]
        result = release.next_release(tags, "build")
        self.assertEqual("v1.1.0-beta.24", result["tag"])
        self.assertEqual("v1.1.0-beta.23", result["previous_tag"])

    def test_build_continues_the_newest_unreleased_version(self):
        tags = LEGACY_TAGS + ["v1.1.0-beta.22", "v1.1.0", "v1.1.1-beta.23", "v1.2.0-beta.24"]
        self.assertEqual("v1.2.0-beta.25", release.next_release(tags, "build")["tag"])

    def test_build_continues_testing_after_a_hotfix_ships(self):
        tags = LEGACY_TAGS + ["v1.1.0-beta.22", "v1.1.0", "v1.2.0-beta.23", "v1.1.1-beta.24", "v1.1.1"]
        self.assertEqual("v1.2.0-beta.25", release.next_release(tags, "build")["tag"])

    def test_a_draft_store_release_counts_as_released(self):
        tags = LEGACY_TAGS + ["v1.1.0-beta.22", "v1.1.0-beta.23"]
        with self.assertRaisesRegex(release.ReleaseError, "bump=minor"):
            release.next_release(tags, "build", drafts=["v1.1.0"])
        self.assertEqual("v1.2.0-beta.24", release.next_release(tags, "minor", drafts=["v1.1.0"])["tag"])

    def test_build_refuses_a_released_version(self):
        tags = LEGACY_TAGS + ["v1.1.0-beta.22", "v1.1.0"]
        with self.assertRaisesRegex(release.ReleaseError, "bump=minor"):
            release.next_release(tags, "build")

    def test_build_without_any_tester_build_asks_for_a_version(self):
        with self.assertRaisesRegex(release.ReleaseError, "Pass the version"):
            release.next_release(LEGACY_TAGS, "build")

    def test_tags_with_build_metadata_are_not_store_releases(self):
        # With the July v1.0.0 and v1.0.1 releases renamed to +N tags, 1.0.0
        # can still become the first store release.
        tags = [t for t in LEGACY_TAGS if t not in ("v1.0.0", "v1.0.1")] + ["v1.0.0+1", "v1.0.1+0"]
        result = release.next_release(tags, "build", "1.0.0")
        self.assertEqual("v1.0.0-beta.22", result["tag"])
        self.assertEqual("v1.0.0+21", result["previous_tag"])
        # One above v1.0.0+21 (base 110000021), the highest code Play has seen.
        self.assertEqual("110000022", result["version_code"])

    def test_minor_and_patch_bump_the_latest_release(self):
        tags = LEGACY_TAGS + ["v1.1.0-beta.22", "v1.1.0"]
        self.assertEqual("v1.2.0-beta.23", release.next_release(tags, "minor")["tag"])
        self.assertEqual("v1.1.1-beta.23", release.next_release(tags, "patch")["tag"])

    def test_explicit_version_must_be_newer_than_the_latest_release(self):
        self.assertEqual("v1.3.0-beta.22", release.next_release(LEGACY_TAGS, "build", "1.3.0")["tag"])
        with self.assertRaisesRegex(release.ReleaseError, "not newer"):
            release.next_release(LEGACY_TAGS, "build", "1.0.1")
        with self.assertRaisesRegex(release.ReleaseError, "look like"):
            release.next_release(LEGACY_TAGS, "build", "1.3")

    def test_unrelated_tags_are_ignored(self):
        result = release.next_release(LEGACY_TAGS + ["wip/x", "source-abc", "v2"], "minor")
        self.assertEqual("v1.1.0-beta.22", result["tag"])

    def test_build_number_limit(self):
        with self.assertRaisesRegex(release.ReleaseError, "1-999"):
            release.next_release(["v1.0.0", "v1.1.0-beta.999"], "build")


class BetaReleaseTest(unittest.TestCase):
    def test_beta_tag_resolves_to_its_store_release(self):
        result = release.beta_release("v1.1.0-beta.22")
        self.assertEqual("v1.1.0", result["release_tag"])
        self.assertEqual("22", result["build"])
        self.assertEqual("110100022", result["version_code"])

    def test_other_tags_are_rejected(self):
        for name in ("v1.1.0", "v1.0.0+21", "v1.0.1-rc.2+3", "beta.22"):
            with self.subTest(name), self.assertRaises(release.ReleaseError):
                release.beta_release(name)


GENERATED = """## What's Changed
### 🚀 Features
* feat(requests): let viewers get notified about requests by @dev in https://github.com/Silo-Server/silo-android/pull/559
### 🐛 Fixes
* fix(tv)!: let Down leave the search field by @dev in https://github.com/Silo-Server/silo-android/pull/561
### Other changes
* ci: cache builds by @dev in https://github.com/Silo-Server/silo-android/pull/500
* feat(profiles): ask who's watching at launch by @dev in https://github.com/Silo-Server/silo-android/pull/543

## New Contributors
* @new made their first contribution in https://github.com/Silo-Server/silo-android/pull/501

**Full Changelog**: https://github.com/Silo-Server/silo-android/compare/v1.0.0+21...v1.1.0-beta.22
"""


class StoreNotesTest(unittest.TestCase):
    def test_keeps_features_and_fixes_as_plain_text(self):
        self.assertEqual(
            "• Let viewers get notified about requests\n• Let Down leave the search field\n"
            "• Ask who's watching at launch",
            release.store_notes(GENERATED, 500),
        )

    def test_only_a_leading_prefix_is_removed(self):
        notes = "### 🐛 Fixes\n* Fix crash when the server says: hello by @dev in https://x/1\n"
        self.assertEqual("• Fix crash when the server says: hello", release.store_notes(notes, 500))

    def test_without_user_facing_changes(self):
        self.assertEqual("Bug fixes and improvements.", release.store_notes("## What's Changed\n", 500))

    def test_fits_the_limit(self):
        many = "### 🐛 Fixes\n" + "\n".join(f"* fix: change number {n} by @dev in https://x/{n}" for n in range(100))
        notes = release.store_notes(many, 500)
        self.assertLessEqual(len(notes), 500)
        self.assertTrue(notes.endswith("• And more."))


if __name__ == "__main__":
    unittest.main()
