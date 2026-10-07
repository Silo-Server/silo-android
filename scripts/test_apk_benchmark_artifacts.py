"""Fixtures exercise actual output metadata/ZIPs; SDK verification is mocked.

These tests do not assert cryptographic validity of fixture bytes. Production
inventory always runs apksigner/aapt; tests assert those calls and their failures.
"""

import copy
import json
import struct
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

import apk_benchmark_artifacts as artifacts


CERT = "12" * 32
OTHER_CERT = "ab" * 32
APKSIGNER = Path("/sdk/apksigner")
AAPT = Path("/sdk/aapt")


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / "source"
        self.tool_calls = []
        self.tool_error = None
        self.certificates = {}
        self.badging_overrides = {}
        self.signing_overrides = {}
        self.make_source(self.source)
        self.run_patch = patch.object(artifacts.subprocess, "run", side_effect=self.fake_run)
        self.run_patch.start()
        self.addCleanup(self.run_patch.stop)

    def make_source(self, source, certificate=CERT, profiles=True, timestamp=(2026, 10, 7, 1, 0, 0)):
        self.certificates[str(source.resolve())] = certificate
        for module, config in artifacts.MODULES.items():
            module_root = source / module
            build_config = module_root / "build/generated/source/buildConfig/release" / Path(config["namespace"].replace(".", "/")) / "BuildConfig.java"
            build_config.parent.mkdir(parents=True)
            display = 'public static final String DISPLAY_VERSION = "0.0.1-ci-benchmark";\n' if module == "androidTvApp" else ""
            build_config.write_text(
                f'package {config["namespace"]};\n'
                'public final class BuildConfig {\n'
                'public static final String BUILD_NUMBER = "1";\n'
                'public static final String RELEASE_CHANNEL = "sideload";\n'
                + display + '}\n'
            )
            mapping = module_root / "build/outputs/mapping/release/mapping.txt"
            mapping.parent.mkdir(parents=True)
            mapping.write_text(f"{module}.Example -> a:\n")
            apk_root = module_root / "build/outputs/apk/release"
            apk_root.mkdir(parents=True)
            elements = []
            for abi in artifacts.OUTPUTS:
                filename = f"{module}-{abi}-release.apk"
                entries = {
                    "AndroidManifest.xml": f"manifest for {module}".encode(),
                    "classes.dex": f"dex for {module}".encode(),
                    "classes10.dex": b"additional dex",
                    "resources.arsc": f"resources for {module}".encode(),
                    "res/xml/config.xml": b"resource",
                    "META-INF/com/example/MANIFEST.MF": b"ordinary nested metadata",
                    "META-INF/services/example.Service": b"example.Provider\n",
                    "META-INF/kotlinx_coroutines_android.version": b"1.10.2",
                    "META-INF/MANIFEST.MF": b"manifest signed by " + certificate.encode(),
                    "META-INF/CERT.SF": b"signature file " + certificate.encode(),
                    "META-INF/CERT.RSA": b"signature block " + certificate.encode(),
                }
                if profiles:
                    entries["assets/dexopt/baseline.prof"] = f"baseline profile {module}".encode()
                    entries["assets/dexopt/baseline.profm"] = f"profile metadata {module}".encode()
                for native_abi in artifacts.ABIS if abi == "universal" else [abi]:
                    entries[f"lib/{native_abi}/libexample.so"] = f"native {module} {native_abi}".encode()
                self.write_zip(apk_root / filename, entries, timestamp)
                elements.append({
                    "type": "SINGLE" if abi == "universal" else "ONE_OF_MANY",
                    "filters": [] if abi == "universal" else [{"filterType": "ABI", "value": abi}],
                    "attributes": [], "versionCode": config["version_code"],
                    "versionName": artifacts.VERSION_NAME, "outputFile": filename,
                })
            (apk_root / "output-metadata.json").write_text(json.dumps({
                "version": 3, "artifactType": {"type": "APK", "kind": "Directory"},
                "applicationId": artifacts.PACKAGE, "variantName": "release", "elements": elements,
            }))

    @staticmethod
    def write_zip(path, entries, timestamp=(2026, 10, 7, 1, 0, 0)):
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in entries.items():
                info = zipfile.ZipInfo(name, date_time=timestamp)
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)

    def apk(self, module="androidApp", abi="arm64-v8a", source=None):
        return (source or self.source) / module / "build/outputs/apk/release" / f"{module}-{abi}-release.apk"

    def change_zip(self, path, updates=None, removals=()):
        with zipfile.ZipFile(path) as archive:
            entries = {info.filename: archive.read(info) for info in archive.infolist()}
        for name in removals:
            entries.pop(name)
        entries.update(updates or {})
        self.write_zip(path, entries)

    def change_metadata(self, change, module="androidApp"):
        path = self.source / module / "build/outputs/apk/release/output-metadata.json"
        metadata = json.loads(path.read_text())
        change(metadata)
        path.write_text(json.dumps(metadata))

    def fake_run(self, arguments, **kwargs):
        self.tool_calls.append(arguments)
        self.assertEqual(kwargs, {"capture_output": True, "text": True, "timeout": 120, "check": False})
        apk = Path(arguments[-1])
        module = next(part for part in apk.parts if part in artifacts.MODULES)
        source = apk.parents[5]
        config = artifacts.MODULES[module]
        if arguments[0] == str(APKSIGNER):
            self.assertEqual(arguments[1:-1], ["verify", "--verbose", "--print-certs"])
            cert = self.certificates[str(source.resolve())]
            output = (
                "Verifies\n"
                "Verified using v1 scheme (JAR signing): true\n"
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "Verified using v3 scheme (APK Signature Scheme v3): true\n"
                "Verified using v3.1 scheme (APK Signature Scheme v3.1): false\n"
                "Verified using v4 scheme (APK Signature Scheme v4): false\n"
                "Number of signers: 1\n"
                f"Signer #1 certificate SHA-256 digest: {cert}\n"
            )
            output = self.signing_overrides.get(module, output)
        else:
            self.assertEqual(arguments[0], str(AAPT))
            self.assertEqual(arguments[1:-1], ["dump", "badging"])
            output = (
                f"package: name='{artifacts.PACKAGE}' versionCode='{config['version_code']}' versionName='0.0.1'\n"
                "feature-group: label=''\n"
                f"  uses-feature: name='{config['required_feature']}'\n"
                + ("  uses-feature-not-required: name='android.hardware.touchscreen'\n" if module == "androidTvApp" else "")
            )
            output = self.badging_overrides.get(module, output)
        if self.tool_error:
            if isinstance(self.tool_error, BaseException):
                raise self.tool_error
            return subprocess.CompletedProcess(arguments, self.tool_error, "PRIVATE_TOOL_OUTPUT", "PRIVATE_TOOL_ERROR")
        return subprocess.CompletedProcess(arguments, 0, output, "")

    def inventory(self, source=None, modules=None, certificate=CERT):
        return artifacts.inventory(source or self.source, modules or list(artifacts.MODULES), certificate, APKSIGNER, AAPT)

    def test_inventory_checks_actual_files_and_both_sdk_tools_for_all_eight_apks(self):
        report = self.inventory()
        self.assertEqual(report["schema_version"], 1)
        self.assertEqual(report["certificate_sha256"], CERT)
        self.assertEqual(len(self.tool_calls), 16)
        self.assertEqual(report["modules"]["androidTvApp"]["build_config"]["DISPLAY_VERSION"], "0.0.1-ci-benchmark")
        for module, data in report["modules"].items():
            self.assertEqual(set(data["apks"]), set(artifacts.OUTPUTS))
            for abi, apk in data["apks"].items():
                self.assertEqual(apk["native_abis"], sorted(artifacts.ABIS if abi == "universal" else [abi]))
                self.assertEqual(apk["package"]["required_features"], [artifacts.MODULES[module]["required_feature"]])
                self.assertTrue(apk["signature_schemes"]["v1"])
                self.assertEqual(apk["excluded_v1_entries"], ["META-INF/CERT.RSA", "META-INF/CERT.SF", "META-INF/MANIFEST.MF"])
                self.assertNotIn("META-INF/MANIFEST.MF", apk["entries"])
                self.assertIn("META-INF/com/example/MANIFEST.MF", apk["entries"])
                self.assertIn("META-INF/services/example.Service", apk["entries"])
                self.assertIn("classes10.dex", apk["entries"])
        json.dumps(report)  # Reports remain plain JSON data.

    def test_invalid_universal_native_entry_retains_actual_zip_inventory(self):
        path = self.apk(abi="universal")
        entry = "lib/x86/libdependency.so"
        self.change_zip(path, {entry: b"actual dependency bytes"})
        with self.assertRaises(artifacts.NativePayloadContractError) as caught:
            self.inventory(modules=["androidApp"])
        error = caught.exception
        self.assertEqual(str(error), "androidApp/universal: invalid native library entry")
        self.assertNotIn(entry, str(error))
        self.assertEqual(error.details["label"], "androidApp/universal")
        self.assertEqual(error.details["invalid_entry"], entry)
        with zipfile.ZipFile(path) as archive:
            expected = [{"path": info.filename, "size": info.file_size,
                         "sha256": artifacts._digest(archive.read(info))}
                        for info in archive.infolist() if info.filename.startswith("lib/")]
        self.assertEqual(error.details["native_entries"], sorted(expected, key=lambda value: value["path"]))
        self.assertEqual(len(error.details["native_entries"]), 4)

    def test_native_diagnostic_rejects_count_and_path_budget_excess(self):
        for entries in [{f"lib/x86/lib{index}.so": b"native" for index in range(129)},
                        {"lib/x86/" + "n" * 513 + ".so": b"native"}]:
            with self.subTest(count=len(entries)):
                path = self.apk(abi="universal")
                original = path.read_bytes()
                self.change_zip(path, entries)
                with self.assertRaisesRegex(ValueError, "native APK diagnostic exceeds its budget"):
                    self.inventory(modules=["androidApp"])
                path.write_bytes(original)

    def test_matrix_aggregation_matches_combined_with_independent_signing_and_zip_timestamps(self):
        combined_source = Path(self.temp.name) / "combined"
        self.make_source(combined_source, OTHER_CERT, timestamp=(2026, 10, 7, 2, 0, 0))
        matrix = [self.inventory(modules=[module]) for module in artifacts.MODULES]
        combined = self.inventory(combined_source, certificate=OTHER_CERT)
        left = matrix[0]["modules"]["androidApp"]["apks"]["arm64-v8a"]
        right = combined["modules"]["androidApp"]["apks"]["arm64-v8a"]
        self.assertNotEqual(left["signed_sha256"], right["signed_sha256"])
        self.assertNotEqual(left["zip_metadata"], right["zip_metadata"])
        self.assertEqual(left["payload_sha256"], right["payload_sha256"])
        self.assertEqual(artifacts.compare_profiles(matrix, [combined])["apk_count"], 8)

    def test_legitimate_absence_of_packaged_profiles_matches(self):
        other = Path(self.temp.name) / "without-profile"
        self.make_source(other, profiles=False)
        report = self.inventory(other)
        self.assertEqual(report["modules"]["androidApp"]["apks"]["universal"]["profiles"], {})
        self.assertTrue(artifacts.compare_profiles([report], [copy.deepcopy(report)])["equivalent"])

    def test_certificate_colon_format_is_normalized(self):
        colon_cert = ":".join(["12"] * 32)
        self.assertEqual(self.inventory(certificate=colon_cert)["certificate_sha256"], CERT)

    def test_missing_or_empty_apk_is_rejected(self):
        for empty in (False, True):
            with self.subTest(empty=empty):
                path = self.apk()
                original = path.read_bytes()
                path.write_bytes(b"") if empty else path.unlink()
                with self.assertRaisesRegex(ValueError, "nonempty ordinary file"):
                    self.inventory()
                path.write_bytes(original)

    def test_extra_apk_is_rejected(self):
        self.apk().with_name("untracked.apk").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "unexpected APK files"):
            self.inventory()

    def test_extra_or_missing_element_is_rejected(self):
        metadata = self.source / "androidApp/build/outputs/apk/release/output-metadata.json"
        original = metadata.read_text()
        for count in (3, 5):
            with self.subTest(count=count):
                data = json.loads(original)
                data["elements"] = data["elements"][:count] if count == 3 else data["elements"] + [copy.deepcopy(data["elements"][0])]
                metadata.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, "four release APK metadata elements"):
                    self.inventory()

    def test_duplicate_abi_or_filename_is_rejected(self):
        metadata = self.source / "androidApp/build/outputs/apk/release/output-metadata.json"
        original = metadata.read_text()
        for field in ("filters", "outputFile"):
            with self.subTest(field=field):
                data = json.loads(original)
                data["elements"][1][field] = data["elements"][0][field]
                metadata.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, "duplicate APK output"):
                    self.inventory()

    def test_unknown_and_duplicate_filters_are_rejected(self):
        metadata = self.source / "androidApp/build/outputs/apk/release/output-metadata.json"
        original = metadata.read_text()
        bad_filters = [
            [{"filterType": "DENSITY", "value": "hdpi"}],
            [{"filterType": "ABI", "value": "x86"}],
            [{"filterType": "ABI", "value": "arm64-v8a", "extra": True}],
            [{"filterType": "ABI", "value": "arm64-v8a"}] * 2,
        ]
        for filters in bad_filters:
            with self.subTest(filters=filters):
                data = json.loads(original)
                data["elements"][0]["filters"] = filters
                metadata.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, "unknown APK output filter"):
                    self.inventory()

    def test_output_filename_cannot_escape_release_directory(self):
        metadata = self.source / "androidApp/build/outputs/apk/release/output-metadata.json"
        original = metadata.read_text()
        for filename in ("../outside.apk", "/tmp/outside.apk", "nested/outside.apk", r"..\outside.apk", "C:outside.apk", "bad\x00.apk"):
            with self.subTest(filename=filename):
                data = json.loads(original)
                data["elements"][0]["outputFile"] = filename
                metadata.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, "unsafe APK output filename"):
                    self.inventory()

    def test_symlink_output_is_rejected(self):
        path = self.apk()
        outside = Path(self.temp.name) / "outside.apk"
        outside.write_bytes(path.read_bytes())
        path.unlink()
        path.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "ordinary file inside source"):
            self.inventory()

    def test_wrong_output_version_or_variant_is_rejected(self):
        self.change_metadata(lambda data: data["elements"][0].update(versionCode=200002003))
        with self.assertRaisesRegex(ValueError, "metadata version"):
            self.inventory()
        self.change_metadata(lambda data: data.update(variantName="debug"))
        with self.assertRaisesRegex(ValueError, "release APK metadata"):
            self.inventory()

    def test_wrong_generated_build_config_is_rejected(self):
        for module, field, old, new in (
            ("androidApp", "RELEASE_CHANNEL", '"sideload"', '"beta"'),
            ("androidApp", "BUILD_NUMBER", '"1"', '"2"'),
            ("androidTvApp", "DISPLAY_VERSION", '"0.0.1-ci-benchmark"', '"0.0.1"'),
        ):
            with self.subTest(module=module, field=field):
                path = next((self.source / module / "build/generated/source/buildConfig/release").rglob("BuildConfig.java"))
                original = path.read_text()
                path.write_text(original.replace(old, new))
                with self.assertRaisesRegex(ValueError, field):
                    self.inventory()
                path.write_text(original)

    def test_r8_mapping_must_exist_and_be_nonempty(self):
        (self.source / "androidApp/build/outputs/mapping/release/mapping.txt").write_text("")
        with self.assertRaisesRegex(ValueError, "R8 mapping"):
            self.inventory()

    def test_wrong_certificate_and_failed_verification_are_rejected_before_aapt(self):
        for failure in ("certificate", "exit", "timeout"):
            with self.subTest(failure=failure):
                self.tool_calls.clear()
                self.tool_error = 2 if failure == "exit" else subprocess.TimeoutExpired("PRIVATE_COMMAND", 120) if failure == "timeout" else None
                with self.assertRaises(ValueError) as context:
                    self.inventory(certificate=OTHER_CERT if failure == "certificate" else CERT)
                self.assertNotIn("PRIVATE", str(context.exception))
                self.assertEqual(len(self.tool_calls), 1)

    def test_multiple_or_unverified_signers_are_rejected(self):
        normal = self.fake_run([str(APKSIGNER), "verify", "--verbose", "--print-certs", str(self.apk())], capture_output=True, text=True, timeout=120, check=False).stdout
        for output in (
            normal.replace("Number of signers: 1", "Number of signers: 2"),
            normal + f"Signer #2 certificate SHA-256 digest: {CERT}\n",
            normal.replace("Verifies\n", ""),
            normal.replace(": true", ": false"),
        ):
            with self.subTest(output=output):
                self.signing_overrides["androidApp"] = output
                with self.assertRaisesRegex(ValueError, "successfully verified APK signer"):
                    self.inventory()

    def test_wrong_badging_versions_package_or_features_are_rejected(self):
        normal = self.fake_run([str(AAPT), "dump", "badging", str(self.apk())], capture_output=True, text=True, timeout=120, check=False).stdout
        for output in (
            normal.replace("200002002", "200002003"),
            normal.replace("0.0.1", "0.0.1-ci-benchmark"),
            normal.replace(artifacts.PACKAGE, "org.other.app"),
            normal.replace("uses-feature:", "uses-feature-not-required:"),
            normal + "uses-feature: name='android.software.leanback'\n",
            normal + "uses-feature-not-required: name='android.hardware.touchscreen'\n",
            normal + "package: name='second'\n",
        ):
            with self.subTest(output=output):
                self.badging_overrides["androidApp"] = output
                with self.assertRaises(ValueError):
                    self.inventory()

    def test_tv_touchscreen_must_be_optional(self):
        path = self.apk("androidTvApp")
        normal = self.fake_run([str(AAPT), "dump", "badging", str(path)], capture_output=True, text=True, timeout=120, check=False).stdout
        self.badging_overrides["androidTvApp"] = normal.replace("uses-feature-not-required: name='android.hardware.touchscreen'\n", "")
        with self.assertRaisesRegex(ValueError, "TV touchscreen must be optional"):
            self.inventory()

    def test_missing_wrong_or_extra_native_abi_is_rejected(self):
        path = self.apk()
        original = path.read_bytes()
        for native in ({}, {"lib/x86_64/libexample.so": b"wrong"}, {"lib/arm64-v8a/libexample.so": b"right", "lib/x86_64/libexample.so": b"extra"}):
            with self.subTest(native=native):
                self.change_zip(path, native, ["lib/arm64-v8a/libexample.so"])
                with self.assertRaisesRegex(ValueError, "native ABI payload"):
                    self.inventory()
                path.write_bytes(original)

    def test_universal_requires_all_three_abis(self):
        self.change_zip(self.apk(abi="universal"), removals=["lib/x86_64/libexample.so"])
        with self.assertRaisesRegex(ValueError, "native ABI payload"):
            self.inventory()

    def test_duplicate_zip_entries_are_rejected_even_for_signing_files(self):
        path = self.apk()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("META-INF/CERT.SF", b"second signature entry")
        with self.assertRaisesRegex(ValueError, "duplicate APK ZIP entry"):
            self.inventory()

    def test_unsafe_zip_entries_are_rejected(self):
        path = self.apk()
        original = path.read_bytes()
        for name in ("../escape", "/absolute", "nested/../escape", "./dot", "empty//part", r"back\slash", "drive:colon", "control\x01"):
            with self.subTest(name=name):
                self.change_zip(path, {name: b"unsafe"})
                with self.assertRaisesRegex(ValueError, "unsafe APK ZIP entry"):
                    self.inventory()
                path.write_bytes(original)

    def test_raw_nul_zip_name_is_rejected(self):
        path = self.apk()
        self.change_zip(path, {"nul_name": b"unsafe"})
        path.write_bytes(path.read_bytes().replace(b"nul_name", b"nul\x00name"))
        with self.assertRaisesRegex(ValueError, "unsafe APK ZIP entry"):
            self.inventory()

    def test_encrypted_zip_entry_is_rejected(self):
        path = self.apk()
        data = bytearray(path.read_bytes())
        central = data.index(b"PK\x01\x02")
        flags = struct.unpack_from("<H", data, central + 8)[0]
        struct.pack_into("<H", data, central + 8, flags | 1)
        path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "encrypted APK ZIP entry"):
            self.inventory()

    def test_zip_crc_corruption_is_rejected(self):
        path = self.apk()
        data = bytearray(path.read_bytes())
        central = data.index(b"PK\x01\x02")
        struct.pack_into("<L", data, central + 16, 0)
        path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "invalid APK ZIP payload"):
            self.inventory()

    def test_special_zip_entry_is_rejected(self):
        path = self.apk()
        with zipfile.ZipFile(path, "a") as archive:
            info = zipfile.ZipInfo("symlink")
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            archive.writestr(info, b"target")
        with self.assertRaisesRegex(ValueError, "special APK ZIP entry"):
            self.inventory()

    def test_verified_v1_signing_entries_require_one_matching_trio(self):
        path = self.apk()
        self.change_zip(path, removals=["META-INF/CERT.RSA"])
        with self.assertRaisesRegex(ValueError, "ambiguous verified v1"):
            self.inventory()
        self.change_zip(path, {"META-INF/OTHER.RSA": b"other"})
        with self.assertRaisesRegex(ValueError, "mismatched verified v1"):
            self.inventory()

    def test_v1_false_retains_manifest_and_signature_named_payload(self):
        normal = self.fake_run([str(APKSIGNER), "verify", "--verbose", "--print-certs", str(self.apk())], capture_output=True, text=True, timeout=120, check=False).stdout
        self.signing_overrides["androidApp"] = normal.replace("(JAR signing): true", "(JAR signing): false")
        report = self.inventory()
        apk = report["modules"]["androidApp"]["apks"]["arm64-v8a"]
        self.assertEqual(apk["excluded_v1_entries"], [])
        self.assertIn("META-INF/MANIFEST.MF", apk["entries"])
        self.assertIn("META-INF/CERT.SF", apk["entries"])

    def test_payload_profile_and_ordinary_meta_inf_mutations_fail_comparison(self):
        left = self.inventory()
        path = self.apk()
        original = path.read_bytes()
        for name in ("classes.dex", "classes10.dex", "resources.arsc", "lib/arm64-v8a/libexample.so", "assets/dexopt/baseline.prof", "META-INF/kotlinx_coroutines_android.version", "META-INF/com/example/MANIFEST.MF", "META-INF/services/example.Service"):
            with self.subTest(name=name):
                self.change_zip(path, {name: b"changed payload"})
                right = self.inventory()
                with self.assertRaisesRegex(ValueError, "differs between profiles"):
                    artifacts.compare_profiles([left], [right])
                path.write_bytes(original)

    def test_profile_presence_and_r8_mapping_mutations_fail_comparison(self):
        left = self.inventory()
        self.change_zip(self.apk(), removals=["assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"])
        with self.assertRaisesRegex(ValueError, "profiles differs"):
            artifacts.compare_profiles([left], [self.inventory()])
        right = copy.deepcopy(left)
        right["modules"]["androidApp"]["r8_mapping_sha256"] = "34" * 32
        with self.assertRaisesRegex(ValueError, "r8_mapping_sha256"):
            artifacts.compare_profiles([left], [right])

    def test_comparison_requires_complete_unique_eight_apk_inventory(self):
        combined = self.inventory()
        phone = self.inventory(modules=["androidApp"])
        tv = self.inventory(modules=["androidTvApp"])
        for reports in ([], [phone], [phone, phone], [phone, tv, combined], [{"schema_version": 2, "modules": {}}]):
            with self.subTest(reports=reports):
                with self.assertRaises(ValueError):
                    artifacts.compare_profiles(reports, [combined])
        incomplete = copy.deepcopy(combined)
        del incomplete["modules"]["androidApp"]["apks"]["universal"]
        with self.assertRaisesRegex(ValueError, "four ABI outputs"):
            artifacts.compare_profiles([incomplete], [combined])

    def test_matching_null_collector_placeholders_are_rejected(self):
        report = self.inventory()
        for module in report["modules"].values():
            module["build_config"] = None
            module["r8_mapping_sha256"] = None
            for apk in module["apks"].values():
                for field in apk:
                    apk[field] = None
        with self.assertRaises(ValueError):
            artifacts.compare_profiles([report], [copy.deepcopy(report)])

    def test_matching_malformed_payload_reports_are_rejected(self):
        valid = self.inventory()
        mutations = (
            ("entries", {}), ("entries", None), ("profiles", None),
            ("payload_sha256", "not-a-digest"), ("native_abis", None),
            ("signature_schemes", {}), ("package", None), ("size_bytes", 0),
        )
        for field, value in mutations:
            with self.subTest(field=field):
                report = copy.deepcopy(valid)
                report["modules"]["androidApp"]["apks"]["arm64-v8a"][field] = value
                with self.assertRaises(ValueError):
                    artifacts.compare_profiles([report], [copy.deepcopy(report)])
        for mutation in ("missing-manifest", "missing-dex", "invalid-entry-digest", "invalid-entry-size"):
            with self.subTest(mutation=mutation):
                report = copy.deepcopy(valid)
                entries = report["modules"]["androidApp"]["apks"]["arm64-v8a"]["entries"]
                if mutation == "missing-manifest":
                    del entries["AndroidManifest.xml"]
                elif mutation == "missing-dex":
                    del entries["classes.dex"]
                else:
                    entries["classes.dex"]["sha256" if mutation == "invalid-entry-digest" else "size"] = None
                with self.assertRaises(ValueError):
                    artifacts.compare_profiles([report], [copy.deepcopy(report)])

    def test_invalid_module_selection_and_certificate_are_rejected(self):
        for modules in ([], ["androidApp", "androidApp"], ["arbitraryModule"]):
            with self.subTest(modules=modules):
                with self.assertRaises(ValueError):
                    artifacts.inventory(self.source, modules, CERT, APKSIGNER, AAPT)
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            self.inventory(certificate="not-a-fingerprint")


if __name__ == "__main__":
    unittest.main()
