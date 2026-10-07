"""Validate disposable release APKs and compare matrix/combined build payloads.

No Android dependency is imported. The controller supplies the installed SDK
tools and the SHA-256 certificate fingerprint of its disposable signing key.
Only inventory() invokes those tools; compare_profiles() consumes JSON reports.
"""

from __future__ import annotations

import hashlib
import json
import re
import stat
import subprocess
import zipfile
from pathlib import Path


SCHEMA_VERSION = 1
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
OUTPUTS = (*ABIS, "universal")
# The sealed source's universal output has no ABI filter. Pinned dependency
# AARs include x86; the three explicitly filtered split outputs do not.
UNIVERSAL_ABIS = (*ABIS, "x86")
DEPENDENCY_NATIVE_LIBRARIES = (
    "libandroidx.graphics.path.so", "libass.so", "libasskt.so",
    "libc++_shared.so", "libdatastore_shared_counter.so",
)
NATIVE_LIBRARIES = {
    abi: DEPENDENCY_NATIVE_LIBRARIES + ("libffmpegJNI.so", "libsilo_dovi.so")
    for abi in ABIS
}
NATIVE_LIBRARIES["x86"] = DEPENDENCY_NATIVE_LIBRARIES
PACKAGE = "org.siloserver.silo"
VERSION_NAME = "0.0.1"
MODULES = {
    "androidApp": {
        "namespace": "org.siloserver.silo.android",
        "version_code": 200002002,
        "required_feature": "android.hardware.touchscreen",
    },
    "androidTvApp": {
        "namespace": "org.siloserver.silo.tv",
        "version_code": 200002003,
        "required_feature": "android.software.leanback",
    },
}


class NativePayloadContractError(ValueError):
    """Keep a bounded native ZIP diagnostic in the failed report artifact."""

    def __init__(self, label: str, invalid_entry: str, native: dict):
        if len(native) > 128 or any(len(name.encode()) > 512 for name in native):
            raise ValueError(f"{label}: native APK diagnostic exceeds its budget")
        super().__init__(f"{label}: invalid native library entry")
        self.details = {
            "schema_version": 1,
            "label": label,
            "invalid_entry": invalid_entry,
            "native_entries": [
                {"path": name, **value} for name, value in sorted(native.items())
            ],
        }


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _file_digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            hasher.update(chunk)
    return hasher.hexdigest()


def _canonical_digest(value: object) -> str:
    return _digest(json.dumps(value, sort_keys=True, separators=(",", ":")).encode())


def _fingerprint(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"(?:[0-9a-fA-F]{64}|(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2})", value):
        raise ValueError("certificate fingerprint must be SHA-256 hexadecimal")
    return value.replace(":", "").lower()


def _file(path: Path, root: Path, label: str) -> Path:
    """Accept a nonempty ordinary file inside the checkout; reject symlinks."""
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to(root)
        relative = path.relative_to(root)
        for ancestor in (root / relative).parents:
            if ancestor == root:
                break
            if ancestor.is_symlink():
                raise ValueError(f"{label}: symlink path is not allowed")
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"{label}: expected nonempty ordinary file")
        return path
    except (OSError, RuntimeError, ValueError) as exc:
        raise ValueError(f"{label}: expected nonempty ordinary file inside source") from exc


def _tool(tool: Path, arguments: list[str], label: str) -> str:
    # Never echo tool output, command arguments or environment on failure.
    try:
        result = subprocess.run(
            [str(tool), *arguments], capture_output=True, text=True,
            timeout=120, check=False,
        )
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise ValueError(f"{label}: SDK verification tool failed") from None
    if result.returncode != 0:
        raise ValueError(f"{label}: SDK verification tool failed (exit {result.returncode})")
    return result.stdout


def _signing(apk: Path, certificate: str, apksigner: Path, label: str) -> dict:
    output = _tool(apksigner, ["verify", "--verbose", "--print-certs", str(apk)], label)
    schemes = dict(re.findall(r"^Verified using (v[0-9.]+) scheme[^\n:]*: (true|false)\s*$", output, re.MULTILINE))
    certs = re.findall(r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F:]+)\s*$", output, re.MULTILINE)
    counts = re.findall(r"^Number of signers: (\d+)\s*$", output, re.MULTILINE)
    if (not re.search(r"^Verifies\s*$", output, re.MULTILINE)
            or counts != ["1"] or len(certs) != 1 or certs[0][0] != "1"
            or not schemes or "true" not in schemes.values()):
        raise ValueError(f"{label}: expected one successfully verified APK signer")
    if _fingerprint(certs[0][1]) != certificate:
        raise ValueError(f"{label}: signing certificate does not match disposable fixture")
    return {key: value == "true" for key, value in sorted(schemes.items())}


