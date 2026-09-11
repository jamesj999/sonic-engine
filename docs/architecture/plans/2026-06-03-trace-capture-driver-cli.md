# Trace Capture — Headless Driver & CLI Implementation Plan (Plan 2 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the Plan 1 capture subsystem to the real engine: render a chosen trace headless (with desync ghosts), tap video+audio per frame, and write a synced lossless MKV via ffmpeg, driven by a `tools` CLI.

**Architecture:** A scope-agnostic `FfmpegEncoder` + `GlReadPixelsGrabber` + `DrainPcmAudioTap` complete the `com.openggf.capture` pipeline. A UI-agnostic `TraceReplayDriver` (extracted from `TraceSessionLauncher`) drives the deterministic replay; a `HeadlessGameBoot` helper stands up an offscreen `GameLoop` + gameplay session; `TraceRenderVisibility` gates the ghost/game-HUD/debug-HUD sites in `LevelRenderer`; `TraceCaptureSession` ties step→render→grab→submit; `TraceCaptureTool` is the CLI.

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), Maven, LWJGL (GLFW/OpenGL), ffmpeg (external, on PATH). Reference spec: `docs/architecture/designs/2026-06-03-trace-capture-recorder-design.md`. Prereq: Plan 1 (`docs/architecture/plans/2026-06-03-trace-capture-foundation.md`) is merged on `develop`.

**Branch base:** `develop`. Stage only the files each task names (shared tree — never `git add -A`). Engine commits carry the full trailer block; `feat`/`fix` under `src/main/` set `Changelog: updated` (+stage `CHANGELOG.md`) or a justified `n/a`.

---

## Execution note — two phases, different verifiability

- **Phase A (Tasks 1–4): capture I/O + config.** Pure / lightly-coupled. Unit-testable with no ROM/GL (ffmpeg only for an opt-in smoke test). **Workflow-safe.**
- **Phase B (Tasks 5–10): engine integration + CLI.** Needs a **ROM, a live offscreen GL context, and ffmpeg** to verify end-to-end, and Task 5 is a refactor of fragile shared code. These should be executed with **review checkpoints**, not blindly — an autonomous worker without ROM/GL/ffmpeg cannot run their integration tests. Each Phase B task states its verification preconditions.

---

## File Structure

**New — `com.openggf.capture`:**
| File | Responsibility |
|------|----------------|
| `GlReadPixelsGrabber.java` | `VideoFrameGrabber` over `glReadPixels` (raw RGBA, no Java-side flip) |
| `DrainPcmAudioTap.java` | `AudioFrameTap` over `AudioManager.drainCaptureFrame` |
| `FfmpegEncoder.java` | `CaptureEncoder`: phase-1 video pipe + temp s16le audio, phase-2 mux; `findFfmpeg` |

**New — driver/session/CLI:**
| File | Responsibility |
|------|----------------|
| `com/openggf/trace/replay/TraceReplayDriver.java` | UI-agnostic deterministic replay drive (extracted from `TraceSessionLauncher`) |
| `com/openggf/tools/HeadlessGameBoot.java` | Offscreen `GameLoop` + gameplay session bootstrap |
| `com/openggf/tools/TraceCaptureSession.java` | step→render→grab→submit per frame; `isComplete()` |
| `com/openggf/tools/TraceCaptureTool.java` | CLI entry: arg parse, boot, capture loop, teardown |

**Modified:**
| File | Change |
|------|--------|
| `configuration/SonicConfiguration.java` | add `CAPTURE_OUTPUT_DIR`, `CAPTURE_SCALE`, `CAPTURE_FPS`, `CAPTURE_CODEC` |
| `configuration/SonicConfigurationService.java` | `putDefault` for the four keys |
| `configuration/ConfigCatalog.java` | catalog meta for the four keys (a `capture.*` section) |
| `level/LevelRenderer.java` | gate ghost (~L989), game-HUD (~L653), debug-HUD (~L662) sites via `TraceRenderVisibility` |
| `TraceSessionLauncher.java` | delegate its post-bootstrap body + per-frame drive to `TraceReplayDriver` |
| `CONFIGURATION.md`, `CHANGELOG.md` | document capture config + feature |

---

# PHASE A — capture I/O + config (workflow-safe)

