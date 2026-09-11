# S3K AIZ Intro Parity Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the S3K replay harness with the approved AIZ intro parity gate so the authoritative BK2 replay no longer mis-anchors the second elastic window at `gameplay_start`, then re-run the replay and record the first remaining red point.

**Architecture:** Keep the current S3K replay structure and fix the gate in the narrowest way possible: promote `aiz1_fire_transition_begin` into the detector's required checkpoint contract, change the controller's elastic-window map to `intro_begin -> gameplay_start` and `aiz1_fire_transition_begin -> aiz2_main_gameplay`, and add one strict-mode guard so the replay cannot silently open the second window without the engine emitting the same entry checkpoint. Do not start engine gameplay fixes under this plan; the plan ends once the harness is trustworthy and the post-alignment replay result is captured.

**Tech Stack:** Java 21, JUnit 5, Maven Surefire, existing trace-replay harness (`AbstractTraceReplayTest`, `S3kElasticWindowController`, `S3kReplayCheckpointDetector`), authoritative S3K BK2 fixture under `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`.

---

## File Map

| Path | Action | Responsibility |
| --- | --- | --- |
| `docs/architecture/designs/2026-04-21-s3k-aiz-intro-parity-gate-design.md` | Already updated | Source-of-truth gate contract for this plan. |
| `src/test/java/com/openggf/tests/trace/s3k/S3kReplayCheckpointDetector.java` | Modify | Promote `aiz1_fire_transition_begin` into the required checkpoint order. |
| `src/test/java/com/openggf/tests/trace/s3k/TestS3kReplayCheckpointDetector.java` | Modify | Lock the detector contract with unit tests. |
| `src/test/java/com/openggf/tests/trace/s3k/S3kElasticWindowController.java` | Modify | Change the second elastic window to start at `aiz1_fire_transition_begin`, not `gameplay_start`. |
| `src/test/java/com/openggf/tests/trace/s3k/TestS3kElasticWindowController.java` | Modify | Lock the controller behavior around window exit, strict-gap replay, and second-window entry. |
| `src/test/java/com/openggf/tests/trace/s3k/S3kRequiredCheckpointGuard.java` | Create | Enforce that strict-mode elastic-window entry checkpoints are also emitted by the engine detector on that replay tick. |
| `src/test/java/com/openggf/tests/trace/s3k/TestS3kRequiredCheckpointGuard.java` | Create | Unit tests for the new guard. |
| `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java` | Modify | Wire the new guard into the S3K replay loop before opening strict-mode elastic windows. |
| `docs/architecture/validation/2026-04-21-s3k-aiz-intro-parity-gate.md` | Create | Record the post-alignment replay outcome and first remaining red point. |

## Task 1: Promote `aiz1_fire_transition_begin` In The Detector Contract

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/s3k/TestS3kReplayCheckpointDetector.java`
- Modify: `src/test/java/com/openggf/tests/trace/s3k/S3kReplayCheckpointDetector.java`

- [ ] **Step 1: Write the failing detector tests first**

Replace the current detector assertions that treat `aiz1_fire_transition_begin` as diagnostics-only with these two methods:

```java
@Test
void requiredCheckpointsEmitOnceInFixedOrder() {
    S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

    assertEquals("intro_begin", detector.observe(new S3kCheckpointProbe(
            0, null, null, null, 12, 0, false, false, false, false, false, false, false, false)).name());
    assertEquals("gameplay_start", detector.observe(new S3kCheckpointProbe(
            10, 0, 0, 0, 12, 0, false, false, false, false, false, false, true, false)).name());
    assertEquals("aiz1_fire_transition_begin", detector.observe(new S3kCheckpointProbe(
            1651, 0, 0, 0, 12, 0, false, true, false, false, false, false, true, false)).name());
    assertEquals("aiz2_reload_resume", detector.observe(new S3kCheckpointProbe(
            5610, 0, 1, 0, 12, 5, true, false, false, false, false, false, true, false)).name());

    assertTrue(detector.requiredCheckpointNamesReached().contains("aiz1_fire_transition_begin"));
}

@Test
void fireTransitionBeginIsRequiredCheckpoint() {
    S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

    detector.observe(new S3kCheckpointProbe(
            0, null, null, null, 12, 0, false, false, false, false, false, false, false, false));
    detector.observe(new S3kCheckpointProbe(
            10, 0, 0, 0, 12, 0, false, false, false, false, false, false, true, false));

    TraceEvent.Checkpoint hit = detector.observe(new S3kCheckpointProbe(
            1651, 0, 0, 0, 12, 0, false, true, false, false, false, false, true, false));

    assertNotNull(hit);
    assertEquals("aiz1_fire_transition_begin", hit.name());
    assertTrue(detector.requiredCheckpointNamesReached().contains("aiz1_fire_transition_begin"));
}
```

Run:

```bash
mvn -Dtest=TestS3kReplayCheckpointDetector test
```

Expected: `FAIL` because `REQUIRED_ORDER` still omits `aiz1_fire_transition_begin`.

- [ ] **Step 2: Implement the minimal detector change**

Update the required-order list in `S3kReplayCheckpointDetector.java` to make the fire-transition checkpoint required:

```java
private static final List<String> REQUIRED_ORDER = List.of(
        "intro_begin",
        "gameplay_start",
        "aiz1_fire_transition_begin",
        "aiz2_reload_resume",
        "aiz2_main_gameplay",
        "hcz_handoff_complete");
