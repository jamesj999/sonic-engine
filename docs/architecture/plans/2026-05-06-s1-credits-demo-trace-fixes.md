# S1 Credits Demo Trace Fixes + Rewind Torture Test Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two tracks. (A) Land the 5 failing Sonic 1 credits demo trace replay tests (`TestS1Credits01Mz2`, `TestS1Credits02Syz3`, `TestS1Credits03Lz3`, `TestS1Credits04Slz3`, `TestS1Credits05Sbz1`) by diagnosing and fixing the engine-vs-ROM divergences each one exposes. (B) Add a rewind torture test that exercises keyframe-boundary edge cases (rewind target at/before/after keyframe, resume crossing keyframe boundaries) to lock down the rewind framework's correctness.

**Architecture:** Tasks 1-6 (track A) are ordered by hypothesis confidence (highest first); each is **diagnose → fix → cross-game-verify → commit**, abortable between tasks. Task 7 (track B) is independent of A and can run in isolation. Per the `trace-replay-bug-fixing` skill, demo fixes must come from the engine path that should produce the ROM-correct value (not by hydrating from trace data), every behaviour change cites the disassembly, and per-game divergences go behind a `PhysicsFeatureSet` flag.

**Tech Stack:** JUnit 5, JBidEm fixture (`HeadlessTestFixture`), `TraceData`/`TraceBinder`, S1 disassembly under `docs/s1disasm/`, `RomOffsetFinder`, `mvn test`.

**Pre-flight (do once before any task):**
- [ ] Confirm the baseline failure state by running `mvn test -q '-Dtest=TestS1Credits01Mz2TraceReplay,TestS1Credits02Syz3TraceReplay,TestS1Credits03Lz3TraceReplay,TestS1Credits04Slz3TraceReplay,TestS1Credits05Sbz1TraceReplay,TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay,TestS2Ehz1TraceReplay' -DfailIfNoTests=false 2>&1 | tail -40`. Expected: 5 credits failures with first-error frames matching this plan; GHZ1, MZ1, EHZ1 green.
- [ ] After every fix task, the same command must show: the targeted credits test goes from failing → passing (or first-error frame strictly later), GHZ1+MZ1+EHZ1 stay green, and any previously-green credits demos (00-Ghz1, 06-Sbz2, 07-Ghz1b) stay green. Failure of any of these aborts the fix.

---

## Task 1: SLZ3 — Elevator pull-up after jump (highest confidence)

**Hypothesis:** When player jumps off an SLZ Elevator (object 0x59) at frame 500, ROM `Sonic_Jump` does `addq.w #5, obY` (+5px for rolling-radius adjustment, `docs/s1disasm/_incObj/01 Sonic.asm:1166`), then the elevator's `PlatformObject`/`Solid_Top` routine still moves the rider up by 2 within the same frame because the elevator runs after Sonic in `ExecuteObjects`. The engine applies the +5 jump adjust but **not** the post-jump elevator pull-up, so engine y ends at +5 vs ROM's +3.

**Files (likely — confirm in Step 2):**
- Read: `docs/s1disasm/_incObj/01 Sonic.asm:1118-1177` (Sonic_Jump)
- Read: `docs/s1disasm/_incObj/59 SLZ Elevators.asm` (Elev_Platform calls PlatformObject)
- Read: `docs/s1disasm/_incObj/sub PlatformObject.asm` (find via grep)
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1ElevatorObjectInstance.java` (or whichever S1 elevator class — find via Glob `Sonic1*Elev*.java`)
- Possibly modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` (SolidContacts) if the issue is post-jump solid execution ordering

- [ ] **Step 1: Re-run the failing test and capture context window**

```bash
mvn test -q -Dtest=TestS1Credits04Slz3TraceReplay -DfailIfNoTests=false
```
Expected: 2 errors, first error frame 500. Read `target/trace-reports/s1_credits_04_slz3_context.txt` lines 22-28 (frame 500 onwards) and confirm: ROM y=0x01F0, ENG y=0x01F2 with `mode on_object 1->0`, `near s91 0x59 @1358,0207` (elevator).

- [ ] **Step 2: Locate the engine S1 elevator class and PlatformObject equivalent**

```bash
ls src/main/java/com/openggf/game/sonic1/objects/ | grep -iE 'elev|platform'
```
Read the S1 elevator object file. Find where it calls into the shared platform/solid logic. Locate the equivalent of ROM's `PlatformObject` in `ObjectManager.SolidContacts` or a shared helper.

Read `docs/s1disasm/_incObj/sub PlatformObject.asm` (find with `grep -rln 'PlatformObject:' docs/s1disasm`) and identify whether the routine moves a rider whose `obStatus.in_air` bit is set.

