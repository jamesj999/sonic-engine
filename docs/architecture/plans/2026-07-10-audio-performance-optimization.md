# Audio Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce audio CPU cost, transient allocation, and idle/capture memory without changing generated PCM, SMPS/chip timing, command order, rewind boundaries, or frame-visible Sonic 2 special-stage state.

**Architecture:** Keep synthesis and logical audio behavior single-threaded and deterministic. Make backend PCM history an armed, backend-owned resource that disappears while a presentation-producing deterministic runtime owns history; replace allocation-heavy ordered-command selection with an in-place ordered prefix; fuse only the duplicate stereo traversal in the FIR resampler while retaining separate left/right accumulation order and rounding. Gate wall-clock diagnostics entirely when fine logging is disabled, and prove every change with focused state, allocation, and bit-exact tests before measuring throughput.

**Tech Stack:** Java 17, JUnit 5/Jupiter, Maven Surefire, LWJGL/OpenAL, existing SMPS/YM2612/PSG/Blip audio stack, `ThreadMXBean` allocation measurement.

---

## Scope And Non-Negotiable Invariants

This plan covers only these audited candidates:

1. Lazy backend PCM rewind-history allocation and release lifecycle.
2. Capture/presentation runtimes owning the sole active PCM history ring.
3. Bit-exact fused stereo FIR interpolation.
4. Allocation-free deterministic-runtime pending-command prefix consumption.
5. Gating Sonic 2 special-stage wall-clock diagnostics and warning I/O.
6. Repeatable allocation, throughput, and PCM bit-exact verification.

Do not change:

- SMPS tick/update order, `SmpsDriver` hybrid event boundaries, tempo math, fade timing, channel-lock order, YM/PSG register ordering, DAC stepping, or chip rates.
- FIR coefficient generation, tap order, per-channel multiplication/addition order, `Math.round`, or output advancement.
- Audio command ordering by `(frame, order)`, late-command discard semantics, replay suppression, or timeline state.
- Rewind audio cursor rounding/rate behavior or the configured history duration/size.
- Gameplay lag compensation, input, track advancement, collision, RNG, or any other Sonic 2 special-stage logical state.
- Threading. Do not move synthesis, special-stage updates, or command dispatch onto a worker thread.

If any proposed optimization cannot pass the bit-exact tests, revert that optimization rather than updating expected PCM.

## File Structure

Modify:

- `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
  - Own lazy allocation/release of backend PCM history.
  - Suppress backend history/cursor ownership while the attached deterministic runtime provides presentation PCM.
- `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`
  - Stop eagerly allocating PCM history during OpenAL initialization.
- `src/main/java/com/openggf/audio/HeadlessSmpsAudioBackend.java`
  - Stop eagerly allocating a redundant history ring during headless initialization.
- `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java`
  - Consume pending commands in deterministic prefix order without streams or temporary lists.
- `src/main/java/com/openggf/audio/synth/BlipResampler.java`
  - Add one stereo interpolation operation that traverses FIR taps once.
- `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
  - Consume the packed stereo FIR result without changing output timing.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
  - Skip all wall-clock diagnostic work and periodic warning I/O unless `FINE` logging is enabled.
- `src/test/java/com/openggf/audio/TestRewindHistoryArming.java`
  - Assert lazy allocation, release, re-arm, and recording lifecycle.
- `src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java`
  - Assert capture/presentation ownership does not retain a backend history ring.
- `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntimeCommands.java`
  - Prove sorted, out-of-order submission, late-entry, discard, and exactly-once behavior.
- `src/test/java/com/openggf/audio/synth/TestBlipResamplerBitExactness.java`
  - Compare the fused stereo result against the existing pre-optimization reference implementation.
- `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManagerTest.java`
  - Prove normal updates do not sample wall-clock diagnostics and enabled diagnostics do not alter logical state.
- `src/test/java/com/openggf/audio/driver/TestSmpsFadeAudioThroughput.java`
  - Report allocation as well as throughput for the production audio path.
- `CHANGELOG.md`
  - Record the completed audio performance work under Unreleased.

Create:

- `src/test/java/com/openggf/audio/TestAudioHistoryAllocationMeasurement.java`
  - Measurement-tagged harness that prints backend/capture history retained bytes and allocation deltas; assertions remain machine-independent invariants.

Do not create a second resampler, second command timeline, new executor, new configuration flag, or new audio abstraction.

## Commit Trailer Template

Every non-merge commit on this branch must include the repository trailers. Production performance commits also update and stage `CHANGELOG.md`:

```text
Changelog: updated
Guide: n/a: no user workflow change
Known-Discrepancies: n/a: no accuracy discrepancy change
S3K-Known-Discrepancies: n/a: no S3K discrepancy change
Agent-Docs: n/a: no agent guidance change
Configuration-Docs: n/a: no configuration change
Skills: n/a: no skill change
```

Test-only commits use `Changelog: n/a: test-only performance coverage` and do not stage `CHANGELOG.md`.

## Task 1: Establish The Pre-Change Measurement And Parity Baseline

**Files:**

- No files modified.

- [ ] **Step 1: Confirm the isolated worktree and branch**

Run:

```powershell
git rev-parse --show-toplevel
git branch --show-current
git status --short
```

