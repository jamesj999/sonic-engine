# Rewind Framework v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the rewind framework's core primitives + minimum-viable subsystem coverage end-to-end through the trace-replay visualiser, with held-rewind UX and a passing parity test against a real reference trace.

**Architecture:** Six interfaces in `com.openggf.game.rewind` (`RewindSnapshottable`, `CompositeSnapshot`, `RewindRegistry`, `KeyframeStore`, `InputSource`, `RewindController`) plus `PlaybackController` and `SegmentCache`. Level state uses copy-on-write snapshots on `AbstractLevel`; subsystems opt in by implementing `RewindSnapshottable` and registering with a `RewindRegistry` field on `GameplayModeContext`. Visualiser wraps the engine loop with a `PlaybackController` that flips between PLAYING / PAUSED / REWINDING.

**Tech Stack:** Java 21, Maven 3.x with MSE, JUnit 5 (Jupiter only), existing OpenGGF conventions (CLAUDE.md).

**Spec reference:** `docs/architecture/designs/2026-05-04-rewind-framework-design.md`.

**Prerequisites already landed (Plan 1):** `OscillationManager.snapshot()/restore()`, `LiveTraceComparator` per-frame observer hook, `RecordingFrameObserver` captor, 6 of 7 mutation stragglers migrated, source-scan guards, `ObjectServices.zoneLayoutMutationPipeline()`.

---

## Scope and deferrals

**In scope (this plan):**

- All framework interfaces and reference implementations (in-memory `KeyframeStore`, trace-backed `InputSource`).
- Copy-on-write snapshots on `AbstractLevel` covering `Block`, `Chunk`, `Map`. (Pattern bytes are derived state — not snapshotted.)
- `RewindSnapshottable` for the GameplayModeContext-owned atomic-state subsystems: `Camera`, `GameStateManager`, `GameRng`, `TimerManager`, `FadeManager`, `OscillationManager` (static-state adapter using D.1 helpers).
- Composite `ObjectManagerSnapshot` covering slot inventory + per-instance state.
- Visualiser integration: held-rewind at framerate, paused single-step both directions, buffer-end signal.
- `SegmentCache` between `RewindController` and `KeyframeStore` for O(1)-amortised held-rewind.
- Unit tests per `RewindSnapshottable`, level CoW round-trip, `PlaybackController` state-machine, parity test against a single S2 reference trace.

**Deferred to a follow-up plan ("rewind framework v1 — comprehensive coverage"):**

- `RewindSnapshottable` for `ParallaxManager`, `WaterSystem`, `SolidExecutionRegistry`, `ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`, `AnimatedTileChannelGraph`, `SpecialRenderEffectRegistry`, `AdvancedRenderModeController`, `ZoneLayoutMutationPipeline`.
- Animator counter snapshots (`AniPlcScriptState`, `Sonic1PatternAnimator`, `Sonic3kPatternAnimator`).
- Per-game PLC art registry snapshots (`Sonic2PlcArtRegistry`, `Sonic3kPlcArtRegistry`).
- `AbstractLevelEventManager` per-game subclass snapshots.
- `RingManager` / `LostRingPool` composite snapshot.
- Object identity rebinding tests.
- Act-boundary / warp parity tests.
- `RewindBenchmark` headless perf harness with the long-tail determinism gate.
- No-redraw mutation API on `ZoneLayoutMutationPipeline` (unblocks migrating `Sonic3kMGZEvents` off the A.8 allow-list).

The deferred items are an additive coverage extension — they don't change the framework's shape, only the surface area of what survives a rewind.

---

## File structure

| Path | Responsibility |
|---|---|
| `src/main/java/com/openggf/game/rewind/RewindSnapshottable.java` | Generic interface `RewindSnapshottable<S>` with `key()` / `capture()` / `restore(S)`. |
| `src/main/java/com/openggf/game/rewind/CompositeSnapshot.java` | Immutable record holding `Map<String, Object>` of per-subsystem snapshots in registration order. |
| `src/main/java/com/openggf/game/rewind/RewindRegistry.java` | Holds the list of registered `RewindSnapshottable<?>`s, performs composite capture/restore. |
| `src/main/java/com/openggf/game/rewind/KeyframeStore.java` | Interface: `put(int frame, CompositeSnapshot)`, `latestAtOrBefore(int frame)`, `earliestFrame()`. |
| `src/main/java/com/openggf/game/rewind/InMemoryKeyframeStore.java` | `TreeMap<Integer, CompositeSnapshot>` reference impl. |
| `src/main/java/com/openggf/game/rewind/InputSource.java` | Interface: `read(int frame) → Bk2FrameInput`, `frameCount()`. |
| `src/main/java/com/openggf/game/rewind/TraceInputSource.java` | Reads inputs from an existing `TraceData`. |
| `src/main/java/com/openggf/game/rewind/SegmentCache.java` | Strip cache: expand a `[K, K+interval)` segment on demand by stepping forward from K capturing per-frame snapshots. |
| `src/main/java/com/openggf/game/rewind/RewindController.java` | Drives forward step + seek + segment cache. |
| `src/main/java/com/openggf/game/rewind/PlaybackController.java` | PLAYING/PAUSED/REWINDING state machine. |
| `src/main/java/com/openggf/game/rewind/snapshot/CameraSnapshot.java` | Record: x, y, lock state, target ref id. |
| `src/main/java/com/openggf/game/rewind/snapshot/GameStateSnapshot.java` | Record: rings, score, lives, time, emeralds. |
| `src/main/java/com/openggf/game/rewind/snapshot/GameRngSnapshot.java` | Record: seed (long). |
| `src/main/java/com/openggf/game/rewind/snapshot/TimerManagerSnapshot.java` | Record: per-timer state list. |
| `src/main/java/com/openggf/game/rewind/snapshot/FadeManagerSnapshot.java` | Record: phase, intensity, target palette refs. |
| `src/main/java/com/openggf/game/rewind/snapshot/OscillationStaticSnapshot.java` | Wrapper that composes `OscillationSnapshot` (already in `com.openggf.game`). |
| `src/main/java/com/openggf/game/rewind/snapshot/ObjectManagerSnapshot.java` | Composite: slot table, per-slot serialised object state, child-spawn descriptors. |
| `src/main/java/com/openggf/game/rewind/snapshot/LevelSnapshot.java` | Composite: snapshot epoch + Block[]/Chunk[]/Map references. |
| Plus: `Camera`, `GameStateManager`, `GameRng`, `TimerManager`, `FadeManager`, `LevelManager`, `ObjectManager`, `AbstractLevel` each gain a `RewindSnapshottable`-implementing inner adapter or direct interface impl. | |
| `src/main/java/com/openggf/level/AbstractLevel.java` | Adds `snapshotEpoch` field, `bumpEpoch()`, `currentEpoch()` accessors, level-snapshot capture/restore. |
| `src/main/java/com/openggf/level/Block.java` | Adds `lastTouchedEpoch`, `cowEnsureWritable(int currentEpoch)`. |
| `src/main/java/com/openggf/level/Chunk.java` | Same. |
| `src/main/java/com/openggf/level/Map.java` | Same. |
| `src/main/java/com/openggf/game/session/GameplayModeContext.java` | Adds `RewindRegistry` field + accessor; created at construction, torn down with the context. |
| `src/main/java/com/openggf/debug/playback/...` (visualiser glue) | Plumbs `PlaybackController` into the trace-replay UX. Held-rewind keybinding, single-step keybindings. |
| `src/test/java/com/openggf/game/rewind/...` | Unit tests per subsystem snapshot, `RewindRegistry` composite, `InMemoryKeyframeStore`, `SegmentCache`, `PlaybackController` state machine, level CoW round-trip, **keystone parity test using `RecordingFrameObserver`**. |