- [ ] **Step 3: Write a focused regression test that reproduces the divergence**

Create `src/test/java/com/openggf/game/sonic1/TestS1JumpFromElevator.java`. The test should:
- Use `HeadlessTestFixture` to load SLZ act 3 (or a synthetic level if SLZ load is heavy)
- Place Sonic standing on an SLZ Elevator object that's moving up
- Press jump for one frame
- Assert post-frame y matches ROM-derived expected value (+3, not +5)

If no convenient SLZ entry point exists, write the test against a contrived synthetic setup that exercises the same code path: a solid platform moving up while Sonic stands on it, then jumps.

```bash
mvn test -q -Dtest=TestS1JumpFromElevator -DfailIfNoTests=false
```
Expected: FAIL with y=0x01F2 (or equivalent +5) instead of +3.

- [ ] **Step 4: Implement the fix**

Two likely shapes — pick whichever the disassembly supports:

(a) The S1 Elevator's solid update should always run its rider-track regardless of in_air, applying the same delta the platform moved by, OR

(b) `Sonic_Jump`'s y+=5 is being double-counted (engine already moved Sonic with the elevator earlier in the same frame, then jump adds +5 on top).

Read both routines carefully, decide which engine path is wrong, fix it. Cite the disassembly file and line numbers in the code comment.

If this is genuinely an S1 vs S2/S3K divergence, gate behind a new field on `PhysicsFeatureSet` (per `CLAUDE.md` Per-Game Physics Framework), set `true` for `SONIC_1` and `false` for `SONIC_2`/`SONIC_3K`, and branch on the flag.

- [ ] **Step 5: Run focused test then full trace cross-check**

```bash
mvn test -q -Dtest=TestS1JumpFromElevator -DfailIfNoTests=false
```
Expected: PASS.

```bash
mvn test -q '-Dtest=TestS1Credits04Slz3TraceReplay,TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay,TestS2Ehz1TraceReplay,TestS1Credits00Ghz1TraceReplay,TestS1Credits06Sbz2TraceReplay,TestS1Credits07Ghz1bTraceReplay' -DfailIfNoTests=false
```
Expected: All PASS (or SLZ3 advances first-error-frame past 500 with no new regressions).

- [ ] **Step 6: Commit**

Note that S1 trace fixes need the project's commit-message trailer block (see `CLAUDE.md` Branch Documentation Policy). Stage explicit files, never `git add .`. Use HEREDOC commit message with all required trailers.

---

## Task 2: MZ2 — Post-touch position adjustment off by 1 (high confidence, possibly same root cause as Task 1)

**Hypothesis:** At frame 341, engine `touchBox @0E12,043C` shows touch-time player centre x=0x0E1A (matches ROM), but final recorded x=0x0E19. Something between touch-response and end-of-frame moves engine sprite by -1. The nearby `s44 0x33 PushBlock @0E0F,0470` (object 0x33 = MZ pushable block) is a likely candidate — its solid-contact write-back may push the player by -1 in a way that doesn't match ROM.

**Files (likely — confirm in Step 2):**
- Read: `docs/s1disasm/_incObj/33 ` (MZ Push Block — find with `ls docs/s1disasm/_incObj/ | grep '^33 '`)
- Read: `src/main/java/com/openggf/game/sonic1/objects/Sonic1*PushBlock*.java` (find via Glob)
- Possibly modify: that PushBlock class or `ObjectManager.SolidContacts`

**Conditional:** If Task 1's fix already advances MZ2's first-error frame past 341, **skip this task** and proceed to Task 3 — Task 1 likely fixed both.

- [ ] **Step 1: Re-run after Task 1 to check residual state**

```bash
mvn test -q -Dtest=TestS1Credits01Mz2TraceReplay -DfailIfNoTests=false
```
Read summary line. If first-error frame is now > 341 OR the test passes, skip the rest of this task.

- [ ] **Step 2: Confirm hypothesis by inspecting touchBox vs final**

Re-read `target/trace-reports/s1_credits_01_mz2_context.txt` lines 22-23 (frame 341) and confirm: `touchBox @0E12,...` (centre = touchBox.x+8 = 0x0E1A, matches ROM), recorded x = 0x0E19. Identify which nearby object's solid-contact write-back ran between touch and end-of-frame. The PushBlock at @0E0F,0470 is the most likely.

Read the MZ Push Block disassembly and the engine equivalent. Identify the difference in how it adjusts the player's position when the player overlaps it from the side.

- [ ] **Step 3: Add a focused regression test**