Expected: top-level path ends in `.worktrees/ai-performance-optimization`, branch follows `feature/ai-*`, and no unexplained changes are present. Do not run Maven in the shared root worktree.

- [ ] **Step 2: Run the current audio parity baseline**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.AudioRegressionTest,com.openggf.audio.driver.TestSmpsFadeHybridParity,com.openggf.audio.smps.TestSmpsSequencerTempoMath,com.openggf.audio.synth.TestBlipResamplerBitExactness,com.openggf.audio.synth.TestBlipResamplerTailSnapshot,com.openggf.audio.runtime.TestStreamBackedDeterministicAudioRuntimeCommands,com.openggf.audio.TestRewindHistoryArming,com.openggf.audio.AudioManagerCaptureModeTest" test
```

Expected: all selected tests pass. Save the Surefire summary in the task notes; do not proceed from a red baseline.

- [ ] **Step 3: Record baseline fade throughput**

Run twice from the same warmed machine state:

```powershell
mvn "-Dtest=com.openggf.audio.driver.TestSmpsFadeAudioThroughput" test
```

Expected: test passes or skips only if `s2.gen` is unavailable. When present, copy both `FADE_THROUGHPUT` lines into the task notes. This is a measurement, not a pass/fail performance threshold.

## Task 2: Lazy Backend PCM History And Single-Owner Capture Lifecycle

**Files:**

- Modify: `src/test/java/com/openggf/audio/TestRewindHistoryArming.java`
- Modify: `src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java:49-66,661-725,1018-1073`
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java:118-123`
- Modify: `src/main/java/com/openggf/audio/HeadlessSmpsAudioBackend.java:42-50`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Replace the eager-allocation assertion with failing lazy lifecycle tests**

In `TestRewindHistoryArming`, change the default test and add release/re-arm coverage:

```java
@Test
void historyRingIsAbsentUntilARewindConsumerArmsIt() throws Exception {
    assertNull(pcmHistoryRing(backend),
            "an unused backend must not retain roughly eleven MiB of PCM history");

    backend.fillBuffer(0);

    assertNull(pcmHistoryRing(backend));
}

@Test
void armingAllocatesRecordingHistoryAndDisarmingReleasesIt() throws Exception {
    backend.setRewindHistoryArmed(true);
    PcmHistoryRing firstRing = pcmHistoryRing(backend);
    assertNotNull(firstRing);

    backend.fillBuffer(0);
    assertTrue(storedFrames(firstRing) > 0);

    backend.setRewindHistoryArmed(false);
    assertNull(pcmHistoryRing(backend), "disarming is a hard presentation-history boundary");

    backend.setRewindHistoryArmed(true);
    PcmHistoryRing secondRing = pcmHistoryRing(backend);
    assertNotNull(secondRing);
    assertNotSame(firstRing, secondRing);
    assertEquals(0, storedFrames(secondRing));
}
```

Add imports:

```java
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
```

Delete the old `armingEnablesRecordingAndDisarmingStopsIt` assertion that retains and inspects the released ring.

- [ ] **Step 2: Add failing capture single-owner coverage**

In `AudioManagerCaptureModeTest`, add a real headless backend test and reflection helper:

```java
@Test
void captureRuntimeOwnsTheOnlyHistoryRing() throws Exception {
    AudioManager audio = AudioManager.getInstance();
    audio.resetState();
    HeadlessSmpsAudioBackend backend = new HeadlessSmpsAudioBackend(
            com.openggf.configuration.SonicConfigurationService.getInstance(),
            com.openggf.debug.PerformanceProfiler.getInstance());
    audio.setBackend(backend);
    audio.setRewindHistoryArmed(true);
    assertNotNull(backendHistory(backend));

    audio.beginCaptureMode(48_000, 60);

    assertNull(backendHistory(backend),
            "presentation-producing capture runtime must be the sole history owner");
    audio.advanceGameplayFrameAudio();
    assertEquals(800, audio.drainCaptureFrame(new short[1_600]));

    audio.endCaptureMode();
    assertNotNull(backendHistory(backend),
            "ending capture re-establishes an empty armed backend history");
    audio.resetState();
}

private static com.openggf.audio.runtime.PcmHistoryRing backendHistory(
        AbstractSmpsAudioBackend backend) throws Exception {
    java.lang.reflect.Field field = AbstractSmpsAudioBackend.class.getDeclaredField("pcmHistory");
    field.setAccessible(true);
    return (com.openggf.audio.runtime.PcmHistoryRing) field.get(backend);
}
```

- [ ] **Step 3: Run the tests and verify the red state**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.TestRewindHistoryArming,com.openggf.audio.AudioManagerCaptureModeTest" test
```

Expected: failures because both backends still allocate on `init()` and attaching capture does not release backend history.

- [ ] **Step 4: Centralize lazy history allocation in the abstract backend**

In `AbstractSmpsAudioBackend`, add these methods beside `getStreamSampleRate()`:

```java
private PcmHistoryRing ensurePcmHistory() {
    if (pcmHistory == null) {
        int sampleRate = Math.max(1, outputSampleRate());
        int frames = PcmHistoryRing.capacityFramesFor(
                sampleRate,
                configService.getString(SonicConfiguration.REWIND_AUDIO_HISTORY_LIMIT_TYPE),
                configService.getInt(SonicConfiguration.REWIND_AUDIO_HISTORY_SECONDS),
                configService.getInt(SonicConfiguration.REWIND_AUDIO_HISTORY_SIZE_MB));
        pcmHistory = new PcmHistoryRing(Math.max(STREAM_BUFFER_SIZE, frames));
    }
    return pcmHistory;
}

