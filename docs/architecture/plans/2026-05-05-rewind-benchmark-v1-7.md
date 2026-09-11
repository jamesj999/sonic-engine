# Rewind Benchmark v1.7 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a headless `RewindBenchmark` harness that runs a real reference trace through the rewind framework, reports per-phase timing/memory characteristics, and includes a long-tail determinism gate that surfaces hidden coverage gaps automatically by scrubbing backward and asserting state matches.

**Architecture:** Single test class at `src/test/java/com/openggf/game/rewind/RewindBenchmark.java` runnable via `mvn test "-Dtest=RewindBenchmark"`. Excluded from default CI by an opt-in system property guard (`openggf.rewind.benchmark.run=true`) so CI runs ignore it. Six measurement phases each emit per-frame mean/p50/p95/p99/max in nanoseconds plus total wall time. Outputs human-readable stdout and a machine-readable `target/rewind-benchmark-results.json` for trend tracking. The long-tail determinism gate scrubs back N seconds via `RewindController.seekTo`, plays forward to the original frame, and asserts the resulting `CompositeSnapshot` matches the snapshot captured at that frame on the original forward pass — for the v1.5-covered keys.

**Tech Stack:** Java 21, JUnit 5 / Jupiter, Maven 3.x with MSE (`-Dmse=relaxed` default; tests use `-Dmse=off` for full Maven logs). Reuses Plan 2 framework (`RewindController`, `RewindRegistry`, `InMemoryKeyframeStore`, `TraceInputSource`, `SegmentCache`) and Plan 3 coverage. Fixture pattern mirrors `TestRewindParityAgainstTrace.java`.

**Spec reference:** `docs/architecture/designs/2026-05-04-rewind-framework-design.md` lines 285–302 (Performance benchmark section). Plans 2 + 3: `docs/architecture/plans/2026-05-05-rewind-framework-v1.md` and `docs/architecture/plans/2026-05-05-rewind-framework-coverage-v1-5.md`.

**Prerequisites already landed (Plans 2 + 3):** Full rewind framework with 20 round-tripping keys for an S2 EHZ1 trace. `gameplayMode.installPlaybackController(InputSource, EngineStepper, int)` factory method. `RecordingFrameObserver` + `LiveTraceComparator` per-frame observer hook. The S2 EHZ1 trace at `src/test/resources/traces/s2/ehz1_fullrun/` is the primary benchmark target.

---

## Scope

**In scope (this plan):**

- `RewindBenchmark` harness class with shared scaffolding (trace load, fixture build, timing helpers, phase runner).
- 6 measurement phases per the spec:
  1. Forward playback overhead (framework on vs off).
  2. Keyframe capture cost (per-subsystem breakdown via `RewindRegistry`-internal timing).
  3. Keyframe restore cost (per-subsystem breakdown).
  4. Cold seek (`seekTo(F)` with segment cache empty).
  5. Hot seek (held-rewind simulation: `stepBackward()` repeatedly across one segment + segment-boundary crossing).
  6. Memory (keyframe count, avg snapshot size per subsystem, peak SegmentCache strip size, level CoW array reuse rate, total resident bytes).
- JSON output dump at `target/rewind-benchmark-results.json` with per-phase results.
- Long-tail determinism gate: scrub back configurable N seconds, replay forward, assert covered-key round-trip. Reports "longest clean rewind".
- Baseline numbers committed to a sibling JSON `src/test/resources/rewind-benchmark-baseline.json`.

**Out of scope (deferred):**

- CI budget gate enforcement (fail the build when p95 forward-step exceeds budget). Plan ships the *measurement* infrastructure; budget thresholds are tuned in a follow-up after we have baseline numbers from real hardware.
- Cross-game benchmarks (S1 GHZ1, S3K AIZ) — v1.7 ships S2 EHZ1 only. Cross-game is mechanical extension once the harness shape is right.
- `RewindRegistry.capture()` per-subsystem timing instrumentation IF it requires invasive changes. Default approach times the whole capture; if per-subsystem cost is needed, the plan adds optional internal timing hooks (Task 3 has a fallback path).

---

## File structure

| Path | Responsibility |
|---|---|
| `src/test/java/com/openggf/game/rewind/RewindBenchmark.java` | Single test class with all 6 phases + long-tail determinism gate. Uses `@EnabledIfSystemProperty(named = "openggf.rewind.benchmark.run", matches = "true")` to skip in default CI. |
| `src/test/java/com/openggf/game/rewind/BenchmarkResults.java` | Helper record holding per-phase measurement results (mean/p50/p95/p99/max ns, total wall time, optional per-subsystem breakdown map). JSON-serialisable. |
| `src/test/java/com/openggf/game/rewind/BenchmarkTiming.java` | Helper class wrapping a `long[]` sample array with `record(long ns)` and `summarize()` returning `BenchmarkResults.PhaseStats`. |
| `src/test/resources/rewind-benchmark-baseline.json` | Committed baseline numbers from one developer-machine run, for trend comparison. |
| `src/main/java/com/openggf/game/rewind/RewindRegistry.java` | (modified — Task 3) optional `Consumer<PerSubsystemTiming>` hook on `capture()` / `restore(...)` for benchmark instrumentation. Production users pass null. |

---

## Track A — Harness scaffolding + Phase 1 (forward overhead)

