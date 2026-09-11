# Audio Batching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a conservative hybrid batching path to `SmpsDriver.read(...)` that lowers CPU cost without adding latency, while keeping batched output bit-identical to the existing sample-accurate path.

**Architecture:** Keep `SmpsSequencer` responsible for conservative boundary reporting and fallback detection, and keep `SmpsDriver` responsible for choosing between sample-accurate and hybrid chunked rendering. Implement the batching path behind an explicit dual-mode read harness so tests can compare `HYBRID` against `SAMPLE_ACCURATE` directly before the hybrid mode becomes the default path.

**Tech Stack:** Java 21, Maven, JUnit 5

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `src/main/java/com/openggf/audio/smps/SmpsSequencer.java` | Modify | Add conservative batching query APIs and fallback detection hooks |
| `src/main/java/com/openggf/audio/driver/SmpsDriver.java` | Modify | Add dual read modes, extract current sample path, implement hybrid chunk planner, and preserve current lock/removal semantics |
| `src/test/java/com/openggf/tests/TestSmpsSequencerBatchBoundaries.java` | Create | Unit tests for observable-event boundaries and explicit fallback states |
| `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java` | Create | Driver-level identity tests between `SAMPLE_ACCURATE` and `HYBRID` modes |
| `src/test/java/com/openggf/audio/AudioRegressionTest.java` | Modify | ROM-backed identity checks between fallback and hybrid modes and final benchmark wiring |

---

### Task 1: Add conservative sequencing boundary APIs with direct unit coverage

**Files:**
- Create: `src/test/java/com/openggf/tests/TestSmpsSequencerBatchBoundaries.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`

- [ ] **Step 1: Write the failing boundary-query tests**

Create `src/test/java/com/openggf/tests/TestSmpsSequencerBatchBoundaries.java`:

```java
package com.openggf.tests;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsSequencerBatchBoundaries {

    @Test
    void getSamplesUntilNextObservableEvent_reportsRemainingDurationSamples() throws Exception {
        SmpsSequencer seq = newSequencer(noteScript());
        setDouble(seq, "sampleCounter", 0.0);
        setDouble(seq, "samplesPerFrame", 8.0);
        SmpsSequencer.Track track = seq.getTracks().get(0);
        track.duration = 3;
        track.scaledDuration = 3;
        track.fill = 0;
        assertEquals(24, seq.getSamplesUntilNextObservableEvent());
    }

    @Test
    void getSamplesUntilNextObservableEvent_reportsFillBoundaryBeforeDurationExpiry() throws Exception {
        SmpsSequencer seq = newSequencer(noteScript());
        setDouble(seq, "sampleCounter", 0.0);
        setDouble(seq, "samplesPerFrame", 8.0);
        SmpsSequencer.Track track = seq.getTracks().get(0);
        track.duration = 5;
        track.scaledDuration = 5;
        track.fill = 2;
        assertEquals(16, seq.getSamplesUntilNextObservableEvent());
    }

    @Test
    void getSamplesUntilNextObservableEvent_reportsFadeDelayBoundary() throws Exception {
        SmpsSequencer seq = newSequencer(noteScript());
        Object fadeState = getObject(seq, "fadeState");
        setBoolean(fadeState, "active", true);
        setInt(fadeState, "steps", 3);
        setInt(fadeState, "delayInit", 2);
        setInt(fadeState, "delayCounter", 1);
        setDouble(seq, "sampleCounter", 0.0);
        setDouble(seq, "samplesPerFrame", 8.0);
        assertEquals(8, seq.getSamplesUntilNextObservableEvent());
    }

    @Test
    void getSamplesUntilNextObservableEvent_reportsSfxMaxTicksBoundary() throws Exception {
        SmpsSequencer seq = newSequencer(noteScript());
        seq.setSfxMode(true);
        setInt(seq, "maxTicks", 1);
        setDouble(seq, "sampleCounter", 0.0);
        setDouble(seq, "samplesPerFrame", 8.0);
        assertEquals(8, seq.getSamplesUntilNextObservableEvent());
    }

    @Test
    void requiresSampleAccurateFallback_returnsTrueForActiveFade() throws Exception {
        SmpsSequencer seq = newSequencer(noteScript());
        Object fadeState = getObject(seq, "fadeState");
        setBoolean(fadeState, "active", true);
        assertTrue(seq.requiresSampleAccurateFallback());
    }

    @Test
    void requiresSampleAccurateFallback_returnsTrueForSpeedMultiplierAboveOne() {
        SmpsSequencer seq = newSequencer(noteScript());
        seq.setSpeedMultiplier(2);
        assertTrue(seq.requiresSampleAccurateFallback());
    }

    private static SmpsSequencer newSequencer(byte[] script) {
        AbstractSmpsData smps = new Sonic2SmpsData(script);
        return new SmpsSequencer(smps, null, new VirtualSynthesizer(), Sonic2SmpsSequencerConfig.CONFIG);
    }

    private static byte[] noteScript() {
        byte[] data = new byte[32];
        data[2] = 2;
        data[4] = 1;
        data[5] = (byte) 0x80;
        data[0x0A] = 0x14;
        data[0x0B] = 0x00;
        data[0x14] = (byte) 0x81;
        data[0x15] = 0x01;
        data[0x16] = (byte) 0xF2;
        return data;
    }

    private static void setDouble(Object target, String field, double value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setDouble(target, value);
    }

    private static void setInt(Object target, String field, int value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(target, value);
    }

    private static void setBoolean(Object target, String field, boolean value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setBoolean(target, value);
    }

    private static Object getObject(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
```

