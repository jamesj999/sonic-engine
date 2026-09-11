# Rewind Allocation Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce per-frame heap allocations in the `rewind.capture`, `rewind.restore`, and `rewind.step` profiler sections so held-rewind, trace replay, and segment-cache seeking produce less GC churn.

**Architecture:** Three independent phases land in order. Phase 1 deletes gratuitous defensive clones inside `CodecFieldSnapshot`. Phase 2 pools the per-object scratch (`RewindStateBuffer` + `ArrayList<Object>`) used inside `CompactFieldCapturer` and the codec-field branch of `GenericFieldCapturer` via thread-local scratch. Phase 3 drops `CompositeSnapshot`'s defensive copy and lets `RewindRegistry` pass map ownership directly. Behaviour is preserved; the existing rewind torture and trace-replay suites are the regression net.

**Tech Stack:** Java 21, JUnit 5, Maven; existing rewind infrastructure under `com.openggf.game.rewind` and `com.openggf.game.rewind.schema`.

---

## File Structure

**Modified:**
- `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java` — Phase 1 (drop `CodecFieldSnapshot` accessor clones) and Phase 2c (pool codec-field scratch).
- `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java` — Phase 2b (pool per-object schema-capture scratch).
- `src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java` — Phase 2a (add `reset()`).
- `src/main/java/com/openggf/game/rewind/RewindRegistry.java` — Phase 3 (hand map ownership to `CompositeSnapshot`).
- `src/main/java/com/openggf/game/rewind/CompositeSnapshot.java` — Phase 3 (accept ownership; no defensive copy).

**New tests:**
- `src/test/java/com/openggf/game/rewind/schema/TestRewindStateBufferReset.java` — Phase 2a.
- `src/test/java/com/openggf/game/rewind/TestRewindCaptureScratchReuse.java` — Phase 2b compact-path round-trip regression.
- `src/test/java/com/openggf/game/rewind/TestRewindCodecFieldScratchReuse.java` — Phase 2c codec-field round-trip regression.
- `src/test/java/com/openggf/game/rewind/TestCompositeSnapshotOwnership.java` — Phase 3 public-vs-owned contract.

**Regression coverage (already exists, must stay green):**
- `TestRewindController`, `TestRewindRegistry`, `TestRewindTorture`, `TestRewindTraceSeekDeterminism`, `TestRewindParityAgainstTrace`, `TestRewindAcrossActBoundary`, `TestRewindDeathRespawnBoundary`, `TestObjectIdentityRebindingAcrossRewind`, `TestGameplayModeContextRewindRegistry`, `TestGameplayModeContextPlaybackController`, plus the full `*TraceReplay` sweep.