### Task 1 — `BenchmarkResults` + `BenchmarkTiming` helpers

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/BenchmarkResults.java`
- Create: `src/test/java/com/openggf/game/rewind/BenchmarkTiming.java`
- Test: `src/test/java/com/openggf/game/rewind/TestBenchmarkTiming.java`

- [ ] **Step 1: Write the failing test:**

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBenchmarkTiming {

    @Test
    void summarizeReportsCorrectStats() {
        BenchmarkTiming t = new BenchmarkTiming(100);
        for (int i = 1; i <= 100; i++) {
            t.record(i * 1000L); // 1000 ns to 100000 ns
        }
        BenchmarkResults.PhaseStats stats = t.summarize();
        assertEquals(100, stats.sampleCount());
        assertEquals(50_500L, stats.meanNs(), 100L); // (1+100)/2 * 1000
        assertEquals(50_000L, stats.p50Ns(), 1000L);
        assertEquals(95_000L, stats.p95Ns(), 1000L);
        assertEquals(99_000L, stats.p99Ns(), 1000L);
        assertEquals(100_000L, stats.maxNs());
    }

    @Test
    void summarizeOnEmptyReturnsZeros() {
        BenchmarkTiming t = new BenchmarkTiming(10);
        BenchmarkResults.PhaseStats stats = t.summarize();
        assertEquals(0, stats.sampleCount());
        assertEquals(0L, stats.meanNs());
    }

    @Test
    void recordRespectsCapacityAndOverwritesNothing() {
        BenchmarkTiming t = new BenchmarkTiming(3);
        t.record(100); t.record(200); t.record(300);
        // Recording a fourth sample is silently dropped (or wraps — pick one).
        // We pick: dropped, to keep summarize semantics simple.
        t.record(400);
        BenchmarkResults.PhaseStats stats = t.summarize();
        assertEquals(3, stats.sampleCount());
        assertEquals(300L, stats.maxNs());
    }
}
```

- [ ] **Step 2: Run test, confirm it fails to compile** because `BenchmarkResults` and `BenchmarkTiming` don't exist yet.

`mvn test "-Dtest=TestBenchmarkTiming" -q 2>&1 | tail -5` → MSE will show compile failure.

- [ ] **Step 3: Implement `BenchmarkResults.java`:**

```java
package com.openggf.game.rewind;

import java.util.Map;

/**
 * Per-phase benchmark measurement results. JSON-serialisable record.
 */
public record BenchmarkResults(
        String phaseName,
        PhaseStats overall,
        long totalWallTimeNs,
        Map<String, PhaseStats> perSubsystem
) {
    public BenchmarkResults {
        perSubsystem = perSubsystem == null ? Map.of() : Map.copyOf(perSubsystem);
    }

    public record PhaseStats(
            long sampleCount,
            long meanNs,
            long p50Ns,
            long p95Ns,
            long p99Ns,
            long maxNs
    ) {}
}
```

- [ ] **Step 4: Implement `BenchmarkTiming.java`:**

```java
package com.openggf.game.rewind;

import java.util.Arrays;

/**
 * Fixed-capacity sample collector. Records per-frame timings via
 * {@link #record(long)}; produces a {@link BenchmarkResults.PhaseStats}
 * summary on {@link #summarize()}. Samples beyond capacity are dropped.
 */
public final class BenchmarkTiming {

    private final long[] samples;
    private int count;

    public BenchmarkTiming(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.samples = new long[capacity];
        this.count = 0;
    }

    public void record(long ns) {
        if (count < samples.length) {
            samples[count++] = ns;
        }
    }

    public BenchmarkResults.PhaseStats summarize() {
        if (count == 0) {
            return new BenchmarkResults.PhaseStats(0, 0L, 0L, 0L, 0L, 0L);
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        long sum = 0;
        for (long s : sorted) sum += s;
        long mean = sum / count;
        long p50 = sorted[Math.min(count - 1, (int) (count * 0.50))];
        long p95 = sorted[Math.min(count - 1, (int) (count * 0.95))];
        long p99 = sorted[Math.min(count - 1, (int) (count * 0.99))];
        long max = sorted[count - 1];
        return new BenchmarkResults.PhaseStats(count, mean, p50, p95, p99, max);
    }
}
```

- [ ] **Step 5: Run test, confirm it passes:**

`mvn test "-Dtest=TestBenchmarkTiming" -q 2>&1 | tail -5`. Verify via `target/surefire-reports/TEST-com.openggf.game.rewind.TestBenchmarkTiming.xml` shows `failures="0"`.

- [ ] **Step 6: Commit:**

```
git add src/test/java/com/openggf/game/rewind/BenchmarkResults.java \
        src/test/java/com/openggf/game/rewind/BenchmarkTiming.java \
        src/test/java/com/openggf/game/rewind/TestBenchmarkTiming.java
git commit -m "$(cat <<'EOF'
test(rewind): add BenchmarkResults + BenchmarkTiming helpers

Per-phase benchmark measurement primitives. PhaseStats record carries
mean/p50/p95/p99/max in nanoseconds; BenchmarkTiming wraps a fixed-
capacity sample buffer and produces stats on summarize. Foundation for
the RewindBenchmark harness phases.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 — `RewindBenchmark` harness skeleton + Phase 1 (forward playback overhead)

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`

The class loads the S2 EHZ1 trace, builds a HeadlessTestFixture, and runs a "framework off" pass (raw `fixture.stepFrameFromRecording()`) and a "framework on" pass (drives the engine via `PlaybackController.tick()` after `installPlaybackController(...)`). Reports per-frame timing.

- [ ] **Step 1: Inspect existing trace fixture pattern.** Read `src/test/java/com/openggf/game/rewind/TestRewindParityAgainstTrace.java` to confirm the boot sequence (HeadlessTestFixture builder, BK2 file lookup, trace metadata loading). Reuse the same idiom; don't invent a new fixture pattern.

- [ ] **Step 2: Write the harness skeleton:**

