#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
policy_script="${script_dir}/check-build-supply-chain.sh"
test_root="$(mktemp -d)"
trap 'rm -rf "${test_root}"' EXIT

fixture_root=""
failures=0

new_fixture() {
  local name="$1"
  fixture_root="${test_root}/${name}"
  mkdir -p \
    "${fixture_root}/.github/workflows" \
    "${fixture_root}/gradle/wrapper" \
    "${fixture_root}/scripts"
  cp "${policy_script}" "${fixture_root}/scripts/check-build-supply-chain.sh"
  chmod +x "${fixture_root}/scripts/check-build-supply-chain.sh"

  printf '%s\n' \
    'distributionUrl=https\://services.gradle.org/distributions/gradle-8.12-bin.zip' \
    'distributionSha256Sum=7a00d51fb93147819aab76024feece20b6b84e420694101f276be952e08bef03' \
    > "${fixture_root}/gradle/wrapper/gradle-wrapper.properties"

  write_valid_metadata
}

write_workflow() {
  printf '%s\n' "$1" > "${fixture_root}/.github/workflows/test.yml"
}

write_valid_metadata() {
  printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<verification-metadata>' \
    '  <configuration>' \
    '    <verify-metadata>true</verify-metadata>' \
    '    <verify-signatures>false</verify-signatures>' \
    '  </configuration>' \
    '  <components>' \
    '    <component group="example" name="library" version="1.0">' \
    '      <artifact name="library-1.0.jar">' \
    '        <sha256 value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/>' \
    '      </artifact>' \
    '    </component>' \
    '  </components>' \
    '</verification-metadata>' \
    > "${fixture_root}/gradle/verification-metadata.xml"
}

expect_pass() {
  local name="$1"
  if ! "${fixture_root}/scripts/check-build-supply-chain.sh" \
    > "${fixture_root}/output.log" 2>&1; then
    printf 'FAIL: %s should pass\n' "${name}" >&2
    cat "${fixture_root}/output.log" >&2
    failures=$((failures + 1))
  fi
}

expect_fail() {
  local name="$1"
  local expected_message="$2"
  if "${fixture_root}/scripts/check-build-supply-chain.sh" \
    > "${fixture_root}/output.log" 2>&1; then
    printf 'FAIL: %s should fail\n' "${name}" >&2
    failures=$((failures + 1))
  elif ! grep -q "${expected_message}" "${fixture_root}/output.log"; then
    printf 'FAIL: %s did not report %s\n' "${name}" "${expected_message}" >&2
    cat "${fixture_root}/output.log" >&2
    failures=$((failures + 1))
  fi
}

new_fixture "pinned-action"
write_workflow 'jobs:
  test:
    steps:
      - uses: actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803'
expect_pass "pinned action"

new_fixture "flow-mapping"
write_workflow 'jobs:
  test:
    steps:
      - { uses: actions/checkout@v6 }'
expect_fail "flow mapping" "Unpinned GitHub Action"

new_fixture "spaced-key"
write_workflow 'jobs:
  test:
    steps:
      - uses : actions/checkout@v6'
expect_fail "spaced uses key" "Unpinned GitHub Action"

new_fixture "job-level"
write_workflow 'jobs:
  reusable:
    uses: example/project/.github/workflows/reusable.yml@main'
expect_fail "job-level reusable workflow" "Unpinned GitHub Action"

new_fixture "first-party-reusable"
write_workflow 'jobs:
  reusable:
    uses: Silo-Server/silo-server/.github/workflows/reusable.yml@main'
expect_pass "first-party reusable workflow"

new_fixture "lookalike-owner"
write_workflow 'jobs:
  reusable:
    uses: Silo-Server-fork/silo-server/.github/workflows/reusable.yml@main'
expect_fail "lookalike owner reusable workflow" "Unpinned GitHub Action"

new_fixture "nested-mutable"
write_workflow 'jobs:
  test:
    steps:
      - name: Nested mutable action
        uses: example/project/action@main'
expect_fail "nested mutable action" "Unpinned GitHub Action"

new_fixture "local-action"
write_workflow 'jobs:
  test:
    steps:
      - uses: ./github/actions/local'
expect_pass "local action"

new_fixture "verification-disabled"
write_workflow 'jobs: {}'
sed -i.bak 's/<verify-metadata>true/<verify-metadata>false/' \
  "${fixture_root}/gradle/verification-metadata.xml"
expect_fail "metadata verification disabled" "dependency verification metadata"

new_fixture "missing-checksum"
write_workflow 'jobs: {}'
sed -i.bak '/<sha256 /d' "${fixture_root}/gradle/verification-metadata.xml"
expect_fail "missing artifact checksum" "dependency verification metadata"

new_fixture "orphan-artifact"
write_workflow 'jobs: {}'
printf '%s\n' \
  '<verification-metadata>' \
  '  <configuration><verify-metadata>true</verify-metadata></configuration>' \
  '  <components>' \
  '    <component group="example" name="library" version="1.0"/>' \
  '    <artifact name="orphan.jar">' \
  '      <sha256 value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/>' \
  '    </artifact>' \
  '  </components>' \
  '</verification-metadata>' \
  > "${fixture_root}/gradle/verification-metadata.xml"
expect_fail "orphan artifact checksum" "dependency verification metadata"

new_fixture "missing-linux-aapt2"
write_workflow 'jobs: {}'
printf '%s\n' \
  '<verification-metadata>' \
  '  <configuration><verify-metadata>true</verify-metadata></configuration>' \
  '  <components>' \
  '    <component group="com.android.tools.build" name="aapt2" version="8.10.1-12782657">' \
  '      <artifact name="aapt2-8.10.1-12782657-osx.jar">' \
  '        <sha256 value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/>' \
  '      </artifact>' \
  '      <artifact name="aapt2-8.10.1-12782657.pom">' \
  '        <sha256 value="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"/>' \
  '      </artifact>' \
  '    </component>' \
  '  </components>' \
  '</verification-metadata>' \
  > "${fixture_root}/gradle/verification-metadata.xml"
expect_fail "missing Linux AAPT2" "Missing Linux AAPT2 verification artifact"

new_fixture "trust-all"
write_workflow 'jobs: {}'
sed -i.bak '/<verify-signatures>/a\
    <trusted-artifacts><trust group=".*" regex="true"/></trusted-artifacts>' \
  "${fixture_root}/gradle/verification-metadata.xml"
expect_fail "trust-all metadata" "dependency verification metadata"

if ((failures > 0)); then
  printf '%d supply-chain policy self-test(s) failed\n' "${failures}" >&2
  exit 1
fi

printf 'All supply-chain policy self-tests passed\n'