---

## Track A — Framework primitives

7 tasks. All in `com.openggf.game.rewind`. Each is small (one interface or one impl class + tests). TDD throughout.

### Task A.1 — `RewindSnapshottable` interface + `CompositeSnapshot` record

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/RewindSnapshottable.java`
- Create: `src/main/java/com/openggf/game/rewind/CompositeSnapshot.java`
- Test: `src/test/java/com/openggf/game/rewind/TestCompositeSnapshot.java` (create)

- [ ] **Step 1: Write failing test:**

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class TestCompositeSnapshot {

    @Test
    void preservesInsertionOrder() {
        var entries = new LinkedHashMap<String, Object>();
        entries.put("camera", "c1");
        entries.put("rng", "r1");
        entries.put("game-state", "gs1");
        CompositeSnapshot cs = new CompositeSnapshot(entries);
        assertEquals(java.util.List.of("camera", "rng", "game-state"),
                java.util.List.copyOf(cs.entries().keySet()));
    }

    @Test
    void rejectsNullEntries() {
        assertThrows(NullPointerException.class,
                () -> new CompositeSnapshot(null));
    }

    @Test
    void entriesViewIsImmutable() {
        var entries = new LinkedHashMap<String, Object>();
        entries.put("k", "v");
        CompositeSnapshot cs = new CompositeSnapshot(entries);
        assertThrows(UnsupportedOperationException.class,
                () -> cs.entries().put("x", "y"));
    }

    @Test
    void getReturnsTypedValue() {
        var entries = new LinkedHashMap<String, Object>();
        entries.put("camera", new Object());
        CompositeSnapshot cs = new CompositeSnapshot(entries);
        Object v = cs.get("camera");
        assertNotNull(v);
        assertNull(cs.get("missing"));
    }
}
```

- [ ] **Step 2: Confirm compile failure**: `mvn test -Dtest=TestCompositeSnapshot -q` → fails (no class).

- [ ] **Step 3: Implement `RewindSnapshottable.java`:**

```java
package com.openggf.game.rewind;

/**
 * A subsystem snapshot contract used by {@link RewindRegistry} and
 * {@link RewindController}.
 *
 * <p>Implementations capture an immutable snapshot of their state and
 * restore from one. The snapshot type {@code S} is typically a record
 * with primitive / immutable fields; opaque {@code Object} payloads are
 * also valid.
 *
 * <p>{@link #key()} must be stable across captures of the same
 * subsystem — it is used as the key into {@link CompositeSnapshot}.
 */
public interface RewindSnapshottable<S> {
    String key();
    S capture();
    void restore(S snapshot);
}
```

- [ ] **Step 4: Implement `CompositeSnapshot.java`:**

```java
package com.openggf.game.rewind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable composite of per-subsystem snapshots, keyed by
 * {@link RewindSnapshottable#key()}, in registration order. Returned by
 * {@link RewindRegistry#capture()} and consumed by
 * {@link RewindRegistry#restore(CompositeSnapshot)}.
 */
public final class CompositeSnapshot {

    private final Map<String, Object> entries;

    public CompositeSnapshot(Map<String, Object> entries) {
        Objects.requireNonNull(entries, "entries");
        // Defensive copy preserves insertion order via LinkedHashMap.
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public Map<String, Object> entries() {
        return entries;
    }

    public Object get(String key) {
        return entries.get(key);
    }
}
```

- [ ] **Step 5: Confirm test passes**: `mvn test -Dtest=TestCompositeSnapshot -q`.

- [ ] **Step 6: Commit:**

```
git add src/main/java/com/openggf/game/rewind/RewindSnapshottable.java \
        src/main/java/com/openggf/game/rewind/CompositeSnapshot.java \
        src/test/java/com/openggf/game/rewind/TestCompositeSnapshot.java
git commit -m "$(cat <<'EOF'
feat(rewind): add RewindSnapshottable interface and CompositeSnapshot record

Foundation contracts for the rewind framework. Subsystems implement
RewindSnapshottable<S> to opt in to rewind-aware capture/restore.
CompositeSnapshot is an immutable LinkedHashMap-backed bundle of
per-subsystem snapshots keyed by RewindSnapshottable.key(), preserving
registration order so captures and restores are deterministic.

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

### Task A.2 — `RewindRegistry`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/RewindRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindRegistry.java` (create)

- [ ] **Step 1: Write failing test:**

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestRewindRegistry {

    private static RewindSnapshottable<Integer> intSnap(String key, AtomicInteger ref) {
        return new RewindSnapshottable<>() {
            @Override public String key() { return key; }
            @Override public Integer capture() { return ref.get(); }
            @Override public void restore(Integer s) { ref.set(s); }
        };
    }

    @Test
    void captureWalksRegistrationOrder() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger a = new AtomicInteger(1), b = new AtomicInteger(2);
        reg.register(intSnap("a", a));
        reg.register(intSnap("b", b));
        CompositeSnapshot cs = reg.capture();
        assertEquals(java.util.List.of("a", "b"),
                java.util.List.copyOf(cs.entries().keySet()));
        assertEquals(1, cs.get("a"));
        assertEquals(2, cs.get("b"));
    }

    @Test
    void restoreAppliesEachSnapshot() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger a = new AtomicInteger(1);
        reg.register(intSnap("a", a));
        CompositeSnapshot cs = reg.capture();
        a.set(99);
        reg.restore(cs);
        assertEquals(1, a.get());
    }

    @Test
    void deregisterRemovesSubsystem() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger a = new AtomicInteger(1);
        reg.register(intSnap("a", a));
        reg.deregister("a");
        CompositeSnapshot cs = reg.capture();
        assertTrue(cs.entries().isEmpty());
    }

    @Test
    void duplicateKeyRejected() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger a = new AtomicInteger();
        reg.register(intSnap("dup", a));
        assertThrows(IllegalStateException.class,
                () -> reg.register(intSnap("dup", a)));
    }

    @Test
    void restoreOnUnknownKeyIsTolerated() {
        // If a snapshot has a key that's not registered (e.g. subsystem
        // was removed since capture), restore should silently skip it.
        RewindRegistry reg = new RewindRegistry();
        var entries = new java.util.LinkedHashMap<String, Object>();
        entries.put("ghost", 42);
        reg.restore(new CompositeSnapshot(entries));
        // No exception — pass.
    }
}
```

- [ ] **Step 2: Confirm compile failure**.

- [ ] **Step 3: Implement `RewindRegistry.java`:**

```java
package com.openggf.game.rewind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the list of {@link RewindSnapshottable} subsystems for the
 * current gameplay session. Owned by {@code GameplayModeContext}.
 *
 * <p>Capture and restore are atomic per frame: no subsystem is mid-step
 * during these operations, so registration order does not affect
 * correctness. Order is preserved for predictable diffing during
 * debugging.
 *
 * <p>Restore is tolerant of unknown keys (a subsystem that was
 * registered when a snapshot was captured may have been deregistered
 * since); such entries are skipped. The reverse — registered subsystems
 * with no entry in the snapshot — leaves them at their current state.
 */