- [ ] **Step 2: Run the new test class and verify it fails to compile**

Run: `mvn test -Dtest=TestSmpsSequencerBatchBoundaries`

Expected: compilation failure mentioning missing methods:
- `cannot find symbol: method getSamplesUntilNextObservableEvent()`
- `cannot find symbol: method requiresSampleAccurateFallback()`

- [ ] **Step 3: Implement the missing `SmpsSequencer` batching query APIs**

In `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`, add these methods near `advanceBatch(...)` and `getSamplesUntilNextTempoFrame()`:

```java
    public int getSamplesUntilNextObservableEvent() {
        int limit = Integer.MAX_VALUE;

        if (fadeState.active) {
            limit = Math.min(limit, getSamplesUntilNextTempoFrame());
        }

        if (sfxMode && maxTicks <= 1) {
            limit = Math.min(limit, getSamplesUntilNextTempoFrame());
        }

        for (Track t : tracks) {
            if (!t.active) {
                continue;
            }
            if (t.duration > 0) {
                int durationSamples = samplesUntilTempoTicks(t.duration);
                limit = Math.min(limit, durationSamples);

                if (t.fill > 0 && !t.tieNext && t.type != TrackType.DAC) {
                    int fillTicks = Math.max(0, t.duration - t.fill);
                    limit = Math.min(limit, samplesUntilTempoTicks(fillTicks));
                }
            } else {
                limit = 0;
            }
        }

        return Math.max(0, limit);
    }

    public boolean requiresSampleAccurateFallback() {
        return fadeState.active || speedMultiplier > 1;
    }

    private int samplesUntilTempoTicks(int ticks) {
        if (ticks <= 0) {
            return 0;
        }
        int first = getSamplesUntilNextTempoFrame();
        if (ticks == 1) {
            return first;
        }
        return first + (ticks - 1) * (int) Math.ceil(samplesPerFrame);
    }
```

- [ ] **Step 4: Run the boundary tests and verify they pass**

Run: `mvn test -Dtest=TestSmpsSequencerBatchBoundaries`

Expected: `BUILD SUCCESS` and all 6 tests pass.

- [ ] **Step 5: Commit the sequencer query work**

```bash
git add src/main/java/com/openggf/audio/smps/SmpsSequencer.java src/test/java/com/openggf/tests/TestSmpsSequencerBatchBoundaries.java
git commit -m "test: add smps batching boundary queries"
```

---

### Task 2: Add a dual-mode `SmpsDriver` read harness without changing behavior

