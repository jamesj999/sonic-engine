# S2 Production Visual Bootstrap Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a prepared production visual replay adopt its real title-card state and preserve the ROM-owned level-start leader history through the first direct sidekick INIT tick, then pin the first complete S2 EHZ1 visual segment.

**Architecture:** `TraceReplayDriver` will separate prepared-session adoption from standalone metadata bootstrap. `LevelManager` will grant a one-shot, rewind-captured history-prefill ownership token only to a controller whose leader is the exact main player whose ring it populated; `SidekickCpuController` will consume that token by performing its ordinary captured-anchor/CPU reset while skipping only the leader-ring rewrite. Bootstrap-only placement and chained-sidekick behavior remain separate.

**Tech Stack:** Java 21, Maven 3.9, JUnit Jupiter, OpenGGF trace schema v5, BizHawk BK2/trace fixtures (comparison-only).

## Global Constraints

- Run Maven on JDK 21; verify with `mvn -v` before every baseline or verification batch.
- Discover and use the root ROM files without renaming/copying them: `s1.gen`, `s2.gen`, and `s3k.gen`, with the hashes specified in `AGENTS.md`.
- Never hydrate gameplay from trace physics/aux rows and never alter committed trace fixtures for this fix.
- Introduce no zone, route, frame, or fixture-name predicate and no fitted timing constant.
- Preserve the original five `TestS2CompleteEmeraldRunChain` axes exactly; special-stage visual frame 136 remains out of scope.
- The shared S2/S3K behavior is licensed by S2 `Obj01_Init_Continued` (`s2.asm:36201-36217`) and S3K `Sonic_Init_Continued` / `Reset_Player_Position_Array` (`sonic3k.asm:21931-21940,22166-22178`).
- Keep the existing bootstrap-specific `applyLevelStartSidekickPlacementSkipPrefill()` behavior isolated; production prefill ownership must not use that helper.
- Develop on `bugfix/ai-s2-visual-bootstrap-ownership` in a linked worktree created from the current `develop` commit. Do not switch the main workspace branch.
- Use `-Ptrace-replay -Dmse=off -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical` for comparable trace measurements.
- Update `CHANGELOG.md`, the `README.md` release section, the adjacent audit, and `docs/status/trace-frontier-log.md`; stage every artifact.
- The reusable-pitfall checklist is a justified skip: fresh-context controls against the unchanged S2 and S3K reference packages both derived the complete safe ownership design, so `superpowers:writing-skills` forbids a speculative skill edit when the baseline does not fail. Keep `Skills: n/a` and record this evidence in the adjacent audit.

## Pre-execution gate

This reviewed plan must be committed on the checked-out `develop` branch before Task 1 begins. From the main workspace, stage only this file, inspect it, and commit it as a documentation-only artifact:

```bash
git add docs/architecture/plans/2026-08-14-s2-production-visual-bootstrap-ownership.md
git diff --cached --check
git diff --cached --stat
git commit -m "docs: plan S2 production visual bootstrap ownership" \
  -m "Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Expected: `develop` is clean at the plan commit before fetching, pulling, or creating the implementation worktree. The plan therefore travels in the integration base and does not need to be staged again in Task 4.

---

### Task 1: Create the isolated worktree and record both baselines

**Files:**

- Read: `.gitignore`
- Read: `docs/architecture/audits/2026-08-14-s2-emerald-frontier-follow-up.md`
- Read: `docs/status/trace-frontier-log.md`
- Generated only: `target/surefire-reports/`

**Interfaces:**

- Consumes: `develop` at the plan commit and the root-level ROM files.
- Produces: an isolated `bugfix/ai-s2-visual-bootstrap-ownership` worktree plus exact default-suite and trace-profile baseline inventories for later comparison.

- [ ] **Step 1: Verify repository/worktree state and the worktree directory guard**

Run from the main workspace:

```bash
git fetch origin
git pull --ff-only origin develop
git status --short --branch
git rev-parse --git-dir
git rev-parse --git-common-dir
git rev-parse --show-superproject-working-tree
git branch --show-current
git check-ignore -q .worktrees
mvn -v
sha1sum s1.gen s2.gen s3k.gen
```

Expected: clean `develop`, normal main checkout, `.worktrees` ignored, Maven JVM 21, and the three `AGENTS.md` SHA-1 values.

- [ ] **Step 2: Create the implementation worktree without switching main**

```bash
git worktree add .worktrees/ai-s2-visual-bootstrap-ownership \
  -b bugfix/ai-s2-visual-bootstrap-ownership develop