public final class RewindRegistry {

    private final Map<String, RewindSnapshottable<?>> entries = new LinkedHashMap<>();

    public void register(RewindSnapshottable<?> s) {
        Objects.requireNonNull(s, "s");
        if (entries.putIfAbsent(s.key(), s) != null) {
            throw new IllegalStateException(
                    "RewindSnapshottable already registered: " + s.key());
        }
    }

    public void deregister(String key) {
        entries.remove(key);
    }

    public CompositeSnapshot capture() {
        var bundle = new LinkedHashMap<String, Object>(entries.size());
        for (var e : entries.entrySet()) {
            bundle.put(e.getKey(), e.getValue().capture());
        }
        return new CompositeSnapshot(bundle);
    }

    public void restore(CompositeSnapshot cs) {
        Objects.requireNonNull(cs, "cs");
        for (var e : entries.entrySet()) {
            Object snap = cs.get(e.getKey());
            if (snap == null) continue;
            @SuppressWarnings({"rawtypes", "unchecked"})
            RewindSnapshottable raw = e.getValue();
            raw.restore(snap);
        }
    }
}
```

- [ ] **Step 4: Confirm tests pass**.

- [ ] **Step 5: Commit** (use the standard trailer block ending with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`):

```
git add src/main/java/com/openggf/game/rewind/RewindRegistry.java \
        src/test/java/com/openggf/game/rewind/TestRewindRegistry.java
git commit -m "$(cat <<'EOF'
feat(rewind): add RewindRegistry for composite capture/restore

Holds RewindSnapshottable subsystems in registration order. capture()
and restore(CompositeSnapshot) walk the list and produce/consume an
immutable CompositeSnapshot. Restore is tolerant of unknown keys for
subsystem-lifecycle tolerance. Duplicate keys are rejected.

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

### Task A.3 — `KeyframeStore` interface + `InMemoryKeyframeStore`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/KeyframeStore.java`
- Create: `src/main/java/com/openggf/game/rewind/InMemoryKeyframeStore.java`
- Test: `src/test/java/com/openggf/game/rewind/TestInMemoryKeyframeStore.java` (create)

- [ ] **Step 1: Write failing test:**

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestInMemoryKeyframeStore {

    private static CompositeSnapshot snap(int marker) {
        var entries = new LinkedHashMap<String, Object>();
        entries.put("marker", marker);
        return new CompositeSnapshot(entries);
    }

    @Test
    void putAndLatestAtOrBefore() {
        InMemoryKeyframeStore s = new InMemoryKeyframeStore();
        s.put(0, snap(0));
        s.put(60, snap(60));
        s.put(120, snap(120));
        assertEquals(60, s.latestAtOrBefore(75).get().frame());
        assertEquals(60, s.latestAtOrBefore(60).get().frame());
        assertEquals(0, s.latestAtOrBefore(0).get().frame());
        assertTrue(s.latestAtOrBefore(-1).isEmpty());
        assertEquals(120, s.latestAtOrBefore(99999).get().frame());
    }

    @Test
    void earliestFrame() {
        InMemoryKeyframeStore s = new InMemoryKeyframeStore();
        assertEquals(-1, s.earliestFrame());
        s.put(60, snap(60));
        assertEquals(60, s.earliestFrame());
        s.put(0, snap(0));
        assertEquals(0, s.earliestFrame());
    }

    @Test
    void putReplacesExistingEntry() {
        InMemoryKeyframeStore s = new InMemoryKeyframeStore();
        s.put(60, snap(1));
        s.put(60, snap(2));
        assertEquals(2, ((Integer) s.latestAtOrBefore(60).get().snapshot().get("marker")));
    }
}
```

- [ ] **Step 2: Implement `KeyframeStore.java` (interface) and `InMemoryKeyframeStore.java`:**

```java
package com.openggf.game.rewind;

import java.util.Optional;

/**
 * Frame-keyed snapshot store. v1 keeps captures in memory; v2 will swap
 * in a bounded ring + disk spill backing.
 */
public interface KeyframeStore {

    /** Stores a snapshot at the given frame, replacing any existing one. */
    void put(int frame, CompositeSnapshot snapshot);

    /**
     * Returns the latest stored entry at frame F or earlier, or empty if
     * no entry is at or before F.
     */
    Optional<Entry> latestAtOrBefore(int frame);

    /** Earliest-stored frame, or {@code -1} if empty. */
    int earliestFrame();

    /** Stored entry record. */
    record Entry(int frame, CompositeSnapshot snapshot) {}
}
```

```java
package com.openggf.game.rewind;

import java.util.Optional;
import java.util.TreeMap;

/**
 * TreeMap-backed in-memory KeyframeStore for v1.
 */
public final class InMemoryKeyframeStore implements KeyframeStore {

    private final TreeMap<Integer, CompositeSnapshot> entries = new TreeMap<>();

    @Override
    public void put(int frame, CompositeSnapshot snapshot) {
        entries.put(frame, snapshot);
    }

    @Override
    public Optional<Entry> latestAtOrBefore(int frame) {
        var floor = entries.floorEntry(frame);
        return floor == null
                ? Optional.empty()
                : Optional.of(new Entry(floor.getKey(), floor.getValue()));
    }

    @Override
    public int earliestFrame() {
        return entries.isEmpty() ? -1 : entries.firstKey();
    }
}
```

- [ ] **Step 3: Confirm tests pass and commit** (trailer block as above).

---

### Task A.4 — `InputSource` interface + `TraceInputSource`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/InputSource.java`
- Create: `src/main/java/com/openggf/game/rewind/TraceInputSource.java`
- Test: `src/test/java/com/openggf/game/rewind/TestTraceInputSource.java` (create)

- [ ] **Step 1: Inspect `Bk2FrameInput` and `TraceData` interfaces** to confirm the read API. Read `src/main/java/com/openggf/debug/playback/Bk2FrameInput.java` and `src/main/java/com/openggf/trace/TraceData.java`.

- [ ] **Step 2: Write the interface:**

```java
package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;

/**
 * Per-frame input source. v1 reads from a recorded trace; v2 will swap
 * in a live recorder that captures user inputs each frame.
 */
public interface InputSource {

    /** Total number of available frames. */
    int frameCount();

    /** Inputs for the given frame. Caller must respect 0 ≤ frame < frameCount(). */
    Bk2FrameInput read(int frame);
}
```

- [ ] **Step 3: Implement `TraceInputSource`:**

```java
package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;

import java.util.Objects;

/** Reads inputs frame-by-frame from a {@link TraceData}. */
public final class TraceInputSource implements InputSource {

    private final TraceData trace;

    public TraceInputSource(TraceData trace) {
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    @Override
    public int frameCount() {
        return trace.frameCount();
    }

    @Override
    public Bk2FrameInput read(int frame) {
        TraceFrame tf = trace.getFrame(frame);
        // TraceFrame already exposes the Bk2-style input mask; adapt as
        // needed to match Bk2FrameInput's actual constructor / factory.
        return Bk2FrameInput.fromTraceFrame(tf);
    }
}
```

> If `Bk2FrameInput.fromTraceFrame` doesn't exist, the existing `LiveTraceComparator` flow shows how the engine synthesises a `Bk2FrameInput` for trace replay — port that conversion here. If it requires `TraceMetadata` or other context, take it as a constructor arg.