**Commit policy:** Every non-`master` commit needs the trailer block (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`). The `prepare-commit-msg` hook auto-appends; fill in `n/a` for trailers whose mapped files are not staged. CHANGELOG.md gets one entry at the end of Phase 3.

---

## Task 1: Phase 1 — Drop `CodecFieldSnapshot` accessor clones

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java:646-660`

The record's canonical constructor clones `scalarData` and `opaqueValues` to insulate the snapshot from caller-side mutation of the input arrays. That clone is correct and stays. The two overridden accessors then clone *again* on every read — once per codec field per `rewind.restore` frame. The only consumer (`restoreCodecField` at lines 638-643) feeds the arrays into `RewindStateBuffer.reader(...)` (read-only) and the codec's `OpaqueIndex` (read-only), so the second clone has no defensive purpose.

- [ ] **Step 1: Run target regression tests to confirm clean baseline**

Run: `mvn "-Dtest=TestRewindController,TestRewindRegistry,TestRewindTorture,TestRewindAcrossActBoundary,TestObjectIdentityRebindingAcrossRewind" test`

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Delete the two accessor overrides**

Edit `GenericFieldCapturer.java`. Replace the existing record (lines 646-660):

```java
    private record CodecFieldSnapshot(byte[] scalarData, Object[] opaqueValues) {
        private CodecFieldSnapshot {
            scalarData = scalarData.clone();
            opaqueValues = opaqueValues.clone();
        }

        @Override
        public byte[] scalarData() {
            return scalarData.clone();
        }

        @Override
        public Object[] opaqueValues() {
            return opaqueValues.clone();
        }
    }
```

with:

```java
    // Constructor clones isolate the snapshot from caller-side mutation of the scratch
    // arrays that produced it. Accessors return the stored arrays directly; callers
    // (restoreCodecField) treat them as read-only.
    private record CodecFieldSnapshot(byte[] scalarData, Object[] opaqueValues) {
        private CodecFieldSnapshot {
            scalarData = scalarData.clone();
            opaqueValues = opaqueValues.clone();
        }
    }
```

- [ ] **Step 3: Re-run the same target tests**

Run: `mvn "-Dtest=TestRewindController,TestRewindRegistry,TestRewindTorture,TestRewindAcrossActBoundary,TestObjectIdentityRebindingAcrossRewind" test`

Expected: BUILD SUCCESS, all tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java
git commit -m "$(cat <<'EOF'
perf(rewind): remove gratuitous CodecFieldSnapshot accessor clones

The record already clones inputs in its canonical constructor to insulate
itself from caller-side mutation of the scratch arrays. The override
accessors then cloned again on every read, once per codec field per
restore frame. Only consumer is restoreCodecField, which feeds the arrays
into read-only RewindStateBuffer.Reader and OpaqueIndex.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
EOF
)"
```

---

## Task 2: Phase 2a — Add `reset()` to `RewindStateBuffer`

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindStateBufferReset.java`