Create `src/test/java/com/openggf/game/sonic1/TestS1PushBlockSideContact.java`. Place Sonic running left into the side of a stationary push block at the recorded position; assert post-frame x matches ROM's +0 (no -1 push-back) when the player's touch box doesn't overlap.

```bash
mvn test -q -Dtest=TestS1PushBlockSideContact -DfailIfNoTests=false
```
Expected: FAIL with x off by -1 from ROM.

- [ ] **Step 4: Implement the fix in the PushBlock or solid resolution code**

Cite the disassembly. Gate behind `PhysicsFeatureSet` only if it's genuinely game-divergent.

- [ ] **Step 5: Run focused test then full trace cross-check**

```bash
mvn test -q -Dtest=TestS1PushBlockSideContact -DfailIfNoTests=false && \
mvn test -q '-Dtest=TestS1Credits01Mz2TraceReplay,TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay,TestS2Ehz1TraceReplay,TestS1Credits00Ghz1TraceReplay,TestS1Credits04Slz3TraceReplay,TestS1Credits06Sbz2TraceReplay,TestS1Credits07Ghz1bTraceReplay' -DfailIfNoTests=false
```
Expected: All PASS or first-error frame strictly later in MZ2 with no other regressions.

- [ ] **Step 6: Commit with required trailers**

---

## Task 3: SYZ3 — Phantom ring s43 collection at frame 253

**Hypothesis:** S1 parent `Sonic1RingInstance` transitions `INIT → ANIMATE` during the same frame's `runExecLoop`, then `applyTouchResponses(player)` runs with `usePreUpdateState=false` (`ObjectManager.runTouchResponsesForPlayer`, `ObjectManager.java:271-285`) and reads the **post-update** flags. The ring becomes touchable in the same frame it activates, while ROM enforces a 1-frame delay via `obRender` bit 7 (set by `BuildSprites` at end of frame). Result: engine collects ring s43 one frame earlier than ROM.

**Confidence note:** This hypothesis has not been fully confirmed — the trace shows `eng-near s43 col=00` for 6 consecutive frames before the touch fires, which is more frames than a simple 1-frame INIT lifecycle should produce. **Step 1 must confirm the actual ring lifecycle before any fix.**

