#!/usr/bin/env bash
# Vendors the selected native API v2 contract fixtures from a silo-server
# checkout into shared/src/commonTest/resources/api/v2/fixtures.
#
# Usage: scripts/sync-apiv2-fixtures.sh /path/to/silo-server
#
# Only the fixtures the Android client consumes are copied (the four pilot
# operations, the probe's system-info body, and the generic problem bodies)
# plus fixtures.schema.json and an index.json filtered to the vendored
# entries. The full OpenAPI document is never vendored. SOURCE records the
# exact server commit in the repository's key=value convention.
set -euo pipefail

SERVER_DIR="${1:?usage: $0 /path/to/silo-server}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/shared/src/commonTest/resources/api/v2/fixtures"
SRC="$SERVER_DIR/contracts/api/v2/fixtures"
SCHEMA="$SERVER_DIR/contracts/api/v2/fixtures.schema.json"

SELECTED=(
  get_system_info_ok
  get_setup_status_ok
  get_current_user_ok
  list_progress_ok
  list_progress_profile_header_required
  list_progress_offset_rejected
  update_profile_ok
  update_profile_null_not_clearable
  authentication_required
  validation_failed_body
  not_found
  rate_limited
  profile_verification_required
  not_acceptable
)

[ -d "$SRC" ] || { echo "no fixtures at $SRC" >&2; exit 1; }
[ -f "$SCHEMA" ] || { echo "no schema at $SCHEMA" >&2; exit 1; }

mkdir -p "$DEST"
find "$DEST" -maxdepth 1 -name '*.json' -delete
for name in "${SELECTED[@]}"; do
  cp "$SRC/$name.json" "$DEST/$name.json"
done
cp "$SCHEMA" "$DEST/fixtures.schema.json"

# index.json keeps the server's entry objects byte-for-byte but only for the
# vendored bodies, so a test can iterate it and expect every body_file to exist.
SELECTED_CSV="$(IFS=,; echo "${SELECTED[*]}")" python3 - "$SRC/index.json" "$DEST/index.json" <<'PY'
import json, os, sys
selected = set(os.environ["SELECTED_CSV"].split(","))
with open(sys.argv[1]) as f:
    index = json.load(f)
index["fixtures"] = [e for e in index["fixtures"] if e["name"] in selected]
missing = selected - {e["name"] for e in index["fixtures"]}
if missing:
    sys.exit("fixtures missing from server index: %s" % ", ".join(sorted(missing)))
with open(sys.argv[2], "w") as f:
    json.dump(index, f, indent=2)
    f.write("\n")
PY

COMMIT="$(git -C "$SERVER_DIR" rev-parse HEAD)"
REF="$(git -C "$SERVER_DIR" rev-parse --abbrev-ref HEAD)"
cat > "$DEST/SOURCE" <<EOS
repository=https://github.com/Silo-Server/silo-server
path=contracts/api/v2/fixtures
commit=$COMMIT
ref=$REF
api_major=2

Byte-identical copies of a subset of the server's native API v2 contract
fixtures; do not hand-edit any of them. The server generates them through its
real v2 router (make apiv2-fixtures) and validates the bodies against the
committed OpenAPI artifact. Only the fixtures the Android pilot consumes are
vendored here, and index.json is filtered to those entries; the OpenAPI
document itself is never vendored. Re-vendor with
scripts/sync-apiv2-fixtures.sh <server checkout> and run :shared:testDebugUnitTest.

ApiV2ContractTest decodes every body here with the production SiloJson.
EOS
echo "wrote ${#SELECTED[@]} fixtures to $DEST (server commit $COMMIT)"
