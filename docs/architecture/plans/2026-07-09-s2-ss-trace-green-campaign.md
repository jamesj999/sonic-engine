# S2 SS Trace-Green Campaign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Task 6 is an ITERATIVE LOOP task — dispatch one subagent per loop iteration, not one for the whole loop.

**Goal:** Drive `TestS2SpecialStageTraceReplay` to fully green with the ratchet on, fixing special-stage control-lock timing, Tails CPU semantics, playback speed, and text/graphics/ring timing.

**Architecture:** Root-ordered fix campaign against the committed 5,299-frame trace. Stage 1 (Tasks 1–4) removes the dominant init/pre-roll misalignment via two gates (a PRE_ROLL intro phase for global state; a `spawned` flag for players). Stage 2 (Tasks 5–6) is the iterative first-divergence fix loop to Tier-1 green + ratchet. Stage 3 (Tasks 7–9) adds the engine state Tier-2 comparison needs and ratchets each group. Stage 4 (Task 10) replaces the flat lag compensator with a trace-derived pure-function model. Stage 5 (Task 11) locks everything green.

**Tech Stack:** Java 21, JUnit 5 (Jupiter ONLY), BizHawk 2.11 lua (one recorder regen), Maven.

**Spec (binding):** `docs/architecture/designs/2026-07-09-s2-ss-trace-green-campaign-design.md`. Read it before any task. Its Method rules are law: first-divergence ordering; ROM-modeled fixes only (cite `s2.asm` lines; no constants sniffed from the trace; no zone/route/frame carve-outs); comparison-only invariant; no compensating errors.

## Global Constraints

- Branch: `feature/ai-s2-ss-trace-green` off `develop`.
- JUnit 5 / Jupiter only. No `org.junit.*` (JUnit 4) imports.
- Shared working tree with concurrent sessions: stage exact paths only; never `git add -A`; never stash.
- `feat`/`fix` commits touching `src/main/` → `Changelog: updated` + stage `CHANGELOG.md` (CRLF file — verify `git diff CHANGELOG.md` shows only your line; on flattening, reconstruct from the parent blob: see commit `d759115ff` for the recovery pattern).
- ROM-gated tests: `Assumptions.assumeTrue(Files.exists(Path.of("s2.gen")))`.
- PowerShell: quote Maven `-D` props. `-Dtest` prints a project-wide MSE `total=`; judge by the focused class results.
- `docs/status/trace-frontier-log.md` updated on EVERY frontier move (command, commit, status, error count, first-error frame/field).
- The trace test command used throughout:
  `mvn "-Dtest=com.openggf.tests.trace.s2.TestS2SpecialStageTraceReplay" test`
  Report lands at `target/trace-reports/s2_special_stage_0_report.json`.
- Key anchors (verified during spec review): `Sonic2SpecialStageManager.java` — `drawingIndex++` `:1030`, `intro.update()` `:1040`, unconditional `trackAnimator.update()` `:1043-1044`, `updatePlayers()` gate on `intro.isInputEnabled()` `:1080`, `handleObjectCollision(obj, player)` `:1234/:1243`, `collectRing()` `:1247`, `loseRingsFromBombHit()` `:1257`, rings-to-go render calc `:1735`, intro-phase snapshot `:2149`, `restorePlayerTopologyForRewind` throw `:2263-2264`, speed-factor read via animator `:2416`, `toComparisonPlayerState` `:2442`; `Sonic2SpecialStageIntro.java` — phase machine `:76,134,188`, `isInputEnabled()` `:515`; `Sonic2TrackAnimator.java` — `speedFactor=12` default `:50,81`. Line numbers drift as tasks land — re-anchor by symbol, not number.

---

### Task 1: ROM init-timeline reference (research artifact)

**Files:**
- Create: `docs/architecture/research/trace/s2-special-stage-init-timeline.md`

**Interfaces:**
- Produces: a frame-by-frame table of the ROM's `SpecialStage:` init (`s2.asm:6537-6672`) that Tasks 2–3 implement against and reviewers check fixes against. Columns: VInt # (0-based from `Game_Mode=0x10`), ROM action (with `s2.asm` line), observable RAM effects (which recorded csv/aux fields change). The doc states the pre-roll length as a single number that Task 2 encodes as `Sonic2SpecialStageIntro.PRE_ROLL_FRAMES`.