**Files:**
- Create: `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java`
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`

- [ ] **Step 1: Write a failing driver identity test that needs explicit read modes**

Create `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java`:

```java
package com.openggf.tests;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TestSmpsDriverBatching {

    @Test
    void readModes_matchForSteadyFmSequence() {
        short[] sampleAccurate = render(SmpsDriver.ReadMode.SAMPLE_ACCURATE, steadyMusicScript(), 4096);
        short[] hybrid = render(SmpsDriver.ReadMode.HYBRID, steadyMusicScript(), 4096);
        assertArrayEquals(sampleAccurate, hybrid);
    }

    private static short[] render(SmpsDriver.ReadMode mode, byte[] script, int totalSamples) {
        SmpsDriver driver = new SmpsDriver(44100.0);
        driver.setReadModeForTesting(mode);
        AbstractSmpsData smps = new Sonic2SmpsData(script);
        SmpsSequencer seq = new SmpsSequencer(smps, null, driver, Sonic2SmpsSequencerConfig.CONFIG);
        seq.setSampleRate(44100.0);
        driver.addSequencer(seq, false);

        short[] output = new short[totalSamples];
        short[] chunk = new short[512];
        int written = 0;
        while (written < output.length) {
            int toCopy = Math.min(chunk.length, output.length - written);
            driver.read(chunk);
            System.arraycopy(chunk, 0, output, written, toCopy);
            written += toCopy;
        }
        return output;
    }

    private static byte[] steadyMusicScript() {
        byte[] data = new byte[48];
        data[2] = 2;
        data[4] = 1;
        data[5] = (byte) 0x80;
        data[0x0A] = 0x14;
        data[0x0B] = 0x00;
        data[0x14] = (byte) 0x81;
        data[0x15] = 0x20;
        data[0x16] = (byte) 0x81;
        data[0x17] = 0x20;
        data[0x18] = (byte) 0xF2;
        return data;
    }
}
```

- [ ] **Step 2: Run the new driver test and verify it fails to compile**

Run: `mvn test -Dtest=TestSmpsDriverBatching`

Expected: compilation failure mentioning missing symbols:
- `SmpsDriver.ReadMode`
- `setReadModeForTesting`

- [ ] **Step 3: Extract the current sample-accurate path and add explicit read modes**

In `src/main/java/com/openggf/audio/driver/SmpsDriver.java`, add the mode enum and a test hook near the top of the class:

```java
    public enum ReadMode {
        SAMPLE_ACCURATE,
        HYBRID
    }

    private static final int MIN_BATCH_SAMPLES = 32;
    private ReadMode readMode = ReadMode.HYBRID;

    public void setReadModeForTesting(ReadMode readMode) {
        this.readMode = readMode;
    }
```

Replace the current `read(...)` implementation with an extracted sample-accurate helper that preserves behavior:

```java
    @Override
    public int read(short[] buffer) {
        return switch (readMode) {
            case SAMPLE_ACCURATE -> readSampleAccurate(buffer);
            case HYBRID -> readSampleAccurate(buffer);
        };
    }

    private int readSampleAccurate(short[] buffer) {
        int frames = buffer.length / 2;
        synchronized (sequencersLock) {
            for (int i = 0; i < frames; i++) {
                int size = sequencers.size();
                for (int j = 0; j < size; j++) {
                    SmpsSequencer seq = sequencers.get(j);
                    seq.advance(1.0);
                    if (seq.isComplete()) {
                        pendingRemovals.add(seq);
                    }
                }
                removeCompletedSequencers();
                super.render(scratchFrameBuf);
                buffer[i * 2] = scratchFrameBuf[0];
                buffer[i * 2 + 1] = scratchFrameBuf[1];
            }
        }
        return buffer.length;
    }

    private void removeCompletedSequencers() {
        if (pendingRemovals.isEmpty()) {
            return;
        }
        for (int j = 0; j < pendingRemovals.size(); j++) {
            SmpsSequencer seq = pendingRemovals.get(j);
            sequencers.remove(seq);
            releaseLocks(seq);
            sfxSequencers.remove(seq);
        }
        pendingRemovals.clear();
    }
