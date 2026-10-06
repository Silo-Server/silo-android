# Shuffle

Shuffle plays random movies and episodes from a library, a series, a season, or
a collection until the viewer stops. The server picks every item and applies
the profile's access and parental limits; the client never chooses or orders
picks. The contract is the server's `docs/playback-api.md` ("Shuffle").

## Client pieces

- `ShufflesV2Api` (shared) wraps the six `/api/v2/shuffles` operations. Every
  call is pinned to the profile that made it; mutations are single-attempt.
- `ShuffleFeatureStore` (shared) holds the capability. Entry points appear only
  for scope kinds listed in `scope_kinds` while `state` is `available`. A `404`
  from an older server hides them. Both shells reset and refresh it on every
  server or profile change.
- `ShufflePlayback` (shared) is one running shuffle inside a player. It keeps
  the last shuffle the server returned, and treats `409` from a read as "nothing
  in the scope can play any more". Other failures keep the last pick.

## Entry points

| Scope | Phone | TV |
| --- | --- | --- |
| Library (movie, TV, mixed) | Shuffle chip before the library tabs | Shuffle pill in the Library tab's Sort/Filter row |
| Series | "Shuffle Series", first in the series overflow menu | "Shuffle Series", first in More Actions |
| Season | "Shuffle Season N" after it, shown with two or more playable episodes | Same, in season mode only |
| Server collection | Top-bar Shuffle button | Shuffle pill in the collection's control row |
| User collection | Top-bar Shuffle button | Shuffle pill beside the title |

Neither app has a separate season screen: a season is the series page with that
season selected. Android TV has no screen for mixed libraries.

## Player rules

- The player route carries `shuffleId`. Every pick starts at `0.0` (the start
  over position), and a multi-part item opens at its first part.
- While a shuffle plays, the player resolves no sequential next episode. The
  phone tabletop Next button, the TV Up Next transport button, and the
  SiloCast `hasNextEpisode` flag are off.
- The up-next card reuses the next-episode flow and countdown. It reads
  "Shuffling <scope>" (a season reads "<series> · <season>"), shows a movie by
  its title without an S·E line, and adds Pick Another and Stop shuffling.
- The card announces the `next` the server returned. When the card opens, the
  player reads the shuffle again so the server can replace a pick that can no
  longer play. When `next` equals the item playing, or the read answers `409`,
  the card shows Finished.
- Play Now and the countdown call `advance` with the item that played, then
  play the returned `current`. Pick Another calls `skip`; a new pick restarts
  an at-end countdown. Stop shuffling deletes the shuffle and leaves the
  player.
- A multi-part pick plays each later part in place before the card appears.
- Leaving the player with Back keeps the shuffle on the server. Playing an On
  Deck item from the phone card leaves the shuffle.
- Starting a shuffle opens the local player; it is not launched on a
  connected SiloCast target.