```

Do not change the predicate itself yet; keep the existing detector rule:

```java
if (!emitted.contains("aiz1_fire_transition_begin")
        && zoneActMatches(probe, 0, 0)
        && probe.eventsFg5()) {
    return emit(probe, "aiz1_fire_transition_begin");
}
```

- [ ] **Step 3: Re-run the detector tests**

Run:

```bash
mvn -Dtest=TestS3kReplayCheckpointDetector test
```

Expected: `PASS`.

- [ ] **Step 4: Commit the detector contract change**

Run:

```bash
git add src/test/java/com/openggf/tests/trace/s3k/S3kReplayCheckpointDetector.java src/test/java/com/openggf/tests/trace/s3k/TestS3kReplayCheckpointDetector.java
git commit -m "test: require s3k fire transition checkpoint"
```

Expected: one small test-focused commit.

## Task 2: Fix The Elastic Window Map And Strict Gap Behavior

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/s3k/TestS3kElasticWindowController.java`
- Modify: `src/test/java/com/openggf/tests/trace/s3k/S3kElasticWindowController.java`

- [ ] **Step 1: Write the failing controller tests first**

Replace the current gameplay-start chaining assertions with these tests:

```java
@Test
void gameplayStartClosesFirstWindowAndResumesStrictReplay() {
    S3kElasticWindowController controller = new S3kElasticWindowController(Map.of(
            "intro_begin", 0,
            "gameplay_start", 12,
            "aiz1_fire_transition_begin", 40,
            "aiz2_main_gameplay", 260));

    controller.onEntryFrameValidated(new TraceEvent.Checkpoint(
            0, "intro_begin", null, null, null, 12, null));
    controller.onEngineCheckpoint(new TraceEvent.Checkpoint(
            12, "gameplay_start", 0, 0, 0, 12, null));

    assertTrue(controller.isStrictComparisonEnabled());
    assertEquals(13, controller.strictTraceIndex());
    assertEquals(13, controller.driveTraceIndex());
}

@Test
void fireTransitionCheckpointOpensSecondWindowAfterStrictGap() {
    S3kElasticWindowController controller = new S3kElasticWindowController(Map.of(
            "intro_begin", 0,
            "gameplay_start", 12,
            "aiz1_fire_transition_begin", 40,
            "aiz2_main_gameplay", 260));

    controller.onEntryFrameValidated(new TraceEvent.Checkpoint(
            40, "aiz1_fire_transition_begin", 0, 0, 0, 12, null));

    assertFalse(controller.isStrictComparisonEnabled());
    assertEquals(40, controller.strictTraceIndex());
    assertEquals(40, controller.driveTraceIndex());
}

@Test
void signpostCheckpointRemainsDiagnosticsOnly() {
    S3kElasticWindowController controller = new S3kElasticWindowController(Map.of(
            "aiz1_fire_transition_begin", 40,
            "aiz2_main_gameplay", 260));

    controller.onEntryFrameValidated(new TraceEvent.Checkpoint(
            40, "aiz1_fire_transition_begin", 0, 0, 0, 12, null));
    controller.onEngineCheckpoint(new TraceEvent.Checkpoint(
            215, "aiz2_signpost_begin", 0, 1, 1, 12, null));

    assertFalse(controller.isStrictComparisonEnabled());
    assertEquals(40, controller.strictTraceIndex());
}
```

Run:

```bash
mvn -Dtest=TestS3kElasticWindowController test
```

Expected: `FAIL` because the controller still chains from `gameplay_start`.

- [ ] **Step 2: Implement the minimal controller fix**

Change the window map in `S3kElasticWindowController.java` to:

```java
private static final Map<String, String> ELASTIC_WINDOWS = Map.of(
        "intro_begin", "gameplay_start",
        "aiz1_fire_transition_begin", "aiz2_main_gameplay");
```

Do not change the close/re-anchor logic. The existing code path under:

```java
if (checkpoint.name().equals(expectedExitName)) {
    String chainedExitName = ELASTIC_WINDOWS.get(checkpoint.name());
    if (chainedExitName != null) {
        ...
    }
    strictTraceIndex = exitTraceFrame + 1;
    driveTraceIndex = exitTraceFrame + 1;
    openEntryName = null;
    expectedExitName = null;
    return;
}
```