## Task 1: Capture config keys (`CAPTURE_*`) + catalog meta

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java`
- Modify: `CONFIGURATION.md`, `CHANGELOG.md`
- Test: `src/test/java/com/openggf/configuration/CaptureConfigDefaultsTest.java`

> Note: a committed `TestConfigCatalog.everyConstantExceptVersionHasMeta` requires every new `SonicConfiguration` constant to have `ConfigCatalog` meta — that is why the catalog edit is part of this task, not optional.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureConfigDefaultsTest {

    @BeforeEach
    void reset() {
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void captureDefaults() {
        SonicConfigurationService c = SonicConfigurationService.getInstance();
        assertEquals("target/trace-videos", c.getString(SonicConfiguration.CAPTURE_OUTPUT_DIR));
        assertEquals(4, c.getInt(SonicConfiguration.CAPTURE_SCALE));
        assertEquals(60, c.getInt(SonicConfiguration.CAPTURE_FPS));
        assertEquals("ffv1", c.getString(SonicConfiguration.CAPTURE_CODEC));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=CaptureConfigDefaultsTest" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL — constants do not exist (compile error).

- [ ] **Step 3: Add the enum constants**

In `SonicConfiguration.java`, after the `TRACE_SHOW_DEBUG_HUD` constant added in Plan 1, add:

```java
	/** Output directory for trace capture videos. */
	CAPTURE_OUTPUT_DIR,
	/** Integer nearest-neighbor upscale factor for trace capture output. */
	CAPTURE_SCALE,
	/** Output frame rate for trace capture. */
	CAPTURE_FPS,
	/** Trace capture video codec (e.g. "ffv1"). */
	CAPTURE_CODEC,
```

- [ ] **Step 4: Add the defaults**

In `SonicConfigurationService.java`, beside the other `putDefault(...)` calls, add:

```java
		putDefault(SonicConfiguration.CAPTURE_OUTPUT_DIR, "target/trace-videos");
		putDefault(SonicConfiguration.CAPTURE_SCALE, 4);
		putDefault(SonicConfiguration.CAPTURE_FPS, 60);
		putDefault(SonicConfiguration.CAPTURE_CODEC, "ffv1");
```

- [ ] **Step 5: Add catalog meta**

In `ConfigCatalog.java`, after the `debug.traceRender` block added in Plan 1's fix, add a capture section (uses the existing `of(section, key, TYPE, desc)` helper; `INT`/`STRING` are already statically imported):

```java
        // capture (trace video capture)
        put(CAPTURE_OUTPUT_DIR, of("capture", "outputDir", STRING,
                "Output directory for trace capture videos"));
        put(CAPTURE_SCALE, of("capture", "scale", INT,
                "Integer nearest-neighbor upscale factor for capture output"));
        put(CAPTURE_FPS, of("capture", "fps", INT, "Output frame rate for trace capture"));
        put(CAPTURE_CODEC, of("capture", "codec", STRING, "Capture video codec (e.g. ffv1)"));
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn "-Dtest=CaptureConfigDefaultsTest,TestConfigCatalog" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS — both green (catalog meta present for all new constants).

- [ ] **Step 7: Document**

Add to `CONFIGURATION.md` (capture section) and a `CHANGELOG.md` bullet:

```markdown
- Added `CAPTURE_OUTPUT_DIR` / `CAPTURE_SCALE` / `CAPTURE_FPS` / `CAPTURE_CODEC` config for trace video capture.
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/SonicConfigurationService.java src/main/java/com/openggf/configuration/ConfigCatalog.java src/test/java/com/openggf/configuration/CaptureConfigDefaultsTest.java CONFIGURATION.md CHANGELOG.md
git commit -m "feat(config): trace capture output config (dir/scale/fps/codec)

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a"
```

---

## Task 2: `FfmpegEncoder` — `findFfmpeg` + command construction (unit-testable parts)

Split the encoder into pure, unit-testable helpers (PATH discovery, command-array construction) plus the process-driving body. Tasks 2 tests the pure helpers with no ffmpeg; Task 4's opt-in smoke test exercises the real process.