test -r .worktrees/ai-s2-visual-bootstrap-ownership/s1.gen
test -r .worktrees/ai-s2-visual-bootstrap-ownership/s2.gen
test -r .worktrees/ai-s2-visual-bootstrap-ownership/s3k.gen
sha1sum .worktrees/ai-s2-visual-bootstrap-ownership/s1.gen \
  .worktrees/ai-s2-visual-bootstrap-ownership/s2.gen \
  .worktrees/ai-s2-visual-bootstrap-ownership/s3k.gen
```

Expected: the new worktree starts at the current `develop` plan commit, and its checkout-hook ROM links are readable and resolve to the three `AGENTS.md` SHA-1 values.

- [ ] **Step 3: Record the updated integration default-suite baseline**

Run from the main workspace:

```bash
mvn clean test -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dsonic1.rom.path=s1.gen -Dsonic2.rom.path=s2.gen -Ds3k.rom.path=s3k.gen
```

Expected: record the Maven summary and every failing/error class exactly. A red baseline is acceptable; later work may not add or worsen a failure.

- [ ] **Step 4: Record the updated integration trace-profile baseline**

```bash
mvn clean test -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dtest='*TraceReplay' \
  -Dsonic1.rom.path=s1.gen -Dsonic2.rom.path=s2.gen -Ds3k.rom.path=s3k.gen
```

Expected: record run/failure/error/skip totals and the exact red-class set before another Maven invocation replaces the reports.

- [ ] **Step 5: Record the pre-change S1 prepared visual baseline**

```bash
mvn test -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dtest=TestS1CompleteEmeraldVisualRun \
  -Dsonic1.rom.path=s1.gen
