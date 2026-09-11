# S1 Bug Triage And Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduce, root-cause, and fix the reported Sonic 1 gameplay, object, rewind, and trace-parity bugs without adding zone, route, frame, or trace-specific carve-outs.

**Architecture:** Treat this as a set of independent bug workstreams, not one broad refactor. Object-local issues stay in S1 object or boss classes with disassembly citations; shared player/input/rewind/audio fixes must be proven against cross-game behavior before touching shared code.

**Tech Stack:** Java 17, Maven, JUnit 5, S1 disassembly under `docs/s1disasm`, trace replay tests under `src/test/java/com/openggf/tests/trace/s1`, rewind tests under `src/test/java/com/openggf/game/rewind`.

---

## Workstream Summary

| Workstream | Bugs | Primary owners | First verification |
|---|---|---|---|
| Input and special stage | Can't jump in special stages; held direction feels intermittently weak | `Sonic1SpecialStageManager`, `InputHandler`, `LogicalInputSnapshot`, `LiveRewindInputSource` | `mvn "-Dtest=TestSpecialStageLogicalInput,Sonic1SpecialStageManagerTest,TestInputHandlerLogicalSnapshot,TestLiveRewindLogicalInput" test` |
| S1 object visuals | Egg prison button persists; glass reflection oscillates incorrectly; lamppost head duplicate; lavafall landing flicker | `Sonic1EggPrison*`, `Sonic1Glass*`, `Sonic1Lamppost*`, `Sonic1LavaGeyser*` | focused object tests plus S1 MZ/LZ/SYZ/SLZ trace subset |
| Badnik and touch responses | Roller standing-state defeat; Yadrin top spikes; hurt-on-slope animation | `Sonic1RollerBadnikInstance`, `Sonic1YadrinBadnikInstance`, `ObjectTouchResponseController`, playable hurt logic | focused badnik tests plus `TestS1Syz*TraceReplay` |
| Springs and bumpers | Hurt spring recovery; SYZ spring-bumper loop clipping; possible bumper/spring emulator parity | `Sonic1SpringObjectInstance`, `Sonic1BumperObjectInstance`, player object-control timers | focused spring/bumper tests plus BizHawk confirmation for parity question |
| LZ water/current | Blocked doorway current softlock; breath timer rewind; waterfall-column 1px gap; underwater conveyor spawn/despawn | `Sonic1LZEvents`, `Sonic1LZWaterEvents`, `WaterSystem`, `DrowningController`, `Sonic1LZConveyorObjectInstance`, `Sonic1WaterfallObjectInstance` | `mvn "-Dtest=TestS1Lz1CompleteRunTraceReplay,TestS1Lz2CompleteRunTraceReplay,TestS1Lz3CompleteRunTraceReplay,TestWaterSystemRewindSnapshot,TestSharedWaterEffectGraphRewind" test` |
| Rewind and audio | Invincibility music cuts wrong; speed shoes can last indefinitely; breath countdown rewind; SLZ3 boss stops dropping balls | `AudioManager`, `TimerManager`, `SpeedShoesTimer`, `DrowningController`, `Sonic1SLZBossInstance`, `Sonic1SLZBossSpikeball` | rewind tests plus SLZ3 trace |
| Diagnostics and logging | MZ getSpawnIndex identity miss; S1 SFX warnings | `AbstractPlacementManager`, `ObjectPlacementController`, `Sonic1SmpsLoader` | log reproduction under MZ3 plus targeted unit test |
| Verification-only questions | SYZ ring slope behavior; SYZ running angle; spring into bumper emulator parity | trace capture / BizHawk probes first | no engine change until ROM behavior is proven |

## Global Rules For Every Task

- Use `bugfix/ai-s1-bug-batch` or a more specific `bugfix/ai-s1-...` branch. Do not mix unrelated work into the branch after execution starts.
- Before changing behavior, reproduce the bug or produce a targeted diagnostic that shows the failing state.
- Every gameplay, physics, object, audio, and rewind behavior fix must be backed by observed ROM behavior: S1 disassembly, trace data, or a BizHawk probe/capture. Do not land expectation-based fixes because the engine behavior "looks wrong" unless the ROM evidence agrees.
- For trace-class bugs, use `trace-replay-bug-fixing`: trace data remains read-only and engine state must not be hydrated from trace rows.
- Fixes must not regress trace tests. If any trace that passed at the Task 1 baseline starts failing, stop and treat that regression as part of the same root-cause investigation before landing the fix.
- For S1 object or badnik changes, use `s1-implement-object` and `s1disasm-guide`; cite the S1 disassembly file and line numbers for non-obvious behavior.
- Use center-coordinate APIs for ROM `obX` / `obY`. Use top-left `getX()` / `getY()` only for sprite bounds or explicit render extents.
- If `src/main/` engine behavior changes in a `fix:` commit, update `CHANGELOG.md` or give an explicit changelog trailer justification.