**Files:**
- Read: `src/main/java/com/openggf/game/sonic1/objects/Sonic1RingInstance.java`
- Read: `docs/s1disasm/_incObj/25 & 37 Rings.asm` (Ring_Main / Ring_Animate)
- Read: `docs/s1disasm/_inc/BuildSprites.asm:96` (where obRender bit 7 gets set)
- Read: `src/main/java/com/openggf/level/objects/ObjectManager.java:271-371` (touch-response paths and `usePreUpdateState`)
- Read: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java:585-612` (`isOnScreenForTouch`, `requiresRenderFlagForTouch`)
- Likely modify: one of the above to add a 1-frame "just-activated" delay for newly-spawned objects with non-zero collision flags

- [ ] **Step 1: Instrument ring s43 lifecycle**

Add temporary logging to a debug probe (use `src/test/java/com/openggf/tests/trace/s1/DebugS1Credits02Syz3RingProbe.java` or create one). For the SYZ3 demo, log ring s43's `state`, `getCollisionFlags()`, and `preUpdateCollisionFlags` every frame from f240-f255. Use `Sonic1RingInstance.State` directly via reflection or by adding a `@VisibleForTesting` getter.

```bash
mvn test -q -Dtest=DebugS1Credits02Syz3RingProbe -DfailIfNoTests=false
```
Capture the per-frame trace. Confirm the actual transition pattern. The fix design depends on whether:

(a) Ring is in INIT for many frames then transitions on f253 (timing-of-first-update bug)

(b) Ring transitions normally but touch fires anyway (touch path bug)

(c) Ring is being respawned each frame (placement bug)

- [ ] **Step 2: Read the relevant ROM routines and confirm correct ROM behaviour**

Re-read `Ring_Main` (ROM routine 0) → `Ring_Animate` (routine 2) → `BuildSprites.asm:96` to confirm: in ROM, a freshly spawned ring's first frame runs Ring_Main (sets ColType=$47), but ReactToItem at slot 0 has already run for that frame using the **previous** frame's obRender (bit 7 was 0 because BuildSprites hadn't yet run). So ring is effectively touchable starting frame N+2 after spawn (where N is the spawn frame).

- [ ] **Step 3: Write a focused regression test**

Create `src/test/java/com/openggf/game/sonic1/TestS1FreshRingSameFrameTouchSkip.java`. Spawn a ring on the player's path such that the first frame the ring exists is the same frame the player would overlap it. Assert no collection on that first frame; collection happens on the next eligible frame.

```bash
mvn test -q -Dtest=TestS1FreshRingSameFrameTouchSkip -DfailIfNoTests=false
```
Expected: FAIL — engine collects on frame 0 of ring's life.

- [ ] **Step 4: Implement the fix**

Likely shape: extend `skipTouchThisFrame` (`AbstractObjectInstance.java:92`) to be set on **all** newly-spawned objects (not just dynamically allocated children at line 1203), then cleared by the next snapshot. Or: gate `processCollisionLoop` on a "has been displayed at least once" flag matching ROM's `obRender` bit 7 semantics.

If the bug is specifically that `usePreUpdateState=false` in `runTouchResponsesForPlayer` causes the touch loop to read post-exec flags, consider whether the S1 inline-physics path should use `usePreUpdateState=true` (matching how `touchResponses.update(player, frameCounter)` defaults).

Cite ROM file/line numbers in the comment.

- [ ] **Step 5: Run focused test then full trace cross-check**

```bash
mvn test -q -Dtest=TestS1FreshRingSameFrameTouchSkip -DfailIfNoTests=false && \
mvn test -q '-Dtest=TestS1Credits02Syz3TraceReplay,TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay,TestS2Ehz1TraceReplay,TestS1Credits00Ghz1TraceReplay,TestS1Credits01Mz2TraceReplay,TestS1Credits04Slz3TraceReplay,TestS1Credits06Sbz2TraceReplay,TestS1Credits07Ghz1bTraceReplay' -DfailIfNoTests=false
```
Expected: SYZ3 PASS, all others stay green. (This change touches a hot path — extra cross-game vigilance on EHZ1 and MZ1.)

- [ ] **Step 6: Remove the debug probe, commit with required trailers**

---

## Task 4: SBZ1 — Sub-pixel desync via vanishing platform / SBZ-specific physics

**Hypothesis:** Frame 285 has y off by 1 with sub_y differing (ROM=0x7800 at f284, ENG=0x0000). The desync originates around frames 281-282 when y_speed jumps from 0x0000 to 0x0800 in one frame (way more than gravity's 0x38) — strongly suggesting an SBZ-specific event (vanishing platform `s48 0x6C @1510,0138`, conveyor, or similar) sets velocity outside the standard SpeedToPos flow with a different sub-pixel result than ROM. Persists for 58 frames.

**Confidence note:** Low — the trace context only shows 20 frames around f285, and the actual desync origin is earlier. **Investigation must locate the originating frame before fixing.**

**Files:** TBD after Step 1; likely `src/main/java/com/openggf/game/sonic1/objects/Sonic1*VanishingPlatform*.java` or whatever 0x6C maps to.

- [ ] **Step 1: Capture an extended diagnostic context window**

Modify `AbstractCreditsDemoTraceReplayTest.writeReport` (or a temporary probe variant) to dump a 100-frame window around the first error rather than 20. Re-run SBZ1, inspect frames 185-285. Find the first frame where ROM and ENG sub_y diverge.

```bash
mvn test -q -Dtest=TestS1Credits05Sbz1TraceReplay -DfailIfNoTests=false
```
Read the expanded `target/trace-reports/s1_credits_05_sbz1_context.txt`. Identify the exact frame where sub-pixel desync starts.

- [ ] **Step 2: Identify what object/event causes the desync**

Look at the `eng-near` and ROM `near` data at the divergence frame. Identify which object's update or which physics routine introduces the sub-pixel difference. Cross-reference with SBZ-specific objects (object 0x6C, conveyors, etc.).

- [ ] **Step 3: Read the matching ROM disassembly**

Find the object's S1 disassembly (e.g., `docs/s1disasm/_incObj/6C *.asm`). Identify the exact routine that writes velocity or position. Diff against the engine implementation.

- [ ] **Step 4: Add a focused regression test for the specific interaction**

Test should reproduce the same SBZ object interaction with the player and assert sub-pixel-accurate position matching ROM.

```bash
mvn test -q -Dtest=TestS1SbzVanishingPlatform... -DfailIfNoTests=false
```
Expected: FAIL with sub-pixel diff matching the trace.

- [ ] **Step 5: Implement the fix, citing ROM lines**

- [ ] **Step 6: Run focused test then full trace cross-check (same command as Task 3 Step 5 with TestS1Credits05Sbz1 substituted)**

- [ ] **Step 7: Revert any temporary report-window expansion in `AbstractCreditsDemoTraceReplayTest`. Commit with required trailers.**

---

## Task 5: LZ3 — Underwater y bump and sub_x desync

**Hypothesis:** At frame 221 ROM y bumps by +2 in a single frame while engine y stays constant; sub_x has diverged by 0x6400 ahead of frame 221. Likely an LZ-specific event (water surface contact, bubble interaction, swimming animation tick) that the engine handles slightly differently. The sub_x desync indicates the divergence has been brewing for many frames before f221.

**Confidence note:** Lowest — both the y bump origin and the sub_x desync need separate investigation. Defer until Tasks 1-4 are landed (one of them may surface the same root cause).

**Files:** TBD after Step 1.

- [ ] **Step 1: Re-run after Tasks 1-4 to check whether LZ3 was incidentally fixed**

```bash
mvn test -q -Dtest=TestS1Credits03Lz3TraceReplay -DfailIfNoTests=false
```
If first-error frame moved or test passes, skip to Step 6. Otherwise continue.

- [ ] **Step 2: Capture extended context window (frames 121-221, 100-frame radius)**

Use the same temporary writeReport modification approach as Task 4 Step 1. Find the first frame where sub_x diverges. (If sub_x diverges way before f221, that's the bug origin to chase.)

- [ ] **Step 3: Identify the originating event**

Look for: water entry/exit (`docs/s1disasm/_incObj/01 Sonic.asm:240` water-entry halving), bubble objects (object 0x0C), Drowning Countdown (object 0x0A — visible near s37 in trace), or LZ harpoons.

- [ ] **Step 4: Read the matching ROM disassembly and locate the engine equivalent**

- [ ] **Step 5: Add focused regression test, fix, verify**

Same shape as Tasks 1-4. Cross-game cross-check must include `TestS1Mz1TraceReplay` (no water-related code paths there, but watch for regressions in shared underwater handling).

- [ ] **Step 6: Commit with required trailers**

---

## Task 6: Final Verification (credits demo fixes)

- [ ] **Step 1: Run the entire trace replay matrix**

```bash
mvn test -q '-Dtest=*TraceReplay' -DfailIfNoTests=false
```
Expected: All S1 + S2 EHZ1 traces green. (S3K traces are out of scope for this plan.)

- [ ] **Step 2: Update `CHANGELOG.md`, `docs/status/known-discrepancies.md`, and any guide doc with the fixes landed**

These are required by the Branch Documentation Policy in `CLAUDE.md`.

- [ ] **Step 3: Final commit including doc updates with full trailer block**

---

## Task 7: Rewind Torture Test

**Independent of Tasks 1-6.** This phase adds a stress test for the rewind framework (`com.openggf.game.rewind.RewindController` + `KeyframeStore` + `RewindRegistry`) that drives the entire S2 EHZ1 trace forward with periodic rewinds, exercising edge cases around keyframe boundaries by sheer volume rather than enumerated cases.

**Design — pluggable rewind patterns:**

The test driver runs an entire BK2 trace forward, asking a `RewindTorturePattern` for the next `(forwardFrames, rewindFrames)` pair until the trace ends. Constraints enforced by the helper: `forwardFrames >= rewindFrames + 1` (i.e., `rewindAmount < rewindInterval`, never rewind beyond what we just played).

Three pattern implementations as separate `@Test` methods:

**Pattern (a) `tortureFixedAdjacent` — adjacent rewinds**
- `(forward=2, rewind=1)` repeated until trace end. Net progress = +1/cycle.
- Most adversarial pattern for keyframe-boundary edge cases — every cycle straddles different boundary positions.

**Pattern (b) `tortureProgressiveLongRewinds` — long rewinds with progressive landing**
- Forward to end-of-trace (frame `frameCount-1`).
- Rewind to frame `1`. Forward to end. Rewind to frame `2`. Forward to end. Rewind to `3`. … until landing frame `N` reaches `frameCount - 100` (avoid trivial cases).
- Each rewind crosses many keyframes; tests segment cache + long-distance `seekTo` correctness.

**Pattern (c) random — three seeds for reproducibility**
- `tortureRandomSeed42`, `tortureRandomSeed1337`, `tortureRandomSeed8675309`.
- Each cycle picks `interval ∈ [2, frameCount-currentFrame]`, then `amount ∈ [1, interval-1]`. Continue until cycle would overrun trace end.
- Failing test = rerun with same seed = same divergence (deterministic).

**Verification strategy:**

- **Per-iteration cheap check:** assert `controller.currentFrame()` matches the expected logical frame (sum of `+forward - rewind` deltas). Catches counter desync immediately.
- **Sampled full-state check:** every `CHECKPOINT_INTERVAL=100` iterations and at end-of-trace, capture `CompositeSnapshot` from the rewind run, then build a fresh fixture, fast-forward to the same logical frame, capture reference snapshot, assert byte-equivalence per registered key.
- **Pattern-(b) full-state check on every cycle** — only ~`frameCount/100` cycles total, so per-cycle is affordable.

**Files:**
- Read: `src/main/java/com/openggf/game/rewind/RewindController.java` (full)
- Read: `src/test/java/com/openggf/game/rewind/TestRewindParityAgainstTrace.java` (existing pattern: fixture boot, registry setup, snapshot capture, key-by-key compare)
- Create: `src/test/java/com/openggf/game/rewind/RewindTorturePattern.java` (interface + 3 impls)
- Create: `src/test/java/com/openggf/game/rewind/TestRewindTorture.java`

- [ ] **Step 1: Read existing infrastructure**

Read `src/main/java/com/openggf/game/rewind/RewindController.java` (already in context: `step()`, `recordExternalStep()`, `seekTo()`, `stepBackward()`).

Read `src/test/java/com/openggf/game/rewind/TestRewindParityAgainstTrace.java` end-to-end. Note especially:
- How the fixture boots S2 EHZ1 via the BK2 trace
- How `RewindRegistry` is constructed and which snapshottables are registered
- How `CompositeSnapshot` is captured (the keys listed at the top of that file)
- How field-level diff is performed (the per-component diff helper from `d3a44c83d`)

- [ ] **Step 2: Define the pattern interface and three implementations**

Create `src/test/java/com/openggf/game/rewind/RewindTorturePattern.java`:

```java
package com.openggf.game.rewind;

