package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.FadeManagerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestRewindController {

    private static CompositeSnapshot snap(int marker) {
        var e = new LinkedHashMap<String, Object>();
        e.put("marker", marker);
        return new CompositeSnapshot(e);
    }

    @Test
    void stepForwardCapturesKeyframesAtIntervals() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(10);
        AtomicInteger stepCount = new AtomicInteger();
        EngineStepper stepper = (in) -> {
            stepCount.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 3);
        assertEquals(0, rc.currentFrame());

        // Step to frame 3 — should capture keyframe
        for (int i = 0; i < 3; i++) rc.step();
        assertEquals(3, rc.currentFrame());
        assertTrue(keyframes.latestAtOrBefore(3).isPresent());

        // Step to frame 6 — should capture another keyframe
        for (int i = 0; i < 3; i++) rc.step();
        assertEquals(6, rc.currentFrame());
        assertTrue(keyframes.latestAtOrBefore(6).isPresent());
    }

    @Test
    void seekToRestoresStateAndStepsForward() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(10);
        AtomicInteger state = new AtomicInteger(0);
        EngineStepper stepper = (in) -> {
            state.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        // Register a simple snapshottable
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "state"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 5);

        // Step to frame 5
        for (int i = 0; i < 5; i++) rc.step();
        assertEquals(5, state.get());

        // Seek to frame 3: should restore to state 0, step 3 times
        rc.seekTo(3);
        assertEquals(3, rc.currentFrame());
        assertEquals(3, state.get());
    }

    @Test
    void seekToPrimesSeekAwareStepperAtRestoredKeyframeBeforeReplay() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(10);
        SeekAwareStepper stepper = new SeekAwareStepper();

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 3);
        for (int i = 0; i < 5; i++) {
            rc.step();
        }
        stepper.steppedFrames.clear();

        rc.seekTo(4);

        assertEquals(3, stepper.firstRestoredFrame,
                "Stepper must be primed to the restored keyframe before replaying target frames");
        assertEquals(3, stepper.firstRestoredInputFrame);
        assertEquals(List.of(4), stepper.steppedFrames);
    }

    @Test
    void stepBackwardWithinSegmentIsCacheHit() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        AtomicInteger stepInvocations = new AtomicInteger();
        EngineStepper stepper = (in) -> {
            stepInvocations.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 10);

        // Step forward to frame 15
        for (int i = 0; i < 15; i++) rc.step();
        assertEquals(15, rc.currentFrame());
        int stepsAfterForward = stepInvocations.get();

        // Step backward to frame 12 (same segment): should not step forward more times
        rc.stepBackward();
        rc.stepBackward();
        rc.stepBackward();
        assertEquals(12, rc.currentFrame());
        // stepInvocations should only increase by the segment expansion cost,
        // not by 3 additional steps for the 3 backward calls
        int stepsAfterBackward = stepInvocations.get();
        // Expansion of segment [10, 20): need to step from frame 10 to target frame 12
        // Forward steps: 0->1...1->15 (15 steps), segment expansion: 10->12 (2 steps to 12)
        // Actually: initial 15 forward steps + 4 steps to expand to offset 4 (steps 11-14)
        assertTrue(stepsAfterBackward < stepsAfterForward + 10,
                "segment cache should cost O(1) per backward step, not O(n)");
    }

    @Test
    void warmStepBackwardHitSkipsKeyframeFloorLookupAndExpansionCallbacks() {
        RewindRegistry reg = new RewindRegistry();
        AtomicInteger state = new AtomicInteger();
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "state"; }
            @Override public Integer capture() { captures.incrementAndGet(); return state.get(); }
            @Override public void restore(Integer snapshot) { restores.incrementAndGet(); state.set(snapshot); }
        });
        CountingKeyframeStore keyframes = new CountingKeyframeStore();
        AtomicInteger stepInvocations = new AtomicInteger();
        EngineStepper stepper = in -> {
            stepInvocations.incrementAndGet();
            state.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
        RewindController rc = new RewindController(reg, keyframes, new FakeInputSource(100), stepper, 10);
        for (int i = 0; i < 15; i++) rc.step();

        assertTrue(rc.stepBackward(), "cold access expands the segment");
        int floorLookupsAfterExpansion = keyframes.floorLookups.get();
        captures.set(0);
        restores.set(0);
        int stepsAfterExpansion = stepInvocations.get();

        assertTrue(rc.stepBackward(), "next frame is a warm cache hit");

        assertEquals(floorLookupsAfterExpansion, keyframes.floorLookups.get(),
                "warm hit must not allocate Optional/Entry through the keyframe store");
        assertEquals(0, captures.get(), "warm hit must not run expansion capture callbacks");
        assertEquals(1, restores.get(), "warm hit still commits exactly one target restore");
        assertEquals(stepsAfterExpansion, stepInvocations.get(), "warm hit must not replay a tick");
        assertEquals(13, rc.currentFrame());
        assertEquals(13, state.get(), "restored state fingerprint must match the target frame");
    }

    @Test
    void forwardResumeInvalidatesWarmBackwardCache() {
        CountingKeyframeStore keyframes = new CountingKeyframeStore();
        RewindController rc = new RewindController(
                new RewindRegistry(), keyframes, new FakeInputSource(100),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME, 10);
        for (int i = 0; i < 15; i++) rc.step();
        assertTrue(rc.stepBackward());
        assertTrue(rc.stepBackward());
        int warmLookupCount = keyframes.floorLookups.get();

        rc.step();
        assertTrue(rc.stepBackward());

        assertEquals(warmLookupCount + 1, keyframes.floorLookups.get(),
                "normal forward resume must invalidate snapshots from the abandoned future");
    }

    @Test
    void stepBackwardAcrossSegmentBoundaryRebuilds() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        AtomicInteger stepInvocations = new AtomicInteger();
        EngineStepper stepper = (in) -> {
            stepInvocations.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 10);

        // Step forward to frame 15
        for (int i = 0; i < 15; i++) rc.step();

        // Step backward across segment boundary to frame 5
        rc.seekTo(5);
        assertEquals(5, rc.currentFrame());
    }

    @Test
    void seekToEarlierFrameDiscardsFutureKeyframes() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(20);
        EngineStepper stepper = (in) -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME;

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 3);
        for (int i = 0; i < 6; i++) rc.step();
        assertEquals(6, keyframes.latestAtOrBefore(99).orElseThrow().frame());

        rc.seekTo(4);

        assertEquals(3, keyframes.latestAtOrBefore(99).orElseThrow().frame(),
                "rewinding abandons keyframes from the previous future branch");
    }

    @Test
    void stepBackwardDiscardsFutureKeyframes() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(20);
        EngineStepper stepper = (in) -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME;

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 3);
        for (int i = 0; i < 6; i++) rc.step();
        assertEquals(6, keyframes.latestAtOrBefore(99).orElseThrow().frame());

        assertTrue(rc.stepBackward());

        assertEquals(3, keyframes.latestAtOrBefore(99).orElseThrow().frame(),
                "one-frame rewind abandons keyframes beyond the restored frame");
    }

    @Test
    void earliestAvailableFrameClampsSeekTo() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(10);
        EngineStepper stepper = (in) -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME;

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 5);
        rc.seekTo(-5);  // Request before earliest frame
        assertEquals(0, rc.currentFrame());
    }

    @Test
    void stepBackwardReturnsFalseAtEarliestFrame() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(10);
        EngineStepper stepper = (in) -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME;

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 5);
        assertFalse(rc.stepBackward(), "should return false at earliest frame");
    }

    @Test
    void stepBackwardClampsBeforeCommittingToAPoisonedFadeFrame() {
        RewindRegistry reg = new RewindRegistry();
        FadeManager fadeManager = new FadeManager();
        reg.register(fadeManager);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        AtomicBoolean fadeStarted = new AtomicBoolean(false);
        AtomicInteger completions = new AtomicInteger();
        EngineStepper stepper = (in) -> {
            if (in.frameIndex() == 10 && !fadeStarted.get()) {
                fadeStarted.set(true);
                // holdFrames=0, totalDuration=0 (default 21-frame fade):
                // FADING_TO_BLACK -> auto HOLD_BLACK -> callback fires and
                // clears onFadeComplete, all with a real completion callback
                // still pending until it runs.
                fadeManager.startFadeToBlack(completions::incrementAndGet, 0, 0);
            }
            fadeManager.update();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 5);
        for (int i = 0; i < 40; i++) {
            rc.step();
        }
        assertEquals(1, completions.get(),
                "fixture assumption: the fade's completion callback has already fired by frame 40");

        int stepsTaken = 0;
        while (rc.stepBackward()) {
            stepsTaken++;
            assertFalse(stepsTaken > 100, "stepBackward should clamp, not loop forever");
        }

        // Frames [10, 30] are poisoned (mid fade-to-black with the completion
        // callback still pending); frame 31 is the first frame after the
        // callback ran. Scrubbing backward from frame 40 must clamp at 31 --
        // the last good frame reachable without landing on a poisoned one --
        // rather than skip over the poisoned span to reach frame 9.
        assertEquals(31, rc.currentFrame(),
                "held rewind must clamp at the last good frame before the poisoned fade window");

        // Release resumes cleanly: stepping forward again must not throw or
        // leave FadeManager stuck, and must not re-run the (already-fired)
        // completion callback.
        assertDoesNotThrow(rc::step);
        assertEquals(32, rc.currentFrame());
        assertEquals(1, completions.get(),
                "resuming forward from the clamped frame must not re-fire the fade completion callback");
    }

    @Test
    void cachedPoisonedTargetDoesNotRepeatFloorCaptureRestoreOrReplay() {
        AtomicInteger state = new AtomicInteger();
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger steps = new AtomicInteger();
        RewindRegistry reg = new RewindRegistry();
        reg.register(new RewindSnapshottable<FadeManagerSnapshot>() {
            @Override public String key() { return "fademanager"; }
            @Override public FadeManagerSnapshot capture() {
                captures.incrementAndGet();
                return fadeSnapshot(state.get(), state.get() == 6);
            }
            @Override public void restore(FadeManagerSnapshot snapshot) {
                restores.incrementAndGet();
                state.set(snapshot.frameCount());
            }
        });
        CountingKeyframeStore keyframes = new CountingKeyframeStore();
        RewindController rc = new RewindController(
                reg, keyframes, new FakeInputSource(30), in -> {
                    steps.incrementAndGet();
                    state.incrementAndGet();
                    return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
                }, 5);
        for (int i = 0; i < 7; i++) rc.step();

        assertFalse(rc.stepBackward(), "cold expansion discovers and rejects poisoned frame 6");
        assertEquals(7, rc.currentFrame());
        assertEquals(7, state.get(), "cold rejection rolls live state back");
        int floorCount = keyframes.floorLookups.get();
        captures.set(0);
        restores.set(0);
        int stepCount = steps.get();

        assertFalse(rc.stepBackward(), "cached poisoned target remains a clamp");

        assertEquals(floorCount, keyframes.floorLookups.get());
        assertEquals(0, captures.get());
        assertEquals(0, restores.get());
        assertEquals(stepCount, steps.get());
        assertEquals(7, rc.currentFrame());
        assertEquals(7, state.get());
    }

    @Test
    void resetBufferAtBoundaryMakesCurrentFrameEarliestAvailable() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(100);
        EngineStepper stepper = (in) -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME;

        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 10);
        for (int i = 0; i < 25; i++) rc.step();

        rc.resetBufferAtCurrentFrame();

        assertEquals(25, rc.earliestAvailableFrame());
        assertFalse(rc.stepBackward(), "level and act boundaries must not rewind into the previous buffer");
    }

    @Test
    void recordExternalStepAdvancesWithoutInvokingStepper() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(10);
        AtomicInteger stepInvocations = new AtomicInteger();

        RewindController rc = new RewindController(
                reg,
                keyframes,
                inputs,
                in -> {
                    stepInvocations.incrementAndGet();
                    return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
                },
                3);

        assertTrue(rc.recordExternalStep());
        assertEquals(1, rc.currentFrame());
        assertEquals(0, stepInvocations.get(),
                "external recording must not recurse through the engine stepper");
    }

    @Test
    void recordExternalStepCapturesKeyframesAtIntervals() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(10);

        RewindController rc = new RewindController(
                reg, keyframes, inputs,
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME, 3);

        assertTrue(rc.recordExternalStep());
        assertTrue(rc.recordExternalStep());
        assertTrue(rc.recordExternalStep());

        assertEquals(3, rc.currentFrame());
        assertTrue(keyframes.latestAtOrBefore(3).isPresent(),
                "externally advanced visual playback still needs periodic rewind keyframes");
    }

    @Test
    void pruneHistoryAlignsToEarlierKeyframeBoundary() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(200);

        RewindController rc = new RewindController(
                reg, keyframes, inputs,
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME, 10);
        for (int i = 0; i < 35; i++) {
            rc.step();
        }

        int earliest = rc.pruneHistoryToRetainFrames(12);

        assertEquals(20, earliest,
                "retention must keep the keyframe at or before the requested horizon");
        assertTrue(keyframes.latestAtOrBefore(19).isEmpty());
        assertEquals(20, keyframes.latestAtOrBefore(20).orElseThrow().frame());
        assertEquals(30, keyframes.latestAtOrBefore(99).orElseThrow().frame());
    }

    @Test
    void pruneHistoryDoesNotDiscardWhenWithinRetentionWindow() {
        RewindRegistry reg = new RewindRegistry();
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(20);

        RewindController rc = new RewindController(
                reg, keyframes, inputs,
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME, 10);
        for (int i = 0; i < 5; i++) {
            rc.step();
        }

        assertEquals(0, rc.pruneHistoryToRetainFrames(60));
        assertEquals(0, keyframes.earliestFrame());
    }

    @Test
    void intermediateCheckpointsBoundColdReplayAcrossHistoryAndBranches() {
        RewindRegistry registry = new RewindRegistry();
        AtomicInteger state = new AtomicInteger();
        AtomicInteger steps = new AtomicInteger();
        registry.register(new RewindSnapshottable<Integer>() {
            public String key() { return "counter"; }
            public Integer capture() { return state.get(); }
            public void restore(Integer value) { state.set(value); }
        });
        RewindController controller = new RewindController(registry,
                new InMemoryKeyframeStore(), new FakeInputSource(300), in -> {
                    state.incrementAndGet();
                    steps.incrementAndGet();
                    return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
                }, 60);
        controller.setGameplayCheckpointInterval(10);
        for (int frame = 1; frame <= 180; frame++) {
            state.incrementAndGet();
            assertTrue(controller.recordExternalStep());
        }
        assertEquals(50, controller.pruneHistoryToRetainFrames(125));
        for (int frame = 179; frame >= 50; frame--) {
            int before = steps.get();
            assertTrue(controller.stepBackward());
            assertTrue(steps.get() - before <= 9, "cold replay exceeds nine ticks at " + frame);
            assertEquals(frame, state.get());
        }
        assertFalse(controller.stepBackward());
        // A new future and an unaligned boundary must not resurrect cached state.
        for (int frame = 51; frame <= 67; frame++) controller.step();
        controller.resetBufferAtCurrentFrame();
        for (int frame = 68; frame <= 81; frame++) controller.step();
        for (int frame = 80; frame >= 67; frame--) {
            int before = steps.get();
            assertTrue(controller.stepBackward());
            assertTrue(steps.get() - before <= 9);
            assertEquals(frame, state.get());
        }
        assertFalse(controller.stepBackward());
    }

    private static class FakeInputSource implements InputSource {
        private final int count;

        FakeInputSource(int count) {
            this.count = count;
        }

        @Override
        public int frameCount() {
            return count;
        }

        @Override
        public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "fake");
        }
    }

    private static final class SeekAwareStepper implements RewindSeekAwareEngineStepper {
        int restoredFrame = -1;
        int restoredInputFrame = -1;
        int firstRestoredFrame = -1;
        int firstRestoredInputFrame = -1;
        final List<Integer> steppedFrames = new ArrayList<>();

        @Override
        public void restoreToFrame(int frame, Bk2FrameInput inputAtFrame) {
            if (firstRestoredFrame < 0) {
                firstRestoredFrame = frame;
                firstRestoredInputFrame = inputAtFrame.frameIndex();
            }
            restoredFrame = frame;
            restoredInputFrame = inputAtFrame.frameIndex();
        }

        @Override
        public com.openggf.LevelFrameResult step(Bk2FrameInput inputs) {
            steppedFrames.add(inputs.frameIndex());
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        }
    }

    private static final class CountingKeyframeStore implements KeyframeStore {
        private final InMemoryKeyframeStore delegate = new InMemoryKeyframeStore();
        private final AtomicInteger floorLookups = new AtomicInteger();

        @Override public void put(int frame, CompositeSnapshot snapshot) { delegate.put(frame, snapshot); }
        @Override public Optional<Entry> latestAtOrBefore(int frame) {
            floorLookups.incrementAndGet();
            return delegate.latestAtOrBefore(frame);
        }
        @Override public int earliestFrame() { return delegate.earliestFrame(); }
        @Override public void clear() { delegate.clear(); }
        @Override public void discardAfter(int frame) { delegate.discardAfter(frame); }
        @Override public void discardBefore(int frame) { delegate.discardBefore(frame); }
    }

    private static FadeManagerSnapshot fadeSnapshot(int frame, boolean poisoned) {
        return new FadeManagerSnapshot(
                poisoned ? FadeManager.FadeState.FADING_TO_BLACK : FadeManager.FadeState.NONE,
                frame, 0, 0, 0, 0, FadeManager.FadeType.BLACK,
                0, 0, 1, 1, 1, false, poisoned);
    }
}