---

### Task 1: Baseline Reproduction And Bug Ledger

**Files:**
- Create: `docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md`
- Read: `docs/status/trace-frontier-log.md`
- Read: `target/trace-reports/*` if present

- [ ] **Step 1: Create the working branch**

Run:
```powershell
git switch -c bugfix/ai-s1-bug-batch
```
Expected: branch switches to `bugfix/ai-s1-bug-batch`.

- [ ] **Step 2: Run the current focused S1 trace baseline**

Run:
```powershell
mvn "-Dtest=TestS1Ghz1TraceReplay,TestS1Mz3CompleteRunTraceReplay,TestS1Syz1CompleteRunTraceReplay,TestS1Syz3CompleteRunTraceReplay,TestS1Lz1CompleteRunTraceReplay,TestS1Lz2CompleteRunTraceReplay,TestS1Slz2CompleteRunTraceReplay,TestS1Slz3CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```
Expected: record pass/fail status, first-error frame/field for each failing trace, and exact command in the ledger.

- [ ] **Step 3: Run focused non-trace tests for already-covered components**

Run:
```powershell
mvn "-Dtest=TestSpecialStageLogicalInput,Sonic1SpecialStageManagerTest,TestSonic1GlassBlockObjectInstance,TestSonic1GlassReflectionGraphRewind,TestSonic1LavaGeyserOutOfRange,TestSonic1LavaGeyserGraphRewind,TestSonic1YadrinBadnikInstance,TestSonic1SpringObjectInstance,TestSonic1StaircaseWallCollision,TestSonic1StaircaseActivation,TestS1SlzBossSpikeballGraphRewind,TestS1SyzBossBlockGraphRewind,TestLiveRewindManagerAudioCleanup,TestLiveRewindSpeedModifiers,TestWaterSystemRewindSnapshot,TestSharedWaterEffectGraphRewind" test
```
Expected: capture current pass/fail state in the ledger.

- [ ] **Step 4: Create the ledger**

Write one row per bug with: symptom, zone/act/coordinates, reproduction route, suspected owner, current automated coverage, first failing command, and status `untriaged`, `reproduced`, `fixed`, `rom-confirmed-intentional`, or `blocked-needs-capture`.

- [ ] **Step 5: Commit only the ledger if execution will span multiple sessions**

Run:
```powershell
git add docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md
git commit -m "docs: add S1 bug batch triage ledger"
```
Required trailers: `Changelog: n/a: planning-only`, `Guide: n/a`, `Known-Discrepancies: n/a`, `S3K-Known-Discrepancies: n/a`, `Agent-Docs: n/a`, `Configuration-Docs: n/a`, `Skills: n/a`.

---

### Task 2: Fix Input Edge Regressions Before Gameplay-Specific Bugs