```

Expected: record the exact outcome, cursor, and first-error frame/field if red. The default suite excludes `tests/trace/**`, and the restricted `*TraceReplay` sweep does not select this class, so this explicit pre-change result is the attribution baseline for the shared prepared-replay change.

- [ ] **Step 6: Reconfirm the five-axis S2 chain baseline**

```bash
mvn test -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dtest=TestS2CompleteEmeraldRunChain \
  -Dsonic2.rom.path=s2.gen
```

Expected: the exact five axes and values recorded in the adjacent audit: cursor `3977/3997`, segment 11 with 236 errors first at frame 3525, gap deltas `-1`, `+1`, and `16 expected/18 actual`.

---

### Task 2: Add the red visual/history regressions and cross-game guards

**Files:**

- Create: `src/test/java/com/openggf/tests/trace/runs/TestS2CompleteEmeraldVisualRun.java`
- Modify: `src/test/java/com/openggf/tests/TestS2PostLoadAssemblyHeadless.java`
- Modify: `src/test/java/com/openggf/tests/TestMultiSidekickSpawn.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kMgzSidekickAirCollisionOrdering.java`
- Modify: `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerRewindCapture.java`

**Interfaces:**

- Consumes: `VisualRunReplayHarness.stopAfterSegmentBody(int)`, `LevelManager.spawnSidekicks(int,int)`, `SidekickCpuController.update(int)`, and the read-only history-copy APIs.
- Produces: behavioral tests for prepared visual adoption, direct-leader prefill preservation, chained-leader isolation, S3K intro-air preservation, and rewind capture of `levelStartLeaderHistoryPrefillPending`.

- [ ] **Step 1: Add the production visual canary**

Create this class:

```java
package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_2)
class TestS2CompleteEmeraldVisualRun {
    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s2", "runs",
            "s2-sonic-tails-complete-emeralds");

    @AfterEach
    void tearDown() {
        VisualRunReplayHarness.tearDown();
    }

    @Test
    void replaysEveryComparedRowOfTheFirstEhz1Segment() throws Exception {
        VisualRunReplayHarness.Result result = VisualRunReplayHarness.replay(
                RUN_DIR, VisualRunReplayHarness.stopAfterSegmentBody(0));

        assertEquals(VisualRunReplayHarness.Outcome.REACHED_SEGMENT, result.outcome());
        assertEquals(4_479, result.sharedCursor(),
                "769 + 3710 is the first row after the complete EHZ1 body: " + result);
        assertEquals(0, result.currentSegmentIndex());
    }
}
```

The literal cursor is independently derived from manifest segment 0 (`bk2_frame_offset=769`, `trace_frame_count=3710`).

- [ ] **Step 2: Add the direct-leader history regression**

Change `TestS2PostLoadAssemblyHeadless.createSidekick()` to construct the controller with `sprite` as its explicit leader. Add this helper, which compares every entry against hand-derived literals supplied by the test:

```java
private static void assertHistoryFilled(
        AbstractPlayableSprite leader, int expectedX, int expectedY) {
    short[] xHistory = leader.copyXHistory();
    short[] yHistory = leader.copyYHistory();
    assertEquals(64, xHistory.length);
    assertEquals(64, yHistory.length);
    for (int slot = 0; slot < 64; slot++) {
        assertEquals(expectedX, xHistory[slot], "history X slot " + slot);
        assertEquals(expectedY, yHistory[slot], "history Y slot " + slot);
    }
}
```

Then add:

```java
@Test
void firstCpuInitPreservesTheLevelLoadLeaderHistoryPrefill() {
    sprite.setCentreX((short) 200);
    sprite.setCentreY((short) 400);
    Tails tails = createSidekick();

    GameServices.level().spawnSidekicks(-40, 0);
    tails.getCpuController().update(0);

    assertHistoryFilled(sprite, 168, 404);
    assertEquals(63, sprite.historyPos(),
            "the first live Sonic_RecordPos write must still target slot 0");
    assertFalse(capturedLevelStartLeaderHistoryPrefillPending(tails.getCpuController()),
            "the level-start prefill ownership token is one-shot");
}
```

`168,404` is the hand-derived ROM prefill `(200-$20,400+4)`. The current implementation rewrites all entries to `200,400`, so this is a behavioral failure rather than a source-text assertion.

Use this test-local helper until Task 3 adds the rewind record component:

```java
private static boolean capturedLevelStartLeaderHistoryPrefillPending(
        SidekickCpuController controller) {
    try {
        Object rewindState = controller.captureRewindState();
        return (boolean) rewindState.getClass()
                .getMethod("levelStartLeaderHistoryPrefillPending")
                .invoke(rewindState);
    } catch (ReflectiveOperationException e) {
        throw new AssertionError(
                "Sidekick CPU rewind state must expose levelStartLeaderHistoryPrefillPending", e);
    }
}
```

The literal history assertion remains before the reflection assertion, so Task 2 still records the behavioral history failure against the old implementation. Reflection keeps this red test compilable before Task 3 adds the named record accessor; after the history behavior is fixed, it catches a token that was not consumed.

- [ ] **Step 3: Add the chained-leader isolation guard**

Add to `TestMultiSidekickSpawn`:

```java
@Test
void chainedFollowerStillInitializesItsOwnLeadersHistory() {
    GameServices.level().spawnSidekicks(-40, 0);
    AbstractPlayableSprite chainedLeader = sidekicks[0];
    chainedLeader.setCentreX((short) 300);
    chainedLeader.setCentreY((short) 400);
    chainedLeader.prefillPositionHistoryWithCentre((short) 11, (short) 22);

    controllers[1].update(0);

    assertHistoryFilled(chainedLeader, 300, 400);
}
```

Add this test-local helper to `TestMultiSidekickSpawn` as well:

```java
private static void assertHistoryFilled(
        AbstractPlayableSprite leader, int expectedX, int expectedY) {
    short[] xHistory = leader.copyXHistory();
    short[] yHistory = leader.copyYHistory();
    assertEquals(64, xHistory.length);
    assertEquals(64, yHistory.length);
    for (int slot = 0; slot < 64; slot++) {
        assertEquals(expectedX, xHistory[slot], "history X slot " + slot);
        assertEquals(expectedY, yHistory[slot], "history Y slot " + slot);
    }
}
```

This test is expected to pass before the fix and protects against incorrectly granting the main-player ownership token to every controller in the loop.

- [ ] **Step 4: Add the S3K falling-intro air-state guard**

In `TestS3kMgzSidekickAirCollisionOrdering`, add these imports explicitly:

```java
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Tails;

import static org.junit.jupiter.api.Assertions.assertTrue;
```

Create a fresh CPU Tails with the fixture main sprite as explicit leader, register it, call `spawnSidekicks(-32,4)`, move the leader so a live-anchor shortcut would be visible, then simulate the real post-spawn zone-event write before INIT:

```java
@Test
void firstCpuInitPreservesPostSpawnFallingIntroAirState() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withSharedLevel(sharedLevel)
            .build();
    AbstractPlayableSprite leader = fixture.sprite();
    Tails fallingTails = new Tails("tails_intro_guard", (short) 0, (short) 0);
    fallingTails.setCpuControlled(true);
    SidekickCpuController controller =
            new SidekickCpuController(fallingTails, leader);
    fallingTails.setCpuController(controller);
    GameServices.sprites().addSprite(fallingTails, "tails");

    short capturedX = leader.getCentreX();
    short capturedY = leader.getCentreY();
    GameServices.level().spawnSidekicks(-32, 4);
    leader.setCentreX((short) (capturedX + 100));
    leader.setCentreY((short) (capturedY + 100));
    fallingTails.setAir(true);
    controller.update(0);

    assertEquals(capturedX - 32, fallingTails.getCentreX(),
            "INIT placement must use the level-start leader anchor");
    assertEquals(capturedY + 4, fallingTails.getCentreY(),
            "INIT placement must use the level-start leader anchor");
    assertTrue(fallingTails.getAir(),
            "MGZ1/HCZ1/LRZ1 apply zone-event air state after sidekick spawn");
    assertHistoryFilled(leader, capturedX - 32, capturedY + 4);
}
```

Add the same literal-entry `assertHistoryFilled` helper shown in Step 3 to this class. Keep the captured-anchor and air assertions before the intentionally red history assertion: before the fix they prove correct placement and post-spawn air-state preservation before the leader-history assertion fails after INIT resets the ring from the moved live leader. After the fix, all three pass. Together they catch accidental reuse of the bootstrap skip helper, which uses the live leader and calls `setAir(false)`.

- [ ] **Step 5: Add a rewind sentinel for the proposed one-shot token**

Add this entry to `scalarSentinels()` in `TestSidekickCpuControllerRewindCapture`:

```java
values.put("levelStartLeaderHistoryPrefillPending", true);
```

The existing reflection-driven round-trip test will fail because the field does not exist yet; after implementation it proves the new per-frame scalar is captured and restored.

- [ ] **Step 6: Run the tests and verify the intended red/green split**

```bash
mvn test -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dtest=TestS2CompleteEmeraldVisualRun,TestS2PostLoadAssemblyHeadless,TestMultiSidekickSpawn,TestS3kMgzSidekickAirCollisionOrdering,TestSidekickCpuControllerRewindCapture \
  -Dsonic2.rom.path=s2.gen -Ds3k.rom.path=s3k.gen
```

Expected:

- `TestS2CompleteEmeraldVisualRun` fails at segment 0 frame 0 with 91 history errors (`0x0293` expected, `0x0294` actual).
- `firstCpuInitPreservesTheLevelLoadLeaderHistoryPrefill` fails with `168/404` expected and live-leader values actual.
- `firstCpuInitPreservesPostSpawnFallingIntroAirState` fails only on the 64-entry leader-history assertion; captured-anchor placement and `air=true` remain correct.
- the rewind sentinel fails because `levelStartLeaderHistoryPrefillPending` is absent.
- the chained-leader guard passes.

Do not write production code until this exact failure shape is observed.

---

### Task 3: Implement prepared-session adoption and one-shot prefill ownership

**Files:**

- Modify: `src/main/java/com/openggf/trace/replay/TraceReplayDriver.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Modify: `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`
- Modify: `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCarry.java`
- Modify: `src/test/java/com/openggf/sprites/managers/TestInitialPlayableProcessSpritesPass.java`
- Modify: `CHANGELOG.md`
- Modify: `docs/architecture/plans/2026-08-14-s2-production-visual-bootstrap-ownership.md`

**Interfaces:**

- Consumes: `startPlayback(playback, preparedLevel)`, the exact leader identity from `SidekickCpuController.getLeader()`, and the prefilled main-player ring.
- Produces: `SidekickCpuController.adoptLevelStartLeaderHistoryPrefill()` and a rewind record component `boolean levelStartLeaderHistoryPrefillPending`.

- [ ] **Step 1: Make prepared replay skip standalone position setup**

In `TraceReplayDriver.startPlayback`, wrap both the pre-snap block and `applyStartPositionAndGroundSnap` in `if (!preparedLevel)`. Leave bootstrap selection after it unchanged:

```java
if (!preparedLevel) {
    if (TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace)
            && !TraceReplaySessionBootstrap
                    .shouldPreserveFreshGroundedStatusUntilFirstDispatch(trace)) {
        AbstractPlayableSprite preSnapSprite = fixture.sprite();
        if (preSnapSprite != null) {
            TraceMetadata meta = trace.metadata();
            preSnapSprite.setCentreX(meta.startX());
            preSnapSprite.setCentreY(meta.startY());
            GameServices.collision().resolveGroundAttachment(
                    preSnapSprite, 14, () -> false);
        }
    }
    TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
}
```

Update the method comment to state that this block recreates `HeadlessTestFixture.Builder` only for standalone replay; a prepared visual session adopts the production title-card state.

- [ ] **Step 2: Measure the first correction independently**

Run only the visual canary:

```bash
mvn test -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dtest=TestS2CompleteEmeraldVisualRun \
  -Dsonic2.rom.path=s2.gen
```

Expected: still red, but reduced from 91 to 76 frame-0 history errors with remaining entries at the live leader Y `0x0290`. If the shape differs, stop and update both design and plan before continuing.

- [ ] **Step 3: Add the distinct one-shot ownership token**

In `SidekickCpuController`, add adjacent to `bootstrapPreludePlacementApplied`:

```java
private boolean levelStartLeaderHistoryPrefillPending;
```

Add the semantic production method:

```java
/**
 * Adopts the ROM-owned Pos_table/Stat_table prefill already written for this
 * controller's direct leader during level assembly. Consumed by the next INIT
 * placement; it does not authorize trace data or bootstrap placement.
 */
