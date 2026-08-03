# APK-only GitHub Release Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure APK-only release tags automatically publish their signed APKs as the normal GitHub `Latest` release when Google Play is intentionally skipped.

**Architecture:** Keep the current release graph and add an explicit status-aware gate to the final `publish-release` job. Protect that gate with a focused shell invariant test executed by the release workflow before the Gradle test suite.

**Tech Stack:** GitHub Actions YAML, Bash, `actionlint`, GitHub CLI.

## Global Constraints

- Play publishing must remain skipped for prerelease-suffixed tags.
- Setup, test, Play, signing, or APK build failures must continue blocking GitHub releases.
- Cancelled runs must not publish.
- Release naming, asset naming, release classification, and GitHub `Latest` behavior must not change.
- The validation process must not trigger a new release.

---

### Task 1: Add the release gate regression test

**Files:**
- Create: `scripts/test-release-workflow.sh`
- Modify: `.github/workflows/release.yml`
- Test: `scripts/test-release-workflow.sh`

**Interfaces:**
- Consumes: `.github/workflows/release.yml` and its `publish-release` job.
- Produces: an executable self-test that exits nonzero unless the final release job explicitly requires non-cancellation and successful `setup` and `apks` jobs.

- [ ] **Step 1: Create the focused workflow invariant test**

Create `scripts/test-release-workflow.sh` with strict Bash mode. Resolve the repository root relative to the script, extract the `publish-release` job from `.github/workflows/release.yml`, and assert that it contains these exact invariants:

```text
needs: [setup, apks]
if: >-
!cancelled()
needs.setup.result == 'success'
needs.apks.result == 'success'
```

Each missing invariant must print `FAIL: publish-release must ...` to stderr and increment a failure counter. A clean run prints `All release workflow self-tests passed`.

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
chmod +x scripts/test-release-workflow.sh
./scripts/test-release-workflow.sh
```

Expected: nonzero exit with `FAIL: publish-release must define an explicit job condition` because the current workflow has no `if` gate.

- [ ] **Step 3: Add the minimal publish condition**

Add this immediately after `needs: [setup, apks]`:

```yaml
if: >-
  ${{ !cancelled() &&
      needs.setup.result == 'success' &&
      needs.apks.result == 'success' }}
```

- [ ] **Step 4: Execute the regression test in release CI**

In the `unit-tests` job, add this command before the existing supply-chain checks:

```bash
./scripts/test-release-workflow.sh
```

- [ ] **Step 5: Verify GREEN**

Run:

```bash
bash -n scripts/test-release-workflow.sh
./scripts/test-release-workflow.sh
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Expected: every command exits zero and both self-test suites print their success messages.

- [ ] **Step 6: Commit the implementation**

```bash
git add scripts/test-release-workflow.sh .github/workflows/release.yml
git commit -m "fix(ci): publish APK-only GitHub releases"
```

### Task 2: Validate and deliver

**Files:**
- Verify: `.github/workflows/release.yml`
- Verify: `scripts/test-release-workflow.sh`

**Interfaces:**
- Consumes: the completed workflow fix and its regression test.
- Produces: a validated branch and pull request against `Silo-Server/silo-android:main`.

- [ ] **Step 1: Validate workflow syntax and repository state**

Run:

```bash
actionlint .github/workflows/release.yml
git diff --check upstream/main...HEAD
git status --short --branch
```

Expected: `actionlint` and `git diff --check` exit zero; the worktree is clean and the branch is ahead of `upstream/main` only by the design, plan, and implementation commits.

- [ ] **Step 2: Re-run the full focused verification**

Run:

```bash
bash -n scripts/test-release-workflow.sh
./scripts/test-release-workflow.sh
./scripts/test-check-build-supply-chain.sh
./scripts/check-build-supply-chain.sh
```

Expected: all commands exit zero.

- [ ] **Step 3: Push and open the pull request**

Push `fix/apk-only-release-publish` to the writable fork remote and open a PR targeting `Silo-Server/silo-android:main`. The PR body must document run `30814079342` as the reproduction, explain the explicit status gate, state that Play and `Latest` behavior are unchanged, and list every verification command.