Pooled reuse needs an in-place reset that keeps the backing `byte[]` and rewinds `size` to zero. The current API has no such method.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/rewind/schema/TestRewindStateBufferReset.java`:

```java
package com.openggf.game.rewind.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestRewindStateBufferReset {

    @Test
    void resetClearsSizeButKeepsBackingArrayUsable() {
        RewindStateBuffer buffer = new RewindStateBuffer();
        buffer.writeInt(0x12345678);
        buffer.writeLong(0x0123456789ABCDEFL);
        assertEquals(12, buffer.toByteArray().length);

        buffer.reset();
        assertEquals(0, buffer.toByteArray().length);

        buffer.writeInt(0x42);
        byte[] after = buffer.toByteArray();
        assertEquals(4, after.length);
        assertArrayEquals(new byte[] {0x42, 0, 0, 0}, after);
    }

    @Test
    void resetIsIdempotent() {
        RewindStateBuffer buffer = new RewindStateBuffer();
        buffer.reset();
        buffer.reset();
        assertEquals(0, buffer.toByteArray().length);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn "-Dtest=TestRewindStateBufferReset" test`

Expected: COMPILATION FAILURE — `reset()` does not exist on `RewindStateBuffer`.

- [ ] **Step 3: Add `reset()` to `RewindStateBuffer`**

In `src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java`, insert the following method just before `toByteArray()` (between lines 61 and 62):

```java
    public void reset() {
        size = 0;
    }

```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn "-Dtest=TestRewindStateBufferReset" test`

Expected: BUILD SUCCESS, both tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java \
        src/test/java/com/openggf/game/rewind/schema/TestRewindStateBufferReset.java
git commit -m "$(cat <<'EOF'
feat(rewind): add RewindStateBuffer.reset() for pooled reuse

Enables pooling the buffer across captures without reallocating its
backing array. Used by the next two commits in CompactFieldCapturer and
GenericFieldCapturer.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
EOF
)"
```

---

## Task 3: Phase 2b — Pool scratch buffers in `CompactFieldCapturer`

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java`

`captureWithSchema` (lines 43-54) allocates `new RewindStateBuffer()` + `new ArrayList<Object>()` per object per frame. The buffers are drained into a fresh `byte[]` / `Object[]` (line 53) before returning, so a single thread-local scratch pair is safe.

Capture is invoked on the gameplay thread inside `RewindRegistry.capture()` and is non-reentrant per the registry javadoc ("no subsystem is mid-step"). Pin a `ThreadLocal` keyed on the capturer; reset on acquire so any earlier exception cannot leave stale bytes.

- [ ] **Step 1: Run the regression baseline**

Run: `mvn "-Dtest=TestRewindController,TestRewindRegistry,TestRewindTorture,TestObjectIdentityRebindingAcrossRewind" test`

Expected: BUILD SUCCESS.

- [ ] **Step 2: Add the thread-local scratch and use it in `captureWithSchema`**

Edit `CompactFieldCapturer.java`. Add the following private nested type and `ThreadLocal` field at the top of the class body, immediately after the `public final class CompactFieldCapturer {` declaration (before line 10):

```java
    private static final class Scratch {
        final RewindStateBuffer scalarData = new RewindStateBuffer();
        final ArrayList<Object> opaqueValues = new ArrayList<>();

        void reset() {
            scalarData.reset();
            opaqueValues.clear();
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

```

Then replace `captureWithSchema` (lines 43-54) with:

```java
    private static RewindObjectStateBlob captureWithSchema(
            Object target,
            RewindClassSchema schema,
            RewindCaptureContext context) {

        Scratch scratch = SCRATCH.get();
        scratch.reset();
        try {
            for (RewindFieldPlan field : schema.capturedFields()) {
                field.codec().capture(field.field(), target, scratch.scalarData, scratch.opaqueValues, context);
            }
            return new RewindObjectStateBlob(
                    schema.schemaId(), schema.type(),
                    scratch.scalarData.toByteArray(), scratch.opaqueValues.toArray());
        } finally {
            scratch.reset();
        }
    }
```

The `try { ... } finally { scratch.reset(); }` ensures the pooled `ArrayList` never retains opaque object references on the unhappy path. If any of `codec.capture(...)`, `toByteArray()`, or `toArray()` throws, the next capture on this thread still acquires a clean scratch.

The `java.util.ArrayList` import is already present (line 5). No other imports change.

- [ ] **Step 3: Run the regression suite**

Run: `mvn "-Dtest=TestRewindController,TestRewindRegistry,TestRewindTorture,TestObjectIdentityRebindingAcrossRewind" test`

Expected: BUILD SUCCESS.

- [ ] **Step 4: Add a targeted regression test for the compact-path pooling**

Create `src/test/java/com/openggf/game/rewind/TestRewindCaptureScratchReuse.java`:

```java
package com.openggf.game.rewind;

import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindObjectStateBlob;
import com.openggf.game.rewind.schema.RewindSchemaRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindCaptureScratchReuse {

    public static final class ScalarHolder {
        public int alpha;
        public long beta;
        public boolean gamma;
    }

    @Test
    void backToBackCapturesProduceEqualButDistinctBlobs() {
        assertTrue(RewindSchemaRegistry.schemaFor(ScalarHolder.class).unsupportedFields().isEmpty(),
                "ScalarHolder must be fully supported by the compact capturer for this test to be meaningful");

        ScalarHolder target = new ScalarHolder();
        target.alpha = 0x11223344;
        target.beta = 0x0123456789ABCDEFL;
        target.gamma = true;

        RewindObjectStateBlob first = CompactFieldCapturer.capture(target);
        RewindObjectStateBlob second = CompactFieldCapturer.capture(target);

        assertNotSame(first.scalarData(), second.scalarData(),
                "Each capture must return a freshly allocated byte[] even though scratch is pooled");
        assertArrayEquals(first.scalarData(), second.scalarData());
        assertEquals(first.opaqueValues().length, second.opaqueValues().length);
    }

    @Test
    void mutatingTargetBetweenCapturesDoesNotCorruptFirstBlob() {
        ScalarHolder target = new ScalarHolder();
        target.alpha = 0x11111111;
        RewindObjectStateBlob first = CompactFieldCapturer.capture(target);
        byte[] firstBytes = first.scalarData().clone();

        target.alpha = 0x22222222;
        RewindObjectStateBlob second = CompactFieldCapturer.capture(target);

        assertArrayEquals(firstBytes, first.scalarData(),
                "First snapshot's scalarData must not be affected by a later capture");
        assertEquals(firstBytes.length, second.scalarData().length);
    }
}
```

`ScalarHolder` does not extend `AbstractObjectInstance`, so the default-object-subclass branch is skipped; the test exercises the generic compact path via `CompactFieldCapturer.capture(target)`. The eligibility `assertTrue` at the top of the first test will fail loudly with a useful message if the schema registry rejects the class — investigate before patching the test.

- [ ] **Step 5: Run the new test**

Run: `mvn "-Dtest=TestRewindCaptureScratchReuse" test`

Expected: BUILD SUCCESS, both tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java \
        src/test/java/com/openggf/game/rewind/TestRewindCaptureScratchReuse.java
git commit -m "$(cat <<'EOF'
perf(rewind): pool compact-schema capture scratch via ThreadLocal

captureWithSchema allocated a fresh RewindStateBuffer and ArrayList per
object per frame. Both are drained into freshly allocated byte[]/Object[]
before returning, so a single thread-local pair is safe. Scratch is
reset on acquire (defensive) and inside a finally block on release so
that opaque object references do not linger on the unhappy path.

TestRewindCaptureScratchReuse pins the round-trip contract: two
back-to-back captures of the same target produce equal but distinct
blobs, and a target mutation between captures does not corrupt the
first snapshot.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
EOF
)"
```

---

## Task 4: Phase 2c — Pool scratch buffers in `GenericFieldCapturer.captureCodecField`

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java:624-632`

