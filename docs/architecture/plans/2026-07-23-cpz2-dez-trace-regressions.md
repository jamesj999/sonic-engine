# CPZ2 and DEZ Trace Regression Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore green CPZ2 and DEZ Sonic 2 trace replays with ROM-native runtime and bootstrap behavior.

**Architecture:** Reuse `SidekickCpuController`'s existing semantic water clamp in automatic recovery flight. Correct the S2 title-card history bootstrap by retaining the native level-start leader anchor separately from the metadata gameplay coordinate and using that anchor for the ROM `Obj01_Init` prefill.

**Tech Stack:** Java 21, JUnit 5, Maven, Sonic 2 REV01 trace replay fixtures.

## Global Constraints

- Trace data remains comparison-only and read-only.
- No zone, route, or frame carve-outs.
- Production behavior must cite the Sonic 2 disassembly.
- Update `docs/status/trace-frontier-log.md` when the trace results move.

---

### Task 1: CPZ2 water-target regression

**Files:**
- Modify: `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerFlightAutoRecovery.java`
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`

**Interfaces:**
- Consumes: `SidekickCpuController.clampTargetYToWater(int)`
- Produces: automatic-recovery `catchUpTargetY()` clamped to the active gameplay waterline minus `0x10`

- [ ] **Step 1: Write the failing test**

Add a fixture-backed test that puts the controller in `FLIGHT_AUTO_RECOVERY`,
seeds the leader's delayed Y below the active water ceiling, runs one update,
and asserts `catchUpTargetY() == gameplayWaterY - 0x10`.

- [ ] **Step 2: Run test to verify it fails**

Run:
`mvn -Dmse=off -Dtest=TestSidekickCpuControllerFlightAutoRecovery test`

Expected: the new assertion reports the unbounded delayed leader Y.

- [ ] **Step 3: Write minimal implementation**

Change the target sampling to:

```java
int targetY = clampTargetYToWater(
        leader.getCentreY(ROM_FOLLOW_DELAY_FRAMES) & 0xFFFF);
```

Retain a nearby `s2.asm` citation for `TailsCPU_Flying_Part2`.

- [ ] **Step 4: Run test to verify it passes**

Run:
`mvn -Dmse=off -Dtest=TestSidekickCpuControllerFlightAutoRecovery test`

Expected: PASS.

### Task 2: DEZ native-start history regression

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/s2/TestS2ReplayBootstrapTailsFrame0.java`
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`

**Interfaces:**
- Consumes: `ZoneRegistry.getStartPosition(int, int)` before metadata positioning
- Produces: a native level-start `(x,y)` anchor used only for S2 `Obj01_Init` history prefill

- [ ] **Step 1: Write the failing test**

Add `dez_ending` to `nativePreludeSeedsPlayerHistoryFromRomOrdering` and map it
to `Sonic2ZoneConstants.ZONE_DEZ`.

- [ ] **Step 2: Run test to verify it fails**

Run:
`mvn -Dmse=off -Ds2.rom.path='Sonic The Hedgehog 2 (W) (REV01) [!].gen' -Dtest=TestS2ReplayBootstrapTailsFrame0#nativePreludeSeedsPlayerHistoryFromRomOrdering test`

Expected: DEZ history entries 26-63 report expected `0x0131`, actual `0x0130`.

- [ ] **Step 3: Write minimal implementation**

Capture the native level start before `applyStartPositionAndGroundSnap` overwrites
the sprite with metadata coordinates. Pass that native anchor through bootstrap
placement and use it for the existing `Obj01_Init` offset prefill. Do not read
the expected history snapshot or special-case DEZ.

- [ ] **Step 4: Run test to verify it passes**

Run the focused parameterized test again.

Expected: EHZ, SCZ, WFZ, and DEZ all PASS.

### Task 3: End-to-end verification and documentation

**Files:**
- Modify: `docs/status/trace-frontier-log.md`

**Interfaces:**
- Consumes: focused and fleet test results
- Produces: an auditable frontier entry for both restored traces

- [ ] **Step 1: Run both affected traces**

Run:
`mvn -Dmse=off -Ds2.rom.path='Sonic The Hedgehog 2 (W) (REV01) [!].gen' -Dtest=TestS2Cpz2LevelSelectTraceReplay,TestS2DezEndingLevelSelectTraceReplay test`

Expected: both PASS with zero release-blocking divergences.

- [ ] **Step 2: Run all trace replays**

Run:
`mvn -Dmse=off -Ds1.rom.path=<discovered-s1-rom> -Ds2.rom.path='Sonic The Hedgehog 2 (W) (REV01) [!].gen' -Ds3k.rom.path=<discovered-s3k-rom> -Dtest='*TraceReplay' test`

Expected: no previously green trace regresses.

- [ ] **Step 3: Update the frontier log**

Record the branch, exact commands, CPZ2 and DEZ pass status, and any remaining
fleet frontiers.

- [ ] **Step 4: Review the diff**

Run `git diff --check` and inspect only the files in this plan. Preserve all
pre-existing unrelated worktree changes.