def _badging(apk: Path, module: str, aapt: Path, label: str) -> dict:
    output = _tool(aapt, ["dump", "badging", str(apk)], label)
    package_lines = re.findall(r"^package: (.*)$", output, re.MULTILINE)
    if len(package_lines) != 1:
        raise ValueError(f"{label}: expected one APK package record")
    fields = dict(re.findall(r"([A-Za-z]+)='([^']*)'", package_lines[0]))
    config = MODULES[module]
    if (fields.get("name") != PACKAGE or fields.get("versionName") != VERSION_NAME
            or fields.get("versionCode") != str(config["version_code"])):
        raise ValueError(f"{label}: package version does not match benchmark inputs")
    required = re.findall(r"^[ \t]*uses-feature: name='([^']+)'(?: .*?)?[ \t]*$", output, re.MULTILINE)
    optional = re.findall(r"^[ \t]*uses-feature-not-required: name='([^']+)'(?: .*?)?[ \t]*$", output, re.MULTILINE)
    if len(set(required)) != len(required) or set(required) & set(optional):
        raise ValueError(f"{label}: conflicting APK feature records")
    if config["required_feature"] not in required:
        raise ValueError(f"{label}: required form-factor feature is absent")
    other = "android.software.leanback" if module == "androidApp" else "android.hardware.touchscreen"
    if other in required:
        raise ValueError(f"{label}: opposite form-factor feature is required")
    if module == "androidTvApp" and "android.hardware.touchscreen" not in optional:
        raise ValueError(f"{label}: TV touchscreen must be optional")
    return {
        "name": PACKAGE, "version_name": VERSION_NAME,
        "version_code": config["version_code"], "required_features": sorted(required),
        "optional_features": sorted(set(optional)),
    }


def _safe_zip_entry(info: zipfile.ZipInfo, seen: set[str], label: str) -> None:
    name = info.orig_filename
    components = name.removesuffix("/").split("/")
    if (not name or name != info.filename or name.startswith("/") or "\\" in name
            or ":" in name or any(ord(char) < 32 or ord(char) == 127 for char in name)
            or any(component in ("", ".", "..") for component in components)):
        raise ValueError(f"{label}: unsafe APK ZIP entry")
    if name in seen:
        raise ValueError(f"{label}: duplicate APK ZIP entry")
    seen.add(name)
    if info.flag_bits & 1:
        raise ValueError(f"{label}: encrypted APK ZIP entry")
    kind = stat.S_IFMT(info.external_attr >> 16)
    if kind not in (0, stat.S_IFREG, stat.S_IFDIR):
        raise ValueError(f"{label}: special APK ZIP entry")


def _v1_entries(names: set[str], schemes: dict, label: str) -> set[str]:
    if not schemes.get("v1", False):
        return set()
    # apksigner has verified the v1 signer. A single matching direct signing
    # trio is the controlled APK format; ordinary META-INF files remain payload.
    sfs = {name for name in names if re.fullmatch(r"META-INF/[^/]+\.SF", name)}
    blocks = {name for name in names if re.fullmatch(r"META-INF/[^/]+\.(?:RSA|DSA|EC)", name)}
    if len(sfs) != 1 or len(blocks) != 1 or "META-INF/MANIFEST.MF" not in names:
        raise ValueError(f"{label}: ambiguous verified v1 signing entries")
    sf = next(iter(sfs))
    block = next(iter(blocks))
    if sf.rsplit(".", 1)[0] != block.rsplit(".", 1)[0]:
        raise ValueError(f"{label}: mismatched verified v1 signing entries")
    return {"META-INF/MANIFEST.MF", sf, block}


def _native_payload(entries: dict, abi: str, label: str) -> tuple[dict, set[str]]:
    """Require the sealed native member inventory, including universal x86."""
    native = {name: value for name, value in entries.items()
              if name.startswith("lib/") and not name.endswith("/")}
    native_abis = set()
    for name, value in native.items():
        parts = name.split("/")
        if (len(parts) != 3 or parts[1] not in NATIVE_LIBRARIES
                or parts[2] not in NATIVE_LIBRARIES[parts[1]] or not value["size"]):
            raise NativePayloadContractError(label, name, native)
        native_abis.add(parts[1])
    expected_abis = set(UNIVERSAL_ABIS) if abi == "universal" else {abi}
    if native_abis != expected_abis:
        raise ValueError(f"{label}: native ABI payload does not match output filter")
    expected_members = {f"lib/{native_abi}/{library}" for native_abi in expected_abis
                        for library in NATIVE_LIBRARIES[native_abi]}
    if set(native) != expected_members:
        raise ValueError(f"{label}: native library payload does not match sealed dependencies")
    return native, native_abis


