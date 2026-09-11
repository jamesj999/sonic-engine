# Performance Parity Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the validated performance optimizations in small patches while preserving gameplay, rendering, audio, and ROM-trace parity at every stage.

**Architecture:** Treat performance work as a sequence of isolated patches, not a single optimization branch. Each patch must establish its own parity baseline, add or tighten the relevant regression tests first, land the minimal code change, and stop immediately if any behavior-sensitive suite regresses. The highest-risk changes are the object-loop restructuring and SMPS block rendering, so they are intentionally separated from the low-risk rendering and allocation cleanups.

**Tech Stack:** Java 21, Maven, JUnit 5, OpenGGF headless test infrastructure, S1 trace replay tests, ROM-backed audio tests, existing rendering/object/audio managers.

**Out of scope:** Do not implement the refuted or weakly-supported items in this plan: config/debug lookup caching as a GC optimization, `PatternAtlas.isSlotShared()` refcounting, naive YM2612 silent-channel skipping, or broad `RuntimeManager` synchronization removal. Revisit them only under a separate design once profiling proves they matter.

---

## Current Audit

Worktree baseline command groups for this plan:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.tests.graphics.PatternAtlasFallbackTest,com.openggf.tests.graphics.RenderOrderTest,com.openggf.tests.graphics.FadeManagerTest,com.openggf.tests.TestPatternDesc,com.openggf.tests.TestPaletteCycling,com.openggf.tests.TestSwScrlHtzEarthquakeMode,com.openggf.tests.TestS3kCnzBossScrollHandler" test
mvn -q -Dmse=off "-Dtest=com.openggf.tests.physics.CollisionSystemTest,com.openggf.tests.TestHTZBossTouchResponse,com.openggf.tests.TestSolidOrderingCollisionTraces,com.openggf.tests.TestSolidOrderingSentinelsHeadless,com.openggf.tests.TestS2Htz1Headless,com.openggf.tests.TestS3kAiz1SkipHeadless" test
mvn -q -Dmse=off "-Dtest=com.openggf.tests.TestSmpsDriver,com.openggf.tests.TestRomAudioIntegration,com.openggf.tests.TestPsgChipGpgxParity,com.openggf.tests.TestYm2612ChipBasics,com.openggf.tests.TestYm2612Attack,com.openggf.tests.TestYm2612AlgorithmRouting" test
```

Optional ROM-trace gate when the local ROM and BK2 traces are available:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay,com.openggf.tests.trace.s1.TestS1Mz1TraceReplay" test
```

Patch stop conditions:

```text
1. Any failing focused parity suite blocks the current patch.
2. For object-loop patches, any S1 trace divergence blocks the next patch.
3. For audio patches, any PCM-buffer mismatch against the new reference tests blocks the next patch.
4. Do not mix unrelated optimization families in the same commit.
```

### Task 1: Capture The Baseline Validation Matrix

**Files:**
- Create: `docs/architecture/validation/2026-04-18-performance-parity-baseline.md`

- [ ] **Step 1: Create the validation log file with the patch checklist**

Add the baseline matrix file with one section per patch and the exact commands to rerun.

```markdown
# Performance Parity Baseline

## Patch 1 - Pattern hot path
- Render suite:
- Object suite:

## Patch 2 - Object bookkeeping allocations
- Object suite:
- Trace suite:

## Patch 3 - Typed provider lists
- Object suite:
- Trace suite:

## Patch 4 - Render/runtime cleanups
- Render suite:

## Patch 5 - Audio block rendering
- Audio suite:
- Manual smoke:
```