**Bugs covered:**
- Can't jump in special stages anymore.
- Direction sometimes feels barely held for a frame.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java`
- Modify only if evidence points there: `src/main/java/com/openggf/control/InputHandler.java`
- Modify only if evidence points there: `src/main/java/com/openggf/control/LogicalInputSnapshot.java`
- Modify only if evidence points there: `src/main/java/com/openggf/game/rewind/LiveRewindInputSource.java`
- Test: `src/test/java/com/openggf/TestSpecialStageLogicalInput.java`
- Test: `src/test/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManagerTest.java`
- Test: `src/test/java/com/openggf/control/TestInputHandlerLogicalSnapshot.java`
- Test: `src/test/java/com/openggf/game/rewind/TestLiveRewindLogicalInput.java`

- [ ] **Step 1: Add a failing special-stage jump edge test**

Assert that a held jump input produces exactly one `pressedButtons & INPUT_JUMP` edge when Sonic is grounded, and that `Sonic1SpecialStageManager.processJump()` makes Sonic airborne with `SS_JUMP_FORCE`-derived velocity.

- [ ] **Step 2: Add a held-direction continuity test**

Assert that holding left or right for 120 frames produces no zeroed logical frame unless the physical input source released the direction.

- [ ] **Step 3: Run the focused input tests and confirm failure**

Run:
```powershell
mvn "-Dtest=TestSpecialStageLogicalInput,Sonic1SpecialStageManagerTest,TestInputHandlerLogicalSnapshot,TestLiveRewindLogicalInput" test
```
Expected: at least one new assertion fails before implementation.

- [ ] **Step 4: Root-cause the edge loss**

Trace `handleInput` into `heldButtons` and `pressedButtons`. If rewind or frame-step input filtering is consuming an edge, fix that owner. If special stage is clearing `pressedButtons` before the stage update sees it, fix `Sonic1SpecialStageManager`.

- [ ] **Step 5: Verify**

Run:
```powershell
mvn "-Dtest=TestSpecialStageLogicalInput,Sonic1SpecialStageManagerTest,TestInputHandlerLogicalSnapshot,TestLiveRewindLogicalInput,TestS1Ghz1TraceReplay" "-DfailIfNoTests=false" test
```
Expected: focused input tests pass and GHZ trace does not regress.

---

### Task 3: Fix S1 Object Visual Lifetime Bugs

**Bugs covered:**
- Egg prison capsule button visual persists after destruction.
- Glass block reflection oscillates without the block lowering.
- Lamppost head sometimes stops duplicated and X-offset.
- Single-frame flicker where a vertical lavafall will land when it starts.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1EggPrisonObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1EggPrisonButtonObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1GlassBlockObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1GlassReflectionInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1LamppostObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1LamppostTwirlInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1LavaGeyserMakerObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1LavaGeyserObjectInstance.java`
- Test: `src/test/java/com/openggf/game/rewind/TestSonic1EggPrisonButtonGraphRewind.java`
- Test: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1GlassBlockObjectInstance.java`
- Test: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1GlassReflectionGraphRewind.java`
- Test: `src/test/java/com/openggf/game/rewind/TestCheckpointStarpostGraphRewind.java`
- Test: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1LavaGeyserOutOfRange.java`
- Test: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1LavaGeyserGraphRewind.java`

- [ ] **Step 1: Egg prison reproduction**

Add a test that triggers `Sonic1EggPrisonButtonObjectInstance.onSolidContact`, advances the capsule into/through destruction, and asserts the button either stops rendering or is destroyed when the parent no longer expects it.

- [ ] **Step 2: Glass reflection reproduction**

Add a test for subtype 4 switch-activated lowering where parent `glassDist` decreases and the reflection uses the parent-driven lowering path, not independent oscillation.

- [ ] **Step 3: Lamppost reproduction**

Add a test that activates a lamppost, advances twirl through finish, and asserts there is one terminal head/twirl visual centered on the parent X.

- [ ] **Step 4: Lavafall flicker reproduction**

Add a test for a subtype-nonzero `Sonic1LavaGeyserMakerObjectInstance` that advances the first lavafall spawn frame and asserts no body/third piece renders at the eventual landing Y before the head reaches its ROM-created position.

- [ ] **Step 5: Run tests and capture failures**

Run:
```powershell
mvn "-Dtest=TestSonic1EggPrisonButtonGraphRewind,TestSonic1GlassBlockObjectInstance,TestSonic1GlassReflectionGraphRewind,TestCheckpointStarpostGraphRewind,TestSonic1LavaGeyserOutOfRange,TestSonic1LavaGeyserGraphRewind" test
```

- [ ] **Step 6: Fix each root cause independently**

Use S1 disassembly files:
- `docs/s1disasm/_incObj/3E Prison Capsule.asm`
- `docs/s1disasm/_incObj/30 MZ Large Green Glass Blocks.asm`
- `docs/s1disasm/_incObj/79 Lamppost.asm`
- `docs/s1disasm/_incObj/4C & 4D Lava Geyser Maker.asm`

Do not reuse one visual workaround across these objects unless disassembly proves the same lifecycle rule.

- [ ] **Step 7: Verify object tests and affected traces**