```

- [ ] **Step 4: Run the driver identity test and verify it passes**

Run: `mvn test -Dtest=TestSmpsDriverBatching`

Expected: `BUILD SUCCESS`. The two modes match because `HYBRID` still delegates to the extracted sample path.

- [ ] **Step 5: Commit the driver harness refactor**

```bash
git add src/main/java/com/openggf/audio/driver/SmpsDriver.java src/test/java/com/openggf/tests/TestSmpsDriverBatching.java
git commit -m "refactor: add dual read modes to smps driver"
```

---

### Task 3: Implement the conservative hybrid read planner and prove it activates safely

**Files:**
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- Modify: `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java`

- [ ] **Step 1: Extend the driver test with hybrid activation and fallback coverage**

Append these tests to `src/test/java/com/openggf/tests/TestSmpsDriverBatching.java`:

```java
    @Test
    void hybridMode_usesChunkRenderingForSteadySequence() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        driver.setReadModeForTesting(SmpsDriver.ReadMode.HYBRID);
        AbstractSmpsData smps = new Sonic2SmpsData(steadyMusicScript());
        SmpsSequencer seq = new SmpsSequencer(smps, null, driver, Sonic2SmpsSequencerConfig.CONFIG);
        seq.setSampleRate(44100.0);
        driver.addSequencer(seq, false);

        short[] buffer = new short[4096];
        driver.read(buffer);

        assertTrue(driver.getHybridChunkCountForTesting() > 0);
    }

    @Test
    void hybridMode_matchesSampleAccurateForFillBoundaryScript() {
        short[] sampleAccurate = render(SmpsDriver.ReadMode.SAMPLE_ACCURATE, fillScript(), 4096);
        short[] hybrid = render(SmpsDriver.ReadMode.HYBRID, fillScript(), 4096);
        assertArrayEquals(sampleAccurate, hybrid);
    }

    private static byte[] fillScript() {
        byte[] data = new byte[48];
        data[2] = 2;
        data[4] = 1;
        data[5] = (byte) 0x80;
        data[0x0A] = 0x14;
        data[0x0B] = 0x00;
        data[0x14] = (byte) 0xE8; // Set note fill
        data[0x15] = 0x02;
        data[0x16] = (byte) 0x81;
        data[0x17] = 0x05;
        data[0x18] = (byte) 0xF2;
        return data;
    }
```

- [ ] **Step 2: Run the extended driver tests and verify they fail**

Run: `mvn test -Dtest=TestSmpsDriverBatching`

Expected:
- `getHybridChunkCountForTesting()` is missing, or
- the `hybridMode_usesChunkRenderingForSteadySequence` assertion fails because hybrid still never batches

- [ ] **Step 3: Implement the hybrid planner, chunk renderer, and explicit fallback gates**

In `src/main/java/com/openggf/audio/driver/SmpsDriver.java`, add the reusable chunk scratch buffer and test hook:

```java
    private short[] chunkScratch = new short[0];
    private int hybridChunkCountForTesting;

    public int getHybridChunkCountForTesting() {
        return hybridChunkCountForTesting;
    }
```

Update `read(...)` so `HYBRID` uses a real chunked path:

```java
    @Override
    public int read(short[] buffer) {
        return switch (readMode) {
            case SAMPLE_ACCURATE -> readSampleAccurate(buffer);
            case HYBRID -> readHybrid(buffer);
        };
    }

    private int readHybrid(short[] buffer) {
        int frames = buffer.length / 2;
        hybridChunkCountForTesting = 0;
        synchronized (sequencersLock) {
            int frameIndex = 0;
            while (frameIndex < frames) {
                if (requiresSampleAccurateFallback()) {
                    renderSingleSample(buffer, frameIndex++);
                    continue;
                }

                int safeChunk = computeSafeChunkSamples(frames - frameIndex);
                if (safeChunk < MIN_BATCH_SAMPLES) {
                    renderSingleSample(buffer, frameIndex++);
                    continue;
                }

                advanceSequencersBatch(safeChunk);
                removeCompletedSequencers();
                renderChunk(buffer, frameIndex, safeChunk);
                hybridChunkCountForTesting++;
                frameIndex += safeChunk;
            }
        }
        return buffer.length;
    }
```

Add the helper methods below `readSampleAccurate(...)`:

```java
    private boolean requiresSampleAccurateFallback() {
        for (SmpsSequencer seq : sequencers) {
            if (seq.requiresSampleAccurateFallback()) {
                return true;
            }
        }
        return false;
    }

    private int computeSafeChunkSamples(int maxFrames) {
        int safe = maxFrames;
        for (SmpsSequencer seq : sequencers) {
            safe = Math.min(safe, seq.getSamplesUntilNextTempoFrame());
            safe = Math.min(safe, seq.getSamplesUntilNextObservableEvent());
        }
        return Math.max(0, safe);
    }

    private void advanceSequencersBatch(int frames) {
        int size = sequencers.size();
        for (int i = 0; i < size; i++) {
            SmpsSequencer seq = sequencers.get(i);
            seq.advanceBatch(frames);
            if (seq.isComplete()) {
                pendingRemovals.add(seq);
            }
        }
    }

    private void renderSingleSample(short[] buffer, int frameIndex) {
        int size = sequencers.size();
        for (int j = 0; j < size; j++) {
            SmpsSequencer seq = sequencers.get(j);
            seq.advance(1.0);
            if (seq.isComplete()) {
                pendingRemovals.add(seq);
            }
        }
        removeCompletedSequencers();
        super.render(scratchFrameBuf);
        buffer[frameIndex * 2] = scratchFrameBuf[0];
        buffer[frameIndex * 2 + 1] = scratchFrameBuf[1];
    }

    private void renderChunk(short[] target, int frameOffset, int frames) {
        int sampleCount = frames * 2;
        if (chunkScratch.length < sampleCount) {
            chunkScratch = new short[sampleCount];
        }
        super.render(chunkScratch);
        System.arraycopy(chunkScratch, 0, target, frameOffset * 2, sampleCount);
    }
