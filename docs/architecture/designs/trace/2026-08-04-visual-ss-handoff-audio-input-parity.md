# Visual special-stage handoff audio and input parity

## Problem

The S1 big-ring route emits the special-stage entry SFX while the results
screen owns the fade, then emits it again when `GameLoop` consumes the held
white screen.  Whole-run visual replay also has a seam between special-stage
local input and the next level's shared BK2 input.  If the playback session or
boundary probe still owns the special-stage row at that seam, the first GHZ2
ticks can receive the prior special-stage input without the destination
comparator observing the destination row.

## Design

Transition audio is owned by the transition phase that starts it.  A
special-stage request that arrives with an already-complete HOLD_WHITE fade
has already played its entry SFX during the fade, so the generic entry method
must not play the same transition SFX again.  HOLD_BLACK does not carry that
same ownership proof and therefore retains the generic emission.  This is
expressed by an explicit fade-owner predicate, not by game, zone, route, or
trace.

The shared playback bridge remains the sole level input owner and the
`BoundaryProbe` remains the sole playback frame observer for a run.  Special
stage input overrides are cleared at the end of each local row.  On destination
admission, the probe drops its cached delegate, attaches the new shared
comparator, and `PlaybackDebugManager.startSession` resets its prepared and
edge state at the destination's absolute BK2 row before the first level tick.
The first destination tick therefore reads the destination row and publishes
that row through the destination comparator.  No trace values hydrate gameplay;
	the recorded BK2 row is comparison/input authority only, as in headless replay.

The title-card release itself is the final pre-production admission seam. The
shared `GameLoop` release path invokes the run coordinator after it changes the
mode back to `LEVEL` and before its same-step fall-through input/gameplay work.
This covers results-return title cards that perform a setup-only release and
therefore do not pass through the ordinary level admission callback. The
coordinator still receives the structural destination-row count (zero or one)
from the shared cursor; it is never capped or rebased after production.

The release callback is also the authoritative structural signal that the
current title card is no longer pending. If a level-load request flag remains
latched while the results-return presentation is being torn down, the release
observation clears only that admission predicate; it does not mutate level
state or consume trace data. A new level-load request still blocks admission
until its own title card releases.

Completed level loads are identified by a monotonic production-load generation
owned by `LevelManager`, not by `LevelData` object identity. Same-zone/act
reloads intentionally reuse the same data object, so reference comparison
cannot distinguish a special-stage return or death reload from no load at all.
The run tracker consumes the generation only as structural lifecycle evidence
and continues to derive the destination identity from the loaded runtime.

## Invariants

* Already-faded and newly-fading transitions each issue one entry SFX.
* HOLD_BLACK remains a one-SFX path unless an explicit prior audio owner is
  introduced.
* The special-stage logical override is absent before GHZ2 level production.
* The first GHZ2 input is read from its segment offset, not from the last
  special-stage row.
* The destination comparator is installed behind the persistent boundary probe
  before destination production, so mismatches are visible immediately.
* No second level load is introduced; the existing production load and title
  card remain the lifecycle owners.
* A direct destination admission publishes the current destination input to the
  rebuilt player immediately, covering loads that do not pass through a title
  card.
* A title-card release cannot advance a destination row before the coordinator
  has had its pre-production admission opportunity, including setup-only
  releases from special-stage results.
* Reloading the same `LevelData` instance still publishes a new level-load
  receipt and generation.

## Verification

Focused tests cover the fade/SFX ownership predicate and the playback session
rebind/first-destination-row path.  The full Maven suite is run before and
after the change; pre-existing baseline failures are compared by report rather
than treated as feature failures.

Deferred scheduling is guarded by the coordinator's accepted matching load and
transition-gap phase, not by the boundary probe latch.  `level_load` boundaries
intentionally do not latch on the probe, but their accepted load is still a
valid input-cursor rebind seam.