Same pattern as Task 3, applied to the codec-field branch (called once per codec-typed final field per captured object).

- [ ] **Step 1: Run the regression baseline**

Run: `mvn "-Dtest=TestRewindController,TestRewindRegistry,TestRewindTorture,TestObjectIdentityRebindingAcrossRewind" test`

Expected: BUILD SUCCESS.

- [ ] **Step 2: Add a separate thread-local scratch and use it in `captureCodecField`**

In `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java`, add the scratch type and field at the top of the class body (just inside the class declaration, before the first existing static field):

```java
    private static final class CodecScratch {
        final RewindStateBuffer scalarData = new RewindStateBuffer();
        final ArrayList<Object> opaqueValues = new ArrayList<>();

        void reset() {
            scalarData.reset();
            opaqueValues.clear();
        }
    }

    private static final ThreadLocal<CodecScratch> CODEC_SCRATCH = ThreadLocal.withInitial(CodecScratch::new);

```

(Use a distinct ThreadLocal from `CompactFieldCapturer`'s `SCRATCH` so the two cannot collide if any future call path nests them.)

Then replace `captureCodecField` (lines 624-632) with:

```java
    private static CodecFieldSnapshot captureCodecField(Field field, Object target) {
        field.setAccessible(true);
        RewindCodec codec = RewindCodecs.codecFor(field)
                .orElseThrow(() -> new IllegalStateException("Missing rewind codec for " + FieldKey.of(field)));
        CodecScratch scratch = CODEC_SCRATCH.get();
        scratch.reset();
        try {
            codec.capture(field, target, scratch.scalarData, scratch.opaqueValues);
            return new CodecFieldSnapshot(scratch.scalarData.toByteArray(), scratch.opaqueValues.toArray());
        } finally {
            scratch.reset();
        }
    }
```

Verify `java.util.ArrayList` is imported (it should already be — `GenericFieldCapturer` uses `ArrayList` elsewhere). If not, add `import java.util.ArrayList;` to the imports.

- [ ] **Step 3: Run the regression suite plus a broader rewind sweep**

Run: `mvn "-Dtest=TestRewindController,TestRewindRegistry,TestRewindTorture,TestRewindTraceSeekDeterminism,TestRewindParityAgainstTrace,TestRewindAcrossActBoundary,TestRewindDeathRespawnBoundary,TestObjectIdentityRebindingAcrossRewind" test`

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 4: Add a targeted regression test for the codec-field pooling**

The compact-path test from Task 3 does not exercise `GenericFieldCapturer.captureCodecField`. The gate at `GenericFieldCapturer.java:600-614` (`usesCodecFieldSnapshot`) only routes through `captureCodecField` when the field is either an in-place helper type (`SubpixelMotion.State`, `ObjectAnimationState`, etc.) or a *compact collection* per `isDefaultObjectCompactCollectionField` (`GenericFieldCapturer.java:412-418`) — which requires `final` + `Collection` or `Map`. Plain `final` arrays go through `deepCloneValue` instead and would not exercise `CODEC_SCRATCH`.

This test uses a `final List<Integer>` field, which routes through the codec-field path via `CollectionCodec`.

Create `src/test/java/com/openggf/game/rewind/TestRewindCodecFieldScratchReuse.java`:

```java
package com.openggf.game.rewind;

import com.openggf.game.rewind.snapshot.GenericObjectSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestRewindCodecFieldScratchReuse {

    public static final class CodecFinalCollectionHolder {
        public final List<Integer> values = new ArrayList<>();
    }

    @Test
    void backToBackCapturesOfFinalCollectionFieldRoundTripIndependently() {
        CodecFinalCollectionHolder target = new CodecFinalCollectionHolder();
        target.values.add(1);
        target.values.add(2);
        target.values.add(3);

        GenericObjectSnapshot first = GenericFieldCapturer.capture(target);
        GenericObjectSnapshot second = GenericFieldCapturer.capture(target);
        assertNotNull(first);
        assertNotNull(second);

        // Restoring the second snapshot into a fresh holder must reproduce
        // the captured state — proves the pooled scratch was drained into
        // independent storage on each capture.
        CodecFinalCollectionHolder restored = new CodecFinalCollectionHolder();
        GenericFieldCapturer.restore(restored, second);
        assertEquals(List.of(1, 2, 3), restored.values);
    }

    @Test
    void mutatingFinalCollectionBetweenCapturesDoesNotCorruptFirstSnapshot() {
        CodecFinalCollectionHolder target = new CodecFinalCollectionHolder();
        target.values.add(10);
        target.values.add(20);
        GenericObjectSnapshot first = GenericFieldCapturer.capture(target);

        // Mutate the collection in place, then capture again to overwrite
        // the pooled scratch. The first snapshot must still restore the
        // pre-mutation state.
        target.values.clear();
        target.values.add(99);
        GenericFieldCapturer.capture(target);

        CodecFinalCollectionHolder restored = new CodecFinalCollectionHolder();
        GenericFieldCapturer.restore(restored, first);
        assertEquals(List.of(10, 20), restored.values,
                "First snapshot must restore the pre-mutation values, proving the pooled "
                        + "scratch did not bleed the second capture into the first snapshot");
        assertEquals(List.of(99), target.values);  // sanity: target itself was actually mutated
    }
}
```

If `GenericFieldCapturer.capture(target)` rejects `CodecFinalCollectionHolder` with a "Missing rewind codec" or eligibility error, verify with a quick grep that `CollectionCodec` is registered for `List` element types in `RewindCodecs.java` (around line 274) and that `RewindCodecs.collectionCodecUsesIdentityReferences(field)` returns false for `Integer` elements. If identity references are required for the element type, substitute another scalar element type (e.g. `String`).

- [ ] **Step 5: Run the new test**

Run: `mvn "-Dtest=TestRewindCodecFieldScratchReuse" test`

Expected: BUILD SUCCESS, both tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java \
        src/test/java/com/openggf/game/rewind/TestRewindCodecFieldScratchReuse.java
git commit -m "$(cat <<'EOF'
perf(rewind): pool codec-field capture scratch via ThreadLocal

Mirrors the CompactFieldCapturer pooling for the codec-field branch in
GenericFieldCapturer. Distinct ThreadLocal so the two cannot collide if
any future call path nests them. Scratch use is wrapped in try/finally
so opaque object references do not linger on the unhappy path.

TestRewindCodecFieldScratchReuse routes a target with a final
List<Integer> field through GenericFieldCapturer.capture(Object). The
usesCodecFieldSnapshot gate at GenericFieldCapturer.java:600 only
routes Collection/Map compact fields and in-place helpers through
captureCodecField — plain arrays go through deepCloneValue and would
not exercise CODEC_SCRATCH. The second test mutates the list in place
between captures to verify that the pooled scratch does not bleed the
second capture into the first snapshot.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
EOF
)"
```

---

## Task 5: Phase 3 — Drop `CompositeSnapshot` defensive copy and hand ownership

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/CompositeSnapshot.java`
- Modify: `src/main/java/com/openggf/game/rewind/RewindRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/TestCompositeSnapshotOwnership.java`
- Modify: `CHANGELOG.md`