public void adoptLevelStartLeaderHistoryPrefill() {
    levelStartLeaderHistoryPrefillPending = true;
}
```

- [ ] **Step 4: Consume the token by skipping only the ring rewrite**

Refactor the private placement method to accept `preserveExistingLeaderPrefill` in addition to `useRomAccuratePrefill`. Keep all captured-anchor placement, transient CPU reset, velocity reset, and air-state preservation in the common body. Change only the history tail:

```java
if (useRomAccuratePrefill) {
    leader.prefillPositionHistoryWithCentre(
            (short) (anchorX + LEVEL_START_X_OFFSET),
            (short) (anchorY + LEVEL_START_Y_OFFSET));
} else if (!preserveExistingLeaderPrefill) {
    leader.resetPositionHistory();
}
```

Consume the production token before the bootstrap branch so a real level assembly cannot fall into the live-anchor/`setAir(false)` helper:

```java
if (levelStartLeaderHistoryPrefillPending) {
    levelStartLeaderHistoryPrefillPending = false;
    applyLevelStartSidekickPlacement(false, true);
} else if (bootstrapPreludePlacementApplied) {
    applyLevelStartSidekickPlacementSkipPrefill();
} else {
    applyLevelStartSidekickPlacement(false, false);
}
```

`applyLevelStartSidekickPlacementForBootstrap()` must call `(true,false)`. Correct the stale nearby S2 `Obj01_Init` citation from `s2.asm:35907-35918` to `s2.asm:36201-36217` while editing this block.

- [ ] **Step 5: Grant ownership only to the ring's exact owner**

In `LevelManager.spawnSidekicks`, keep the existing main-player prefill. After capturing the leader anchor, gate the new method by reference identity:

```java
SidekickCpuController controller = sidekick.getCpuController();
if (player instanceof AbstractPlayableSprite leaderSprite) {
    controller.captureLevelStartLeaderAnchor(
            leaderSprite.getCentreX(), leaderSprite.getCentreY());
    if (controller.getLeader() == leaderSprite) {
        controller.adoptLevelStartLeaderHistoryPrefill();
    }
}
```

The identity check is structural ownership, not a game/zone/route predicate. Do not mark controllers that follow a preceding sidekick.

- [ ] **Step 6: Capture and restore the token**

Add `boolean levelStartLeaderHistoryPrefillPending` immediately before `bootstrapPreludePlacementApplied` in `SidekickCpuRewindExtra`. Pass and restore it in `SidekickCpuController.captureRewindState()` / `restoreRewindState()`. Add the new accessor pass-through at the same position in the two test constructors:

```java
source.levelStartLeaderHistoryPrefillPending(),
source.bootstrapPreludePlacementApplied(),
```

Files with constructor updates:

- `TestSidekickCpuControllerCarry.withMgzControlScalars`
- `TestInitialPlayableProcessSpritesPass.withCpuSetupSentinels`

- [ ] **Step 7: Run the focused green set**

```bash
mvn test -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dtest=TestS2CompleteEmeraldVisualRun,TestS2PostLoadAssemblyHeadless,TestMultiSidekickSpawn,TestS3kMgzSidekickAirCollisionOrdering,TestSidekickCpuControllerRewindCapture,TestSidekickCpuControllerCarry,TestInitialPlayableProcessSpritesPass,TestS2ReplayBootstrapTailsFrame0,TestHardwareTimingAuthorityGuard,TestRewindCoverageGuard \
  -Dsonic2.rom.path=s2.gen -Ds3k.rom.path=s3k.gen