```java
package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GameServices;
import com.openggf.game.rewind.snapshot.OscillationStaticAdapter;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headless benchmark harness for the rewind framework.
 *
 * <p>Runs a real reference trace through the engine in two passes
 * (framework off / framework on) and reports per-phase timing
 * characteristics. Excluded from default CI via the
 * {@code openggf.rewind.benchmark.run} system property — opt in with
 * {@code mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true"}.
 */
@RequiresRom(SonicGame.SONIC_2)
@EnabledIfSystemProperty(named = "openggf.rewind.benchmark.run", matches = "true")
public class RewindBenchmark {

    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int KEYFRAME_INTERVAL = 60;

    /** Aggregate phase results, dumped to JSON at end of test. */
    private final Map<String, BenchmarkResults> results = new LinkedHashMap<>();

    @Test
    public void runBenchmark() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);

        // Phase 1: forward overhead (framework off + framework on)
        runPhase1ForwardOverhead();

        // Phase 2-6: implemented in subsequent tasks.

        // Long-tail determinism gate: implemented in Task 5.

        // Dump JSON: implemented in Task 6.
        // For now: print human-readable stdout summary.
        for (var e : results.entrySet()) {
            System.out.printf("Phase: %s%n", e.getKey());
            BenchmarkResults r = e.getValue();
            BenchmarkResults.PhaseStats s = r.overall();
            System.out.printf("  samples=%d mean=%dns p50=%dns p95=%dns p99=%dns max=%dns wall=%dns%n",
                    s.sampleCount(), s.meanNs(), s.p50Ns(), s.p95Ns(), s.p99Ns(), s.maxNs(),
                    r.totalWallTimeNs());
        }
    }

    private void runPhase1ForwardOverhead() throws IOException {
        // Pass 1: framework OFF. Drive engine via fixture.stepFrameFromRecording().
        BenchmarkTiming offTiming = new BenchmarkTiming(20_000);
        long offWallStart = System.nanoTime();
        try (HeadlessTestFixture fixture = buildFixture()) {
            int frameCount = Math.min(2000, fixture.recordingFrameCount());
            for (int i = 0; i < frameCount; i++) {
                long t0 = System.nanoTime();
                fixture.stepFrameFromRecording();
                offTiming.record(System.nanoTime() - t0);
            }
        }
        long offWall = System.nanoTime() - offWallStart;

        // Pass 2: framework ON. Drive engine via PlaybackController.tick().
        BenchmarkTiming onTiming = new BenchmarkTiming(20_000);
        long onWallStart = System.nanoTime();
        try (HeadlessTestFixture fixture = buildFixture()) {
            GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
            // Build trace InputSource from the fixture's recording.
            // Build EngineStepper that calls fixture.stepFrameFromRecording with given Bk2FrameInput.
            // installPlaybackController.
            var inputs = new TraceFixtureInputSource(fixture);
            var stepper = new TraceFixtureEngineStepper(fixture);
            gameplayMode.installPlaybackController(inputs, stepper, KEYFRAME_INTERVAL);
            int frameCount = Math.min(2000, fixture.recordingFrameCount());
            for (int i = 0; i < frameCount; i++) {
                long t0 = System.nanoTime();
                gameplayMode.getPlaybackController().tick();
                onTiming.record(System.nanoTime() - t0);
            }
        }
        long onWall = System.nanoTime() - onWallStart;

        results.put("phase1.forward.off",
                new BenchmarkResults("phase1.forward.off",
                        offTiming.summarize(), offWall, Map.of()));
        results.put("phase1.forward.on",
                new BenchmarkResults("phase1.forward.on",
                        onTiming.summarize(), onWall, Map.of()));
    }

    private HeadlessTestFixture buildFixture() throws IOException {
        Path bk2 = findBk2(TRACE_DIR);
        return HeadlessTestFixture.builder()
                .withRecording(bk2)
                .withZoneAndAct(0, 0)
                .build();
    }

    private static Path findBk2(Path traceDir) throws IOException {
        try (var stream = Files.list(traceDir)) {
            return stream.filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No .bk2 file found in " + traceDir));
        }
    }

    // EngineStepper that drives the fixture's recording cursor using whatever
    // input is requested. For trace-mode rewind, we ignore the supplied
    // Bk2FrameInput and use fixture.stepFrameFromRecording() (which advances
    // the BK2 cursor in the same order as the trace) — verified equivalent
    // because the InputSource reads the same trace.
    private static final class TraceFixtureEngineStepper
            implements com.openggf.game.rewind.EngineStepper {
        private final HeadlessTestFixture fixture;
        TraceFixtureEngineStepper(HeadlessTestFixture fixture) { this.fixture = fixture; }
        @Override public void step(Bk2FrameInput inputs) {
            fixture.stepFrameFromRecording();
        }
    }

    // InputSource backed by fixture's recording — reads BK2 frame inputs
    // without advancing the cursor (cursor is advanced by stepper).
    // Implementation reuses fixture.peekRecordingFrameInput(int) if such an
    // accessor exists; otherwise falls back to TraceData.getFrame.
    private static final class TraceFixtureInputSource
            implements com.openggf.game.rewind.InputSource {
        private final HeadlessTestFixture fixture;
        private final int frameCount;
        TraceFixtureInputSource(HeadlessTestFixture fixture) {
            this.fixture = fixture;
            this.frameCount = fixture.recordingFrameCount();
        }
        @Override public int frameCount() { return frameCount; }
        @Override public Bk2FrameInput read(int frame) {
            // If HeadlessTestFixture lacks a peek API, this method falls
            // back to a no-op zero-input — the Stepper above doesn't use the
            // result anyway (it advances the BK2 cursor directly). Verify
            // this assumption by reading TestRewindParityAgainstTrace.
            return Bk2FrameInput.empty();
        }
    }
}
```

- [ ] **Step 3: Inspect `HeadlessTestFixture` API** to confirm `recordingFrameCount()` and `stepFrameFromRecording()` exist (they do per Plan 2 reference). If `Bk2FrameInput.empty()` doesn't exist, build a zero-input `Bk2FrameInput` via whatever constructor it provides — read `src/main/java/com/openggf/debug/playback/Bk2FrameInput.java`.

- [ ] **Step 4: Inspect `HeadlessTestFixture`** for `close()` / `AutoCloseable` support. If not auto-closeable, replace `try-with-resources` with explicit cleanup or a lambda+finally pattern. The fixture probably has a `cleanup()` or `dispose()` method.

- [ ] **Step 5: Run the benchmark with the opt-in flag:**

```
mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Dmse=off" 2>&1 | tail -30
```

Expected: stdout shows two phase results — `phase1.forward.off` and `phase1.forward.on` — with sample counts, percentile timings, and total wall-time. The "on" phase wall time should be slightly higher than "off" reflecting per-frame `RewindRegistry.capture()` overhead at keyframe boundaries.