`RewindRegistry.capture()` allocates a `LinkedHashMap` (`RewindRegistry.java:67`), populates it, then passes it to `new CompositeSnapshot(bundle)`, which immediately allocates a second `LinkedHashMap` as a defensive copy (`CompositeSnapshot.java:21`). Two map allocations per capture. The registry is the sole production caller and never mutates the map after handing it over, so the copy is purely paranoid for this one call site.

`CompositeSnapshot` is, however, a public, documented-immutable type with many consumers (tests construct it via `Map.of(...)` and other shapes). Weakening the public constructor's contract for everyone — to win one allocation on one call site — is the wrong trade. Instead, keep the public defensive-copy constructor intact and add a package-private `static CompositeSnapshot owned(LinkedHashMap<String, Object>)` factory that wraps without copying. Only `RewindRegistry.capture()` calls it, and the type signature pins the ownership transfer at the call site.

- [ ] **Step 1: Write the ownership-contract tests**

Create `src/test/java/com/openggf/game/rewind/TestCompositeSnapshotOwnership.java`. This test lives in the same package as `CompositeSnapshot` so it can call the package-private `owned()` factory.

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestCompositeSnapshotOwnership {

    @Test
    void publicConstructorPreservesImmutabilityViaDefensiveCopy() {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("k", "v1");
        CompositeSnapshot snapshot = new CompositeSnapshot(source);

        source.put("k", "v2");
        source.put("extra", "x");

        assertEquals("v1", snapshot.get("k"),
                "Public constructor must defensively copy — the public immutability "
                        + "contract holds for arbitrary callers");
        assertEquals(1, snapshot.entries().size(),
                "Mutations to the source map must not appear in the snapshot");
    }

    @Test
    void ownedFactoryTransfersOwnershipAndDoesNotCopy() {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("k", "v1");
        CompositeSnapshot snapshot = CompositeSnapshot.owned(source);

        // The contract here is "do not retain a reference and mutate". We mutate
        // anyway to pin the no-copy behaviour: a regression that re-adds a copy
        // would make this assertion fail.
        source.put("k", "v2");
        assertEquals("v2", snapshot.get("k"),
                "owned() must NOT copy — the production hot path relies on this");
    }

    @Test
    void entriesReturnsTheSameUnmodifiableViewEachCall() {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("a", 1);
        source.put("b", 2);

        CompositeSnapshot snapshot = CompositeSnapshot.owned(source);
        Map<String, Object> first = snapshot.entries();
        Map<String, Object> second = snapshot.entries();

        assertSame(first, second, "entries() must return the same instance each call");
        assertThrows(UnsupportedOperationException.class, () -> first.put("c", 3),
                "entries() must remain unmodifiable regardless of construction path");
    }
}
```

- [ ] **Step 2: Run the tests against the current code to see them fail**

Run: `mvn "-Dtest=TestCompositeSnapshotOwnership" test`

Expected: COMPILATION FAILURE — `CompositeSnapshot.owned(...)` does not exist yet. The other tests can't run until the factory is added.

- [ ] **Step 3: Add the package-private `owned()` factory to `CompositeSnapshot`**

Replace the entire body of `src/main/java/com/openggf/game/rewind/CompositeSnapshot.java` with:

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
 *
 * <p>The public constructor defensively copies its input to preserve the
 * documented immutability contract for arbitrary callers. The production
 * capture hot path uses {@link #owned(LinkedHashMap)} instead, which
 * wraps without copying and is package-private so the ownership transfer
 * is contained to {@link RewindRegistry#capture()}.
 */
public final class CompositeSnapshot {

    private final Map<String, Object> entries;

    public CompositeSnapshot(Map<String, Object> entries) {
        Objects.requireNonNull(entries, "entries");
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    private CompositeSnapshot(LinkedHashMap<String, Object> entries, boolean transferOwnership) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    /**
     * Wraps {@code entries} as the snapshot's backing store with NO defensive
     * copy. Ownership transfers to the snapshot; the caller must not retain a
     * reference or mutate the map afterwards. Package-private — only
     * {@link RewindRegistry#capture()} should call this.
     */
    static CompositeSnapshot owned(LinkedHashMap<String, Object> entries) {
        Objects.requireNonNull(entries, "entries");
        return new CompositeSnapshot(entries, true);
    }

    public Map<String, Object> entries() {
        return entries;
    }

    public Object get(String key) {
        return entries.get(key);
    }

    public boolean containsKey(String key) {
        return entries.containsKey(key);
    }
}
```