import java.util.Random;

/**
 * Generates the next (forwardFrames, rewindFrames) pair for a rewind torture run.
 * Returns null when the pattern has no more cycles to produce.
 */
interface RewindTorturePattern {

    /**
     * @param currentFrame the controller's current frame after the previous cycle
     * @param frameCount   total trace length
     * @return next cycle, or null when the pattern is exhausted
     */
    Cycle next(int currentFrame, int frameCount);

    /**
     * @param forwardFrames frames to step forward (>= 1)
     * @param rewindFrames  frames to rewind after stepping forward (0..forwardFrames-1).
     *                      MUST be strictly less than forwardFrames so the cycle makes
     *                      net forward progress.
     */
    record Cycle(int forwardFrames, int rewindFrames) {
        Cycle {
            if (forwardFrames < 1) {
                throw new IllegalArgumentException("forwardFrames must be >= 1, got " + forwardFrames);
            }
            if (rewindFrames < 0 || rewindFrames >= forwardFrames) {
                throw new IllegalArgumentException(
                        "rewindFrames must be in [0, forwardFrames-1], got " + rewindFrames
                                + " for forwardFrames=" + forwardFrames);
            }
        }
    }

    /** Pattern (a): every 2 frames forward, rewind 1. Net +1/cycle. */
    final class FixedAdjacent implements RewindTorturePattern {
        @Override
        public Cycle next(int currentFrame, int frameCount) {
            if (currentFrame + 2 >= frameCount) return null;
            return new Cycle(2, 1);
        }
    }

