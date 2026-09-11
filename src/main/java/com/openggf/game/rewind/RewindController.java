package com.openggf.game.rewind;

import com.openggf.audio.AudioManager;
import com.openggf.audio.rewind.AudioKeyframeStore;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.audio.rewind.AudioReplayReason;
import com.openggf.audio.rewind.AudioReplayScope;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.SectionProfiler;
import com.openggf.graphics.FadeManagerSnapshot;

import java.util.Objects;
import java.util.logging.Logger;

public final class RewindController {

    private static final Logger LOGGER = Logger.getLogger(RewindController.class.getName());

    private final RewindRegistry registry;
    private final KeyframeStore keyframes;
    private final InputSource inputs;
    private final EngineStepper engineStepper;
    private final SegmentCache segmentCache;
    private final int keyframeInterval;
    private int gameplayCheckpointInterval;
    private final AudioManager audioManager;
    private final AudioKeyframeStore audioKeyframes;
    private final SectionProfiler profiler;

    private int currentFrame;
    private boolean audioRestoreDeferred;
    private RewindDeterminismAuditor determinismAuditor;

    public RewindController(
            RewindRegistry registry,
            KeyframeStore keyframes,
            InputSource inputs,
            EngineStepper engineStepper,
            int keyframeInterval) {
        this(registry, keyframes, inputs, engineStepper, keyframeInterval, null, null);
    }

    public RewindController(
            RewindRegistry registry,
            KeyframeStore keyframes,
            InputSource inputs,
            EngineStepper engineStepper,
            int keyframeInterval,
            AudioManager audioManager) {
        this(registry, keyframes, inputs, engineStepper, keyframeInterval, audioManager, null);
    }

    public RewindController(
            RewindRegistry registry,
            KeyframeStore keyframes,
            InputSource inputs,
            EngineStepper engineStepper,
            int keyframeInterval,
            AudioManager audioManager,
            SectionProfiler profiler) {
        this.registry = Objects.requireNonNull(registry);
        this.keyframes = Objects.requireNonNull(keyframes);
        this.inputs = Objects.requireNonNull(inputs);
        this.engineStepper = Objects.requireNonNull(engineStepper);
        if (keyframeInterval <= 0) {
            throw new IllegalArgumentException(
                    "keyframeInterval must be > 0, got " + keyframeInterval);
        }
        this.keyframeInterval = keyframeInterval;
        this.gameplayCheckpointInterval = keyframeInterval;
        this.audioManager = audioManager;
        this.audioKeyframes = audioManager != null ? new AudioKeyframeStore() : null;
        this.segmentCache = new SegmentCache(keyframeInterval);
        this.currentFrame = 0;
        this.profiler = profiler;
        // Capture frame 0 so seekTo(0) always has a base.
        keyframes.put(0, registry.capture());
        captureAudioKeyframe(0);
    }

    /**
     * Adds gameplay-only checkpoints between audio keyframes to bound cold replay
     * work. Configure before stepping; durable audio snapshots keep their original
     * cadence. A divisor keeps each audio keyframe aligned with a gameplay one.
     */
    public void setGameplayCheckpointInterval(int interval) {
        if (interval <= 0 || keyframeInterval % interval != 0) {
            throw new IllegalArgumentException("gameplay interval must divide the keyframe interval");
        }
        if (currentFrame != 0) {
            throw new IllegalStateException("configure gameplay checkpoints before stepping");
        }
        gameplayCheckpointInterval = interval;
    }

    public int currentFrame() { return currentFrame; }

    /**
     * Consults the (possibly-absent) {@code "fademanager"} entry of a
     * candidate {@link CompositeSnapshot} for {@link FadeManagerSnapshot#isPoisoned()}.
     * A missing entry (no FadeManager registered, e.g. bare test registries)
     * is never poisoned.
     */
    private static boolean isPoisonedFadeSnapshot(CompositeSnapshot snapshot) {
        Object raw = snapshot.get("fademanager");
        return raw instanceof FadeManagerSnapshot fade && fade.isPoisoned();
    }

    public void setDeterminismAuditor(RewindDeterminismAuditor auditor) {
        this.determinismAuditor = auditor;
    }

    public int earliestAvailableFrame() {
        // v1: trace mode — earliest accessible frame is whatever the
        // earliest stored keyframe is (typically 0).
        int e = keyframes.earliestFrame();
        return e < 0 ? 0 : e;
    }

