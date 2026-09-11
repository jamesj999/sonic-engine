# CNZ2 Knuckles Cutscene and End-Boss Parity Delivery Plan

Status: implementation-ready orchestration artifact  
Source blueprint: `docs/architecture/plans/cnz2-knuckles-cutscene-boss-parity-tasks.md`
Scope: Sonic/non-Knuckles CNZ Act 2 route, from the first rival-Knuckles encounter through the ICZ1 handoff.

This document translates the source blueprint into staged, delegated delivery work. The source blueprint is user-owned and must not be edited as part of this orchestration run.

## Requirements

### Goals

1. Match the locked-on S3K ROM behavior for both CNZ2 rival-Knuckles cutscenes and their wall, button, light-flash, palette, music, camera, and layout-mutation support.
2. Match `Obj_CNZEndBoss` entry, attack cycle, children, hit feedback, defeat graph, capsule release, cannon launch, and ICZ1 transition.
3. Preserve native center-coordinate, object-control, logical-input, fixed-point movement, child-slot, collision-list, and rewind semantics.
4. Add focused unit, headless, art, graph-rewind, and architecture-guard coverage for every behavior changed.
5. Deliver in small policy-compliant commits with disjoint ownership wherever practical.

### Non-goals

- Knuckles' alternate CNZ2 route.
- Advancing or making the CNZ trace frontier green.
- Recording, refreshing, or replaying a CNZ trace.
- G1 trace coverage or G2 screenshot/visual-trace validation from the source blueprint.
- Zone-, route-, or frame-specific trace carve-outs.
- Broad changes to shared player physics, collision, level-transition, rendering, or input systems when object-local behavior is sufficient.

### Constraints

- All coding, code review, and test implementation is delegated to subagents; the coordinator integrates and verifies.
- Do not run `TestS3kCnzTraceReplay`, `TestS3kCnzCompleteRunTraceReplay`, any other CNZ `*TraceReplay` test, stable-retro capture, or `s3k-zone-validate` visual comparison.
- Trace fixtures are read-only and are not regenerated or used to hydrate engine state.
- ROM `x_pos`/`y_pos` use center-coordinate APIs. Playable native position writes use `NativePositionOps` or preserve-subpixel setters according to the exact word/long write.
- Gameplay layout edits route through `ZoneLayoutMutationPipeline`; direct map mutation is forbidden.
- Objects use injected `ObjectServices`; no new singleton access.
- New real children require rewind recreation and parent/child relinking. Do not suppress coverage findings with unjustified transient annotations or baseline growth.
- Prefer S&K-half labels and addresses. The cited routines are in `docs/skdisasm/sonic3k.asm` unless stated otherwise.
- Preserve unrelated dirty and untracked files. Commits stage only assigned files and required documentation.
- Main-path `src/main` fixes require an `Unreleased` changelog entry and compliant commit trailers.

### Acceptance criteria

- Both cutscenes remain dormant outside their native camera windows, converge boundaries through the shared ROM camera gate, use terrain-probe landings, and preserve native animation timing and control-lock ordering.
- CNZ2A uses the `$1C,$1C,$1D` laugh, restores line 2 from a snapshot, restores boundaries gradually, fades to level music, and does not perform the S3-standalone-only line-1 restore.
- The button presses from range alone; placed subtypes 4 and 6 behave natively; subtype 6 mutates layer 0 cells `(0x8E,14..17)` to `$14,$0F,$0F,$88` through the mutation pipeline; pressed persistence and rewind are characterized.
- CNZ2B fades into and out of Knuckles music, animates the post-jump laugh, restores CNZ palette line 1 at walk-off, and keeps control locked continuously until the native shaft-exit release.
- The boss includes wind-down, horizontal magnet drop/bounces, descent-bottom reattachment, always-live magnet hazard, native child animations, hit flicker, and routine-0 child spawn timing.
- The defeat sequence uses a real Robotnik ship/head/flame/explosion graph, the two native waits, timer stop/fade/hide ordering, native body/arm/magnet debris, and absolute post-boss camera bounds derived from `$4760`.
- Cannon control arms on capture state 1, waits for the `$BF` timer and raw angle `$12`, then launches through forced logical jump input. The ICZ handoff performs no invented player neutralization before the transition pipeline takes ownership.
- Focused non-trace tests and rewind/static-state/art/architecture guards pass. No new rewind baseline entries are introduced without an explicit reviewed reason.
- `CHANGELOG.md` is updated. `docs/S3K_KNOWN_DISCREPANCIES.md` is updated only for an intentionally retained, user-visible divergence.

### Assumptions