def _zip_payload(apk: Path, abi: str, schemes: dict, label: str) -> dict:
    entries = {}
    metadata = {}
    seen: set[str] = set()
    try:
        with zipfile.ZipFile(apk) as archive:
            infos = archive.infolist()
            for info in infos:
                _safe_zip_entry(info, seen, label)
            excluded = _v1_entries(seen, schemes, label)
            for info in infos:
                hasher = hashlib.sha256()
                size = 0
                with archive.open(info) as stream:
                    while chunk := stream.read(1024 * 1024):
                        hasher.update(chunk)
                        size += len(chunk)
                if size != info.file_size:
                    raise ValueError(f"{label}: invalid APK ZIP entry size")
                metadata[info.filename] = {
                    "date_time": list(info.date_time), "compression": info.compress_type,
                    "compressed_size": info.compress_size, "crc32": info.CRC,
                    "flags": info.flag_bits, "external_attr": info.external_attr,
                    "create_system": info.create_system, "extra_hex": info.extra.hex(),
                }
                if info.filename not in excluded:
                    entries[info.filename] = {"sha256": hasher.hexdigest(), "size": size}
    except (OSError, zipfile.BadZipFile, RuntimeError, NotImplementedError) as exc:
        raise ValueError(f"{label}: invalid APK ZIP payload") from exc
    native, native_abis = _native_payload(entries, abi, label)
    profiles = {
        name: value["sha256"] for name, value in entries.items()
        if name.startswith("assets/dexopt/") and name.endswith((".prof", ".profm"))
    }
    resources = {
        name: value for name, value in entries.items()
        if name in ("AndroidManifest.xml", "resources.arsc") or name.startswith(("res/", "assets/"))
    }
    dex = {name: value for name, value in entries.items() if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)}
    if not entries.get("AndroidManifest.xml", {}).get("size") or not dex.get("classes.dex", {}).get("size"):
        raise ValueError(f"{label}: APK manifest or DEX payload is absent")
    return {
        "entries": dict(sorted(entries.items())), "zip_metadata": dict(sorted(metadata.items())),
        "excluded_v1_entries": sorted(excluded), "native_abis": sorted(native_abis),
        "profiles": dict(sorted(profiles.items())), "payload_sha256": _canonical_digest(entries),
        "native_sha256": _canonical_digest(native), "resource_sha256": _canonical_digest(resources),
        "dex_sha256": _canonical_digest(dex),
    }


def _build_config(source: Path, module: str) -> dict:
    config = MODULES[module]
    root = source / module / "build/generated/source/buildConfig/release"
    path = root / Path(config["namespace"].replace(".", "/")) / "BuildConfig.java"
    _file(path, source, f"{module}: release BuildConfig")
    if sorted(root.rglob("BuildConfig.java")) != [path]:
        raise ValueError(f"{module}: expected one generated release BuildConfig")
    try:
        content = path.read_text()
    except (OSError, UnicodeError) as exc:
        raise ValueError(f"{module}: invalid generated release BuildConfig") from exc
    if not re.search(r"^package\s+" + re.escape(config["namespace"]) + r"\s*;", content, re.MULTILINE):
        raise ValueError(f"{module}: unexpected generated BuildConfig namespace")
    expected = {"BUILD_NUMBER": "1", "RELEASE_CHANNEL": "sideload"}
    if module == "androidTvApp":
        expected["DISPLAY_VERSION"] = "0.0.1-ci-benchmark"
    for name, value in expected.items():
        matches = re.findall(r"public\s+static\s+final\s+String\s+" + name + r'\s*=\s*("(?:[^"\\]|\\.)*")\s*;', content)
        try:
            valid = len(matches) == 1 and json.loads(matches[0]) == value
        except (ValueError, IndexError):
            valid = False
        if not valid:
            raise ValueError(f"{module}: generated release {name} does not match benchmark inputs")
    return expected


