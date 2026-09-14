# Android API v2 migration status

Every HTTP call the Android clients make goes to `/api/v2/` except the two
exceptions at the end. Source of truth: `grep -rhoE '"/api/v2/[^"]+' shared/src/commonMain`
and the server ledger `contracts/api/v2/migration.json` (silo-server `origin/apiv2`).

| Endpoint family | v2 paths | Status |
| --- | --- | --- |
| Auth | `/api/v2/auth/{login,setup,signup,logout,refresh}`, `/api/v2/system/setup`, `/api/v2/account/me`, `/api/v2/system/info` | migrated |
| Device sign-in | `/api/v2/auth/device`, `/api/v2/auth/device/{start,poll,capability,approve,approve-handoff,deny}` | migrated |
| Invitations | `/api/v2/invitations/capabilities`, `/api/v2/invitations/{token}`, `/api/v2/invitations/{token}/accept` | migrated |
| Onboarding | `/api/v2/onboarding/{flow,state,progress}` | migrated |
| Profiles | `/api/v2/profiles`, `/api/v2/profiles/{id}`, `/api/v2/profiles/{id}/verify-pin` | migrated |
| Branding | `/api/v2/theme/branding` | migrated |
| Catalog | `/api/v2/catalog`, `/api/v2/catalog/query`, `/api/v2/catalog/filters`, `/api/v2/catalog/filters/search`, `/api/v2/catalog/search/capabilities`, `/api/v2/catalog/audiobook-groups`, `/api/v2/catalog/items/{id}`, `/api/v2/catalog/items/{id}/{episodes,versions,translate-description}`, `/api/v2/catalog/series/{id}/seasons`, `/api/v2/catalog/series/{id}/seasons/{n}/episodes`, `/api/v2/catalog/people`, `/api/v2/catalog/people/{id}`, `/api/v2/catalog/people/{id}/refresh`, `/api/v2/watch/{id}` | migrated |
| Libraries and sections | `/api/v2/user/libraries`, `/api/v2/library/{id}/sections`, `/api/v2/library/{id}/sections/{section_id}/items`, `/api/v2/library/{id}/collections`, `/api/v2/library-playback-prefs`, `/api/v2/library-playback-prefs/{id}` (PATCH) | migrated |
| Home | `/api/v2/home/sections`, `/api/v2/home/sections/{id}/items`, `/api/v2/home/dismissals/{surface}/{item_id}` | migrated |
| Personal data | `/api/v2/progress`, `/api/v2/history`, `/api/v2/favorites/{item_id}`, `/api/v2/watchlist/{item_id}`, `/api/v2/watched/{item_id}`, `/api/v2/ratings/{item_id}` | migrated |
| Recommendations | `/api/v2/recommendations/{discover,taste-profile}`, `/api/v2/recommendations/similar/{id}` | migrated |
| Collections | `/api/v2/collections`, `/api/v2/collections/{id}`, `/api/v2/collections/{id}/items/{item_id}`, `/api/v2/collections/{id}/items/order`, `/api/v2/collections/order`, `/api/v2/collections/groups`, `/api/v2/collections/groups/{id}`, `/api/v2/collections/groups/order`, `/api/v2/collections/capabilities` | migrated |
| Calendar | `/api/v2/calendar` | migrated |
| Requests | `/api/v2/requests`, `/api/v2/requests/{id}`, `/api/v2/requests/{id}/cancel`, `/api/v2/requests/{mine,status,search,discover}`, `/api/v2/requests/discover/{section}`, `/api/v2/requests/detail/{media_type}/{tmdb_id}` | migrated (server-gated by `requests_enabled`) |
| Playback | `/api/v2/playback/capabilities`, `/api/v2/playback/start`, `/api/v2/playback/route-events`, `/api/v2/playback/sessions/{id}/control/ws-ticket`, `/api/v2/playback/sessions/{id}/control/ws` | migrated (start was redesigned server-side; the client uses the v2 `PlaybackDecision` shape) |
| Subtitles | `/api/v2/subtitles/{media_file_id}`, `/api/v2/subtitles/{search,download}`, `/api/v2/subtitles/ai/{status,quota,translate,jobs}`, `/api/v2/subtitles/ai/jobs/{id}`, `/api/v2/subtitles/ai/jobs/{id}/cancel` | migrated |
| Settings | `/api/v2/settings/contract/capabilities`, `/api/v2/settings/values/effective`, `/api/v2/settings/values/{key}`, `/api/v2/settings/overlay-config` | migrated |
| Downloads | `/api/v2/downloads`, `/api/v2/downloads/{id}`, `/api/v2/capabilities/downloads` | migrated |
| Ebooks (phone only) | `/api/v2/capabilities/ebooks`, `/api/v2/ebooks/{id}/{progress,reader-config,annotations}`, `/api/v2/ebooks/{id}/files/{file_id}/read` | migrated |
| Notifications | `/api/v2/notifications`, `/api/v2/notifications/{id}`, `/api/v2/notifications/{id}/read`, `/api/v2/notifications/{sync,unread-count,read-all,preferences,capabilities}`, `/api/v2/notifications/push/devices` | migrated |
| Events | `/api/v2/events/ws-ticket`, `/api/v2/events/ws` | migrated |
| Metadata AI | `/api/v2/capabilities/metadata-ai` | migrated |
| Diagnostics | `/api/v2/diagnostics/capabilities`, `/api/v2/diagnostics/reports` | migrated |
| Watch Together (transport only, no UI) | `/api/v2/watch-together/rooms`, `/api/v2/watch-together/join`, `/api/v2/watch-together/rooms/{id}/...`, `/api/v2/watch-together/rooms/{id}/ws-ticket`, `/api/v2/watch-together/rooms/{id}/ws` | migrated; not exposed in either app |

## Intentional exceptions

- `GET /health` (root, version-neutral). The server redesigned `/api/v1/health`
  into the unversioned liveness route; `HealthApi` calls it without credentials.
- `settings/{key}`, `settings/device/{key}`, `settings/effective` (v1). The server
  removed them with no v2 replacement. The client code was deleted; device
  settings resolve through `/api/v2/settings/values/effective`.
- `/api/v2/playback/timelines/{file_id}` and the owner-loss recovery union are
  not on the server and are not called.

`ApiV2Gate` blocks every v2 call when the active server reports `UPDATE_REQUIRED`.
`ApiV2Gate.Unrestricted` is used only for public probes of a server the app is not
yet connected to (setup/signup status, invitation lookup, device sign-in at an
explicit URL, branding) and for websocket ticket minting, where the session's
existence already proves the contract.
