# Audio Memory Benchmark Optimization Series Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add benchmark-grade memory metrics to the existing audio benchmarks, record a 10-run baseline, then implement and evaluate ranked memory candidates 1-4 one at a time without breaking sample-accurate output.

**Architecture:** Keep the benchmark workloads in `AudioRegressionTest` unchanged and add a test-local memory probe that captures pre/post benchmark deltas plus untimed peak-heap replay data. Run every optimization in isolation behind the existing exact-output and batching regression tests, then benchmark each accepted step with the same 10-run workflow used for baseline.

**Tech Stack:** Java, JUnit 5, Maven Surefire, Java management MXBeans, Sonic 2 audio regression assets, PowerShell benchmark loops.

---

## File Map

**Plan / Notes**
- Create: `docs/architecture/plans/2026-04-17-audio-memory-benchmark-optimization-series.md`
- Create: `docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md`
- Create: `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`

**Benchmark / Regression**
- Create: `src/test/java/com/openggf/audio/AudioBenchmarkMemoryProbe.java`
- Modify: `src/test/java/com/openggf/audio/AudioRegressionTest.java`

**Candidate 1**
- Modify: `src/main/java/com/openggf/audio/synth/BlipResampler.java`
- Modify: `src/test/java/com/openggf/tests/TestBlipResampler.java`
- Modify if needed: `src/test/java/com/openggf/tests/TestYm2612ChipBasics.java`

**Candidate 2**
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java`

**Candidate 3**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify or create if needed: `src/test/java/com/openggf/tests/TestSmpsSequencerAllocationPaths.java`

**Candidate 4**
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Modify: `src/test/java/com/openggf/tests/TestVirtualSynthesizerMix.java`

## Shared Commands

- [ ] **Step 1: Define the regression gate**

Run:

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest,TestPsgChipGpgxParity,TestYm2612ChipBasics,TestYm2612Attack,TestYm2612InstrumentTone,TestYm2612SsgEg,TestYm2612TimerCSM,TestYm2612VoiceLengths,TestYm2612AlgorithmRouting,TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries,TestRomAudioIntegration,TestTitleScreenAudioRegression,TestSonic2PsgEnvelopesAgainstRom,TestBlipResampler,TestVirtualSynthesizerMix" test
```

Expected:
- Build success
- All named tests pass

- [ ] **Step 2: Define the benchmark command**