Run:
```powershell
mvn "-Dtest=TestSonic1EggPrisonButtonGraphRewind,TestSonic1GlassBlockObjectInstance,TestSonic1GlassReflectionGraphRewind,TestCheckpointStarpostGraphRewind,TestSonic1LavaGeyserOutOfRange,TestSonic1LavaGeyserGraphRewind,TestS1Mz3CompleteRunTraceReplay,TestS1Syz3CompleteRunTraceReplay,TestS1Slz3CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

---

### Task 4: Fix Badnik And Player Hurt Contact Semantics

**Bugs covered:**
- SYZ Roller cannot be defeated when standing at the start.
- Sonic sometimes does not enter hurt animation when injured while climbing a slope.
- Yadrin has spikes on its head, but Sonic can jump on it anyway.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/objects/badniks/Sonic1RollerBadnikInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/badniks/Sonic1YadrinBadnikInstance.java`
- Modify only if evidence points there: `src/main/java/com/openggf/level/objects/ObjectTouchResponseController.java`
- Modify only if evidence points there: playable hurt handling under `src/main/java/com/openggf/sprites/playable/`
- Test: create or extend `src/test/java/com/openggf/game/sonic1/objects/badniks/TestSonic1RollerBadnikInstance.java`
- Test: extend `src/test/java/com/openggf/game/sonic1/objects/badniks/TestSonic1YadrinBadnikInstance.java`

- [ ] **Step 1: Add Roller standing-state contact tests**

Cover Roller initial standing/unfolded state and rolling state separately. Assert the initial destroyable state matches `docs/s1disasm/_incObj/43 Badnik - Roller.asm` collision writes, not a blanket invincible state.

- [ ] **Step 2: Add Yadrin spike-region tests**

Use `docs/s1disasm/_incObj/Sonic ReactToItem.asm` `React_Yadrin` as reference. Test the top spiked region hurts an attacking player, while non-spike contact still follows normal enemy defeat rules.

- [ ] **Step 3: Add hurt-on-slope animation reproduction**

Create a headless player test with a sloped ground mode, trigger hurt, and assert hurt animation/state survives the slope landing/angle path.

- [ ] **Step 4: Verify failing tests**

Run:
```powershell
mvn "-Dtest=TestSonic1RollerBadnikInstance,TestSonic1YadrinBadnikInstance,TestPlayableSpriteMovement,TestS1Syz1CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

- [ ] **Step 5: Implement object-local fixes first**

Only touch shared touch/player code if both Roller/Yadrin disassembly and the focused tests prove the shared controller is misordering hurt/defeat state.

- [ ] **Step 6: Cross-game sanity when shared code changes**

Run:
```powershell
mvn "-Dtest=TestS1Syz1CompleteRunTraceReplay,TestS2Ehz1TraceReplay,TestS3kAizTraceReplay" "-DfailIfNoTests=false" test
```

---

### Task 5: Fix Spring, Bumper, And Object-Control Recovery

**Bugs covered:**
- When injured, landing on a spring bounces Sonic up, but control is not restored.
- SYZ spring into bumper can knock Sonic down repeatedly and eventually clip into the spring.
- Determine whether the spring-bumper loop is possible on emulator.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1SpringObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1BumperObjectInstance.java`
- Modify only if evidence points there: `src/main/java/com/openggf/sprites/playable/ObjectControlState.java`
- Modify only if evidence points there: playable hurt/control-lock code under `src/main/java/com/openggf/sprites/playable/`
- Test: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1SpringObjectInstance.java`
- Test: create `src/test/java/com/openggf/game/sonic1/objects/TestSonic1BumperObjectInstance.java` if no S1 bumper test exists

- [ ] **Step 1: Add injured-spring control recovery test**

Set Sonic hurt, land him on an upward S1 spring, assert bounce velocity applies and control-lock/hurt-state timers match S1 ROM behavior after the bounce window.

- [ ] **Step 2: Add spring-bumper collision-loop test**

Build a headless fixture with a spring below and bumper above at the reported SYZ route section. Step repeated bounce contacts and assert Sonic does not penetrate the spring solid bounds.

- [ ] **Step 3: Confirm emulator behavior before deciding expected result**

Use BizHawk with S1 REV01 and a short movie/probe around the same section. If ROM also traps Sonic in the loop, preserve the loop but still forbid engine-only clipping. If ROM escapes cleanly, compare bumper velocities, spring inactive frames, and hurt/object-control flags.

- [ ] **Step 4: Verify**

Run:
```powershell
mvn "-Dtest=TestSonic1SpringObjectInstance,TestSonic1BumperObjectInstance,TestS1Syz1CompleteRunTraceReplay,TestS1Syz2CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

