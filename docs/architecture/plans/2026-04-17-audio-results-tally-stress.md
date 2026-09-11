# Audio Results Tally Stress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic Sonic 2 results-tally stress case to the audio regression suite that proves `HYBRID` output matches `SAMPLE_ACCURATE` output under rapid `BLIP` SFX churn and provides a dedicated SFX-heavy benchmark.

**Architecture:** Keep the implementation entirely inside `AudioRegressionTest`. Build an explicit tally trigger schedule in game frames, convert it to exact audio frame boundaries, and render only up to the next trigger boundary so SFX starts happen at the same cadence as the real results screen. Reuse the same tally render helper for both the new identity regression and the new benchmark.

**Tech Stack:** Java 21, Maven, JUnit 5, existing Sonic 2 SMPS loader/test ROM harness

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `src/test/java/com/openggf/audio/AudioRegressionTest.java` | Modify | Add Sonic 2 tally SFX constants, exact-schedule tally render helper, hybrid identity regression, and tally-stress benchmark |
| `docs/architecture/plans/2026-04-17-audio-results-tally-stress.md` | Create | Implementation plan for the new tally stress test/benchmark |

---

### Task 1: Add the failing Sonic 2 tally identity regression

**Files:**
- Modify: `src/test/java/com/openggf/audio/AudioRegressionTest.java`

- [ ] **Step 1: Write the failing identity regression and schedule constants**

Add the Sonic 2 tally SFX constants near the existing `SFX_RING`, `SFX_JUMP`, and `SFX_SPRING` definitions:

```java
    private static final int SFX_BLIP = 0xCD;
    private static final int SFX_TALLY_END = 0xC5;
```

Add fixed tally schedule constants near the other test constants:

```java
    private static final int GAME_FPS = 60;
    private static final int RESULTS_TALLY_TICK_INTERVAL_FRAMES = 4;
    private static final int RESULTS_TALLY_LAST_BLIP_FRAME = 176;
    private static final int RESULTS_TALLY_END_FRAME = 180;
    private static final int RESULTS_TALLY_TAIL_FRAMES = 30;
```

Add the new regression test below `testHybridReadModeMatchesFallbackForMixedMusicSfx()`:

```java
    @Test
    public void testHybridReadModeMatchesFallbackForResultsTallyStress() {
        assumeTrue(loader != null, "ROM not available, skipping tally stress identity test");

        int totalFrames = toAudioFrames(RESULTS_TALLY_END_FRAME + RESULTS_TALLY_TAIL_FRAMES);
        int totalSamples = totalFrames * 2;

        short[] sampleAccurate = renderResultsTallyStressWithMode(
                SmpsDriver.ReadMode.SAMPLE_ACCURATE,
                totalSamples
        );
        short[] hybrid = renderResultsTallyStressWithMode(
                SmpsDriver.ReadMode.HYBRID,
                totalSamples
        );

        assertTrue(hasNonZeroSample(sampleAccurate),
                "Results tally stress should produce audible non-zero PCM output");
        assertArrayEquals(
                sampleAccurate,
                hybrid,
                "Hybrid read mode must match fallback output for results tally stress"
        );
    }
```

Add a temporary red-phase stub near the existing `renderMixedWithMode(...)` helper:

```java
    private short[] renderResultsTallyStressWithMode(SmpsDriver.ReadMode mode, int totalSamples) {
        return new short[totalSamples];
    }

    private boolean hasNonZeroSample(short[] audio) {
        for (short sample : audio) {
            if (sample != 0) {
                return true;
            }
        }
        return false;
    }

    private int toAudioFrames(int gameFrames) {
        return (int) ((SAMPLE_RATE / GAME_FPS) * gameFrames);
    }
```

The stub is intentional. It gives the test a concrete method to call while still failing at runtime because the rendered PCM is silent.

- [ ] **Step 2: Run the new test and verify it fails for the right reason**

Run:

```powershell
mvn -Dmse=off -Dtest=AudioRegressionTest#testHybridReadModeMatchesFallbackForResultsTallyStress test
```

Expected:

- `FAILURE`
- the new test runs
- failure is the new non-zero-output assertion because the helper currently returns silence instead of real tally audio

- [ ] **Step 3: Commit the red test**

```powershell
git add src/test/java/com/openggf/audio/AudioRegressionTest.java
git commit -m "test: add failing results tally stress identity case"
```

### Task 2: Implement exact-boundary tally rendering and make the identity test pass

**Files:**
- Modify: `src/test/java/com/openggf/audio/AudioRegressionTest.java`