```

Expected: all selected tests pass. The visual canary stops at cursor 4479 after all EHZ1 rows; direct history remains `168,404`; chained history becomes `300,400`; S3K air remains true; rewind round-trips the pending token.

- [ ] **Step 8: Confirm the later visual frontier without broadening the canary**

Temporarily add this uncommitted method to `TestS2CompleteEmeraldVisualRun`:

```java
@Test
void probeFirstSpecialStageFrontier() throws Exception {
    VisualRunReplayHarness.replay(
            RUN_DIR, VisualRunReplayHarness.stopAfterSegmentBody(1));
}
```

Run:

```bash
mvn test -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dtest=TestS2CompleteEmeraldVisualRun#probeFirstSpecialStageFrontier \
  -Dsonic2.rom.path=s2.gen
```

Expected: EHZ1 completes and the first pause is special-stage frame 136 on `dynamic_art.edges`, with ROM `[]`/outstanding `[1,2,3]` and engine completions `[4,5,6]`. Remove exactly `probeFirstSpecialStageFrontier` through `apply_patch`, then rerun the committed EHZ1 canary green before proceeding.

- [ ] **Step 9: Commit the runtime/test slice and changelog**

Stage the Task 3 production changes, rewind-constructor updates, `CHANGELOG.md`,
and this plan update, then commit them with `Changelog: updated`. Task 4 owns
the README, audit, frontier documentation, and its own staging/commit-plan
update; it does not amend this implementation commit.

---

### Task 4: Record the delivered frontier and release-facing change

**Files:**

- Modify: `README.md`
- Modify: `docs/status/trace-frontier-log.md`
- Modify: `docs/architecture/audits/2026-08-14-s2-emerald-frontier-follow-up.md`
- Modify: `docs/architecture/plans/2026-08-14-s2-production-visual-bootstrap-ownership.md`

**Interfaces:**

- Consumes: actual red/green measurements from Tasks 1-3.
- Produces: release note, merge-policy README summary, canonical trace-frontier entry, and the adjacent relay log requested by the user.

- [ ] **Step 1: Add the README release summary**

Under `### v0.6.prerelease`, add a short bullet titled `Sonic 2 production visual trace bootstrap ownership (2026-08-14)`. Summarize the real-title-card adoption and the new EHZ1 visual canary; state that the original five synthetic-chain axes are unchanged.