    /**
     * Pattern (b): forward to end-of-trace, rewind to landing frame N. After each
     * rewind, increment N until N reaches frameCount - 100.
     */
    final class ProgressiveLongRewind implements RewindTorturePattern {
        private int landingFrame = 1;

        @Override
        public Cycle next(int currentFrame, int frameCount) {
            if (landingFrame >= frameCount - 100) return null;
            int forwardFrames = (frameCount - 1) - currentFrame;
            if (forwardFrames < 1) return null;
            int rewindFrames = (currentFrame + forwardFrames) - landingFrame;
            if (rewindFrames < 1 || rewindFrames >= forwardFrames) return null;
            landingFrame++;
            return new Cycle(forwardFrames, rewindFrames);
        }
    }

    /**
     * Pattern (c): random forwardFrames in [2, remaining], random rewindFrames in
     * [1, forwardFrames-1]. Seeded for reproducibility.
     */
    final class Random_ implements RewindTorturePattern {
        private final Random rng;

        Random_(long seed) { this.rng = new Random(seed); }

        @Override
        public Cycle next(int currentFrame, int frameCount) {
            int remaining = frameCount - 1 - currentFrame;
            if (remaining < 2) return null;
            int forwardFrames = 2 + rng.nextInt(Math.min(remaining, 600) - 1);
            int rewindFrames = 1 + rng.nextInt(forwardFrames - 1);
            return new Cycle(forwardFrames, rewindFrames);
        }
    }
}
```

(The `Math.min(remaining, 600)` cap on random forward frames keeps individual cycles bounded so we don't accidentally generate one giant cycle that consumes the whole trace; tweak if too restrictive after Step 5.)

- [ ] **Step 3: Write the test class skeleton**

Create `src/test/java/com/openggf/game/rewind/TestRewindTorture.java`:

```java
package com.openggf.game.rewind;

