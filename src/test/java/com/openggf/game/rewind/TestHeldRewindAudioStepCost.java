package com.openggf.game.rewind;

import com.openggf.audio.AudioBenchmarkMemoryProbe;
import com.openggf.audio.AudioManager;
import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static com.openggf.tests.RomTestUtils.ensureRomAvailable;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement harness, not a regression test: quantifies the per-step cost of
 * held-rewind backward stepping with the REAL synth-stack audio backend
 * ({@link HeadlessSmpsAudioBackend} + the unified presentation producer and its
 * offline capture lease), which the NullAudioBackend probes cannot measure (no
 * synthesis, no SmpsDriver snapshot/restore cost).
 *
 * <p>Scenario per repetition: real backend + offline capture lease, ROM-loaded EHZ
 * music started on frame 1, 120 forward frames (each synthesizing and
 * draining one 60 fps capture frame), then reverse audio presentation, then
 * 60 timed {@code stepBackward()} calls (wall ns per step + ThreadMXBean
 * allocated bytes over the block), then the timed release path
 * ({@code commitDeferredAudioRestore} — looked up reflectively so the same
 * file compiles on the pre-deferral baseline a737d65a9, where backward steps
 * restore eagerly and there is nothing to commit — followed by
 * {@code afterRewindRestore}). Results print as a parseable
 * {@code HELD_REWIND_STEP_COST} line; assertions are sanity-only so the
 * harness never fails on slow machines. ROM-gated.
 */
@Tag("performance-measurement")
class TestHeldRewindAudioStepCost {

    private static final int MUSIC_EHZ = 0x81;
    private static final int FORWARD_FRAMES = 120;
    private static final int BACKWARD_STEPS = 60;
    private static final int KEYFRAME_INTERVAL = 4;
    private static final int REPETITIONS = 3;
    private static final int CAPTURE_FPS = 60;

    private static Rom rom;

    @BeforeAll
    static void setUpClass() {
        TestEnvironment.resetAll();
        File romFile = ensureRomAvailable();
        if (romFile == null) {
            return;
        }
        Rom candidate = new Rom();
        if (candidate.open(romFile.getAbsolutePath())) {
            rom = candidate;
        }
    }

    @AfterAll
    static void tearDownClass() {
        AudioManager.getInstance().resetState();
        SessionManager.clear();
    }

    @Test
    void heldRewindBackwardStepCostWithRealBackend() {
        assumeTrue(rom != null, "ROM not available, skipping held-rewind step cost measurement");

        RepetitionResult[] results = new RepetitionResult[REPETITIONS];
        for (int i = 0; i < REPETITIONS; i++) {
            results[i] = runRepetition();
        }

        double[] perStepMedianUs = new double[REPETITIONS];
        double[] perStepAllocKb = new double[REPETITIONS];
        double[] releaseUs = new double[REPETITIONS];
        boolean allocSupported = true;
        for (int i = 0; i < REPETITIONS; i++) {
            perStepMedianUs[i] = results[i].perStepMedianUs();
            perStepAllocKb[i] = results[i].perStepAllocKb();
            releaseUs[i] = results[i].releaseUs();
            allocSupported &= results[i].allocSupported();
        }

        System.out.printf(Locale.ROOT,
                "HELD_REWIND_STEP_COST perStepMedianUs=%.1f perStepAllocKb=%.1f releaseCommitUs=%.1f "
                        + "allocSupported=%b reps=%d stepsPerRep=%d forwardFrames=%d keyframeInterval=%d "
                        + "perRepStepUs=%s perRepAllocKb=%s perRepReleaseUs=%s%n",
                median(perStepMedianUs.clone()), median(perStepAllocKb.clone()), median(releaseUs.clone()),
                allocSupported, REPETITIONS, BACKWARD_STEPS, FORWARD_FRAMES, KEYFRAME_INTERVAL,
                formatList(perStepMedianUs), formatList(perStepAllocKb), formatList(releaseUs));

        assertTrue(median(perStepMedianUs.clone()) > 0, "backward steps must consume wall time");
    }

