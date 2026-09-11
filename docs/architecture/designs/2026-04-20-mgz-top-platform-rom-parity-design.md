# MGZ Top Platform ROM Parity Design

## Summary

Restore Marble Garden Zone top-platform behavior to a ROM-faithful model by rebuilding its carried-player flow around true object-control semantics instead of the current `controlLocked` approximation.

The immediate scope is:

- `MGZTopPlatformObjectInstance`
- the existing `MGZTriggerPlatformObjectInstance` regression surface
- narrowly scoped shared player/solid/hurt code where MGZ exposes a real engine-level parity gap
- focused MGZ regression tests
- Sonic 1 GHZ and MZ trace replays as "must not get worse" checkpoint guards

This is not a general physics rewrite. Shared movement/collision changes are allowed only when they are required to match the ROM behavior the MGZ platform depends on.

## Problem

The current MGZ top-platform implementation reproduces large parts of the object-local state machine, but it does not reproduce the ROM's player-ownership model.

In the ROM, the grab path:

- sets `object_control`
- raises the MGZ-specific `status_tertiary` wall-cling state
- clears normal standing coupling
- hands effective ownership of the player's movement state to the platform until release

The current Java implementation instead uses:

- `controlLocked`
- wall-cling bits
- per-frame position snapping
- speed zeroing to suppress drift

That approximation leaves the player inside normal airborne movement/collision paths while the platform also manipulates the player. The result is a class of parity bugs rather than isolated object-local mistakes:

- springs can trigger but do not affect Sonic correctly while attached
- hurt/ring-loss behavior while attached diverges from the ROM's `status_tertiary` special case
- platform landing / nearby terrain / wall interaction is unstable
- the current code compensates for these mismatches with more MGZ-specific special cases instead of fixing the ownership model

## Goals

- Rebuild MGZ top-platform grab/release flow around true engine `objectControlled` semantics aligned with ROM `object_control`.
- Keep MGZ-specific `status_tertiary` wall-cling behavior because the ROM uses it for side-contact, ceiling-contact, and hurt handling.
- Remove engine-side compensation that only exists because normal airborne physics still run while the player is grabbed.
- Fix the concrete parity failures already identified:
  - spring interaction while grabbed
  - hurt/ring-loss behavior while grabbed
  - terrain and wall interaction when the platform lands, carries, or releases near solids
  - incomplete release semantics for secondary riders
  - missing skid presentation inside the carried-player ground-motion helper
- Preserve existing trigger-platform parity and launcher/twisting-loop regression coverage.
- Use Sonic 1 GHZ and MZ trace replays as baseline/checkpoint guards so shared-physics changes do not make broader physics parity worse.

## Non-Goals

- Create a new generic "captured player" framework for every object in the engine.
- Rewrite unrelated object-controlled objects to use a common abstraction in this pass.
- Convert the MGZ trigger platform into a broader MGZ event-system project.
- Require the GHZ and MZ trace replays to become fully passing as part of this work.
- Introduce hardware emulation or hardware-adjacent simulation beyond the current engine architecture.

## Architecture

### 1. Ownership model

`MGZTopPlatformObjectInstance` becomes the authoritative owner of a grabbed player by setting `objectControlled` at the same conceptual point where the ROM sets `object_control`.

While grabbed:

- the platform owns carried-player positioning and release state
- the platform still raises/consumes the MGZ wall-cling bits in `status_tertiary`
- the normal player movement pipeline must not also run its standard airborne physics/collision for that player

When the ROM clears `object_control`, Java clears `objectControlled` in the matching release path and returns the player to ordinary movement on the next frame.

### 2. MGZ-specific side channel

`status_tertiary` remains the MGZ-specific side channel for:

- wall-cling active state
- side-hit feedback from solid contact
- top-hit feedback from solid contact
- hurt-path special handling while attached

This state should stay explicit in `AbstractPlayableSprite`, because it already mirrors the ROM concept and other S3K parity work may depend on it. What changes is that it should complement object control, not replace it.

### 3. Shared-code scope discipline

Shared code may change only where MGZ reveals a genuine engine-level parity mismatch.

Expected touch points:

- `PlayableSpriteMovement`
  - only if object-controlled players still need specific non-physics behavior that the current early-return path suppresses incorrectly
- `ObjectManager`
  - only if solid-contact or damage sequencing disagrees with ROM behavior for a wall-cling/object-controlled player
- `AbstractPlayableSprite`
  - only for generic state semantics such as object control, wall-cling, or hurt interactions that should be true for the ROM model itself

This keeps the fix centered on MGZ parity and avoids using the object as a pretext for unrelated engine redesign.

## Behavior Design

### 1. Grab path

The grab path in `MGZTopPlatformObjectInstance` should:

- snap X as the ROM does
- set `objectControlled`
- set the MGZ wall-cling bit in `status_tertiary`
- clear normal standing/riding coupling
- set in-air / clear on-object semantics as the ROM does
- move the per-player platform routine into the grabbed state

It should stop relying on:

- `controlLocked` as the primary ownership mechanism
- speed-zeroing as a substitute for disabling normal physics
- comments/assumptions that "input lockout is enough"

### 2. Grabbed update

While the player is grabbed:

- the platform handles jump release
- the platform handles the carried-player ground-motion helper
- the platform handles centering / lateral launch math
- the platform handles MGZ terrain probes and related carry/release transitions
- the platform handles the MGZ wall-cling side/top feedback loop

The platform should no longer need to fight normal airborne level collision each frame.

### 3. Spring interaction

Spring behavior while grabbed should be corrected by restoring the right ownership model first, then reducing or replacing the current heuristic handoff if the ROM-equivalent state sequencing makes it unnecessary.

The target outcome is not merely "spring anim observed"; it is ROM-faithful spring influence on the player/platform interaction while in the attached MGZ state.

### 4. Hurt / ring behavior

The attached-player hurt path must respect the ROM's `status_tertiary` special case in `HurtCharacter`.

This means the implementation needs to decide hurt/ring behavior from the same effective state the ROM uses, not from a generic "player has rings" path alone. If the current generic lost-ring spawn ordering in `ObjectManager` conflicts with the ROM's attached-player behavior, it must be narrowed or refactored.

### 5. Terrain and wall behavior

When the platform lands or moves beside terrain/walls, the carried player must not simultaneously:

- receive normal airborne terrain collision from shared movement code, and
- receive independent MGZ carry/probe correction from the platform

The platform should be the sole owner of carried-player physical reconciliation until the ROM-equivalent release point.

### 6. Release behavior

Release paths must clear `objectControlled`, wall-cling state, and on-object state at the same points the ROM clears `object_control` and related status bits.

`sub_3519A` parity must include both:

- full release of active routine-4 riders
- clearing `Status_OnObj` for any occupied player slot, even when not in routine 4

### 7. Presentation parity

The carried-player ground-motion helper should restore the ROM skid presentation:

- skid animation
- skid SFX
- dust spawn

This is lower risk than the ownership-model work but still part of the correct ROM behavior.

## Baseline and checkpoint rules

Before code changes:

- run `TestS1Ghz1TraceReplay`
- run `TestS1Mz1TraceReplay`
- record whether they pass/fail and, if failing, the current divergence level

During implementation:

- re-run those same two trace replays at each shared-physics checkpoint
- accept existing failures only if they do not get worse than baseline

The traces are a regression guard, not a success gate for this project.

## Existing MGZ guards

Keep green:

- `TestS3kMgzTriggerPlatformObject`
- `TestS3kMgzTopLauncherTwistingLoopRegression`

These remain the minimum "do not break what already works" MGZ coverage.

## New MGZ regression coverage

Add focused MGZ top-platform tests for:

- grabbed spring interaction
- hurt/ring behavior while grabbed
- terrain/wall behavior during carry and release near solids
- release semantics when both main player and sidekick have occupied MGZ platform state

The new tests should prefer deterministic object-state and player-state assertions over renderer or screenshot checks.

## File Scope

Expected primary files:

- `src/main/java/com/openggf/game/sonic3k/objects/MGZTopPlatformObjectInstance.java`
- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- `src/main/java/com/openggf/level/objects/ObjectManager.java`
- `src/test/java/com/openggf/tests/TestS3kMgzTopLauncherTwistingLoopRegression.java`
- new MGZ top-platform regression tests under `src/test/java/com/openggf/tests/`

Files outside this set should be touched only if the implementation proves they are directly involved in the MGZ platform ownership path.

## Risks

- Changing object-control semantics can regress other captured-player objects if the shared behavior is altered too broadly.
- Reworking damage/ring sequencing in the wrong place could change non-MGZ hurt behavior.
- Trying to preserve the current `controlLocked` approximation while also adding `objectControlled` would likely create a hybrid state that is harder to reason about than either model alone.
- Overfitting to the existing MGZ launcher regression without adding focused tests would leave the newly identified parity gaps unguarded.

## Success Criteria

- MGZ top-platform grab/release behavior is modeled around true object control rather than `controlLocked` emulation.
- Springs affect the attached player/platform interaction in a ROM-faithful way.
- Hurt/ring behavior while attached follows the ROM's `status_tertiary`-driven path closely enough to remove the known discrepancy.
- Terrain/wall interaction during carry/release no longer exhibits the current dual-physics instability.
- Existing MGZ trigger/launcher regression tests still pass.
- Sonic 1 GHZ and MZ trace replays are no worse than their pre-change baseline.