def _output_files(source: Path, module: str) -> dict[str, Path]:
    root = source / module / "build/outputs/apk/release"
    metadata_file = _file(root / "output-metadata.json", source, f"{module}: output metadata")
    try:
        metadata = json.loads(metadata_file.read_text())
    except (OSError, UnicodeError, ValueError) as exc:
        raise ValueError(f"{module}: invalid output metadata") from exc
    if (not isinstance(metadata, dict) or metadata.get("variantName") != "release"
            or metadata.get("applicationId") != PACKAGE
            or not isinstance(metadata.get("artifactType"), dict)
            or metadata["artifactType"].get("type") != "APK"
            or not isinstance(metadata.get("elements"), list) or len(metadata["elements"]) != 4):
        raise ValueError(f"{module}: expected four release APK metadata elements")
    outputs: dict[str, Path] = {}
    filenames = set()
    for element in metadata["elements"]:
        if not isinstance(element, dict) or not isinstance(element.get("filters"), list):
            raise ValueError(f"{module}: invalid APK output filters")
        filters = element["filters"]
        if not filters:
            abi = "universal"
        elif (len(filters) == 1 and isinstance(filters[0], dict)
              and set(filters[0]) == {"filterType", "value"}
              and filters[0].get("filterType") == "ABI" and filters[0].get("value") in ABIS):
            abi = filters[0]["value"]
        else:
            raise ValueError(f"{module}: unknown APK output filter")
        filename = element.get("outputFile")
        if (not isinstance(filename, str) or not filename.endswith(".apk")
                or Path(filename).name != filename or any(char in filename for char in ("/", "\\", ":"))
                or any(ord(char) < 32 or ord(char) == 127 for char in filename)):
            raise ValueError(f"{module}: unsafe APK output filename")
        if abi in outputs or filename in filenames:
            raise ValueError(f"{module}: duplicate APK output")
        if element.get("versionCode") != MODULES[module]["version_code"] or element.get("versionName") != VERSION_NAME:
            raise ValueError(f"{module}: output metadata version does not match benchmark inputs")
        outputs[abi] = _file(root / filename, source, f"{module}/{abi}")
        filenames.add(filename)
    if set(outputs) != set(OUTPUTS) or set(root.rglob("*.apk")) != set(outputs.values()):
        raise ValueError(f"{module}: unexpected APK files or missing ABI outputs")
    return outputs


def inventory(source: Path, modules: list[str], certificate_sha256: str, apksigner: Path, aapt: Path) -> dict:
    """Verify a one- or two-module release artifact inventory (JSON serializable)."""
    if (not isinstance(modules, list) or not modules
            or any(not isinstance(module, str) or module not in MODULES for module in modules)
            or len(set(modules)) != len(modules)):
        raise ValueError("modules must select androidApp and/or androidTvApp exactly once")
    source = Path(source).resolve()
    if not source.is_dir():
        raise ValueError("source must be an existing checkout directory")
    certificate = _fingerprint(certificate_sha256)
    result = {"schema_version": SCHEMA_VERSION, "certificate_sha256": certificate, "modules": {}}
    for module in sorted(modules):
        build_config = _build_config(source, module)
        mapping = _file(source / module / "build/outputs/mapping/release/mapping.txt", source, f"{module}: R8 mapping")
        apks = {}
        for abi, apk in sorted(_output_files(source, module).items()):
            label = f"{module}/{abi}"
            schemes = _signing(apk, certificate, Path(apksigner), label)
            package = _badging(apk, module, Path(aapt), label)
            payload = _zip_payload(apk, abi, schemes, label)
            apks[abi] = {
                "output_file": apk.name, "size_bytes": apk.stat().st_size,
                "signed_sha256": _file_digest(apk), "certificate_sha256": certificate,
                "signature_schemes": schemes, "package": package, **payload,
            }
        result["modules"][module] = {
            "build_config": build_config, "r8_mapping_sha256": _file_digest(mapping), "apks": apks,
        }
    return result


