# Android Hosted Diagnostics Implementation Plan

**Goal:** Add the Silo-operated hosted collector as the default diagnostics
destination while preserving the existing self-hosted path and privacy gates.

**Architecture:** Destination-neutral coordination delegates hosted identity,
capability, installation, transport, status, and deletion behavior to typed
hosted collaborators. Local evidence remains account-bound; hosted wire payloads
contain no source identity. Archive encoding is separated from sanitization.

## Tasks

- [x] Add typed hosted capabilities, installation, create/upload/status/delete
  API contracts and a dedicated public HTTP client.
- [x] Persist installation credentials in encrypted preferences and capabilities
  in diagnostics DataStore.
- [x] Add destination selection, hosted-default UI copy, consent constraints,
  manifest sanitization, exact-envelope retry, and intent-first erasure.
- [x] Require live collector identity before manual/timed capture.
- [x] Persist a one-way Silo account owner per source server, clear it on server
  purge, and require live authenticated ownership before first hosted upload.
- [x] Model `processing` as a typed accepted state, retain local evidence, and
  schedule WorkManager status polling until `ready`.
- [x] Refresh coordinator state in upload workers, retry temporary source-server
  unavailability, and reconcile remote erasure outside the coordinator actor.
- [x] Extract deterministic USTAR/gzip encoding from privacy sanitization.
- [x] Add focused regression tests and update current product documentation.

## Verification

Run the diagnostics-focused Android shared tests, compile both app variants, run
`git diff --check`, and compare the working head with the pinned PR head before
publishing.