- [ ] **Step 2: Append the trace frontier entry**

Use the current format in `docs/status/trace-frontier-log.md`. Record:

- exact worktree branch/commit context;
- the exact `TestS2CompleteEmeraldVisualRun` command;
- PASS through EHZ1 cursor 4479;
- the diagnostic wider-probe result: first error special-stage frame 136, field `dynamic_art.edges`;
- the exact chain command and its unchanged five axes;
- whether the measurement was clean committed state or local uncommitted probe state.

- [ ] **Step 3: Finish the adjacent audit relay**

Change its status to implementation complete pending integration, and add dated progress entries for:

- both TDD red failures;
- the intermediate 91-to-76 measurement;
- focused green results;
- S3K/multi-sidekick/rewind validation;
- unchanged original five axes;
- a clearly marked pending-final-verification entry; Task 5 replaces it with the exact full-suite and trace-profile comparisons.
- the reusable-pitfall checklist ruling: two fresh-context controls using the unchanged S2/S3K skill packages already produced the complete safe design, so no speculative skill edit was made and `Skills: n/a` remains valid.

- [ ] **Step 4: Stage and inspect every deliverable**

```bash
git add README.md \
  docs/status/trace-frontier-log.md \
  docs/architecture/audits/2026-08-14-s2-emerald-frontier-follow-up.md \
  docs/architecture/plans/2026-08-14-s2-production-visual-bootstrap-ownership.md
git diff --cached --check
git diff --cached --stat
```