private void reconcilePcmHistoryOwnership() {
    reverseCursor = null;
    if (runtimeProvidesPresentationPcm() || !rewindHistoryArmed) {
        pcmHistory = null;
    } else {
        ensurePcmHistory();
    }
}
```

Ensure `SonicConfiguration` is imported. Do not expose the ring publicly.

- [ ] **Step 5: Reconcile ownership when runtimes attach and arming changes**

Replace `attachDeterministicAudioRuntime` with:

```java
@Override
public void attachDeterministicAudioRuntime(DeterministicAudioRuntime runtime) {
    synchronized (streamLock) {
        deterministicAudioRuntime = runtime;
        reconcilePcmHistoryOwnership();
        bindRuntimePresentationStreams();
    }
}
```

Replace `setRewindHistoryArmed` with:

```java
@Override
public void setRewindHistoryArmed(boolean armed) {
    synchronized (streamLock) {
        rewindHistoryArmed = armed;
        reconcilePcmHistoryOwnership();
    }
}
```

Change `beginReversePresentation` so only the owner creates a backend cursor:

```java
@Override
public void beginReversePresentation() {
    synchronized (streamLock) {
        reverseCursor = !runtimeProvidesPresentationPcm() && pcmHistory != null
                ? pcmHistory.createReverseCursor()
                : null;
        if (reverseCursor != null) {
            reverseCursor.setRate(pendingReverseRate);
        }
    }
}
```

In `fillBuffer`, change backend history recording to:

```java
if (!runtimePresentation && rewindHistoryArmed && reverseCursor == null && pcmHistory != null) {
    pcmHistory.write(streamData, STREAM_BUFFER_SIZE);
}
```

This prevents a capture runtime and backend from copying the same PCM into two rings.

- [ ] **Step 6: Remove eager allocations from concrete backends**

Delete lines 118–123 from `LWJGLAudioBackend.hookInitDevice()`.

Replace `HeadlessSmpsAudioBackend.hookInitDevice()` with:

```java
@Override
protected void hookInitDevice() {
    // No device. PCM history is allocated lazily by the base class only when
    // this backend, rather than a deterministic presentation runtime, owns it.
}
```

Update the class/base comments that currently promise initialization-time history allocation.

- [ ] **Step 7: Run the focused lifecycle tests**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.TestRewindHistoryArming,com.openggf.audio.AudioManagerCaptureModeTest,com.openggf.audio.TestAudioManagerRewindSuppression,com.openggf.audio.runtime.TestPcmHistoryRing" test
```

Expected: all pass. The capture test must produce exactly 800 stereo frames for a 48 kHz/60 Hz frame.

- [ ] **Step 8: Update changelog and commit**

Add under Unreleased in `CHANGELOG.md`:

```markdown
- Reduced audio rewind memory by allocating backend PCM history only while armed and keeping capture/runtime history single-owned.
```

Commit:

```powershell
git add src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java `
        src/main/java/com/openggf/audio/LWJGLAudioBackend.java `
        src/main/java/com/openggf/audio/HeadlessSmpsAudioBackend.java `
        src/test/java/com/openggf/audio/TestRewindHistoryArming.java `
        src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java `
        CHANGELOG.md
git commit -m "perf: make audio rewind history single-owned" -m "Changelog: updated
Guide: n/a: no user workflow change
Known-Discrepancies: n/a: no accuracy discrepancy change
S3K-Known-Discrepancies: n/a: no S3K discrepancy change
Agent-Docs: n/a: no agent guidance change
Configuration-Docs: n/a: no configuration change
Skills: n/a: no skill change"
```

## Task 3: Allocation-Free Pending Command Prefix Consumption

**Files:**

- Modify: `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntimeCommands.java`
- Modify: `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java:7-18,70-81,200-213`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add failing ordering and allocation-sensitive tests**

Add to `TestStreamBackedDeterministicAudioRuntimeCommands`:

```java
@Test
void commandsSubmittedOutOfOrderRemainOrderedAndLateCommandsAreDiscarded() {
    StreamBackedDeterministicAudioRuntime runtime = new StreamBackedDeterministicAudioRuntime(
            new AudioFrameClock(2, 1), new AudioOutputFifo(64));
    List<String> handled = new ArrayList<>();
    runtime.setCommandHandler(command -> handled.add(((AudioCommand.PlaySfx) command).sfxName()));

    runtime.submit(entry(9, 0, "D"));
    runtime.submit(entry(5, 2, "C"));
    runtime.submit(entry(5, 0, "A"));
    runtime.submit(entry(5, 1, "B"));
    runtime.submit(entry(3, 0, "late"));

    runtime.advanceFrame(5, FrameAudioMode.NORMAL);
    assertEquals(List.of("A", "B", "C"), handled);

    runtime.discardSubmittedCommandsAfter(8);
    runtime.advanceFrame(9, FrameAudioMode.NORMAL);
    assertEquals(List.of("A", "B", "C"), handled);
}