Run:

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkAudioRendering+benchmarkResultsTallyStressRendering" test
```

Expected output fragments:
- `Audio render time:`
- `Real-time factor:`
- `Allocated bytes during run:`
- `Heap used before:`
- `Heap used after:`
- `GC count delta:`
- `Peak heap during replay:`
- `Results tally stress render time:`
- `Results tally stress real-time factor:`

---

### Task 1: Add Benchmark-Grade Memory Measurement

**Files:**
- Create: `src/test/java/com/openggf/audio/AudioBenchmarkMemoryProbe.java`
- Modify: `src/test/java/com/openggf/audio/AudioRegressionTest.java`

- [ ] **Step 1: Add a focused probe test first**

Add this test method near the other benchmark helper tests in `AudioRegressionTest.java`:

```java
    @Test
    public void benchmarkMemoryProbeReportsNonNegativeDeltasForAudioRenderWorkload() {
        assumeTrue(loader != null, "ROM not available, skipping memory probe test");

        AbstractSmpsData musicData = loader.loadMusic(MUSIC_EHZ);
        assertNotNull(musicData, "Music data should load");

        SmpsDriver driver = new SmpsDriver(SAMPLE_RATE);
        driver.setRegion(SmpsSequencer.Region.NTSC);

        SmpsSequencer seq = new SmpsSequencer(musicData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        seq.setSampleRate(SAMPLE_RATE);
        driver.addSequencer(seq, false);

        short[] buffer = new short[BUFFER_SIZE * 2];
        AudioBenchmarkMemoryProbe probe = AudioBenchmarkMemoryProbe.create();

        AudioBenchmarkMemoryProbe.RunResult result = probe.measureTimedRun(() -> {
            for (int i = 0; i < 4; i++) {
                driver.read(buffer);
            }
        });

        assertTrue(!result.allocatedBytesSupported() || result.allocatedBytes() >= 0,
                "Allocated bytes delta should be non-negative when supported");
        assertTrue(result.gcCountDelta() >= 0, "GC count delta should be non-negative");
        assertTrue(result.gcTimeDeltaMs() >= 0, "GC time delta should be non-negative");
    }
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkMemoryProbeReportsNonNegativeDeltasForAudioRenderWorkload" test
```

Expected:
- FAIL with compilation errors because `AudioBenchmarkMemoryProbe` does not exist yet

- [ ] **Step 3: Create the benchmark probe helper**

Create `src/test/java/com/openggf/audio/AudioBenchmarkMemoryProbe.java` with this implementation:

```java
package com.openggf.audio;

import com.sun.management.ThreadMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

public final class AudioBenchmarkMemoryProbe {
    private final MemoryMXBean memoryBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final ThreadMXBean threadBean;
    private final long threadId;
    private final boolean threadAllocatedBytesSupported;

    private AudioBenchmarkMemoryProbe() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        this.threadId = Thread.currentThread().getId();
        this.threadAllocatedBytesSupported =
                threadBean.isThreadAllocatedMemorySupported() && threadBean.isThreadAllocatedMemoryEnabled();
    }

    public static AudioBenchmarkMemoryProbe create() {
        return new AudioBenchmarkMemoryProbe();
    }

    public RunResult measureTimedRun(Runnable workload) {
        Snapshot before = snapshot();
        long start = System.nanoTime();
        workload.run();
        long elapsedNanos = System.nanoTime() - start;
        Snapshot after = snapshot();
        return RunResult.from(before, after, elapsedNanos);
    }

    public long measurePeakHeapBytes(Runnable replayStep, int iterations) {
        long peak = snapshot().heapUsedBytes();
        for (int i = 0; i < iterations; i++) {
            replayStep.run();
            long heapUsed = snapshot().heapUsedBytes();
            if (heapUsed > peak) {
                peak = heapUsed;
            }
        }
        return peak;
    }

    private Snapshot snapshot() {
        return new Snapshot(
                getHeapUsedBytes(),
                getGcCount(),
                getGcTimeMs(),
                getThreadAllocatedBytesOrMinusOne()
        );
    }

    private long getHeapUsedBytes() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        return heap.getUsed();
    }

    private long getGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long count = gcBean.getCollectionCount();
            if (count >= 0) {
                total += count;
            }
        }
        return total;
    }

    private long getGcTimeMs() {
        long total = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long time = gcBean.getCollectionTime();
            if (time >= 0) {
                total += time;
            }
        }
        return total;
    }

    private long getThreadAllocatedBytesOrMinusOne() {
        if (!threadAllocatedBytesSupported) {
            return -1L;
        }
        try {
            return threadBean.getThreadAllocatedBytes(threadId);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private record Snapshot(long heapUsedBytes, long gcCount, long gcTimeMs, long threadAllocatedBytes) {
    }

    public record RunResult(
            long elapsedNanos,
            long allocatedBytes,
            boolean allocatedBytesSupported,
            long heapUsedBeforeBytes,
            long heapUsedAfterBytes,
            long heapUsedDeltaBytes,
            long gcCountDelta,
            long gcTimeDeltaMs
    ) {
        private static RunResult from(Snapshot before, Snapshot after, long elapsedNanos) {
            boolean supported = before.threadAllocatedBytes() >= 0 && after.threadAllocatedBytes() >= 0;
            long allocatedBytes = supported ? Math.max(0L, after.threadAllocatedBytes() - before.threadAllocatedBytes()) : -1L;
            return new RunResult(
                    elapsedNanos,
                    allocatedBytes,
                    supported,
                    before.heapUsedBytes(),
                    after.heapUsedBytes(),
                    after.heapUsedBytes() - before.heapUsedBytes(),
                    Math.max(0L, after.gcCount() - before.gcCount()),
                    Math.max(0L, after.gcTimeMs() - before.gcTimeMs())
            );
        }
    }
}
```

- [ ] **Step 4: Wire the probe into the two benchmark entry points**

Add these helper methods to `AudioRegressionTest.java` below the benchmark methods:

```java
    private static double bytesToMb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private static String formatAllocatedBytes(AudioBenchmarkMemoryProbe.RunResult result) {
        return result.allocatedBytesSupported()
                ? String.format("%.3f MB", bytesToMb(result.allocatedBytes()))
                : "N/A";
    }

    private static void printMemoryMetrics(
            AudioBenchmarkMemoryProbe.RunResult result,
            long peakHeapBytes,
            int readCount,
            double normalizedRunUnits,
            String normalizedLabel
    ) {
        System.out.println("Allocated bytes during run: " + formatAllocatedBytes(result));
        System.out.println("Heap used before: " + bytesToMb(result.heapUsedBeforeBytes()) + " MB");
        System.out.println("Heap used after: " + bytesToMb(result.heapUsedAfterBytes()) + " MB");
        System.out.println("Heap used delta: " + bytesToMb(result.heapUsedDeltaBytes()) + " MB");
        System.out.println("GC count delta: " + result.gcCountDelta());
        System.out.println("GC time delta: " + result.gcTimeDeltaMs() + " ms");
        System.out.println("Peak heap during replay: " + bytesToMb(peakHeapBytes) + " MB");
        if (result.allocatedBytesSupported()) {
            System.out.println("KB per read: " + (result.allocatedBytes() / 1024.0 / readCount));
            System.out.println(normalizedLabel + ": " + (bytesToMb(result.allocatedBytes()) / normalizedRunUnits));
        } else {
            System.out.println("KB per read: N/A");
            System.out.println(normalizedLabel + ": N/A");
        }
    }
```

Update `benchmarkAudioRendering()` so the timed loop is wrapped by the probe and peak heap is measured in an untimed replay:

```java
        AudioBenchmarkMemoryProbe probe = AudioBenchmarkMemoryProbe.create();
        AudioBenchmarkMemoryProbe.RunResult runResult = probe.measureTimedRun(() -> {
            for (int i = 0; i < iterations; i++) {
                driver.read(buffer);
            }
        });

        double msPerSecond = runResult.elapsedNanos() / 1_000_000.0;
        System.out.println("Audio render time: " + msPerSecond + " ms per second of audio");
        System.out.println("Real-time factor: " + (1000.0 / msPerSecond) + "x");

        SmpsDriver replayDriver = new SmpsDriver(SAMPLE_RATE);
        replayDriver.setRegion(SmpsSequencer.Region.NTSC);
        SmpsSequencer replaySeq = new SmpsSequencer(musicData, dacData, replayDriver, Sonic2SmpsSequencerConfig.CONFIG);
        replaySeq.setSampleRate(SAMPLE_RATE);
        replayDriver.addSequencer(replaySeq, false);
        short[] replayBuffer = new short[BUFFER_SIZE * 2];
        long peakHeapBytes = probe.measurePeakHeapBytes(() -> replayDriver.read(replayBuffer), iterations);

        printMemoryMetrics(runResult, peakHeapBytes, iterations, 1.0, "MB per audio-second");
```

Extend `ResultsTallyStressBenchmarkResult` to carry the memory result and peak heap:

```java
    private record ResultsTallyStressBenchmarkResult(
            ResultsTallyStressPlan plan,
            long elapsedNanos,
            AudioBenchmarkMemoryProbe.RunResult memory,
            long peakHeapBytes
    ) {
    }
```

Then update `measureResultsTallyStressRendering()` to time with the probe and run an untimed replay using `executeResultsTallyStressPlan(...)`.

- [ ] **Step 5: Run the focused probe test again**

Run:

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkMemoryProbeReportsNonNegativeDeltasForAudioRenderWorkload" test
```

Expected:
- PASS

- [ ] **Step 6: Run the full regression gate**

Run the shared regression command from the top of this plan.

Expected:
- PASS

- [ ] **Step 7: Commit the benchmark-memory change**

```powershell
git add src/test/java/com/openggf/audio/AudioBenchmarkMemoryProbe.java src/test/java/com/openggf/audio/AudioRegressionTest.java
git commit -m "test: add memory metrics to audio benchmarks"
```

---

### Task 2: Record the 10-Run Baseline

**Files:**
- Create: `docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md`

- [ ] **Step 1: Create the baseline note template**

Create `docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md` with this skeleton:

~~~markdown
# Audio Memory Benchmark Baseline 2026-04-17

## Preconditions

- Regression gate status:
- Audio reference assets present:
- Sonic 2 ROM present:

## Benchmark Command

```powershell
mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkAudioRendering+benchmarkResultsTallyStressRendering" test
~~~

## Raw Runs

| Run | Audio ms/sec | Audio x realtime | Audio alloc MB | Audio heap delta MB | Audio peak heap MB | Audio GC count | Tally ms/run | Tally x realtime | Tally alloc MB | Tally heap delta MB | Tally peak heap MB | Tally GC count |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | | | | | | | | | | | | |
| 2 | | | | | | | | | | | | |
| 3 | | | | | | | | | | | | |
| 4 | | | | | | | | | | | | |
| 5 | | | | | | | | | | | | |
| 6 | | | | | | | | | | | | |
| 7 | | | | | | | | | | | | |
| 8 | | | | | | | | | | | | |
| 9 | | | | | | | | | | | | |
| 10 | | | | | | | | | | | | |

## Summary

### Audio Render Benchmark

- Median:
- Mean:
- Min:
- Max:

### Results Tally Stress Benchmark

- Median:
- Mean:
- Min:
- Max:

## Notes

- 
```

- [ ] **Step 2: Run the regression gate before baseline**

Run the shared regression command.

Expected:
- PASS

- [ ] **Step 3: Run the benchmark loop 10 times**

Run:

```powershell
1..10 | ForEach-Object { mvn -Dmse=off "-Dtest=AudioRegressionTest#benchmarkAudioRendering+benchmarkResultsTallyStressRendering" test }
```

Expected:
- 10 successful benchmark runs
- Each run prints timing and memory metrics for both benchmark methods

- [ ] **Step 4: Record raw runs and summary stats**

Populate `docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md` with the 10 run outputs.

Expected:
- 10 raw runs captured
- Median/mean/min/max computed for both benchmark families

- [ ] **Step 5: Commit the baseline notes**

```powershell
git add docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md
git commit -m "docs: record audio memory benchmark baseline"
```

---

### Task 3: Create the Step-Results Ledger

**Files:**
- Create: `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`

- [ ] **Step 1: Create the step-results template**

Create `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md` with this initial content:

~~~markdown
# Audio Memory Benchmark Step Results 2026-04-17

## Baseline Reference

- Audio render median:
- Audio render alloc median:
- Audio render peak heap median:
- Results tally median:
- Results tally alloc median:
- Results tally peak heap median:

## Step Template

### Step N - Name

- Files changed:
- Regression gate:
- Benchmark runs:
- Audio median before:
- Audio median after:
- Audio delta:
- Audio alloc median before:
- Audio alloc median after:
- Audio alloc delta:
- Audio peak heap median before:
- Audio peak heap median after:
- Audio peak heap delta:
- Tally median before:
- Tally median after:
- Tally delta:
- Tally alloc median before:
- Tally alloc median after:
- Tally alloc delta:
- Tally peak heap median before:
- Tally peak heap median after:
- Tally peak heap delta:
- Notes:
- Decision:
~~~

- [ ] **Step 2: Seed the baseline reference values**

Copy the baseline medians from `docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md` into the top section of the step-results file.

- [ ] **Step 3: Commit the ledger**

```powershell
git add docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "docs: add audio memory benchmark step ledger"
```

---

### Task 4: Candidate 1 - BlipResampler History Right-Sizing

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/BlipResampler.java`
- Modify: `src/test/java/com/openggf/tests/TestBlipResampler.java`
- Modify if needed: `src/test/java/com/openggf/tests/TestYm2612ChipBasics.java`
- Update: `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`

- [ ] **Step 1: Add a failing structural test for retained history size**

Add this test to `TestBlipResampler.java`:

```java
    @Test
    void historyBuffersUseDocumentedCapacity() throws Exception {
        BlipResampler resampler = new BlipResampler(53267.041666666664, 44100.0);

        java.lang.reflect.Field historyL = BlipResampler.class.getDeclaredField("historyL");
        java.lang.reflect.Field historyR = BlipResampler.class.getDeclaredField("historyR");
        historyL.setAccessible(true);
        historyR.setAccessible(true);

        int[] left = (int[]) historyL.get(resampler);
        int[] right = (int[]) historyR.get(resampler);

        assertEquals(left.length, right.length, "Stereo histories should stay symmetric");
        assertTrue(left.length <= 8192, "History capacity should be reduced from the old 16384-sample footprint");
    }
```

- [ ] **Step 2: Run the targeted test to verify failure**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestBlipResampler#historyBuffersUseDocumentedCapacity+TestBlipResampler#deterministicStereoSequenceRemainsBitExact+TestBlipResampler#repeatedChannelReadsWithoutStateChangeRemainStable" test
```

Expected:
- FAIL because the current history arrays are length `16384`

- [ ] **Step 3: Reduce the retained history footprint without changing interpolation math**

Modify `BlipResampler.java` so the circular-buffer capacity is smaller but still safely exceeds the required interpolation horizon:

```java
    private static final int BUFFER_SIZE = 1 << 13;
    private static final int BUFFER_MASK = BUFFER_SIZE - 1;
```

Do not change:
- `FILTER_TAPS`
- `PHASE_BITS`
- `SINC_TABLE`
- `interpolate(...)`
- `sampleAt(...)` arithmetic apart from using the new constants

- [ ] **Step 4: Run the focused parity tests**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestBlipResampler,TestYm2612ChipBasics" test
```

Expected:
- PASS

- [ ] **Step 5: Run the full regression gate**

Run the shared regression command.

Expected:
- PASS

- [ ] **Step 6: Run the benchmark loop 10 times**

Run the shared 10-run benchmark loop.

Expected:
- 10 successful runs with timing and memory metrics

- [ ] **Step 7: Record the candidate-1 results and decision**

Add a `Step 1 - BlipResampler History Right-Sizing` entry to `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md` and fill in:
- files changed
- regression status
- 10-run medians and deltas
- keep or revert decision

- [ ] **Step 8: Commit or revert**

If kept:

```powershell
git add src/main/java/com/openggf/audio/synth/BlipResampler.java src/test/java/com/openggf/tests/TestBlipResampler.java docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "perf: reduce blip resampler history footprint"
```

If reverted:

```powershell
git checkout -- src/main/java/com/openggf/audio/synth/BlipResampler.java src/test/java/com/openggf/tests/TestBlipResampler.java
git add docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "docs: record rejected blip resampler history experiment"
```

---

### Task 5: Candidate 2 - SmpsDriver Chunk Scratch Reuse

**Files:**
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java`
- Update: `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`

- [ ] **Step 1: Tighten the chunk-scratch behavior test**

Add this test to `TestSmpsDriverBatching.java`:

```java
    @Test
    public void renderChunkReusesSharedScratchForSmallerChunkLengths() throws Exception {
        SmpsDriver driver = new SmpsDriver();
        short[] originalScratch = new short[64];
        setChunkScratch(driver, originalScratch);

        invokeRenderChunk(driver, new short[32], 0, 16);
        short[] afterFirst = chunkScratch(driver);

        invokeRenderChunk(driver, new short[16], 0, 8);
        short[] afterSecond = chunkScratch(driver);

        assertSame(originalScratch, afterFirst);
        assertSame(originalScratch, afterSecond);
    }
```

- [ ] **Step 2: Run the focused batching tests**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries" test
```

Expected:
- The new behavior test PASSes after the implementation
- The exact hybrid/sample-accurate identity tests remain green

- [ ] **Step 3: Remove the smaller-chunk fallback allocation**

Update `SmpsDriver.renderChunk(...)` to reuse `chunkScratch` for any smaller chunk by rendering into the shared oversized buffer and copying only `sampleCount` samples:

```java
    private void renderChunk(short[] target, int frameOffset, int frames) {
        int sampleCount = frames * 2;
        if (chunkScratch.length < sampleCount) {
            chunkScratch = new short[sampleCount];
        }

        super.render(chunkScratch);
        System.arraycopy(chunkScratch, 0, target, frameOffset * 2, sampleCount);
    }
```

Do not change any logic outside `renderChunk(...)`.

- [ ] **Step 4: Run the focused tests again**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries" test
```

Expected:
- PASS

- [ ] **Step 5: Run the full regression gate**

Run the shared regression command.

Expected:
- PASS

- [ ] **Step 6: Run the benchmark loop 10 times**

Run the shared 10-run benchmark loop.

Expected:
- 10 successful runs

- [ ] **Step 7: Record the candidate-2 results and decision**

Add a `Step 2 - SmpsDriver Chunk Scratch Reuse` entry to `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`.

- [ ] **Step 8: Commit or revert**

If kept:

```powershell
git add src/main/java/com/openggf/audio/driver/SmpsDriver.java src/test/java/com/openggf/tests/TestSmpsDriverBatching.java docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "perf: reuse smps driver chunk scratch"
```

If reverted:

```powershell
git checkout -- src/main/java/com/openggf/audio/driver/SmpsDriver.java src/test/java/com/openggf/tests/TestSmpsDriverBatching.java
git add docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "docs: record rejected smps driver scratch experiment"
```

---

### Task 6: Candidate 3 - SmpsSequencer Hot-Path Array Hoisting

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Create: `src/test/java/com/openggf/tests/TestSmpsSequencerAllocationPaths.java`
- Update: `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`

- [ ] **Step 1: Add a failing structural test for hoisted lookup tables**

Create `src/test/java/com/openggf/tests/TestSmpsSequencerAllocationPaths.java` with this content:

```java
package com.openggf.tests;

import com.openggf.audio.smps.SmpsSequencer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSmpsSequencerAllocationPaths {

    @Test
    public void operatorIndexTablesAreHoistedToStaticFinalFields() throws Exception {
        Field tlIdx = SmpsSequencer.class.getDeclaredField("TL_INDEXES");
        Field opMap = SmpsSequencer.class.getDeclaredField("VOICE_OPERATOR_ORDER");

        assertTrue(Modifier.isStatic(tlIdx.getModifiers()) && Modifier.isFinal(tlIdx.getModifiers()));
        assertTrue(Modifier.isStatic(opMap.getModifiers()) && Modifier.isFinal(opMap.getModifiers()));
    }
}
```

- [ ] **Step 2: Run the focused test to verify failure**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestSmpsSequencerAllocationPaths" test
```

Expected:
- FAIL because the fields do not exist yet

- [ ] **Step 3: Hoist the inline arrays in `SmpsSequencer`**

Add these fields near the other static constants in `SmpsSequencer.java`:

```java
    private static final int[] TL_INDEXES = {21, 23, 22, 24};
    private static final int[] VOICE_OPERATOR_ORDER = {0, 2, 1, 3};
```

Then update the two hot paths:

```java
        int[] tlIdx = TL_INDEXES;
```

and

```java
            int[] opMap = VOICE_OPERATOR_ORDER;
```

No other behavior changes belong in this step.

- [ ] **Step 4: Run the focused tests**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestSmpsSequencerAllocationPaths,TestSmpsDriverBatching,TestSmpsSequencerBatchBoundaries" test
```

Expected:
- PASS

- [ ] **Step 5: Run the full regression gate**

Run the shared regression command.

Expected:
- PASS

- [ ] **Step 6: Run the benchmark loop 10 times**

Run the shared 10-run benchmark loop.

Expected:
- 10 successful runs

- [ ] **Step 7: Record the candidate-3 results and decision**

Add a `Step 3 - SmpsSequencer Hot-Path Array Hoisting` entry to `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`.

- [ ] **Step 8: Commit or revert**

If kept:

```powershell
git add src/main/java/com/openggf/audio/smps/SmpsSequencer.java src/test/java/com/openggf/tests/TestSmpsSequencerAllocationPaths.java docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "perf: hoist smps sequencer operator index tables"
```

If reverted:

```powershell
git checkout -- src/main/java/com/openggf/audio/smps/SmpsSequencer.java src/test/java/com/openggf/tests/TestSmpsSequencerAllocationPaths.java
git add docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "docs: record rejected smps sequencer hoisting experiment"
```

---

### Task 7: Candidate 4 - VirtualSynthesizer Scratch / Mix Consolidation

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java`
- Modify: `src/test/java/com/openggf/tests/TestVirtualSynthesizerMix.java`
- Update: `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`

- [ ] **Step 1: Add a focused render-shape test**

Add this test to `TestVirtualSynthesizerMix.java` and add `import static org.junit.jupiter.api.Assertions.assertSame;`:

```java
    @Test
    public void renderReusesExistingStereoScratchAcrossSmallerBuffers() throws Exception {
        VirtualSynthesizer synth = new VirtualSynthesizer();

        short[] first = new short[64];
        short[] second = new short[16];

        synth.render(first);

        java.lang.reflect.Field scratchLeft = VirtualSynthesizer.class.getDeclaredField("scratchLeft");
        java.lang.reflect.Field scratchRight = VirtualSynthesizer.class.getDeclaredField("scratchRight");
        scratchLeft.setAccessible(true);
        scratchRight.setAccessible(true);

        int[] leftBefore = (int[]) scratchLeft.get(synth);
        int[] rightBefore = (int[]) scratchRight.get(synth);

        synth.render(second);

        int[] leftAfter = (int[]) scratchLeft.get(synth);
        int[] rightAfter = (int[]) scratchRight.get(synth);

        assertSame(leftBefore, leftAfter);
        assertSame(rightBefore, rightAfter);
    }
```

- [ ] **Step 2: Run the focused virtual-synth tests**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestVirtualSynthesizerMix" test
```

Expected:
- Existing exact-output test remains the hard parity check

- [ ] **Step 3: Consolidate the scratch handling without changing mix/clamp order**

Refactor `render(short[] buffer)` so buffer-size growth still happens once, but the body uses local aliases and a single scratch clear path:

```java
    public void render(short[] buffer) {
        int frames = buffer.length / 2;

        if (scratchLeft.length < frames) {
            scratchLeft = new int[frames];
            scratchRight = new int[frames];
        }

        int[] left = scratchLeft;
        int[] right = scratchRight;
        Arrays.fill(left, 0, frames, 0);
        Arrays.fill(right, 0, frames, 0);

        ym.renderStereo(left, right, frames);
        psg.renderStereo(left, right, frames);

        for (int i = 0; i < frames; i++) {
            int l = left[i];
            int r = right[i];

            if (MASTER_GAIN_SHIFT > 0) {
                l >>= MASTER_GAIN_SHIFT;
                r >>= MASTER_GAIN_SHIFT;
            }

            if (l > 32767) l = 32767; else if (l < -32768) l = -32768;
            if (r > 32767) r = 32767; else if (r < -32768) r = -32768;

            buffer[i * 2] = (short) l;
            buffer[i * 2 + 1] = (short) r;
        }
    }
```

Keep the FM and PSG render ordering unchanged.

- [ ] **Step 4: Run the focused tests again**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestVirtualSynthesizerMix,TestYm2612ChipBasics,TestPsgChipGpgxParity" test
```

Expected:
- PASS

- [ ] **Step 5: Run the full regression gate**

Run the shared regression command.

Expected:
- PASS

- [ ] **Step 6: Run the benchmark loop 10 times**

Run the shared 10-run benchmark loop.

Expected:
- 10 successful runs

- [ ] **Step 7: Record the candidate-4 results and decision**

Add a `Step 4 - VirtualSynthesizer Scratch / Mix Consolidation` entry to `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`.

- [ ] **Step 8: Commit or revert**

If kept:

```powershell
git add src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java src/test/java/com/openggf/tests/TestVirtualSynthesizerMix.java docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "perf: consolidate virtual synthesizer scratch usage"
```

If reverted:

```powershell
git checkout -- src/main/java/com/openggf/audio/synth/VirtualSynthesizer.java src/test/java/com/openggf/tests/TestVirtualSynthesizerMix.java
git add docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "docs: record rejected virtual synthesizer scratch experiment"
```

---

### Task 8: Final Comparison and Review

**Files:**
- Review: `docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md`
- Review: `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md`

- [ ] **Step 1: Produce the final comparison table**

Add a final section to `docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md` with this shape:

~~~markdown
## Final Comparison

| Step | Audio median ms/sec | Audio alloc MB | Audio peak heap MB | Tally median ms/run | Tally alloc MB | Tally peak heap MB | Decision |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Baseline | | | | | | | Reference |
| Candidate 1 | | | | | | | |
| Candidate 2 | | | | | | | |
| Candidate 3 | | | | | | | |
| Candidate 4 | | | | | | | |
~~~

- [ ] **Step 2: Run the regression gate one last time**

Run the shared regression command.

Expected:
- PASS

- [ ] **Step 3: Commit the final documentation update**

```powershell
git add docs/architecture/validation/audio-memory-benchmark-baseline-2026-04-17.md docs/architecture/validation/audio-memory-benchmark-step-results-2026-04-17.md
git commit -m "docs: summarize audio memory optimization benchmark results"
```