    /** One full setup + measurement pass on a fresh backend/controller. */
    private RepetitionResult runRepetition() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setAudioProfile(new Sonic2AudioProfile());
        audio.setRom(rom);
        // Backend BEFORE beginCaptureMode: setBackend rebuilds the
        // presentation producer, which would otherwise drop the offline
        // capture lease (mirrors the TraceCaptureSession boot order).
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        audio.setBackend(new HeadlessSmpsAudioBackend(config, PerformanceProfiler.getInstance()));
        assertTrue(audio.getBackend() instanceof HeadlessSmpsAudioBackend,
                "real headless SMPS backend must survive init (setBackend silently falls back to Null on failure)");
        int sampleRate = audio.outputSampleRate();
        audio.beginCaptureMode(sampleRate, CAPTURE_FPS);
        try {
            short[] drainScratch = new short[(sampleRate / CAPTURE_FPS + 2) * 2];
            boolean[] audibleForwardPcm = new boolean[1];
            EngineStepper stepper = in -> {
                if (in.frameIndex() == 1) {
                    audio.playMusic(MUSIC_EHZ);
                }
                // Mirror the per-frame capture pump on live forward frames
                // only; rewind replay re-steps must stay silent (commands are
                // suppressed and presentation reads PCM history instead).
                if (!audio.isRewindReplaySuppressed()) {
                    audio.presentFrame(PresentationMode.FORWARD);
                    int frames = audio.drainCaptureFrame(drainScratch);
                    for (int sample = 0; sample < frames * 2; sample++) {
                        if (drainScratch[sample] != 0) {
                            audibleForwardPcm[0] = true;
                            break;
                        }
                    }
                }
                return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
            };
            RewindController controller = new RewindController(
                    new RewindRegistry(),
                    new InMemoryKeyframeStore(),
                    new FixedFrameInputSource(FORWARD_FRAMES + 8),
                    stepper,
                    KEYFRAME_INTERVAL,
                    audio);

            for (int i = 0; i < FORWARD_FRAMES; i++) {
                controller.step();
            }
            assertTrue(controller.currentFrame() == FORWARD_FRAMES, "forward play must reach frame " + FORWARD_FRAMES);
            assertNotNull(audio.captureLogicalSnapshot().presentation().activeMusic(),
                    "music voice must own the music slot in the presentation snapshot");
            // Occupying the music slot only proves a command resolved; the
            // harness measures SmpsDriver snapshot/restore cost, so require
            // audible PCM out of the forward drains (real synthesis engaged).
            assertTrue(audibleForwardPcm[0],
                    "forward capture drains must carry non-silent PCM (real synthesis engaged)");

            audio.beginReverseAudioPresentation();

            long[] stepNanos = new long[BACKWARD_STEPS];
            AudioBenchmarkMemoryProbe probe = AudioBenchmarkMemoryProbe.create();
            AudioBenchmarkMemoryProbe.RunResult block = probe.measureTimedRun(() -> {
                for (int i = 0; i < BACKWARD_STEPS; i++) {
                    long start = System.nanoTime();
                    boolean stepped = controller.stepBackward();
                    stepNanos[i] = System.nanoTime() - start;
                    assertTrue(stepped, "stepBackward must succeed at step " + i);
                }
            });

            long releaseStart = System.nanoTime();
            commitDeferredAudioRestoreIfSupported(controller);
            audio.afterRewindRestore(controller.currentFrame(),
                    AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);
            long releaseNanos = System.nanoTime() - releaseStart;

            double[] stepUs = new double[BACKWARD_STEPS];
            for (int i = 0; i < BACKWARD_STEPS; i++) {
                stepUs[i] = stepNanos[i] / 1_000.0;
            }
            double perStepAllocKb = block.allocatedBytesSupported()
                    ? block.allocatedBytes() / 1024.0 / BACKWARD_STEPS
                    : -1;
            return new RepetitionResult(
                    median(stepUs),
                    perStepAllocKb,
                    releaseNanos / 1_000.0,
                    block.allocatedBytesSupported());
        } finally {
            audio.endReverseAudioPresentation();
            audio.endCaptureMode();
            audio.resetState();
        }
    }

    /**
     * HEAD-era release commit. On the pre-deferral baseline the method does
     * not exist (every backward step already restored eagerly), so the lookup
     * degrades to a no-op and the release timing measures just the
     * presentation cleanup — the apples-to-apples cost lives in the per-step
     * numbers either way.
     */
    private static void commitDeferredAudioRestoreIfSupported(RewindController controller) {
        try {
            Method commit = RewindController.class.getMethod("commitDeferredAudioRestore");
            commit.invoke(controller);
        } catch (NoSuchMethodException e) {
            // Baseline era: nothing to commit.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("commitDeferredAudioRestore invocation failed", e);
        }
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        return values.length % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2.0;
    }

    private static String formatList(double[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format(Locale.ROOT, "%.1f", values[i]));
        }
        return sb.append("]").toString();
    }

    private record RepetitionResult(
            double perStepMedianUs,
            double perStepAllocKb,
            double releaseUs,
            boolean allocSupported) {
    }

    private static final class FixedFrameInputSource implements InputSource {
        private final int frames;

        FixedFrameInputSource(int frames) {
            this.frames = frames;
        }

        @Override
        public int frameCount() {
            return frames;
        }

        @Override
        public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "measurement");
        }
    }
}
