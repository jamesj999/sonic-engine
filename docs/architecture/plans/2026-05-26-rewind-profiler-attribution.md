# Rewind Profiler Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Attribute rewind-time allocations and time to dedicated `rewind.*` profiler sections instead of leaking them into the `update` umbrella section, so the debug performance overlay accurately shows where rewind cost actually lives.

**Architecture:** Mirror the existing `rewind.capture` instrumentation pattern (`RewindRegistry.capture()` already wraps itself in `beginSection`/`endSection`). Add four sibling sections: `rewind.restore` inside `RewindRegistry.restore()`, plus `rewind.step`, `rewind.seek`, and `rewind.tick` inside `RewindController`. Because `PerformanceProfiler` sections do not nest (a new `beginSection` implicitly ends the active one), the outer wrappers re-open themselves explicitly after sub-sections close, and every `beginSection` has a paired `endSection` in a `try/finally` so exceptions never leave the profiler with a dangling active section. Production code depends on a new minimal `SectionProfiler` interface (just `beginSection` / `endSection`) instead of the concrete `PerformanceProfiler` singleton, enabling clean test doubles.

**Tech Stack:** Java 21, JUnit 5 / Jupiter, existing `PerformanceProfiler` + `MemoryStats` singleton infrastructure under `com.openggf.debug`.

---

## Background & Why This Matters

During held rewind, the user observed `update` and `rewind.capture` ("rewind.c" truncated) showing large allocations in the performance overlay's memory profile, much higher than during normal play.

Root cause:

1. **`PerformanceProfiler` sections do not nest** — `beginSection` implicitly ends the active section (`PerformanceProfiler.java:173-180`). The outer `update` section opened at `Engine.java:1323` only covers the prologue before the first nested section starts.
2. **Normal play:** `GameLoop.stepInternal()` quickly calls `beginSection("timers")` (`:549`), so `update` is truncated to a tiny prologue and the rest of the tick attributes to `timers`/`physics`/`objects`/`camera`/`level`.
3. **Held rewind:** `GameLoop.stepInternal()` early-returns at `:463` *before any child section starts*, so `update` accumulates everything `LiveRewindManager` → `RewindController.stepBackward` does — including up to 60 full `engineStepper.step()` calls inside `SegmentCache.snapshotAt` cold expansion, plus two `registry.restore()` calls — until eventually `RewindRegistry.capture()` opens `rewind.capture` and implicitly closes `update`.
4. **`rewind.capture` spike:** Same segment-cache cold expansion fires `registry.capture()` up to 60 times in one visual frame at each backward keyframe crossing.

This plan does **not** reduce the underlying allocation cost. It only fixes *attribution* so the overlay tells the truth.

## File Structure

**Modified / created production files:**

| File | Responsibility | Change |
|------|----------------|--------|
| `src/main/java/com/openggf/debug/SectionProfiler.java` | **New.** Minimal interface: `beginSection(String)` + `endSection(String)`. Plus a `NOOP` constant for code paths that opt out. | Create. |
| `src/main/java/com/openggf/debug/PerformanceProfiler.java` | Profiler singleton. | Add `implements SectionProfiler` to the class declaration. Method signatures already match — no body change. |
| `src/main/java/com/openggf/game/rewind/RewindRegistry.java` | Capture/restore registry. Already wraps `capture()` with `rewind.capture` profiler section. | Change profiler field/constructor parameter type from `PerformanceProfiler` to `SectionProfiler`. Add matching `rewind.restore` wrapper around `restore()`. |
| `src/main/java/com/openggf/game/rewind/RewindController.java` | Orchestrates stepBackward/seekTo + segment cache + audio replay. | Add optional `SectionProfiler` field + constructor overload. Wrap `stepBackward()`, `seekTo()`, and the inner engine-step calls with paired `try/finally` blocks for every section. |
| `src/main/java/com/openggf/game/session/GameplayModeContext.java` | Constructs `RewindController`. | Pass the existing `profiler` field into the new constructor overload. (Field stays typed `PerformanceProfiler`; auto-upcasts to `SectionProfiler`.) |

**New test files:**

| File | Responsibility |
|------|----------------|
| `src/test/java/com/openggf/game/rewind/RecordingSectionProfiler.java` | Test-only helper. Implements `SectionProfiler`. Records a transcript of begin/end events, exposes `activeSection()` for balance assertions, exposes `clearTranscript()` for setup. |
| `src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java` | Verifies `rewind.restore`, `rewind.step`, `rewind.seek`, `rewind.tick` begin/end calls fire in the expected order, are properly balanced (no dangling active section), and stay balanced through thrown exceptions. |

**Documentation files:**

| File | Change |
|------|--------|
| `PERFORMANCE_OVERVIEW_20260512.md` | Add new rows to §1 "Sections instrumented" table for the four new sections. Update §2.4 to reflect the new attribution. |
| `docs/guide/contributing/rewind-system.md` | Short subsection naming the new sections + a sentence explaining the section-non-nesting interaction. |

**Out of scope (explicit non-goals):**

- Per-subsystem `rewind.restore.{audio,objects,camera,...}` breakdown.
- Pooling `LinkedHashMap` / `CompositeSnapshot[60]` / `GenericFieldCapturer` allocations to reduce the spike's magnitude.
- A non-truncating `MemoryStats.recordSectionAllocations(...)` primitive analogous to `PerformanceProfiler.recordSectionTime(...)`.
- Instrumenting `TraceSessionLauncher.handleRealtimeRewindInput`.

