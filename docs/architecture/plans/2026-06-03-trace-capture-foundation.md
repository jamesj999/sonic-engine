# Trace Capture Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the scope-agnostic `com.openggf.capture` recording subsystem plus the `AudioManager` deterministic capture-mode API and the trace-framework visibility config, all unit-tested without GL/ROM/ffmpeg.

**Architecture:** A driver-agnostic capture pipeline (`CaptureRecorder` → `FrameSink`/`EncoderSink` → `CaptureEncoder`) consuming immutable `CapturedFrame`s, with a `BackpressurePolicy` controlling queue-full behavior. Audio is tapped deterministically via a new public `AudioManager` capture-mode API that force-installs the `StreamBackedDeterministicAudioRuntime`; per-frame sample counts come from a non-mutating `AudioFrameClock.peekSamplesForNextFrame()`. Visibility (ghosts / game HUD / debug HUD) is a config-backed `TraceRenderVisibility` honored later by both live Trace Test Mode and the recorder.

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), Maven, LWJGL (only in Phase 2), ffmpeg (only in Phase 2). Reference spec: `docs/architecture/designs/2026-06-03-trace-capture-recorder-design.md`.

**Scope note:** This is Plan 1 of 2. Plan 2 ("Headless trace driver & CLI") covers the engine boot, `TraceReplayDriver` refactor, `TraceCaptureSession`, `TraceCaptureTool`, render-site gate wiring, and ffmpeg/integration tests. Author Plan 2 after this lands.