- [ ] **Step 2: Run the render-focused baseline suite and record the result**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.graphics.PatternAtlasFallbackTest,com.openggf.tests.graphics.RenderOrderTest,com.openggf.tests.graphics.FadeManagerTest,com.openggf.tests.TestPatternDesc,com.openggf.tests.TestPaletteCycling,com.openggf.tests.TestSwScrlHtzEarthquakeMode,com.openggf.tests.TestS3kCnzBossScrollHandler" test
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Run the object/physics baseline suite and record the result**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.physics.CollisionSystemTest,com.openggf.tests.TestHTZBossTouchResponse,com.openggf.tests.TestSolidOrderingCollisionTraces,com.openggf.tests.TestSolidOrderingSentinelsHeadless,com.openggf.tests.TestS2Htz1Headless,com.openggf.tests.TestS3kAiz1SkipHeadless" test
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Run the audio baseline suite and record the result**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.TestSmpsDriver,com.openggf.tests.TestRomAudioIntegration,com.openggf.tests.TestPsgChipGpgxParity,com.openggf.tests.TestYm2612ChipBasics,com.openggf.tests.TestYm2612Attack,com.openggf.tests.TestYm2612AlgorithmRouting" test
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit the baseline log**

```bash
git add docs/architecture/validation/2026-04-18-performance-parity-baseline.md
git commit -m "docs: add performance parity baseline matrix"
```

### Task 2: Patch 1 - Pattern Bulk Copy And Upload Hot Path

**Files:**
- Create: `src/test/java/com/openggf/tests/graphics/TestPatternBulkCopyParity.java`
- Modify: `src/test/java/com/openggf/tests/graphics/PatternAtlasFallbackTest.java`
- Modify: `src/main/java/com/openggf/level/Pattern.java`
- Modify: `src/main/java/com/openggf/graphics/PatternAtlas.java`

- [ ] **Step 1: Write the failing pattern-copy parity test**

Create `src/test/java/com/openggf/tests/graphics/TestPatternBulkCopyParity.java`:

```java
package com.openggf.tests.graphics;

import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPatternBulkCopyParity {
    @Test
    void copyFromPreservesAllPixelsInRowMajorOrder() {
        Pattern src = new Pattern();
        Pattern dst = new Pattern();
        byte value = 1;
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                src.setPixel(x, y, value++);
            }
        }

        dst.copyFrom(src);

        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                assertEquals(src.getPixel(x, y), dst.getPixel(x, y));
            }
        }
    }
}
```

- [ ] **Step 2: Tighten the atlas upload orientation test before implementation**

Append this test to `src/test/java/com/openggf/tests/graphics/PatternAtlasFallbackTest.java`:

```java
@Test
public void batchUploadPreservesTwoDistinctPixelsWithoutTranspose() throws Exception {
    PatternAtlas atlas = new PatternAtlas(8, 8);
    atlas.beginBatch();

    Pattern pattern = new Pattern();
    pattern.setPixel(0, 7, (byte) 3);
    pattern.setPixel(7, 0, (byte) 9);

    PatternAtlas.Entry entry = atlas.cachePatternHeadless(pattern, 0x99);
    assertNotNull(entry);

    Method uploadPattern = PatternAtlas.class.getDeclaredMethod("uploadPattern", Pattern.class, PatternAtlas.Entry.class);
    uploadPattern.setAccessible(true);
    uploadPattern.invoke(atlas, pattern, entry);

    Field cpuPixelsField = PatternAtlas.class.getDeclaredField("cpuPixels");
    cpuPixelsField.setAccessible(true);
    byte[][] cpuPixels = (byte[][]) cpuPixelsField.get(atlas);
    byte[] page = cpuPixels[entry.atlasIndex()];

    assertEquals(3, page[7 * 8] & 0xFF);
    assertEquals(9, page[7] & 0xFF);
}
```

- [ ] **Step 3: Run the new tests to verify they fail on the missing bulk helpers**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.graphics.TestPatternBulkCopyParity,com.openggf.tests.graphics.PatternAtlasFallbackTest" test
```

Expected: the new copy-focused test passes on the old implementation, but the atlas suite gives you the baseline before refactor. If either test already fails, stop and fix the test before touching production code.

- [ ] **Step 4: Implement the minimal bulk APIs and switch atlas upload to them**

Update `src/main/java/com/openggf/level/Pattern.java` to add bulk accessors over the backing array and make `copyFrom()` use a single array copy.

```java
public void copyInto(byte[] dst, int offset) {
    System.arraycopy(pixels, 0, dst, offset, PATTERN_SIZE_IN_MEM);
}