- The existing S3K object-art provider's always-loaded `ObjectArtKeys.EXPLOSION` sheet is an architecture-equivalent replacement for the ROM's late `Queue_Kos_Module`; F3 needs a residency assertion, not duplicate loading, unless the assertion fails.
- Only button subtypes 4 and 6 occur on the scoped Sonic-path CNZ2 placement. Subtypes 0 and 2 are not implemented unless a fresh placement audit disproves this.
- `S3kSharedBossCameraGate` can be made reusable by CNZ2A without changing its existing consumers' behavior.
- The destination ICZ load already establishes the snowboard intro state, so source-side cannon state can survive until `requestZoneAndAct(..., true)` freezes the old level.
- Shared `SongFadeTransitionInstance`, palette ownership, mutation pipeline, terrain probes, and object lifetime helpers are sufficient; no new cross-engine subsystem is expected.

### Risks

- Object-slot ordering can shift camera continuation, forced input, explosion allocation, or child first-dispatch timing even when visible states appear correct.
- A consolidated boss child can under-allocate SST slots and break rewind or later allocation order. E1 children must be real dynamic objects.
- Animation delay and countdown underflow conventions are easy to collapse by one dispatch.
- Rewind links can retain stale parent/child references after recreation.
- Palette ownership conflicts can make a correct byte copy invisible or overwrite an unrelated writer.
- Generalizing camera/boundary helpers can affect HCZ/LBZ/boss consumers. Characterization tests precede helper edits.
- Concurrent workers can collide in `CnzEndBossInstance`, `CutsceneKnucklesCnz2AInstance`, and shared tests. The plan serializes those ownership transfers.

## Exploration Synthesis

Three bounded explorations covered cutscene/support behavior, boss/defeat behavior, and capsule/cannon/ICZ handoff. Their conclusions agree with the source blueprint and narrow several implementation choices.

### Cutscene and support-object findings

- `CutsceneKnucklesCnz2AInstance` and `CutsceneKnucklesCnz2BInstance` currently lack the outer `Check_CameraInRange` lifetime gate. Use respawnable `ObjectLifetimeOps` semantics for windows A `$176..$300/$1C00..$1E00` and B `$720..$A00/$45C0..$46E0` (`sonic3k.asm:129034,129269,180433`).
- `src/main/java/com/openggf/game/sonic3k/objects/bosses/S3kSharedBossCameraGate.java` is prior art for `loc_85CA4`; expose/reuse it in CNZ2A and the boss rather than maintaining `CutsceneKnucklesCnz2AInstance.updateCameraLock()` and boss-local approximations. Music timer, min-Y, and min/max-X convergence are independent goals.
- Landing calls need `ObjectTerrainUtils.checkFloorDist` with the ROM probe radius `$13`; spawn-Y flooring is not equivalent (`loc_6237C`, `loc_620AA`).
- Raw animation cadence is run 5, jump 2, laugh 8 engine frames. Both post-bounce waits use `$1C,$1C,$1D`; `$1E,$1F` remains only the initial pose.
- `CutsceneKnucklesCnz2AInstance.restoreStoredCameraBounds()` must hand off to a generalized gradual controller supporting min-X down, min-Y down, and max-X up at the existing `$4000` subpixel ramp.
- S&K behavior snapshots/restores palette line 2 at CNZ2A and leaves line 1 alone until CNZ2B. The current A-side `restoreLevelPaletteLine1()` follows the wrong `s3.asm` half.
- Use `SongFadeTransitionInstance` for B4/C1. Monitor art is standalone in the engine, so prove readiness rather than emulating a destructive raw PLC reload.
- `Cnz2CutsceneButtonInstance` already has the proximity calculation but adds `hasReachedButtonImpact()`. Remove that handshake after terrain-probe landings make range authoritative.
- Placement analysis found only button subtypes 4 and 6 in scope. Do not invent subtype 0/2 or `_unkFAA9` consumers. Characterize respawn-bit/pressed-state persistence and rewind first.
- The subtype-6 edit is layer 0, X `$8E`, Y `14..17`, bytes `$14,$0F,$0F,$88`, routed through `ZoneLayoutMutationPipeline`.
- CNZ2B currently unlocks briefly between exit phases; the ROM keeps `Ctrl_1_locked` set until the final camera-Y/object-Y condition.

Evidence files: `CutsceneKnucklesCnz2AInstance.java`, `CutsceneKnucklesCnz2BInstance.java`, `CutsceneKnuxCnz2WallInstance.java`, `Cnz2CutsceneButtonInstance.java`, `CnzLightsFlashChildInstance.java`, `Sonic3kCNZEvents.java`, `TestCutsceneKnucklesCnz2Instance.java`, `TestCnzLightsFlashChildInstance.java`, `TestS3kCnz2CutsceneButtonGraphRewind.java`.

### Boss and defeat findings