- [ ] **Step 0: Create the branch.** `git checkout develop && git checkout -b feature/ai-s2-ss-trace-green` (if the branch already exists, just check it out). All campaign work happens here.
- [ ] **Step 1: Extract the timeline.** Read `docs/s2disasm/s2.asm:6537-6700` and the fade routine `:3570-3582`. Document: which VInts the 22 `Pal_FadeToWhite` iterations consume; the init work after the fade call's return (`:6547` onward — VDP setup, DMA fills, RAM clears, `ssInitTableBuffers`, `ssLdComprsdData`, `RunPLC_ROM`) and which of it consumes additional VInts; player object id writes (`:6628-6634`); `SS_New_Speed_Factor=$C0000` (`:6640`); `SpecialStage_Started` clear (`:6585`); the PLC/track wait loops (`:6644-6658`); the gameplay-loop gate (`:6689`). **Attribute the 23rd observed pre-roll frame (f0–f22 window, flip at f23) to a specific VInt — never pad.**
- [ ] **Step 2: Cross-check against the committed trace.** Decompress `src/test/resources/traces/s2/special_stage/physics.csv.gz` and verify the table's predictions: `*_present` flips at f23, `speed_factor` 0→c at the predicted frame, `track_anim_frame` movement start. Note any mismatch in the doc with a resolution (the disasm wins; the trace verifies).
- [ ] **Step 3: Commit** (`docs: S2 SS ROM init timeline reference`; trailers all `n/a`).

---