@Test
void consumingPendingCommandsAllocatesNoTemporarySelectionList() {
    StreamBackedDeterministicAudioRuntime runtime = new StreamBackedDeterministicAudioRuntime(
            new AudioFrameClock(48_000, 60), new AudioOutputFifo(96_000));
    runtime.setCommandHandler(command -> { });
    for (int i = 0; i < 1_000; i++) {
        runtime.submit(entry(i + 1L, 0, "S" + i));
    }

    com.openggf.audio.AudioBenchmarkMemoryProbe probe =
            com.openggf.audio.AudioBenchmarkMemoryProbe.create();
    com.openggf.audio.AudioBenchmarkMemoryProbe.RunResult result =
            probe.measureTimedRun(() -> {
                for (long frame = 1; frame <= 1_000; frame++) {
                    runtime.advanceFrame(frame, FrameAudioMode.SILENT_STEP);
                }
            });

    if (result.allocatedBytesSupported()) {
        assertTrue(result.allocatedBytes() < 16_384,
                "command selection must not allocate a stream/list per frame: " + result);
    }
}
```

The second test uses `SILENT_STEP` so PCM output does not fill the FIFO and obscure command-selection allocation.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.runtime.TestStreamBackedDeterministicAudioRuntimeCommands" test
```

Expected: allocation assertion fails on supported HotSpot JVMs because `stream().filter().sorted().toList()` creates temporary objects. Ordering tests must continue to pass.

- [ ] **Step 3: Maintain pending commands in sorted order at submission**

Remove stream-related imports. Add these fields:

```java
private static final java.util.Comparator<AudioTimelineEntry> COMMAND_ORDER =
        java.util.Comparator.comparingLong(AudioTimelineEntry::frame)
                .thenComparingInt(AudioTimelineEntry::order);
private int firstPendingCommand;
```

Replace `submit` with binary insertion over the unconsumed suffix:

```java
@Override
public void submit(AudioTimelineEntry entry) {
    AudioTimelineEntry command = Objects.requireNonNull(entry, "entry");
    compactConsumedCommandsIfNeeded();
    int low = firstPendingCommand;
    int high = pendingCommands.size();
    while (low < high) {
        int mid = (low + high) >>> 1;
        if (COMMAND_ORDER.compare(pendingCommands.get(mid), command) <= 0) {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    pendingCommands.add(low, command);
}

private void compactConsumedCommandsIfNeeded() {
    if (firstPendingCommand >= 64
            && firstPendingCommand * 2 >= pendingCommands.size()) {
        pendingCommands.subList(0, firstPendingCommand).clear();
        firstPendingCommand = 0;
    }
}
```

Equal `(frame, order)` submissions retain submission order. The occasional `subList` allocation occurs on command submission/compaction, not on the per-frame consume path. Do not deduplicate commands.

- [ ] **Step 4: Replace stream selection with ordered-prefix consumption**

Replace `consumeCommands` with:

```java
private void consumeCommands(long frame) {
    while (firstPendingCommand < pendingCommands.size()) {
        AudioTimelineEntry entry = pendingCommands.get(firstPendingCommand);
        if (entry.frame() > frame) {
            break;
        }
        if (entry.frame() == frame) {
            commandHandler.accept(entry.command());
        }
        firstPendingCommand++;
    }
    if (firstPendingCommand == pendingCommands.size()) {
        pendingCommands.clear();
        firstPendingCommand = 0;
    }
}
```

Entries older than `frame` are discarded, matching the old final `removeIf(entry.frame() <= frame)` behavior. Current-frame commands dispatch in `(frame, order)` order.

Replace `discardSubmittedCommandsAfter` and `clearSubmittedCommands` so the suffix cursor stays valid:

```java
@Override
public void discardSubmittedCommandsAfter(long frame) {
    for (int i = pendingCommands.size() - 1;
         i >= firstPendingCommand && pendingCommands.get(i).frame() > frame;
         i--) {
        pendingCommands.remove(i);
    }
    if (firstPendingCommand == pendingCommands.size()) {
        pendingCommands.clear();
        firstPendingCommand = 0;
    }
}

@Override
public void clearSubmittedCommands() {
    pendingCommands.clear();
    firstPendingCommand = 0;
}
```

- [ ] **Step 5: Run command and timeline tests**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.runtime.TestStreamBackedDeterministicAudioRuntimeCommands,com.openggf.audio.runtime.TestStreamBackedDeterministicAudioRuntime,com.openggf.audio.TestAudioCommandTimeline,com.openggf.audio.rewind.TestAudioCommandTimelineIndexing" test
```

Expected: all pass; supported-JVM allocation stays below the stated 16 KiB ceiling.

- [ ] **Step 6: Update changelog and commit**

Add under Unreleased:

```markdown
- Removed per-frame temporary command-selection lists from deterministic audio dispatch while preserving timeline order.
```

Commit with the full trailer template:

```powershell
git add src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java `
        src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntimeCommands.java `
        CHANGELOG.md