    /**
     * Re-roots rewind history at the current frame after a committed level or
     * act boundary. This prevents seeks from replaying across incompatible
     * level-load state while keeping rewind available in the new segment.
     */
    public void resetBufferAtCurrentFrame() {
        dropDeferredAudioRestore();
        segmentCache.invalidate();
        keyframes.clear();
        keyframes.put(currentFrame, registry.capture());
        if (audioKeyframes != null) {
            audioKeyframes.clear();
            captureAudioKeyframe(currentFrame);
        }
    }

    /**
     * Re-roots rewind history at frame 0 after a level/act boundary.
     * Unlike {@link #resetBufferAtCurrentFrame}, this also rewinds the
     * controller's {@code currentFrame} counter back to 0 so the new
     * segment starts with a clean rewind-counter origin. Callers must
     * separately reset the {@link InputSource} (e.g. discardAfter(0))
     * and any companion audio state to keep the two sides aligned.
     */
    public void resetToFrameZero() {
        dropDeferredAudioRestore();
        segmentCache.invalidate();
        keyframes.clear();
        currentFrame = 0;
        keyframes.put(0, registry.capture());
        if (audioKeyframes != null) {
            audioKeyframes.clear();
            captureAudioKeyframe(0);
        }
    }

    /** Steps forward one frame, capturing a keyframe at the boundary. */
    public void step() {
        if (currentFrame + 1 >= inputs.frameCount()) {
            return;   // end of trace
        }
        commitDeferredAudioRestore();
        // Forward resume abandons the cached future branch. Reusing those
        // snapshots on a later backward step would restore stale state.
        segmentCache.invalidate();
        beginAudioFrame(currentFrame + 1);
        Bk2FrameInput in = inputs.read(currentFrame + 1);
        if (engineStepper.step(in) == com.openggf.LevelFrameResult.SETUP_ONLY) {
            discardAudioAfter(currentFrame);
            return;
        }
        currentFrame++;
        if (currentFrame % gameplayCheckpointInterval == 0) {
            keyframes.put(currentFrame, registry.capture());
        }
        if (currentFrame % keyframeInterval == 0) {
            captureAudioKeyframe(currentFrame);
        }
    }

    /**
     * Records that the host visual loop has already advanced the engine by one
     * input frame. This keeps rewind history in sync without recursively
     * invoking {@link EngineStepper#step(com.openggf.debug.playback.Bk2FrameInput)}.
     *
     * @return true when the controller advanced its cursor, false at input end
     */
    public boolean recordExternalStep() {
        if (currentFrame + 1 >= inputs.frameCount()) {
            return false;
        }
        commitDeferredAudioRestore();
        currentFrame++;
        beginAudioFrame(currentFrame);
        segmentCache.invalidate();
        if (currentFrame % gameplayCheckpointInterval == 0) {
            keyframes.put(currentFrame, registry.capture());
        }
        if (currentFrame % keyframeInterval == 0) {
            captureAudioKeyframe(currentFrame);
            if (determinismAuditor != null) {
                auditLastSegment();
            }
        }
        return true;
    }