- D1-D8 and E1-E6 are genuine gaps in `CnzEndBossInstance` and its current children.
- Add a distinct wind-down routine after the `$FF` magnetic phase. Arm slowdown follows parent bit 7 and decrements every `$40` frames.
- `CnzEndBossMagnetChild` needs X velocity `+/-$100` toward the closest participating player and move-before-gravity fixed-point integration; each qualifying bounce halves/negates Y and plays the floor-thump sound.
- Reattachment occurs when descent reaches magnet Y minus `$14`; the magnet remains a `$8B` hazard from init until defeat.
- Magnet and arm animations require their exact multi-delay scripts and parent-bit/routine gates. Boss body render skips alternate hit-timer frames in addition to the existing palette flash.
- Magnet, four arms, and Robotnik ship spawn only when routine 0 begins after the shared camera continuation, not when the gate merely completes.
- Robotnik ship subtype 9 must be a real child using frame 9, with real Robotnik head, flame, and explosion-controller children. Reuse HCZ ship behavior only after contract comparison; CNZ escape and defeat ownership remain CNZ-specific.
- Zero hits begins a `$3F` visible wait with the HUD timer stopped. Fade/hide occurs only after it; a second roughly 120-dispatch wait separates debris from capsule creation.
- Body halves use offsets `-/+$14`, frames B/C, velocities `-/+$100,-$100`, no gravity, flicker, and camera culling. Arms scatter in their existing slots; magnet becomes two sparks.
- Post-boss bounds use the camera-gate base `$4760`: capsule max X `$48F0`, cannon max X `$4A70`, stored min Y `$0200`. The current `savedCameraMaxX` base is incorrect.

Evidence files: `CnzEndBossInstance.java`, `CnzEndBossMagnetChild.java`, `CnzEndBossArmChild.java`, `CnzEndBossFieldChild.java`, `CnzEndBossDefeatDebrisChild.java`, `CnzEndBossBoundaryController.java`, `CnzEndBossRewindLinks.java`, `HczEndBossRobotnikShip.java`, `TestS3kCnzEndBossHeadless.java`, `TestS3kCnzEndBossGraphRewind.java`.

### Capsule, cannon, ICZ, validation, and policy findings

- ROM `loc_6E778/loc_6E7B6/loc_6E7E4/loc_6E80C` is at `sonic3k.asm:146050-146105`. The boss arms when cannon per-P1 byte `$30` becomes 1, counts `$BF`, then waits for raw cannon angle `$12` before writing A/B/C held and press bits.
- `CnzCannonInstance` has matching private states idle/pulling/ready/cooldown and `spinAngle`, but exposes only ready-state/direct-launch APIs. Add capture-state and raw-angle contracts; use its existing `isJumpPressed()` path for the boss-forced launch.
- `CnzEndBossInstance.updatePostDefeatSequence()` currently arms late and calls `triggerEndSequenceLaunch()` directly. It should set forced jump input at angle `$12`, let the cannon consume it, and clear the forced input after release.
- `Sonic3kObjectArtProvider.loadArtForZone()` loads ordinary explosion art for every zone, and cannon puffs already render `ObjectArtKeys.EXPLOSION`. F3 is therefore a focused readiness test unless it fails.
- `preparePlayersForIczFade()` is an engine-only neutralization/hide step absent from the ROM. `requestZoneAndAct(ICZ,0,true)` freezes immediately and the ICZ load already installs snowboard X/Y velocity and air state. Remove the source-side rewrite and update the integration expectation.
- Existing coverage is strong but asserts the old behavior in places. Primary tests are `TestCnzCannonInstance`, `TestS3kCnzTeleporterRouteHeadless`, `TestS3kCnzDirectedTraversalHeadless`, `TestCnzTraversalObjectArt`, `TestS3kCnzEndBossHeadless`, `TestS3kCnzEndBossGraphRewind`, and `TestS3kCnzTraversalPlayerReferenceRewind`.
- `CHANGELOG.md` must change for main-path fixes. `docs/S3K_KNOWN_DISCREPANCIES.md` changes only for a deliberately retained divergence. `docs/status/trace-frontier-log.md` remains untouched because no permitted trace run or frontier move is part of this delivery.

### Scope corrections and resolved conflicts

- B3 follows S&K exactly: line-2 snapshot/restore at A; line-1 CNZ restore at B. The S3-standalone behavior is not retained.
- B4 does not raw-reload monitor PLC data because the engine owns monitor art as an immutable standalone sheet; readiness coverage proves the equivalent invariant.
- B6 does not add unused subtypes 0/2 or speculative `_unkFAA9` state.
- F3 does not duplicate globally resident explosion art without a failing residency test.
- F4 removes the source-side neutralization rather than treating it as a required transition contract.
- G1 and G2 are explicitly deferred by user instruction, not silently omitted.

## Architecture Decision

### Ownership and boundaries