```

- [ ] **Step 4: Run the driver batching tests and verify they pass**

Run: `mvn test -Dtest=TestSmpsDriverBatching`

Expected: `BUILD SUCCESS`; the steady test reports at least one hybrid chunk and all array comparisons remain exact.

- [ ] **Step 5: Commit the hybrid read implementation**

```bash
git add src/main/java/com/openggf/audio/driver/SmpsDriver.java src/test/java/com/openggf/tests/TestSmpsDriverBatching.java
git commit -m "feat: add conservative hybrid smps batching"
```

---

### Task 4: Add ROM-backed hybrid-vs-fallback identity coverage

**Files:**
- Modify: `src/test/java/com/openggf/audio/AudioRegressionTest.java`

- [ ] **Step 1: Write failing ROM-backed identity tests for the two read modes**

In `src/test/java/com/openggf/audio/AudioRegressionTest.java`, add these tests after `testMixedMusicSfxMatchesReference()`:

```java
    @Test
    public void testHybridReadModeMatchesFallbackForMusicEhz() {
        assumeTrue(loader != null, "ROM not available, skipping identity test");
        short[] sampleAccurate = renderMusicWithMode(MUSIC_EHZ, SmpsDriver.ReadMode.SAMPLE_ACCURATE, BUFFER_SIZE * 16);
        short[] hybrid = renderMusicWithMode(MUSIC_EHZ, SmpsDriver.ReadMode.HYBRID, BUFFER_SIZE * 16);
        assertArrayEquals(sampleAccurate, hybrid);
    }

    @Test
    public void testHybridReadModeMatchesFallbackForMixedMusicSfx() {
        assumeTrue(loader != null, "ROM not available, skipping identity test");
        short[] sampleAccurate = renderMixedWithMode(SmpsDriver.ReadMode.SAMPLE_ACCURATE, BUFFER_SIZE * 24);
        short[] hybrid = renderMixedWithMode(SmpsDriver.ReadMode.HYBRID, BUFFER_SIZE * 24);
        assertArrayEquals(sampleAccurate, hybrid);
    }
