package com.openggf.capture;

import com.openggf.audio.ClockedSilenceAudioHandle;
import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.runtime.AudioFrameClock;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LiveCaptureController implements AutoCloseable {
    private static final Logger LOGGER =
            Logger.getLogger(LiveCaptureController.class.getName());

    public enum State { INACTIVE, STARTING, ACTIVE, STOPPING, FAILED }
    public enum StopReason { USER, VIEWPORT_CHANGED, CAPTURE_ERROR, SHUTDOWN }

    /**
     * A recording that ended without the user asking for it, worth surfacing
     * on screen. A user-requested stop and an ordinary shutdown are not
     * interruptions: the player already knows why the indicator went away.
     */
    public enum Interruption { WINDOW_RESIZED, CAPTURE_ERROR }
    public interface AudioHandleFactory { LiveCaptureAudioHandle open(int frameRate); }
    public interface FrameGrabberFactory { VideoFrameGrabber create(CaptureViewport viewport); }
    public interface RecorderFactory { CaptureRecorder create(CaptureViewport viewport, int frameRate); }
    public record Dependencies(AudioHandleFactory audio, IntSupplier audioSampleRate,
                               FrameGrabberFactory grabber,
                               RecorderFactory recorder, ExecutorService finalizer,
                               Duration shutdownTimeout) {
        public Dependencies {
            Objects.requireNonNull(audio);
            Objects.requireNonNull(audioSampleRate);
            Objects.requireNonNull(grabber);
            Objects.requireNonNull(recorder);
            Objects.requireNonNull(finalizer);
            Objects.requireNonNull(shutdownTimeout);
        }
    }

    private final Dependencies deps;
    private volatile State state = State.INACTIVE;
    private volatile Throwable lastFailure;
    private CaptureViewport viewport;
    private LiveCaptureAudioHandle audio;
    private VideoFrameGrabber grabber;
    private CaptureRecorder recorder;
    private short[] pcm;
    private long frameIndex;
    private short[] pendingPcm;
    private int pendingSamples;
    private Future<?> finalization;
    private CapturedFrame finalizerFrame;
    private boolean audioWarningLogged;
    private Interruption pendingInterruption;
    private AudioFrameClock.Snapshot nextAudioPhase;
    private int captureSampleRate;

    public LiveCaptureController(Dependencies deps) {
        this.deps = deps;
    }

    public synchronized void start(CaptureViewport viewport, int frameRate) {
        if (state == State.ACTIVE || state == State.STARTING || state == State.STOPPING) return;
        state = State.STARTING;
        lastFailure = null;
        audioWarningLogged = false;
        try {
            try {
                audio = deps.audio.open(frameRate);
                prepareAudioHandle(frameRate);
            } catch (Throwable audioFailure) {
                Throwable closeFailure = closeAudioOnCaller();
                if (closeFailure != null) audioFailure.addSuppressed(closeFailure);
                warnAudioOnce(audioFailure);
                audio = new ClockedSilenceAudioHandle(
                        Math.max(1, deps.audioSampleRate.getAsInt()), frameRate);
                prepareAudioHandle(frameRate);
            }
            grabber = deps.grabber.create(viewport);
            recorder = deps.recorder.create(viewport, frameRate);
            recorder.start(viewport.width(), viewport.height(), frameRate, captureSampleRate);
            this.viewport = viewport;
            frameIndex = 0;
            state = State.ACTIVE;
        } catch (Throwable failure) {
            failAndAbort(failure);
        }
    }

    public synchronized void capturePresentedFrame(CaptureViewport currentViewport) {
        if (state != State.ACTIVE) return;
        if (!viewport.equals(currentViewport)) {
            requestStop(StopReason.VIEWPORT_CHANGED);
            return;
        }
        try {
            int samples = drainAudioOrSilence();
            if (grabber.deferredReadback()) {
                grabber.beginReadback();
                if (pendingPcm != null) submitPixels(pendingPcm, pendingSamples);
                if (pendingPcm == null || pendingPcm.length < samples * 2) {
                    pendingPcm = new short[pcm.length];
                }
                System.arraycopy(pcm, 0, pendingPcm, 0, samples * 2);
                pendingSamples = samples;
            } else {
                submitPixels(pcm, samples);
            }
        } catch (Throwable failure) {
            failAndAbort(failure);
        }
    }

    public synchronized void requestStop(StopReason reason) {
        if (state != State.ACTIVE) return;
        state = State.STOPPING;
        recordInterruption(switch (reason) {
            case VIEWPORT_CHANGED -> Interruption.WINDOW_RESIZED;
            case CAPTURE_ERROR -> Interruption.CAPTURE_ERROR;
            case USER, SHUTDOWN -> null;
        });
        // GL readback and deletion must finish on the presentation thread before
        // handing only encoder finalization to the background executor.
        CapturedFrame closingFrame;
        try {
            closingFrame = pendingPcm == null ? null
                    : recorder.grabFrame(grabber, pendingPcm, pendingSamples, frameIndex++);
            pendingPcm = null;
            closeGrabber();
        } catch (Throwable failure) {
            failAndAbort(failure);
            return;
        }
        Throwable audioCloseFailure = closeAudioOnCaller();
        if (audioCloseFailure != null) {
            warnAudioOnce(audioCloseFailure);
        }
        CaptureRecorder stoppingRecorder = recorder;
        finalizerFrame = closingFrame;
        try {
            finalization = deps.finalizer.submit(() -> {
                CapturedFrame lastFrame;
                synchronized (LiveCaptureController.this) {
                    lastFrame = finalizerFrame;
                    finalizerFrame = null;
                }
                boolean submitted = false;
                try {
                    // Readback already completed on the GL thread. A full encoder
                    // queue must not trap shutdown in the caller before its timeout.
                    if (lastFrame != null) stoppingRecorder.submit(lastFrame);
                    submitted = true;
                    String exhaustion = stoppingRecorder.exhaustionSummary();
                    if (exhaustion != null) LOGGER.warning("Live viewport recording: " + exhaustion);
                    stoppingRecorder.stop();
                    synchronized (LiveCaptureController.this) {
                        if (state == State.STOPPING) state = State.INACTIVE;
                        clearResources();
                    }
                } catch (Throwable failure) {
                    if (!submitted && lastFrame != null) lastFrame.release();
                    stoppingRecorder.abort();
                    synchronized (LiveCaptureController.this) {
                        if (state == State.STOPPING) {
                            lastFailure = failure;
                            state = State.FAILED;
                            recordInterruption(Interruption.CAPTURE_ERROR);
                        }
                        clearResources();
                    }
                }
            });
        } catch (RuntimeException failure) {
            if (closingFrame != null) closingFrame.release();
            failAndAbort(failure);
        }
    }

    private void submitPixels(short[] samples, int count) throws CaptureException {
        CapturedFrame frame = recorder.grabFrame(grabber, samples, count, frameIndex++);
        try {
            recorder.submit(frame);
        } catch (Throwable failure) {
            frame.release();
            throw failure;
        }
    }

    public State state() { return state; }
    public Throwable lastFailure() { return lastFailure; }
    public boolean indicatorVisible() { return state == State.ACTIVE; }

    @Override
    public void close() {
        long timeoutNanos = Math.max(0, deps.shutdownTimeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        Future<?> pending;
        synchronized (this) {
            if (state == State.ACTIVE) requestStop(StopReason.SHUTDOWN);
            pending = finalization;
        }
        if (pending != null) {
            try {
                long gracefulNanos = timeoutNanos - Math.min(
                        TimeUnit.MILLISECONDS.toNanos(250), Math.max(1, timeoutNanos / 5));
                pending.get(Math.max(1, gracefulNanos), TimeUnit.NANOSECONDS);
            } catch (Exception failure) {
                CaptureRecorder r;
                synchronized (this) { r = recorder; }
                if (r != null) {
                    long remaining = Math.max(0, deadline - System.nanoTime());
                    r.abort(Duration.ofNanos(remaining));
                }
                pending.cancel(true);
            }
        }
        deps.finalizer.shutdown();
        synchronized (this) {
            closeAudioOnCaller();
            state = State.INACTIVE;
            clearResources();
        }
    }

    private void failAndAbort(Throwable failure) {
        Throwable closeFailure = closeAudioOnCaller();
        if (closeFailure != null && closeFailure != failure) {
            failure.addSuppressed(closeFailure);
        }
        if (recorder != null) recorder.abort();
        lastFailure = failure;
        state = State.FAILED;
        recordInterruption(Interruption.CAPTURE_ERROR);
        clearResources();
    }

    private void recordInterruption(Interruption interruption) {
        if (interruption != null) {
            pendingInterruption = interruption;
        }
    }

    /**
     * Takes the interruption awaiting display, if any. One-shot: the caller
     * owns how long to show it, so a repeated poll each frame does not keep
     * re-arming the notice.
     */
    public synchronized java.util.Optional<Interruption> consumeInterruption() {
        Interruption pending = pendingInterruption;
        pendingInterruption = null;
        return java.util.Optional.ofNullable(pending);
    }

    /**
     * Releases the audio lease on the calling thread, following the same rule
     * {@code AudioManager.closeLiveCaptureAudio} follows: the reference is
     * dropped only once the producer has accepted the detach.
     *
     * <p>A capture lease can only be released on the producer's owner thread.
     * {@code AudioManager} deliberately keeps its {@code
     * activeLiveCaptureAudioHandle} when the detach is refused, so a later
     * owner-thread stop can complete it. Nulling {@code audio} in a {@code
     * finally} would throw away the only reference able to retry, leaving the
     * producer copying every presented packet into an orphan lease while every
     * subsequent {@code start()} is rejected with "a live capture audio handle
     * is already attached" and degrades to clocked silence for the rest of the
     * process.
     */
    private Throwable closeAudioOnCaller() {
        if (audio == null) {
            return null;
        }
        try {
            audio.close();
        } catch (Throwable closeFailure) {
            return closeFailure;
        }
        audio = null;
        return null;
    }

    private int drainAudioOrSilence() {
        AudioFrameClock.Snapshot phaseBeforeDrain = nextAudioPhase;
        try {
            phaseBeforeDrain = audio.clockSnapshot();
            int frames = audio.drainPresentationFrame(pcm);
            nextAudioPhase = audio.clockSnapshot();
            return frames;
        } catch (Throwable audioFailure) {
            Throwable closeFailure = closeAudioOnCaller();
            if (closeFailure != null) audioFailure.addSuppressed(closeFailure);
            warnAudioOnce(audioFailure);
            audio = ClockedSilenceAudioHandle.atPhase(phaseBeforeDrain);
            if (pcm.length < audio.maxStereoFramesPerPacket() * 2) {
                pcm = new short[audio.maxStereoFramesPerPacket() * 2];
            }
            int frames = audio.drainPresentationFrame(pcm);
            nextAudioPhase = audio.clockSnapshot();
            return frames;
        }
    }

    private void prepareAudioHandle(int expectedFrameRate) {
        int sampleRate = audio.sampleRate();
        int actualFrameRate = audio.frameRate();
        int maxStereoFrames = audio.maxStereoFramesPerPacket();
        AudioFrameClock.Snapshot phase = audio.clockSnapshot();
        if (sampleRate <= 0) {
            throw new IllegalStateException("capture audio sample rate must be positive");
        }
        if (actualFrameRate != expectedFrameRate) {
            throw new IllegalStateException("capture audio frame rate does not match video");
        }
        if (maxStereoFrames <= 0) {
            throw new IllegalStateException("capture audio packet capacity must be positive");
        }
        if (phase.sampleRate() != sampleRate || phase.frameRate() != actualFrameRate) {
            throw new IllegalStateException("capture audio clock metadata is inconsistent");
        }
        captureSampleRate = sampleRate;
        nextAudioPhase = phase;
        pcm = new short[Math.multiplyExact(maxStereoFrames, 2)];
    }

    private void warnAudioOnce(Throwable failure) {
        if (!audioWarningLogged) {
            audioWarningLogged = true;
            lastFailure = failure;
            LOGGER.log(Level.WARNING,
                    "Live viewport recording audio failed; continuing with stereo silence",
                    failure);
        }
    }

    private void clearResources() {
        viewport = null;
        if (finalizerFrame != null) finalizerFrame.release();
        finalizerFrame = null;
        closeGrabber();
        recorder = null;
        pcm = null;
        pendingPcm = null;
        nextAudioPhase = null;
        captureSampleRate = 0;
    }

    private void closeGrabber() {
        // The grabber owns a viewport-sized native read buffer for its lifetime
        // now that it reuses one instead of allocating per frame, so dropping
        // the reference is no longer enough to release it.
        if (grabber != null) {
            try {
                grabber.close();
            } catch (Exception failure) {
                LOGGER.log(Level.WARNING,
                        "Live capture frame grabber failed to release its buffers",
                        failure);
            }
        }
        grabber = null;
    }
}