git commit -m "perf: consume deterministic audio commands in place" -m "Changelog: updated
Guide: n/a: no user workflow change
Known-Discrepancies: n/a: no accuracy discrepancy change
S3K-Known-Discrepancies: n/a: no S3K discrepancy change
Agent-Docs: n/a: no agent guidance change
Configuration-Docs: n/a: no configuration change
Skills: n/a: no skill change"
```

## Task 4: Bit-Exact Fused Stereo FIR Traversal

**Files:**

- Modify: `src/test/java/com/openggf/audio/synth/TestBlipResamplerBitExactness.java`
- Modify: `src/main/java/com/openggf/audio/synth/BlipResampler.java:203-256`
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java:1482-1497`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add failing fused-output comparison to every reference sample**

In `TestBlipResamplerBitExactness.pump`, replace the two production getter assertions with:

```java
int expectedLeft = reference.getOutputLeft();
int expectedRight = reference.getOutputRight();
long packed = production.getOutputStereoPacked();
assertEquals(expectedLeft, (int) (packed >> 32),
        "left output " + index + " diverged");
assertEquals(expectedRight, (int) packed,
        "right output " + index + " diverged");
```

Keep all existing seeds, early-history, wrap-around, unity, upsample, downsample, and reset cases.

- [ ] **Step 2: Run and verify the compile-red state**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.synth.TestBlipResamplerBitExactness" test
```

Expected: compile failure because `getOutputStereoPacked()` does not exist.

- [ ] **Step 3: Implement a single traversal with independent sums**

Add to `BlipResampler` and retain the existing scalar getters for compatibility/debug tests:

```java
public long getOutputStereoPacked() {
    final double pos = outputPos;
    long center = (long) pos;
    if (pos != cachedPhaseOutputPos) {
        double frac = pos - center;
        int phase = (int) (frac * PHASE_COUNT);
        if (phase >= PHASE_COUNT) phase = PHASE_COUNT - 1;
        cachedPhase = phase;
        cachedPhaseOutputPos = pos;
    }
    double[] coeffs = SINC_TABLE[cachedPhase];
    long start = center - (FILTER_TAPS / 2) + 1;
    double sumLeft = 0.0;
    double sumRight = 0.0;

    if (start >= inputIndex - BUFFER_SIZE && center + (FILTER_TAPS / 2) < inputIndex) {
        int ringPos = (head - (int) (inputIndex - start)) & BUFFER_MASK;
        for (int tap = 0; tap < FILTER_TAPS; tap++) {
            double coefficient = coeffs[tap];
            sumLeft += historyL[ringPos] * coefficient;
            sumRight += historyR[ringPos] * coefficient;
            ringPos = (ringPos + 1) & BUFFER_MASK;
        }
    } else {
        for (int tap = 0; tap < FILTER_TAPS; tap++) {
            long index = start + tap;
            double coefficient = coeffs[tap];
            sumLeft += sampleAt(historyL, index) * coefficient;
            sumRight += sampleAt(historyR, index) * coefficient;
        }
    }

    int left = (int) Math.round(sumLeft);
    int right = (int) Math.round(sumRight);
    return ((long) left << 32) | (right & 0xFFFF_FFFFL);
}
```

Important: do not combine sums, use `Math.fma`, use float coefficients, unroll/reassociate taps, or change the left/right multiplication order.

- [ ] **Step 4: Route YM output through the fused method**

In the `useBlipResampler` branch of `Ym2612Chip.renderStereo`, replace the two getter calls with:

```java
long stereo = blipResampler.getOutputStereoPacked();
leftBuf[outIdx] += (int) (stereo >> 32);
rightBuf[outIdx] += (int) stereo;
blipResampler.advanceOutput();
```

Do not move `advanceOutput()` or the internal-sample generation loop.

- [ ] **Step 5: Run resampler and chip parity tests**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.synth.TestBlipResamplerBitExactness,com.openggf.audio.synth.TestBlipResamplerTailSnapshot,com.openggf.audio.synth.TestYm2612ChipSnapshot,com.openggf.audio.synth.TestVirtualSynthesizerSnapshot,com.openggf.audio.AudioRegressionTest,com.openggf.audio.driver.TestSmpsFadeHybridParity" test
```

Expected: all pass with exact sample equality. Any single-sample mismatch blocks this task; do not update reference output.

- [ ] **Step 6: Update changelog and commit**

Add under Unreleased:

```markdown
- Fused left/right FIR resampling into one ring traversal while retaining bit-exact per-channel output.
```

Commit with the full trailer template:

```powershell
git add src/main/java/com/openggf/audio/synth/BlipResampler.java `
        src/main/java/com/openggf/audio/synth/Ym2612Chip.java `
        src/test/java/com/openggf/audio/synth/TestBlipResamplerBitExactness.java `
        CHANGELOG.md
git commit -m "perf: fuse stereo FIR resampling" -m "Changelog: updated
Guide: n/a: no user workflow change
Known-Discrepancies: n/a: no accuracy discrepancy change
S3K-Known-Discrepancies: n/a: no S3K discrepancy change
Agent-Docs: n/a: no agent guidance change
Configuration-Docs: n/a: no configuration change
Skills: n/a: no skill change"
```

## Task 5: Gate Sonic 2 Special-Stage Wall-Clock Diagnostics

**Files:**

- Modify: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManagerTest.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java:193-200,988-1069,1542-1543`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add a deterministic clock seam and failing disabled-diagnostics test**

Add to `Sonic2SpecialStageManagerTest`:

```java
@Test
void normalUpdatesDoNotReadWallClockWhenFineDiagnosticsAreDisabled() {
    Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
    java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
    manager.setDiagnosticClockForTesting(new Sonic2SpecialStageManager.DiagnosticClock() {
        @Override public long nanoTime() { reads.incrementAndGet(); return 1_000_000L; }
        @Override public long currentTimeMillis() { reads.incrementAndGet(); return 1_000L; }
    });
    manager.setFineDiagnosticsEnabledForTesting(false);

    manager.updateTimingDiagnosticsIfEnabled();

    assertEquals(0, reads.get());
}

@Test
void enablingDiagnosticsDoesNotChangeLogicalSpecialStageState() {
    Sonic2SpecialStageManager disabled = new Sonic2SpecialStageManager();
    Sonic2SpecialStageManager enabled = new Sonic2SpecialStageManager();
    disabled.setFineDiagnosticsEnabledForTesting(false);
    enabled.setFineDiagnosticsEnabledForTesting(true);
    enabled.setDiagnosticClockForTesting(new Sonic2SpecialStageManager.DiagnosticClock() {
        private long nanos;
        private long millis;
        @Override public long nanoTime() { return nanos += 16_666_667L; }
        @Override public long currentTimeMillis() { return millis += 17L; }
    });

    for (int i = 0; i < 180; i++) {
        disabled.updateTimingDiagnosticsIfEnabled();
        enabled.updateTimingDiagnosticsIfEnabled();
    }

    Sonic2SpecialStageComparisonState left = disabled.captureComparisonState();
    Sonic2SpecialStageComparisonState right = enabled.captureComparisonState();
    assertEquals(left, right);
}
```

- [ ] **Step 2: Run and verify the compile-red state**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManagerTest" test
```

Expected: compile failure because the diagnostic clock seam and test override do not exist.

- [ ] **Step 3: Add the package-local diagnostic seam**

In `Sonic2SpecialStageManager`, add:

```java
interface DiagnosticClock {
    long nanoTime();
    long currentTimeMillis();
}

private static final DiagnosticClock SYSTEM_DIAGNOSTIC_CLOCK = new DiagnosticClock() {
    @Override public long nanoTime() { return System.nanoTime(); }
    @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
};

private DiagnosticClock diagnosticClock = SYSTEM_DIAGNOSTIC_CLOCK;
private Boolean fineDiagnosticsOverride;

private boolean fineDiagnosticsEnabled() {
    return fineDiagnosticsOverride != null
            ? fineDiagnosticsOverride
            : LOGGER.isLoggable(java.util.logging.Level.FINE);
}

void setDiagnosticClockForTesting(DiagnosticClock clock) {
    diagnosticClock = java.util.Objects.requireNonNull(clock, "clock");
}

void setFineDiagnosticsEnabledForTesting(boolean enabled) {
    fineDiagnosticsOverride = enabled;
}

void updateTimingDiagnosticsIfEnabled() {
    if (fineDiagnosticsEnabled()) {
        updateTimingDiagnostics();
    }
}
```

- [ ] **Step 4: Extract and gate all periodic wall-clock work**

Replace the timing blocks in `update()` with one call to the same package-local gate exercised by the tests:

```java
updateTimingDiagnosticsIfEnabled();
```

Add:

```java
private void updateTimingDiagnostics() {
    long now = diagnosticClock.nanoTime();
    if (lastFrameTime != 0) {
        frameSampleSum += now - lastFrameTime;
        frameSampleCount++;
        if (frameSampleCount >= FRAME_SAMPLE_SIZE) {
            double avgMs = (frameSampleSum / (double) frameSampleCount) / 1_000_000.0;
            LOGGER.fine(String.format(java.util.Locale.ROOT,
                    "Actual FPS: %.1f (%.2f ms/frame)", 1000.0 / avgMs, avgMs));
            frameSampleCount = 0;
            frameSampleSum = 0;
        }
    }
    lastFrameTime = now;

    long wallNow = diagnosticClock.currentTimeMillis();
    if (diagnosticWallStartTime == 0) {
        diagnosticWallStartTime = wallNow;
    }
    diagnosticUpdateCount++;
    long elapsedMs = wallNow - diagnosticWallStartTime;
    if (elapsedMs >= 5_000) {
        double seconds = elapsedMs / 1000.0;
        LOGGER.fine(String.format(java.util.Locale.ROOT,
                "DIAGNOSTIC: %.1f updates/sec (expect 60), %.1f track/sec (expect 12), speedFactor=%d, duration=%d",
                diagnosticUpdateCount / seconds,
                diagnosticTrackAdvances / seconds,
                trackAnimator.getSpeedFactor(),
                getAlignmentFrameDuration()));
        diagnosticWallStartTime = wallNow;
        diagnosticUpdateCount = 0;
        diagnosticTrackAdvances = 0;
    }
}
```

Increment `diagnosticTrackAdvances` only inside `if (fineDiagnosticsEnabled())`. On lag-skip and alignment-test paths, update `lastFrameTime` only when diagnostics are enabled. The former unconditional warning becomes `FINE`; production runs must not emit a five-second warning.

Do not remove diagnostic snapshot fields in this task; preserving snapshot schema avoids unrelated rewind churn.

- [ ] **Step 5: Run special-stage deterministic coverage**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManagerTest,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageRendererDeterminismTest,com.openggf.tests.trace.s2.S2SpecialStageReplayDeterminismTest,com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageRewindSnapshot" test
```