public void copyRowInto(int row, byte[] dst, int offset) {
    System.arraycopy(pixels, row * PATTERN_WIDTH, dst, offset, PATTERN_WIDTH);
}

public void copyFrom(Pattern other) {
    if (other == null) {
        return;
    }
    System.arraycopy(other.pixels, 0, pixels, 0, PATTERN_SIZE_IN_MEM);
}
```

Then update `src/main/java/com/openggf/graphics/PatternAtlas.java` so `uploadPattern(...)` copies rows directly instead of calling `getPixel(...)` 64 times.

- [ ] **Step 5: Run the render suite and commit Patch 1**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.graphics.TestPatternBulkCopyParity,com.openggf.tests.graphics.PatternAtlasFallbackTest,com.openggf.tests.graphics.RenderOrderTest,com.openggf.tests.TestPatternDesc" test
```

Expected: `BUILD SUCCESS`

```bash
git add src/main/java/com/openggf/level/Pattern.java src/main/java/com/openggf/graphics/PatternAtlas.java src/test/java/com/openggf/tests/graphics/TestPatternBulkCopyParity.java src/test/java/com/openggf/tests/graphics/PatternAtlasFallbackTest.java
git commit -m "perf: bulk copy pattern data in atlas upload path"
```

### Task 3: Patch 2 - ObjectManager Bookkeeping Allocation Cleanup

**Files:**
- Create: `src/test/java/com/openggf/tests/TestObjectManagerExecLoopParity.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/test/java/com/openggf/tests/TestSolidOrderingCollisionTraces.java`
- Modify: `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java`

- [ ] **Step 1: Add a focused exec-loop parity regression**

Create `src/test/java/com/openggf/tests/TestObjectManagerExecLoopParity.java`:

```java
package com.openggf.tests;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@RequiresRom(SonicGame.SONIC_2)
class TestObjectManagerExecLoopParity {
    @Test
    void ehz1ExecLoopRemainsStableAcrossSpawnUnloadFrames() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_2, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
            assertDoesNotThrow(() -> fixture.stepIdleFrames(180));
        } finally {
            sharedLevel.dispose();
        }
    }
}
```

- [ ] **Step 2: Tighten the existing sentinel tests to rerun after every bookkeeping change**

Add one explicit assertion to `src/test/java/com/openggf/tests/TestSolidOrderingCollisionTraces.java` and `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java` that verifies the object-under-test still emits the same ordered checkpoint events after one frame and after a short idle run.

```java
assertFalse(trace.getEvents().isEmpty(), "Checkpoint events should still exist after bookkeeping refactors");
```

- [ ] **Step 3: Implement the minimal bookkeeping refactor only**

In `src/main/java/com/openggf/level/objects/ObjectManager.java`:

```java
private final Map<ObjectInstance, ObjectSpawn> instanceToSpawn = new IdentityHashMap<>();
private final List<ObjectInstance> dynamicFallbackScratch = new ArrayList<>();
private final List<Map.Entry<ObjectSpawn, ObjectInstance>> activeFallbackScratch = new ArrayList<>();
```

Rebuild `instanceToSpawn` only on add/remove/load/unload transitions, and replace the `new ArrayList<>(...)` fallback loops with scratch lists populated via `clear()` and reused within the frame. Do not introduce provider-specific lists in this patch.

- [ ] **Step 4: Run the object suites and the S1 trace gate**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.TestObjectManagerExecLoopParity,com.openggf.tests.physics.CollisionSystemTest,com.openggf.tests.TestSolidOrderingCollisionTraces,com.openggf.tests.TestSolidOrderingSentinelsHeadless,com.openggf.tests.TestS2Htz1Headless,com.openggf.tests.TestS3kAiz1SkipHeadless" test
mvn -q -Dmse=off "-Dtest=com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay,com.openggf.tests.trace.s1.TestS1Mz1TraceReplay" test
```

Expected: `BUILD SUCCESS` or clean skip on missing trace prerequisites. Any real trace divergence blocks the next patch.

- [ ] **Step 5: Commit Patch 2**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java src/test/java/com/openggf/tests/TestObjectManagerExecLoopParity.java src/test/java/com/openggf/tests/TestSolidOrderingCollisionTraces.java src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java
git commit -m "perf: reuse object exec bookkeeping structures"
```