**Branch base:** `develop`. Per repo policy, every non-`master` commit carries the trailer block (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`). Engine changes under `src/main/` set `Changelog: updated` and stage `CHANGELOG.md`. Stage only the files each task names — the working tree is shared across sessions; never `git add -A`.

---

## File Structure

**New package `com.openggf.capture` (`src/main/java/com/openggf/capture/`):**

| File | Responsibility |
|------|----------------|
| `CapturedFrame.java` | Immutable record: RGBA pixels + that frame's stereo PCM + counts + index. Self-validating. |
| `BackpressurePolicy.java` | Enum: `BLOCK` / `DROP_OLDEST` / `FAIL`. |
| `CaptureException.java` | Checked exception for capture/encode failures. |
| `CaptureEncoder.java` | Interface: `open` → `encode*` → `finish`/`abort`. |
| `FrameSink.java` | Interface: `submit(frame)` + `Path stop()`. |
| `EncoderSink.java` | Bounded-queue background-thread sink applying a `BackpressurePolicy`, feeding a `CaptureEncoder`. Implements `FrameSink`. |
| `CaptureRecorder.java` | Façade: resolves the timestamped output path, owns the sink, exposes `start`/`submit`/`stop`. |
| `VideoFrameGrabber.java` | Interface: `byte[] grab()` + dims. (impl `GlReadPixelsGrabber` is Plan 2.) |
| `AudioFrameTap.java` | Interface: `int drain(short[] target)`. (impl `DrainPcmAudioTap` is Plan 2.) |

**Modified existing files:**

| File | Change |
|------|--------|
| `audio/runtime/AudioFrameClock.java` | Add non-mutating `peekSamplesForNextFrame()`. |
| `audio/AudioManager.java` | Add public `beginCaptureMode` / `drainCaptureFrame` / `endCaptureMode` + last-produced-count tracking. |
| `audio/runtime/StreamBackedDeterministicAudioRuntime.java` | Expose `lastProducedFrames()` (stereo-frame count from the most recent NORMAL `advanceFrame`). |
| `configuration/SonicConfiguration.java` | Add `TRACE_SHOW_DESYNC_GHOSTS`, `TRACE_SHOW_GAME_HUD`, `TRACE_SHOW_DEBUG_HUD`. |
| `configuration/SonicConfigurationService.java` | `putDefault` for the three new flags. |

**New `TraceRenderVisibility` (`src/main/java/com/openggf/testmode/TraceRenderVisibility.java`):** reads the three flags into three independent master gates; pure logic, no rendering, no `DebugOverlayManager` mutation.

**Tests (`src/test/java/com/openggf/...`):** mirror packages.

---

## Task 1: `CapturedFrame` record

**Files:**
- Create: `src/main/java/com/openggf/capture/CapturedFrame.java`
- Test: `src/test/java/com/openggf/capture/CapturedFrameTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedFrameTest {

    @Test
    void acceptsConsistentDimensionsAndPcm() {
        CapturedFrame f = new CapturedFrame(new byte[2 * 3 * 4], 2, 3,
                new short[800 * 2], 800, 7L);
        assertEquals(2, f.width());
        assertEquals(3, f.height());
        assertEquals(800, f.sampleCount());
        assertEquals(7L, f.frameIndex());
    }

    @Test
    void rejectsRgbaLengthMismatch() {
        assertThrows(IllegalArgumentException.class, () ->
                new CapturedFrame(new byte[10], 2, 3, new short[0], 0, 0L));
    }

    @Test
    void rejectsPcmShorterThanSampleCount() {
        assertThrows(IllegalArgumentException.class, () ->
                new CapturedFrame(new byte[4], 1, 1, new short[2], 800, 0L));
    }

    @Test
    void defensivelyCopiesSourceArraysSoProducerCanReuseBuffers() {
        byte[] rgba = new byte[]{1, 2, 3, 4};
        short[] pcm = new short[]{10, 20};
        CapturedFrame f = new CapturedFrame(rgba, 1, 1, pcm, 1, 0L);

        // Producer reuses/overwrites its buffers after submitting the frame.
        rgba[0] = 99;
        pcm[0] = 99;

        assertEquals(1, f.rgba()[0], "frame holds its own rgba copy");
        assertEquals(10, f.pcm()[0], "frame holds its own pcm copy");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=CapturedFrameTest" test`
Expected: FAIL — `CapturedFrame` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.capture;

/**
 * One captured game frame: RGBA pixels (top-left origin, row-major, 4 bytes/px)
 * plus the stereo PCM produced by that same frame's audio step.
 *
 * <p><b>Ownership:</b> the constructor defensively copies {@code rgba} and
 * {@code pcm}, so a producer may immediately reuse its grab/drain buffers after
 * constructing a frame. Frames are handed to an async encoder thread, so this
 * copy is what makes per-frame buffer reuse safe. (Accessors return the internal
 * copies; the encoder only reads them.)
 *
 * @param rgba        width*height*4 bytes, RGBA8888 (copied)
 * @param pcm         interleaved stereo shorts; length >= sampleCount*2 (copied)
 * @param sampleCount stereo frames of audio for this video frame
 * @param frameIndex  monotonic 0-based capture index
 */
public record CapturedFrame(byte[] rgba, int width, int height,
                            short[] pcm, int sampleCount, long frameIndex) {
    public CapturedFrame {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("negative dimensions");
        }
        if (rgba.length != width * height * 4) {
            throw new IllegalArgumentException(
                    "rgba length " + rgba.length + " != width*height*4 ("
                    + (width * height * 4) + ")");
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("negative sampleCount");
        }
        if (pcm.length < sampleCount * 2) {
            throw new IllegalArgumentException(
                    "pcm holds " + (pcm.length / 2) + " stereo frames, need "
                    + sampleCount);
        }
        // Defensive copy: the producer reuses its grab/drain buffers each frame,
        // but frames live on an async encoder queue. Copy so they can't be
        // mutated out from under the encoder.
        rgba = rgba.clone();
        pcm = pcm.clone();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=CapturedFrameTest" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/capture/CapturedFrame.java src/test/java/com/openggf/capture/CapturedFrameTest.java
git commit -m "feat(capture): CapturedFrame record with self-validation

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 2: `BackpressurePolicy`, `CaptureException`, `CaptureEncoder`, `FrameSink`

These are small declarations with no behavior to test in isolation; they are exercised by `EncoderSink` (Task 3) and `CaptureRecorder` (Task 4). Create them together.

**Files:**
- Create: `src/main/java/com/openggf/capture/BackpressurePolicy.java`
- Create: `src/main/java/com/openggf/capture/CaptureException.java`
- Create: `src/main/java/com/openggf/capture/CaptureEncoder.java`
- Create: `src/main/java/com/openggf/capture/FrameSink.java`

- [ ] **Step 1: Create `BackpressurePolicy`**

```java
package com.openggf.capture;

/** What a {@link FrameSink} does when its bounded queue is full. */
public enum BackpressurePolicy {
    /** Block the producer until space frees up. Never drops; paces the producer. */
    BLOCK,
    /** Discard the oldest queued frame to make room for the new one. */
    DROP_OLDEST,
    /** Throw {@link CaptureException} from {@code submit}. */
    FAIL
}
```

- [ ] **Step 2: Create `CaptureException`**

```java
package com.openggf.capture;

/** Thrown when a capture sink or encoder fails. */
public class CaptureException extends Exception {
    public CaptureException(String message) { super(message); }
    public CaptureException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 3: Create `CaptureEncoder`**

```java
package com.openggf.capture;

import java.nio.file.Path;

/**
 * Encodes a stream of {@link CapturedFrame}s to {@code output}. Lifecycle:
 * {@code open} once (receives the destination path), {@code encode} per frame
 * (in order), then {@code finish} (success) or {@code abort} (failure).
 * Implementations are driven from a single encoder thread by {@link EncoderSink}.
 */
public interface CaptureEncoder {
    /** @param output the file the encoder must write (owned by the recorder). */
    void open(Path output, int width, int height, int fps, int sampleRate) throws CaptureException;

    void encode(CapturedFrame frame) throws CaptureException;

    /** Flush and finalize; returns the written output file (normally {@code output}). */
    Path finish() throws CaptureException;

    /** Best-effort cleanup after a failure. Must not throw. */
    void abort();
}
```

- [ ] **Step 4: Create `FrameSink`**

```java
package com.openggf.capture;

import java.nio.file.Path;

/** Consumes captured frames. {@code stop} drains and finalizes, returning the file. */
public interface FrameSink {
    void submit(CapturedFrame frame) throws CaptureException;

    Path stop() throws CaptureException;
}
```

- [ ] **Step 5: Verify it compiles**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/capture/BackpressurePolicy.java src/main/java/com/openggf/capture/CaptureException.java src/main/java/com/openggf/capture/CaptureEncoder.java src/main/java/com/openggf/capture/FrameSink.java
git commit -m "feat(capture): backpressure policy, encoder + sink interfaces

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 3: `EncoderSink` (bounded queue + encoder thread + policy)

**Files:**
- Create: `src/main/java/com/openggf/capture/EncoderSink.java`
- Test: `src/test/java/com/openggf/capture/EncoderSinkTest.java`

- [ ] **Step 1: Write the failing test**

The test uses a controllable fake encoder: a gate latch holds the worker inside `encode` so the queue can be filled deterministically.

```java
package com.openggf.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EncoderSinkTest {

    private static CapturedFrame frame(long index) {
        return new CapturedFrame(new byte[4], 1, 1, new short[2], 1, index);
    }

    /** Records encoded frame indices; an optional gate blocks the first encode. */
    private static final class FakeEncoder implements CaptureEncoder {
        final List<Long> encoded = new ArrayList<>();
        final CountDownLatch gate;
        volatile boolean opened;
        volatile boolean finished;
        volatile boolean aborted;

        FakeEncoder(CountDownLatch gate) { this.gate = gate; }

        @Override public synchronized void open(Path output, int w, int h, int fps, int sr) { opened = true; }
        @Override public void encode(CapturedFrame f) throws CaptureException {
            if (gate != null) {
                try { gate.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CaptureException("interrupted", e);
                }
            }
            synchronized (encoded) { encoded.add(f.frameIndex()); }
        }
        @Override public synchronized Path finish() { finished = true; return Path.of("out.mkv"); }
        @Override public synchronized void abort() { aborted = true; }
    }

    @Test
    void blockNeverDropsAndPreservesOrder() throws Exception {
        FakeEncoder enc = new FakeEncoder(null);
        EncoderSink sink = new EncoderSink(enc, BackpressurePolicy.BLOCK, 2);
        sink.open(Path.of("out.mkv"), 1, 1, 60, 48000);
        for (long i = 0; i < 50; i++) {
            sink.submit(frame(i));
        }
        sink.stop();
        assertTrue(enc.opened);
        assertTrue(enc.finished);
        assertEquals(50, enc.encoded.size());
        for (int i = 0; i < 50; i++) {
            assertEquals((long) i, enc.encoded.get(i), "order preserved");
        }
        assertEquals(0, sink.droppedCount());
    }

    @Test
    void dropOldestDiscardsWhenFull() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeEncoder enc = new FakeEncoder(gate);
        EncoderSink sink = new EncoderSink(enc, BackpressurePolicy.DROP_OLDEST, 1);
        sink.open(Path.of("out.mkv"), 1, 1, 60, 48000);
        // First submit is taken by the worker and blocks in encode() on the gate.
        sink.submit(frame(0));
        // Give the worker a moment to pull frame 0 into encode().
        TimeUnit.MILLISECONDS.sleep(50);
        // Queue capacity 1: fill it, then overflow to force drops.
        sink.submit(frame(1)); // sits in queue
        sink.submit(frame(2)); // drops frame 1, queues frame 2
        sink.submit(frame(3)); // drops frame 2, queues frame 3
        gate.countDown();
        sink.stop();
        assertTrue(sink.droppedCount() >= 1, "at least one frame dropped");
        // Frame 0 (in-flight) and the newest survivor (3) must be encoded.
        assertTrue(enc.encoded.contains(0L));
        assertTrue(enc.encoded.contains(3L));
    }

    @Test
    void failThrowsWhenFull() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeEncoder enc = new FakeEncoder(gate);
        EncoderSink sink = new EncoderSink(enc, BackpressurePolicy.FAIL, 1);
        sink.open(Path.of("out.mkv"), 1, 1, 60, 48000);
        sink.submit(frame(0)); // taken by worker, blocks on gate
        TimeUnit.MILLISECONDS.sleep(50);
        sink.submit(frame(1)); // queued (capacity 1)
        assertThrows(CaptureException.class, () -> sink.submit(frame(2)));
        gate.countDown();
        sink.stop();
    }

    /** An encoder that throws on its first encode. */
    private static final class FailingEncoder implements CaptureEncoder {
        volatile boolean aborted;
        @Override public void open(Path output, int w, int h, int fps, int sr) { }
        @Override public void encode(CapturedFrame f) throws CaptureException {
            throw new CaptureException("boom");
        }
        @Override public Path finish() { return Path.of("never"); }
        @Override public void abort() { aborted = true; }
    }

    @Test
    @Timeout(10)
    void encoderFailureSurfacesWithoutHanging() throws Exception {
        FailingEncoder enc = new FailingEncoder();
        EncoderSink sink = new EncoderSink(enc, BackpressurePolicy.BLOCK, 1);
        sink.open(Path.of("out.mkv"), 1, 1, 60, 48000);
        // The worker dies on the first encode; further submits and stop must
        // surface the failure rather than block forever on a full queue.
        CaptureException failure = assertThrows(CaptureException.class, () -> {
            for (long i = 0; i < 1000; i++) {
                sink.submit(frame(i));
            }
            sink.stop();
        });
        assertTrue(failure.getMessage().contains("encoder thread failed")
                || "boom".equals(failure.getCause() != null ? failure.getCause().getMessage() : null));
        assertTrue(enc.aborted, "encoder aborted on failure");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=EncoderSinkTest" test`
Expected: FAIL — `EncoderSink` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.capture;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link FrameSink} that hands frames to a {@link CaptureEncoder} on a single
 * background thread via a bounded queue. Queue-full behavior follows the
 * configured {@link BackpressurePolicy}. {@code BLOCK} guarantees no drops.
 */
public final class EncoderSink implements FrameSink {

    private static final CapturedFrame POISON =
            new CapturedFrame(new byte[0], 0, 0, new short[0], 0, -1L);

    private final CaptureEncoder encoder;
    private final BackpressurePolicy policy;
    private final BlockingQueue<CapturedFrame> queue;
    private final AtomicLong dropped = new AtomicLong();
    private Thread worker;
    private volatile CaptureException workerFailure;
    private volatile Path output;

    public EncoderSink(CaptureEncoder encoder, BackpressurePolicy policy, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.encoder = encoder;
        this.policy = policy;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public void open(Path output, int width, int height, int fps, int sampleRate) throws CaptureException {
        encoder.open(output, width, height, fps, sampleRate);
        worker = new Thread(this::runWorker, "capture-encoder");
        worker.start();
    }

    @Override
    public void submit(CapturedFrame frame) throws CaptureException {
        if (workerFailure != null) {
            throw new CaptureException("encoder thread failed", workerFailure);
        }
        switch (policy) {
            case BLOCK -> {
                try {
                    // Timed offer rather than a blocking put: if the worker dies
                    // while the queue is full, an unbounded put() would block the
                    // producer forever and it would never reach stop(). Re-check
                    // worker health between attempts.
                    while (!queue.offer(frame, 50, TimeUnit.MILLISECONDS)) {
                        if (workerFailure != null) {
                            throw new CaptureException("encoder thread failed", workerFailure);
                        }
                        if (!worker.isAlive()) {
                            throw new CaptureException("encoder thread exited unexpectedly");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CaptureException("interrupted while submitting", e);
                }
            }
            case DROP_OLDEST -> {
                while (!queue.offer(frame)) {
                    if (queue.poll() != null) {
                        dropped.incrementAndGet();
                    }
                }
            }
            case FAIL -> {
                if (!queue.offer(frame)) {
                    throw new CaptureException("capture queue full (FAIL policy)");
                }
            }
        }
    }

    @Override
    public Path stop() throws CaptureException {
        try {
            // Deliver the poison pill without hanging: if the worker has already
            // died (e.g. encoder failure) the queue may be full and a blocking
            // put would never return. Offer with a timeout while the worker is
            // alive; bail out as soon as it has exited or recorded a failure.
            while (worker.isAlive() && !queue.offer(POISON, 50, TimeUnit.MILLISECONDS)) {
                if (workerFailure != null) {
                    break;
                }
            }
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException("interrupted while stopping", e);
        }
        if (workerFailure != null) {
            throw new CaptureException("encoder thread failed", workerFailure);
        }
        return output;
    }

    public long droppedCount() {
        return dropped.get();
    }

    private void runWorker() {
        try {
            while (true) {
                CapturedFrame frame = queue.take();
                if (frame == POISON) {
                    output = encoder.finish();
                    return;
                }
                encoder.encode(frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerFailure = new CaptureException("encoder thread interrupted", e);
            encoder.abort();
        } catch (CaptureException e) {
            workerFailure = e;
            encoder.abort();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=EncoderSinkTest" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/capture/EncoderSink.java src/test/java/com/openggf/capture/EncoderSinkTest.java
git commit -m "feat(capture): EncoderSink bounded-queue background encoder with backpressure

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 4: `CaptureRecorder` façade

**Files:**
- Create: `src/main/java/com/openggf/capture/CaptureRecorder.java`
- Test: `src/test/java/com/openggf/capture/CaptureRecorderTest.java`

`CaptureRecorder` resolves a timestamped output path and owns an `EncoderSink`. The timestamp is injected (no `Date.now()`-style hidden clock) so tests are deterministic and it composes with the engine's clock later.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.capture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaptureRecorderTest {

    private static final class FakeEncoder implements CaptureEncoder {
        final List<Long> encoded = new ArrayList<>();
        Path openedOutput;
        int width, height, fps, sampleRate;
        Path finishedAt;

        @Override public void open(Path output, int w, int h, int f, int sr) {
            openedOutput = output; width = w; height = h; fps = f; sampleRate = sr;
        }
        @Override public void encode(CapturedFrame frame) { encoded.add(frame.frameIndex()); }
        @Override public Path finish() { finishedAt = openedOutput; return finishedAt; }
        @Override public void abort() { }
    }

    private static CapturedFrame frame(long i) {
        return new CapturedFrame(new byte[4], 1, 1, new short[2], 1, i);
    }

    @Test
    void resolvesTimestampedPathAndDrivesEncoder() throws Exception {
        FakeEncoder enc = new FakeEncoder();
        CaptureRecorder recorder = new CaptureRecorder(
                enc, BackpressurePolicy.BLOCK, 4,
                Path.of("/out"), "aiz1", "20260603-101500");
        Path expected = Path.of("/out", "capture-aiz1-20260603-101500.mkv");
        assertEquals(expected, recorder.outputFile());

        recorder.start(320, 224, 60, 48000);
        for (long i = 0; i < 5; i++) recorder.submit(frame(i));
        Path result = recorder.stop();

        assertEquals(expected, enc.openedOutput, "recorder hands its output path to the encoder");
        assertEquals(expected, result, "stop returns the finalized output file");
        assertEquals(320, enc.width);
        assertEquals(224, enc.height);
        assertEquals(60, enc.fps);
        assertEquals(48000, enc.sampleRate);
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), enc.encoded);
        assertNotNull(enc.finishedAt);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=CaptureRecorderTest" test`
Expected: FAIL — `CaptureRecorder` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.capture;

import java.nio.file.Path;

/**
 * Driver-agnostic recording façade. A driver calls {@link #start} once,
 * {@link #submit} per frame, then {@link #stop}. The output filename is
 * {@code capture-<label>-<timestamp>.mkv} under {@code outputDir}.
 *
 * <p>The timestamp string is injected so callers control formatting/clock and
 * tests stay deterministic.
 */
public final class CaptureRecorder {

    private final EncoderSink sink;
    private final Path outputFile;

    public CaptureRecorder(CaptureEncoder encoder, BackpressurePolicy policy, int queueCapacity,
                           Path outputDir, String label, String timestamp) {
        this.sink = new EncoderSink(encoder, policy, queueCapacity);
        this.outputFile = outputDir.resolve("capture-" + label + "-" + timestamp + ".mkv");
    }

    public Path outputFile() {
        return outputFile;
    }

    public void start(int width, int height, int fps, int sampleRate) throws CaptureException {
        sink.open(outputFile, width, height, fps, sampleRate);
    }

    public void submit(CapturedFrame frame) throws CaptureException {
        sink.submit(frame);
    }

    /** Drains and finalizes; returns the encoder's written file. */
    public Path stop() throws CaptureException {
        return sink.stop();
    }

    public long droppedCount() {
        return sink.droppedCount();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=CaptureRecorderTest" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/capture/CaptureRecorder.java src/test/java/com/openggf/capture/CaptureRecorderTest.java
git commit -m "feat(capture): CaptureRecorder facade with timestamped output path

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 5: `VideoFrameGrabber` + `AudioFrameTap` interfaces

Thin seams; implementations (`GlReadPixelsGrabber`, `DrainPcmAudioTap`) land in Plan 2 because they touch GL/`AudioManager` wiring. Declare the interfaces now so `CaptureRecorder` consumers compile against stable types.

**Files:**
- Create: `src/main/java/com/openggf/capture/VideoFrameGrabber.java`
- Create: `src/main/java/com/openggf/capture/AudioFrameTap.java`

- [ ] **Step 1: Create `VideoFrameGrabber`**

```java
package com.openggf.capture;

/** Produces RGBA8888 pixels (top-left origin) for the current framebuffer. */
public interface VideoFrameGrabber {
    int width();

    int height();

    /** @return a fresh {@code width()*height()*4} byte array of RGBA pixels. */
    byte[] grab();
}
```

- [ ] **Step 2: Create `AudioFrameTap`**

```java
package com.openggf.capture;

/** Drains the current frame's stereo PCM into {@code target}. */
public interface AudioFrameTap {
    /**
     * @param target interleaved stereo buffer, sized for the max samples/frame*2
     * @return the number of stereo frames written (0..target.length/2)
     */
    int drain(short[] target);
}
```

- [ ] **Step 3: Verify it compiles**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/capture/VideoFrameGrabber.java src/main/java/com/openggf/capture/AudioFrameTap.java
git commit -m "feat(capture): video grabber and audio tap interfaces

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 6: `AudioFrameClock.peekSamplesForNextFrame()` (non-mutating)

Spec §4.1: the tap must size drains from a non-mutating count, never `samplesForNextFrame()` (which advances `remainder`/`totalSamplesProduced`).

**Files:**
- Modify: `src/main/java/com/openggf/audio/runtime/AudioFrameClock.java`
- Test: `src/test/java/com/openggf/audio/runtime/AudioFrameClockTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.audio.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioFrameClockTest {

    @Test
    void peekMatchesNextProductionButDoesNotMutate() {
        AudioFrameClock clock = new AudioFrameClock(48000, 60);
        int peek = clock.peekSamplesForNextFrame();
        // Peeking repeatedly must not change anything.
        assertEquals(peek, clock.peekSamplesForNextFrame());
        assertEquals(0, clock.totalSamplesProduced());
        // The actual production matches the prior peek.
        int produced = clock.samplesForNextFrame();
        assertEquals(peek, produced);
        assertEquals(produced, clock.totalSamplesProduced());
    }

    @Test
    void peekTracksRemainderAcrossFrames() {
        AudioFrameClock clock = new AudioFrameClock(48000, 60); // 800 exactly -> always 800
        for (int i = 0; i < 10; i++) {
            assertEquals(clock.peekSamplesForNextFrame(), clock.samplesForNextFrame());
        }
    }

    @Test
    void peekReflectsFractionalRates() {
        AudioFrameClock clock = new AudioFrameClock(44100, 60); // 735 with remainder
        long total = 0;
        for (int i = 0; i < 60; i++) {
            int peek = clock.peekSamplesForNextFrame();
            int produced = clock.samplesForNextFrame();
            assertEquals(peek, produced);
            total += produced;
        }
        assertEquals(44100, total, "one second integrates to the sample rate");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=AudioFrameClockTest" test`
Expected: FAIL — `peekSamplesForNextFrame` not defined.

- [ ] **Step 3: Add the method (place it directly after `samplesForNextFrame()` in `AudioFrameClock.java`)**

```java
    /**
     * The sample count {@link #samplesForNextFrame()} would return next, without
     * advancing the clock. Mirrors the same numerator/remainder arithmetic but
     * mutates nothing — use this to size a per-frame PCM drain.
     */
    public int peekSamplesForNextFrame() {
        return (sampleRate + remainder) / frameRate;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=AudioFrameClockTest" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/runtime/AudioFrameClock.java src/test/java/com/openggf/audio/runtime/AudioFrameClockTest.java
git commit -m "feat(audio): non-mutating AudioFrameClock.peekSamplesForNextFrame

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 7: Expose `StreamBackedDeterministicAudioRuntime.lastProducedFrames()`

The capture tap drains exactly what the most recent NORMAL `advanceFrame` produced. Record that count.

**Files:**
- Modify: `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java`
- Test: `src/test/java/com/openggf/audio/runtime/StreamBackedRuntimeProducedCountTest.java`

- [ ] **Step 1: Write the failing test**

This test drives the runtime directly with no music/sfx streams (silence), verifying the produced-frame count equals the clock cadence and that `drainPcm` returns exactly that many frames.

```java
package com.openggf.audio.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamBackedRuntimeProducedCountTest {

    private static StreamBackedDeterministicAudioRuntime newRuntime() {
        return new StreamBackedDeterministicAudioRuntime(
                new AudioFrameClock(48000, 60),
                new AudioOutputFifo(48000 * 2),
                new PcmHistoryRing(48000),
                1);
    }

    @Test
    void recordsProducedFrameCountForNormalAdvance() {
        StreamBackedDeterministicAudioRuntime runtime = newRuntime();
        runtime.advanceFrame(1, FrameAudioMode.NORMAL);
        assertEquals(800, runtime.lastProducedFrames());

        short[] target = new short[800 * 2];
        int drained = runtime.drainPcm(target, runtime.lastProducedFrames());
        assertEquals(800, drained, "drain returns exactly the produced frame count");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=StreamBackedRuntimeProducedCountTest" test`
Expected: FAIL — `lastProducedFrames` not defined.

- [ ] **Step 3: Implement**

In `StreamBackedDeterministicAudioRuntime.java`, add a field near the other state:

```java
    private int lastProducedFrames;
```

In `advanceFrame(...)`, inside the `if (mode == FrameAudioMode.NORMAL) { ... }` block (where `pcmHistory.write(...)` / `outputFifo.write(...)` run with `samples / 2`), record the count:

```java
        if (mode == FrameAudioMode.NORMAL) {
            if (pcmHistory != null) {
                pcmHistory.write(musicScratch, samples / 2);
            }
            outputFifo.write(musicScratch, samples / 2);
            lastProducedFrames = samples / 2;
        }
```

Add the accessor near `providesPresentationPcm()`:

```java
    /** Stereo-frame count produced by the most recent NORMAL {@link #advanceFrame}. */
    public int lastProducedFrames() {
        return lastProducedFrames;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=StreamBackedRuntimeProducedCountTest" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java src/test/java/com/openggf/audio/runtime/StreamBackedRuntimeProducedCountTest.java
git commit -m "feat(audio): expose StreamBackedDeterministicAudioRuntime.lastProducedFrames

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 8: `AudioManager` capture-mode API

Spec §5: public `beginCaptureMode` / `drainCaptureFrame` / `endCaptureMode` that force-install a `StreamBackedDeterministicAudioRuntime` regardless of `backend.supportsDeterministicRuntimePresentation()`, reachable from `com.openggf.tools`.

**Files:**
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Test: `src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java`

> Before writing the test, read `AudioManager.java` around the existing
> `configureDeterministicRuntimeForBackend()` (lines ~99–119) and the
> package-private `setDeterministicAudioRuntime(...)` (line ~84) to match the
> field names (`deterministicAudioRuntime`, `commandTimeline`) and the existing
> sizing constants (`OUTPUT_FIFO_SECONDS`, `configuredPcmHistoryFrames`,
> `REVERSE_RELEASE_CROSSFADE_MS`). Read `AudioTestFixtures.RecordingAudioBackend`
> (extends `NullAudioBackend`) for the test backend.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.audio;

import com.openggf.audio.AudioTestFixtures.RecordingAudioBackend;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioManagerCaptureModeTest {

    @Test
    void captureModeProducesPerFramePcmEvenWithNonPresentationBackend() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();                                 // isolate from other tests
        audio.setBackend(new RecordingAudioBackend());      // null backend: no real presentation

        audio.beginCaptureMode(48000, 60);

        short[] target = new short[800 * 2];
        long total = 0;
        for (int i = 0; i < 60; i++) {
            audio.advanceGameplayFrameAudio();          // NORMAL advance (writes PCM)
            int frames = audio.drainCaptureFrame(target);
            assertTrue(frames > 0, "each frame produces PCM in capture mode");
            total += frames;
        }
        assertEquals(48000, total, "one second of stereo frames at 48kHz/60fps");

        audio.endCaptureMode();
    }

    @Test
    void drainBeforeAnyAdvanceReturnsZero() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());
        audio.beginCaptureMode(48000, 60);

        assertEquals(0, audio.drainCaptureFrame(new short[800 * 2]),
                "nothing produced yet");

        audio.endCaptureMode();
    }

    @Test
    void secondDrainInSameFrameReturnsZero() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());
        audio.beginCaptureMode(48000, 60);

        audio.advanceGameplayFrameAudio();
        short[] target = new short[800 * 2];
        assertEquals(800, audio.drainCaptureFrame(target), "first drain takes the frame");
        assertEquals(0, audio.drainCaptureFrame(target),
                "FIFO emptied; a second drain without advancing yields nothing");

        audio.endCaptureMode();
    }
}
```

> `AudioManager` is a singleton: `getInstance()` + `resetState()` + `setBackend(...)`
> is the established test pattern (cf. `TestAudioManagerFrameMonotonic.java:28-30`,
> `TestAudioLogicalSnapshot.java:31-33`). `RecordingAudioBackend` extends
> `NullAudioBackend`, whose `supportsDeterministicRuntimePresentation()` is the
> non-presentation path `beginCaptureMode` must override.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=AudioManagerCaptureModeTest" test`
Expected: FAIL — capture-mode methods not defined.

- [ ] **Step 3: Implement the capture-mode API**

Add fields and methods to `AudioManager`. The runtime is force-installed via the existing package-private `applyDeterministicAudioRuntime(...)`; the prior runtime is snapshotted for restore.

```java
    private DeterministicAudioRuntime preCaptureRuntime;
    private StreamBackedDeterministicAudioRuntime captureRuntime;

    /**
     * Force-installs a deterministic streaming runtime for offline capture,
     * independent of {@code backend.supportsDeterministicRuntimePresentation()}.
     * Drive {@link #advanceGameplayFrameAudio()} (or {@link GameLoop#step()})
     * each frame, then {@link #drainCaptureFrame}.
     */
    public void beginCaptureMode(int sampleRate, int frameRate) {
        preCaptureRuntime = this.deterministicAudioRuntime;
        int minFrameCapacity = Math.max(1, sampleRate / Math.max(1, frameRate));
        int fifoFrames = Math.max(minFrameCapacity, sampleRate * OUTPUT_FIFO_SECONDS);
        int historyFrames = Math.max(minFrameCapacity, configuredPcmHistoryFrames(sampleRate));
        int crossfadeFrames = Math.max(1, sampleRate * REVERSE_RELEASE_CROSSFADE_MS / 1000);
        captureRuntime = new StreamBackedDeterministicAudioRuntime(
                new AudioFrameClock(sampleRate, frameRate),
                new AudioOutputFifo(fifoFrames),
                new PcmHistoryRing(historyFrames),
                crossfadeFrames);
        applyDeterministicAudioRuntime(captureRuntime);
    }

    /**
     * Drains the current frame's presentation PCM into {@code target} and
     * returns the stereo-frame count produced by the most recent NORMAL
     * audio frame (never re-advances the clock).
     */
    public int drainCaptureFrame(short[] target) {
        if (captureRuntime == null) {
            throw new IllegalStateException("beginCaptureMode() not called");
        }
        // Drain exactly what the most recent NORMAL advanceFrame produced, and
        // report the ACTUAL drained count. Returning the requested size would
        // mask an underrun (FIFO short) or a double-drain (FIFO already empty).
        int requested = captureRuntime.lastProducedFrames();
        return captureRuntime.drainPcm(target, requested);
    }

    /** Restores the runtime that was active before {@link #beginCaptureMode}. */
    public void endCaptureMode() {
        if (captureRuntime == null) {
            return;
        }
        applyDeterministicAudioRuntime(
                preCaptureRuntime != null ? preCaptureRuntime : NoOpDeterministicAudioRuntime.INSTANCE);
        captureRuntime = null;
        preCaptureRuntime = null;
    }
```

> `applyDeterministicAudioRuntime`, `OUTPUT_FIFO_SECONDS`,
> `configuredPcmHistoryFrames`, `REVERSE_RELEASE_CROSSFADE_MS`,
> `NoOpDeterministicAudioRuntime`, and the runtime/FIFO/ring/clock types are all
> already present in `AudioManager.java` (verified in the spec's §5 anchors).
> `advanceGameplayFrameAudio()` already exists (line ~638).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=AudioManagerCaptureModeTest" test`
Expected: PASS.

- [ ] **Step 5: Run the broader audio suite for regressions**

Run: `mvn "-Dtest=com.openggf.audio.*" test`
Expected: PASS (no regressions in existing audio tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/audio/AudioManager.java src/test/java/com/openggf/audio/AudioManagerCaptureModeTest.java
git commit -m "feat(audio): public AudioManager capture-mode API for offline PCM tap

beginCaptureMode/drainCaptureFrame/endCaptureMode force-install a
StreamBackedDeterministicAudioRuntime independent of backend presentation
support, so a headless tools driver can drain deterministic per-frame PCM.

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 9: Trace-framework visibility config flags

Spec §6.1: three new `SonicConfiguration` flags with defaults.

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `CONFIGURATION.md`
- Modify: `CHANGELOG.md`
- Test: `src/test/java/com/openggf/configuration/TraceVisibilityConfigDefaultsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceVisibilityConfigDefaultsTest {

    @BeforeEach
    void resetConfig() {
        // The config service is a singleton that loads user/local config.json;
        // reset to defaults so this test is independent of dev environment and
        // of any other test that mutated config values.
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void traceVisibilityDefaults() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        assertTrue(config.getBoolean(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS),
                "ghosts default on");
        assertTrue(config.getBoolean(SonicConfiguration.TRACE_SHOW_GAME_HUD),
                "game HUD default on");
        assertFalse(config.getBoolean(SonicConfiguration.TRACE_SHOW_DEBUG_HUD),
                "debug HUD default off");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TraceVisibilityConfigDefaultsTest" test`
Expected: FAIL — the enum constants do not exist (compilation error).

- [ ] **Step 3: Add the enum constants**

In `SonicConfiguration.java`, after `TRACE_REWIND_KEY` (line ~257), add:

```java
	/**
	 * Trace Test Mode / capture: render the desync ghost(s). Default true.
	 */
	TRACE_SHOW_DESYNC_GHOSTS,
	/**
	 * Trace Test Mode / capture: render the game HUD (rings/score/time). Default true.
	 */
	TRACE_SHOW_GAME_HUD,
	/**
	 * Trace Test Mode / capture: render the debug HUD. When true, the per-element
	 * DebugOverlayToggle states decide which panels show. Default false.
	 */
	TRACE_SHOW_DEBUG_HUD,
```

- [ ] **Step 4: Add the defaults**

In `SonicConfigurationService.java`, alongside the other `putDefault(...)` calls (near line ~426), add:

```java
		putDefault(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, true);
		putDefault(SonicConfiguration.TRACE_SHOW_GAME_HUD, true);
		putDefault(SonicConfiguration.TRACE_SHOW_DEBUG_HUD, false);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn "-Dtest=TraceVisibilityConfigDefaultsTest" test`
Expected: PASS.

- [ ] **Step 6: Document the flags in `CONFIGURATION.md`**

Add a row/entry for each of the three flags under the appropriate (trace / debug) section, e.g.:

```markdown
- `TRACE_SHOW_DESYNC_GHOSTS` (default `true`) — in Trace Test Mode and trace capture, render the desync ghost(s).
- `TRACE_SHOW_GAME_HUD` (default `true`) — render the game HUD (rings/score/time) during trace replay/capture.
- `TRACE_SHOW_DEBUG_HUD` (default `false`) — render the debug HUD during trace replay/capture; individual panels follow the existing `DebugOverlayToggle` states.
```

- [ ] **Step 7: Add a CHANGELOG entry**

Add under the appropriate unreleased section of `CHANGELOG.md`:

```markdown
- Added `TRACE_SHOW_DESYNC_GHOSTS` / `TRACE_SHOW_GAME_HUD` / `TRACE_SHOW_DEBUG_HUD` config flags for trace replay and capture visibility (foundation for the trace capture recorder).
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/SonicConfigurationService.java src/test/java/com/openggf/configuration/TraceVisibilityConfigDefaultsTest.java CONFIGURATION.md CHANGELOG.md
git commit -m "feat(config): trace visibility flags (ghosts/game HUD/debug HUD)

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a"
```

---

## Task 10: `TraceRenderVisibility` (config → render-gate decisions)

Pure value object: reads the three flags into three independent master gates (`showGhosts` / `showGameHud` / `showDebugHud`). It does **not** mutate `DebugOverlayManager` — `showDebugHud()` gates whether the debug HUD renders at all, and the existing per-element `DebugOverlayToggle` states are honored at the render site (preserving per-panel state). Render-site wiring (ghost/game-HUD/debug-HUD call sites) is Plan 2; this task delivers and tests the decision logic in isolation.

**Files:**
- Create: `src/main/java/com/openggf/testmode/TraceRenderVisibility.java`
- Test: `src/test/java/com/openggf/testmode/TraceRenderVisibilityTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.testmode;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceRenderVisibilityTest {

    @BeforeEach
    @AfterEach
    void resetConfig() {
        // These tests mutate the config singleton; reset before and after so
        // neither dev environment nor sibling tests leak state across runs.
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void readsAllThreeFlagsIndependentlyFromConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, true);
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_GAME_HUD, false);
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_DEBUG_HUD, true);

        TraceRenderVisibility vis = TraceRenderVisibility.fromConfig(config);
        assertTrue(vis.showGhosts());
        assertFalse(vis.showGameHud());
        assertTrue(vis.showDebugHud());
    }

    @Test
    void reflectsFlippedFlags() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, false);
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_GAME_HUD, true);
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_DEBUG_HUD, false);

        TraceRenderVisibility vis = TraceRenderVisibility.fromConfig(config);
        assertFalse(vis.showGhosts());
        assertTrue(vis.showGameHud());
        assertFalse(vis.showDebugHud());
    }
}
```

> `setConfigValue(key, value)` mirrors the call in
> `VisualReferenceGenerator.initialize()`. No `DebugOverlayManager` is touched
> here — `showDebugHud()` is a master gate read at the render site (Plan 2);
> the existing per-element `DebugOverlayToggle` states are left untouched so
> per-panel state is preserved.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TraceRenderVisibilityTest" test`
Expected: FAIL — `TraceRenderVisibility` does not exist.

- [ ] **Step 3: Implement**

```java
package com.openggf.testmode;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;

/**
 * Resolves trace render-visibility decisions from config as three independent
 * master gates. Honored by both live Trace Test Mode and the headless capture
 * recorder (Plan 2 wires the gates at the render sites).
 *
 * <p>{@code showDebugHud()} is a master gate only — it does NOT mutate
 * {@code DebugOverlayManager}. When true, the render site renders the debug HUD
 * and the existing per-element {@code DebugOverlayToggle} states decide which
 * panels show; when false, the debug HUD is skipped without touching any toggle
 * state. (Driving per-panel selection from capture config in headless mode is a
 * Plan 2 concern.)
 */
public final class TraceRenderVisibility {

    private final boolean showGhosts;
    private final boolean showGameHud;
    private final boolean showDebugHud;

    private TraceRenderVisibility(boolean showGhosts, boolean showGameHud, boolean showDebugHud) {
        this.showGhosts = showGhosts;
        this.showGameHud = showGameHud;
        this.showDebugHud = showDebugHud;
    }

    public static TraceRenderVisibility fromConfig(SonicConfigurationService config) {
        return new TraceRenderVisibility(
                config.getBoolean(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS),
                config.getBoolean(SonicConfiguration.TRACE_SHOW_GAME_HUD),
                config.getBoolean(SonicConfiguration.TRACE_SHOW_DEBUG_HUD));
    }

    public boolean showGhosts() { return showGhosts; }

    public boolean showGameHud() { return showGameHud; }

    public boolean showDebugHud() { return showDebugHud; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TraceRenderVisibilityTest" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/testmode/TraceRenderVisibility.java src/test/java/com/openggf/testmode/TraceRenderVisibilityTest.java
git commit -m "feat(testmode): TraceRenderVisibility config-to-gate decisions

Changelog: n/a: internal capture scaffolding, not user-reachable yet
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 11: Foundation green-sweep

- [ ] **Step 1: Run all new/affected suites together**

Run:
```
mvn "-Dtest=CapturedFrameTest,EncoderSinkTest,CaptureRecorderTest,AudioFrameClockTest,StreamBackedRuntimeProducedCountTest,AudioManagerCaptureModeTest,TraceVisibilityConfigDefaultsTest,TraceRenderVisibilityTest" test
```
Expected: PASS, all tests green.

- [ ] **Step 2: Full compile to confirm no broken references**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3 (optional): broader regression**

Run: `mvn "-Dtest=com.openggf.audio.*,com.openggf.configuration.*" test`
Expected: PASS — no regressions in audio/config suites.

---

## Plan 2 preview (NOT part of this plan — author after Plan 1 lands)

Detailed step-level tasks to be written against the real engine internals:

1. **`GlReadPixelsGrabber`** implements `VideoFrameGrabber` over
   `ScreenshotCapture`-style `glReadPixels` (raw bytes, no Y-flip — ffmpeg
   handles `vflip`). Test via an offscreen GL fixture.
2. **`DrainPcmAudioTap`** implements `AudioFrameTap` over
   `AudioManager.drainCaptureFrame(...)`.
3. **`FfmpegEncoder`** implements `CaptureEncoder`: phase-1 video pipe
   (`-f rawvideo -pix_fmt rgba ... -vf "vflip,scale=iw*N:ih*N:flags=neighbor"
   -c:v ffv1`) + temp `s16le`; phase-2 `-c:v copy -c:a flac` mux; ffmpeg PATH
   discovery; opt-in smoke test (skipped when ffmpeg absent).
4. **`TraceReplayDriver`** — extract the UI-agnostic body of
   `TraceSessionLauncher.finishLaunchAfterGameBootstrap()`; consumed by both the
   live launcher and the capture session (spec §2.3).
5. **Headless boot** — `EngineContext` + `new GameLoop(...)` +
   `SessionManager.openGameplaySession(...)` offscreen, no master-title/fade
   (spec §2.3).
6. **`TraceCaptureSession`** — `stepAndRender()` via live `GameLoop.step()` +
   live LEVEL render; `isComplete()` via `LiveTraceComparator`.
7. **Render-site gate wiring** — apply `TraceRenderVisibility` at the
   `GhostTraceRenderer` call site, the game-HUD render, and the debug-HUD render
   (honored by live Trace Test Mode too).
8. **`TraceCaptureTool`** CLI (`tools`) — arg parsing, boot, capture loop, teardown.
9. **Integration test** — short headless capture with a fake encoder asserting
   exact frame/sample counts + monotonic indices; determinism (two runs →
   identical pre-encode stream).
```