Expected: all pass. Comparison state must be identical with diagnostics enabled/disabled.

- [ ] **Step 6: Update changelog and commit**

Add under Unreleased:

```markdown
- Removed always-on wall-clock sampling and warning output from Sonic 2 special stages unless fine diagnostics are enabled.
```

Commit with the full trailer template:

```powershell
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java `
        src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManagerTest.java `
        CHANGELOG.md
git commit -m "perf: gate Sonic 2 special stage diagnostics" -m "Changelog: updated
Guide: n/a: no user workflow change
Known-Discrepancies: n/a: no accuracy discrepancy change
S3K-Known-Discrepancies: n/a: no S3K discrepancy change
Agent-Docs: n/a: no agent guidance change
Configuration-Docs: n/a: no configuration change
Skills: n/a: no skill change"
```

## Task 6: Add Repeatable Memory And Allocation Measurements

**Files:**

- Create: `src/test/java/com/openggf/audio/TestAudioHistoryAllocationMeasurement.java`
- Modify: `src/test/java/com/openggf/audio/driver/TestSmpsFadeAudioThroughput.java`

- [ ] **Step 1: Add the history ownership measurement test**

Create `TestAudioHistoryAllocationMeasurement.java`:

```java
package com.openggf.audio;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.PerformanceProfiler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("performance-measurement")
class TestAudioHistoryAllocationMeasurement {
    @Test
    void reportsLazyAndCaptureHistoryOwnership() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        HeadlessSmpsAudioBackend backend = new HeadlessSmpsAudioBackend(
                config, PerformanceProfiler.getInstance());
        backend.init();
        assertNull(history(backend));

        AudioBenchmarkMemoryProbe probe = AudioBenchmarkMemoryProbe.create();
        AudioBenchmarkMemoryProbe.RunResult arm =
                probe.measureTimedRun(() -> backend.setRewindHistoryArmed(true));
        assertNotNull(history(backend));

        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(backend);
        AudioBenchmarkMemoryProbe.RunResult capture =
                probe.measureTimedRun(() -> audio.beginCaptureMode(48_000, 60));
        assertNull(history(backend));

        System.out.printf(Locale.ROOT,
                "AUDIO_HISTORY_ALLOCATION armBytes=%d captureBytes=%d allocatedSupported=%s%n",
                arm.allocatedBytes(), capture.allocatedBytes(),
                arm.allocatedBytesSupported() && capture.allocatedBytesSupported());

        audio.endCaptureMode();
        audio.resetState();
        config.resetToDefaults();
    }

    private static Object history(AbstractSmpsAudioBackend backend) throws Exception {
        Field field = AbstractSmpsAudioBackend.class.getDeclaredField("pcmHistory");
        field.setAccessible(true);
        return field.get(backend);
    }
}
```

- [ ] **Step 2: Extend fade throughput with allocation reporting**

In `TestSmpsFadeAudioThroughput.renderFadeWindowOnce`, wrap only the existing fade-window `renderChunks` call with `AudioBenchmarkMemoryProbe.measureTimedRun`. Return a record:

```java
private record FadeRun(double throughput, long allocatedBytes, boolean allocationSupported) { }
```

Add:

```java
import com.openggf.audio.AudioBenchmarkMemoryProbe;
```

The measured workload remains exactly:

```java
AudioBenchmarkMemoryProbe probe = AudioBenchmarkMemoryProbe.create();
boolean[] audible = new boolean[1];
AudioBenchmarkMemoryProbe.RunResult measurement = probe.measureTimedRun(
        () -> audible[0] = renderChunks(driver, buffer, FADE_WINDOW_CHUNKS, chunkPeaks));
double renderedSeconds = FADE_WINDOW_FRAMES / SAMPLE_RATE;
return new FadeRun(
        renderedSeconds / (measurement.elapsedNanos() / 1_000_000_000.0),
        measurement.allocatedBytes(),
        measurement.allocatedBytesSupported());
```

Print median throughput and median allocated bytes in a parseable line:

```text
FADE_THROUGHPUT median=... renderedSecPerWallSec medianAllocatedBytes=... allocatedSupported=...
```

Keep assertions machine-independent: positive throughput, audible early PCM, falling fade amplitude, and non-negative allocated bytes only when supported. Do not assert a wall-time speedup percentage in CI.

- [ ] **Step 3: Run measurement harnesses**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.TestAudioHistoryAllocationMeasurement" test
mvn "-Dtest=com.openggf.audio.driver.TestSmpsFadeAudioThroughput" test
```

Expected: history measurement passes and prints one `AUDIO_HISTORY_ALLOCATION` line. Fade test passes and prints one `FADE_THROUGHPUT` line, or skips only when `s2.gen` is absent.

- [ ] **Step 4: Compare against Task 1 baseline**

Run the fade harness twice more from the same machine state. Record median of the three post-change throughput values and compare with the two pre-change runs. Accept normal noise; investigate a repeatable regression over 5%. Never relax PCM parity for throughput.

- [ ] **Step 5: Commit measurement-only coverage**

```powershell
git add src/test/java/com/openggf/audio/TestAudioHistoryAllocationMeasurement.java `
        src/test/java/com/openggf/audio/driver/TestSmpsFadeAudioThroughput.java
git commit -m "test: measure audio allocation and throughput" -m "Changelog: n/a: test-only performance coverage
Guide: n/a: no user workflow change
Known-Discrepancies: n/a: no accuracy discrepancy change
S3K-Known-Discrepancies: n/a: no S3K discrepancy change
Agent-Docs: n/a: no agent guidance change
Configuration-Docs: n/a: no configuration change
Skills: n/a: no skill change"
```

