package com.openggf.capture;

import com.openggf.tests.TestTempFiles;
import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.DebugFailAfterFramesAudioHandle;
import com.openggf.audio.runtime.AudioFrameClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LiveCaptureControllerTest {
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach void shutdown() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test void startCaptureAndAsyncStopReverseResourceOrder() throws Exception {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        assertEquals(LiveCaptureController.State.ACTIVE, c.state());
        assertTrue(c.indicatorVisible());
        c.capturePresentedFrame(h.viewport);
        c.capturePresentedFrame(h.viewport);
        assertEquals(List.of(0L, 1L), h.recorder.indexes);
        c.requestStop(LiveCaptureController.StopReason.USER);
        awaitNotStopping(c);
        assertEquals(LiveCaptureController.State.INACTIVE, c.state());
        assertEquals(List.of("audio-close", "recorder-stop"), h.events);
        assertFalse(c.indicatorVisible());
    }

    @Test void deferredReadbackPairsAudioAndFlushesLastFrameOnCaller() throws Exception {
        Harness h = new Harness();
        h.deferred = true;
        h.sampleRate = 32_000; // alternating packet lengths expose off-by-one audio pairing
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);
        assertTrue(h.recorder.frames.isEmpty());
        c.capturePresentedFrame(h.viewport);
        c.capturePresentedFrame(h.viewport);
        assertEquals(List.of(0L, 1L), h.recorder.indexes);
        c.requestStop(LiveCaptureController.StopReason.USER);
        assertSame(Thread.currentThread(), h.grabberCloseThread);
        awaitNotStopping(c);
        assertEquals(List.of(0L, 1L, 2L), h.recorder.indexes);
        AudioFrameClock expected = new AudioFrameClock(32_000, 60);
        for (int i = 0; i < 3; i++) {
            CapturedFrame frame = h.recorder.frames.get(i);
            assertEquals(i + 1, frame.rgba()[0]);
            assertEquals(expected.samplesForNextFrame(), frame.sampleCount());
        }
        assertEquals(LiveCaptureController.State.INACTIVE, c.state());
    }

    @Test void deferredStopMappingFailureAbortsOnCaller() {
        Harness h = new Harness();
        h.deferred = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);
        h.failGrab = true;
        c.requestStop(LiveCaptureController.StopReason.USER);
        assertEquals(LiveCaptureController.State.FAILED, c.state());
        assertTrue(h.recorder.aborted);
        assertSame(Thread.currentThread(), h.grabberCloseThread);
    }

    @Test void deferredFinalSubmitFailureAbortsWithoutLosingGlCleanup() throws Exception {
        Harness h = new Harness();
        h.deferred = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);
        h.recorder.failSubmit = true;
        c.requestStop(LiveCaptureController.StopReason.USER);
        awaitNotStopping(c);
        assertEquals(LiveCaptureController.State.FAILED, c.state());
        assertTrue(h.recorder.aborted);
        assertSame(Thread.currentThread(), h.grabberCloseThread);
    }

    // ---------------------------------------------------------------
    // Why a recording ended, for the on-screen notice
    // ---------------------------------------------------------------

    @Test void resizeStopIsReportedAsAnInterruption() throws Exception {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);

        c.requestStop(LiveCaptureController.StopReason.VIEWPORT_CHANGED);
        awaitNotStopping(c);

        assertEquals(java.util.Optional.of(
                        LiveCaptureController.Interruption.WINDOW_RESIZED),
                c.consumeInterruption());
    }

    /**
     * The player pressed the key, so the indicator disappearing is exactly what
     * they asked for. Reporting it would train them to ignore the notice.
     */
    @Test void userStopIsNotAnInterruption() throws Exception {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);

        c.requestStop(LiveCaptureController.StopReason.USER);
        awaitNotStopping(c);

        assertEquals(java.util.Optional.empty(), c.consumeInterruption());
    }

    @Test void shutdownStopIsNotAnInterruption() throws Exception {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);

        c.requestStop(LiveCaptureController.StopReason.SHUTDOWN);
        awaitNotStopping(c);

        assertEquals(java.util.Optional.empty(), c.consumeInterruption());
    }

    /**
     * A recording that dies from a grab or encoder fault currently only reaches
     * a log line, which is exactly as invisible as the silent resize stop was.
     */
    @Test void captureFailureIsReportedAsAnInterruption() {
        Harness h = new Harness();
        h.failGrab = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);

        c.capturePresentedFrame(h.viewport);

        assertEquals(LiveCaptureController.State.FAILED, c.state());
        assertEquals(java.util.Optional.of(
                        LiveCaptureController.Interruption.CAPTURE_ERROR),
                c.consumeInterruption());
    }

    /**
     * The renderer polls every frame, so a non-consuming read would re-arm the
     * notice forever and it would never time out.
     */
    @Test void consumingAnInterruptionClearsIt() throws Exception {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.requestStop(LiveCaptureController.StopReason.VIEWPORT_CHANGED);
        awaitNotStopping(c);

        assertTrue(c.consumeInterruption().isPresent());
        assertEquals(java.util.Optional.empty(), c.consumeInterruption(),
                "a second poll must not re-arm the notice");
    }

    @Test void aQuietRecordingReportsNoInterruption() {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);

        assertEquals(java.util.Optional.empty(), c.consumeInterruption());
    }

    @Test void viewportMismatchStopsBeforeGrabOrSubmit() throws Exception {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(new CaptureViewport(5, 0, 320, 224));
        awaitNotStopping(c);
        assertEquals(0, h.grabs);
        assertTrue(h.recorder.indexes.isEmpty());
    }

    @Test void startFailuresAbortAndCloseAcquiredResources() {
        Harness h = new Harness();
        h.failRecorderCreate = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        assertEquals(LiveCaptureController.State.FAILED, c.state());
        assertEquals(List.of("audio-close"), h.events);
        h.failRecorderCreate = false;
        c.start(h.viewport, 60);
        assertEquals(LiveCaptureController.State.ACTIVE, c.state());
    }

    @Test void audioDrainFailureClosesTapOnceAndContinuesCurrentFrameWithSilence() {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);
        h.failDrain = true;
        c.capturePresentedFrame(h.viewport);
        h.failDrain = false;
        c.capturePresentedFrame(h.viewport);
        assertEquals(LiveCaptureController.State.ACTIVE, c.state());
        assertFalse(h.recorder.aborted);
        assertEquals(List.of(0L, 1L, 2L), h.recorder.indexes);
        assertEquals(List.of(800, 800, 800), h.recorder.frames.stream()
                .map(CapturedFrame::sampleCount).toList());
        assertSilent(h.recorder.frames.get(1));
        assertSilent(h.recorder.frames.get(2));
        assertEquals(1, h.audioCloseCalls);
    }

    @Test void replacementSilenceContinuesFailedHandlesExactClockPhase() {
        Harness h = new Harness();
        h.sampleRate = 5;
        h.advanceThenFailDrain = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 2);
        c.capturePresentedFrame(h.viewport);
        h.failDrain = true;
        c.capturePresentedFrame(h.viewport);
        c.capturePresentedFrame(h.viewport);
        assertEquals(List.of(2, 3, 2), h.recorder.frames.stream()
                .map(CapturedFrame::sampleCount).toList(),
                "fallback resumes from the pre-failure phase, ignoring a mutated failed clock");
        assertEquals(List.of(0L, 1L, 2L), h.recorder.indexes);
    }

    @Test void debugWrapperFailureUsesTheSameClockedSilenceDegradationPath() {
        Harness h = new Harness();
        h.debugFailAfterFrames = 1;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);
        c.capturePresentedFrame(h.viewport);
        c.capturePresentedFrame(h.viewport);
        assertEquals(LiveCaptureController.State.ACTIVE, c.state());
        assertEquals(List.of(0L, 1L, 2L), h.recorder.indexes);
        assertFalse(isSilent(h.recorder.frames.get(0)));
        assertTrue(isSilent(h.recorder.frames.get(1)));
        assertTrue(isSilent(h.recorder.frames.get(2)));
        assertEquals(1, h.audioCloseCalls);
    }

    @Test void audioFailureIsReportedOnceAndNextRecordingGetsFreshAudioState() throws Exception {
        Harness h = new Harness();
        h.debugFailAfterFrames = 0;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);
        Throwable firstFailure = c.lastFailure();
        c.capturePresentedFrame(h.viewport);
        assertSame(firstFailure, c.lastFailure(), "remaining silent frames do not re-report");
        c.requestStop(LiveCaptureController.StopReason.USER);
        awaitNotStopping(c);

        h.debugFailAfterFrames = -1;
        c.start(h.viewport, 60);
        assertNull(c.lastFailure());
        c.capturePresentedFrame(h.viewport);
        assertFalse(isSilent(h.recorder.frames.get(h.recorder.frames.size() - 1)));
    }

    @Test void audioCloseFailureDuringStopDoesNotAbortValidVideo() throws Exception {
        Harness h = new Harness();
        h.failAudioClose = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);
        c.requestStop(LiveCaptureController.StopReason.USER);
        awaitNotStopping(c);
        assertEquals(LiveCaptureController.State.INACTIVE, c.state());
        assertFalse(h.recorder.aborted);
        assertEquals(List.of("audio-close", "recorder-stop"), h.events);
    }

    /**
     * A capture lease can only be released on the producer's owner thread, so
     * an off-thread stop is refused. {@code AudioManager} deliberately keeps
     * its lease reference in that case so a later owner-thread stop can
     * complete the detach; the controller is the only production owner of the
     * handle, so it must keep its reference too. Dropping it in a {@code
     * finally} would throw away the sole retry path, leaving the producer
     * copying every presented packet into an orphan lease while every later
     * {@code start()} is refused and degrades to clocked silence.
     */
    @Test void refusedAudioCloseKeepsTheHandleSoALaterStopCanRetryTheDetach() throws Exception {
        Harness h = new Harness();
        h.failAudioClose = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.capturePresentedFrame(h.viewport);

        c.requestStop(LiveCaptureController.StopReason.USER);
        awaitNotStopping(c);
        assertEquals(1, h.audioCloseCalls, "the refused detach was attempted once");

        // The owner thread is available now, so the retry must actually happen.
        h.failAudioClose = false;
        c.close();

        assertEquals(2, h.audioCloseCalls,
                "the controller must retain the handle after a refused detach"
                        + " so a later stop can release the lease");
        assertTrue(h.audioClosed, "the retry released the lease");
    }

    @Test void audioAttachFailureStartsActiveVideoWithSilentStereoTrack() {
        Harness h = new Harness();
        h.failAudio = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        assertEquals(LiveCaptureController.State.ACTIVE, c.state());
        c.capturePresentedFrame(h.viewport);
        assertEquals(List.of(0L), h.recorder.indexes);
        assertEquals(800, h.recorder.frames.get(0).sampleCount());
        assertSilent(h.recorder.frames.get(0));
        assertFalse(h.recorder.aborted);
    }

    @Test void audioMetadataFailureClosesTapAndStartsVideoWithSilentStereoTrack() {
        Harness h = new Harness();
        h.failAudioSampleRate = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        assertEquals(LiveCaptureController.State.ACTIVE, c.state());
        c.capturePresentedFrame(h.viewport);
        assertEquals(1, h.audioCloseCalls);
        assertEquals(48_000, h.recorder.startedSampleRate);
        assertSilent(h.recorder.frames.get(0));
        assertFalse(h.recorder.aborted);
    }

    @Test void recorderOpenFailureAbortsRecorderAndClosesAudio() {
        Harness h = new Harness();
        h.recorder.failStart = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        assertEquals(LiveCaptureController.State.FAILED, c.state());
        assertTrue(h.recorder.aborted);
        assertTrue(h.audioClosed);
    }

    @Test void submitAndGrabFailuresAbort() {
        Harness h = new Harness();
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        h.failGrab = true;
        c.capturePresentedFrame(h.viewport);
        assertEquals(LiveCaptureController.State.FAILED, c.state());
        h.failGrab = false;
        c.start(h.viewport, 60);
        h.recorder.failSubmit = true;
        c.capturePresentedFrame(h.viewport);
        assertEquals(LiveCaptureController.State.FAILED, c.state());
    }

    @Test void finalizationFailurePersistsAndCanRetry() throws Exception {
        Harness h = new Harness();
        h.recorder.failStop = true;
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.requestStop(LiveCaptureController.StopReason.USER);
        awaitNotStopping(c);
        assertEquals(LiveCaptureController.State.FAILED, c.state());
        h.recorder.failStop = false;
        c.start(h.viewport, 60);
        assertEquals(LiveCaptureController.State.ACTIVE, c.state());
    }

    @Test void repeatedStopCloseAndStartWhileStoppingAreSafe() throws Exception {
        Harness h = new Harness();
        h.recorder.stopGate = new java.util.concurrent.CountDownLatch(1);
        LiveCaptureController c = h.controller();
        c.start(h.viewport, 60);
        c.requestStop(LiveCaptureController.StopReason.USER);
        c.requestStop(LiveCaptureController.StopReason.USER);
        c.start(h.viewport, 60);
        assertEquals(LiveCaptureController.State.STOPPING, c.state());
        h.recorder.stopGate.countDown();
        awaitNotStopping(c);
        c.close();
        c.close();
        assertEquals(LiveCaptureController.State.INACTIVE, c.state());
    }

    @Test void closeUsesOneBoundedBudgetToAbortBothFfmpegProcessesAndCleanFiles() throws Exception {
        RetainedProcess video = new RetainedProcess(true);
        RetainedProcess mux = new RetainedProcess(false);
        CountDownLatch muxLaunched = new CountDownLatch(1);
        List<Path> temporaryFiles = new ArrayList<>();
        Path outputDirectory = TestTempFiles.createTempDirectory("live-close");
        AtomicReference<Path> partialOutput = new AtomicReference<>();
        int[] launches = {0};
        FfmpegEncoder encoder = new FfmpegEncoder("ffmpeg", 1, command -> {
            if (launches[0]++ == 0) {
                temporaryFiles.add(Path.of(command.get(command.size() - 1)));
                return video;
            }
            temporaryFiles.add(Path.of(command.get(command.lastIndexOf("-i") + 1)));
            Path muxOutput = Path.of(command.get(command.size() - 1));
            partialOutput.set(muxOutput);
            Files.writeString(muxOutput, "partial");
            muxLaunched.countDown();
            return mux;
        }, 30_000);
        CaptureRecorder actualRecorder = new CaptureRecorder(encoder, BackpressurePolicy.BLOCK, 8,
                outputDirectory, "live", "bounded");
        ExecutorService finalizer = Executors.newSingleThreadExecutor();
        executors.add(finalizer);
        LiveCaptureController controller = new LiveCaptureController(
                new LiveCaptureController.Dependencies(rate -> quietAudio(rate),
                        () -> 48_000,
                        v -> zeroGrabber(v), (v, rate) -> actualRecorder,
                        finalizer, Duration.ofMillis(300)));
        controller.start(new CaptureViewport(0, 0, 1, 1), 60);
        controller.requestStop(LiveCaptureController.StopReason.SHUTDOWN);
        assertTrue(muxLaunched.await(1, TimeUnit.SECONDS));
        long start = System.nanoTime();
        controller.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis < 600, "close exceeded its declared bound: " + elapsedMillis);
        assertTrue(video.destroyCalls > 0,
                "video must be force-destroyed before waiting on the stuck mux");
        assertTrue(mux.destroyCalls > 0,
                "mux must be force-destroyed even though neither process terminates");
        assertNotNull(partialOutput.get());
        assertFalse(Files.exists(partialOutput.get()));
        for (Path temporaryFile : temporaryFiles) {
            assertFalse(Files.exists(temporaryFile), "temporary file leaked: " + temporaryFile);
        }
        assertEquals(LiveCaptureController.State.INACTIVE, controller.state());
    }

    private static LiveCaptureAudioHandle quietAudio(int rate) {
        return new LiveCaptureAudioHandle() {
            private final AudioFrameClock clock = new AudioFrameClock(48_000, rate);
            public int sampleRate() { return 48000; }
            public int frameRate() { return rate; }
            public int maxStereoFramesPerPacket() { return 801; }
            public int drainPresentationFrame(short[] target) {
                return clock.samplesForNextFrame();
            }
            public long totalStereoFrames() { return clock.totalSamplesProduced(); }
            public AudioFrameClock.Snapshot clockSnapshot() {
                return clock.captureSnapshot();
            }
            public void close() {}
        };
    }

    private static VideoFrameGrabber zeroGrabber(CaptureViewport viewport) {
        return new VideoFrameGrabber() {
            public int width() { return viewport.width(); }
            public int height() { return viewport.height(); }
            public byte[] grab() { return new byte[viewport.rgbaByteSize()]; }
        };
    }

    private static final class RetainedProcess extends Process {
        private final boolean waitCompletes;
        private volatile int destroyCalls;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        RetainedProcess(boolean waitCompletes) { this.waitCompletes = waitCompletes; }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException {
            while (!waitCompletes) Thread.sleep(1);
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            if (!waitCompletes) {
                try {
                    unit.sleep(timeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return waitCompletes;
        }
        @Override public int exitValue() {
            if (!waitCompletes) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() { destroyCalls++; }
        @Override public Process destroyForcibly() { destroyCalls++; return this; }
        @Override public boolean isAlive() { return true; }
    }

    private static void awaitNotStopping(LiveCaptureController c) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (c.state() == LiveCaptureController.State.STOPPING && System.nanoTime() < end) {
            Thread.sleep(5);
        }
    }

    private final class Harness {
        final CaptureViewport viewport = new CaptureViewport(0, 0, 320, 224);
        final FakeRecorder recorder = new FakeRecorder();
        final List<String> events = new ArrayList<>();
        boolean failAudio, failAudioSampleRate, failRecorderCreate,
                failDrain, advanceThenFailDrain,
                failGrab, failAudioClose, audioClosed;
        int sampleRate = 48_000;
        int debugFailAfterFrames = -1;
        int audioCloseCalls;
        int grabs;
        boolean deferred;
        Thread grabberCloseThread;

        LiveCaptureController controller() {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executors.add(executor);
            return new LiveCaptureController(new LiveCaptureController.Dependencies(
                    rate -> {
                        if (failAudio) throw new IllegalStateException("audio");
                        LiveCaptureAudioHandle raw = new LiveCaptureAudioHandle() {
                            private final AudioFrameClock clock =
                                    new AudioFrameClock(sampleRate, rate);
                            public int sampleRate() {
                                if (failAudioSampleRate) {
                                    throw new IllegalStateException("sample rate");
                                }
                                return sampleRate;
                            }
                            public int frameRate() { return rate; }
                            public int maxStereoFramesPerPacket() {
                                return Math.floorDiv(sampleRate + rate - 1, rate);
                            }
                            public int drainPresentationFrame(short[] target) {
                                if (failDrain) {
                                    if (advanceThenFailDrain) clock.samplesForNextFrame();
                                    throw new IllegalStateException("drain");
                                }
                                int frames = clock.samplesForNextFrame();
                                java.util.Arrays.fill(target, 0, frames * 2, (short) 31);
                                return frames;
                            }
                            public long totalStereoFrames() {
                                return clock.totalSamplesProduced();
                            }
                            public AudioFrameClock.Snapshot clockSnapshot() {
                                return clock.captureSnapshot();
                            }
                            public void close() {
                                audioClosed = true;
                                audioCloseCalls++;
                                events.add("audio-close");
                                if (failAudioClose) throw new IllegalStateException("audio close");
                            }
                        };
                        return DebugFailAfterFramesAudioHandle.maybeWrap(
                                raw, debugFailAfterFrames);
                    },
                    () -> sampleRate,
                    v -> new VideoFrameGrabber() {
                        public int width() { return v.width(); }
                        public int height() { return v.height(); }
                        private final java.util.ArrayDeque<byte[]> pending = new java.util.ArrayDeque<>();
                        public boolean deferredReadback() { return deferred; }
                        public void beginReadback() {
                            byte[] pixels = new byte[v.rgbaByteSize()];
                            pixels[0] = (byte) ++grabs;
                            pending.add(pixels);
                        }
                        public void grabInto(byte[] target) {
                            if (!deferred) VideoFrameGrabber.super.grabInto(target);
                            else {
                                if (failGrab) throw new IllegalStateException("mapping");
                                System.arraycopy(pending.remove(), 0, target, 0, target.length);
                            }
                        }
                        public void close() { grabberCloseThread = Thread.currentThread(); }
                        public byte[] grab() {
                            grabs++;
                            if (failGrab) throw new IllegalStateException("grab");
                            return new byte[v.rgbaByteSize()];
                        }
                    },
                    (v, rate) -> {
                        if (failRecorderCreate) throw new IllegalStateException("recorder");
                        return recorder;
                    }, executor, Duration.ofSeconds(1)));
        }

        final class FakeRecorder extends CaptureRecorder {
            final List<Long> indexes = new ArrayList<>();
            final List<CapturedFrame> frames = new ArrayList<>();
            boolean aborted, failStart, failSubmit, failStop;
            int startedSampleRate;
            java.util.concurrent.CountDownLatch stopGate;

            FakeRecorder() {
                super(new CaptureEncoder() {
                    public void open(Path p, int w, int h, int f, int s) {}
                    public void encode(CapturedFrame frame) {}
                    public Path finish() { return Path.of("out"); }
                    public void abort() {}
                }, BackpressurePolicy.BLOCK, 1, Path.of("target"), "fake", "now");
            }
            @Override public void start(int w, int h, int f, int s) throws CaptureException {
                if (failStart) throw new CaptureException("start");
                startedSampleRate = s;
            }
            @Override public void submit(CapturedFrame frame) throws CaptureException {
                if (failSubmit) throw new CaptureException("submit");
                indexes.add(frame.frameIndex());
                frames.add(new CapturedFrame(frame.rgba(), frame.width(), frame.height(),
                        frame.pcm(), frame.sampleCount(), frame.frameIndex()));
                frame.release();
            }
            @Override public Path stop() throws CaptureException {
                if (stopGate != null) try { stopGate.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                events.add("recorder-stop");
                if (failStop) throw new CaptureException("stop");
                return Path.of("out");
            }
            @Override public void abort() { aborted = true; events.add("recorder-abort"); }
        }
    }

    private static void assertSilent(CapturedFrame frame) {
        assertTrue(isSilent(frame));
    }

    private static boolean isSilent(CapturedFrame frame) {
        for (int i = 0; i < frame.sampleCount() * 2; i++) {
            if (frame.pcm()[i] != 0) return false;
        }
        return true;
    }
}