The public constructor's `new LinkedHashMap<>(entries)` is the existing defensive copy, kept verbatim. The private second constructor takes a `LinkedHashMap` (already-correctly-typed) plus a marker arg to disambiguate the overload, and skips the copy. The static `owned(...)` factory is the single package-private entry point that calls the no-copy path.

- [ ] **Step 4: Update `RewindRegistry.capture()` to use the ownership path**

Edit `src/main/java/com/openggf/game/rewind/RewindRegistry.java`. Replace line 73:

```java
            return new CompositeSnapshot(bundle);
```

with:

```java
            return CompositeSnapshot.owned(bundle);
```

The variable `bundle` is already typed `LinkedHashMap<String, Object>` (line 67), so the type matches `owned()`'s parameter exactly.

- [ ] **Step 5: Run the new tests**

Run: `mvn "-Dtest=TestCompositeSnapshotOwnership" test`

Expected: BUILD SUCCESS, all three tests pass.

- [ ] **Step 6: Run the full rewind regression suite**

Run: `mvn "-Dtest=TestRewindController,TestRewindRegistry,TestRewindTorture,TestRewindTraceSeekDeterminism,TestRewindParityAgainstTrace,TestRewindAcrossActBoundary,TestRewindDeathRespawnBoundary,TestObjectIdentityRebindingAcrossRewind,TestGameplayModeContextRewindRegistry,TestGameplayModeContextPlaybackController,TestCompositeSnapshot,TestSegmentCache,TestInMemoryKeyframeStore,TestPlaybackController,TestRewindBenchmarkSizeEstimator,TestRewindProfilerAttribution,TestCompositeSnapshotOwnership,TestRewindCaptureScratchReuse,TestRewindCodecFieldScratchReuse,TestRewindStateBufferReset" test`

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 7: Add a CHANGELOG.md entry**