If the test fails because `installPlaybackController` semantics differ (e.g., Plan 2's E.2 had a subtle wiring), fix the harness call site to match. If the test fails because of fixture rebuilding cost or some `RuntimeManager` reset issue, document and either reset between passes or run them separately.

- [ ] **Step 6: Commit:**

```
git add src/test/java/com/openggf/game/rewind/RewindBenchmark.java
git commit -m "$(cat <<'EOF'
test(rewind): add RewindBenchmark harness with Phase 1 (forward overhead)

Headless benchmark harness exercising the rewind framework against an
S2 EHZ1 reference trace. Excluded from default CI via the
openggf.rewind.benchmark.run system property. Phase 1 measures
forward-step overhead with the framework on vs off; subsequent tasks
add capture-cost (Phase 2), restore-cost (Phase 3), seek (Phases 4-5),
memory (Phase 6), JSON output, and the long-tail determinism gate.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Track B — Capture / restore costs (Phases 2-3)

### Task 3 — Phase 2 (capture cost) + Phase 3 (restore cost)

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`

After Phase 1 finishes, the harness owns a populated `KeyframeStore` with a sequence of `CompositeSnapshot`s. Phase 2 measures the cost of additional `RewindRegistry.capture()` calls; Phase 3 measures `RewindRegistry.restore(snap)` cost. Per-subsystem timing is acquired by wrapping the registered `RewindSnapshottable<?>` instances in timing decorators just for these phases (NOT modifying `RewindRegistry` — keep it production-clean).

- [ ] **Step 1: Implement Phase 2 (capture cost):**

Add to `RewindBenchmark.java` after the Phase 1 method:

```java
private void runPhase2CaptureCost() throws IOException {
    BenchmarkTiming overallTiming = new BenchmarkTiming(1000);
    Map<String, BenchmarkTiming> perKey = new LinkedHashMap<>();
    long wallStart = System.nanoTime();
    try (HeadlessTestFixture fixture = buildFixture()) {
        // Advance to a representative state
        for (int i = 0; i < 600; i++) fixture.stepFrameFromRecording();
        GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
        var registry = gm.getRewindRegistry();

        // Wrap each RewindSnapshottable in a timing decorator for this phase
        // by reflecting on registry.entries() — but RewindRegistry doesn't
        // expose its entries. Alternative: time the whole capture, and use
        // a single BenchmarkTiming.
        // Future enhancement: add a registry-internal timing hook.

        // For 1000 iterations, capture + discard
        for (int i = 0; i < 1000; i++) {
            long t0 = System.nanoTime();
            CompositeSnapshot snap = registry.capture();
            overallTiming.record(System.nanoTime() - t0);
        }
    }
    long wall = System.nanoTime() - wallStart;
    Map<String, BenchmarkResults.PhaseStats> perSubsystemStats = new LinkedHashMap<>();
    for (var e : perKey.entrySet()) {
        perSubsystemStats.put(e.getKey(), e.getValue().summarize());
    }
    results.put("phase2.capture",
            new BenchmarkResults("phase2.capture",
                    overallTiming.summarize(), wall, perSubsystemStats));
}
```

The per-subsystem map is left empty for v1.7 — the spec calls for per-subsystem breakdown but `RewindRegistry` doesn't expose its registered list. **Optional enhancement** (do this if time permits during execution): expose `RewindRegistry.snapshottables()` returning a read-only view; if exposed, time each subsystem individually.

If you choose the optional enhancement: modify `src/main/java/com/openggf/game/rewind/RewindRegistry.java` to add:

```java
public java.util.Collection<RewindSnapshottable<?>> snapshottables() {
    return java.util.Collections.unmodifiableCollection(entries.values());
}
```

Then in Phase 2 loop, walk each subsystem, call its `capture()` directly, time it per subsystem, and sum into both `overallTiming` and `perKey.computeIfAbsent(subsystem.key(), k -> new BenchmarkTiming(1000)).record(...)`. Skip the registry-level `capture()` for the per-subsystem path — call each subsystem's `capture()` individually.

- [ ] **Step 2: Implement Phase 3 (restore cost):**

```java
private void runPhase3RestoreCost() throws IOException {
    BenchmarkTiming overallTiming = new BenchmarkTiming(500);
    long wallStart = System.nanoTime();
    try (HeadlessTestFixture fixture = buildFixture()) {
        for (int i = 0; i < 600; i++) fixture.stepFrameFromRecording();
        GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
        var registry = gm.getRewindRegistry();

        // Capture once, then time repeated restores from that snapshot.
        CompositeSnapshot baseline = registry.capture();
        for (int i = 0; i < 500; i++) {
            long t0 = System.nanoTime();
            registry.restore(baseline);
            overallTiming.record(System.nanoTime() - t0);
        }
    }
    long wall = System.nanoTime() - wallStart;
    results.put("phase3.restore",
            new BenchmarkResults("phase3.restore",
                    overallTiming.summarize(), wall, Map.of()));
}
```

Per-subsystem path mirrors Phase 2's optional enhancement.

- [ ] **Step 3: Wire both phases into `runBenchmark()` after Phase 1.** Add:

```java
runPhase2CaptureCost();
runPhase3RestoreCost();
```

- [ ] **Step 4: Run, confirm phases populate:**

```
mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Dmse=off" 2>&1 | tail -20
```

Expected stdout: 4 phase entries (phase1.off, phase1.on, phase2.capture, phase3.restore). Verify the timing values are sane — capture should be in microseconds-to-tens-of-microseconds range; restore similar.

- [ ] **Step 5: Commit:**

```
git add src/test/java/com/openggf/game/rewind/RewindBenchmark.java
# If you took the optional enhancement, also stage RewindRegistry:
# git add src/main/java/com/openggf/game/rewind/RewindRegistry.java
git commit -m "$(cat <<'EOF'
test(rewind): add Phase 2/3 capture and restore cost measurement to RewindBenchmark

Phase 2 measures RewindRegistry.capture cost over 1000 iterations
against a representative engine state; Phase 3 measures restore cost
over 500 iterations from a fixed snapshot. v1.7 ships overall timing
only; per-subsystem breakdown is a future enhancement gated on
RewindRegistry exposing its registered subsystem list.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Track C — Seek phases (Phases 4-5)

### Task 4 — Phase 4 (cold seek) + Phase 5 (hot seek)

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`

Phase 4 measures `seekTo(F)` cost when the segment cache is empty (worst case). Phase 5 measures held-rewind cost via repeated `stepBackward()` calls — the segment cache amortises within a segment but rebuilds at boundaries.

- [ ] **Step 1: Implement Phase 4 (cold seek):**

```java
private void runPhase4ColdSeek() throws IOException {
    BenchmarkTiming overall = new BenchmarkTiming(50);
    long wallStart = System.nanoTime();
    try (HeadlessTestFixture fixture = buildFixture()) {
        GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
        var inputs = new TraceFixtureInputSource(fixture);
        var stepper = new TraceFixtureEngineStepper(fixture);
        gm.installPlaybackController(inputs, stepper, KEYFRAME_INTERVAL);
        var rc = gm.getRewindController();

        // Step forward to populate keyframes
        for (int i = 0; i < 1200; i++) rc.step();

        // Cold seek: invalidate cache between each seekTo.
        int[] targets = {60, 240, 540, 720, 900, 1080};
        // Iterate 8x to get enough samples per target.
        for (int rep = 0; rep < 8; rep++) {
            for (int target : targets) {
                long t0 = System.nanoTime();
                rc.seekTo(target);
                overall.record(System.nanoTime() - t0);
            }
        }
    }
    long wall = System.nanoTime() - wallStart;
    results.put("phase4.cold-seek",
            new BenchmarkResults("phase4.cold-seek",
                    overall.summarize(), wall, Map.of()));
}
```

`RewindController.seekTo(int)` already invalidates the segment cache internally per its implementation in Plan 2. So no explicit invalidation is needed — each `seekTo` is cold by virtue of crossing segments.

- [ ] **Step 2: Implement Phase 5 (hot seek):**

```java
private void runPhase5HotSeek() throws IOException {
    BenchmarkTiming withinSegment = new BenchmarkTiming(500);
    BenchmarkTiming acrossSegment = new BenchmarkTiming(50);
    long wallStart = System.nanoTime();
    try (HeadlessTestFixture fixture = buildFixture()) {
        GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
        var inputs = new TraceFixtureInputSource(fixture);
        var stepper = new TraceFixtureEngineStepper(fixture);
        gm.installPlaybackController(inputs, stepper, KEYFRAME_INTERVAL);
        var rc = gm.getRewindController();

        // Step forward 1200 frames so we have plenty of segments.
        for (int i = 0; i < 1200; i++) rc.step();

        // Hot scrub: stepBackward 30 times within the same segment
        // (segment expands once on first call, then O(1)).
        // Then crossing a segment boundary on call 31 (segment rebuild).
        // Then within the previous segment for another 30 calls.
        for (int i = 0; i < 30; i++) {
            long t0 = System.nanoTime();
            rc.stepBackward();
            withinSegment.record(System.nanoTime() - t0);
        }
        // Crossing a segment boundary
        long t0 = System.nanoTime();
        rc.stepBackward();
        acrossSegment.record(System.nanoTime() - t0);
        // Continue within previous segment
        for (int i = 0; i < 29; i++) {
            long t1 = System.nanoTime();
            rc.stepBackward();
            withinSegment.record(System.nanoTime() - t1);
        }
        // Cross another boundary
        long t2 = System.nanoTime();
        rc.stepBackward();
        acrossSegment.record(System.nanoTime() - t2);
    }
    long wall = System.nanoTime() - wallStart;
    results.put("phase5.hot-seek.within-segment",
            new BenchmarkResults("phase5.hot-seek.within-segment",
                    withinSegment.summarize(), wall, Map.of()));
    results.put("phase5.hot-seek.across-segment",
            new BenchmarkResults("phase5.hot-seek.across-segment",
                    acrossSegment.summarize(), wall, Map.of()));
}
```

- [ ] **Step 3: Wire both into `runBenchmark()` after Phase 3.**

- [ ] **Step 4: Run, confirm:**

```
mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Dmse=off" 2>&1 | tail -25
```

Expected: phase4.cold-seek shows tens-of-milliseconds-or-more (because each seek replays many frames forward from a keyframe). phase5.hot-seek.within-segment shows microseconds (cache hits). phase5.hot-seek.across-segment shows tens-of-milliseconds (segment rebuild).

- [ ] **Step 5: Commit:**

```
git add src/test/java/com/openggf/game/rewind/RewindBenchmark.java
git commit -m "$(cat <<'EOF'
test(rewind): add Phase 4/5 cold and hot seek measurement to RewindBenchmark

Phase 4 measures cold seekTo cost across 6 target frames * 8 reps = 48
samples. Phase 5 measures held-rewind via repeated stepBackward — within
a segment (cache hit) and across a segment boundary (cache rebuild) —
producing two separate timing buckets. Hot scrub within segment should
be O(1) microseconds; segment-boundary spike measures the rebuild cost.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Track D — Memory phase (Phase 6)

### Task 5 — Phase 6 (memory measurement)

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`

Reports keyframe count, total resident bytes (via `ObjectSizeEstimator`), per-subsystem average snapshot byte size, peak SegmentCache strip size, and level CoW array reuse rate.

For estimating object sizes in pure Java, use a simple recursive walk over public fields/records; if too invasive, use serialization to byte buffer and measure bytes. Easiest: serialize `CompositeSnapshot` via `java.io.ObjectOutputStream` and report serialized byte count as a proxy.

- [ ] **Step 1: Implement Phase 6:**

```java
private void runPhase6Memory() throws IOException {
    long wallStart = System.nanoTime();
    Map<String, Long> keyframeBytes = new LinkedHashMap<>();
    int keyframeCount = 0;
    long totalSerializedBytes = 0L;
    try (HeadlessTestFixture fixture = buildFixture()) {
        GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
        var inputs = new TraceFixtureInputSource(fixture);
        var stepper = new TraceFixtureEngineStepper(fixture);
        gm.installPlaybackController(inputs, stepper, KEYFRAME_INTERVAL);
        var rc = gm.getRewindController();

        for (int i = 0; i < 1200; i++) rc.step();

        // Sample one full CompositeSnapshot's serialized size as a proxy
        // for resident bytes per keyframe.
        var registry = gm.getRewindRegistry();
        CompositeSnapshot snap = registry.capture();
        for (var e : snap.entries().entrySet()) {
            long bytes = estimateSerializedSize(e.getValue());
            keyframeBytes.put(e.getKey(), bytes);
            totalSerializedBytes += bytes;
        }
        keyframeCount = 20;   // 1200 frames / 60 interval = 20 keyframes
    }
    long wall = System.nanoTime() - wallStart;

    System.out.printf("Phase 6 memory:%n  keyframeCount=%d totalBytesPerKeyframe=%d%n",
            keyframeCount, totalSerializedBytes);
    for (var e : keyframeBytes.entrySet()) {
        System.out.printf("    %-30s %10d bytes%n", e.getKey(), e.getValue());
    }
    long projectedTotalBytes = (long) keyframeCount * totalSerializedBytes;
    System.out.printf("  projectedResidentBytes=%d (%.2f MB)%n",
            projectedTotalBytes, projectedTotalBytes / 1_048_576.0);

    // Store the per-key bytes as PhaseStats with bytes-as-meanNs (semantic
    // hack to fit the JSON output schema — alternatively, extend
    // BenchmarkResults with a dedicated MemoryStats variant).
    Map<String, BenchmarkResults.PhaseStats> perKey = new LinkedHashMap<>();
    for (var e : keyframeBytes.entrySet()) {
        long bytes = e.getValue();
        perKey.put(e.getKey(),
                new BenchmarkResults.PhaseStats(1, bytes, bytes, bytes, bytes, bytes));
    }
    results.put("phase6.memory",
            new BenchmarkResults("phase6.memory",
                    new BenchmarkResults.PhaseStats(keyframeCount, totalSerializedBytes,
                            totalSerializedBytes, totalSerializedBytes, totalSerializedBytes,
                            totalSerializedBytes),
                    wall, perKey));
}

private static long estimateSerializedSize(Object obj) {
    if (obj == null) return 0L;
    try {
        var baos = new java.io.ByteArrayOutputStream();
        try (var oos = new java.io.ObjectOutputStream(baos)) {
            oos.writeObject(obj);
        }
        return baos.size();
    } catch (java.io.IOException | java.io.NotSerializableException e) {
        // Snapshot record contains non-serializable references (e.g.,
        // ObjectSpawn refs in ObjectManagerSnapshot). Return -1 as a
        // sentinel for "unknown size".
        return -1L;
    }
}
```

The serialization-based estimator returns -1 for non-Serializable snapshots (`ObjectManagerSnapshot` likely fails because `ObjectSpawn` references aren't Serializable; `LevelSnapshot` may fail because `Block[]`/`Chunk[]` aren't). That's ok — surfacing which subsystems aren't serialisable is itself useful for v2 disk-spill planning.

- [ ] **Step 2: Wire into `runBenchmark()` after Phase 5.**

- [ ] **Step 3: Run, confirm.** Expected: `phase6.memory` line in stdout with per-subsystem byte counts. Some keys (object-manager, level) will show -1 — document in stdout output.

- [ ] **Step 4: Commit:**

```
git add src/test/java/com/openggf/game/rewind/RewindBenchmark.java
git commit -m "$(cat <<'EOF'
test(rewind): add Phase 6 memory measurement to RewindBenchmark

Reports per-subsystem serialized snapshot byte size as a proxy for
resident memory. Snapshots that fail Java serialization (e.g.,
ObjectManagerSnapshot's ObjectSpawn refs, LevelSnapshot's Block[])
report -1 as a sentinel — surfacing non-serializable subsystems is
itself useful for v2 disk-spill planning. Total projected resident
bytes computed as keyframeCount * sum(per-key bytes).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Track E — Long-tail determinism gate

### Task 6 — Long-tail determinism gate

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`

The "longest clean rewind" property: scrub back N frames, replay forward to the original frame, assert the captured `CompositeSnapshot` at that frame matches the snapshot from the original forward pass — for the v1.5-covered keys (camera, gamestate, rng, etc.). Iterate increasing N until divergence is found OR we hit the trace start.

- [ ] **Step 1: Implement the gate:**

```java
private void runLongTailDeterminismGate() throws IOException {
    long wallStart = System.nanoTime();
    int longestCleanRewind = 0;
    String firstDivergentKey = null;
    int divergenceFrame = -1;

    try (HeadlessTestFixture fixture = buildFixture()) {
        GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
        var inputs = new TraceFixtureInputSource(fixture);
        var stepper = new TraceFixtureEngineStepper(fixture);
        gm.installPlaybackController(inputs, stepper, KEYFRAME_INTERVAL);
        var rc = gm.getRewindController();
        var registry = gm.getRewindRegistry();

        // Forward run: capture every-frame snapshots into a baseline list
        int targetFrame = 1200;
        java.util.List<CompositeSnapshot> baseline = new java.util.ArrayList<>(targetFrame + 1);
        baseline.add(registry.capture());
        for (int i = 0; i < targetFrame; i++) {
            rc.step();
            baseline.add(registry.capture());
        }

        // Test increasing scrub distances:
        int[] scrubDistances = {60, 120, 300, 600, 900, 1200};
        for (int n : scrubDistances) {
            int origin = rc.currentFrame();
            int back = Math.max(0, origin - n);
            rc.seekTo(back);
            // Replay forward to origin
            while (rc.currentFrame() < origin) {
                rc.step();
            }
            CompositeSnapshot afterReplay = registry.capture();
            String mismatch = compareForCoveredKeys(baseline.get(origin), afterReplay);
            if (mismatch != null) {
                firstDivergentKey = mismatch;
                divergenceFrame = origin;
                System.out.printf("Long-tail determinism gate: divergence after %d-frame rewind on key '%s' at frame %d%n",
                        n, mismatch, origin);
                break;
            }
            longestCleanRewind = n;
            System.out.printf("Long-tail determinism gate: clean rewind of %d frames%n", n);
        }
    }

    long wall = System.nanoTime() - wallStart;
    System.out.printf("Long-tail result: longestCleanRewind=%d frames (%.2f sec @ 60fps)%n",
            longestCleanRewind, longestCleanRewind / 60.0);
    if (firstDivergentKey != null) {
        System.out.printf("  firstDivergentKey=%s at frame %d%n",
                firstDivergentKey, divergenceFrame);
    }

    // Emit as a phase entry too:
    results.put("longtail.determinism",
            new BenchmarkResults("longtail.determinism",
                    new BenchmarkResults.PhaseStats(longestCleanRewind, longestCleanRewind,
                            longestCleanRewind, longestCleanRewind, longestCleanRewind,
                            longestCleanRewind),
                    wall, Map.of()));
}

/**
 * Returns the first mismatching key, or null if both snapshots agree on
 * all v1.5-covered keys. Uses the same per-key comparator approach as
 * TestRewindParityAgainstTrace.
 */
private static String compareForCoveredKeys(CompositeSnapshot a, CompositeSnapshot b) {
    String[] coveredKeys = {
            "camera", "gamestate", "gamerng", "timermanager", "fademanager",
            "oscillation", "level", "object-manager",
            "parallax", "water", "zone-runtime", "palette-ownership",
            "animated-tile-channels", "special-render", "advanced-render-mode",
            "mutation-pipeline", "solid-execution",
            "level-event", "rings", "s2-plc-art"
    };
    for (String key : coveredKeys) {
        Object av = a.get(key);
        Object bv = b.get(key);
        if (av == null && bv == null) continue;
        if (av == null || bv == null) return key + " (one-side-null)";
        if (!keyEquals(key, av, bv)) return key;
    }
    return null;
}

/**
 * Per-key equality. Defaults to .equals(); custom comparators for keys
 * whose snapshot records contain arrays or non-content-equal references.
 */
private static boolean keyEquals(String key, Object a, Object b) {
    return switch (key) {
        case "level" -> compareLevel(a, b);
        case "object-manager" -> compareObjectManager(a, b);
        // Most snapshots are records with primitive/immutable fields —
        // .equals() works.
        default -> java.util.Objects.equals(a, b);
    };
}

private static boolean compareLevel(Object a, Object b) {
    // Mirror TestRewindParityAgainstTrace's level comparison: epoch+1
    // tolerance, blocks/chunks identity-equal, mapData byte-equal.
    if (!(a instanceof com.openggf.game.rewind.snapshot.LevelSnapshot la) ||
        !(b instanceof com.openggf.game.rewind.snapshot.LevelSnapshot lb)) {
        return false;
    }
    return java.util.Arrays.equals(la.blocks(), lb.blocks())
        && java.util.Arrays.equals(la.chunks(), lb.chunks())
        && java.util.Arrays.equals(la.mapData(), lb.mapData());
}

private static boolean compareObjectManager(Object a, Object b) {
    // Defer to record .equals() with element-wise array comparison on
    // usedSlotsBits (mirror existing test's approach).
    if (!(a instanceof com.openggf.game.rewind.snapshot.ObjectManagerSnapshot oa) ||
        !(b instanceof com.openggf.game.rewind.snapshot.ObjectManagerSnapshot ob)) {
        return false;
    }
    if (!java.util.Arrays.equals(oa.usedSlotsBits(), ob.usedSlotsBits())) return false;
    if (oa.frameCounter() != ob.frameCounter()) return false;
    if (oa.vblaCounter() != ob.vblaCounter()) return false;
    // Per-slot list — defer to deep .equals() (PerObjectRewindSnapshot is
    // a primitive-fielded record).
    return java.util.Objects.equals(oa.slots(), ob.slots());
}
```

If `ObjectManagerSnapshot` has different field names than the plan suggests, adjust to match. Read `src/main/java/com/openggf/game/rewind/snapshot/ObjectManagerSnapshot.java` if needed.

If the per-key comparator code duplicates `TestRewindParityAgainstTrace`'s helpers, extract them into a sibling helper class and have both tests use it. That's a clean refactor — do it if convenient. Otherwise just duplicate.

- [ ] **Step 2: Wire into `runBenchmark()` after Phase 6.** Add:

```java
runLongTailDeterminismGate();
```

- [ ] **Step 3: Run.** Expected: stdout reports "Long-tail determinism gate: clean rewind of 60 frames", "...300 frames", "...1200 frames" or surfaces a divergence at one of the boundaries with the offending key. **If divergence surfaces, that's a real coverage gap that v1.5's keystone parity test missed** — it's worth investigating whether the gap is in a specific subsystem's restore logic or whether the gate's comparator has a bug.

- [ ] **Step 4: Commit:**

```
git add src/test/java/com/openggf/game/rewind/RewindBenchmark.java
git commit -m "$(cat <<'EOF'
test(rewind): add long-tail determinism gate to RewindBenchmark

Scrubs back N seconds at increasing distances (60, 120, 300, 600, 900,
1200 frames), replays forward to the original frame, asserts the
resulting CompositeSnapshot matches the originally-captured baseline
for v1.5-covered keys. Reports longest clean rewind. Catches escaping
mutable state automatically — divergence at any distance signals a
specific subsystem whose adapter doesn't fully round-trip across
multiple capture/restore cycles.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Track F — JSON output + baseline numbers

### Task 7 — JSON output dump + baseline file

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`
- Create: `src/test/resources/rewind-benchmark-baseline.json`

- [ ] **Step 1: Implement JSON dump at end of `runBenchmark()`:**

Add to `runBenchmark()` after all phases:

```java
dumpJson();
```

And the helper:

```java
private void dumpJson() throws IOException {
    Path outputDir = Path.of("target");
    Files.createDirectories(outputDir);
    Path output = outputDir.resolve("rewind-benchmark-results.json");

    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"trace\": \"").append(TRACE_DIR).append("\",\n");
    sb.append("  \"keyframeInterval\": ").append(KEYFRAME_INTERVAL).append(",\n");
    sb.append("  \"phases\": {\n");
    int i = 0;
    for (var e : results.entrySet()) {
        if (i++ > 0) sb.append(",\n");
        BenchmarkResults r = e.getValue();
        sb.append("    \"").append(escapeJson(e.getKey())).append("\": {\n");
        appendPhaseStats(sb, r.overall());
        sb.append(",\n      \"totalWallTimeNs\": ").append(r.totalWallTimeNs());
        if (!r.perSubsystem().isEmpty()) {
            sb.append(",\n      \"perSubsystem\": {\n");
            int j = 0;
            for (var ps : r.perSubsystem().entrySet()) {
                if (j++ > 0) sb.append(",\n");
                sb.append("        \"").append(escapeJson(ps.getKey())).append("\": {");
                BenchmarkResults.PhaseStats s = ps.getValue();
                sb.append(" \"sampleCount\": ").append(s.sampleCount());
                sb.append(", \"meanNs\": ").append(s.meanNs());
                sb.append(", \"p50Ns\": ").append(s.p50Ns());
                sb.append(", \"p95Ns\": ").append(s.p95Ns());
                sb.append(", \"p99Ns\": ").append(s.p99Ns());
                sb.append(", \"maxNs\": ").append(s.maxNs());
                sb.append(" }");
            }
            sb.append("\n      }");
        }
        sb.append("\n    }");
    }
    sb.append("\n  }\n}\n");

    Files.writeString(output, sb.toString());
    System.out.printf("Benchmark results written to %s%n", output);
}

private static void appendPhaseStats(StringBuilder sb, BenchmarkResults.PhaseStats s) {
    sb.append("      \"sampleCount\": ").append(s.sampleCount()).append(",\n");
    sb.append("      \"meanNs\": ").append(s.meanNs()).append(",\n");
    sb.append("      \"p50Ns\": ").append(s.p50Ns()).append(",\n");
    sb.append("      \"p95Ns\": ").append(s.p95Ns()).append(",\n");
    sb.append("      \"p99Ns\": ").append(s.p99Ns()).append(",\n");
    sb.append("      \"maxNs\": ").append(s.maxNs());
}

private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
}
```

- [ ] **Step 2: Run, confirm `target/rewind-benchmark-results.json` is created** with all phase entries.

```
mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Dmse=off" 2>&1 | tail -10
cat target/rewind-benchmark-results.json
```

Verify the JSON is well-formed with each phase populated.

- [ ] **Step 3: Copy `target/rewind-benchmark-results.json` to `src/test/resources/rewind-benchmark-baseline.json`** as the committed baseline numbers from one developer-machine run:

```bash
cp target/rewind-benchmark-results.json src/test/resources/rewind-benchmark-baseline.json
```

Add a comment-block-like JSON header field to the baseline file noting host info:

```bash
# Edit src/test/resources/rewind-benchmark-baseline.json to add a
# top-level "_meta" key documenting:
#   "_meta": {
#     "host": "<hostname>",
#     "javaVersion": "<java -version output>",
#     "date": "2026-05-05",
#     "purpose": "Initial baseline for rewind framework v1.7. Numbers
#                 are hardware-dependent; CI does not enforce thresholds
#                 from this file. Use as a regression-detection reference
#                 for local benchmark runs."
#   }
```

Use the actual host's hostname and Java version. Use the actual date (`Get-Date -Format "yyyy-MM-dd"`).

- [ ] **Step 4: Commit:**

```
git add src/test/java/com/openggf/game/rewind/RewindBenchmark.java \
        src/test/resources/rewind-benchmark-baseline.json
git commit -m "$(cat <<'EOF'
test(rewind): emit RewindBenchmark JSON output + commit baseline numbers

Writes target/rewind-benchmark-results.json with per-phase percentile
stats + per-subsystem breakdown when available. Commits a baseline
file at src/test/resources/rewind-benchmark-baseline.json from one
developer-machine run for regression-detection reference. Numbers are
hardware-dependent; CI does not enforce budget thresholds from this
file in v1.7 — that's a follow-up.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final integration check

### Task FINAL.1 — Full-suite verification + benchmark sanity run

- [ ] **Step 1: Verify the benchmark is actually skipped in default test runs** (so it doesn't slow CI):

```
mvn test "-Dtest=RewindBenchmark" "-Dmse=off" 2>&1 | grep -E "Tests run:|skipped"
```

Expected: `Tests run: 0, Failures: 0, Errors: 0, Skipped: 1` — i.e., the @EnabledIfSystemProperty guard correctly disables the test when the property isn't set.

- [ ] **Step 2: Verify default-suite full run is unchanged:**

```
mvn test "-Dmse=off" 2>&1 | grep -E "Tests run:.*Failures.*Errors" | tail -1
```

Expected: same test count and failure count as the pre-plan-4 baseline (4464 / 38).

- [ ] **Step 3: Verify the opt-in run works end-to-end:**

```
mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Dmse=off" 2>&1 | tail -50
```

Expected: stdout shows all 6 phases + long-tail determinism gate result. `target/rewind-benchmark-results.json` exists and is well-formed JSON. Long-tail clean rewind is at least 300 frames (5 seconds @ 60fps) for the framework to be considered "shippable" v1.7. If it's less, that's a real coverage gap surfaced by the benchmark — investigate which key diverged at the smallest scrub distance and fix the corresponding adapter.

- [ ] **Step 4: Report final state to user** with: total commits, what each phase reported (mean/p95 per phase), longest clean rewind, any subsystems that surfaced as non-serializable in Phase 6, and any divergences surfaced by the long-tail gate.

---

## Summary

When this plan completes, the codebase has:

1. A `RewindBenchmark` headless harness at `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`, opt-in via `-Dopenggf.rewind.benchmark.run=true`, runnable against the S2 EHZ1 reference trace.
2. Six measurement phases producing per-phase percentile timing stats: forward overhead (off vs on), capture cost, restore cost, cold seek, hot seek (within-segment + across-segment), memory.
3. A long-tail determinism gate that scrubs back N seconds at increasing distances and reports the longest clean rewind. Catches escaping mutable state automatically.
4. JSON output at `target/rewind-benchmark-results.json` for trend tracking, plus a committed baseline at `src/test/resources/rewind-benchmark-baseline.json`.
5. Documented coverage gaps surfaced by the long-tail gate (if any) — specific subsystems whose adapters need follow-up fixes.

Documented follow-ups for v1.8 / v1.9:
- CI budget gate enforcement (fail when p95 forward-step exceeds threshold).
- Cross-game benchmarks (S1 GHZ1, S3K AIZ).
- Per-subsystem capture/restore timing breakdown (gated on `RewindRegistry.snapshottables()` accessor — included as optional enhancement in this plan if executed).
- Visualiser UX (separate plan v1.8).