- Zone/cutscene choreography stays in the CNZ cutscene instances and `Sonic3kCNZEvents`.
- Cross-zone ROM helper semantics live in narrowly reusable helpers: `S3kSharedBossCameraGate`, gradual boundary controller behavior, and `SongFadeTransitionInstance`.
- Button-triggered layout changes enter through `ZoneLayoutMutationPipeline`; palette writes remain under palette ownership.
- Boss cycle and defeat orchestration remain in `CnzEndBossInstance`; visual/collision/lifetime-owning SST parts are real child objects.
- Cannon capture/spin/launch remains owned by `CnzCannonInstance`; the boss observes capture/angle and writes logical input only.
- Level transition remains owned by the existing transition coordinator through `ObjectServices.requestZoneAndAct`; CNZ code does not mutate destination state.

### Lifecycle and data flow

1. Camera-window gate admits a cutscene or boss and records approach bits.
2. Shared camera gate independently converges music timer, Y bounds, and X bounds, then invokes one continuation.
3. Cutscene objects own native control, animation, terrain landing, support-object activation, palette, music, and gradual-boundary restoration until they expire via native lifetime semantics.
4. Boss routine 0 creates a real ship, magnet, and four arm children. Parent state exposes only the flags/routines children consume.
5. On defeat, the ship owns explosion/escape presentation while the parent owns wait sequencing, score/timer state, camera workers, capsule, and later cannon controller role.
6. Capsule results notify the live parent. The parent widens bounds and spawns the cannon.
7. Cannon capture byte is observed by the parent; after timer+angle eligibility the parent forces logical jump, and the cannon performs the native launch.
8. The position threshold requests ICZ1 and freezes CNZ. Destination load initializes the new player state.

### Rewind and identity

- Every gameplay timer, routine, angle, velocity, collision flag, animation cursor, spawn latch, and continuation token is captured.
- Parent/child references use `CnzEndBossRewindLinks`-style identity relinking. Structural renderers/services remain transient with reasons.
- Child constructors encode immutable subtype/offset data in `ObjectSpawn` where possible.
- Button pressed state and placement respawn behavior receive graph rewind tests before implementation assumptions are accepted.

### Failure modes

- Art readiness failure leaves a queued/pending state visible to tests rather than silently drawing corrupt data.
- Missing parent after rewind causes native child expiry or explicit relink failure, never adoption of an unrelated instance.
- Camera helpers complete each goal monotonically and invoke continuation once.
- Forced jump is cleared after the cannon consumes it or if the cannon disappears; it cannot leak into ICZ.
- Mutation failures are surfaced through the pipeline and tested; no direct fallback write is permitted.

### Migration and rollback

- Characterize existing helper consumers first, then generalize without changing their public behavior.
- Land cutscene infrastructure, cutscene A/B, button, boss core, boss graph, defeat, and handoff as separate commits.
- Each commit is independently revertible. New helper APIs retain old call adapters until all consumers migrate in the same verified commit.
- No save/config/data migration is required. Rewind schema changes are additive through generic field capture/recreate contracts.
- Rollback of a child graph removes its registration/relink/tests together; never leave placeholder child references in the parent.

## Feature Design

### Behavior and contracts

- Camera range helper returns approach flags and an in-range decision; out-of-range placed objects expire with their placement eligible to respawn.
- Shared camera gate accepts lock targets, approach flags, saved music/timer state, and a one-shot continuation. Its three goal bits progress independently.
- Cutscene animation helper uses explicit engine-frame durations derived from ROM delay+1.
- Gradual boundary workers declare axis, direction, target, and `$4000` fixed-point rate and stop exactly at target.
- Palette line-2 snapshot is value data owned by CNZ2A and rewind-captured. CNZ2B owns the line-1 CNZ restore.
- Button presses solely on the native half-open range. Subtype 6 submits four deterministic mutation commands before spawning tube controllers.
- Boss `Routine` gains wind-down and defeat subphases rather than overloading descend or one timer.
- Magnet exposes native current position and attached/drop state; parent signals reattach at descent bottom.
- Ship/head/flame/debris/spark children are independent `AbstractObjectInstance` objects with native allocation/lifetime and rewind recreate contracts.
- Cannon exposes read-only capture state and raw angle. It does not expose a boss-only direct-launch shortcut as the primary path.

### Edge cases

- Camera approaches from below/right, music timer completes before bounds, or one axis begins already locked.
- Cutscene unload/re-entry, death/reload, rewind before and after button press, and sidekick presence without sidekick ownership of Sonic-path control.
- Terrain distance is negative, zero, or outside snap range on a bounce frame.
- Palette owner changes while flash/restore children are active.
- Boss is defeated during hit flicker; children see defeat flags in slot order.
- Magnet reaches floor with Y velocity below `$80`, reattaches during descent, or parent disappears after rewind.
- Cannon timer expires at a non-`$12` angle, angle reaches `$12` before timer expiry, forced input is seen one object dispatch later, or cannon disappears while armed.
- ICZ threshold is met on the launch frame; old player state remains frozen until destination initialization.