Expected: no fixture bytes, generated reports, temporary probes, or unrelated user changes are staged.

- [ ] **Step 5: Commit with the required trailers**

```bash
git commit -m "docs: record S2 production visual bootstrap frontier" \
  -m "Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Expected: hooks pass without `--no-verify`.

---

### Task 5: Cross-game verification and independent implementation review

**Files:**

- Read: all files changed in Tasks 2-4
- Generated only: `target/surefire-reports/`, `target/trace-reports/`
- Modify: the adjacent audit and trace frontier log after final measurements

**Interfaces:**

- Consumes: the committed implementation slice and Task 1 baselines.
- Produces: exact no-regression evidence and an independently reviewed implementation.

- [ ] **Step 1: Run focused cross-game and keep-green tests**

```bash
mvn test -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dtest=TestS2CompleteEmeraldVisualRun,TestS1CompleteEmeraldVisualRun,TestS2PostLoadAssemblyHeadless,TestMultiSidekickSpawn,TestS3kMgzSidekickAirCollisionOrdering,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestS3kSonicTailsAizSegmentTraceReplay \
  -Dsonic1.rom.path=s1.gen -Dsonic2.rom.path=s2.gen -Ds3k.rom.path=s3k.gen
```

Expected: all selected tests that were green on baseline remain green. If an existing visual/trace canary has a baseline red result, its class/field/error count must be unchanged or improved for a source-backed reason.

- [ ] **Step 2: Re-run the five-axis chain alone**

Use the Task 1 Step 6 command. Expected: exact same five axes and values; any change is an implementation blocker.

- [ ] **Step 3: Run the full development-worktree default suite**

Use the Task 1 Step 3 command from the worktree. Compare exact failing/error class names and counts with the integration baseline. No baseline pass may turn red and no baseline failure may worsen due to this change.

- [ ] **Step 4: Run the full development-worktree trace sweep**

Use the Task 1 Step 4 command from the worktree. Compare the exact red-class set and each moved frontier. The intended new result is the separate visual canary, not a change to the original chain axes.

- [ ] **Step 5: Request an independent code review**

Give the reviewer the design, this plan, the implementation commit, and exact test results. Require a prioritized concrete issue report covering:

- prepared versus standalone bootstrap ownership;
- exact-leader/multi-sidekick scoping;
- S3K captured-anchor and intro-air semantics;
- one-shot token lifecycle and rewind completeness;
- comparison-only/any-BK2 rules;
- documentation/test evidence.

Fix every valid finding with a new red-green cycle, amend both design and plan if assumptions change, then repeat review until `NO BLOCKING ISSUES`.

- [ ] **Step 6: Finalize and commit the measurement docs unconditionally**

Use `apply_patch` to replace the audit's pending-final-verification entry with the exact Task 5 focused, default-suite, trace-profile, S1 visual, and five-axis comparison results. Append the final clean-commit verification context to the trace frontier log even when the measured frontier is unchanged. Then stage and commit both documents:

```bash
git add docs/architecture/audits/2026-08-14-s2-emerald-frontier-follow-up.md \
  docs/status/trace-frontier-log.md
