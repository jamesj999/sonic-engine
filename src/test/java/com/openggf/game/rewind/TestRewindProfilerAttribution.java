package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindProfilerAttribution {

    private static CompositeSnapshot snap(int marker) {
        var e = new LinkedHashMap<String, Object>();
        e.put("k", marker);
        return new CompositeSnapshot(e);
    }

    @Test
    void registryRestoreWrapsInRewindRestoreSection() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return 1; }
            @Override public void restore(Integer s) { }
        });

        reg.restore(snap(42));

        List<String> transcript = prof.transcript();
        assertEquals(List.of("begin:rewind.restore", "end:rewind.restore"), transcript,
                "Expected exactly one balanced rewind.restore pair: " + transcript);
        assertNull(prof.activeSection(), "No section should be active after restore");
    }

    @Test
    void stepBackwardEmitsExpectedSectionsAndStaysBalanced() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();
        EngineStepper stepper = (in) -> {
            state.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, stepper, 5, null, prof);

        for (int i = 0; i < 7; i++) rc.step();
        prof.clearTranscript(); // Drop capture noise from forward stepping.

        boolean stepped = rc.stepBackward();
        assertTrue(stepped);

        List<String> beginsInOrder = prof.beginNames();
        assertTrue(beginsInOrder.contains("rewind.step"),
                "Expected rewind.step in begin order: " + beginsInOrder);
        assertTrue(beginsInOrder.contains("rewind.tick"),
                "Expected rewind.tick (cold-segment expansion): " + beginsInOrder);
        assertTrue(beginsInOrder.contains("rewind.restore"),
                "Expected rewind.restore: " + beginsInOrder);
        assertTrue(beginsInOrder.indexOf("rewind.step") < beginsInOrder.indexOf("rewind.tick"),
                "rewind.step must open before rewind.tick: " + beginsInOrder);
        assertNull(prof.activeSection(),
                "No section should be active after stepBackward: transcript=" + prof.transcript());
    }

    @Test
    void warmStepBackwardHitHasNoReplayTickOrCaptureSection() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        AtomicInteger state = new AtomicInteger();
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer snapshot) { state.set(snapshot); }
        });
        RewindController rc = new RewindController(
                reg, new InMemoryKeyframeStore(), new FakeInputSource(120),
                in -> {
                    state.incrementAndGet();
                    return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
                }, 5, null, prof);
        for (int i = 0; i < 7; i++) rc.step();
        rc.stepBackward();
        prof.clearTranscript();

        assertTrue(rc.stepBackward());

        List<String> begins = prof.beginNames();
        assertEquals(List.of("rewind.step", "rewind.restore"), begins,
                "warm hit should only bracket the step and committed target restore");
        assertNull(prof.activeSection());
    }

    @Test
    void stepBackwardAttributesKeyframeRestorePrimerToRewindStep() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();
        EngineStepper stepper = (in) -> {
            state.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, stepper, 5, null, prof);

        for (int i = 0; i < 7; i++) rc.step();
        prof.clearTranscript();

        rc.stepBackward();

        // After registry.restore inside the keyframe-restore lambda, the section is
        // closed. Before rewind.tick opens, primeStepperAtFrame runs — that work
        // must credit to rewind.step, not fall into the unattributed gap.
        List<String> transcript = prof.transcript();
        int firstRestoreEnd = transcript.indexOf("end:rewind.restore");
        int firstReplayBegin = transcript.indexOf("begin:rewind.tick");
        assertTrue(firstRestoreEnd >= 0, "Expected first end:rewind.restore: " + transcript);
        assertTrue(firstReplayBegin > firstRestoreEnd,
                "Expected begin:rewind.tick after first end:rewind.restore: " + transcript);
        List<String> gap = transcript.subList(firstRestoreEnd + 1, firstReplayBegin);
        assertTrue(gap.contains("begin:rewind.step"),
                "Expected begin:rewind.step between keyframe-restore close and replay open "
                        + "(primer work attribution gap): gap=" + gap + " transcript=" + transcript);
    }

    @Test
    void stepBackwardLeavesProfilerCleanWhenStepperThrows() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();

        // Stepper throws on the first invocation after poisoned=true. The lambda opens
        // rewind.tick BEFORE calling engineStepper.step, so the guard assertion that
        // begin:rewind.tick appears in the transcript is satisfied even on the first throw.
        final boolean[] poisoned = { false };
        EngineStepper throwingStepper = (in) -> {
            if (poisoned[0]) {
                throw new RuntimeException("simulated stepper failure");
            }
            state.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, throwingStepper, 5, null, prof);

        for (int i = 0; i < 12; i++) rc.step();
        prof.clearTranscript();
        poisoned[0] = true;

        assertThrows(RuntimeException.class, rc::stepBackward,
                "Expected stepBackward to propagate the stepper's exception");
        assertEquals(12, rc.currentFrame(), "failed expansion must preserve the rewind cursor");
        assertEquals(12, state.get(), "failed expansion must roll live registry state back");
        List<String> transcript = prof.transcript();
        // Guard: assert the instrumentation actually opened rewind.tick before the
        // throw. Without this, the test would pass trivially before Task 5 wires the
        // section — the stepper would throw without any section ever being opened,
        // leaving activeSection == null for the wrong reason.
        assertTrue(transcript.contains("begin:rewind.tick"),
                "Expected rewind.tick to have been opened before the throw: " + transcript);
        assertNull(prof.activeSection(),
                "Profiler must have no dangling active section after exception: transcript="
                        + transcript);

        poisoned[0] = false;
        prof.clearTranscript();
        assertTrue(rc.stepBackward(), "invalidated partial cache must retry from the keyframe");
        assertEquals(11, rc.currentFrame());
        assertEquals(11, state.get());
        assertNull(prof.activeSection(), "retry must also leave profiler sections balanced");
    }

    @Test
    void expansionFailureKeepsOriginalExceptionWhenRollbackAlsoFails() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        AtomicInteger state = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        AtomicBoolean failExpansion = new AtomicBoolean();
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer snapshot) {
                if (restores.incrementAndGet() == 2) {
                    throw new IllegalStateException("rollback failed");
                }
                state.set(snapshot);
            }
        });
        RewindController rc = new RewindController(
                reg, new InMemoryKeyframeStore(), new FakeInputSource(120), in -> {
                    if (failExpansion.get() && in.frameIndex() == 11) {
                        throw new RuntimeException("expansion failed");
                    }
                    state.incrementAndGet();
                    return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
                }, 5, null, prof);
        for (int i = 0; i < 12; i++) rc.step();
        failExpansion.set(true);
        prof.clearTranscript();

        RuntimeException failure = assertThrows(RuntimeException.class, rc::stepBackward);

        assertEquals("expansion failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("rollback failed", failure.getSuppressed()[0].getMessage());
        assertEquals(12, rc.currentFrame());
        assertNull(prof.activeSection());
    }

    @Test
    void expansionFailureReprimesSeekAwareStepperAtOriginalFrame() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        AtomicInteger state = new AtomicInteger();
        AtomicBoolean failExpansion = new AtomicBoolean();
        List<Integer> primedFrames = new java.util.ArrayList<>();
        RewindSeekAwareEngineStepper stepper = new RewindSeekAwareEngineStepper() {
            @Override public void restoreToFrame(int frame, Bk2FrameInput inputAtFrame) {
                primedFrames.add(frame);
            }

            @Override public com.openggf.LevelFrameResult step(Bk2FrameInput inputs) {
                if (failExpansion.get()) throw new RuntimeException("expansion failed");
                state.incrementAndGet();
                return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
            }
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer snapshot) { state.set(snapshot); }
        });
        RewindController rc = new RewindController(
                reg, new InMemoryKeyframeStore(), new FakeInputSource(120), stepper, 5, null, prof);
        for (int i = 0; i < 12; i++) rc.step();
        failExpansion.set(true);
        primedFrames.clear();

        assertThrows(RuntimeException.class, rc::stepBackward);

        assertEquals(List.of(10, 12), primedFrames,
                "failed replay primes its keyframe first, then restores the original cursor");
        assertEquals(12, state.get());
        assertEquals(12, rc.currentFrame());
        assertNull(prof.activeSection());
    }

    @Test
    void seekToEmitsExpectedSectionsAndStaysBalanced() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();
        EngineStepper stepper = (in) -> {
            state.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, stepper, 5, null, prof);

        for (int i = 0; i < 7; i++) rc.step();
        prof.clearTranscript();

        rc.seekTo(3);

        List<String> beginsInOrder = prof.beginNames();
        assertTrue(beginsInOrder.contains("rewind.seek"), "Expected rewind.seek: " + beginsInOrder);
        assertTrue(beginsInOrder.contains("rewind.tick"),
                "Expected rewind.tick (forward stepping in seek): " + beginsInOrder);
        assertTrue(beginsInOrder.contains("rewind.restore"),
                "Expected rewind.restore: " + beginsInOrder);
        assertTrue(beginsInOrder.indexOf("rewind.seek") < beginsInOrder.indexOf("rewind.tick"),
                "rewind.seek must open before rewind.tick: " + beginsInOrder);
        assertNull(prof.activeSection(),
                "No section should be active after seekTo: transcript=" + prof.transcript());

        // Stronger ordering: verify rewind.seek is re-opened AFTER rewind.restore.
        // Without this, both seek beginSection calls could be at the start and the
        // post-restore re-open could be missing, but the test above would still pass.
        List<String> transcript = prof.transcript();
        int firstSeekBegin = transcript.indexOf("begin:rewind.seek");
        int seekRestoreEnd = transcript.indexOf("end:rewind.restore");
        // Find second occurrence of begin:rewind.seek after firstSeekBegin.
        int secondSeekBegin = firstSeekBegin >= 0
                ? transcript.subList(firstSeekBegin + 1, transcript.size())
                             .indexOf("begin:rewind.seek")
                : -1;
        if (secondSeekBegin >= 0) secondSeekBegin += firstSeekBegin + 1;
        assertTrue(firstSeekBegin >= 0, "Expected first begin:rewind.seek: " + transcript);
        assertTrue(seekRestoreEnd > firstSeekBegin,
                "Expected end:rewind.restore after first begin:rewind.seek: " + transcript);
        assertTrue(secondSeekBegin > seekRestoreEnd,
                "Expected second begin:rewind.seek AFTER end:rewind.restore "
                        + "(re-open after registry.restore): " + transcript);
    }

    @Test
    void seekToLeavesProfilerCleanWhenStepperThrows() {
        RecordingSectionProfiler prof = new RecordingSectionProfiler();
        RewindRegistry reg = new RewindRegistry(prof);
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();

        // Stepper throws on the first invocation after poisoned=true. The seekTo loop
        // opens rewind.tick BEFORE calling engineStepper.step inside its try/finally,
        // so the guard assertion that begin:rewind.tick appears in the transcript is
        // satisfied even on the first throw.
        final boolean[] poisoned = { false };
        EngineStepper throwingStepper = (in) -> {
            if (poisoned[0]) {
                throw new RuntimeException("simulated seek stepper failure");
            }
            state.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        RewindController rc = new RewindController(
                reg, keyframes, inputs, throwingStepper, 5, null, prof);

        for (int i = 0; i < 7; i++) rc.step();
        prof.clearTranscript();
        poisoned[0] = true;

        // seekTo(4) forces a backward seek that crosses a keyframe boundary
        // (floor keyframe = 0), so the forward-replay loop runs at least once
        // and the poisoned stepper throws. seekTo(5) would short-circuit the
        // loop (target == floor frame) and never invoke the stepper.
        assertThrows(RuntimeException.class, () -> rc.seekTo(4),
                "Expected seekTo to propagate the stepper's exception");
        List<String> transcript = prof.transcript();
        // Guard: assert rewind.tick was actually opened before the throw, otherwise
        // this test would pass trivially before Task 6 wires the section.
        assertTrue(transcript.contains("begin:rewind.tick"),
                "Expected rewind.tick to have been opened before the throw: " + transcript);
        assertNull(prof.activeSection(),
                "Profiler must have no dangling active section after seek exception: transcript="
                        + transcript);
    }

    @Test
    void rewindControllerWorksWithoutProfiler() {
        RewindRegistry reg = new RewindRegistry(); // no profiler
        InMemoryKeyframeStore keyframes = new InMemoryKeyframeStore();
        InputSource inputs = new FakeInputSource(120);
        AtomicInteger state = new AtomicInteger();
        EngineStepper stepper = (in) -> {
            state.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
        reg.register(new RewindSnapshottable<Integer>() {
            @Override public String key() { return "k"; }
            @Override public Integer capture() { return state.get(); }
            @Override public void restore(Integer s) { state.set(s); }
        });

        // Five-arg constructor (no profiler, no audio manager).
        RewindController rc = new RewindController(reg, keyframes, inputs, stepper, 5);
        for (int i = 0; i < 7; i++) rc.step();
        assertEquals(7, rc.currentFrame(), "after 7 forward steps");
        assertEquals(7, state.get(), "stepper ran once per forward step");

        rc.seekTo(3);
        assertEquals(3, rc.currentFrame(), "after seekTo(3)");
        assertEquals(3, state.get(),
                "restored to keyframe 0 then replayed forward 3 frames");

        for (int i = 0; i < 2; i++) rc.stepBackward();
        // The controller must land on the expected logical frame and the
        // replayed state captured by the registry must match it — even with
        // no profiler wired in.
        assertEquals(1, rc.currentFrame(), "after two stepBackward calls");
        assertEquals(1, state.get(),
                "replayed engine state must match the rewound logical frame");
    }

    private static final class FakeInputSource implements InputSource {
        private final int count;
        FakeInputSource(int count) { this.count = count; }
        @Override public int frameCount() { return count; }
        @Override public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "fake");
        }
    }
}
