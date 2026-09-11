# S2 WFZ Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Advance Sonic 2 Wing Fortress Zone toward ROM parity using trace replay and focused disassembly-backed tests.

**Architecture:** Keep WFZ event state in `Sonic2WFZEvents`, integrate missing serialization through `Sonic2LevelEventManager`, and fix object/boss/cutscene behavior only when trace or focused tests prove a ROM mismatch. Trace data remains diagnostic-only and never writes into engine state.

**Tech Stack:** Java 17, Maven, JUnit 5, existing OpenGGF Sonic 2 object/event/trace frameworks.

---

## File Structure

- `src/main/java/com/openggf/game/sonic2/events/Sonic2WFZEvents.java`: WFZ dual-dispatch event logic and test-visible state.
- `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java`: rewind capture/restore of WFZ event extras.
- `src/test/java/com/openggf/game/sonic2/TestTodo12_WFZEventSpecs.java`: focused tests for WFZ events.
- `src/test/java/com/openggf/tests/trace/s2/TestS2WfzLevelSelectTraceReplay.java`: authoritative WFZ route parity test.
- `docs/status/trace-frontier-log.md`: trace frontier evidence.

### Task 1: Establish Live Baseline

**Files:**
- Read: `target/trace-reports/s2_wfz1_report.json`
- Read: `target/trace-reports/s2_wfz1_context.txt`

- [ ] **Step 1: Run focused non-trace WFZ tests**

Run:
```powershell
mvn "-Dmse=off" "-Dtest=com.openggf.game.sonic2.TestTodo12_WFZEventSpecs,com.openggf.tests.TestWFZBoss,com.openggf.game.sonic2.objects.TestTornadoObjectInstance" test
```

Expected: either PASS, or concrete failures to fix before trace investigation.

- [ ] **Step 2: Run the WFZ trace replay**

Run:
```powershell
mvn "-Dmse=off" "-Dsurefire.forkCount=1" "-Dtest=com.openggf.tests.trace.s2.TestS2WfzLevelSelectTraceReplay#replayMatchesTrace" test "-DfailIfNoTests=false"
```

Expected: current known failure at or near frame 4719 unless recent local changes altered the frontier.

- [ ] **Step 3: Inspect trace report**

Run:
```powershell
Get-Content -Raw target/trace-reports/s2_wfz1_context.txt | Select-String -Pattern "frame 4719|ROM:|ENG:" -Context 8,12
```

Expected: first-error context showing the ROM/engine player/object state around the divergence.

### Task 2: Make WFZ Event State Testable and Rewindable

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2WFZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java`
- Modify: `src/test/java/com/openggf/game/sonic2/TestTodo12_WFZEventSpecs.java`

- [ ] **Step 1: Add failing tests for WFZ secondary routine and rewind state**

Add tests that require:
- secondary routine 0 advances to 2 at camera `($2880,$400)`;
- secondary routine 2 advances to 4 at camera Y `$500`;
- all WFZ BG offsets/speed and secondary routine can be captured and restored through `Sonic2LevelEventManager`.

Run the focused test and verify RED.

- [ ] **Step 2: Add minimal accessors and serialization**

Expose package/public test accessors on `Sonic2WFZEvents` for `wfzSubRoutine`, BG position offsets, BG speed, and restore hooks. Extend `Sonic2LevelEventManager.captureExtra()` and `restoreExtra()` by appending WFZ extras after existing fields while preserving backward compatibility for older snapshot sizes.

- [ ] **Step 3: Verify GREEN**

Run:
```powershell
mvn "-Dmse=off" "-Dtest=com.openggf.game.sonic2.TestTodo12_WFZEventSpecs" test
```

Expected: PASS.

### Task 3: Replace WFZ Placeholder Event Actions with ROM-Backed Calls

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2WFZEvents.java`
- Modify tests as needed under `src/test/java/com/openggf/game/sonic2/`

- [ ] **Step 1: Locate existing PLC/control-lock APIs**

