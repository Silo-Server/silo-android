#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
workflow_file="${repo_root}/.github/workflows/release.yml"
failures=0

publish_job="$({
  awk '
    /^  publish-release:[[:space:]]*$/ {
      in_publish_job = 1
    }
    in_publish_job &&
      /^  [[:alnum:]_-]+:[[:space:]]*$/ &&
      $0 !~ /^  publish-release:/ {
      exit
    }
    in_publish_job {
      print
    }
  ' "${workflow_file}"
} | sed '/^[[:space:]]*#/d')"
publish_job_header="$(
  awk '/^    steps:[[:space:]]*$/ { exit } { print }' <<< "${publish_job}"
)"

expect_publish_job_header_to_contain() {
  local description="$1"
  local expected="$2"

  if ! grep -Fq "${expected}" <<< "${publish_job_header}"; then
    printf 'FAIL: publish-release must %s\n' "${description}" >&2
    failures=$((failures + 1))
  fi
}

expect_publish_job_header_to_contain \
  "depend on setup and APK artifacts" \
  "needs: [setup, apks]"
expect_publish_job_header_to_contain \
  "define an explicit job condition" \
  "if: >-"
expect_publish_job_header_to_contain \
  "stop when the workflow is cancelled" \
  "!cancelled()"
expect_publish_job_header_to_contain \
  "require successful setup" \
  "needs.setup.result == 'success'"
expect_publish_job_header_to_contain \
  "require successful APK builds" \
  "needs.apks.result == 'success'"

if ((failures > 0)); then
  printf '%d release workflow self-test(s) failed\n' "${failures}" >&2
  exit 1
fi

printf 'All release workflow self-tests passed\n'