---

### Task 6: Fix LZ Water, Door, Countdown, And Conveyor Rewind Bugs

**Bugs covered:**
- LZ1 water current activates even when doorway is blocked, causing softlock.
- Sonic underwater breath timer and countdown do not rewind correctly.
- LZ2 1px gap between vertical waterfall column and slide water.
- Underwater conveyor blocks may despawn incorrectly or rewind may interfere with their spawn.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/events/Sonic1LZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic1/events/Sonic1LZWaterEvents.java`
- Modify: `src/main/java/com/openggf/level/WaterSystem.java`
- Modify: `src/main/java/com/openggf/sprites/playable/DrowningController.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1LZConveyorObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1WaterfallObjectInstance.java`
- Test: `src/test/java/com/openggf/tests/TestTodo34_WaterSlideDetection.java`
- Test: `src/test/java/com/openggf/level/TestWaterSystemRewindSnapshot.java`
- Test: `src/test/java/com/openggf/game/rewind/TestSharedWaterEffectGraphRewind.java`
- Test: trace tests `TestS1Lz1CompleteRunTraceReplay`, `TestS1Lz2CompleteRunTraceReplay`, `TestS1Lz3CompleteRunTraceReplay`

- [ ] **Step 1: Reproduce LZ1 doorway-current state**

Add a headless event test that sets the doorway blocked state and asserts the water current trigger remains inactive until the same ROM flag/state that opens the path is set.

- [ ] **Step 2: Reproduce breath countdown rewind**

Extend rewind tests to capture drowning/breath timer state, countdown object state, and audio cue phase before rewind, mutate by stepping, rewind, and assert exact restoration.

- [ ] **Step 3: Reproduce LZ2 waterfall seam**

Add a mapping/geometry assertion for slide water and vertical waterfall column extents at the reported location. The expected value must come from S1 ROM layout/object data, not visual preference.

- [ ] **Step 4: Reproduce conveyor spawn/despawn**

Run LZ2 complete-run trace and inspect `slot_dump`/object near events for conveyor block slot changes. If trace lacks enough data, extend diagnostics before engine changes.

- [ ] **Step 5: Verify**

Run:
```powershell
mvn "-Dtest=TestTodo34_WaterSlideDetection,TestWaterSystemRewindSnapshot,TestSharedWaterEffectGraphRewind,TestS1Lz1CompleteRunTraceReplay,TestS1Lz2CompleteRunTraceReplay,TestS1Lz3CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

---

### Task 7: Fix Rewind-Audio And Rewind-Boss State

**Bugs covered:**
- Rewind plus invincibility can cut invincibility audio at the wrong time.
- Rewind plus speed shoes can cause speed shoes to last indefinitely.
- Rewinding on SLZ3 boss causes Robotnik to stop dropping spike balls.
- Sonic underwater breath countdown rewind overlaps with Task 6 and should share the same rewind-state audit.

**Files:**
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/timer/TimerManager.java`
- Modify: `src/main/java/com/openggf/timer/timers/SpeedShoesTimer.java`
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1SLZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1SLZBossSpikeball.java`
- Test: `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java`
- Test: `src/test/java/com/openggf/game/rewind/TestLiveRewindSpeedModifiers.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS1SlzBossSpikeballGraphRewind.java`
- Test: `src/test/java/com/openggf/audio/TestAudioKeyframeReplay.java`

- [ ] **Step 1: Add invincibility audio rewind reproduction**

Snapshot during invincibility music, step until a different music state would be queued, rewind, and assert the logical audio snapshot restores invincibility music and does not prematurely emit the off command.

- [ ] **Step 2: Add speed-shoes duration reproduction**

Snapshot with active speed shoes and finite remaining timer, step beyond expiry, rewind, and assert the restored timer still expires after the original remaining duration.

- [ ] **Step 3: Add SLZ3 boss spikeball rewind reproduction**