### Task 2: PRE_ROLL intro phase (global gate)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageIntro.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackAnimator.java`
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePreRollTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 1's timeline (phase length + per-VInt actions).
- Produces: `Sonic2SpecialStageIntro.Phase.PRE_ROLL` (first enum constant, active from `initialize()`); `public static final int Sonic2SpecialStageIntro.PRE_ROLL_FRAMES` (value from Task 1's doc); `intro.isPreRollActive()` (boolean); the manager suppresses, while pre-roll is active: `trackAnimator.update()`, the `drawingIndex` increment, and banner/DROP start. The manager models speed factor 0 during pre-roll and applies the ROM's set-to-12 at the timeline's `:6640` tick via the animator's EXISTING `setSpeedFactor(int)` (`Sonic2TrackAnimator.java:343-344`). **Both** speed-factor-12 writes in the animator become 0: the field initializer (`Sonic2TrackAnimator.java:50`) AND the reset inside `initialize(int)` (`:81`) — the manager's ROM boot calls the animator's `initialize()`, so missing `:81` would re-set 12 immediately on the real trace path and defeat the gate. Task 3 relies on `isPreRollActive()` + `PRE_ROLL_FRAMES` for spawn timing.

- [ ] **Step 1: Write the failing tests.** These tests STEP `manager.update()`, which early-returns unless `initialized` (`Sonic2SpecialStageManager.java:979-981`, set only by the ROM-backed `initialize(int)` at `:376`) and needs a real `trackAnimator` (`:371`) — so unlike `Sonic2SpecialStageTeamSetupTest` they must be ROM-GATED with the FULL boot sequence. The authoritative reference is `AbstractS2SpecialStageTraceReplayTest.bootHarness()` (`:153-173`) — NOT the `S2SpecialStageReplayHarness` constructor, which per its own Javadoc expects ROM + engine services installed BEFORE construction. `bootHarness()` is package-private in `com.openggf.tests.trace.s2`, so replicate the sequence in your test's setup:

```java
Assumptions.assumeTrue(Files.exists(Path.of("s2.gen")), "s2.gen ROM required");
GraphicsManager.getInstance().resetState();
GraphicsManager.getInstance().initHeadless();
Rom rom = new Rom();
rom.open(Path.of("s2.gen").toAbsolutePath().toString());
TestEnvironment.configureRomFixture(rom);   // wires GameServices.rom(); RESETS config to defaults
GraphicsManager.getInstance().initHeadless();
// team config MUST be set AFTER configureRomFixture (it resets config) and BEFORE initializeStage:
GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
GameServices.configuration().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
provider.initializeStage(0);
provider.setLagCompensation(0); // CRITICAL: default 0.35 makes ~1/3 of update() calls early-return
                                // (lag skip at Sonic2SpecialStageManager.java:990-996) — without this
                                // the boundary assertions fail against a CORRECT implementation.
                                // Matches S2SpecialStageReplayHarness.java:90.
Sonic2SpecialStageManager manager = provider.getManager();
```

(`SonicConfiguration` lives at `com.openggf.configuration.SonicConfiguration`.) Then the test bodies:

```java
@Test
void preRollSuppressesTrackAdvanceAndSpeedFactor() throws Exception {
    // manager from the ROM-gated boot recipe above (lag compensation OFF)
    for (int f = 0; f < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; f++) {
        manager.update();
        Sonic2SpecialStageComparisonState s = manager.captureComparisonState();
        assertEquals(0, s.speedFactor(), "speed factor must be 0 during pre-roll, frame " + f);
        assertEquals(0, s.trackAnimFrame(), "track must not advance during pre-roll, frame " + f);
        assertEquals(0, s.drawingIndex(), "drawingIndex must not advance during pre-roll, frame " + f);
    }
    manager.update(); // first post-pre-roll frame
    assertEquals(12, manager.captureComparisonState().speedFactor(), "ROM sets $C0000 at the s2.asm:6640 tick");
}

@Test
void preRollPhaseIsRewindSnapshotted() throws Exception {
    // capture during pre-roll, advance past it, restore, assert isPreRollActive() again true.
    // Entry points: manager.captureRewindSnapshot()/restoreRewindSnapshot() (intro path:
    // Sonic2SpecialStageIntro.captureRewindSnapshot :420 / restoreRewindSnapshot :462).
}
```

- [ ] **Step 2: Run — FAIL** (`mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStagePreRollTest" test`): speed factor reads 12 at frame 0.
- [ ] **Step 3: Implement.** Add `PRE_ROLL` as the intro's first phase with the Task-1 length; transition to DROP at the modeled tick. **Single boundary snapshot:** the two suppressed operations sit on opposite sides of `intro.update()` (`drawingIndex++` at `:1030` BEFORE it, `trackAnimator.update()` at `:1044` AFTER it), so capture the gate ONCE at the top of `update()` — `boolean preRoll = intro != null && intro.isPreRollActive();` — and gate `drawingIndex++`, `trackAnimator.update()`, AND the speed-factor-12 init tick on that single snapshot. Independently wrapping each line would desync the boundary frame (intro flips PRE_ROLL→DROP mid-update; the track would advance while the drawing index doesn't — a permanent one-frame phase shift, the exact bug class this campaign removes). `intro.update()` itself stays UNGATED — it counts the pre-roll frames. Delete/replace the "Track animation always runs (even during intro)" comment — it is now wrong. Animator: both 12-writes (`:50`, `:81`) become 0; the manager applies 12 at the modeled `:6640` tick via the EXISTING `setSpeedFactor(12)` (`Sonic2TrackAnimator.java:343-344` — do not add a duplicate setter). The intro phase machine is already rewind-snapshotted as a `Phase` object (`Sonic2SpecialStageIntro.java:443-444`), so `PRE_ROLL` is captured for free.
- [ ] **Step 4: Run — PASS.** Then run the SS package neighborhood (`mvn "-Dtest=com.openggf.game.sonic2.specialstage.*Test" test`) and fix fallout (existing tests that assumed track runs from frame 0 — update them to step past the pre-roll ONLY where the test's intent is post-intro behavior; if a test asserts intro behavior itself, its expectation changes are part of this fix and must match the ROM timeline). Known fallout to handle explicitly: `Sonic2SpecialStageComparisonStateTest` asserts `speedFactor()==12` on a fresh manager, and `captureComparisonState()`'s null-animator fallback hardcodes 12 — both must change to the pre-roll value 0 (update the fallback and the test together, citing the ROM's late speed-factor set).
- [ ] **Step 5: Normal-play composition check.** Grep for the SS-entry fade path (`doEnterSpecialStage` → provider init) and confirm the new pre-roll doesn't stack with an engine-side entry fade into a visibly doubled pause; note findings in the commit body.
- [ ] **Step 6: Commit** (`fix(s2ss): model ROM pre-roll — gate track/speed/banner behind PRE_ROLL phase`; `Changelog: updated`).

---

### Task 3: Player `spawned` flag (player gate)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePlayer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSpawnGateTest.java`
- Test-modify (only if needed per Step 4): `src/test/java/com/openggf/tests/trace/s2/AbstractS2SpecialStageTraceReplayTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `intro.isPreRollActive()` and `Sonic2SpecialStageIntro.PRE_ROLL_FRAMES` (Task 2); Task 1 timeline (spawn tick = the `s2.asm:6628-6634` VInt, observed f23). Tests step `manager.update()` and therefore need the same ROM-gated provider boot as Task 2's tests — the FULL recipe from Task 2 Step 1 INCLUDING `provider.setLagCompensation(0)` (without it the lag skip no-ops ~1/3 of updates and the spawn-tick assertion fails spuriously).
- Produces: `Sonic2SpecialStagePlayer.isSpawned()` / package-private `setSpawned(boolean)`. While unspawned: player skips update/collision/render participation and `toComparisonPlayerState` returns null. Flag captured/restored in `Sonic2SpecialStageSnapshot` (player section). Players remain CONSTRUCTED at `initialize()` — the rewind topology invariant (`restorePlayerTopologyForRewind` throws on count change, `:2263-2264`) and `setupIntro()`'s team detection (`:395`) both depend on that.

- [ ] **Step 1: Write the failing tests:**

```java
@Test
void playersUnspawnedDuringPreRollAndSpawnAtRomTick() throws Exception {
    for (int f = 0; f < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; f++) {
        manager.update();
        assertNull(manager.captureComparisonState().sonic(), "present must be false during pre-roll, frame " + f);
    }
    manager.update();
    assertNotNull(manager.captureComparisonState().sonic(), "Sonic spawns at the ROM object-creation tick");
    assertNotNull(manager.captureComparisonState().tails());
}

@Test
void spawnedFlagSurvivesRewindRoundTrip() throws Exception {
    // snapshot during pre-roll (unspawned), advance past spawn, restore, assert unspawned again;
    // and assert players.size() unchanged across the round trip (topology invariant).
}
```

- [ ] **Step 2: Run — FAIL** (comparison state non-null at frame 0).
- [ ] **Step 3: Implement.** `spawned` flag default false, set true for all players at the Task-1 spawn tick (manager-driven, keyed off the pre-roll phase end per the timeline — the ROM writes object ids at `:6628-6634`). The flag drives exactly two things: (a) `toComparisonPlayerState(player)` returns null when `!player.isSpawned()`, and (b) renderer submission is suppressed while unspawned. Do NOT add a `spawned` check inside `updatePlayers()` — it is already gated on `intro.isInputEnabled()` (`:1080`), which is false throughout PRE_ROLL (and the whole intro), so player physics and the `tailsCtrlRecordBuf` machinery already cannot run during pre-roll; whether the ROM shifts `SS_Ctrl_Record_Buf` during the post-spawn DROP/WAIT window is Stage-2 territory (Task 6's anticipated Tails-CPU root), not this task's. Snapshot: add the boolean to the player section of `Sonic2SpecialStageSnapshot` capture/restore.
- [ ] **Step 4: Comparator null-safety check.** Read `AbstractS2SpecialStageTraceReplayTest`'s player-field comparison: with a null engine `PlayerState` it must record `present=false` and skip `ss_x/ss_y/ss_z/angle/routine` for that player-frame — no NPE, no stale compare. If the Task-5-era code already does this (it compared `present` as a Tier-1 field), verify by running the trace test; if not, fix it here (test-tree change, no policy conflict).
- [ ] **Step 5: Run — PASS** + SS neighborhood green.
- [ ] **Step 6: Commit** (`fix(s2ss): gate player spawn on ROM object-creation tick`; `Changelog: updated`).

---

### Task 4: Stage-1 checkpoint — rerun, quantify, bank

**Files:**
- Modify: `docs/status/trace-frontier-log.md`

- [ ] **Step 1: Run the trace test** (command in Global Constraints; ROM required). Expected: test passes (pipeline assertions); the report regenerates.
- [ ] **Step 2: Quantify the collapse.** From the new report: total errors/warnings, first-error frame+field, first divergence per field — compute directly from `target/trace-reports/s2_special_stage_0_report.json` with a short throwaway script (entries live under `errors`/`warnings`, each with `field`, `start_frame`, `end_frame`, `expected_at_start`, `actual_at_start`; group by `field`, take min `start_frame`). Compare against the baseline (15,313 / 1,877 / first error f0). Record before/after in the frontier log with the two commit SHAs.
- [ ] **Step 3: Sanity-guard the win.** If total errors did NOT drop by at least half, STOP — Stage 1's model is wrong somewhere (a compensating-error smell); debug against the Task-1 timeline before proceeding, and do not "tune" constants to fit the trace.
- [ ] **Step 4: Determinism + visual.** Run `S2SpecialStageReplayDeterminismTest` (must stay byte-identical). Launch the jar in test mode once and eyeball the SS trace entry for the new pre-roll (no doubled pause).
- [ ] **Step 5: Commit** frontier log (`docs: bank S2 SS Stage-1 frontier move`; trailers n/a).

---

### Task 5: Recorder regen — `SpecialStage_Started` aux capture

**Files:**
- Modify: `tools/bizhawk/s2_ss_trace_recorder.lua`
- Update artifacts: `src/test/resources/traces/s2/special_stage/{metadata.json, physics.csv.gz, aux_state.jsonl.gz}`

**Interfaces:**
- Consumes: recorder + ps1 workflow from the pipeline project (route `special_stage` in `tools/bizhawk/record_s2_level_select_traces.ps1`).
- Produces: aux events `{"frame":N,"type":"control_state","started":0|1}` emitted on every `SpecialStage_Started` (RAM `0xDB23`, u8) TRANSITION, giving Stage 2 direct control-lock ground truth. `LUA_SCRIPT_VERSION` bumped (it owes a bump from the pipeline project already). CSV schema UNCHANGED (aux-only addition — the 48-column parser contract holds; `ss_csv_version` stays 1).

- [ ] **Step 1: Add the aux emission** to the lua: track previous `mainmemory.read_u8(0xDB23)`, emit a `control_state` aux line (with the `"type"` key — the parser matches `type`, never `event`) on change, including the initial value at trace frame 0.
- [ ] **Step 2: Bump `LUA_SCRIPT_VERSION`** and re-record via the ps1 `special_stage` route.
- [ ] **Step 3: Verify byte-compatibility.** Diff the new `physics.csv` against the previous one (decompressed): MUST be identical (same movie, deterministic emulation, no csv-writer change). If it differs, STOP and find out why before committing — a drifted recording invalidates all banked triage. Verify the new aux contains `control_state` transitions (at least: initial state + the unlock when the ring-requirement message resolves), exactly one checkpoint-owned logical `stage_finished`, the later Obj6F `results_started`, and the existing `checkpoint`/`message_state` events. The ps1 SS validation chain (incl. the P1+P2 input-alignment assert) must pass.
- [ ] **Step 4: Commit** lua + regenerated artifacts (`feat(trace): record SpecialStage_Started control-state transitions`; `Changelog: n/a: tooling + trace resources only`).

---

### Task 6: Stage-2 frontier loop → Tier-1 green + ratchet (ITERATIVE)

**Files (per iteration, varies):**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/*` (fix-specific)
- Modify: `docs/status/trace-frontier-log.md` (every iteration)
- Final: `src/test/java/com/openggf/tests/trace/s2/AbstractS2SpecialStageTraceReplayTest.java` (ratchet flip) and its concrete test

**Loop protocol (binding — dispatch ONE subagent per iteration):**

1. Run the trace test; read the report; identify the FIRST Tier-1 divergence (lowest `start_frame`).
2. Invoke the `trace-replay-bug-fixing` skill's method: root-cause the divergence against `docs/s2disasm/s2.asm` (cite lines), classify engine-bug vs comparator-mapping-bug.
3. Fix the ROM-modeled way. Forbidden: zone/route/frame carve-outs, constants sniffed from the trace, comparator relaxations to hide a real engine divergence. A comparator MAPPING fix (e.g. the routine map gaining a ROM value) is legitimate when the engine models the state differently but correctly — say so explicitly in the commit.
4. Focused unit test for the mechanism where isolable; rerun the trace test; the first divergence MUST move to a later frame or a different root (error count may transiently rise as cascade unmasks — first-divergence frame is the progress metric, not raw count).
5. Frontier-log entry + commit (one commit per root; `Changelog: updated` when src/main changes).
6. Repeat until zero Tier-1 errors.

**Anticipated roots** (from the campaign spec — verify, don't assume): control-lock edges via the new `control_state` aux vs `intro.isInputEnabled()`/`SpecialStage_Started` modeling; Tails CPU `SS_Ctrl_Record_Buf` shift order / delayed-tap index / P2-override branches (`s2.asm:70411-70449`) vs `tailsCtrlRecordBuf` handling (`Sonic2SpecialStageManager.java:1424-1434` — shift `:1424`, P2 override `:1427-1434`); ring-collection windows; bomb/hurt response (f2683 cluster in the baseline); track/segment residuals; the finish frame (baseline: engine finished 146 frames early — should largely close with Stage 1, remainder is course-speed parity).

**Exit steps:**

- [ ] **Step A: Zero Tier-1 errors confirmed** on a clean run + determinism test byte-identical.
- [ ] **Step B: Flip the ratchet.** In `AbstractS2SpecialStageTraceReplayTest`, the hook `assertNoReleaseBlockingDivergences()` is already INVOKED (`:134`) with an intentionally empty body (`:147-149`); replace the empty body with the exact line its own javadoc prescribes: `assertFalse(report.hasErrors(), report.toAssertionSummary());` — Tier-1 fields are `Severity.ERROR`, so `hasErrors()` gates exactly Tier-1. Run twice to confirm green+green.
- [ ] **Step C: Commit** (`feat(trace): S2 SS trace Tier-1 green — ratchet on`; `Changelog: updated`) + frontier log.

---

### Task 7: Per-player rings (Tier-2 enabler)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePlayer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java:1234-1260`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageComparisonState.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePerPlayerRingsTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/s2/AbstractS2SpecialStageTraceReplayTest.java` (ratchet per-player rings warning→error)
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: `Sonic2SpecialStagePlayer.getRings()` (int), incremented on that player's ring touch, debited on that player's bomb hit — mirroring ROM per-object `ss_rings_*` (`s2.asm:70771-70789`; HUD sums both, `s2.asm:9938-9943`). `PlayerState` record gains a trailing `int rings` component (comparator updates accordingly). Combined total (`manager.getRingsCollected()`) must equal the sum at all times.

- [ ] **Step 1: Failing tests** — ring collected by Tails increments only Tails' count and the combined total; bomb hit on Sonic debits only Sonic (floor 0, matching ROM's BCD floor behavior — verify in disasm at `s2.asm:70790+` how underflow is handled and encode that exact rule); snapshot round-trips per-player counts.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement.** `handleObjectCollision(obj, player)` already receives the touching player (`:1234/:1243`) — route `collectRing()` and `loseRingsFromBombHit()` (`:1257`) through per-player mutations that also maintain the shared total (or derive the total as the sum — pick whichever keeps `getRingsCollected()` callers untouched, and say which in the commit).
- [ ] **Step 4: Wire comparison.** Add `rings` to `PlayerState`; comparator compares per-player `trace.CharacterState.ringsBinary()` vs engine per-player rings as ERROR (was Tier-2 warning). Run trace test — must stay green (if it goes red, that's a real per-player divergence: fix via the Task-6 protocol before ratcheting, do not ship a red ratchet).
- [ ] **Step 5: Run all + commit** (`feat(s2ss): per-player ring tracking (ROM parity)`; `Changelog: updated`).

---

### Task 8: Swap-flag reconciliation + hurt/slide/flip timer comparison

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePlayer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageComparisonState.java`
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSwapFlagTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/s2/AbstractS2SpecialStageTraceReplayTest.java` (ratchets)
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: a single manager-owned `swapPositionsFlag` replacing the per-player copies (`Sonic2SpecialStagePlayer.swapPositionsFlag`, line ~142) — ROM has ONE global cell toggled by whichever player jumps (`s2.asm:69058`, `:69253`) and read by both players' swap logic; the per-player getters delegate to the manager. Comparison state gains `int swapPositionsFlag` (manager section) and per-player `int hurtTimer, slideTimer, flipTimer` on `PlayerState` (exposed via new player getters reading the existing fields). Comparator ratchets `swap_positions_flag` and hurt/slide timers warning→error; flip timer compared as warning first (its trace column exists; ratchet in this task only if green immediately, else leave warning with a frontier-log note).

- [ ] **Step 1: Failing test** — player A jumps → BOTH players' swap logic observes the flag flip (currently only A's copy flips; assert through `ssPlayerSwapPositions` behavior or the new shared getter).
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement** (single field on manager; players read/toggle through it; rewind snapshot moves the field from per-player to manager section — keep restore compatible with the snapshot's existing versioning approach).
- [ ] **Step 4: Wire comparisons + ratchet per the Interfaces block; trace test stays green (Task-6 protocol if not).**
- [ ] **Step 5: Run all + commit** (`fix(s2ss): single ROM-faithful swap flag + timer comparison`; `Changelog: updated`).

---

### Task 9: Real `player_anim_frame_timer` + `rings_togo_bcd` mapping

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackAnimator.java` (getPlayerAnimFrameTimer)
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageComparisonState.java` (+`int playerAnimFrameTimer`, +`int ringsToGo`)
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java` (capture wiring)
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2TrackAnimatorAnimTimerTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/s2/AbstractS2SpecialStageTraceReplayTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: `getPlayerAnimFrameTimer()` becomes a real decrementing counter per ROM `SSRun_Animation_Timers` (`s2.asm:960-982`): on speed-factor change reset track timer; each tick `SSTrack_duration_timer--`; on expiry reload from `SSAnim_Base_Duration[(speedFactor>>1)&7]` into BOTH timers and decrement the player timer once; otherwise the player timer reads current+1 (`:978-981`). The comparator STARTS comparing the recorded `player_anim_frame_timer` column (was recorded-not-compared) as warning, ratcheted to error when green. `rings_togo_bcd` compared via the refresh-gated mapping: engine value = `currentRingRequirement - combinedRings` (render calc at `:1735`), BCD-decoded trace value, compared ONLY at/after recorded `check_rings_flag`/`SS_TriggerRingsToGo` transition frames — never raw per-frame equality.

- [ ] **Step 1: Failing unit test** for the timer semantics above (drive the animator N ticks at speedFactor 12 → duration 5 cadence; change speed factor → reset).
- [ ] **Step 2: Run — FAIL** (current impl returns the constant `duration-1`).
- [ ] **Step 3: Implement** the ROM-faithful counter (it may already exist as `frameDelayCounter` arithmetic — derive, don't duplicate state; the pipeline-spec mapping `duration − romTimer == engineCounter` stays valid for `track_duration_timer`).
- [ ] **Step 4: Wire both comparisons + run trace test; ratchet each when green (Task-6 protocol when not).**
- [ ] **Step 5: Run all + commit** (`fix(s2ss): ROM-faithful player anim timer + rings-to-go comparison`; `Changelog: updated`).

---

### Task 10: Trace-derived lag model (normal play)

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageLagModel.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java` (accumulator replacement, `:990-991`; overlay `:1838-1922`)
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java` (retire `lagAccumulator`)
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageLagModelValidationTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces: `Sonic2SpecialStageLagModel.shouldLagThisFrame(int frameCounter, int speedFactor, int segmentType, int drawingIndex, int liveObjectCount)` → boolean; a PURE function (static table + arithmetic on its inputs; no mutable fields, no RNG — the spec forbids carried per-frame state so rewind restore needs nothing new: all five inputs are already rewind-snapshotted). Derivation table checked in as constants WITH a generator: the validation test itself derives buckets from the committed trace artifacts and asserts the constants match, so the table is reproducible, not hand-tuned.

- [ ] **Step 1: Derivation analysis (in the validation test class).** Load `physics.csv.gz` + `aux_state.jsonl.gz` from `src/test/resources/traces/s2/special_stage/`; bucket per-frame `lag` by (segment-type from `current_segment`+track data, `speed_factor`); compute per-bucket lag ratios and burst-length histograms. Write the JUnit 5 (Jupiter) assertions per the spec: model-vs-recorded per-bucket ratio within ±5 percentage points; overall within ±2pp of the artifact-computed overall ratio (≈1971/5299 — compute from the csv, do not hardcode).
- [ ] **Step 2: Run — FAIL** (model class absent).
- [ ] **Step 3: Implement the model** (bucket table + deterministic within-bucket pattern expressed as arithmetic on `frameCounter`, e.g. threshold-accumulator computed as `(frameCounter * ratioNumerator) % denominator` — stateless equivalent of Bresenham pacing; burst shaping only if the ±5pp gate demands it).
- [ ] **Step 4: Replace the accumulator.** In `update()` (`:985-991`): consult the model instead of `lagAccumulator`; delete the `lagCompensation`/`lagAccumulator` fields' pacing role (keep `setLagCompensation(0)` API as the replay force-off switch — semantics: zero disables the model for internally trace-paced sessions, while normal play always uses the derived model; keep the existing replay call sites unchanged). Retire the snapshot's `lagAccumulator` field per the snapshot's versioning conventions. Rework the F1 overlay (`:1838-1922`) to display the model's current bucket/ratio as a read-only diagnostic; remove the obsolete F6/F7 live toggle.
- [ ] **Step 5: Run — PASS** validation test + SS neighborhood + BOTH SS trace tests (replay must be unaffected — it forces the model off via the three existing `setLagCompensation(0)` call sites: `S2SpecialStageReplayHarness.java:90`, `TraceSessionLauncher.java:269`, `TestSpecialStageVisualTraceSession.java:107`; keep that setter and gate the model off when the field is 0 so all three stay UNCHANGED) + rewind tests in the package. Run explicitly and adjust for the snapshot-arity change: `TestSonic2SpecialStageRewindSnapshot` and `Sonic2SpecialStageManagerTest` (both exercise lag fields; they reflect on field names, so keeping `lagCompensation` keeps them green — verify).
- [ ] **Step 6: Visual eyeball.** Jar, normal SS play (not trace mode): perceived speed varies by section. Note in commit body.
- [ ] **Step 7: Commit** (`feat(s2ss): trace-derived deterministic lag model replaces flat compensator`; `Changelog: updated`).

---

### Task 11: Green gate & closeout

**Files:**
- Modify: `docs/status/trace-frontier-log.md`, `CHANGELOG.md`; `README.md` staged at merge time.

- [ ] **Step 1: Full ratchet audit.** Every comparator group is ERROR (per-player rings, swap flag, timers, anim timer, rings-to-go, control state adjudication) — grep the abstract test for any remaining `WARNING` tier and justify each leftover in the frontier log (target: none).
- [ ] **Step 2: Full-suite sweep** (`mvn test`), triaged against the pre-existing-failure baseline exactly as the pipeline project's Task 8 did; plus 3 S2 level-select trace spot-checks; plus `TestS2SpecialStageTraceReplay` + determinism, twice.
- [ ] **Step 3: Add the SS trace test to the tracked keep-green set** used by trace sweeps (follow how the S2 level-select suite is tracked in `docs/status/trace-frontier-log.md` / the sweep tooling).
- [ ] **Step 4: Frontier log final entry + CHANGELOG.** Commit (`docs: S2 SS trace green — campaign closeout`).
- [ ] **Step 5: Merge readiness** per Branch Documentation Policy (README release-log entry staged in the merge commit into develop). Merge only after the campaign's final whole-branch review.

## Follow-ups (out of scope, from the spec)

Results-screen tail comparison; additional traces (solo Sonic/Tails, human-P2, failed-stage, stages 2–7 — recommended immediately after green); lag-model recalibration against a broader corpus; S1 special-stage generalization.