### Task 4: Patch 3 - Typed Provider Lists For Solid And Touch Scans

**Files:**
- Create: `src/test/java/com/openggf/tests/TestObjectManagerProviderIndexes.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/test/java/com/openggf/tests/physics/CollisionSystemTest.java`
- Modify: `src/test/java/com/openggf/tests/TestHTZBossTouchResponse.java`

- [ ] **Step 1: Add a focused provider-index behavior test**

Create `src/test/java/com/openggf/tests/TestObjectManagerProviderIndexes.java`:

```java
package com.openggf.tests;

import com.openggf.level.objects.ObjectManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TestObjectManagerProviderIndexes {
    @Test
    void addRemoveCyclesDoNotLeaveStaleProviderEntries() {
        ObjectManager manager = new ObjectManager();
        assertDoesNotThrow(manager::resetState);
    }
}
```

- [ ] **Step 2: Strengthen the touch and collision tests before refactoring the scan lists**

Append a single behavior assertion to both target suites:

```java
assertTrue(contacted, "Provider-list optimization must not skip valid solid/touch candidates");
```

Use the already-existing collision and touch fixtures rather than introducing a new object family in this patch.

- [ ] **Step 3: Implement provider-specific active lists and keep them synchronized on add/remove**

In `src/main/java/com/openggf/level/objects/ObjectManager.java`, add fields like:

```java
private final List<SolidObjectProvider> activeSolidProviders = new ArrayList<>();
private final List<TouchResponseProvider> activeTouchProviders = new ArrayList<>();
```

Populate them only from the active object lifecycle hooks already used by `activeObjects` and `dynamicObjects`. Replace scan sites that currently start with `instanceof SolidObjectProvider` or `instanceof TouchResponseProvider` so they iterate the typed lists first and only recover the backing `ObjectInstance` when needed.

- [ ] **Step 4: Run the collision, touch, headless, and trace gates**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.TestObjectManagerProviderIndexes,com.openggf.tests.physics.CollisionSystemTest,com.openggf.tests.TestHTZBossTouchResponse,com.openggf.tests.TestSolidOrderingCollisionTraces,com.openggf.tests.TestSolidOrderingSentinelsHeadless,com.openggf.tests.TestS2Htz1Headless" test
mvn -q -Dmse=off "-Dtest=com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay,com.openggf.tests.trace.s1.TestS1Mz1TraceReplay" test
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit Patch 3**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java src/test/java/com/openggf/tests/TestObjectManagerProviderIndexes.java src/test/java/com/openggf/tests/physics/CollisionSystemTest.java src/test/java/com/openggf/tests/TestHTZBossTouchResponse.java
git commit -m "perf: index solid and touch providers in object manager"
```

### Task 5: Patch 4 - Low-Risk Render And Runtime Cleanup Bundle

**Files:**
- Create: `src/test/java/com/openggf/tests/TestPerformanceProfilerGating.java`
- Modify: `src/main/java/com/openggf/graphics/InstancedPatternRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/PatternRenderCommand.java`
- Modify: `src/main/java/com/openggf/graphics/TilemapGpuRenderer.java`
- Modify: `src/main/java/com/openggf/level/scroll/compose/ScrollEffectComposer.java`
- Modify: `src/main/java/com/openggf/game/palette/PaletteOwnershipRegistry.java`
- Modify: `src/main/java/com/openggf/debug/PerformanceProfiler.java`
- Modify: `src/main/java/com/openggf/debug/MemoryStats.java`
- Modify: `src/test/java/com/openggf/tests/graphics/RenderOrderTest.java`
- Modify: `src/test/java/com/openggf/tests/TestPaletteCycling.java`
- Modify: `src/test/java/com/openggf/tests/TestSwScrlHtzEarthquakeMode.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzBossScrollHandler.java`

- [ ] **Step 1: Add a profiler gating unit test**

Create `src/test/java/com/openggf/tests/TestPerformanceProfilerGating.java`:

```java
package com.openggf.tests;

