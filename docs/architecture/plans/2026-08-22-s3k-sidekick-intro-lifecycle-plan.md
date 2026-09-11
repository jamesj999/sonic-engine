# Plan: suppress S3K sidekick presentation during intro setup

Design: [S3K intro sidekick presentation lifecycle](../designs/2026-08-22-s3k-sidekick-intro-lifecycle-design.md)

## Scope

Fix the setup-only presentation leak that lets a registered S3K sidekick render
before its ROM-owned intro CPU state is active. Preserve the ROM lifecycle:

`SpawnLevelMainSprites` creates Player 2 → setup-only `Process_Sprites` runs
`Tails_Init` without the CPU routine → first ordinary Tails dispatch owns the
AIZ dormant marker → AIZ resize at camera `$1308` releases routine `$02`.

The same semantic hook will cover ICZ's existing dormant-marker presentation,
without changing its current gameplay timing. SSZ's separate `$0A00` ROM branch
and any new release mapping remain out of scope.

## Implementation steps

### 1. Add regression tests first (RED)

Update `TestS3kAizIntroEventsHeadless` to assert:

- setup leaves the controller in `INIT`, leaves normal gameplay spawn fields
  unchanged, and sets only the presentation suppression;
- the first ordinary dispatch still enters `DORMANT_MARKER`, writes the ROM
  sentinel/control state, and remains hidden;
- an early release does not change the latch;
- the committed AIZ `$1308` release enters catch-up and restores the saved
  hidden value;
- a pre-existing hidden value is restored rather than unconditionally cleared.

Update `TestS3kIcz1SnowboardIntroHeadless` to verify hidden presentation during
the intro and restoration after the existing crash release. Keep the ICZ
gameplay timing assertion separate: `applyZonePlayerState()` eagerly applies
the ROM marker after sidekick spawn, the setup-only `Process_Sprites` slot
resets the controller to `INIT` without running its CPU routine, and the first
ordinary CPU pass reaches the same provider-owned marker branch. The test must
retain the existing post-bootstrap `DORMANT_MARKER`/sentinel assertions without
claiming that AIZ's first-dispatch timing applies to ICZ.

Extend sidekick rewind coverage in the existing controller rewind test with
round-trip assertions for the latch fields and add a reset/reload assertion.

Add `src/test/java/com/openggf/sprites/playable/TestTailsRendering.java`, using
a mocked `TailsTailsController` and `PlayerSpriteRenderer`, to prove hidden
Tails calls neither child nor parent rendering, while visible behavior keeps
the existing child-first contract. Exercise the ordinary S2-style controller
(`isS3k=false`) for visible parent/child and spindash-dust behavior so the
shared `Tails.draw()` guard does not change normal S2 output.

In `TestS3kAizIntroEventsHeadless`, drive the AIZ plane intro through the
Knuckles `$918` spawn threshold and assert Tails remains owned by its dormant
marker; only the existing resize `$1308` handoff may release it.

Add an explicit S3K provider-gate regression (in the existing S3K event/runtime
state test location) that evaluates the sidekick dormant predicate for SSZ's
`$0A00` branch and confirms it does not arm the new presentation latch while
SSZ remains outside the provider's AIZ/ICZ release mapping.

Run the focused tests and confirm the new assertions fail for the expected
missing setup suppression/child-render behavior, not because of a test or
fixture error.

### 2. Implement controller-owned setup presentation (GREEN)

In `SidekickCpuController`:

- add `initialPresentationSuppressed` and saved prior-hidden state;
- add a public setup method that delegates to
  `shouldEnterLevelEventDormantMarker()` and sets hidden without changing
  position, velocities, state, routine, control bits, or animation;
- clear both latch fields in `resetCpuState`;
- include both fields in `captureRewindState` and `restoreRewindState`;
- in `releaseDormantMarkerForLevelEvent`, restore the saved hidden value only
  for an owned latch, then clear ownership after a successful release.

Keep the first ordinary AIZ CPU dispatch and existing ICZ bootstrap marker
paths unchanged.

### 3. Invoke the setup hook at the correct boundary (GREEN)

In `SpriteManager.initializeInitialAssemblyPlayableSlot`, after the existing
Player 2 setup state/position restoration and only for the first CPU sidekick,
invoke the controller setup presentation method. Do not add a zone/game-name
branch in `SpriteManager` and do not call the eager marker method for AIZ.

In `Sonic3kLevelEventManager`, prime the same controller hook after event
initialization and after zone-player setup, so suppression exists before the
initial setup pass and survives shared-level provider reinitialization. The
controller's initial setup reset preserves an already-armed presentation latch;
ordinary fresh-level reset still clears it.

### 4. Close the child-render hole (GREEN)

Move the hidden check to the beginning of `Tails.draw()` so the parent and its
independent tail child are both suppressed. Keep the existing visible draw
order and S2 dust behavior intact for non-hidden Tails.

### 5. Focused verification and review

Run the AIZ, ICZ, controller, rewind, and rendering focused tests. Inspect the
diff for ROM references, absence of zone/game carve-outs, and correct snapshot
coverage. Run the relevant architectural/source guards.

### 6. Full verification

Run the full JDK 21 Maven test suite and compare failures against the clean
baseline. Run `mvn package` if the final integration workflow requires the
executable build. Record exact commands and outcomes in the final report and
update the trace-frontier log only if a trace frontier actually moves.

## Files expected to change

- `docs/architecture/designs/2026-08-22-s3k-sidekick-intro-lifecycle-design.md`
- `docs/architecture/plans/2026-08-22-s3k-sidekick-intro-lifecycle-plan.md`
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- `src/main/java/com/openggf/sprites/playable/Tails.java`
- `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`
- focused tests under `src/test/java/com/openggf/`
- `src/test/java/com/openggf/game/sonic3k/TestS3kSidekickIntroPresentationGate.java`

No ROM assets, trace payloads, or trace-to-engine state hydration are needed.
