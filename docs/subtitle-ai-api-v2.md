# Android subtitle AI reads on API v2

Phone and TV quota reads, job polling and job cancellation use the shared v2 reads
adapter (`SubtitleAiReadsV2Api`); job creation has its own one-send adapter. The
existing recent-jobs facade uses v2 too, although no current native UI calls it.
`DefaultSubtitlesApi` is v2-only: every subtitle call goes through a required v2
adapter and no v1 subtitle path remains.

The wire adapter requires string job, media-file and result-subtitle IDs. It checks
canonical positive Long job IDs and Int renderer handles before projecting to the
existing player model. It rejects a response for another requested job or file,
duplicate recent jobs, unsupported kinds and non-representable handles. Nullable
result identity, server-redacted errors and timestamp strings are preserved.

Each poll captures one account/profile scope. Requests and results remain bound to
that identity, and an identity change ends polling without publishing the delayed
job. Ordinary transport failures retain the existing retry behavior; missing jobs
and invalid projected identities terminate with an error. V2 reads use the existing
contract gate.

Focused tests cover IDs beyond JavaScript's safe integer range, numeric-wire and
native-overflow refusal, requested identity matching, nullable results, quota,
recent-job file binding and a profile change during a poll. Phone and TV compilation
is checked. No AI/provider request, live job, device playback or mutation validation
is claimed by these tests.