git diff --cached --check
git diff --cached --stat
git commit -m "docs: finalize S2 visual bootstrap verification" \
  -m "Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Expected: the committed adjacent audit contains the exact full-suite and trace-profile comparisons; no published fixture bytes are amended.

---

### Task 6: Integrate into the checked-out develop branch, push, and clean up

**Files:**

- Merge result: all Task 2-5 files
- Generated only: main-workspace `target/` reports

**Interfaces:**

- Consumes: reviewed implementation branch, clean worktree, and baseline inventories.
- Produces: pushed `develop`, post-merge no-regression evidence, removed implementation worktree, and deleted merged local scaffold branch.

- [ ] **Step 1: Verify the implementation worktree is clean and fully committed**

```bash
git status --short --branch
git log --oneline --decorate -5
```

Expected: no unknown, user-authored, or unmerged changes.

- [ ] **Step 2: Update the main-workspace integration branch without switching it**

Run from the main workspace:

```bash
git fetch origin
git pull --ff-only origin develop
git status --short --branch
```

If upstream moved, rerun Task 1 Steps 3-6 on the new integration tip before merging so the default suite, trace sweep, S1 visual control, and five-axis S2 chain all have current attribution baselines.

- [ ] **Step 3: Merge the reviewed worktree branch into develop**

```bash
git merge --no-ff bugfix/ai-s2-visual-bootstrap-ownership
```

Expected: merge succeeds without switching branches. `README.md` is part of the branch range, satisfying the develop merge policy. Reconcile any conflict carefully and record it in the audit/final report.

- [ ] **Step 4: Run post-merge default and trace verification**

From the main workspace, run Task 1 Steps 3-6 plus Task 5 Step 1. Compare against the updated pre-merge integration baseline. No previously passing test may fail; the S1 visual result may not regress and the S2 chain must retain the five exact axes.

- [ ] **Step 5: Push only develop**

```bash
git push origin develop
```

Expected: the remote `develop` tip contains the design, reviewed plan, implementation, audit/frontier updates, and merge commit. Do not push the local worktree branch.

- [ ] **Step 6: Remove the merged worktree and local scaffold branch**

First inspect and classify any remaining worktree changes. If only generated `target/` output remains, remove the worktree through Git, then verify merge ancestry and delete the local branch:

```bash
git worktree remove .worktrees/ai-s2-visual-bootstrap-ownership
git merge-base --is-ancestor bugfix/ai-s2-visual-bootstrap-ownership develop
git branch -d bugfix/ai-s2-visual-bootstrap-ownership
git worktree prune
git status --short --branch
```

Expected: clean `develop`, no stale linked worktree, local scaffold branch deleted, and remote push complete.

---

## Completion Evidence

The final report must include:

- the production ownership change and why the two original root causes required both halves;
- the chained-sidekick and S3K-air design challenges and their resolutions;
- any upstream changes/conflicts reconciled;
- exact focused, default-suite, trace-sweep, and five-axis chain commands/outcomes;
- the implementation/merge commits and the pushed `develop` tip;
- confirmation that the implementation worktree and local branch were removed.
