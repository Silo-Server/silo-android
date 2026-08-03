# APK-only GitHub Release Publishing Design

## Problem

The `v1.0.0-rc.1+4` release run built and uploaded both signed APK artifact sets, but GitHub Actions skipped the `publish-release` job. APK-only tags intentionally skip the `play` job. Although the `apks` matrix has an explicit condition that accepts a skipped Play job, `publish-release` has no explicit status condition. GitHub therefore propagates the skipped dependency through the job chain and applies its implicit success gate.

Run `30814079342` demonstrates the failure: `play` was skipped, both `apks` matrix jobs succeeded, and `publish-release` was skipped without executing any steps.

## Design

Add a job-level condition to `publish-release`:

```yaml
if: >-
  ${{ !cancelled() &&
      needs.setup.result == 'success' &&
      needs.apks.result == 'success' }}
```

The explicit status function disables the implicit success gate that propagates skipped ancestors. Requiring successful `setup` and `apks` results preserves the existing safety boundary: a setup, test, Play, signing, or APK-build failure cannot publish a GitHub release. `!cancelled()` prevents a cancelled workflow from publishing artifacts.

No release naming, prerelease classification, Play behavior, asset naming, or `Latest` behavior changes.

## Regression Protection

Add a focused shell self-test for the release workflow. It will extract the `publish-release` job header and require:

- `needs: [setup, apks]`;
- an explicit `if` condition;
- cancellation protection;
- successful `setup` and `apks` result checks.

The release workflow's unit-test job will run this self-test before Gradle tests so future edits cannot silently restore the transitive-skip bug. Existing supply-chain checks and workflow syntax validation will also run.

## Validation

Validation will cover:

1. The new regression test fails against the current workflow.
2. The minimal condition change makes it pass.
3. Shell syntax checks pass for the new script.
4. Existing supply-chain policy self-tests pass.
5. The workflow parses and passes `actionlint`.

The fix will not trigger a release; it will be delivered through a pull request from an isolated branch based on `upstream/main`.