### Acceptance tests

- Camera helper: independent goal ordering, right/below approach, timer-only completion, one continuation.
- Cutscene A/B: camera gate, terrain landing, exact mapping-frame durations, laugh selection, palette snapshot/restore, fade ownership, continuous control lock, gradual bounds.
- Button: half-open range, no impact handshake, subtypes 4/6, exact mutation cells/values, pressed respawn/rewind behavior.
- Boss core: wind-down duration, arm slowdown cadence, magnet X movement/move-before-gravity/bounces/reattach/hazard, magnet and arm scripts, body flicker, routine-0 spawn boundary.
- Defeat: timer stop, `$3F` wait, fade/hide boundary, second wait, real ship/head/flame/explosion graph, debris velocities/frames/flicker/culling, arm/magnet scatter, exact bounds.
- Handoff: capture-state arming, timer underflow plus angle `$12`, forced-input launch, explosion art residency, unchanged source launch state at freeze, ICZ snowboard destination state.
- Rewind: snapshots on both sides of every new spawn/handoff, relink identity, no stale parent or player reference.

## Implementation Plan

All behavior tasks are tests-first. Workers must report changed files, tests run, failures, and self-review findings. Ownership transfers below are sequential where files overlap.

### Phase 0 — baseline and helper characterization

Owner: shared-infrastructure worker. No other worker edits shared camera/fade/boundary helpers during this phase.

1. Add characterization tests for all existing `S3kSharedBossCameraGate` and `CnzEndBossBoundaryController` consumers.
2. Add failing tests for independent camera goals, approach-from-right/below, timer ordering, and gradual min-X/min-Y/max-X movement.
3. Expose/generalize the helpers minimally; retain old adapters.
4. Verify:

```bash
mvn "-Dtest=*SharedBossCameraGate*,TestS3kCnzEndBossHeadless,TestCutsceneKnucklesCnz2Instance" test
```

Dependency: none. Commit before phases 1 and 4 begin.

### Phase 1 — CNZ2A cutscene

Owner files: `CutsceneKnucklesCnz2AInstance.java`, `CutsceneKnuxCnz2WallInstance.java`, A-focused portions of `TestCutsceneKnucklesCnz2Instance.java`.

1. Add failing tests for range lifetime, approach flags, terrain probe, jump/laugh cadence, line-2 snapshot, no line-1 restore, fade, and gradual bounds.
2. Implement A1-A4 and B1-B5 using Phase-0 helpers and `SongFadeTransitionInstance`.
3. Assert monitor art remains ready after cutscene cleanup.
4. Verify:

```bash
mvn "-Dtest=TestCutsceneKnucklesCnz2Instance,TestCnzLightsFlashChildInstance,TestS3kCnzLocalMechanicsRewind,TestS3kCnzMechanismRewind" test
```

Dependency: Phase 0.

### Phase 2 — CNZ2B cutscene

Owner files: `CutsceneKnucklesCnz2BInstance.java`, B-focused portions of `TestCutsceneKnucklesCnz2Instance.java` after Phase 1 commits.

1. Add failing tests for range lifetime, terrain landing, `$1C/$1D` wait animation, fade transitions, line-1 restore, and uninterrupted control lock.
2. Implement C1-C4.
3. Verify:

```bash
mvn "-Dtest=TestCutsceneKnucklesCnz2Instance,TestS3kCnzLocalMechanicsRewind,TestS3kCnzMechanismRewind" test
```

Dependency: Phase 1 test-file ownership is released.

### Phase 3 — button, flash, and layout mutation

Owner files: `Cnz2CutsceneButtonInstance.java`, `CnzLightsFlashChildInstance.java`, `Sonic3kCNZEvents.java`, button/flash/mutation tests.

1. Inventory CNZ2 placements and write tests proving only subtypes 4/6 are scoped.
2. Characterize pressed-state placement lifetime and graph rewind.
3. Add failing range-only and exact four-cell mutation tests.
4. Remove the impact handshake; implement native persistence and subtype-6 pipeline mutation. Do not add speculative `_unkFAA9` state.
5. Verify:

```bash
mvn "-Dtest=TestCnzLightsFlashChildInstance,TestS3kCnz2CutsceneButtonGraphRewind,TestS3kCnzLocalMechanicsRewind,TestNoDirectMapMutationsInGameplay,TestObjectServicesMigrationGuard" test
```

Dependency: Phase 1, because it removes the A-side handshake contract.

### Phase 4 — boss camera entry and core attack cycle

Owner files: `CnzEndBossInstance.java`, `CnzEndBossMagnetChild.java`, `CnzEndBossArmChild.java`, `CnzEndBossFieldChild.java`, core boss tests.