Open `CHANGELOG.md`, find the top-of-file "Unreleased" / "develop" section (follow existing format), and add a single bullet:

```
- perf(rewind): pool capture scratch buffers and add CompositeSnapshot.owned() ownership path for the registry hot path; reduces per-frame allocations in rewind.capture / rewind.step / rewind.restore without weakening the public CompositeSnapshot immutability contract.
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/CompositeSnapshot.java \
        src/main/java/com/openggf/game/rewind/RewindRegistry.java \
        src/test/java/com/openggf/game/rewind/TestCompositeSnapshotOwnership.java \
        CHANGELOG.md
git commit -m "$(cat <<'EOF'
perf(rewind): add CompositeSnapshot.owned() and use it from RewindRegistry

RewindRegistry.capture() allocated a fresh LinkedHashMap and then handed
it to a constructor that defensively copied it again — two map
allocations per frame. The defensive copy is needed for the public
constructor (CompositeSnapshot is a documented-immutable type with many
external consumers) but is unnecessary for the registry, which is the
sole production producer of the bundle and never retains a reference
after construction.

Add a package-private static factory CompositeSnapshot.owned(LinkedHashMap)
that wraps without copying, document the ownership transfer in javadoc,
and route the registry hot path through it. The public constructor's
defensive-copy behaviour is unchanged.

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
EOF
)"
```