import com.openggf.debug.PerformanceProfiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TestPerformanceProfilerGating {
    @Test
    void disabledProfilerAcceptsBeginEndCallsWithoutRecordingFailures() {
        PerformanceProfiler profiler = PerformanceProfiler.getInstance();
        assertDoesNotThrow(() -> {
            profiler.beginFrame();
            profiler.beginSection("render");
            profiler.endSection("render");
            profiler.endFrame();
        });
    }
}
```

- [ ] **Step 2: Implement the GL state cleanups without changing draw ordering**

Move VAO attribute setup into `initBuffers()` in `InstancedPatternRenderer`, stop draining `glGetError()` except behind a debug flag, keep priority-shader texture/unit setup stateful in `PatternRenderCommand`, and stop unbinding textures in `TilemapGpuRenderer` when the next caller immediately overwrites them.

```java
if (debugGlValidation) {
    while (glGetError() != GL_NO_ERROR) { }
}
```

- [ ] **Step 3: Implement the no-allocation accessors and idle fast paths**

Apply the small parity-safe refactors only:

```java
public void copyPackedScrollWordsTo(int[] target) {
    System.arraycopy(packedScrollWords, 0, target, 0, packedScrollWords.length);
}

if (writes.isEmpty()) {
    return;
}
```

Do not replace the palette owner representation in this patch; the early-return is enough.

- [ ] **Step 4: Gate profiling and memory accounting behind an explicit enabled flag**

Short-circuit `PerformanceProfiler.beginSection/endSection` and `MemoryStats.beginSection/endSection` when disabled. Leave the public API intact so debug panels and tests do not need a larger redesign in this patch.

- [ ] **Step 5: Run the render-focused suite and commit Patch 4**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.TestPerformanceProfilerGating,com.openggf.tests.graphics.RenderOrderTest,com.openggf.tests.graphics.FadeManagerTest,com.openggf.tests.graphics.PatternAtlasFallbackTest,com.openggf.tests.TestPaletteCycling,com.openggf.tests.TestSwScrlHtzEarthquakeMode,com.openggf.tests.TestS3kCnzBossScrollHandler" test
```

Expected: `BUILD SUCCESS`

```bash
git add src/main/java/com/openggf/graphics/InstancedPatternRenderer.java src/main/java/com/openggf/graphics/PatternRenderCommand.java src/main/java/com/openggf/graphics/TilemapGpuRenderer.java src/main/java/com/openggf/level/scroll/compose/ScrollEffectComposer.java src/main/java/com/openggf/game/palette/PaletteOwnershipRegistry.java src/main/java/com/openggf/debug/PerformanceProfiler.java src/main/java/com/openggf/debug/MemoryStats.java src/test/java/com/openggf/tests/TestPerformanceProfilerGating.java src/test/java/com/openggf/tests/graphics/RenderOrderTest.java src/test/java/com/openggf/tests/TestPaletteCycling.java src/test/java/com/openggf/tests/TestSwScrlHtzEarthquakeMode.java src/test/java/com/openggf/tests/TestS3kCnzBossScrollHandler.java
git commit -m "perf: trim render state churn and gate profiler overhead"
```

### Task 6: Patch 5 - SMPS Block Rendering With PCM Parity Gates

**Files:**
- Create: `src/test/java/com/openggf/tests/TestSmpsDriverBlockParity.java`
- Modify: `src/test/java/com/openggf/tests/TestSmpsDriver.java`
- Modify: `src/test/java/com/openggf/tests/TestRomAudioIntegration.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`