already gives the desired behavior once `gameplay_start` is no longer a window entry.

- [ ] **Step 3: Re-run the controller tests**

Run:

```bash
mvn -Dtest=TestS3kElasticWindowController test
```

Expected: `PASS`.

- [ ] **Step 4: Commit the controller change**

Run:

```bash
git add src/test/java/com/openggf/tests/trace/s3k/S3kElasticWindowController.java src/test/java/com/openggf/tests/trace/s3k/TestS3kElasticWindowController.java
git commit -m "test: align s3k elastic window anchors"
```

Expected: one small controller-only commit.

## Task 3: Enforce Strict-Mode Entry Checkpoint Matching In The Replay Loop

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s3k/S3kRequiredCheckpointGuard.java`
- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kRequiredCheckpointGuard.java`
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`

- [ ] **Step 1: Write failing unit tests for the new guard**

Create `TestS3kRequiredCheckpointGuard.java` with these tests:

```java
package com.openggf.tests.trace.s3k;

import com.openggf.tests.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kRequiredCheckpointGuard {

    private final S3kRequiredCheckpointGuard guard = new S3kRequiredCheckpointGuard();

    @Test
    void ignoresNonEntryCheckpoint() {
        assertDoesNotThrow(() -> guard.validateStrictEntry(
                5610,
                new TraceEvent.Checkpoint(5610, "aiz2_reload_resume", 0, 1, 0, 12, null),
                null));
    }

    @Test
    void acceptsMatchingFireTransitionCheckpoint() {
        assertDoesNotThrow(() -> guard.validateStrictEntry(
                1651,
                new TraceEvent.Checkpoint(1651, "aiz1_fire_transition_begin", 0, 0, 0, 12, null),
                new TraceEvent.Checkpoint(1651, "aiz1_fire_transition_begin", 0, 0, 0, 12, null)));
    }

    @Test
    void rejectsMissingFireTransitionCheckpoint() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.validateStrictEntry(
                1651,
                new TraceEvent.Checkpoint(1651, "aiz1_fire_transition_begin", 0, 0, 0, 12, null),
                null));

        assertTrue(ex.getMessage().contains("aiz1_fire_transition_begin"));
        assertTrue(ex.getMessage().contains("1651"));
    }
}
```

Run:

```bash
mvn -Dtest=TestS3kRequiredCheckpointGuard test
```

Expected: `FAIL` because `S3kRequiredCheckpointGuard` does not exist yet.

- [ ] **Step 2: Implement the guard and wire it into the S3K replay loop**

Create `S3kRequiredCheckpointGuard.java` with this implementation:

```java
package com.openggf.tests.trace.s3k;

import com.openggf.tests.trace.TraceEvent;

import java.util.Set;

public final class S3kRequiredCheckpointGuard {

    private static final Set<String> STRICT_ENTRY_CHECKPOINTS =
            Set.of("intro_begin", "aiz1_fire_transition_begin");

    public void validateStrictEntry(
            int traceFrame,
            TraceEvent.Checkpoint traceCheckpoint,
            TraceEvent.Checkpoint engineCheckpoint) {
        if (traceCheckpoint == null || !STRICT_ENTRY_CHECKPOINTS.contains(traceCheckpoint.name())) {
            return;
        }
        if (engineCheckpoint == null) {
            throw new IllegalStateException(
                    "Required engine checkpoint missing at trace frame "
                            + traceFrame + ": expected " + traceCheckpoint.name());
        }
        if (!traceCheckpoint.name().equals(engineCheckpoint.name())) {
            throw new IllegalStateException(
                    "Required engine checkpoint mismatch at trace frame "
                            + traceFrame + ": expected " + traceCheckpoint.name()
                            + " but saw " + engineCheckpoint.name());
        }
    }
}
```

Then wire it into `AbstractTraceReplayTest.replayS3kTrace(...)` by creating the guard next to the detector/controller:

```java
S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
S3kElasticWindowController controller =
        new S3kElasticWindowController(loadCheckpointFrames(trace));