1. Add failing tests for shared-gate spawn boundary, wind-down timing, magnet movement/order/bounces/reattach/hazard/script, arm cadence/script, and hit flicker.
2. Migrate entry to Phase-0 camera gate and spawn children at routine 0.
3. Implement D1-D8 without touching defeat/handoff behavior beyond necessary routine enum migration.
4. Extend graph rewind tests for new routine/animation/velocity state.
5. Verify:

```bash
mvn "-Dtest=TestS3kCnzEndBossHeadless,TestS3kCnzEndBossGraphRewind,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard" test
```

Dependency: Phase 0. Commit before phases 5-7 edit the parent.

### Phase 5 — real Robotnik ship graph

Owner files: new CNZ ship/head/flame/explosion child classes, `CnzEndBossRewindLinks.java`, new ship graph tests. The worker does not edit `CnzEndBossInstance.java` until Phase 4 is committed and ownership is explicitly transferred.

1. Compare `HczEndBossRobotnikShip` contract with `Obj_RobotnikShip4`; add failing frame-9, head animation/hurt, explosion, rise, flame, escape, and Boss-flag tests.
2. Implement real child objects and rewind recreate/relink paths.
3. In a short serialized integration edit, replace the parent's inline frame-5 ship draw and connect routine-0/defeat signals.
4. Run art corruption coverage if mappings/art registration changes.
5. Verify:

```bash
mvn "-Dtest=TestS3kCnzEndBossHeadless,TestS3kCnzEndBossGraphRewind,TestSonic3kPlcArtRegistry#s3kArtRegistryMappingsStayWithinSaneSpriteSheetLimits,TestPatternSpriteRendererCorruptionGuard,TestRewindCoverageGuard" test
```

Dependency: Phase 4.

### Phase 6 — defeat timeline and scatter

Owner files: `CnzEndBossInstance.java`, `CnzEndBossDefeatDebrisChild.java`, arm/magnet defeat branches, new spark/debris helpers, defeat tests. This is a serialized parent ownership transfer after Phase 5.

1. Add failing tests for HUD timer stop, `$3F` pre-fade visibility, fade/hide boundary, second wait, capsule timing, body halves, arm/magnet scatter, and exact bounds.
2. Implement E2-E6, with E1's ship owning explosion presentation.
3. Prefer a narrowly shared FlickerMove utility only if characterization shows identical semantics in another S3K boss; otherwise keep it CNZ-local.
4. Extend graph rewind coverage across both defeat waits and every child type.
5. Verify:

```bash
mvn "-Dtest=TestS3kCnzEndBossHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzEndBossGraphRewind,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard" test
```

Dependency: Phase 5.

### Phase 7 — capsule, cannon, and ICZ handoff

Owner files: `CnzEndBossInstance.java`, `CnzCannonInstance.java`, `CnzEggCapsuleInstance.java` only if a real capsule defect is found, handoff/cannon/art tests.

1. Add failing tests for state-1 arming, `$BF` underflow, raw angle `$12`, forced-input launch, input cleanup, art residency, and source-state preservation at transition freeze.
2. Add read-only capture-state/raw-angle cannon APIs; route boss launch through forced logical input.
3. Remove `preparePlayersForIczFade()` and preserve launch state until destination load.
4. Assert ICZ load still installs `$0800/$0280` snowboard state.
5. Verify:

```bash
mvn "-Dtest=TestCnzCannonInstance,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalObjectArt,TestS3kCnzTraversalPlayerReferenceRewind,TestS3kCnzEndBossGraphRewind" test
```

Dependency: Phase 6, because the same parent method owns the post-defeat flow.

### Phase 8 — integration, docs, and complete non-trace verification

Owner: integration worker/coordinator. Feature workers stop editing shared files.

1. Rebase mental model against the source blueprint; inspect every task A1-F4 and G3/G4.
2. Update `CHANGELOG.md`. Update `docs/S3K_KNOWN_DISCREPANCIES.md` only for a deliberately retained divergence. Do not update `docs/status/trace-frontier-log.md` without an actual permitted frontier event.
3. Discover and verify the root S3K ROM, then run the focused non-trace suite:

```bash
S3K_ROM_PATH="$(find . -maxdepth 1 -type f -name '*.gen' -print -quit)"
sha1sum "$S3K_ROM_PATH"
mvn "-Ds3k.rom.path=$S3K_ROM_PATH" "-Dtest=TestCutsceneKnucklesCnz2Instance,TestCnzLightsFlashChildInstance,TestS3kCnz2CutsceneButtonGraphRewind,TestS3kCnzLocalMechanicsRewind,TestS3kCnzMechanismRewind,TestS3kCnzEndBossHeadless,TestS3kCnzTeleporterRouteHeadless,TestS3kCnzDirectedTraversalHeadless,TestCnzCannonInstance,TestCnzTraversalObjectArt,TestS3kCnzEndBossGraphRewind,TestS3kCnzTraversalPlayerReferenceRewind,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestNoDirectMapMutationsInGameplay,TestObjectServicesMigrationGuard,TestPatternSpriteRendererCorruptionGuard" test
mvn "-Ds3k.rom.path=$S3K_ROM_PATH" "-Dtest=TestSonic3kPlcArtRegistry#s3kArtRegistryMappingsStayWithinSaneSpriteSheetLimits" test
mvn "-Ds3k.rom.path=$S3K_ROM_PATH" -DskipTests package
```