Run:
```powershell
rg -n "loadPlc|LoadPLC|Control_Locked|setControl|lockControl|request.*PLC|PLC_" src/main/java src/test/java
```

Expected: existing engine API or a clear absence requiring a small general-purpose extension.

- [ ] **Step 2: Write failing tests**

Add tests for observable effects, preferring existing service/test doubles if available:
- boss PLC request when secondary routine 0 triggers;
- control lock and Tornado PLC request when secondary routine 2 triggers.

- [ ] **Step 3: Implement minimal ROM behavior**

Implement the missing calls with comments citing `docs/s2disasm/s2.asm:20669-20682`. If the engine lacks a PLC request API, add one at the owning service boundary rather than a WFZ-local fake.

- [ ] **Step 4: Verify focused tests**

Run:
```powershell
mvn "-Dmse=off" "-Dtest=com.openggf.game.sonic2.TestTodo12_WFZEventSpecs" test
```

Expected: PASS.

### Task 4: Diagnose and Fix the First WFZ Trace Frontier

**Files:**
- Modify only the owning object/event/physics file identified by trace evidence.
- Test: existing or new focused test for the identified ROM behavior.

- [ ] **Step 1: Read first-error context**

Use `target/trace-reports/s2_wfz1_report.json`, `target/trace-reports/s2_wfz1_context.txt`, and trace aux events to identify the object/contact/event owner.

- [ ] **Step 2: Read the matching ROM routine fully**

Use `docs/s2disasm/s2.asm` and `rg` labels for the owning object. Note constants, timer decrement style, solid-object call placement, and centre-coordinate usage.

- [ ] **Step 3: Add a focused failing test**

The test must reproduce the smallest behavior mismatch, not the whole trace.

- [ ] **Step 4: Implement one minimal fix**

Use existing utilities such as `SubpixelMotion`, `ObjectControlState`, `ObjectPlayerQuery`, `ObjectLifetimeOps`, or solid/touch profiles where they match the ROM behavior. Do not branch on trace route or frame.

- [ ] **Step 5: Verify target trace**

Run:
```powershell
mvn "-Dmse=off" "-Dsurefire.forkCount=1" "-Dtest=com.openggf.tests.trace.s2.TestS2WfzLevelSelectTraceReplay#replayMatchesTrace" test "-DfailIfNoTests=false"
```

Expected: PASS or a later first-error frame / lower relevant error count without new early divergence.

### Task 5: Regression and Documentation

**Files:**
- Modify: `docs/status/trace-frontier-log.md`

- [ ] **Step 1: Run regression guard**

Run:
```powershell
mvn "-Dmse=off" "-Dsurefire.forkCount=1" "-Dtest=com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay#replayMatchesTrace,com.openggf.tests.trace.s1.TestS1Mz1TraceReplay#replayMatchesTrace,com.openggf.tests.trace.s2.TestS2Ehz1TraceReplay#replayMatchesTrace,com.openggf.tests.trace.s2.TestS2SczLevelSelectTraceReplay#replayMatchesTrace" test "-DfailIfNoTests=false"
```

Expected: PASS.

- [ ] **Step 2: Update trace frontier log**

Record command output, worktree context, WFZ result, and regression guard result in `docs/status/trace-frontier-log.md`.

- [ ] **Step 3: Final verification**

Run:
```powershell
mvn "-Dmse=off" "-Dtest=com.openggf.game.sonic2.TestTodo12_WFZEventSpecs,com.openggf.tests.TestWFZBoss,com.openggf.game.sonic2.objects.TestTornadoObjectInstance" test
```

Expected: PASS.

## Self-Review

Spec coverage: tasks cover current evidence gathering, event runtime gaps, trace-frontier diagnosis, regression verification, and documentation. The plan does not promise all WFZ parity in one code pass; it stops at a proven frontier movement or green trace.

Placeholder scan: no placeholder implementation steps remain; each task has concrete commands and expected outcomes.

Type consistency: file and class names match the current repository inventory.