## Task 7: Final Bit-Exact, Rewind, Determinism, And Full-Suite Gate

**Files:**

- Modify only if a focused test exposes a real defect in files already listed above.

- [ ] **Step 1: Run the complete focused audio parity set**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.AudioRegressionTest,com.openggf.audio.TestAudioLogicalSnapshot,com.openggf.audio.TestAudioKeyframeReplay,com.openggf.audio.TestRewindHistoryArming,com.openggf.audio.AudioManagerCaptureModeTest,com.openggf.audio.runtime.TestStreamBackedDeterministicAudioRuntime,com.openggf.audio.runtime.TestStreamBackedDeterministicAudioRuntimeCommands,com.openggf.audio.runtime.TestAudioRingBuffers,com.openggf.audio.driver.TestSmpsFadeHybridParity,com.openggf.audio.smps.TestSmpsSequencerTempoMath,com.openggf.audio.synth.TestBlipResamplerBitExactness,com.openggf.audio.synth.TestBlipResamplerTailSnapshot,com.openggf.audio.synth.TestYm2612ChipSnapshot,com.openggf.audio.synth.TestPsgChipSnapshot,com.openggf.audio.synth.TestVirtualSynthesizerSnapshot" test
```

Expected: all pass with zero PCM expectation changes.

- [ ] **Step 2: Run rewind presentation and boundary tests**

Run:

```powershell
mvn "-Dtest=com.openggf.audio.TestAudioManagerRewindSuppression,com.openggf.audio.TestDeterministicAudioRuntimeBoundary,com.openggf.audio.TestRewindHistoryArming,com.openggf.game.rewind.TestHeldRewindAudioStepCost,com.openggf.TestGameLoopSpecialStageRewindBoundary,com.openggf.TestGameLoopSpecialStageRewindDebugBoundary" test
```

Expected: all pass. Reverse cursor rate, hard boundaries, suppression, and release crossfade remain unchanged.

- [ ] **Step 3: Run Sonic 2 special-stage determinism tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManagerTest,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageRendererDeterminismTest,com.openggf.tests.trace.s2.S2SpecialStageReplayDeterminismTest,com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageRewindSnapshot" test
```

Expected: all pass; diagnostics gating produces no logical comparison-state or replay divergence.

- [ ] **Step 4: Run architectural and policy guards**

Run:

```powershell
mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.tests.TestArchUnitRules,com.openggf.level.objects.TestObjectServicesMigrationGuard" test
```

Expected: all pass. Do not broaden a guard baseline for these changes.

- [ ] **Step 5: Run the complete suite**

Run from this isolated worktree only:

```powershell
mvn test
```

Expected: full suite passes. If ROM-gated tests skip because user-supplied ROMs are absent, report the exact skipped tests.

- [ ] **Step 6: Run packaging**

```powershell
mvn package
```

Expected: package succeeds and produces `target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar`.

- [ ] **Step 7: Inspect the final diff and policy trailers**

Run:

```powershell
git diff --check
git status --short
git log --format=full -6
```

Expected: no whitespace errors, only planned files changed, and every branch commit contains all seven required trailers.

- [ ] **Step 8: Commit verification-only fixes if required**

Skip this step if verification changed no files. If production code changed, update `CHANGELOG.md` and commit with the full `Changelog: updated` trailer template. If only tests changed, use:

```powershell
git add src/test/java/com/openggf
git commit -m "test: stabilize audio performance verification" -m "Changelog: n/a: test-only verification fixes
Guide: n/a: no user workflow change
Known-Discrepancies: n/a: no accuracy discrepancy change
S3K-Known-Discrepancies: n/a: no S3K discrepancy change
Agent-Docs: n/a: no agent guidance change
Configuration-Docs: n/a: no configuration change
Skills: n/a: no skill change"
```

## Completion Criteria

- An unarmed LWJGL or headless backend owns no `PcmHistoryRing`.
- Arming creates exactly one backend ring; disarming releases it and rearming starts empty.
- Attaching capture/presentation PCM releases the backend ring and prevents duplicate writes/cursors; ending capture restores one empty backend ring only when still armed.
- Pending commands dispatch exactly once in `(frame, order)` order without a per-frame filtered/sorted list.
- Fused stereo FIR output matches the preserved reference at every sample across early history, wrap-around, resets, unity, upsample, 44.1 kHz, and 48 kHz cases.
- YM/PSG/SMPS snapshots, fades, mixed SFX, and regression PCM remain unchanged.
- Sonic 2 special-stage updates perform no wall-clock reads or periodic diagnostics output when fine logging is disabled, and enabling diagnostics does not change logical comparison state.
- Measurement harnesses print parseable allocation/throughput results without brittle machine-speed thresholds.
- Focused tests, architectural guards, `mvn test`, and `mvn package` pass from the isolated worktree.