- [ ] **Step 1: Replace the stub with an exact trigger-boundary render helper**

Replace the red stub with a real helper and supporting helpers:

```java
    private short[] renderResultsTallyStressWithMode(SmpsDriver.ReadMode mode, int totalSamples) {
        AbstractSmpsData blipData = loader.loadSfx(SFX_BLIP);
        AbstractSmpsData tallyEndData = loader.loadSfx(SFX_TALLY_END);

        assertNotNull(blipData, "SFX data should load for BLIP");
        assertNotNull(tallyEndData, "SFX data should load for TALLY_END");

        SmpsDriver driver = new SmpsDriver(SAMPLE_RATE);
        driver.setRegion(SmpsSequencer.Region.NTSC);
        driver.setReadModeForTesting(mode);

        int[] blipTriggerFrames = buildResultsTallyBlipFrameSchedule();
        int blipIndex = 0;
        boolean tallyEndTriggered = false;
        int tallyEndAudioFrame = toAudioFrames(RESULTS_TALLY_END_FRAME);

        short[] audio = new short[totalSamples];
        short[] buffer = new short[BUFFER_SIZE * 2];
        int samplesWritten = 0;

        while (samplesWritten < totalSamples) {
            int currentAudioFrame = samplesWritten / 2;

            while (blipIndex < blipTriggerFrames.length
                    && currentAudioFrame >= blipTriggerFrames[blipIndex]) {
                addSfxSequencer(driver, blipData);
                blipIndex++;
            }

            if (!tallyEndTriggered && currentAudioFrame >= tallyEndAudioFrame) {
                addSfxSequencer(driver, tallyEndData);
                tallyEndTriggered = true;
            }

            int nextTriggerAudioFrame = totalSamples / 2;
            if (blipIndex < blipTriggerFrames.length) {
                nextTriggerAudioFrame = Math.min(nextTriggerAudioFrame, blipTriggerFrames[blipIndex]);
            }
            if (!tallyEndTriggered) {
                nextTriggerAudioFrame = Math.min(nextTriggerAudioFrame, tallyEndAudioFrame);
            }

            int framesUntilNextTrigger = nextTriggerAudioFrame - currentAudioFrame;
            int framesToRead = framesUntilNextTrigger > 0
                    ? Math.min(BUFFER_SIZE, framesUntilNextTrigger)
                    : Math.min(BUFFER_SIZE, (totalSamples - samplesWritten) / 2);
            int samplesToRead = Math.min(framesToRead * 2, totalSamples - samplesWritten);

            driver.read(buffer);
            System.arraycopy(buffer, 0, audio, samplesWritten, samplesToRead);
            samplesWritten += samplesToRead;
        }

        return audio;
    }

    private boolean hasNonZeroSample(short[] audio) {
        for (short sample : audio) {
            if (sample != 0) {
                return true;
            }
        }
        return false;
    }

    private int[] buildResultsTallyBlipFrameSchedule() {
        int triggerCount = (RESULTS_TALLY_LAST_BLIP_FRAME / RESULTS_TALLY_TICK_INTERVAL_FRAMES) + 1;
        int[] triggerFrames = new int[triggerCount];
        for (int i = 0; i < triggerCount; i++) {
            triggerFrames[i] = toAudioFrames(i * RESULTS_TALLY_TICK_INTERVAL_FRAMES);
        }
        return triggerFrames;
    }

    private void addSfxSequencer(SmpsDriver driver, AbstractSmpsData sfxData) {
        SmpsSequencer sfxSeq = new SmpsSequencer(sfxData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        sfxSeq.setSampleRate(SAMPLE_RATE);
        sfxSeq.setSfxMode(true);
        driver.addSequencer(sfxSeq, true);
    }
```

Then fix the `driver.read(buffer)` call so the exact-boundary loop does not overcopy stale samples from earlier iterations. Replace it with a boundary-sized scratch read:

```java
            short[] stepBuffer = samplesToRead == buffer.length ? buffer : new short[samplesToRead];
            driver.read(stepBuffer);
            System.arraycopy(stepBuffer, 0, audio, samplesWritten, samplesToRead);
```

This is the key correctness point of the implementation: the helper must read only up to the next trigger boundary, not blindly one full outer buffer at a time.

- [ ] **Step 2: Run the identity test and verify it passes**

Run:

```powershell
mvn -Dmse=off -Dtest=AudioRegressionTest#testHybridReadModeMatchesFallbackForResultsTallyStress test
```

Expected:

- `BUILD SUCCESS`
- the new tally identity test passes

- [ ] **Step 3: Run the existing mixed identity test to make sure the new helper did not disturb the file**