import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_2)
class TestRewindTorture {

    private static final Path EHZ1_TRACE = Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int KEYFRAME_INTERVAL = 60;
    private static final int CHECKPOINT_INTERVAL = 100;

    @AfterEach
    void tearDown() { TestEnvironment.resetAll(); }

    @Test
    void tortureFixedAdjacent() throws Exception {
        runTorture("fixed-adjacent", new RewindTorturePattern.FixedAdjacent(), false);
    }

    @Test
    void tortureProgressiveLongRewinds() throws Exception {
        // Pattern (b) has few iterations; verify every cycle.
        runTorture("progressive-long", new RewindTorturePattern.ProgressiveLongRewind(), true);
    }

    @Test
    void tortureRandomSeed42() throws Exception {
        runTorture("random-seed-42", new RewindTorturePattern.Random_(42L), false);
    }

    @Test
    void tortureRandomSeed1337() throws Exception {
        runTorture("random-seed-1337", new RewindTorturePattern.Random_(1337L), false);
    }

    @Test
    void tortureRandomSeed8675309() throws Exception {
        runTorture("random-seed-8675309", new RewindTorturePattern.Random_(8675309L), false);
    }

    private void runTorture(String name, RewindTorturePattern pattern,
            boolean verifyEveryCycle) throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(EHZ1_TRACE),
                "EHZ1 trace directory not found: " + EHZ1_TRACE);
        // TODO: full implementation in Step 4
    }
}
```

- [ ] **Step 4: Implement `runTorture`**

The driver lifts the rewind-controller setup from `TestRewindParityAgainstTrace` and adds the pattern loop. Pseudocode:

```
runTorture(name, pattern, verifyEveryCycle):
    fixture = boot S2 EHZ1 from EHZ1_TRACE BK2
    registry = build RewindRegistry with all snapshottables registered
    keyframes = new InMemoryKeyframeStore
    inputs = wrap fixture's BK2 frame source into InputSource
    stepper = (in) -> fixture.stepFrameFromRecording(in)
    controller = new RewindController(registry, keyframes, inputs, stepper, KEYFRAME_INTERVAL)
    frameCount = inputs.frameCount()

    int iteration = 0
    int expectedLogicalFrame = 0
    while ((cycle = pattern.next(controller.currentFrame(), frameCount)) != null):
        for (i in 0 until cycle.forwardFrames):
            controller.step()
        expectedLogicalFrame += cycle.forwardFrames
        if cycle.rewindFrames > 0:
            int target = controller.currentFrame() - cycle.rewindFrames
            controller.seekTo(target)
            expectedLogicalFrame -= cycle.rewindFrames
        // Cheap per-cycle check
        assertEquals(expectedLogicalFrame, controller.currentFrame(),
            "[" + name + "] iteration " + iteration + ": frame counter desync")
        iteration++
        // Sampled full-state check
        if (verifyEveryCycle || iteration % CHECKPOINT_INTERVAL == 0):
            verifyAgainstReference(name, iteration, controller, expectedLogicalFrame)
    // Final check
    verifyAgainstReference(name, iteration, controller, expectedLogicalFrame)
    print(name + " completed " + iteration + " cycles")