**Files:**
- Create: `src/main/java/com/openggf/capture/FfmpegEncoder.java`
- Test: `src/test/java/com/openggf/capture/FfmpegEncoderCommandTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.capture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegEncoderCommandTest {

    @Test
    void phase1CommandHasRawVideoInputAndScaledFfv1Output() {
        List<String> cmd = FfmpegEncoder.phase1Command(
                "ffmpeg", Path.of("/tmp/v.mkv"), 320, 224, 60, 4);
        // raw rgba stdin input
        assertTrue(cmd.contains("rawvideo"));
        assertEquals("rgba", cmd.get(cmd.indexOf("-pix_fmt") + 1));
        assertEquals("320x224", cmd.get(cmd.indexOf("-s") + 1));
        assertEquals("60", cmd.get(cmd.indexOf("-r") + 1));
        assertEquals("pipe:0", cmd.get(cmd.indexOf("-i") + 1));
        // vflip + 4x nearest-neighbor scale
        String vf = cmd.get(cmd.indexOf("-vf") + 1);
        assertTrue(vf.contains("vflip"), vf);
        assertTrue(vf.contains("scale=1280:896:flags=neighbor"), vf);
        // ffv1 video codec, output last
        assertEquals("ffv1", cmd.get(cmd.indexOf("-c:v") + 1));
        assertTrue(cmd.get(cmd.size() - 1).endsWith("v.mkv"));
    }

    @Test
    void phase2MuxCopiesVideoAndEncodesFlac() {
        List<String> cmd = FfmpegEncoder.phase2MuxCommand(
                "ffmpeg", Path.of("/tmp/v.mkv"), Path.of("/tmp/a.raw"),
                48000, Path.of("/out/final.mkv"));
        assertEquals("copy", cmd.get(cmd.indexOf("-c:v") + 1));
        assertEquals("flac", cmd.get(cmd.indexOf("-c:a") + 1));
        // raw s16le audio input declared
        assertTrue(cmd.contains("s16le"));
        assertEquals("48000", cmd.get(cmd.indexOf("-ar") + 1));
        assertEquals("2", cmd.get(cmd.indexOf("-ac") + 1));
        assertTrue(cmd.get(cmd.size() - 1).endsWith("final.mkv"));
    }

    @Test
    void findFfmpegReturnsEmptyForBogusPath() {
        assertTrue(FfmpegEncoder.findFfmpegOnPath("").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=FfmpegEncoderCommandTest" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL — `FfmpegEncoder` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.openggf.capture;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link CaptureEncoder} that streams raw RGBA frames to ffmpeg and produces a
 * lossless MKV. Two phases: (1) pipe RGBA -> ffmpeg FFV1 video, append PCM to a
 * temp s16le file; (2) mux video (copy) + FLAC audio into the final file.
 *
 * <p>Y-flip and integer upscale are done by ffmpeg ({@code -vf vflip,scale=...})
 * so the producer hot path is a raw byte write.
 */
public final class FfmpegEncoder implements CaptureEncoder {

    private final String ffmpeg;
    private final int scale;
    private final int sampleRate0;            // captured for phase 2
    private Path finalOut;
    private Path tempVideo;
    private Path tempAudio;
    private Process videoProc;
    private OutputStream videoStdin;
    private OutputStream audioOut;
    private Thread stderrDrain;
    private int sampleRate;

    /** @param ffmpeg path/name of the ffmpeg executable; @param scale integer upscale factor */
    public FfmpegEncoder(String ffmpeg, int scale) {
        this.ffmpeg = ffmpeg;
        this.scale = Math.max(1, scale);
        this.sampleRate0 = 0;
    }

    // ---- pure helpers (unit-tested) ----

    static List<String> phase1Command(String ffmpeg, Path videoOut,
                                      int width, int height, int fps, int scale) {
        List<String> c = new ArrayList<>();
        c.add(ffmpeg);
        c.add("-y");
        c.add("-f"); c.add("rawvideo");
        c.add("-pix_fmt"); c.add("rgba");
        c.add("-s"); c.add(width + "x" + height);
        c.add("-r"); c.add(String.valueOf(fps));
        c.add("-i"); c.add("pipe:0");
        c.add("-vf"); c.add("vflip,scale=" + (width * scale) + ":" + (height * scale)
                + ":flags=neighbor");
        c.add("-c:v"); c.add("ffv1");
        c.add("-an");
        c.add(videoOut.toAbsolutePath().toString());
        return c;
    }

    static List<String> phase2MuxCommand(String ffmpeg, Path videoMkv, Path audioRaw,
                                         int sampleRate, Path finalOut) {
        List<String> c = new ArrayList<>();
        c.add(ffmpeg);
        c.add("-y");
        c.add("-i"); c.add(videoMkv.toAbsolutePath().toString());
        c.add("-f"); c.add("s16le");
        c.add("-ar"); c.add(String.valueOf(sampleRate));
        c.add("-ac"); c.add("2");
        c.add("-i"); c.add(audioRaw.toAbsolutePath().toString());
        c.add("-c:v"); c.add("copy");
        c.add("-c:a"); c.add("flac");
        c.add(finalOut.toAbsolutePath().toString());
        return c;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Search a PATH-like string for the ffmpeg executable. */
    static Optional<Path> findFfmpegOnPath(String pathEnv) {
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        String exe = isWindows() ? "ffmpeg.exe" : "ffmpeg";
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Paths.get(dir, exe);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static Optional<Path> findFfmpeg() {
        return findFfmpegOnPath(System.getenv("PATH"));
    }

    // ---- CaptureEncoder lifecycle (exercised by the Task 4 smoke test) ----

    @Override
    public void open(Path output, int width, int height, int fps, int sampleRate)
            throws CaptureException {
        this.finalOut = output;
        this.sampleRate = sampleRate;
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            this.tempVideo = Files.createTempFile("trace-capture-", ".video.mkv");
            this.tempAudio = Files.createTempFile("trace-capture-", ".audio.raw");
            ProcessBuilder pb = new ProcessBuilder(
                    phase1Command(ffmpeg, tempVideo, width, height, fps, scale));
            pb.redirectErrorStream(false);
            this.videoProc = pb.start();
            this.videoStdin = videoProc.getOutputStream();
            this.audioOut = Files.newOutputStream(tempAudio);
            this.stderrDrain = drainAsync(videoProc);
        } catch (IOException e) {
            abort();
            throw new CaptureException("failed to start ffmpeg video process", e);
        }
    }

    @Override
    public void encode(CapturedFrame frame) throws CaptureException {
        try {
            videoStdin.write(frame.rgba());
            // s16le little-endian PCM for this frame
            short[] pcm = frame.pcm();
            int n = frame.sampleCount() * 2;
            byte[] bytes = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                short s = pcm[i];
                bytes[i * 2] = (byte) (s & 0xFF);
                bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            audioOut.write(bytes);
        } catch (IOException e) {
            abort();
            throw new CaptureException("ffmpeg write failed", e);
        }
    }

    @Override
    public Path finish() throws CaptureException {
        try {
            videoStdin.close();              // EOF -> ffmpeg finalizes video
            int vexit = videoProc.waitFor();
            if (stderrDrain != null) stderrDrain.join(2000);
            if (vexit != 0) throw new CaptureException("ffmpeg video exited " + vexit);
            audioOut.close();
            // phase 2 mux
            ProcessBuilder pb = new ProcessBuilder(
                    phase2MuxCommand(ffmpeg, tempVideo, tempAudio, sampleRate, finalOut));
            pb.redirectErrorStream(false);
            Process mux = pb.start();
            Thread muxDrain = drainAsync(mux);
            int mexit = mux.waitFor();
            muxDrain.join(2000);
            if (mexit != 0) throw new CaptureException("ffmpeg mux exited " + mexit);
            return finalOut;
        } catch (IOException e) {
            throw new CaptureException("ffmpeg finish failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException("interrupted during ffmpeg finish", e);
        } finally {
            deleteQuietly(tempVideo);
            deleteQuietly(tempAudio);
        }
    }

    @Override
    public void abort() {
        try { if (videoStdin != null) videoStdin.close(); } catch (IOException ignored) { }
        try { if (audioOut != null) audioOut.close(); } catch (IOException ignored) { }
        if (videoProc != null) videoProc.destroyForcibly();
        deleteQuietly(tempVideo);
        deleteQuietly(tempAudio);
    }

    private static Thread drainAsync(Process p) {
        Thread t = new Thread(() -> {
            try (var in = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) >= 0) {
                    // discard ffmpeg diagnostics; just keep the pipe drained
                }
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stderr-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=FfmpegEncoderCommandTest" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/capture/FfmpegEncoder.java src/test/java/com/openggf/capture/FfmpegEncoderCommandTest.java
git commit -m "feat(capture): FfmpegEncoder (two-phase FFV1+FLAC mux, PATH discovery)

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 3: `DrainPcmAudioTap`

**Files:**
- Create: `src/main/java/com/openggf/capture/DrainPcmAudioTap.java`
- Test: `src/test/java/com/openggf/capture/DrainPcmAudioTapTest.java`

`AudioFrameTap.drain(short[])` returns the stereo-frame count; this adapter delegates to `AudioManager.drainCaptureFrame` (Plan 1, Task 8).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.capture;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures.RecordingAudioBackend;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrainPcmAudioTapTest {

    @Test
    void drainsExactlyOneFramesPcmFromCaptureMode() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());
        audio.beginCaptureMode(48000, 60);
        audio.advanceGameplayFrameAudio();

        DrainPcmAudioTap tap = new DrainPcmAudioTap(audio);
        short[] target = new short[2048];
        assertEquals(800, tap.drain(target), "one 48k/60 frame = 800 stereo samples");

        audio.endCaptureMode();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=DrainPcmAudioTapTest" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL — `DrainPcmAudioTap` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.openggf.capture;

import com.openggf.audio.AudioManager;

/** {@link AudioFrameTap} over {@link AudioManager#drainCaptureFrame(short[])}. */
public final class DrainPcmAudioTap implements AudioFrameTap {

    private final AudioManager audio;

    public DrainPcmAudioTap(AudioManager audio) {
        this.audio = audio;
    }

    @Override
    public int drain(short[] target) {
        return audio.drainCaptureFrame(target);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=DrainPcmAudioTapTest" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/capture/DrainPcmAudioTap.java src/test/java/com/openggf/capture/DrainPcmAudioTapTest.java
git commit -m "feat(capture): DrainPcmAudioTap over AudioManager capture-mode

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Task 4: `FfmpegEncoder` end-to-end smoke test (opt-in, ffmpeg-gated)

**Files:**
- Test: `src/test/java/com/openggf/capture/FfmpegEncoderSmokeTest.java`

Verifies a real ffmpeg run end-to-end through `EncoderSink` + `CapturedFrame`. **Skips when ffmpeg is absent** (no JUnit-4 `@Assume`; early return).

- [ ] **Step 1: Write the test**

```java
package com.openggf.capture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegEncoderSmokeTest {

    @Test
    void encodesTenFramesToANonEmptyMkv() throws Exception {
        Optional<Path> ffmpeg = FfmpegEncoder.findFfmpeg();
        if (ffmpeg.isEmpty()) {
            System.out.println("SKIPPED FfmpegEncoderSmokeTest: ffmpeg not on PATH");
            return;
        }
        Path out = Files.createTempFile("trace-capture-smoke-", ".mkv");
        Files.deleteIfExists(out);

        FfmpegEncoder enc = new FfmpegEncoder(ffmpeg.get().toString(), 2);
        EncoderSink sink = new EncoderSink(enc, BackpressurePolicy.BLOCK, 8);
        int w = 32, h = 24, fps = 30, sr = 48000, perFrame = sr / fps; // 1600
        sink.open(out, w, h, fps, sr);
        for (int i = 0; i < 10; i++) {
            byte[] rgba = new byte[w * h * 4];
            java.util.Arrays.fill(rgba, (byte) (i * 20)); // varying grey
            short[] pcm = new short[perFrame * 2];        // silence
            sink.submit(new CapturedFrame(rgba, w, h, pcm, perFrame, i));
        }
        Path result = sink.stop();

        assertTrue(Files.exists(result), "output mkv exists");
        assertTrue(Files.size(result) > 0, "output mkv non-empty");
        Files.deleteIfExists(result);
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn "-Dtest=FfmpegEncoderSmokeTest" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS — either a real MKV is produced, or the test prints SKIPPED (ffmpeg absent) and passes.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/capture/FfmpegEncoderSmokeTest.java
git commit -m "test(capture): opt-in ffmpeg end-to-end smoke test

Changelog: n/a: test only
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

# PHASE B — engine integration + CLI (review-gated; needs ROM + GL + ffmpeg)

> Each Phase B task lists **verification preconditions**. A worker without those cannot run the integration check — implement to the spec, compile, and hand off for a checkpoint run on a machine with ROM + GL + ffmpeg.

## Task 5: Extract `TraceReplayDriver` from `TraceSessionLauncher`

**This is a refactor of fragile shared code — do it carefully and keep `TraceSessionLauncher` behavior identical.**

**Files:**
- Create: `src/main/java/com/openggf/trace/replay/TraceReplayDriver.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java` (delegate to the driver)

**Verification:** this is a pure behavior-preserving **extraction** refactor, so the correct test is that the **existing** trace-replay + headless guard suites stay green — do **not** fabricate a new call-order test. Verification precondition: ROM present for the headless guards (they skip without it; if skipped, defer to a checkpoint run on a ROM machine).

**Source of truth:** `TraceSessionLauncher.finishLaunchAfterGameBootstrap()` lines 156–275 (read them). Per the research brief, the **reusable** core (move into `TraceReplayDriver`) is, in order:
1. `TraceReplaySessionBootstrap.resetLevelSubsystemsForReplay()`
2. `GameplayTeamBootstrap.registerActiveTeam(module, sprites, config)` — **after reset, before zone load**
3. `GameServices.level().loadZoneAndAct(zone, act)`; `loop.setGameMode(GameMode.LEVEL)`
4. consume title-card requests (`consumeTitleCardRequest`, `consumeInLevelTitleCardRequest`, `titleCardProvider.reset()` if active) — **before first step**
5. `playback.startSession(movie, recordingStartFrameForTraceReplay(trace))`
6. `applyStartPositionAndGroundSnap(trace, fixture)` — **before** `applyBootstrap`
7. `applyBootstrap(trace, fixture, -1)` → `BootstrapResult`
8. compute `initialCursor` / `previousDriveFrame`; `alignFrameCountersForReplayStart(previous, first)` — **before** comparator
9. construct `LiveTraceComparator(trace, ToleranceConfig.DEFAULT, initialCursor, spriteSupplier, ...)`; `playback.setFrameObserver(comparator)`

**UI-specific (stays in `TraceSessionLauncher`, NOT moved):** fade/teardown, completion-hold, `TraceCameraFocusController`, `TraceHudOverlay`, rewind controllers, Esc handling, `activeSession` static.

Proposed `TraceReplayDriver` surface (constructor injects what the live launcher and headless session both provide):

```java
public final class TraceReplayDriver {
    public TraceReplayDriver(TraceData trace, Bk2Movie movie, TraceReplayFixture fixture,
                             GameLoop loop, java.util.function.Supplier<AbstractPlayableSprite> spriteSupplier);
    /** Runs the reusable bootstrap steps 1–9 in order. Throws on failure (caller restores config). */
    public void start(int zone, int act) throws Exception;
    public LiveTraceComparator comparator();
    public boolean isComplete();
    public TraceFrame currentVisualFrame();
}
```

- [ ] **Step 1: Create `TraceReplayDriver`** by moving the reusable body (steps 1–9 above) out of `finishLaunchAfterGameBootstrap()` **verbatim**, preserving every ordering comment, and exposing the surface above. The fragile invariants (team-after-reset-before-zone-load, title-card-before-first-step, startPosition-before-applyBootstrap, align-counters-before-comparator, cursor sync) live in this one method now.

- [ ] **Step 2: Rewire `TraceSessionLauncher.finishLaunchAfterGameBootstrap()`** to build a `TraceReplayDriver`, call `driver.start(zone, act)`, then keep its own UI wiring (camera focus controller, HUD overlay, rewind, `activeSession`) reading `driver.comparator()`. Keep the try/catch + config-restore semantics identical.

- [ ] **Step 3: Compile.** `mvn -q -DskipTests compile` → BUILD SUCCESS.

- [ ] **Step 4: Verify no behavior change against the EXISTING suites** (precondition: ROM present). Run the trace-replay + headless guards, e.g.:
  `mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestS3kAizTraceReplay" test`
  Expected: PASS (identical to pre-refactor). If ROM is absent these skip — record that and defer to a checkpoint run on a ROM machine before merging.

- [ ] **Step 5: Commit** (stage `TraceReplayDriver.java` + `TraceSessionLauncher.java`; `Changelog: updated`, full trailer block).

---

## Task 6: `GlReadPixelsGrabber`

**Files:**
- Create: `src/main/java/com/openggf/capture/GlReadPixelsGrabber.java`
- Test: `src/test/java/com/openggf/capture/GlReadPixelsGrabberTest.java`

**Verification precondition:** needs a live GL context to fully test; the unit test below covers the non-GL logic (dimension reporting). Full pixel verification happens in the Task 10 integration capture.

- [ ] **Step 1: Implement**

```java
package com.openggf.capture;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * {@link VideoFrameGrabber} that reads the back buffer via {@code glReadPixels}.
 * Returns RGBA bytes in OpenGL bottom-up order (no Java-side flip) — ffmpeg's
 * {@code vflip} corrects orientation. MUST be called on the GL thread.
 */
public final class GlReadPixelsGrabber implements VideoFrameGrabber {

    private final int width;
    private final int height;

    public GlReadPixelsGrabber(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }

    @Override
    public byte[] grab() {
        ByteBuffer buf = MemoryUtil.memAlloc(width * height * 4);
        try {
            glReadBuffer(GL_BACK);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            byte[] out = new byte[width * height * 4];
            buf.get(out);            // tight copy, bottom-up as GL provides
            return out;
        } finally {
            MemoryUtil.memFree(buf);
        }
    }
}
```

- [ ] **Step 2: Write the non-GL unit test**

```java
package com.openggf.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlReadPixelsGrabberTest {
    @Test
    void reportsConfiguredDimensions() {
        GlReadPixelsGrabber g = new GlReadPixelsGrabber(320, 224);
        assertEquals(320, g.width());
        assertEquals(224, g.height());
    }
}
```

- [ ] **Step 3: Run it.** `mvn "-Dtest=GlReadPixelsGrabberTest" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test` → PASS.

- [ ] **Step 4: Commit** (`Changelog: updated`, full trailer block).

---

## Task 7: Gate ghost / game-HUD / debug-HUD render sites with `TraceRenderVisibility`

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelRenderer.java`

**Verification precondition:** needs ROM + GL for visual confirmation; logic is a guard around existing calls. This affects **both** live Trace Test Mode and capture (shared renderer) — exactly the spec intent.

Per the research brief, `LevelRenderer.drawWithRenderOptions()` is the orchestrator. Resolve a `TraceRenderVisibility` once per render (from `SonicConfigurationService`) and gate three sites:

- [ ] **Step 1: Gate the ghost site (~L989, `renderTraceGhostsForLayer`)** — wrap the `traceSession.renderGhostsForLayer(bucket, highPriority)` call so it only fires when `visibility.showGhosts()`.
- [ ] **Step 2: Gate the game-HUD site (~L651–653)** — add `&& visibility.showGameHud()` to the existing `HudRenderManager.draw(...)` guard.
- [ ] **Step 3: Gate the debug-HUD site (~L661–663)** — add `&& visibility.showDebugHud()` to the existing `DebugRenderer` guard.
- [ ] **Step 4: Resolve visibility once** near the top of `drawWithRenderOptions()`:
  `TraceRenderVisibility vis = TraceRenderVisibility.fromConfig(SonicConfigurationService.getInstance());`
  (Only relevant during trace replay/capture; for normal gameplay these flags default to ghosts-off-by-absence — confirm the ghost gate also checks `traceSession != null` as today so non-trace play is unaffected.)
- [ ] **Step 5: Compile + run a render-touching guard** (precondition: ROM/GL): existing visual/headless tests must stay green. `mvn -q -DskipTests compile` must pass regardless. Defer visual confirmation to Task 10.
- [ ] **Step 6: Commit** (`Changelog: updated`, full trailer block; note this also affects live Trace Test Mode visibility).

---

## Task 8: `HeadlessGameBoot` — offscreen GameLoop + gameplay session

**Files:**
- Create: `src/main/java/com/openggf/tools/HeadlessGameBoot.java`

**Verification precondition:** needs ROM + GL; verified via Task 10. Compile-checkable standalone.

Per the research brief, implement a helper that performs the exact boot sequence (no `Engine`, no master-title):

```java
public final class HeadlessGameBoot implements AutoCloseable {
    public HeadlessGameBoot(int width, int height);           // creates hidden GLFW window + GL
    public GameLoop boot(Path romPath, int zone, int act) throws IOException; // full sequence
    @Override public void close();                            // GL + GLFW teardown
}
```

`boot(...)` must, in order (research Surface 1):
1. `EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap())`.
2. hidden GLFW window (`GLFW_VISIBLE=FALSE`) + `GL.createCapabilities()` + `graphicsManager.init(Engine.RESOURCES_SHADERS_PIXEL_SHADER_GLSL)` + viewport/projection (mirror `VisualReferenceGenerator.initialize` lines 84–167).
3. open ROM via `RomManager` (load `romPath`), `RomDetectionService.detectAndCreateModule(rom)`.
4. `GameplayModeContext mode = SessionManager.openGameplaySession(module)`.
5. `GameplaySessionFactory.attachManagers(mode, services)`; assert `mode.isGameplayRuntimeReady()`.
6. `GameLoop loop = new GameLoop(services); loop.setGameplayMode(mode); loop.setInputHandler(new InputHandler()); loop.setGameMode(GameMode.LEVEL);`
7. `GameModuleRegistry.setCurrent(module)`.
8. `GameServices.level().loadZoneAndAct(zone, act)`.
9. `GameplayTeamBootstrap.registerActiveTeam(module, GameServices.sprites(), config)`; `GameServices.camera().setFocusedSprite(mainSprite)`.

- [ ] **Step 1: Implement per the sequence above** (read `VisualReferenceGenerator` for the GL setup and `Engine.initializeGame` for the team/camera binding lines).
- [ ] **Step 2: Compile.** `mvn -q -DskipTests compile` → BUILD SUCCESS.
- [ ] **Step 3: Commit** (`Changelog: updated`, full trailer block).

---

## Task 9: `TraceCaptureSession` — step → render → grab → submit

**Files:**
- Create: `src/main/java/com/openggf/tools/TraceCaptureSession.java`

**Verification precondition:** ROM + GL + ffmpeg; verified by Task 10.

Ties together (per spec §3): given a booted `GameLoop`, a `TraceReplayDriver`, a `VideoFrameGrabber`, an `AudioFrameTap`, and a `CaptureRecorder`:

```java
public final class TraceCaptureSession {
    public TraceCaptureSession(GameLoop loop, TraceReplayDriver driver,
                               VideoFrameGrabber grabber, AudioFrameTap audioTap,
                               CaptureRecorder recorder, int fps);
    /** Begin audio capture mode + recorder. */
    public void start(int width, int height, int sampleRate) throws CaptureException;
    /** Advance one frame: GameLoop.step() (audio advances inside), render scene+ghosts+HUD,
     *  grab RGBA, drain PCM, submit a CapturedFrame. Returns false when the trace is complete. */
    public boolean stepAndCapture() throws CaptureException;
    public Path finish() throws CaptureException;   // recorder.stop()
}
```

Loop body per frame:
1. if `driver.isComplete()` return false.
2. `GameLoop.step()` (advances audio via `advanceGameplayAudioFrameForTick`).
3. trigger the LEVEL render (the same path Engine.draw uses — invoke `levelManager.drawWithSpritePriority(...)` / the render entry, then `graphicsManager.flush()` + `glFinish()`).
4. `byte[] rgba = grabber.grab();`
5. `short[] pcm = reusableBuf; int n = audioTap.drain(pcm);`
6. `recorder.submit(new CapturedFrame(rgba, w, h, pcm, n, frameIndex++));`
7. return true.

- [ ] **Step 1: Implement** (read `Engine.draw()`/`Engine.display()` ~L1346 for the exact render entry to reuse; `start()` calls `AudioManager.getInstance().beginCaptureMode(sampleRate, fps)` then `recorder.start(...)`; `finish()` calls `recorder.stop()` then `AudioManager.endCaptureMode()`).
- [ ] **Step 2: Compile.** `mvn -q -DskipTests compile` → BUILD SUCCESS.
- [ ] **Step 3: Commit** (`Changelog: updated`, full trailer block).

---

## Task 10: `TraceCaptureTool` CLI + end-to-end integration

**Files:**
- Create: `src/main/java/com/openggf/tools/TraceCaptureTool.java`
- Test: `src/test/java/com/openggf/tools/TraceCaptureToolArgsTest.java`
- Modify: `CHANGELOG.md`, `CONFIGURATION.md` (document the tool + Maven exec invocation)

**Verification precondition for the end-to-end run:** ROM + GL + ffmpeg + at least one trace in `TRACE_CATALOG_DIR`. The arg-parsing test is pure.

CLI per research Surface 6: parse `--trace <id|dir>`, `--out-dir`, `--scale`, `--fps`, `--codec` (defaults from `CAPTURE_*` config), resolve the trace via `TraceCatalog.scan(TRACE_CATALOG_DIR)` (by index or directory name) or treat `--trace` as a path, `TraceData.load(dir)` + `new Bk2MovieLoader().load(bk2Path)`, boot via `HeadlessGameBoot`, build `TraceReplayDriver` + `TraceCaptureSession` with a `CaptureRecorder` wrapping `FfmpegEncoder`, run `while (session.stepAndCapture()) {}`, `session.finish()`, teardown. Timestamp string passed to `CaptureRecorder` is formatted in `main` (not inside the recorder) so the recorder stays deterministic.

- [ ] **Step 1: Write the pure arg-parsing test**

```java
package com.openggf.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceCaptureToolArgsTest {
    @Test
    void parsesArgsWithConfigDefaults() {
        TraceCaptureTool.Args a = TraceCaptureTool.Args.parse(
                new String[]{"--trace", "aiz1", "--scale", "2", "--fps", "30"});
        assertEquals("aiz1", a.trace());
        assertEquals(2, a.scale());
        assertEquals(30, a.fps());
        assertEquals("ffv1", a.codec());            // default from config
        assertNotNull(a.outDir());                  // default from config
    }
}
```

- [ ] **Step 2: Run it → RED.** `mvn "-Dtest=TraceCaptureToolArgsTest" "-Dmse=off" "-Dsurefire.failIfNoSpecifiedTests=false" test` → FAIL.
- [ ] **Step 3: Implement `TraceCaptureTool`** (with a static `Args` record + `parse(String[])` reading `CAPTURE_*` defaults; `main()` mirroring `VisualReferenceGenerator.main`: `initialize → run → cleanup`, `catch → System.exit(1)`).
- [ ] **Step 4: Run the arg test → GREEN.**
- [ ] **Step 5: End-to-end checkpoint run (precondition: ROM+GL+ffmpeg+trace).** Run:
  `mvn exec:java "-Dexec.mainClass=com.openggf.tools.TraceCaptureTool" "-Dexec.args=--trace <id> --out-dir target/trace-videos"`
  Expected: writes `target/trace-videos/capture-<id>-<UTC>.mkv`; open it and confirm video + audio + (if enabled) desync ghosts. Record the result in the commit/PR.
- [ ] **Step 6: Document + commit** (`CHANGELOG.md` feature entry, `CONFIGURATION.md` invocation; `Changelog: updated`, `Configuration-Docs: updated`, full trailer block).

---

## Self-Review checklist (run before handing off)

- **Spec coverage:** §2.3 boot → Task 8; TraceReplayDriver → Task 5; §3 data flow/session → Task 9; render gates §6.1 → Task 7; §7 ffmpeg → Tasks 2/4; grabber/tap → Tasks 3/6; §6.2 capture config → Task 1; CLI §6.2 → Task 10. ✓
- **Verifiability:** Tasks 1–4 are workflow-safe (pure + opt-in ffmpeg smoke). Tasks 5–10 marked with ROM/GL/ffmpeg preconditions and review checkpoints. ✓
- **Type consistency:** `CaptureEncoder.open(Path,…)`, `FrameSink`/`EncoderSink`/`CaptureRecorder`, `AudioFrameTap.drain`, `VideoFrameGrabber.grab`, `AudioManager.beginCaptureMode/drainCaptureFrame/endCaptureMode`, `TraceRenderVisibility.fromConfig/showGhosts/showGameHud/showDebugHud` all match Plan 1's committed signatures. ✓
