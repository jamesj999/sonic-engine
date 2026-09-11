# Rewind Boundary Policy Design

## Problem

Live rewind currently captures ordinary level play, but level/session transitions can replace major runtime state: loaded level data, object managers, special-stage providers, bonus-stage coordinators, title-card flow, music state, and mode-specific render/camera setup. Rewinding across those boundaries is unsafe unless the destination mode has its own complete rewind coverage.

The current code already has two partial safeguards:

- Seamless level transitions re-root live rewind through `LiveRewindManager.resetBufferAtCurrentFrame(...)`.
- `LevelManager.loadZoneAndAct(...)` resets the `RewindController` to frame zero after a level load.

The second safeguard bypasses `LiveRewindManager`, so live input history, HUD state, reverse audio presentation, and reverse fade presentation are not centrally reset with the controller. Special-stage and bonus-stage entry/exit paths also do not express an explicit rewind boundary policy.

The existing live-mode gate already prevents steady-state rewind while `currentGameMode != GameMode.LEVEL`: `handleRealtimeRewindInput(...)` and `recordExternalFrame(...)` both clear or ignore non-level modes. The new boundary policy is not a replacement for that gate. Its job is deterministic teardown and re-rooting at the state-commit point, before stale reverse audio/fade presentation, deferred audio restore state, or input rows can survive until the next non-level frame.

## Goals

- Prevent live rewind from crossing level loads, special-stage entry/exit, bonus-stage entry/exit, title/data/master-title transitions, and other non-level mode boundaries.
- Keep ordinary level rewind available after a boundary by starting a fresh rewind segment.
- Make boundary handling centralized so controller, input history, audio presentation, fade presentation, and HUD state move together.
- Preserve the current invariant that live rewind only runs in `GameMode.LEVEL`.
- Leave future special-stage rewind as a separate segment-owned feature, not as a cross-boundary extension of level rewind.

## Non-Goals

- Do not add special-stage rewind coverage in this change.
- Do not make bonus-stage rewind work unless it is already covered by ordinary level/object adapters after a fresh segment starts.
- Do not allow rewind from a special stage back into the level that launched it.
- Do not globally change object-reference rewind behavior.

## Proposed Architecture

Add a central boundary API on `LiveRewindManager`:

```java
public void markBoundary(RewindBoundary boundary)
```

`RewindBoundary` should describe intent rather than caller location. Initial values:

- `LEVEL_LOAD`: a new level/act/session segment has been loaded; reset to frame zero.
- `MODE_EXIT_TO_NON_REWINDABLE`: leaving ordinary level play for a mode that live rewind does not cover.
- `MODE_ENTER_REWINDABLE`: entering ordinary level play after a transition; ensure a fresh current-state segment exists.
- `SEAMLESS_LEVEL_TRANSITION`: in-level transition that consumes a frame and should re-root at the current frame.

The manager should be responsible for:

- Ending any active or coasting rewind presentation.
- Committing/dropping deferred audio restore through existing controller semantics.
- Resetting reverse audio and reverse fade presentation.
- Clearing or truncating `LiveRewindInputSource` consistently with the controller.
- Re-rooting the `RewindController` through `resetToFrameZero()` or `resetBufferAtCurrentFrame()`.
- Clearing HUD/rewinding flags so the next segment starts clean.

`markBoundary(...)` must re-resolve the active `GameplayModeContext`, `RewindController`, and `LiveRewindInputSource` at call time rather than trusting previously cached fields. Boundaries often happen during session and level replacement; stale cached controller/input-source pairs are exactly what the API is meant to prevent.

`LevelManager` should stop directly calling `RewindController.resetToFrameZero()`. Instead, `GameplayModeContext` should expose a small rewind-boundary reporter, installed by `GameLoop` and backed by `LiveRewindManager`. This keeps `LevelManager` independent of UI-loop classes while still letting level loading report committed boundaries through the central live-rewind policy.

When Trace Test Mode owns rewind for the active session, live rewind boundary reporting must be a no-op and the existing `TraceSessionLauncher.recordExternalRewindFrameAtBoundary()` path remains authoritative. Do not let live rewind reset trace input sources, trace rewind controllers, or trace comparator cursors.

## Boundary Placement

The implementation should mark boundaries at state-commit points, not merely when fades begin:

- After `LevelManager.loadZoneAndAct(...)` has completed and registered new rewind adapters: `LEVEL_LOAD`.
- After a seamless transition has applied: `SEAMLESS_LEVEL_TRANSITION`.
- When entering special-stage mode: `MODE_EXIT_TO_NON_REWINDABLE`.
- When entering bonus-stage title card/bonus-stage mode after loading the bonus zone: `LEVEL_LOAD`, then `MODE_EXIT_TO_NON_REWINDABLE` while the current mode is `TITLE_CARD` or `BONUS_STAGE`.
- When returning from special or bonus stages and reloading/restoring the level: `LEVEL_LOAD`, then `MODE_ENTER_REWINDABLE` when control returns to `GameMode.LEVEL`.
- When leaving gameplay for master title, data select, level select, credits, ending, or editor teardown: `MODE_EXIT_TO_NON_REWINDABLE`.

`LEVEL_LOAD` followed by `MODE_ENTER_REWINDABLE` must be idempotent. `LEVEL_LOAD` performs the hard re-root to frame zero after new adapters are registered. `MODE_ENTER_REWINDABLE` may then install or verify the live rewind segment, but it must not append input, advance the controller, or preserve any pre-load rows. Calling `MODE_ENTER_REWINDABLE` more than once for the same committed level state should leave the controller/input source aligned and should not create a second synthetic base row.

Holding the rewind key during a transition should not replay pre-boundary state. Once the player is back in a rewindable level segment, rewind should only access frames captured after that boundary.

## Future Special-Stage Rewind

Special-stage rewind should be implemented later as a separate mode-owned segment:

- Register special-stage-specific snapshottables for provider/runtime state.
- Use a special-stage input source and stepper that match that mode's simulation.
- Re-root on special-stage entry and exit.
- Keep the hard boundary between level history and special-stage history.

This avoids pretending normal level adapters can restore special-stage state and keeps failed coverage loud.

## Testing

Add focused tests around the boundary API and integration points:

- `LiveRewindManager` boundary tests prove active rewind presentation is ended, input history is cleared, and controller history is re-rooted.
- Add a desync regression test for the current latent failure mode: after `LEVEL_LOAD`, assert `inputSource.earliestFrame()`, `inputSource.frameCount()`, and `rewindController.currentFrame()` are mutually consistent and contain no stale pre-load base frame.
- Level-load tests prove old level frames are inaccessible after `loadZoneAndAct(...)`.
- Special-stage entry tests prove level history is cut before `GameMode.SPECIAL_STAGE`.
- Bonus-stage entry/exit tests prove the bonus stage and returned level do not share rewind history.
- Existing rewind gates should remain unchanged; this is policy hardening, not coverage ratchet movement.

Where full level loading is too expensive for a unit test, use a small fake `GameplayModeContext`/controller harness and reserve one integration test for real `GameLoop` boundary flow.

## Success Criteria

- A held rewind can never seek across level, special-stage, bonus-stage, or mode-change boundaries.
- Rewind remains available within the new ordinary level segment after a boundary.
- Audio/fade reverse presentation cannot leak across a boundary.
- Trace Test Mode's existing boundary reroot behavior remains intact.
- The design leaves an explicit path for future special-stage rewind without weakening the boundary rule.