```

`verifyAgainstReference(name, iteration, controller, frame)`:
1. Capture `actual = registry.capture()` (`CompositeSnapshot`).
2. Tear down current fixture state (don't lose the controller's keyframes — use a separate temp fixture).
3. Build a fresh secondary fixture for S2 EHZ1, step it forward `frame` times.
4. Capture `expected = secondaryRegistry.capture()`.
5. For each registered key in `expected`, assert `actual.get(key)` byte-equal to `expected.get(key)`. On mismatch, fail with: `[name] iteration N at frame F: key K diverged: <field-level diff>`.
6. Dispose secondary fixture.

For the per-key comparison, use the same field-level diff helper that `TestRewindParityAgainstTrace` uses (introduced in commit `d3a44c83d`). If that helper is private to the existing test, hoist it to a `package-private` static method on a shared helper class (`src/test/java/com/openggf/game/rewind/RewindSnapshotDiff.java`) — both tests will use it.

Write the full code (don't leave pseudocode in the implementation). Use `TestRewindParityAgainstTrace`'s registry-construction code verbatim, factored into a shared helper if it makes the tests cleaner. The existing test file is the canonical reference for which snapshottables to register — match that list exactly.

**Cost note:** The reference comparison rebuilds a fresh fixture and re-runs `frame` forward steps each time it's called. For the random patterns at `CHECKPOINT_INTERVAL=100` cycles, with average forward distance ~300 frames per cycle, that's ~17 reference checkpoints over a 5852-frame trace, each costing up to ~5800 forward steps. Total roughly 50k-100k extra forward steps per random test. If wall-clock exceeds 60s per test method, raise `CHECKPOINT_INTERVAL` to 250 or 500.

- [ ] **Step 5: Run the torture tests — first pass identifies any latent bugs**

```bash
mvn test -q -Dtest=TestRewindTorture -DfailIfNoTests=false
```

Expected outcomes per test method:
- (a) **All tests pass** — rewind framework already handles the stress patterns. Land the test as a regression guard.
- (b) **Some tests fail** — diff output identifies which key+pattern fails. For each unique failure mode:
  - Capture pattern + iteration + diverged key
  - Open a sub-task for the fix (loop Steps 6-7 per unique failure)

If a test fails non-deterministically across runs (unlikely with seeded patterns, but possible with hidden RNG state), that itself is a bug — file it as a separate fix.

- [ ] **Step 6: For each unique failure mode, fix the underlying rewind bug**

Per failure, repeat this mini-cycle:
1. Read the diverged key's snapshot/restore code in `src/main/java/com/openggf/game/rewind/snapshot/` and the originating system code.
2. Identify the bug — likely categories:
   - Cached derived field whose cache isn't invalidated on restore
   - Non-deterministic state during forward step that leaks across rewinds
   - Segment cache invalidation missing for some seek path
   - Snapshottable `key()` collision or registration ordering issue
3. Fix. If the bug originates in a ROM-faithful subsystem, cite the relevant disassembly in the comment.
4. Re-run `TestRewindTorture` — confirm the failure mode is gone and no new ones appear.
5. Commit the fix as its own commit (with required trailers).

- [ ] **Step 7: Run full rewind suite + trace replay matrix**

```bash
mvn test -q '-Dtest=*Rewind*,*TraceReplay' -DfailIfNoTests=false
```
Expected: all rewind tests + trace replay tests green.

- [ ] **Step 8: Commit `TestRewindTorture.java`, `RewindTorturePattern.java`, optional `RewindSnapshotDiff.java` helper, and any rewind-framework fixes**

Required trailers per `CLAUDE.md` Branch Documentation Policy.

---

## Notes on Task 7 scope

- The torture test itself is one commit. Each rewind-framework bug it surfaces is its own commit. If all five test methods pass on first run, that's still a useful regression guard worth landing.
- Wall-clock target: each test method ≤ 60s. If a test method exceeds that, raise `CHECKPOINT_INTERVAL` for that test.
- The `Math.min(remaining, 600)` random-cycle cap and `frameCount - 100` progressive-pattern cutoff are tunable. Adjust after first run if cycle counts look pathological.
- If Task 7 surfaces a bug that's deep enough to need its own design pass, write a follow-up plan (`docs/architecture/plans/2026-MM-DD-rewind-<bug>.md`) rather than blocking on it inside this plan.

---

## Notes on hypothesis confidence

| Task | Confidence | Rationale |
|---|---|---|
| 1 SLZ3 | High | Clear math: ROM=+3, ENG=+5, ROM Sonic_Jump does +5 and elevator runs after = -2 |
| 2 MZ2 | High (if Task 1 doesn't fix) | TouchBox at touch-time matches ROM, final position doesn't — clear post-touch issue |
| 3 SYZ3 | Medium | Hand-trace says ROM should also collide. Hypothesis is the most consistent explanation but Step 1 instrumentation is mandatory before code change |
| 4 SBZ1 | Low | Need extended context to find divergence origin |
| 5 LZ3 | Low | Same — origin frame unknown |

Tasks 1 and 2 may share a root cause (post-jump / post-touch solid-contact ordering), so completing Task 1 before Task 2 is important — Task 2 may collapse to a no-op.

Tasks 4 and 5 may also share a root cause (sub-pixel desync via non-standard velocity-write paths); doing them together after instrumentation is added is reasonable.

## Key references

- `CLAUDE.md` — project architecture, ROM offset finder, two-tier services, per-game physics framework, branch documentation policy
- `.claude/skills/trace-replay-bug-fixing/skill.md` — the workflow this plan follows
- `docs/s1disasm/_incObj/01 Sonic.asm` — Sonic main routine
- `docs/s1disasm/_incObj/sub ReactToItem.asm` — ROM touch response logic
- `src/main/java/com/openggf/level/objects/ObjectManager.java:3703-4232` — engine touch response implementation