---

## Task 1: Add `SectionProfiler` interface and make `PerformanceProfiler` implement it

**Files:**
- Create: `src/main/java/com/openggf/debug/SectionProfiler.java`
- Modify: `src/main/java/com/openggf/debug/PerformanceProfiler.java:15`

The interface decouples production rewind code from the singleton, so tests can supply a recording double without subclassing.

- [ ] **Step 1: Create the interface**

Create `src/main/java/com/openggf/debug/SectionProfiler.java`:

```java
package com.openggf.debug;

/**
 * Minimal section-timing surface. Implementations record nanosecond and
 * allocation deltas between matched {@link #beginSection(String)} and
 * {@link #endSection(String)} calls; sections do not nest (a new
 * {@code beginSection} implicitly ends the active one).
 */
public interface SectionProfiler {

    /** Opens a section. Implicitly ends any currently-active section. */
    void beginSection(String name);

    /**
     * Closes the named section if it is currently active. No-op when the
     * active section's name does not match — this is normal because nested
     * {@code beginSection} calls implicitly close the outer section.
     */
    void endSection(String name);

    /** Profiler that does nothing — useful when callers want to opt out without null checks. */
    SectionProfiler NOOP = new SectionProfiler() {
        @Override public void beginSection(String name) { }
        @Override public void endSection(String name) { }
    };
}
```

- [ ] **Step 2: Make `PerformanceProfiler` implement `SectionProfiler`**

In `src/main/java/com/openggf/debug/PerformanceProfiler.java`, change line 15:

```java
public class PerformanceProfiler implements SectionProfiler {
```

(Was: `public class PerformanceProfiler {`.) The existing `beginSection(String)` and `endSection(String)` methods already match the interface — no body changes required.

- [ ] **Step 3: Verify compile**

Run: `mvn -Dmse=relaxed -DskipTests test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/debug/SectionProfiler.java src/main/java/com/openggf/debug/PerformanceProfiler.java
git commit -m "refactor: introduce SectionProfiler interface for rewind instrumentation"
```

Trailer block: `Changelog: n/a`, `Guide: n/a`, `Known-Discrepancies: n/a`, `S3K-Known-Discrepancies: n/a`, `Agent-Docs: n/a`, `Configuration-Docs: n/a`, `Skills: n/a`.

---

## Task 2: Add `RecordingSectionProfiler` test helper

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/RecordingSectionProfiler.java`

Records every begin/end event in order, exposes the active section name so tests can assert balance after the operation under test.

- [ ] **Step 1: Create the recording profiler**

Create `src/test/java/com/openggf/game/rewind/RecordingSectionProfiler.java`:

```java
package com.openggf.game.rewind;