Run:

```powershell
mvn -Dmse=off -Dtest=AudioRegressionTest#testHybridReadModeMatchesFallbackForMixedMusicSfx test
```

Expected:

- `BUILD SUCCESS`
- the existing mixed music/SFX identity test still passes

- [ ] **Step 4: Commit the green helper**

```powershell
git add src/test/java/com/openggf/audio/AudioRegressionTest.java
git commit -m "test: add exact results tally stress renderer"
```

### Task 3: Add the tally-stress benchmark and verify both performance lenses

**Files:**
- Modify: `src/test/java/com/openggf/audio/AudioRegressionTest.java`

- [ ] **Step 1: Write the failing benchmark method**

Add the benchmark method below `benchmarkAudioRendering()`:

```java
    @Test
    public void benchmarkResultsTallyStressRendering() {
        assumeTrue(loader != null, "ROM not available, skipping tally stress benchmark");

        double msPerRun = measureResultsTallyStressRenderingMs();
        double totalDurationMs = ((RESULTS_TALLY_END_FRAME + RESULTS_TALLY_TAIL_FRAMES) * 1000.0) / GAME_FPS;

        System.out.println("Results tally stress render time: " + msPerRun + " ms per stress run");
        System.out.println("Results tally stress real-time factor: " + (totalDurationMs / msPerRun) + "x");

        assertTrue(msPerRun < 500.0,
                "Results tally stress rendering should complete in reasonable time");
    }
```

Add a temporary red-phase stub near the helper section:

```java
    private double measureResultsTallyStressRenderingMs() {
        return Double.POSITIVE_INFINITY;
    }
```

- [ ] **Step 2: Run the benchmark test and verify it fails**

Run:

```powershell
mvn -Dmse=off -Dtest=AudioRegressionTest#benchmarkResultsTallyStressRendering test
```

Expected:

- `FAILURE`
- assertion fails because the temporary measurement helper returns infinity

- [ ] **Step 3: Implement the measurement helper using the exact tally renderer**

Replace the stub with:

```java
    private double measureResultsTallyStressRenderingMs() {
        int totalFrames = toAudioFrames(RESULTS_TALLY_END_FRAME + RESULTS_TALLY_TAIL_FRAMES);
        int totalSamples = totalFrames * 2;

        renderResultsTallyStressWithMode(SmpsDriver.ReadMode.HYBRID, totalSamples);

        long start = System.nanoTime();
        renderResultsTallyStressWithMode(SmpsDriver.ReadMode.HYBRID, totalSamples);
        long elapsed = System.nanoTime() - start;

        return elapsed / 1_000_000.0;
    }
```

This keeps the benchmark aligned with the real stress workload and avoids introducing a second render path that can drift from the identity regression helper.

- [ ] **Step 4: Run the new benchmark and the existing music benchmark**

Run the new stress benchmark:

```powershell
mvn -Dmse=off -Dtest=AudioRegressionTest#benchmarkResultsTallyStressRendering test
```

Expected:

- `BUILD SUCCESS`
- output includes `Results tally stress render time:`
- output includes `Results tally stress real-time factor:`

Run the existing music benchmark:

```powershell
mvn -Dmse=off -Dtest=AudioRegressionTest#benchmarkAudioRendering test
```

Expected:

- `BUILD SUCCESS`
- output still includes `Audio render time:`
- output still includes `Real-time factor:`

- [ ] **Step 5: Run the full audio regression class**

Run:

```powershell
mvn -Dmse=off -Dtest=AudioRegressionTest test
```

Expected:

- `BUILD SUCCESS`
- the new tally identity test passes
- the new tally benchmark passes
- the existing audio regression coverage remains green or skipped only for missing reference assets

- [ ] **Step 6: Commit the benchmark**

```powershell
git add src/test/java/com/openggf/audio/AudioRegressionTest.java
git commit -m "test: add results tally stress benchmark"
```

## Self-Review

- Spec coverage: the plan adds the fixed Sonic 2 tally schedule, the exact-boundary render helper, the hybrid-vs-sample-accurate identity regression, and the dedicated tally-stress benchmark while keeping the existing music benchmark in place.
- Placeholder scan: no `TBD`, `TODO`, or implied “do the obvious thing later” steps remain.
- Type consistency: the same helper names are used throughout the plan: `renderResultsTallyStressWithMode(...)`, `buildResultsTallyBlipFrameSchedule()`, `addSfxSequencer(...)`, `measureResultsTallyStressRenderingMs()`, and `toAudioFrames(...)`.