Use boss graph rewind fixture to snapshot before a spikeball drop cycle, step through one or more drops, rewind, and assert timer/routine/child-spawn phase still produces the next spikeball.

- [ ] **Step 4: Run tests and confirm failures**

Run:
```powershell
mvn "-Dtest=TestLiveRewindManagerAudioCleanup,TestLiveRewindSpeedModifiers,TestS1SlzBossSpikeballGraphRewind,TestAudioKeyframeReplay,TestS1Slz3CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

- [ ] **Step 5: Fix missing rewind state at the owner**

Prefer central rewind adapters/codecs for timers/audio/boss graph state. Do not mark gameplay state transient to silence coverage.

- [ ] **Step 6: Run coverage guards**

Run:
```powershell
mvn "-Dtest=TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestRemainingRewindCoverageClosure" test
```

---

### Task 8: Fix SYZ Boss Spike Handoff

**Bug covered:**
- In SYZ3 boss, Robotnik can leave his spike in the air when he retreats.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1SYZBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/SYZBossSpike.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS1SyzBossBlockGraphRewind.java`
- Test: create/extend `src/test/java/com/openggf/game/sonic1/objects/bosses/TestSonic1SyzBossInstance.java`

- [ ] **Step 1: Add retreat-state spike test**

Advance the boss into `STATE_ASCENT` and `STATE_ESCAPE`, assert the spike child retracts or self-expires exactly as `docs/s1disasm/_incObj/75 Boss - Spring Yard.asm` does.

- [ ] **Step 2: Run focused failure**

Run:
```powershell
mvn "-Dtest=TestS1SyzBossBlockGraphRewind,TestSonic1SyzBossInstance,TestS1Syz3CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

- [ ] **Step 3: Fix parent-child lifecycle**

If ROM deletes the spike by parent routine transition, model that transition. If ROM leaves the child but disables render/collision through state, model those flags in `SYZBossSpike`.

- [ ] **Step 4: Verify**

Run the same focused command and confirm trace first-error frame does not regress.

---

### Task 9: Fix SLZ2 Staircase Collision Pixel

**Bug covered:**
- SLZ2 at X5611 Y58 has a staircase with a single blocking pixel.

**Files:**
- Modify likely: `src/main/java/com/openggf/game/sonic1/objects/Sonic1StaircaseObjectInstance.java` if present
- Modify likely: collision/height handling under `src/main/java/com/openggf/physics/`
- Test: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1StaircaseWallCollision.java`
- Test: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1StaircaseActivation.java`

- [ ] **Step 1: Locate the exact owner**

Run:
```powershell
rg -n "Stair|stair|5611|Sonic1Stair" src/main/java src/test/java docs/s1disasm
```

- [ ] **Step 2: Add coordinate-specific headless regression**

Use center coordinates. Place Sonic at the reported SLZ2 location and step right/left across the staircase pixel. Assert ROM-expected movement from disassembly or trace.

- [ ] **Step 3: Fix the smallest owner**

If it is object collision, fix the staircase object. If it is tile collision, compare S1 height masks and fix collision data decoding/probe offsets.

- [ ] **Step 4: Verify**

Run:
```powershell
mvn "-Dtest=TestSonic1StaircaseWallCollision,TestSonic1StaircaseActivation,TestS1Slz2CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

---

### Task 10: Fix Diagnostics And Log Warnings

**Bugs covered:**
- MZ act 3 `getSpawnIndex: identity miss for spawn at (4076,1696), found via equals at index 39`.
- S1 SFX warnings from `Sonic1SmpsLoader.loadSfx`.

**Files:**
- Modify only if root-caused: `src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java`
- Modify only if root-caused: `src/main/java/com/openggf/level/objects/ObjectPlacementController.java`
- Modify only if root-caused: `src/main/java/com/openggf/game/sonic1/audio/smps/Sonic1SmpsLoader.java`
- Test: `src/test/java/com/openggf/level/objects/TestObjectPlacementManager.java`
- Test: `src/test/java/com/openggf/tests/TestSonic1AudioPriority.java`
- Test: `src/test/java/com/openggf/audio/AudioRegressionTest.java`

- [ ] **Step 1: Reproduce MZ3 identity miss**

Run MZ3 trace or manual startup with logs enabled and capture which object/ring spawn calls `getSpawnIndex` using equal-but-not-identical `ObjectSpawn`.