import com.openggf.debug.SectionProfiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-only {@link SectionProfiler} that records begin/end events and tracks
 * the currently-active section. Use {@link #activeSection()} to assert
 * balance after an operation, and {@link #transcript()} to verify ordering.
 */
final class RecordingSectionProfiler implements SectionProfiler {

    /** Transcript entries are "begin:NAME" or "end:NAME". */
    private final List<String> transcript = new ArrayList<>();

    /** Currently active section name, or null. */
    private String activeName;

    @Override
    public void beginSection(String name) {
        if (activeName != null) {
            transcript.add("end:" + activeName);
        }
        transcript.add("begin:" + name);
        activeName = name;
    }

    @Override
    public void endSection(String name) {
        if (activeName == null || !activeName.equals(name)) {
            return;
        }
        transcript.add("end:" + name);
        activeName = null;
    }

    /** Snapshot the recorded transcript (defensive copy). */
    List<String> transcript() {
        return Collections.unmodifiableList(new ArrayList<>(transcript));
    }

    /** Names that appeared as begin events, in first-occurrence order. */
    List<String> beginNames() {
        List<String> out = new ArrayList<>();
        for (String entry : transcript) {
            if (entry.startsWith("begin:")) {
                String n = entry.substring("begin:".length());
                if (!out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    /** Currently active section, or {@code null} if no section is open. */
    String activeSection() {
        return activeName;
    }

    /** Reset transcript + active section. */
    void clearTranscript() {
        transcript.clear();
        activeName = null;
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -Dmse=relaxed -DskipTests test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/game/rewind/RecordingSectionProfiler.java
git commit -m "test: add RecordingSectionProfiler test helper"
```

Trailer block: all `n/a`.

---

## Task 3: Add `rewind.restore` section to `RewindRegistry`; switch field type to `SectionProfiler`

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/RewindRegistry.java`
- Create: `src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java`

Mirror the existing `rewind.capture` wrapper onto `restore()`, and switch the profiler field type from `PerformanceProfiler` to `SectionProfiler`. The constructor signature change is source-compatible for the only production caller (`GameplayModeContext.java:182` passes `PerformanceProfiler`, which now implements the interface).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java`:

```java
package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindProfilerAttribution {

    private static CompositeSnapshot snap(int marker) {
        var e = new LinkedHashMap<String, Object>();
        e.put("marker", marker);
        return new CompositeSnapshot(e);
    }

    @Test
    void registryRestoreWrapsInRewindRestoreSection() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return 1; }
            @Override public void restore(Integer s) { }
        });

        reg.restore(snap(42));

        List<String> transcript = prof.transcript();
        assertEquals(List.of("begin:rewind.restore", "end:rewind.restore"), transcript,
                "Expected exactly one balanced rewind.restore pair: " + transcript);
        assertNull(prof.activeSection(), "No section should be active after restore");
    }

    private static final class FakeInputSource implements InputSource {
        private final int count;
        FakeInputSource(int count) { this.count = count; }
        @Override public int frameCount() { return count; }
        @Override public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "fake");
        }
    }
}
```

(Inner `FakeInputSource` mirrors `TestRewindController.java:249-265`. `InputSource` has only `frameCount()` and `read(int)` — no `discardAfter`. The five-arg `Bk2FrameInput` constructor `(frameIndex, p1InputMask, p1ActionMask, p1StartPressed, rawLine)` is the right form per `Bk2FrameInput.java:48-51`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dmse=relaxed "-Dtest=TestRewindProfilerAttribution#registryRestoreWrapsInRewindRestoreSection" test`
Expected: FAIL — `RewindRegistry(RecordingSectionProfiler)` does not compile yet (registry takes `PerformanceProfiler`) AND `rewind.restore` events not emitted.

- [ ] **Step 3: Switch field type and wrap `restore`**

In `src/main/java/com/openggf/game/rewind/RewindRegistry.java`:

Replace the import:

```java
import com.openggf.debug.SectionProfiler;
```

(Was: `import com.openggf.debug.PerformanceProfiler;`.)

Replace the field declaration:

```java
    private final SectionProfiler profiler;
```

(Was: `private final PerformanceProfiler profiler;`.)

Replace the parameterised constructor:

```java
    public RewindRegistry(SectionProfiler profiler) {
        this.profiler = profiler;
    }
```

(Was: `public RewindRegistry(PerformanceProfiler profiler) { this.profiler = profiler; }`.)

Replace the existing `restore` method (lines 81-96) with:

```java
    public void restore(CompositeSnapshot cs) {
        Objects.requireNonNull(cs, "cs");
        if (profiler != null) {
            profiler.beginSection("rewind.restore");
        }
        try {
            for (var e : entries.entrySet()) {
                if (!cs.containsKey(e.getKey())) {
                    e.getValue().resetForMissingSnapshot();
                    continue;
                }
                Object snap = cs.get(e.getKey());
                @SuppressWarnings({"rawtypes", "unchecked"})
                RewindSnapshottable raw = e.getValue();
                raw.restore(snap);
            }
            for (Runnable callback : postRestoreCallbacks.values()) {
                callback.run();
            }
        } finally {
            if (profiler != null) {
                profiler.endSection("rewind.restore");
            }
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dmse=relaxed "-Dtest=TestRewindProfilerAttribution#registryRestoreWrapsInRewindRestoreSection" test`
Expected: `Tests run: 1, Failures: 0`.

- [ ] **Step 5: Run the full rewind test class**

Run: `mvn -Dmse=relaxed "-Dtest=com.openggf.game.rewind.**" test`
Expected: all tests pass (the `RewindRegistry(SectionProfiler)` signature accepts both `null` and any `SectionProfiler`, including `PerformanceProfiler`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/RewindRegistry.java src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java
git commit -m "feat: wrap RewindRegistry.restore in rewind.restore section; depend on SectionProfiler"
```

Trailer block: `Changelog: updated`, others `n/a`.

---

## Task 4: Add `SectionProfiler` constructor overload to `RewindController`

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/RewindController.java:1-57`

Add the optional profiler field and a new constructor overload. Existing two- and one-audio-arg constructors delegate with `null` so existing callers and tests don't need changes.

- [ ] **Step 1: Add the import and field**

In `src/main/java/com/openggf/game/rewind/RewindController.java`:

Add to imports (after `com.openggf.debug.playback.Bk2FrameInput`):

```java
import com.openggf.debug.SectionProfiler;
```

Add to the field block (after `private final AudioKeyframeStore audioKeyframes;` on line 21):

```java
    private final SectionProfiler profiler;
```

- [ ] **Step 2: Update constructors**

Replace the two existing constructors (lines 25-57) with three constructors that all delegate down to the same most-general one:

```java
    public RewindController(
            RewindRegistry registry,
            KeyframeStore keyframes,
            InputSource inputs,
            EngineStepper engineStepper,
            int keyframeInterval) {
        this(registry, keyframes, inputs, engineStepper, keyframeInterval, null, null);
    }

    public RewindController(
            RewindRegistry registry,
            KeyframeStore keyframes,
            InputSource inputs,
            EngineStepper engineStepper,
            int keyframeInterval,
            AudioManager audioManager) {
        this(registry, keyframes, inputs, engineStepper, keyframeInterval, audioManager, null);
    }

    public RewindController(
            RewindRegistry registry,
            KeyframeStore keyframes,
            InputSource inputs,
            EngineStepper engineStepper,
            int keyframeInterval,
            AudioManager audioManager,
            SectionProfiler profiler) {
        this.registry = Objects.requireNonNull(registry);
        this.keyframes = Objects.requireNonNull(keyframes);
        this.inputs = Objects.requireNonNull(inputs);
        this.engineStepper = Objects.requireNonNull(engineStepper);
        if (keyframeInterval <= 0) {
            throw new IllegalArgumentException(
                    "keyframeInterval must be > 0, got " + keyframeInterval);
        }
        this.keyframeInterval = keyframeInterval;
        this.audioManager = audioManager;
        this.audioKeyframes = audioManager != null ? new AudioKeyframeStore() : null;
        this.segmentCache = new SegmentCache(keyframeInterval);
        this.currentFrame = 0;
        this.profiler = profiler;
        // Capture frame 0 so seekTo(0) always has a base.
        keyframes.put(0, registry.capture());
        captureAudioKeyframe(0);
    }
```

- [ ] **Step 3: Verify compile + existing tests still pass**

Run: `mvn -Dmse=relaxed "-Dtest=com.openggf.game.rewind.**" test`
Expected: all pre-existing tests pass (the new constructor is additive).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/RewindController.java
git commit -m "feat: add SectionProfiler constructor overload to RewindController"
```

Trailer block: `Changelog: n/a` (no behavior change yet), others `n/a`.

---

## Task 5: Wrap `RewindController.stepBackward` with `rewind.step` + `rewind.tick`, exception-safe

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/RewindController.java:158-195`
- Extend: `src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java`

Wrap the body of `stepBackward()` in a `rewind.step` outer section, and wrap each inner `engineStepper.step(...)` invocation with `rewind.tick`. Every `beginSection` has a paired `endSection` in `try/finally`, so an exception inside `engineStepper.step` or `registry.capture` cannot leave a dangling active section.

Because sections do not nest, `registry.capture()` opening `rewind.capture` will implicitly close `rewind.tick` on the happy path — the inner `endSection("rewind.tick")` becomes a no-op in that case (it sees `activeSection == null` and returns without recording anything). On the exception path, the inner `endSection` records the partial delta and clears the active section. Net effect: `rewind.tick`'s allocation total is recorded once per replay iteration regardless of path.

- [ ] **Step 1: Write the failing test (happy path, hot + cold segments, plus balance)**

Append to `TestRewindProfilerAttribution`:

```java
    @Test
    void stepBackwardEmitsExpectedSectionsAndStaysBalanced() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();
        EngineStepper stepper = (in) -> state.incrementAndGet();
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, stepper, 5, null, prof);

        for (int i = 0; i < 7; i++) rc.step();
        prof.clearTranscript(); // Drop capture noise from forward stepping.

        boolean stepped = rc.stepBackward();
        assertTrue(stepped);

        List<String> beginsInOrder = prof.beginNames();
        assertTrue(beginsInOrder.contains("rewind.step"),
                "Expected rewind.step in begin order: " + beginsInOrder);
        assertTrue(beginsInOrder.contains("rewind.tick"),
                "Expected rewind.tick (cold-segment expansion): " + beginsInOrder);
        assertTrue(beginsInOrder.contains("rewind.restore"),
                "Expected rewind.restore: " + beginsInOrder);
        assertTrue(beginsInOrder.indexOf("rewind.step") < beginsInOrder.indexOf("rewind.tick"),
                "rewind.step must open before rewind.tick: " + beginsInOrder);
        assertNull(prof.activeSection(),
                "No section should be active after stepBackward: transcript=" + prof.transcript());
    }
```

- [ ] **Step 2: Write the failing exception-path test**

Append to `TestRewindProfilerAttribution`:

```java
    @Test
    void stepBackwardLeavesProfilerCleanWhenStepperThrows() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();

        // Stepper throws on the second invocation, simulating a replay-frame failure
        // mid-segment-expansion (after at least one rewind.tick section has opened).
        final boolean[] poisoned = { false };
        EngineStepper throwingStepper = (in) -> {
            if (poisoned[0]) {
                throw new RuntimeException("simulated stepper failure");
            }
            state.incrementAndGet();
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, throwingStepper, 5, null, prof);

        for (int i = 0; i < 12; i++) rc.step();
        prof.clearTranscript();
        poisoned[0] = true;

        assertThrows(RuntimeException.class, rc::stepBackward,
                "Expected stepBackward to propagate the stepper's exception");
        List<String> transcript = prof.transcript();
        // Guard: assert the instrumentation actually opened rewind.tick before the
        // throw. Without this, the test would pass trivially before Task 5 wires the
        // section — the stepper would throw without any section ever being opened,
        // leaving activeSection == null for the wrong reason.
        assertTrue(transcript.contains("begin:rewind.tick"),
                "Expected rewind.tick to have been opened before the throw: " + transcript);
        assertNull(prof.activeSection(),
                "Profiler must have no dangling active section after exception: transcript="
                        + transcript);
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -Dmse=relaxed "-Dtest=TestRewindProfilerAttribution#stepBackwardEmitsExpectedSectionsAndStaysBalanced+TestRewindProfilerAttribution#stepBackwardLeavesProfilerCleanWhenStepperThrows" test`
Expected: both FAIL (section names not emitted yet).

- [ ] **Step 4: Wrap `stepBackward`**

In `src/main/java/com/openggf/game/rewind/RewindController.java`, replace the existing `stepBackward()` method (lines 158-195) with:

```java
    public boolean stepBackward() {
        if (currentFrame <= earliestAvailableFrame()) return false;
        int originalFrame = currentFrame;
        int target = currentFrame - 1;
        int keyframeFrame = (target / keyframeInterval) * keyframeInterval;
        final var floor = keyframes.latestAtOrBefore(keyframeFrame).orElseThrow();
        final int keyframeSnapshot = floor.frame();
        final var restoreSnapshot = floor.snapshot();
        // Use int[] wrapper to allow mutation within lambdas
        final int[] pos = { currentFrame };
        if (profiler != null) profiler.beginSection("rewind.step");
        try (AudioReplayScope ignored = beginAudioReplay(
                originalFrame, target, AudioReplayReason.STEP_BACKWARD)) {
            CompositeSnapshot snap = segmentCache.snapshotAt(
                    target,
                    restoreSnapshot,
                    keyframeSnapshot,
                    () -> {
                        registry.restore(restoreSnapshot);
                        pos[0] = keyframeSnapshot;
                        primeStepperAtFrame(pos[0]);
                    },
                    () -> {
                        if (profiler != null) profiler.beginSection("rewind.tick");
                        try {
                            Bk2FrameInput in = inputs.read(pos[0] + 1);
                            engineStepper.step(in);
                            pos[0]++;
                            // On happy path, registry.capture() opens rewind.capture which
                            // implicitly ends rewind.tick (recording its delta) before
                            // the finally fires. The finally then no-ops.
                            return registry.capture();
                        } finally {
                            if (profiler != null) profiler.endSection("rewind.tick");
                        }
                    });
            registry.restore(snap);
            // registry.restore closed its rewind.restore in its own finally,
            // leaving no active section. Re-open rewind.step so the audio
            // bookkeeping tail credits to it. No re-open is needed between
            // snapshotAt return and registry.restore: nothing measurable
            // happens between them (trivial reference assignment).
            if (profiler != null) profiler.beginSection("rewind.step");
            currentFrame = target;
            keyframes.discardAfter(currentFrame);
            discardAudioAfter(currentFrame);
            restoreAudioLogicalState(currentFrame);
            beginAudioFrame(currentFrame);
            primeStepperAtFrame(currentFrame);
            afterAudioRestore(AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
        } finally {
            if (profiler != null) profiler.endSection("rewind.step");
        }
        return true;
    }
```

Key exception-safety property: the inner `try/finally` around the replay body guarantees `endSection("rewind.tick")` is called on every path. On the happy path it no-ops (`registry.capture()` already closed the section). On the exception path it closes the section, the exception propagates, and the outer `try/finally` closes `rewind.step` if it was the active section, leaving the profiler clean.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -Dmse=relaxed "-Dtest=TestRewindProfilerAttribution#stepBackwardEmitsExpectedSectionsAndStaysBalanced+TestRewindProfilerAttribution#stepBackwardLeavesProfilerCleanWhenStepperThrows" test`
Expected: both pass.

- [ ] **Step 6: Run all rewind tests**

Run: `mvn -Dmse=relaxed "-Dtest=com.openggf.game.rewind.**" test`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/RewindController.java src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java
git commit -m "feat: attribute RewindController.stepBackward to rewind.step + rewind.tick (exception-safe)"
```

Trailer block: `Changelog: updated`, others `n/a`.

---

## Task 6: Wrap `RewindController.seekTo` with `rewind.seek` + `rewind.tick`, exception-safe

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/RewindController.java:124-152`
- Extend: `src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java`

Same exception-safety pattern: `try/finally` around each `rewind.tick` window so a throwing stepper can't leak. Unlike `stepBackward`, `seekTo` does not call `registry.capture()` inside its loop, so on the happy path the `endSection("rewind.tick")` actually records the delta (it isn't pre-closed).

- [ ] **Step 1: Write the failing tests (happy path + exception path)**

Append to `TestRewindProfilerAttribution`:

```java
    @Test
    void seekToEmitsExpectedSectionsAndStaysBalanced() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();
        EngineStepper stepper = (in) -> state.incrementAndGet();
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, stepper, 5, null, prof);

        for (int i = 0; i < 7; i++) rc.step();
        prof.clearTranscript();

        rc.seekTo(3);

        List<String> beginsInOrder = prof.beginNames();
        assertTrue(beginsInOrder.contains("rewind.seek"), "Expected rewind.seek: " + beginsInOrder);
        assertTrue(beginsInOrder.contains("rewind.tick"),
                "Expected rewind.tick (forward stepping in seek): " + beginsInOrder);
        assertTrue(beginsInOrder.contains("rewind.restore"),
                "Expected rewind.restore: " + beginsInOrder);
        assertTrue(beginsInOrder.indexOf("rewind.seek") < beginsInOrder.indexOf("rewind.tick"),
                "rewind.seek must open before rewind.tick: " + beginsInOrder);
        assertNull(prof.activeSection(),
                "No section should be active after seekTo: transcript=" + prof.transcript());
    }

    @Test
    void seekToLeavesProfilerCleanWhenStepperThrows() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();

        final boolean[] poisoned = { false };
        EngineStepper throwingStepper = (in) -> {
            if (poisoned[0]) {
                throw new RuntimeException("simulated seek stepper failure");
            }
            state.incrementAndGet();
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, throwingStepper, 5, null, prof);

        for (int i = 0; i < 7; i++) rc.step();
        prof.clearTranscript();
        poisoned[0] = true;

        // seekTo(4) forces a backward seek that crosses a keyframe boundary
        // (floor keyframe = 0), so the forward-replay loop runs at least once
        // and the poisoned stepper throws. seekTo(5) would short-circuit the
        // loop (target == floor frame) and never invoke the stepper.
        assertThrows(RuntimeException.class, () -> rc.seekTo(4),
                "Expected seekTo to propagate the stepper's exception");
        List<String> transcript = prof.transcript();
        // Guard: assert rewind.tick was actually opened before the throw, otherwise
        // this test would pass trivially before Task 6 wires the section.
        assertTrue(transcript.contains("begin:rewind.tick"),
                "Expected rewind.tick to have been opened before the throw: " + transcript);
        assertNull(prof.activeSection(),
                "Profiler must have no dangling active section after seek exception: transcript="
                        + transcript);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -Dmse=relaxed "-Dtest=TestRewindProfilerAttribution#seekToEmitsExpectedSectionsAndStaysBalanced+TestRewindProfilerAttribution#seekToLeavesProfilerCleanWhenStepperThrows" test`
Expected: both FAIL.

- [ ] **Step 3: Wrap `seekTo`**

In `src/main/java/com/openggf/game/rewind/RewindController.java`, replace the existing `seekTo` method (lines 124-152) with:

```java
    public void seekTo(int targetFrame) {
        if (targetFrame == currentFrame) return;
        if (targetFrame < earliestAvailableFrame()) {
            targetFrame = earliestAvailableFrame();
        }
        final int clampedTarget = targetFrame;
        var floor = keyframes.latestAtOrBefore(clampedTarget).orElseThrow(
                () -> new IllegalStateException(
                        "no keyframe at or before " + clampedTarget));
        int originalFrame = currentFrame;
        if (profiler != null) profiler.beginSection("rewind.seek");
        try (AudioReplayScope ignored = beginAudioReplay(
                originalFrame, clampedTarget, AudioReplayReason.SEEK)) {
            segmentCache.invalidate();
            registry.restore(floor.snapshot());
            // registry.restore closed rewind.restore in its finally; re-open rewind.seek.
            if (profiler != null) profiler.beginSection("rewind.seek");
            currentFrame = floor.frame();
            primeStepperAtFrame(currentFrame);
            while (currentFrame < clampedTarget) {
                if (profiler != null) profiler.beginSection("rewind.tick");
                try {
                    Bk2FrameInput in = inputs.read(currentFrame + 1);
                    engineStepper.step(in);
                    currentFrame++;
                } finally {
                    if (profiler != null) profiler.endSection("rewind.tick");
                }
            }
            // After the loop, the last endSection("rewind.tick") cleared the
            // active section. Re-open rewind.seek for the audio bookkeeping tail.
            if (profiler != null) profiler.beginSection("rewind.seek");
            keyframes.discardAfter(currentFrame);
            discardAudioAfter(currentFrame);
            restoreAudioLogicalState(currentFrame);
            beginAudioFrame(currentFrame);
            primeStepperAtFrame(currentFrame);
            afterAudioRestore(AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
        } finally {
            if (profiler != null) profiler.endSection("rewind.seek");
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dmse=relaxed "-Dtest=TestRewindProfilerAttribution#seekToEmitsExpectedSectionsAndStaysBalanced+TestRewindProfilerAttribution#seekToLeavesProfilerCleanWhenStepperThrows" test`
Expected: both pass.

- [ ] **Step 5: Run all rewind tests**

Run: `mvn -Dmse=relaxed "-Dtest=com.openggf.game.rewind.**" test`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/RewindController.java src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java
git commit -m "feat: attribute RewindController.seekTo to rewind.seek + rewind.tick (exception-safe)"
```

Trailer block: `Changelog: updated`, others `n/a`.

---

## Task 7: Verify null-profiler path is null-safe

**Files:**
- Extend: `src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java`

Make sure the no-profiler constructor path runs end-to-end without NPE.

- [ ] **Step 1: Write the null-safety test**

Append to `TestRewindProfilerAttribution`:

```java
    @Test
    void rewindControllerWorksWithoutProfiler() {
        RewindRegistry reg = new RewindRegistry(); // no profiler
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();
        EngineStepper stepper = (in) -> state.incrementAndGet();
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        // Five-arg constructor (no profiler, no audio manager).
        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 5);
        for (int i = 0; i < 7; i++) rc.step();
        rc.seekTo(3);
        for (int i = 0; i < 2; i++) rc.stepBackward();
        // Reaching this line with no exception is the assertion.
    }
```

- [ ] **Step 2: Run the new test**

Run: `mvn -Dmse=relaxed "-Dtest=TestRewindProfilerAttribution#rewindControllerWorksWithoutProfiler" test`
Expected: `Tests run: 1, Failures: 0`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/game/rewind/TestRewindProfilerAttribution.java
git commit -m "test: assert RewindController null-profiler path stays NPE-free"
```

Trailer block: all `n/a`.

---

## Task 8: Wire the profiler into `GameplayModeContext`

**Files:**
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java:461-467`

`GameplayModeContext` already has a `PerformanceProfiler profiler` field (used at `:182` for `RewindRegistry`). Pass it into the new `RewindController` constructor — it auto-upcasts to `SectionProfiler`.

- [ ] **Step 1: Update the constructor call**

In `src/main/java/com/openggf/game/session/GameplayModeContext.java`, replace lines 461-467:

```java
        this.rewindController = new RewindController(
                rewindRegistry,
                new InMemoryKeyframeStore(),
                inputs,
                stepper,
                keyframeInterval,
                audioManager,
                profiler);
```

(Was: ended with `audioManager);` — now adds the trailing `, profiler);`.)

- [ ] **Step 2: Verify compile + existing tests pass**

Run: `mvn -Dmse=relaxed "-Dtest=com.openggf.game.session.*" test`
Expected: all pass.

Run: `mvn -Dmse=relaxed "-Dtest=com.openggf.game.rewind.**" test`
Expected: all pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/session/GameplayModeContext.java
git commit -m "feat: wire profiler into RewindController via GameplayModeContext"
```

Trailer block: `Changelog: updated`, others `n/a`.

---

## Task 9: Confirm `RewindBenchmark` budgets still hold

**Files:**
- Verify only: `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`

`RewindBenchmark` is gated by `@EnabledIfSystemProperty(named = "openggf.rewind.benchmark.run", matches = "true")` (`RewindBenchmark.java:44`) and needs the Sonic 2 ROM (`Sonic The Hedgehog 2 (W) (REV01) [!].gen`) in the working directory. Each new section adds ~100 ns of begin+end overhead. Worst case is `stepBackward` cold-segment expansion (60 iterations × 2 begin/end pairs ≈ 12 µs) — well under any budget.

- [ ] **Step 1: Confirm the ROM is present**

Run (PowerShell): `Test-Path -LiteralPath "Sonic The Hedgehog 2 (W) (REV01) [!].gen"`
Expected: `True`. If `False`, place the S2 ROM in the working directory before continuing.

(The `-LiteralPath` is required: PowerShell treats `[` and `]` as wildcard metacharacters under `-Path`, which would cause `Test-Path` to return the wrong result for this filename.)

- [ ] **Step 2: Run the benchmark**

Run: `mvn -Dmse=relaxed "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" test`
Expected: all phases pass their mean budgets — capture ≤ 1 ms, restore ≤ 1.5 ms, seek ≤ 10 ms, per-keyframe memory ≤ 128 KB.

If a budget is breached, the most likely culprit is an inner-loop `beginSection` that should be hoisted, or an extra begin/end pair that crept into the replay lambda. The wrappers in this plan add exactly two begin/end pairs per replay iteration (one outer for `rewind.step`/`rewind.seek`, one inner for `rewind.tick`); no additional pairs should appear.

- [ ] **Step 3: No commit needed** (verification only).

---

## Task 10: Update documentation

**Files:**
- Modify: `PERFORMANCE_OVERVIEW_20260512.md` (sections §1 and §2.4)
- Modify: `docs/guide/contributing/rewind-system.md`

- [ ] **Step 1: Update the sections-instrumented table in PERFORMANCE_OVERVIEW_20260512.md**

Append the following rows to the "Section / Site / Notes" table that starts near line 24, just below the existing `rewind.capture` row:

```markdown
| `rewind.restore` | `RewindRegistry.restore()` | Restore cost. Mirrors `rewind.capture`. Dominant on hot-segment held rewind. |
| `rewind.step` | `RewindController.stepBackward()` | Outer wrapper. Catches audio bookkeeping + segment-cache array alloc + post-restore work. Re-opens itself after inner `rewind.tick`/`rewind.capture`/`rewind.restore` sections implicitly close it. |
| `rewind.seek` | `RewindController.seekTo()` | Outer wrapper for the seek path. Same re-open pattern as `rewind.step`. Used by debug/Bk2 seek paths. |
| `rewind.tick` | `RewindController.stepBackward()` segment-expansion lambda + `RewindController.seekTo()` forward loop | Wraps each `engineStepper.step(...)` inside segment-cache cold expansion and `seekTo` forward-stepping. Surfaces the "60× game frames replayed in one visual frame" cost at backward keyframe crossings. |
```

- [ ] **Step 2: Update §2.4 "Rewind capture" paragraph**

In `PERFORMANCE_OVERVIEW_20260512.md` §2.4 ("Rewind capture"), append a paragraph after the existing storage-budget paragraph:

```markdown
**Attribution note (2026-05-26):** Held rewind no longer leaks into the
`update` umbrella section. The cold-segment expansion path is now broken
out into `rewind.tick` (the 60× `engineStepper.step` calls) plus
`rewind.capture` (the 60× `RewindRegistry.capture` calls), with
`rewind.restore` and `rewind.step`/`rewind.seek` covering the rest of
the path. Every `beginSection` has a paired `endSection` in `try/finally`,
so a thrown exception inside `engineStepper.step` or `registry.capture`
cannot leave the profiler with a dangling active section. `update` now
reports its normal prologue-only allocation during held rewind, the same
as during normal play.
```

- [ ] **Step 3: Update `docs/guide/contributing/rewind-system.md`**

Find an existing "debugging" or "profiling" section (or add a short subsection at the end). Add:

```markdown
### Performance attribution

When the debug performance overlay is enabled, the rewind hot path
appears under five sections:

- `rewind.capture` — `RewindRegistry.capture()` (snapshot bundle build).
- `rewind.restore` — `RewindRegistry.restore()` (snapshot apply).
- `rewind.step` — `RewindController.stepBackward()` outer body
  (audio bookkeeping, segment-cache array alloc, primer calls).
- `rewind.seek` — `RewindController.seekTo()` outer body.
- `rewind.tick` — each `engineStepper.step(...)` call replayed during
  segment-cache cold expansion or seek forward-stepping.

The profiler does not nest sections (`PerformanceProfiler.beginSection`
implicitly ends the active one), so `rewind.step` / `rewind.seek` are
re-opened explicitly after each inner section closes. Every `beginSection`
is paired with `endSection` in a `try/finally` so exceptions cannot leave
a dangling active section.

Production rewind code depends on the `SectionProfiler` interface
(`com.openggf.debug.SectionProfiler`), not the `PerformanceProfiler`
singleton directly — keeps tests cheap and avoids singleton coupling.
```

- [ ] **Step 4: Commit**

```bash
git add PERFORMANCE_OVERVIEW_20260512.md docs/guide/contributing/rewind-system.md
git commit -m "docs: document new rewind.* profiler sections and SectionProfiler interface"
```

Trailer block: `Changelog: updated`, `Guide: updated`, others `n/a`.

---

## Task 11: Smoke-test the overlay in a real run

**Files:**
- Verify only.

Run the engine, hold rewind, and confirm the new sections appear in the overlay.

- [ ] **Step 1: Build**

Run: `mvn -Dmse=relaxed package`
Expected: `BUILD SUCCESS`, jar at `target/OpenGGF-*-jar-with-dependencies.jar`.

- [ ] **Step 2: Run with overlay**

Launch the engine, enable the debug view + performance overlay, load any level, hold the rewind key for ~2 seconds.

Run: `java -jar target/OpenGGF-*-jar-with-dependencies.jar`

Expected: overlay top-allocators panel shows `rewind.restore`, `rewind.step`, `rewind.tick`, `rewind.capture` during held rewind (instead of `update` ballooning). `update` allocation should now look comparable to normal play.

- [ ] **Step 3: No commit needed** (verification only).

If the overlay does not show the new sections, check that `debugViewEnabled && !isNativeImage() && debugOverlayManager.isEnabled(DebugOverlayToggle.PERFORMANCE)` is all true (`Engine.java:1162-1164`) — the profiler is short-circuited otherwise.

---

## Final Verification Checklist

- [ ] All six new tests pass: `mvn -Dmse=relaxed "-Dtest=TestRewindProfilerAttribution" test`
- [ ] No regressions in rewind suite: `mvn -Dmse=relaxed "-Dtest=com.openggf.game.rewind.**" test`
- [ ] No regressions in session suite: `mvn -Dmse=relaxed "-Dtest=com.openggf.game.session.*" test`
- [ ] `RewindBenchmark` still meets its budgets (with the `openggf.rewind.benchmark.run=true` opt-in and the S2 ROM present).
- [ ] Both documentation files reflect the new sections.
- [ ] Visual overlay smoke test (Task 11) confirms `update` no longer balloons during rewind.

## Risk Summary

| Risk | Mitigation |
|------|------------|
| Overhead from added begin/end pairs blows benchmark budgets | Worst case ~12 µs added per cold-segment stepBackward; budgets have ms-level headroom. Task 9 verifies. |
| Section spam pushes other allocators off the top-5 overlay display | Five `rewind.*` sections are accurate attribution, not noise. Display already sorts dynamically. |
| Exception inside `engineStepper.step` or `registry.capture` leaves profiler with dangling active section | All wrappers use `try/finally`. Every `beginSection` is paired with `endSection` on every path. Tasks 5 and 6 include explicit throwing-stepper tests that verify `activeSection() == null` after the exception. |
| Re-opening `rewind.step` mid-method records spurious zero-cost intervals | `beginSection` records the closing section's nonzero delta (if any) and starts a fresh timestamp — no time/allocation accounting bug. Net effect: `rewind.step` allocation == sum of its open windows, which is what we want. |
| Other production code coupled to concrete `PerformanceProfiler` breaks when widened to `SectionProfiler` | Only `RewindRegistry`'s constructor parameter type changes. The only production caller (`GameplayModeContext.java:182`) passes a `PerformanceProfiler` field, which now implements `SectionProfiler` — auto-upcasts. |

## Notes for the Reviewer

- **Interface vs subclass:** The first draft of this plan used a `protected` constructor on `PerformanceProfiler` to enable test subclassing. After review feedback, switched to a tiny `SectionProfiler` interface that `PerformanceProfiler` implements. The interface has exactly two methods (the two production rewind code actually uses), avoids widening the singleton-like class's API surface, and lets the test recorder implement directly with no subclass machinery.
- **Single re-open per outer section:** `stepBackward` and `seekTo` each re-open their outer section once — after `registry.restore(snap)` / `registry.restore(floor.snapshot())` respectively — because that's the only point with measurable work to attribute between sub-sections. There's no need to re-open between `segmentCache.snapshotAt` returning and the next `registry.restore`: nothing measurable happens between them (trivial reference assignment).
- **`rewind.tick` finally on the happy path:** Inside `stepBackward`'s segment-expansion lambda, the `finally`'s `endSection("rewind.tick")` is a no-op on success because `registry.capture()` already closed the section. It only does work on the exception path. This is intentional and matches the `MemoryStats.endSection` / `PerformanceProfiler.endSection` contract: they no-op when the active section doesn't match the requested name (`PerformanceProfiler.java:193-195`).
- **Asymmetry between `stepBackward` and `seekTo` replay closure:** `stepBackward` relies on the implicit close from `registry.capture()`; `seekTo` records the delta explicitly because no capture follows. Both ultimately call `endSection("rewind.tick")` in `finally`. The asymmetry is intentional and called out in the in-method comments.
- **Ordering assertions:** Tests assert that `rewind.step` opens *before* `rewind.tick` (and similar for `rewind.seek`) using `beginNames().indexOf(...)`. Combined with the `activeSection() == null` post-condition, this catches both ordering bugs and balance bugs without coupling to exact transcript contents (which could vary if `keyframeInterval` changes).