Expected S3K locked-on SHA-1: `CFBF98C36C776677290A872547AC47C53D2761D6`. If the discovered ROM does not match, do not run ROM-backed acceptance tests with it.

4. Review staged files and commit trailers before every commit. Do not stage the user-owned source blueprint unless the user separately requests it.

### Conditional non-CNZ frontier regression strategy

No planned task requires shared main-path physics/engine changes, so no trace test is part of normal verification. If a worker nevertheless changes shared player physics, collision, object scheduling, logical input, camera phase ordering, level-transition execution, or rendering publication:

1. Stop integration and justify why object-local implementation is impossible.
2. Run the repository's established currently-green trace replay tests across affected S1, S2, and S3K paths, explicitly excluding every class or method whose name contains CNZ.
3. Compare first divergent frame and mismatch count against `docs/status/trace-frontier-log.md`; any changed non-CNZ frontier is a blocker unless proved to be an intended ROM-correct advancement and documented per policy.
4. Never run CNZ trace tests, stable-retro CNZ capture, or visual trace validation in this delivery.

The exact sweep command must be selected from the current green inventory at integration time rather than hard-coding a stale fleet. Record the selected classes and results in the Integration Report.

## Integration Report

Implementation landed as the following policy-compliant sequence, in dependency order:

- `f493fd6c0` — created this delivery/orchestration artifact.
- `715446f31` — implemented CNZ2 button proximity, subtype-4/6 actions, pressed-state handling,
  and the subtype-6 mutation-pipeline edit.
- `9aa728aa9` — implemented magnet/arm attack-cycle behavior and child animation/hazard state.
- `136d25955` — aligned both rival-Knuckles cutscenes: terrain landings, animation, palette,
  fades, control sequencing, and gradual boundaries.
- `33817170e` and `e78357f83` — exposed cannon capture/angle state and preserved the ROM's
  old-angle-then-increment launch ordering.
- `55951095d` — implemented native body-half, arm, magnet-spark, flicker, and culling scatter.
- `a37386406`, `049f94750`, and `4cf97491c` — completed the boss parent/ship graph, corrected
  child motion gates, body flicker, and arm reset cadence.
- `f42311423` and `aa59f561e` — corrected cutscene landing contact cadence, per-dispatch native
  camera-window checks, pressed-button offscreen lifetime, and 91/121 post-init fade timing.
- `506800134` — completed the two-wait defeat timeline, absolute camera bounds, capsule/cannon
  handoff, forced-input launch, and ICZ transition ownership.
- `4e1ba9f20` — restored compact rewind coverage for the final camera-gate helper and exact
  end-cannon object identity.
- `fcfea2a16` and `5e81e136e` — corrected and integrated the shared camera gate's live-X
  left-approach behavior and added left/right/below, independent-goal, placement, and art tests.
- `7f5f5e55a` — bound the subtype-4 explosion controller to the real ship graph and preserved
  Robotnik-head animation state through hurt/defeat presentation.

Requirements A1-F4 and permitted validation work G3/G4 are complete for the scoped Sonic route.
The final curated non-trace matrix passed **131/131**, and the repository package build completed
successfully. The locked-on root ROM was used unchanged as
`Sonic and Knuckles & Sonic 3 (W) [!].gen` (expected SHA-1
`CFBF98C36C776677290A872547AC47C53D2761D6`). Rewind graph, instance/static coverage, art
readiness/corruption, object-service, mutation-routing, and focused headless coverage were included
in that matrix.

No shared player physics, collision, object scheduling, or level-transition execution behavior was
changed. The only cross-cutting main change was compact rewind schema support for an existing final
`RewindStateful` value; focused rewind/coverage guards passed. A pre-existing
`TestS3kCnzDirectedTraversalHeadless#cnzCylinderForcedReleaseClearsInvalidRiderStateWithoutUsingTheJumpPath`
failure was reproduced identically at baseline `f493fd6c0`; relevant cylinder code was unchanged, so
that unrelated failure was excluded from the final matrix. No conditional trace-frontier sweep was
required.

The three intentional architecture equivalences are now executable assertions:

- B6 omits button subtypes 0/2 because the locked-on CNZ2 placement contains only Obj83 subtypes
  4 and 6.
- B4 does not reload `PLC_Monitors` because the engine owns monitor art as an independent immutable
  ready sheet, unaffected by cutscene-Knuckles art loading.