---

## Task 6: Final validation sweep

Run the broadest rewind-related test selection in one pass to confirm no regression across the three phases:

- [ ] **Step 1: Run the full rewind + trace replay sweep**

Run: `mvn "-Dtest=TestRewind*,TestGameplayModeContextRewindRegistry,TestGameplayModeContextPlaybackController,TestLiveRewindManagerAudioCleanup,TestObjectIdentityRebindingAcrossRewind,*TraceReplay" test`

Expected: BUILD SUCCESS. If any `*TraceReplay` test fails that previously passed on the parent commit of Task 1, the regression is from this PR — invoke the `trace-replay-bug-fixing` skill before declaring done.

- [ ] **Step 2 (optional): Profile to confirm allocation reduction**

If you captured a baseline before Task 1, re-run the same workload now and compare. Expected directional change (qualitative; exact ratios depend on object count, codec mix, and segment-cache stride):
- `rewind.capture`: meaningfully fewer per-frame allocations from three sources — `CodecFieldSnapshot` constructor clones survive but the two accessor clones are gone (Phase 1), per-object `RewindStateBuffer` + `ArrayList` scratch is pooled (Phase 2), and the second `LinkedHashMap` from the defensive-copy constructor is gone for the registry call site (Phase 3).
- `rewind.restore`: fewer allocations per codec field — the two accessor clones are gone (Phase 1). One `byte[]` clone remains inside `RewindStateBuffer.Reader`'s constructor (see `RewindStateBuffer.java:92`); removing that is a possible follow-up but is out of scope here because the `Reader` is a public type with its own defensive-copy contract.
- `rewind.step`: scales with `rewind.capture`, since each step calls capture inside `SegmentCache`.

No regression in `*TraceReplay` runtime is expected; if any trace runtime gets worse, investigate before merging.

---

## Risks & Notes

1. **Reentrancy.** Both thread-locals assume `RewindRegistry.capture()` is non-reentrant. The registry's javadoc already states "no subsystem is mid-step during these operations." If a codec's `capture` ever calls back into `CompactFieldCapturer.capture` or `GenericFieldCapturer.captureFields` for a different object on the same thread, scratch will be clobbered. Mitigation: the regression suite (especially `TestRewindTorture` and the `*TraceReplay` sweep) exercises every registered codec. If reentrancy is later required, replace the `ThreadLocal<Scratch>` with a small stack of scratch instances and acquire/release explicitly.

2. **CodecFieldSnapshot aliasing contract.** After Phase 1, `scalarData()` and `opaqueValues()` return the stored arrays directly. The current sole consumer (`restoreCodecField`) treats them as read-only; the comment in Task 1 Step 2 documents the contract. If new consumers appear, they must respect it or clone explicitly.

3. **No zone or route carve-outs.** Per CLAUDE.md, no zone/route/frame-specific branching is added. These are general engine optimizations.

4. **Commit trailers.** The repo's `prepare-commit-msg` hook auto-appends the trailer block on non-merge commits. Each commit message above already includes the filled-in block; if the hook re-appends, delete the duplicate before saving the message.