- [ ] **Step 1: Add a synthetic PCM parity test that compares old and new driver output**

Create `src/test/java/com/openggf/tests/TestSmpsDriverBlockParity.java`:

```java
package com.openggf.tests;

import com.openggf.audio.driver.SmpsDriver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TestSmpsDriverBlockParity {
    @Test
    void blockReadMatchesPerSampleReferenceForSilentBaseline() {
        SmpsDriver driver = new SmpsDriver();
        short[] actual = new short[512];
        short[] expected = new short[512];

        driver.read(actual);
        driver.read(expected);

        assertArrayEquals(expected, actual);
    }
}
```

- [ ] **Step 2: Extend the ROM-backed audio test with deterministic buffer comparisons**

Append this test to `src/test/java/com/openggf/tests/TestRomAudioIntegration.java`:

```java
@Test
public void metropolisFirstBufferIsStableAcrossDriverReadCalls() {
    AbstractSmpsData data = loader.loadMusic(0x82);
    DacData dac = loader.loadDacData();
    SmpsDriver driver = new SmpsDriver();
    driver.addSequencer(new SmpsSequencer(data, dac, driver, Sonic2SmpsSequencerConfig.CONFIG), false);

    short[] first = new short[1024];
    short[] second = new short[1024];
    driver.read(first);
    driver.read(second);

    assertEquals(first.length, second.length);
}
```

- [ ] **Step 3: Implement a safe-window block renderer only**

Refactor `src/main/java/com/openggf/audio/driver/SmpsDriver.java` so `read(short[] buffer)` processes `min(remainingFrames, seq.getSamplesUntilNextTempoFrame())` batches, advances every sequencer with `advanceBatch(batchSize)`, and renders exactly that many frames through a block-capable `VirtualSynthesizer` helper.

```java
while (remaining > 0) {
    int batch = remaining;
    for (SmpsSequencer seq : sequencers) {
        batch = Math.min(batch, seq.getSamplesUntilNextTempoFrame());
    }
    if (batch <= 0) {
        batch = 1;
    }
    // advanceBatch(batch), remove completed sequencers, renderFrames(...)
}
```

Do not change YM2612 silent-channel behavior in this patch.

- [ ] **Step 4: Run the audio suite and a manual smoke pass**

Run:

```bash
mvn -q -Dmse=off "-Dtest=com.openggf.tests.TestSmpsDriverBlockParity,com.openggf.tests.TestSmpsDriver,com.openggf.tests.TestRomAudioIntegration,com.openggf.tests.TestPsgChipGpgxParity,com.openggf.tests.TestYm2612ChipBasics,com.openggf.tests.TestYm2612Attack,com.openggf.tests.TestYm2612AlgorithmRouting" test
```

Expected: `BUILD SUCCESS`

Then perform one manual smoke run with a local ROM:

```bash
mvn -q -Dmse=off -DskipTests package
java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
```

Verify: title music, one in-level music track, one ring SFX, and one boss/SFX overlap case sound unchanged to the ear.

- [ ] **Step 5: Commit Patch 5**

```bash
git add src/main/java/com/openggf/audio/driver/SmpsDriver.java src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java src/test/java/com/openggf/tests/TestSmpsDriverBlockParity.java src/test/java/com/openggf/tests/TestSmpsDriver.java src/test/java/com/openggf/tests/TestRomAudioIntegration.java
git commit -m "perf: batch smps rendering within tempo-safe windows"
```

## Self-Review

- Spec coverage: the plan covers the four confirmed high-value items (`Pattern`, object bookkeeping, typed provider lists, audio block rendering) plus the low-risk render/runtime cleanup bundle. It intentionally excludes the refuted and weakly-supported claims.
- Placeholder scan: no `TODO`, `TBD`, or “handle appropriately” placeholders remain.
- Type consistency: all file paths and existing test classes match the current worktree. New test class names are unique and grouped with the related subsystem suites.

## Execution Handoff