- [ ] **Step 2: Classify the identity miss**

If the caller constructed a probe only for lookup, switch it to an index-preserving path. If the live object lost its canonical spawn reference, fix object construction or rewind restore to retain the original spawn identity.

- [ ] **Step 3: Reproduce SFX warning**

Run MZ3 with full logs and capture exact warning line after `Sonic1SmpsLoader loadSfx`. Identify the SFX ID/name and call site.

- [ ] **Step 4: Fix only invalid warnings**

If an unknown SFX is legitimately requested, fix the requester. If the loader emits warning-level logs for expected absent optional data, lower or gate the log after adding a test.

- [ ] **Step 5: Verify**

Run:
```powershell
mvn "-Dtest=TestObjectPlacementManager,TestSonic1AudioPriority,AudioRegressionTest,TestS1Mz3CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

---

### Task 11: ROM/Emulator Confirmation Tasks

**Questions covered:**
- SYZ section with rings that appear to go upward while Sonic rolls off/down: intentional?
- Sonic's running angle in SYZ seems one angle too much.
- SYZ spring into bumper repeated knockdown: can this be done on emulator?

**Files:**
- Create BizHawk diagnostic scripts under `tools/bizhawk/` only if needed.
- Update: `docs/status/trace-frontier-log.md` if a trace frontier or sweep is used.
- Update: `KNOWN_DISCREPANCIES.md` only if ROM behavior is confirmed intentional and differs from a player expectation.

- [ ] **Step 1: Capture ROM evidence**

Use S1 REV01 in BizHawk. Record short BK2 clips around the reported SYZ sections and capture player `obX`, `obY`, `obAngle`, `obInertia`, `obVelX`, `obVelY`, status bits, and nearby object slots.

- [ ] **Step 2: Compare engine route**

Run the same input through the engine trace/test harness or a headless route harness. Compare angle and motion fields frame-by-frame.

- [ ] **Step 3: Decide outcome**

If ROM matches engine, mark the bug as `rom-confirmed-intentional` in the ledger and document if user-facing. If ROM differs, create a new focused task for the responsible subsystem with the captured first divergence.

---

## Final Verification

- [ ] **Step 1: Run focused S1 batch**

Run:
```powershell
mvn "-Dtest=TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay,TestS1Mz1CompleteRunTraceReplay,TestS1Mz2CompleteRunTraceReplay,TestS1Mz3CompleteRunTraceReplay,TestS1Syz1CompleteRunTraceReplay,TestS1Syz2CompleteRunTraceReplay,TestS1Syz3CompleteRunTraceReplay,TestS1Lz1CompleteRunTraceReplay,TestS1Lz2CompleteRunTraceReplay,TestS1Lz3CompleteRunTraceReplay,TestS1Slz1CompleteRunTraceReplay,TestS1Slz2CompleteRunTraceReplay,TestS1Slz3CompleteRunTraceReplay" "-DfailIfNoTests=false" test
```

Expected: no trace that passed in the Task 1 baseline regresses. A moved frontier is acceptable only when the trace was already failing and the new first divergence is documented in `docs/status/trace-frontier-log.md`.

- [ ] **Step 2: Run rewind coverage guards**

Run:
```powershell
mvn "-Dtest=TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestRemainingRewindCoverageClosure" test
```

- [ ] **Step 3: Run full test suite if focused batch is green**

Run:
```powershell
mvn test
```

- [ ] **Step 4: Update docs**

Update `CHANGELOG.md` for user-visible fixes. Update `docs/status/trace-frontier-log.md` for any trace frontier movement or trace sweep used to pick/confirm targets. Update S1 object pitfall skills only when a generalized object implementation pitfall was found.

- [ ] **Step 5: Check ROM evidence before each commit**

For each fix commit, the ledger or commit message must name the ROM evidence used: disassembly file/line, trace report/frame, or BizHawk probe/capture. A fix without ROM evidence is not ready to commit.

- [ ] **Step 6: Commit in coherent chunks**

Use one commit per independent root cause. Suggested subjects:
```text
fix: restore S1 special stage jump input edges
fix: align S1 object visual lifetimes
fix: correct S1 badnik touch responses
fix: restore S1 rewind power-up state
fix: gate LZ water current on ROM doorway state
```

Each commit must include the required branch-policy trailers.