- F3 does not queue duplicate explosion art because the shared explosion renderer remains resident
  and ready in CNZ2.

The source task's F1 description called angle `$12` "straight up"; ROM `sub_3192C` derives mapping
frame 6 before storing angle `$14`, so the native launch vector is the tested diagonal
`x_vel=$0B50`, `y_vel=-$0B50`. The implementation and tests follow the ROM rather than that gloss.

`CHANGELOG.md` records the user-visible changes. No known discrepancy was retained, so
`docs/S3K_KNOWN_DISCREPANCIES.md` correctly remains unchanged. The source task plan is user-owned,
untracked, and intentionally unedited. Other unrelated dirty/user files were preserved and excluded
from every commit.

G1 trace capture/replay and G2 screenshot/visual validation remain explicitly deferred. No CNZ
trace, visual comparison, stable-retro capture, or trace-fixture regeneration was run.

## End-to-End Review

Independent review found **no known implementation blocker** after the final follow-up commits.

- **Requirements traceability:** A1-A4, B1-B7, C1-C4, D1-D8, E1-E6, F1-F4, and permitted G3/G4
  map to committed source and focused tests. G1/G2 are documented deferrals mandated by the task.
- **Architecture and ownership:** choreography remains in CNZ cutscene/boss objects; shared camera,
  fade, boundary, palette, mutation, lifetime, and rewind facilities retain their established owners.
  No zone/frame trace carve-out, direct gameplay map mutation, singleton access from object code, or
  invented source-side ICZ neutralization was introduced.
- **ROM parity:** review rechecked `Check_CameraInRange`, `loc_85CA4`, both CNZ2 Knuckles routines,
  `Obj_CutsceneButton`, `Obj_CNZEndBoss`, `Obj_RobotnikShip4`, `Obj_CNZCannon`, and the final
  `loc_6E80C` handoff. The last camera blocker—using boundary easing rather than live
  `Camera_X_pos` during left approach—was corrected by `fcfea2a16` and covered in the integrated
  boss path by `5e81e136e`.
- **Rewind/lifecycle/object slots:** real ship/head/flame/explosion, magnet, arms, scatter, boundary,
  fade, button, and cannon objects have recreate/capture paths. Review caught and fixed the final
  camera-helper compact-state omission, stale end-cannon identity, and explosion-controller ship
  parent/lifetime link in `4e1ba9f20`/`7f5f5e55a`.
- **Review fixes:** per-dispatch camera deletion semantics, button pressed-state unload latch,
  post-init fade off-by-one timing, cannon old-angle ordering, live-X camera convergence, end-cannon
  relink, and ship-bound explosion lifetime were all found during review and resolved before closure.
- **Residual risk:** G1/G2 provide no full-route trace or screenshot evidence by explicit request;
  compact `RewindStateful` value support is broader than CNZ although its focused graph/coverage
  guards are green; and the unrelated pre-existing cylinder test remains outside this delivery.
- **Documentation/test gaps:** none blocking. The source task plan remains intentionally unmodified;
  its F1 vector gloss is corrected here. The curated matrix and package build are green.

Human checklist before merge:

1. Confirm the listed 17-commit scope and target branch.
2. Confirm the final test evidence and accepted G1/G2 deferral.
3. Keep `.gitignore`, `.idea/vcs.xml`, `docs/status/rewind-round-trip-gaps.md`, and the untracked source task plan
   out of the merge unless their owner separately requests them.
4. Push the final branch and satisfy the repository's README-on-merge policy when merging into
   `develop`.

Implementation is ready for merge; formal merge approval remains with the human reviewer.

## Artifact Self-Review

- Requirements gate: **green** — goals, exclusions, user constraints, assumptions, risks, and testable acceptance criteria are explicit.
- Exploration gate: **green** — all three bounded explorations are synthesized with source files, ROM labels/lines, conflicts, and scope corrections.
- Architecture gate: **green** — ownership, lifecycle, data flow, failure modes, migration, rewind, and rollback are defined using existing project patterns.
- Feature-design gate: **green** — contracts, edge cases, and acceptance tests map to A1-F4 and G3/G4.
- Implementation-plan gate: **green** — tests precede behavior, overlapping parent/test ownership is serialized, dependencies are explicit, and every phase has a non-trace verification command.
- Deferral gate: **green** — G1 and G2 remain visibly deferred, and the completed Integration Report
  and End-to-End Review record that neither was run.
- Safety gate: **green** — CNZ traces and visual validation are forbidden; conditional frontier work excludes CNZ and activates only for shared main-path changes.
- Delivery-evidence gate: **green** — the final commit sequence, ROM provenance, 131/131 curated
  non-trace matrix, package success, baseline-excluded unrelated failure, and documentation/trailer
  decisions are recorded above.
- Closure gate: **green** — independent review found no known blocker; residual risks and the final
  human checklist are explicit.