    private void auditLastSegment() {
        try {
            var prevOpt = keyframes.latestAtOrBefore(currentFrame - keyframeInterval);
            var liveOpt = keyframes.latestAtOrBefore(currentFrame);
            if (prevOpt.isEmpty() || liveOpt.isEmpty() || liveOpt.get().frame() != currentFrame) {
                return;
            }

            var prev = prevOpt.get();
            CompositeSnapshot live = liveOpt.get().snapshot();
            boolean diverged = false;
            try (AudioReplayScope ignored = beginAudioReplay(
                    currentFrame, currentFrame, AudioReplayReason.SEEK)) {
                try {
                    registry.restore(prev.snapshot());
                    primeStepperAtFrame(prev.frame());
                    int pos = prev.frame();
                    while (pos < currentFrame) {
                        stepReplayInput(inputs.read(pos + 1));
                        pos++;
                    }
                    diverged = determinismAuditor.report(
                            prev.frame(), currentFrame, live, registry.capture());
                } finally {
                    registry.restore(live);
                    primeStepperAtFrame(currentFrame);
                    restoreAudioLogicalState(currentFrame);
                    beginAudioFrame(currentFrame);
                    afterAudioRestore(AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
                }
            }
            if (diverged) {
                determinismAuditor = null;
            }
        } catch (RuntimeException e) {
            LOGGER.warning("Live rewind determinism audit failed and will be disabled: "
                    + e);
            determinismAuditor = null;
        }
    }

    /**
     * Drops rewind history older than {@code retainedFrames}, aligned to a
     * retained keyframe so replay from the new earliest frame still has every
     * input row needed to reach current time. Audio retains the keyframe at
     * or before the gameplay floor, which can fall between audio boundaries.
     * The command timeline is pruned to that retained audio keyframe's
     * {@code commandEntryCount} so replay from every surviving gameplay
     * checkpoint still has its audio base and commands.
     *
     * @return the earliest retained frame after pruning
     */
    public int pruneHistoryToRetainFrames(int retainedFrames) {
        if (retainedFrames <= 0) {
            return earliestAvailableFrame();
        }
        int requestedEarliestFrame = currentFrame - retainedFrames;
        if (requestedEarliestFrame <= earliestAvailableFrame()) {
            return earliestAvailableFrame();
        }
        var retainedFloor = keyframes.latestAtOrBefore(requestedEarliestFrame);
        if (retainedFloor.isEmpty()) {
            return earliestAvailableFrame();
        }
        int retainedKeyframe = retainedFloor.get().frame();
        keyframes.discardBefore(retainedKeyframe);
        if (audioKeyframes != null) {
            audioKeyframes.discardBeforeRetainingFloor(retainedKeyframe);
            AudioLogicalSnapshot earliestAudio = audioKeyframes.earliestSnapshot();
            if (earliestAudio != null) {
                audioManager.pruneAudioCommandsBefore(earliestAudio.commandEntryCount());
            }
        }
        return earliestAvailableFrame();
    }

    /**
     * Seeks to {@code targetFrame} by restoring the latest keyframe at or
     * before it, then stepping forward. Held-rewind callers should use
     * {@link #stepBackward()} for steady-state O(1) cost.
     */
    public void seekTo(int targetFrame) {
        if (targetFrame == currentFrame) return;
        if (targetFrame < earliestAvailableFrame()) {
            targetFrame = earliestAvailableFrame();
        }
        final int clampedTarget = targetFrame;
        var floor = keyframes.latestAtOrBefore(clampedTarget).orElseThrow(
                () -> new IllegalStateException(
                        "no keyframe at or before " + clampedTarget));
        int originalFrame = currentFrame;
        if (profiler != null) profiler.beginSection("rewind.seek");
        try (AudioReplayScope ignored = beginAudioReplay(
                originalFrame, clampedTarget, AudioReplayReason.SEEK)) {
            segmentCache.invalidate();
            registry.restore(floor.snapshot());
            // registry.restore closed rewind.restore in its finally; re-open rewind.seek.
            if (profiler != null) profiler.beginSection("rewind.seek");
            currentFrame = floor.frame();
            primeStepperAtFrame(currentFrame);
            while (currentFrame < clampedTarget) {
                if (profiler != null) profiler.beginSection("rewind.tick");
                try {
                    Bk2FrameInput in = inputs.read(currentFrame + 1);
                    stepReplayInput(in);
                    currentFrame++;
                } finally {
                    if (profiler != null) profiler.endSection("rewind.tick");
                }
            }
            // After the loop, the last endSection("rewind.tick") cleared the
            // active section. Re-open rewind.seek for the audio bookkeeping tail.
            if (profiler != null) profiler.beginSection("rewind.seek");
            keyframes.discardAfter(currentFrame);
            discardAudioAfter(currentFrame);
            // The seek's own restore lands the committed target state, so any
            // restore deferred by held backward stepping is superseded here.
            dropDeferredAudioRestore();
            restoreAudioLogicalState(currentFrame);
            beginAudioFrame(currentFrame);
            if (audioManager != null) {
                audioManager.commitDeferredReverseLogicalRestore();
            }
            primeStepperAtFrame(currentFrame);
            afterAudioRestore(AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
        } finally {
            if (profiler != null) profiler.endSection("rewind.seek");
        }
    }

    /**
     * Rewinds one frame using the segment cache for amortised O(1) cost.
     * Returns false if already at {@code earliestAvailableFrame}.
     */
    public boolean stepBackward() {
        if (currentFrame <= earliestAvailableFrame()) return false;
        int originalFrame = currentFrame;
        int target = currentFrame - 1;
        CompositeSnapshot snap = segmentCache.cachedSnapshotAtOrNull(target);
        if (profiler != null) profiler.beginSection("rewind.step");
        try (AudioReplayScope ignored = beginAudioReplay(
                originalFrame, target, AudioReplayReason.STEP_BACKWARD)) {
            // A cache miss below expands the segment by actually driving
            // engineStepper.step() forward from the segment's keyframe, which
            // mutates live registry state as a side effect of computing
            // `snap` -- unlike a cache hit, which is a pure array read with
            // no live mutation. Capture a rollback point ONLY when a miss is
            // about to happen (steady-state stepBackward() stays O(1)); it is
            // used to undo that mutation if `target` turns out to be a
            // poisoned frame we must refuse to land on.
            CompositeSnapshot preExpansionSnapshot = null;
            if (snap == null) {
                // Search for the latest keyframe at or before target directly. The
                // historical interval floor is invalid after an unaligned boundary reset.
                final var floor = keyframes.latestAtOrBefore(target).orElseThrow();
                final int keyframeSnapshot = floor.frame();
                final var restoreSnapshot = floor.snapshot();
                final int[] pos = { currentFrame };
                preExpansionSnapshot = registry.capture();
                try {
                    snap = segmentCache.snapshotAt(
                            target,
                            restoreSnapshot,
                            keyframeSnapshot,
                            () -> {
                                registry.restore(restoreSnapshot);
                                // registry.restore closed rewind.restore in its finally; re-open
                                // rewind.step so primeStepperAtFrame credits to it instead of
                                // falling into the unattributed gap before rewind.tick opens.
                                if (profiler != null) profiler.beginSection("rewind.step");
                                pos[0] = keyframeSnapshot;
                                primeStepperAtFrame(pos[0]);
                            },
                            () -> {
                                if (profiler != null) profiler.beginSection("rewind.tick");
                                try {
                                    Bk2FrameInput in = inputs.read(pos[0] + 1);
                                    stepReplayInput(in);
                                    pos[0]++;
                                    // On happy path, registry.capture() opens rewind.capture which
                                    // implicitly ends rewind.tick (recording its delta) before
                                    // the finally fires. The finally then no-ops.
                                    return registry.capture();
                                } finally {
                                    if (profiler != null) profiler.endSection("rewind.tick");
                                }
                            });
                } catch (RuntimeException | Error expansionFailure) {
                    currentFrame = originalFrame;
                    try {
                        registry.restore(preExpansionSnapshot);
                    } catch (RuntimeException | Error rollbackFailure) {
                        if (rollbackFailure != expansionFailure) {
                            expansionFailure.addSuppressed(rollbackFailure);
                        }
                    }
                    // registry.restore closes rewind.restore in its finally, even
                    // when rollback fails. Re-open the parent before re-priming.
                    if (profiler != null) profiler.beginSection("rewind.step");
                    try {
                        primeStepperAtFrame(originalFrame);
                    } catch (RuntimeException | Error primeFailure) {
                        if (primeFailure != expansionFailure) {
                            expansionFailure.addSuppressed(primeFailure);
                        }
                    }
                    throw expansionFailure;
                }
            }
            if (isPoisonedFadeSnapshot(snap)) {
                // Committing here would leave a still-in-flight, callback-
                // bearing fade resting as the live state -- FadeManager.restore()
                // never restores the transient onFadeComplete callback, so the
                // fade would be stuck faded forever with nothing left to un-fade
                // it (see FadeManagerSnapshot.isPoisoned()). Refuse exactly like
                // hitting earliestAvailableFrame(): clamp here rather than
                // stepping onto (or skipping past) the poisoned frame.
                if (preExpansionSnapshot != null) {
                    registry.restore(preExpansionSnapshot);
                    // registry.restore closed rewind.restore in its finally; re-open
                    // rewind.step to bracket the re-priming below.
                    if (profiler != null) profiler.beginSection("rewind.step");
                    primeStepperAtFrame(currentFrame);
                }
                return false;
            }
            registry.restore(snap);
            // registry.restore closed its rewind.restore in its own finally,
            // leaving no active section. Re-open rewind.step so the audio
            // bookkeeping tail credits to it. No re-open is needed between
            // snapshotAt return and registry.restore: nothing measurable
            // happens between them (trivial reference assignment).
            if (profiler != null) profiler.beginSection("rewind.step");
            currentFrame = target;
            keyframes.discardAfter(currentFrame);
            discardAudioAfter(currentFrame);
            // While reverse audio presentation is active (held rewind), the
            // audible output comes from the PcmHistoryRing, not the logical
            // SMPS driver state, so the expensive logical restore is deferred
            // until the rewind commits (release, seek, forward resume, or
            // buffer re-root) via commitDeferredAudioRestore().
            if (shouldDeferAudioRestore()) {
                audioRestoreDeferred = true;
            } else {
                audioRestoreDeferred = false;
                restoreAudioLogicalState(currentFrame);
            }
            beginAudioFrame(currentFrame);
            primeStepperAtFrame(currentFrame);
            afterAudioRestore(AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
        } finally {
            if (profiler != null) profiler.endSection("rewind.step");
        }
        return true;
    }

    /**
     * Performs the logical audio restore that was deferred during held-rewind
     * backward stepping, landing exactly one restore at the committed frame.
     * Invoked automatically on every forward-resume path through this
     * controller (level-boundary re-roots instead {@link
     * #dropDeferredAudioRestore() drop} the pending restore); held-rewind
     * hosts must call it before issuing a non-suppressed
     * {@code afterRewindRestore} so the presentation cleanup
     * (stopAllSfx / restoreMusic / stopPlayback) acts on committed-frame state.
     */
    public void commitDeferredAudioRestore() {
        if (!audioRestoreDeferred) {
            return;
        }
        audioRestoreDeferred = false;
        restoreAudioLogicalState(currentFrame);
        beginAudioFrame(currentFrame);
        if (audioManager != null) {
            audioManager.commitDeferredReverseLogicalRestore();
        }
    }

    /**
     * Arms or disarms continuous PCM rewind-history recording via the
     * constructor-injected {@link AudioManager} collaborator. Package-private:
     * {@code com.openggf.game.rewind} is a runtime-owned framework package
     * that must not reach audio through the {@code GameServices} static
     * facade, so held-rewind hosts in this package (e.g. {@code
     * LiveRewindManager}) go through the controller's own explicit
     * collaborator instead.
     */
    void setRewindHistoryArmed(boolean armed) {
        if (audioManager != null) {
            audioManager.setRewindHistoryArmed(armed);
        }
    }

    /** See {@link #setRewindHistoryArmed}; clears the ring at a hard boundary. */
    void clearPcmHistory() {
        if (audioManager != null) {
            audioManager.clearPcmHistory();
        }
    }

    /**
     * Severs a held rewind's pre-boundary logical restore and prepares the
     * already initialized post-boundary dual audio state for transactional
     * reverse release. Repeated calls retry the exact fresh prepared token.
     */
    boolean preparePostBoundaryAudioRelease() {
        audioRestoreDeferred = false;
        return audioManager == null
                || audioManager.preparePostBoundaryReverseRelease();
    }

    /**
     * Discards a pending deferred restore without applying it. Buffer re-roots
     * follow a committed level/act boundary whose load path has already
     * reinitialized audio for the new level ({@code LevelManager.loadLevel}
     * runs its init steps — including fresh audio init — before
     * {@code resetToFrameZero}; seamless transitions likewise precede
     * {@code resetBufferAtCurrentFrame}). A deferred commit must never run
     * after audio has been freshly reinitialized for a new level: committing
     * the stale pre-rewind state here would overwrite the fresh state and
     * capture it into the re-rooted audio keyframe.
     */
    private void dropDeferredAudioRestore() {
        audioRestoreDeferred = false;
    }

    /**
     * The gate intentionally keys off reverse-presentation state: the
     * held-rewind hosts (LiveRewindManager / TraceSessionLauncher) own that
     * lifecycle and bracket exactly the window where audible output comes from
     * the PcmHistoryRing, while non-presentation backward stepping (e.g. trace
     * tooling via PlaybackController) must stay eager.
     */
    private boolean shouldDeferAudioRestore() {
        return audioManager != null && audioManager.isReverseAudioPresentationActive();
    }

    private AudioReplayScope beginAudioReplay(int fromFrame, int targetFrame, AudioReplayReason reason) {
        if (audioManager == null) {
            return () -> {};
        }
        return audioManager.beginRewindReplay(fromFrame, targetFrame, reason);
    }

    boolean afterAudioRestore(AudioPresentationPolicy policy) {
        return audioManager == null
                || audioManager.afterRewindRestore(currentFrame, policy);
    }

    private void beginAudioFrame(int frame) {
        if (audioManager != null) {
            audioManager.beginCommandTimelineFrame(frame);
        }
    }

    private void discardAudioAfter(int frame) {
        if (audioManager != null) {
            audioManager.discardAudioCommandsAfter(frame);
            audioKeyframes.discardAfter(frame);
        }
    }

    private void captureAudioKeyframe(int frame) {
        if (audioKeyframes != null) {
            audioKeyframes.capture(frame, audioManager);
        }
    }

    private void restoreAudioLogicalState(int frame) {
        if (audioKeyframes != null) {
            audioKeyframes.replayToLogicalState(audioManager, frame);
        }
    }

    private void primeStepperAtFrame(int frame) {
        if (engineStepper instanceof RewindSeekAwareEngineStepper seekAware) {
            seekAware.restoreToFrame(frame, inputs.read(frame));
        }
    }

    private void stepReplayInput(Bk2FrameInput input) {
        while (engineStepper.step(input) == com.openggf.LevelFrameResult.SETUP_ONLY) {
            // Initial Process_Sprites consumes no input row or rewind time.
        }
    }
}