S3kRequiredCheckpointGuard checkpointGuard = new S3kRequiredCheckpointGuard();
```

and validating strict-mode entry checkpoints before opening a trace-side elastic window:

```java
TraceEvent.Checkpoint traceCheckpoint = trace.latestCheckpointAtOrBefore(strictTraceIndex);
if (traceCheckpoint != null && traceCheckpoint.frame() == strictTraceIndex) {
    checkpointGuard.validateStrictEntry(strictTraceIndex, traceCheckpoint, engineCheckpoint);
    controller.onEntryFrameValidated(traceCheckpoint);
}
```

This is the minimal runtime change that makes `aiz1_fire_transition_begin` a real replay-side gate rather than a unit-test-only label.

- [ ] **Step 3: Re-run the focused guard and harness-unit suite**

Run:

```bash
mvn -Dtest=TestS3kReplayCheckpointDetector,TestS3kElasticWindowController,TestS3kRequiredCheckpointGuard test
```

Expected: `PASS`.

- [ ] **Step 4: Commit the replay-loop guard**

Run:

```bash
git add src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java src/test/java/com/openggf/tests/trace/s3k/S3kRequiredCheckpointGuard.java src/test/java/com/openggf/tests/trace/s3k/TestS3kRequiredCheckpointGuard.java
git commit -m "test: guard s3k strict checkpoint entries"
```

Expected: one small replay-loop commit.

## Task 4: Re-Run The Authoritative Replay And Record The First Remaining Red Point

**Files:**
- Create: `docs/architecture/validation/2026-04-21-s3k-aiz-intro-parity-gate.md`

- [ ] **Step 1: Run the aligned S3K replay gate**

Run:

```powershell
mvn --% -Dmse=off -Dtest=com.openggf.tests.trace.s3k.TestS3kReplayCheckpointDetector,com.openggf.tests.trace.s3k.TestS3kElasticWindowController,com.openggf.tests.trace.s3k.TestS3kRequiredCheckpointGuard,com.openggf.tests.trace.s3k.TestS3kAizTraceReplay -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

Expected:

- the three focused unit-test classes pass
- `TestS3kAizTraceReplay` does **not** error with `Elastic window drift budget exhausted for gameplay_start`
- the replay either passes entirely or fails later with a new first-red point at or after `gameplay_start`

- [ ] **Step 2: Write the validation note from the actual replay output**

Run this PowerShell exactly after Step 1 so the note captures the real result of the aligned harness:

```powershell
$command = 'mvn --% -Dmse=off -Dtest=com.openggf.tests.trace.s3k.TestS3kReplayCheckpointDetector,com.openggf.tests.trace.s3k.TestS3kElasticWindowController,com.openggf.tests.trace.s3k.TestS3kRequiredCheckpointGuard,com.openggf.tests.trace.s3k.TestS3kAizTraceReplay -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" test'
$surefire = 'target/surefire-reports/TEST-com.openggf.tests.trace.s3k.TestS3kAizTraceReplay.xml'
$traceJson = 'target/trace-reports/s3k_aiz1_report.json'
$traceContext = 'target/trace-reports/s3k_aiz1_context.txt'
$xml = if (Test-Path $surefire) { Get-Content $surefire } else { @() }
$interesting = $xml | Select-String -Pattern 'All frames match trace|First error:|Elastic window drift budget exhausted|Required engine checkpoint|AssertionFailedError|IllegalStateException'
$firstInteresting = if ($interesting) { $interesting[0].Line.Trim() } else { 'No matching summary line found in surefire XML.' }
$gameplayBudgetStillPresent = [bool]($xml | Select-String -SimpleMatch 'Elastic window drift budget exhausted for gameplay_start')
$traceJsonState = if (Test-Path $traceJson) { 'present' } else { 'missing' }
$traceContextState = if (Test-Path $traceContext) { 'present' } else { 'missing' }
@"
# S3K AIZ Intro Parity Gate Validation

Captured on `2026-04-21` in worktree `feature/ai-s2-s3k-trace-recorder-v3`.

## Replay Command

```powershell
$command
```

## Observed Output

- Surefire report: `$surefire`
- Trace JSON report: `$traceJsonState` (`$traceJson`)
- Trace context report: `$traceContextState` (`$traceContext`)
- First notable replay line: `$firstInteresting`
- `Elastic window drift budget exhausted for gameplay_start` still present: `$gameplayBudgetStillPresent`

## Interpretation

- If the budget error above is `False`, the harness-alignment gate succeeded.
- If the replay is still red, investigate only the earliest failing region named by the new output.
"@ | Set-Content 'docs/architecture/validation/2026-04-21-s3k-aiz-intro-parity-gate.md'
```

- [ ] **Step 3: Inspect the recorded validation result and stop at the gate**

Run:

```powershell
Get-Content 'docs/architecture/validation/2026-04-21-s3k-aiz-intro-parity-gate.md'
```

Expected:

- the note shows whether the old `gameplay_start` budget error is gone
- if the replay is still red, the note and generated report paths identify the new earliest failing region
- execution stops here for this plan; do **not** start engine intro fixes under the same implementation pass

- [ ] **Step 4: Commit the validation note**

Run:

```bash
git add docs/architecture/validation/2026-04-21-s3k-aiz-intro-parity-gate.md
git commit -m "docs: record s3k aiz parity gate replay result"
```

Expected: one docs-only commit containing the post-alignment replay outcome.