- [ ] **Step 4: Write a small round-trip test** (using a stub `TraceData` with 2 frames, read each, assert the returned `Bk2FrameInput` matches expectations). Use Mockito if it's already a project dep; otherwise build a minimal anonymous `TraceData` impl.

- [ ] **Step 5: Confirm tests pass and commit.**

---

### Task A.5 — `SegmentCache`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/SegmentCache.java`
- Test: `src/test/java/com/openggf/game/rewind/TestSegmentCache.java` (create)

This is a strip cache: when a held-rewind enters segment `[K, K+interval)`, expand it once by stepping forward from K, capturing per-frame snapshots into an in-memory array. Backward steps within the segment become O(1) array lookups.

For v1 the segment cache is invoked by `RewindController` during `stepBackward()`; expansion calls a `Stepper` callback that runs the engine forward one frame.

- [ ] **Step 1: Define `Stepper` interface and `SegmentCache`:**

```java
package com.openggf.game.rewind;

/**
 * Strip cache between RewindController and KeyframeStore. Expands one
 * segment of {@code intervalFrames} on demand by stepping forward from
 * a keyframe and capturing per-frame snapshots. Subsequent backward
 * steps within the expanded segment are O(1) array lookups.
 *
 * <p>v1 keeps at most one expanded segment ("currentSegment"); a
 * follow-up plan will extend this to a small ring of expanded segments
 * around the rewind cursor.
 */
public final class SegmentCache {

    /** Drives the engine forward one frame and returns a fresh snapshot. */
    @FunctionalInterface
    public interface Stepper {
        CompositeSnapshot stepAndCapture();
    }

    private final int intervalFrames;
    private int currentBaseFrame = -1;
    private CompositeSnapshot[] strip = null;
    private int validUpTo = -1;   // strip[validUpTo] is the last valid entry

    public SegmentCache(int intervalFrames) {
        if (intervalFrames <= 0) {
            throw new IllegalArgumentException(
                    "intervalFrames must be > 0, got " + intervalFrames);
        }
        this.intervalFrames = intervalFrames;
    }

    /** Drops any currently-cached segment. */
    public void invalidate() {
        currentBaseFrame = -1;
        strip = null;
        validUpTo = -1;
    }

    /**
     * Returns the snapshot at frame F, expanding segment [K, K+interval)
     * (where K = (F / interval) * interval) if necessary. If F lies in a
     * different segment than the currently-cached one, the cache is
     * dropped and re-expanded from the new segment's keyframe (using
     * {@code restoreKeyframe} to bring the engine back to K, then
     * {@code stepper} to advance).
     */
    public CompositeSnapshot snapshotAt(
            int frame,
            CompositeSnapshot keyframeAt,   // base keyframe of segment containing F
            int keyframeFrame,
            Runnable restoreKeyframe,       // restores engine state from keyframeAt
            Stepper stepper) {
        if (frame < keyframeFrame) {
            throw new IllegalArgumentException(
                    "frame " + frame + " < keyframe " + keyframeFrame);
        }
        // If we've cached this segment already, lookup is O(1).
        if (currentBaseFrame == keyframeFrame
                && strip != null
                && (frame - keyframeFrame) <= validUpTo) {
            return strip[frame - keyframeFrame];
        }
        // Otherwise expand the segment.
        currentBaseFrame = keyframeFrame;
        strip = new CompositeSnapshot[intervalFrames];
        strip[0] = keyframeAt;
        validUpTo = 0;
        restoreKeyframe.run();
        for (int offset = 1; offset <= (frame - keyframeFrame); offset++) {
            strip[offset] = stepper.stepAndCapture();
            validUpTo = offset;
        }
        return strip[frame - keyframeFrame];
    }
}
```

- [ ] **Step 2: Write tests:**

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestSegmentCache {

    private static CompositeSnapshot snap(int marker) {
        var e = new LinkedHashMap<String, Object>();
        e.put("marker", marker);
        return new CompositeSnapshot(e);
    }

    @Test
    void firstAccessExpandsSegmentAndCachesIt() {
        SegmentCache cache = new SegmentCache(60);
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        SegmentCache.Stepper stepper = () -> snap(steps.incrementAndGet());

        // K=0, request frame 5: expand segment [0, 60) up to offset 5
        var got = cache.snapshotAt(5, snap(0), 0,
                restores::incrementAndGet, stepper);
        assertEquals(1, restores.get(), "must restore keyframe on cold expand");
        assertEquals(5, steps.get(), "must step 5 frames forward (1..5)");
        assertEquals(5, got.get("marker"));
    }

    @Test
    void secondAccessSameSegmentIsCacheHit() {
        SegmentCache cache = new SegmentCache(60);
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        SegmentCache.Stepper stepper = () -> snap(steps.incrementAndGet());

        cache.snapshotAt(5, snap(0), 0, restores::incrementAndGet, stepper);
        // request frame 3 — already cached, no re-expansion
        var got = cache.snapshotAt(3, snap(0), 0, restores::incrementAndGet, stepper);
        assertEquals(1, restores.get(), "no re-expand on cached frame");
        assertEquals(5, steps.get(), "no extra steps");
        assertEquals(3, got.get("marker"));
    }

    @Test
    void crossingSegmentBoundaryRebuilds() {
        SegmentCache cache = new SegmentCache(60);
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        SegmentCache.Stepper stepper = () -> snap(steps.incrementAndGet());

        // Segment 1 [0, 60): expand up to 30
        cache.snapshotAt(30, snap(0), 0, restores::incrementAndGet, stepper);
        int stepsAfterSeg1 = steps.get();
        // Segment 2 [60, 120): expand up to 75
        cache.snapshotAt(75, snap(60), 60, restores::incrementAndGet, stepper);
        assertEquals(2, restores.get(), "second segment requires keyframe restore");
        assertEquals(stepsAfterSeg1 + 15, steps.get(), "stepped forward to offset 15");
    }

    @Test
    void invalidateForcesNextAccessToRebuild() {
        SegmentCache cache = new SegmentCache(60);
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        SegmentCache.Stepper stepper = () -> snap(steps.incrementAndGet());

        cache.snapshotAt(5, snap(0), 0, restores::incrementAndGet, stepper);
        cache.invalidate();
        cache.snapshotAt(5, snap(0), 0, restores::incrementAndGet, stepper);
        assertEquals(2, restores.get());
    }
}
```

- [ ] **Step 3: Confirm tests pass and commit.**

---

### Task A.6 — `RewindController`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/RewindController.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindController.java` (create)

The controller drives forward stepping, keyframe capture, and seeking. It composes `RewindRegistry`, `KeyframeStore`, `InputSource`, and `SegmentCache`.