def _is_digest(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def _report_module(data: dict, module: str, certificate: str, side: str) -> None:
    """Reject missing collector data before declaring two layouts equivalent."""
    label = f"{side}/{module}"
    expected_config = {"BUILD_NUMBER": "1", "RELEASE_CHANNEL": "sideload"}
    if module == "androidTvApp":
        expected_config["DISPLAY_VERSION"] = "0.0.1-ci-benchmark"
    if data.get("build_config") != expected_config or not _is_digest(data.get("r8_mapping_sha256")):
        raise ValueError(f"{label}: invalid release metadata inventory")
    for abi, apk in data["apks"].items():
        label = f"{side}/{module}/{abi}"
        if (not isinstance(apk, dict) or not _is_digest(apk.get("signed_sha256"))
                or type(apk.get("size_bytes")) is not int or apk["size_bytes"] <= 0
                or apk.get("certificate_sha256") != certificate
                or not isinstance(apk.get("output_file"), str)
                or not isinstance(apk.get("zip_metadata"), dict)
                or any(not _is_digest(apk.get(field)) for field in ("payload_sha256", "native_sha256", "resource_sha256", "dex_sha256"))):
            raise ValueError(f"{label}: invalid APK inventory record")
        schemes = apk.get("signature_schemes")
        package = apk.get("package")
        if (not isinstance(schemes, dict) or not schemes or not any(schemes.values())
                or any(not isinstance(key, str) or not re.fullmatch(r"v[0-9.]+", key) or type(value) is not bool for key, value in schemes.items())
                or not isinstance(package, dict) or package.get("name") != PACKAGE
                or package.get("version_name") != VERSION_NAME
                or package.get("version_code") != MODULES[module]["version_code"]
                or not isinstance(package.get("required_features"), list)
                or not isinstance(package.get("optional_features"), list)
                or apk.get("native_abis") != sorted(UNIVERSAL_ABIS if abi == "universal" else [abi])):
            raise ValueError(f"{label}: invalid APK identity inventory")
        entries = apk.get("entries")
        profiles = apk.get("profiles")
        if not isinstance(entries, dict) or not entries or not isinstance(profiles, dict):
            raise ValueError(f"{label}: invalid APK payload inventory")
        for name, entry in entries.items():
            if (not isinstance(name, str) or not name or not isinstance(entry, dict)
                    or not _is_digest(entry.get("sha256"))
                    or type(entry.get("size")) is not int or entry["size"] < 0):
                raise ValueError(f"{label}: invalid APK entry inventory")
        if not entries.get("AndroidManifest.xml", {}).get("size") or not entries.get("classes.dex", {}).get("size"):
            raise ValueError(f"{label}: missing APK manifest or DEX inventory")
        native, native_abis = _native_payload(entries, abi, label)
        if apk["native_abis"] != sorted(native_abis) or apk["native_sha256"] != _canonical_digest(native):
            raise ValueError(f"{label}: inconsistent native payload inventory")
        expected_profiles = {name: entry["sha256"] for name, entry in entries.items()
                             if name.startswith("assets/dexopt/") and name.endswith((".prof", ".profm"))}
        if profiles != expected_profiles or apk["payload_sha256"] != _canonical_digest(entries):
            raise ValueError(f"{label}: inconsistent APK payload inventory")


def _aggregate(reports: list[dict], side: str) -> dict:
    if not isinstance(reports, list) or not reports:
        raise ValueError(f"{side}: expected inventory reports")
    modules = {}
    for report in reports:
        if (not isinstance(report, dict) or report.get("schema_version") != SCHEMA_VERSION
                or not isinstance(report.get("modules"), dict) or not report["modules"]):
            raise ValueError(f"{side}: invalid inventory report")
        certificate = _fingerprint(report.get("certificate_sha256"))
        for module, data in report["modules"].items():
            if module not in MODULES or module in modules or not isinstance(data, dict):
                raise ValueError(f"{side}: duplicate or unknown inventory module")
            if not isinstance(data.get("apks"), dict) or set(data["apks"]) != set(OUTPUTS):
                raise ValueError(f"{side}: expected four ABI outputs per module")
            _report_module(data, module, certificate, side)
            modules[module] = data
    if set(modules) != set(MODULES):
        raise ValueError(f"{side}: expected both phone and TV inventories")
    return modules


def compare_profiles(left_reports: list[dict], right_reports: list[dict]) -> dict:
    """Compare two layouts, each representing both modules and all eight APKs.

    Signed file/container hashes, local certificates and ZIP metadata may differ.
    Every uncompressed payload entry, signing scheme, app metadata, ABI/profile
    inventory and R8 mapping must match. Raise ValueError on the first difference.
    """
    left = _aggregate(left_reports, "left")
    right = _aggregate(right_reports, "right")
    for module in sorted(MODULES):
        for field in ("build_config", "r8_mapping_sha256"):
            if field not in left[module] or left[module].get(field) != right[module].get(field):
                raise ValueError(f"{module}: {field} differs between profiles")
        for abi in OUTPUTS:
            for field in ("signature_schemes", "package", "native_abis", "profiles", "entries", "payload_sha256", "native_sha256", "resource_sha256", "dex_sha256"):
                if field not in left[module]["apks"][abi] or left[module]["apks"][abi].get(field) != right[module]["apks"][abi].get(field):
                    raise ValueError(f"{module}/{abi}: {field} differs between profiles")
    return {"schema_version": SCHEMA_VERSION, "equivalent": True, "modules": sorted(MODULES), "apk_count": 8}