```

Add the helper methods near the existing `renderAudio(...)` helper:

```java
    private short[] renderMusicWithMode(int musicId, SmpsDriver.ReadMode mode, int totalSamples) {
        AbstractSmpsData musicData = loader.loadMusic(musicId);
        SmpsDriver driver = new SmpsDriver(SAMPLE_RATE);
        driver.setReadModeForTesting(mode);
        driver.setRegion(SmpsSequencer.Region.NTSC);

        SmpsSequencer seq = new SmpsSequencer(musicData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        seq.setSampleRate(SAMPLE_RATE);
        driver.addSequencer(seq, false);
        return renderAudio(driver, totalSamples);
    }

    private short[] renderMixedWithMode(SmpsDriver.ReadMode mode, int totalSamples) {
        AbstractSmpsData musicData = loader.loadMusic(MUSIC_EHZ);
        AbstractSmpsData sfxRingData = loader.loadSfx(SFX_RING);
        AbstractSmpsData sfxJumpData = loader.loadSfx(SFX_JUMP);

        SmpsDriver driver = new SmpsDriver(SAMPLE_RATE);
        driver.setReadModeForTesting(mode);
        driver.setRegion(SmpsSequencer.Region.NTSC);

        SmpsSequencer musicSeq = new SmpsSequencer(musicData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        musicSeq.setSampleRate(SAMPLE_RATE);
        driver.addSequencer(musicSeq, false);

        short[] audio = new short[totalSamples];
        short[] buffer = new short[BUFFER_SIZE * 2];
        int written = 0;
        boolean ringTriggered = false;
        boolean jumpTriggered = false;

        while (written < totalSamples) {
            int frame = written / 2;
            if (!ringTriggered && frame >= (int) (0.5 * SAMPLE_RATE)) {
                SmpsSequencer ring = new SmpsSequencer(sfxRingData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
                ring.setSampleRate(SAMPLE_RATE);
                ring.setSfxMode(true);
                driver.addSequencer(ring, true);
                ringTriggered = true;
            }
            if (!jumpTriggered && frame >= (int) (1.0 * SAMPLE_RATE)) {
                SmpsSequencer jump = new SmpsSequencer(sfxJumpData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
                jump.setSampleRate(SAMPLE_RATE);
                jump.setSfxMode(true);
                driver.addSequencer(jump, true);
                jumpTriggered = true;
            }
            int toCopy = Math.min(buffer.length, totalSamples - written);
            driver.read(buffer);
            System.arraycopy(buffer, 0, audio, written, toCopy);
            written += toCopy;
        }
        return audio;
    }
```

- [ ] **Step 2: Run the regression class and verify the new tests fail before driver mode is public enough**

Run: `mvn test -Dtest=AudioRegressionTest`

Expected: either compilation failure around `SmpsDriver.ReadMode` / `setReadModeForTesting`, or test failure if the hybrid path is not yet exactly identical.

- [ ] **Step 3: Make sure the `SmpsDriver` mode hook is public and re-run the regression class**

If `setReadModeForTesting(...)` or `ReadMode` is not accessible from `com.openggf.audio`, adjust the declarations in `SmpsDriver.java` to be `public` exactly as follows:

```java
    public enum ReadMode {
        SAMPLE_ACCURATE,
        HYBRID
    }

    public void setReadModeForTesting(ReadMode readMode) {
        this.readMode = readMode;
    }
```

- [ ] **Step 4: Run the regression class and verify all identity checks pass**

Run: `mvn test -Dtest=AudioRegressionTest`

Expected:
- existing WAV-reference checks still pass
- `testHybridReadModeMatchesFallbackForMusicEhz` passes
- `testHybridReadModeMatchesFallbackForMixedMusicSfx` passes

- [ ] **Step 5: Commit the regression coverage**

```bash
git add src/test/java/com/openggf/audio/AudioRegressionTest.java src/main/java/com/openggf/audio/driver/SmpsDriver.java
git commit -m "test: compare hybrid audio batching against sample path"
```

---

### Task 5: Validate benchmark output and wire the default mode

**Files:**
- Modify: `src/main/java/com/openggf/audio/driver/SmpsDriver.java`

- [ ] **Step 1: Make `HYBRID` the default read mode explicitly**

In `src/main/java/com/openggf/audio/driver/SmpsDriver.java`, keep this field exactly as the final default:

```java
    private ReadMode readMode = ReadMode.HYBRID;
```

Do not add any new config flag in this task. The test hook remains `setReadModeForTesting(...)`.

- [ ] **Step 2: Run the focused test suite before benchmark work**

Run: `mvn test -Dtest=TestSmpsSequencerBatchBoundaries,TestSmpsDriverBatching,AudioRegressionTest`

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run the existing benchmark test and capture before/after numbers**

Run: `mvn test -Dtest=AudioRegressionTest#benchmarkAudioRendering`

Expected output includes:

```text
Audio render time: <number> ms per second of audio
Real-time factor: <number>x
```

Record the hybrid-mode number in the task notes next to the last sample-accurate benchmark run.

- [ ] **Step 4: Commit the default-mode flip after validation**

```bash
git add src/main/java/com/openggf/audio/driver/SmpsDriver.java
git commit -m "feat: enable hybrid smps batching by default"
```

---

## Self-Review

- Spec coverage:
  - no latency increase: no OpenAL buffer changes are planned
  - explicit fallback states: covered by `requiresSampleAccurateFallback()`
  - boundary list: covered by Task 1 tests and `getSamplesUntilNextObservableEvent()`
  - exact identity: covered by Task 3 and Task 4 array-equality tests
  - performance validation: covered by Task 5 benchmark run
- Placeholder scan:
  - no `TODO`, `TBD`, or “appropriate handling” placeholders remain
  - every modified file is named explicitly
  - every code-changing step includes code
- Type consistency:
  - `SmpsDriver.ReadMode`
  - `setReadModeForTesting(ReadMode readMode)`
  - `getSamplesUntilNextObservableEvent()`
  - `requiresSampleAccurateFallback()`
  - `getHybridChunkCountForTesting()`

## Execution Handoff

Plan complete and saved to `docs/architecture/plans/2026-04-16-audio-batching.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