- [ ] **Step 1: Define the engine-step abstraction first** (so tests don't depend on a real engine):

```java
package com.openggf.game.rewind;

/**
 * Drives the engine forward one frame using the given inputs. Owned by
 * the visualiser / engine glue, passed into RewindController.
 */
@FunctionalInterface
public interface EngineStepper {
    void step(com.openggf.debug.playback.Bk2FrameInput inputs);
}
```

- [ ] **Step 2: Implement the controller:**

```java
package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;

import java.util.Objects;

public final class RewindController {

    private final RewindRegistry registry;
    private final KeyframeStore keyframes;
    private final InputSource inputs;
    private final EngineStepper engineStepper;
    private final SegmentCache segmentCache;
    private final int keyframeInterval;

    private int currentFrame;

    public RewindController(
            RewindRegistry registry,
            KeyframeStore keyframes,
            InputSource inputs,
            EngineStepper engineStepper,
            int keyframeInterval) {
        this.registry = Objects.requireNonNull(registry);
        this.keyframes = Objects.requireNonNull(keyframes);
        this.inputs = Objects.requireNonNull(inputs);
        this.engineStepper = Objects.requireNonNull(engineStepper);
        if (keyframeInterval <= 0) {
            throw new IllegalArgumentException(
                    "keyframeInterval must be > 0, got " + keyframeInterval);
        }
        this.keyframeInterval = keyframeInterval;
        this.segmentCache = new SegmentCache(keyframeInterval);
        this.currentFrame = 0;
        // Capture frame 0 so seekTo(0) always has a base.
        keyframes.put(0, registry.capture());
    }

    public int currentFrame() { return currentFrame; }

    public int earliestAvailableFrame() {
        // v1: trace mode — earliest accessible frame is whatever the
        // earliest stored keyframe is (typically 0).
        int e = keyframes.earliestFrame();
        return e < 0 ? 0 : e;
    }

    /** Steps forward one frame, capturing a keyframe at the boundary. */
    public void step() {
        if (currentFrame + 1 >= inputs.frameCount()) {
            return;   // end of trace
        }
        Bk2FrameInput in = inputs.read(currentFrame + 1);
        engineStepper.step(in);
        currentFrame++;
        if (currentFrame % keyframeInterval == 0) {
            keyframes.put(currentFrame, registry.capture());
        }
    }

    /**
     * Seeks to {@code targetFrame} by restoring the latest keyframe at or
     * before it, then stepping forward. Held-rewind callers should use
     * {@link #stepBackward()} for steady-state O(1) cost.
     */
    public void seekTo(int targetFrame) {
        if (targetFrame == currentFrame) return;
        if (targetFrame < earliestAvailableFrame()) {
            targetFrame = earliestAvailableFrame();
        }
        var floor = keyframes.latestAtOrBefore(targetFrame).orElseThrow(
                () -> new IllegalStateException(
                        "no keyframe at or before " + targetFrame));
        segmentCache.invalidate();
        registry.restore(floor.snapshot());
        currentFrame = floor.frame();
        while (currentFrame < targetFrame) {
            Bk2FrameInput in = inputs.read(currentFrame + 1);
            engineStepper.step(in);
            currentFrame++;
        }
    }

    /**
     * Rewinds one frame using the segment cache for amortised O(1) cost.
     * Returns false if already at {@code earliestAvailableFrame}.
     */
    public boolean stepBackward() {
        if (currentFrame <= earliestAvailableFrame()) return false;
        int target = currentFrame - 1;
        int keyframeFrame = (target / keyframeInterval) * keyframeInterval;
        var floor = keyframes.latestAtOrBefore(keyframeFrame).orElseThrow();
        CompositeSnapshot snap = segmentCache.snapshotAt(
                target,
                floor.snapshot(),
                floor.frame(),
                () -> {
                    registry.restore(floor.snapshot());
                    currentFrame = floor.frame();
                },
                () -> {
                    Bk2FrameInput in = inputs.read(currentFrame + 1);
                    engineStepper.step(in);
                    currentFrame++;
                    return registry.capture();
                });
        registry.restore(snap);
        currentFrame = target;
        return true;
    }
}
```

- [ ] **Step 3: Write tests with stub registry / keyframe store / input source / stepper.** Cover:
  - Step forward N times, seekTo intermediate frame, verify state restores correctly.
  - stepBackward through a segment boundary.
  - earliestAvailableFrame clamping.
  - Sequential stepBackward calls cost O(1) inside a segment (count step invocations).

- [ ] **Step 4: Confirm tests pass and commit.**

---

### Task A.7 — `PlaybackController`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/PlaybackController.java`
- Test: `src/test/java/com/openggf/game/rewind/TestPlaybackController.java` (create)

- [ ] **Step 1: Implement** — small state machine wrapping `RewindController`:

```java
package com.openggf.game.rewind;

import java.util.Objects;
import java.util.function.Consumer;

public final class PlaybackController {

    public enum State { PLAYING, PAUSED, REWINDING }

    private final RewindController rc;
    private final Consumer<State> stateObserver;
    private State state = State.PLAYING;

    public PlaybackController(RewindController rc) {
        this(rc, s -> {});
    }

    public PlaybackController(RewindController rc, Consumer<State> stateObserver) {
        this.rc = Objects.requireNonNull(rc);
        this.stateObserver = Objects.requireNonNull(stateObserver);
    }

    public State state() { return state; }

    public void play()    { setState(State.PLAYING); }
    public void pause()   { setState(State.PAUSED); }
    public void rewind()  { setState(State.REWINDING); }

    private void setState(State s) {
        if (s == state) return;
        state = s;
        stateObserver.accept(s);
    }

    /** Called once per visualiser frame at the configured framerate. */
    public void tick() {
        switch (state) {
            case PLAYING -> rc.step();
            case REWINDING -> {
                if (!rc.stepBackward()) {
                    // Hit buffer start — auto-pause.
                    setState(State.PAUSED);
                }
            }
            case PAUSED -> { /* no-op */ }
        }
    }

    public void stepForwardOnce() {
        State prev = state;
        rc.step();
        if (prev != State.PAUSED) setState(State.PAUSED);
    }

    public void stepBackwardOnce() {
        State prev = state;
        if (!rc.stepBackward()) {
            setState(State.PAUSED);
        } else if (prev != State.PAUSED) {
            setState(State.PAUSED);
        }
    }
}
```

- [ ] **Step 2: Write tests covering all state transitions** — PLAYING/PAUSED/REWINDING, single-step in either direction, auto-pause on buffer start. Use a stub `RewindController` (extract a small interface or use a test double).

- [ ] **Step 3: Confirm tests pass and commit.**

---

## Track B — Level CoW snapshots on `AbstractLevel`

5 tasks. The level snapshot is the heaviest single subsystem — copy-on-write avoids paying full-snapshot cost per keyframe.

### Task B.1 — Add `snapshotEpoch` to `AbstractLevel`

**Files:**
- Modify: `src/main/java/com/openggf/level/AbstractLevel.java`
- Test: `src/test/java/com/openggf/level/TestAbstractLevelEpoch.java` (create)

- [ ] **Step 1: Read `AbstractLevel.java`** — identify where to place the epoch field. It should be a `long`, initialised to `0`. Add `bumpEpoch()` and `currentEpoch()` accessors. The field is private; `currentEpoch()` is package-private so `Block` / `Chunk` / `Map` (in the same package) can read it without exposing it publicly.

- [ ] **Step 2: Write the test** — assert that `bumpEpoch()` strictly increases the value, never wraps to negative.

- [ ] **Step 3: Implement** the field + accessors. Commit.

---

### Task B.2 — `cowEnsureWritable` on `Block`

**Files:**
- Modify: `src/main/java/com/openggf/level/Block.java`
- Test: `src/test/java/com/openggf/level/TestBlockCoW.java` (create)

- [ ] **Step 1: Inspect `Block.java`** — note the backing array (`int[]` of `ChunkDesc.get()` values) and every mutator that writes to it (`setChunkDesc`, `restoreState`, `fromSegaFormat`).

- [ ] **Step 2: Write tests:**

```java
@Test
void cowClonesArrayOnFirstWriteSinceEpochBump() {
    Block b = new Block();
    b.setChunkDesc(0, 0xABCD);
    int[] beforeArray = b.chunkDescsArrayForTest();   // package-private accessor
    b.cowEnsureWritable(/*currentEpoch=*/ 1L);
    int[] afterArray = b.chunkDescsArrayForTest();
    // Future writes don't touch `beforeArray`.
    b.setChunkDesc(0, 0x9999);
    assertEquals(0xABCD, beforeArray[0]);
    assertEquals(0x9999, afterArray[0]);
}

@Test
void cowDoesNotCloneWithinSameEpoch() {
    Block b = new Block();
    b.cowEnsureWritable(1L);
    int[] arr1 = b.chunkDescsArrayForTest();
    b.cowEnsureWritable(1L);
    int[] arr2 = b.chunkDescsArrayForTest();
    assertSame(arr1, arr2);
}
```

(`chunkDescsArrayForTest()` is a new package-private accessor returning the live array reference for assertion purposes — annotate with `@VisibleForTesting`-style javadoc.)

- [ ] **Step 3: Implement** `cowEnsureWritable(long currentEpoch)` and `lastTouchedEpoch` field. Update every mutator in `Block` to call `cowEnsureWritable(...)` first, passing the current level's epoch (which the mutator can reach via the `level` reference if `Block` holds one, or via a passed-in epoch parameter — pick whichever is cleanest).

  > If `Block` doesn't currently hold a back-reference to its `AbstractLevel`, **don't add one** for CoW alone — instead change the mutator signatures to take a `long currentEpoch` parameter, and have `AbstractLevel`-level mutators (called by gameplay code via the pipeline) pass `this.currentEpoch()`. This keeps `Block` decoupled from `AbstractLevel`.

- [ ] **Step 4: Confirm tests pass.** Run `mvn test "-Dtest=TestBlockCoW"`. Run the broader level test suite to confirm no regressions.

- [ ] **Step 5: Commit.**

---

### Task B.3 — `cowEnsureWritable` on `Chunk`

**Files:**
- Modify: `src/main/java/com/openggf/level/Chunk.java`
- Test: `src/test/java/com/openggf/level/TestChunkCoW.java` (create)

Same shape as Task B.2. `Chunk.saveState()` returns 6 ints (4 patternDescs + 2 solid indices). The CoW affects the patternDesc and solid-index arrays.

- [ ] **Steps 1-5:** mirror Task B.2 against `Chunk` mutators (`setPatternDesc`, `setSolidTileIndex`, `setSolidTileAltIndex`, `restoreState`, `fromSegaFormat`).

---

### Task B.4 — `cowEnsureWritable` on `Map`

**Files:**
- Modify: `src/main/java/com/openggf/level/Map.java`
- Test: `src/test/java/com/openggf/level/TestMapCoW.java` (create)

Same shape. `Map.setValue(layer, x, y, byte)` is the only mutator. Internal storage is layered byte arrays per `Map.getData()`. CoW clones the affected layer's array on first write per epoch.

- [ ] **Steps 1-5:** mirror Task B.2 against `Map.setValue`.

---

### Task B.5 — `LevelManager.RewindSnapshottable` adapter

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/LevelSnapshot.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java` (or a new sibling file `LevelManagerRewindSnapshottable.java`)
- Test: `src/test/java/com/openggf/level/TestLevelManagerRewindSnapshot.java` (create)

The adapter implements `RewindSnapshottable<LevelSnapshot>` against the current `AbstractLevel` instance. Snapshot **stores references** to current `Block[]`, `Chunk[]`, `Map` — not copies (CoW handles divergence automatically).

- [ ] **Step 1: Define `LevelSnapshot`:**

```java
package com.openggf.game.rewind.snapshot;

import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Map;

/**
 * Reference-only level state snapshot. Block / Chunk / Map arrays are
 * shared between the snapshot and the live level until a mutator's
 * cowEnsureWritable clones the affected array. Capture is O(arrays),
 * not O(bytes); restore is a reference swap.
 */
public record LevelSnapshot(
        long epochAtCapture,
        Block[] blocks,
        Chunk[] chunks,
        Map map) {}
```

> If `AbstractLevel`'s field types are different (e.g. `Block[][]`, `LevelMap`, etc.), update the record fields to match. Read `AbstractLevel.java` first.

- [ ] **Step 2: Implement the adapter.** Add a `RewindSnapshottable<LevelSnapshot>` accessor to `LevelManager`:

```java
public RewindSnapshottable<LevelSnapshot> levelRewindSnapshottable() {
    return new RewindSnapshottable<>() {
        @Override public String key() { return "level"; }
        @Override public LevelSnapshot capture() {
            AbstractLevel level = currentLevel();
            return new LevelSnapshot(
                    level.currentEpoch(),
                    level.blocksReference(),
                    level.chunksReference(),
                    level.map());
        }
        @Override public void restore(LevelSnapshot s) {
            AbstractLevel level = currentLevel();
            level.replaceBlocks(s.blocks());
            level.replaceChunks(s.chunks());
            level.replaceMap(s.map());
            level.bumpEpoch();
            // Mark dirty so processDirtyRegions() re-uploads on next frame.
            level.markAllDirty();
        }
    };
}
```

> Add `replaceBlocks`/`replaceChunks`/`replaceMap`/`markAllDirty` package-private methods to `AbstractLevel`. They simply assign the field references and mark the level's dirty BitSets fully set, so `LevelManager.processDirtyRegions()` re-uploads on the next frame.

- [ ] **Step 3: Write tests:**

```java
@Test
void captureRestoreRoundTripsBlockMutations() {
    // Load a fixture level (use HeadlessTestFixture / SharedLevel)
    // Capture snapshot -> mutate a Block -> restore -> verify Block is
    // back to original.
}

@Test
void mutationAfterCaptureCowClonesUnderlyingArray() {
    // Capture snapshot, mutate Block[5] with a setChunkDesc.
    // Verify the snapshot's Block[5].chunkDescsArrayForTest() is
    // unchanged (because cow cloned it on first write).
}

@Test
void restoreMarksDirtyForReupload() {
    // Capture, mutate, restore.
    // Verify level.dirtyBlocks bitset has bits set after restore.
}
```

- [ ] **Step 4: Confirm tests pass and commit.**

---

## Track C — Atomic-state subsystem RewindSnapshottables

6 tasks. Each is a small per-subsystem snapshot record + a `RewindSnapshottable` adapter on the owning subsystem.

### Task C.1 — `Camera` rewind snapshot

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/CameraSnapshot.java`
- Modify: `src/main/java/com/openggf/camera/Camera.java`
- Test: `src/test/java/com/openggf/camera/TestCameraRewindSnapshot.java` (create)

- [ ] **Step 1: Inspect `Camera.java`** — list every mutable field (x, y, frozen, locked, target reference, scroll-delay timers, etc.). The snapshot record must cover all of them.

- [ ] **Step 2: Define `CameraSnapshot` record** with one field per mutable Camera field. For target sprite reference, store a stable identifier (sprite role enum, e.g., `MAIN`/`SIDEKICK_0`/...) and re-resolve on restore via `SpriteManager.getMainPlayer()` / etc., NOT a Java reference.

- [ ] **Step 3: Add `RewindSnapshottable<CameraSnapshot>` interface implementation directly to `Camera`** (or a sibling adapter class — match whatever convention the codebase prefers).

- [ ] **Step 4: Test round-trip** — capture, mutate camera, restore, assert all fields back to captured values.

- [ ] **Step 5: Commit.**

---

### Task C.2 — `GameStateManager` rewind snapshot

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/GameStateSnapshot.java`
- Modify: `src/main/java/com/openggf/game/GameStateManager.java`
- Test: `src/test/java/com/openggf/game/TestGameStateRewindSnapshot.java` (create)

Mirror Task C.1. Snapshot record holds rings, score, lives, time, emeralds, continues, super-state flag, etc. — every gameplay-meaningful field.

---

### Task C.3 — `GameRng` rewind snapshot

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/GameRngSnapshot.java`
- Modify: `src/main/java/com/openggf/game/GameRng.java`
- Test: `src/test/java/com/openggf/game/TestGameRngRewindSnapshot.java` (create)

Snapshot is just `long seed`. Restore via existing `setSeed(long)`. Confirm flavour (S1 vs S2 vs S3K) is also captured if it's mutable mid-session.

---

### Task C.4 — `TimerManager` rewind snapshot

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/TimerManagerSnapshot.java`
- Modify: `src/main/java/com/openggf/timer/TimerManager.java`
- Test: `src/test/java/com/openggf/timer/TestTimerManagerRewindSnapshot.java` (create)

Snapshot covers the list of active timers. Each timer's state must round-trip — read `TimerManager.java` to enumerate fields per `AbstractTimer`.

---

### Task C.5 — `FadeManager` rewind snapshot

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/FadeManagerSnapshot.java`
- Modify: `src/main/java/com/openggf/graphics/FadeManager.java`
- Test: `src/test/java/com/openggf/graphics/TestFadeManagerRewindSnapshot.java` (create)

Snapshot covers fade phase, intensity counter, target palette IDs, callback list (or skip callbacks if they're transient — document the choice).

---

### Task C.6 — `OscillationManager` static-state RewindSnapshottable

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/OscillationStaticAdapter.java`
- Test: `src/test/java/com/openggf/game/TestOscillationStaticAdapter.java` (create)

`OscillationManager` is static-only (Plan 1 D.1 added `snapshot()`/`restore(...)`). Wrap those static methods in a `RewindSnapshottable<OscillationSnapshot>`:

```java
public final class OscillationStaticAdapter
        implements RewindSnapshottable<OscillationSnapshot> {
    @Override public String key() { return "oscillation"; }
    @Override public OscillationSnapshot capture() {
        return OscillationManager.snapshot();
    }
    @Override public void restore(OscillationSnapshot s) {
        OscillationManager.restore(s);
    }
}
```

Test round-trip; commit.

---

## Track D — Composite ObjectManager snapshot

3 tasks. Most invasive of the subsystems because objects spawn / despawn dynamically and cross-reference each other.

### Task D.1 — Define `ObjectManagerSnapshot` record

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/ObjectManagerSnapshot.java`

The record must cover (read `ObjectManager.java` to confirm field names):

- `usedSlots` (BitSet — captured as `long[]`)
- `reservedChildSlots` (Map of ObjectSpawn → slot — captured as a list of `(ObjectSpawn key, int slot)` entries; `ObjectSpawn` references are stable across rewind because they come from the immutable level layout)
- `frameCounter`, `vblaCounter`, `currentExecSlot`, `peakSlotCount`, `bucketsDirty`
- For each active slot: a `PerObjectSnapshot` containing the slot's spawn ID + the per-instance state (a `byte[]` or struct that the object knows how to serialize)
- For child-spawned objects (ObjectSpawn not in the level layout): a `ChildSpawnDescriptor` that carries the parent identifier + spawn-time parameters, so on restore the parent can re-issue an identical child spawn.

Define the record + supporting nested records. No tests yet for D.1 — they live in D.2/D.3.

- [ ] Commit the record.

---

### Task D.2 — Per-instance snapshot via `AbstractObjectInstance.captureRewindState()`

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Test: `src/test/java/com/openggf/level/objects/TestAbstractObjectInstanceRewindCapture.java` (create)

Add a default method on `AbstractObjectInstance`:

```java
/**
 * Captures this object's gameplay-relevant state for a rewind snapshot.
 * Default implementation captures the standard fields (x, y, x_vel,
 * y_vel, x_pos, y_pos, anim_timer, etc.). Subclasses override to add
 * their own private state if it's gameplay-relevant.
 */
public PerObjectRewindSnapshot captureRewindState() {
    return new PerObjectRewindSnapshot(
            x, y, xSpeed, ySpeed, anim, animTimer, /* etc. */);
}

public void restoreRewindState(PerObjectRewindSnapshot s) {
    this.x = s.x();
    this.y = s.y();
    /* etc. */
}
```

`PerObjectRewindSnapshot` is a record holding the standard mutable fields. Document that subclasses with private state (e.g., boss phase counters, badnik-specific state) MUST override and add their own fields — otherwise their state silently won't round-trip.

> This task only adds the framework. The first set of subclass overrides is part of D.3 (only the player + a small handful of objects for v1; comprehensive coverage of every object class is deferred to a follow-up plan).

- [ ] Test default capture/restore round-trips x/y/speeds/anim. Commit.

---

### Task D.3 — `ObjectManager.RewindSnapshottable` adapter

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: `src/test/java/com/openggf/level/objects/TestObjectManagerRewindSnapshot.java` (create)

The adapter:
- Captures: `usedSlots` (BitSet), per-slot `(spawnId, capturedRewindState)` entries, child-spawn descriptors, scalar counters.
- Restores: clears the current object table, re-instantiates each captured slot from its spawn (using the spawn factory), restores per-instance state, re-resolves child-parent links, and replays the scalar counters.

Document the contract that holders of object references **must re-resolve** on restore (Camera target, LevelEventManager boss reference, etc.) — for v1 the burden is on the owning subsystem (Camera in Task C.1 already re-resolves via SpriteManager).

- [ ] Test round-trip: spawn an object, advance N frames so it has non-default state, capture, advance further, restore, verify object is back at the captured state with correct identity.

- [ ] **Optional:** also test object destruction across rewind — destroy an object, capture, advance, restore — verify the object is recreated with the captured state.

- [ ] Commit.

---

## Track E — `GameplayModeContext` integration

2 tasks. Wires the `RewindRegistry` into the gameplay session container so rewind primitives have a natural home.

### Task E.1 — Add `RewindRegistry` field to `GameplayModeContext`

**Files:**
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Test: `src/test/java/com/openggf/game/session/TestGameplayModeContextRewindRegistry.java` (create)

- [ ] **Step 1: Add `private final RewindRegistry rewindRegistry = new RewindRegistry();`** as a field on `GameplayModeContext`. Add a `getRewindRegistry()` accessor.

- [ ] **Step 2: Wire registration during runtime build-up.** Find the place where `GameplayModeContext` finishes attaching managers (probably `RuntimeManager.buildGameplayMode()` or a `setManagers(...)` call). After all managers are attached, register their `RewindSnapshottable` adapters with the registry — e.g., `rewindRegistry.register(camera.rewindSnapshottable())`.

  > For v1 this registration block is hard-coded in one place — the runtime build-up. A future plan can refactor it to a per-subsystem `attachToRewindRegistry(...)` lifecycle hook if it grows unwieldy.

- [ ] **Step 3: Test that constructing a `GameplayModeContext` and capturing a snapshot through `getRewindRegistry().capture()` yields a non-empty `CompositeSnapshot` containing all the C.* / D.* / B.5 keys (`camera`, `game-state`, `rng`, `timers`, `fade`, `oscillation`, `level`, `object-manager`).**

- [ ] Commit.

---

### Task E.2 — Surface `RewindController` + `PlaybackController` from `GameRuntime`

**Files:**
- Modify: `src/main/java/com/openggf/game/GameRuntime.java`
- Modify: `src/main/java/com/openggf/game/GameServices.java` (delegating accessor)

Add a `getPlaybackController()` accessor on `GameRuntime`/`GameplayModeContext`. The visualiser (Track F) reads it to drive playback.

For v1 the controllers are constructed by `RuntimeManager` when it builds the gameplay mode — taking the `RewindRegistry`, an `InMemoryKeyframeStore`, the visualiser-supplied `InputSource` (trace), and an `EngineStepper` callback that drives the existing engine update loop one frame.

- [ ] Wire and test that calling `gameRuntime.getPlaybackController().tick()` advances the engine by one frame in PLAYING state. Commit.

---

## Track F — Visualiser integration

3 tasks. Held-rewind UX, single-step both directions, buffer-end signal.

### Task F.1 — Replace direct trace stepping with `PlaybackController.tick()`

**Files:**
- Modify: trace-replay visualiser glue (find via `grep -rn "TraceReplayBootstrap\|playbackTick\|PlaybackDebugManager" src/main/java/`).

The visualiser today drives the engine by some mechanism (likely `PlaybackDebugManager.advance()`). Wire it to call `gameRuntime.getPlaybackController().tick()` instead — when the visualiser is active and a trace is loaded.

- [ ] Smoke-test: load a trace, confirm playback still works (PLAYING state is the default; tick() advances one frame just like before).

- [ ] Commit.

---

### Task F.2 — Held-rewind keybinding

**Files:**
- Modify: trace-replay input handling (probably `TraceTestModeController` or `PlaybackDebugManager` keybinding logic).

Bind a key (suggest **Backspace** for "back in time" — confirm doesn't conflict with existing bindings; also consider **B** as a back-up). On press → `playbackController.rewind()`. On release → `playbackController.pause()` (so released-and-still-rewinding doesn't keep firing).

- [ ] Test interactively: hold Backspace during trace playback, confirm engine plays backward at framerate.

- [ ] Commit.

---

### Task F.3 — Single-step both directions

**Files:**
- Modify: same input glue as F.2.

Bind **Right Arrow** while paused → `stepForwardOnce()`. Bind **Left Arrow** while paused → `stepBackwardOnce()`.

- [ ] Test interactively. Commit.

---

## Track G — Tests

2 tasks (most TDD test coverage already lives in the per-task tests above; this track adds the cross-cutting parity test and the act-boundary smoke test).

### Task G.1 — Keystone parity test using `RecordingFrameObserver`

**Files:**
- Test: `src/test/java/com/openggf/game/rewind/TestRewindParityAgainstTrace.java` (create)

Drives a real S2 reference trace through the engine in two passes:

1. **Pass A:** play the entire trace forward with a `RecordingFrameObserver` capturing every `FrameComparison`.
2. **Pass B:** advance to a chosen midpoint frame F (~halfway through the trace), call `playbackController.rewind()` enough times to scrub back to F-100, then call `playbackController.play()` and play forward to the trace end. Capture with another `RecordingFrameObserver`.
3. Assert `passA.diff(passB)` is empty (or minimal — document any expected divergence).

If any divergence shows up, the parity test surfaces the first frame at which the two passes diverge — a signal that some mutable state escaped the registry.

- [ ] **Step 1: Pick a small reference trace** (an S2 EHZ1 trace from `src/test/resources/traces/`). Trace replay infra already supports this.

- [ ] **Step 2: Build the test using `LiveTraceComparator` constructor with the per-frame observer hook from Plan 1 / commit `43362a884`.**

- [ ] **Step 3: For v1, accept that divergences may exist** for subsystems we haven't covered yet (animators, parallax, water, etc.). The test asserts:

  - At minimum: `passA.frames().size() == passB.frames().size()` (same number of compared frames).
  - The divergent fields at frame F+50 (well past the rewind point) should match in both passes — i.e., subsystems we DO cover (camera, rng, game state, oscillation, level, objects) round-trip cleanly.

  Document expected gaps in a comment for follow-up coverage.

- [ ] **Step 4: Commit.** This is the keystone test — when it goes green, v1 ships.

---

### Task G.2 — Act-boundary smoke test (basic)

**Files:**
- Test: `src/test/java/com/openggf/game/rewind/TestRewindAcrossActBoundary.java` (create)

Drives a trace that crosses an act boundary (S2 EHZ1 → EHZ2 if available; otherwise S1 GHZ1 → GHZ2). Assert that rewinding back across the act boundary does NOT throw an exception, even if state doesn't fully round-trip (PLC art reload state isn't covered in v1).

If the test panics with NPE or array-bounds errors, that's a real bug to fix. If it just fails the parity assertion, that's an expected coverage gap — document it.

- [ ] Smoke-only for v1. Commit.

---

## Final integration check

### Task FINAL.1 — Full-suite verification

```
mvn test -q
```

- [ ] **Step 1:** confirm test count is at least the prior baseline (~4221 passing) plus the new tests added by this plan.

- [ ] **Step 2:** if any pre-existing tests start failing, isolate which task introduced the regression (check via `git bisect` on the plan-2 commits).

- [ ] **Step 3:** if Track G's parity test surfaces unexpected divergences in covered subsystems, that's a real bug — fix the offending Snapshottable before declaring done.

- [ ] **Step 4:** report final state to the user with: total commits, total new tests, parity-test result, any deferred concerns.

---

## Summary

When this plan completes, the codebase has:

1. A complete rewind framework (interfaces + reference impls) in `com.openggf.game.rewind`.
2. Copy-on-write level snapshots covering Block/Chunk/Map (Pattern bytes are derived state — not snapshotted).
3. RewindSnapshottable coverage for Camera, GameStateManager, GameRng, TimerManager, FadeManager, OscillationManager, LevelManager (level state via CoW), ObjectManager (composite).
4. Held-rewind UX in the trace-replay visualiser (hold Backspace to rewind at framerate; arrow keys for single-step both directions when paused).
5. A keystone parity test that drives a real S2 trace forward then mid-trace rewind+replay and asserts captured `FrameComparison` vectors match for covered subsystems.
6. Documented coverage gaps for the deferred follow-up plan: ParallaxManager, WaterSystem, SolidExecutionRegistry, ZoneRuntimeRegistry, PaletteOwnershipRegistry, AnimatedTileChannelGraph, SpecialRenderEffectRegistry, AdvancedRenderModeController, ZoneLayoutMutationPipeline, AbstractLevelEventManager subclasses, animator counter snapshots, PLC art registry snapshots, RingManager / LostRingPool, RewindBenchmark headless harness, no-redraw mutation API for the MGZ migration.

The follow-up plan ("rewind framework v1 — comprehensive coverage") is an additive extension: each item closes a coverage gap by implementing `RewindSnapshottable` on the corresponding subsystem and re-running the parity test until it goes fully green.