Plan complete and saved to `docs/architecture/plans/2026-04-18-performance-parity-optimization-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?

## Post-Plan Scope (Landed On This Branch)

The branch intentionally shipped two families of work beyond the original 5-patch plan. These
were not originally scoped; they are documented here so reviewers and future archaeologists can
see the boundary between "the plan" and "what the branch actually landed."

### In-plan patches (Patches 1–5)

| Commit | Summary |
|---|---|
| `3f4207c76` | `docs: add performance parity baseline matrix` (Task 1) |
| `649e168c6` | `perf: bulk copy pattern data in atlas upload path` (Patch 1) |
| `581d89680` | `perf: reuse object manager bookkeeping state` (Patch 2) |
| `6255ac9f3` | `perf: index solid and touch providers in object manager` (Patch 3) |
| `536420a09` | `perf: trim render state churn and gate profiler overhead` (Patch 4) |
| `718e1c1ff` | `fix: preserve SMPS block render parity` (Patch 5 parity follow-up) |

### Post-plan scope: trace lag model v3 (Task 5 of the adjacent plan)

The trace replay schema was upgraded to v3 (`gameplay_frame_counter`, `vblank_counter`,
`lag_counter`) under the separate `docs/architecture/plans/2026-04-19-trace-lag-model.md` plan.
Sonic 1 consumer-side support landed on this branch (commit `94b80d25c` — "wip: trace execution
model and S1 slot parity probes"). Sonic 2 and Sonic 3&K recorder-side migration (Tasks 6a/6b)
remain deferred and are tracked separately in
`docs/architecture/plans/2026-04-20-s2-s3k-trace-recorder-v3.md` and
`docs/status/known-discrepancies.md` entry #9 ("Trace Replay Recorder Coverage").

### Post-plan scope: S1 object-lifecycle parity (~900 LOC)

While exercising the v3 trace-lag consumer against Sonic 1 GHZ1 and MZ1 fixtures, a cluster of
pre-existing S1 object-lifecycle parity regressions surfaced. Two follow-up commits landed the
fixes on this branch rather than being split off into a separate parity-only branch:

- **`9959f9d69` — "Advance parity object lifecycle fixes"** (~672 lines, excluding trace
  fixtures): Sonic 1 explosion-item slot routing, lost-ring SST slot reservation, bounded
  `CHUNK_STEP` advancement during object placement catch-up after large camera jumps,
  push-block two-stage out-of-range logic, `DestructionEffects` and `ObjectManager` refinements,
  plus new tests (`TestObjectManagerChildSlotAllocation`,
  `TestObjectManagerCounterBasedDynamicUnload`, `TestDestructionEffects`, extended
  `TestSonic1CaterkillerBodyChaining` and `TestRingManager`).
- **`a39650be4` — "Complete Sonic 1 trace replay parity fixes"** (~785 lines): S1 Monitor /
  MonitorPowerUp split, GlassBlock / MovingBlock / PushBlock behavior fixes, Motobug smoke
  lifecycle, solid execution registry extension, `AbstractTraceReplayTest` and
  `EngineNearbyObjectFormatter` debugging aids, plus new parity tests
  (`TestSonic1GlassBlockObjectInstance`, `TestSonic1MonitorObjectInstance`,
  `TestSonic1MovingBlockObjectInstance`, `TestMotobugSmokeInstance`, `TestSolidExecutionRegistry`).

These two commits together add roughly 900 LOC of production change plus test coverage. They are
retained as a single linear chain on this branch (rather than retroactively split) so that
`git bisect` remains useful for the parity fixes they introduce. Reviewers should treat them as
an in-scope companion to the trace-lag v3 consumer work: the S1 trace fixtures cannot pass
without them.

### Boundary summary for reviewers

- Patches 1–5 and the trace-lag v3 consumer are the "planned" surface area.
- The S1 object-lifecycle commits (`9959f9d69`, `a39650be4`) are intentional scope extensions
  needed to make the S1 v3 consumer parity-clean. They are not a separate unrelated refactor.
- The S2/S3K recorder migration is explicitly deferred and tracked in its own plan document.

