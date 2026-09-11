package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRewindDeterminismAuditor {

    /** Fully captured counter subsystem: replay is deterministic. */
    private static final class CapturedCounter implements RewindSnapshottable<Integer> {
        int value;

        @Override public String key() { return "counter"; }
        @Override public Integer capture() { return value; }
        @Override public void restore(Integer snapshot) { value = snapshot; }
    }

    /** Subsystem with a hidden field the snapshot misses: replay diverges. */
    private static final class LeakyCounter implements RewindSnapshottable<Integer> {
        int value;
        int hidden; // NOT captured - models out-of-snapshot state

        @Override public String key() { return "leaky"; }
        @Override public Integer capture() { return value; }
        @Override public void restore(Integer snapshot) { value = snapshot; }
    }

    private static final class ScriptedInputs implements InputSource {
        private final int frames;

        ScriptedInputs(int frames) { this.frames = frames; }

        @Override public int frameCount() { return frames; }
        @Override public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "");
        }
    }

    private RewindController build(RewindRegistry registry, EngineStepper stepper, int interval) {
        return new RewindController(registry, new InMemoryKeyframeStore(),
                new ScriptedInputs(1000), stepper, interval);
    }

    @Test
    public void deterministicSubsystemProducesNoDivergence() {
        CapturedCounter counter = new CapturedCounter();
        RewindRegistry registry = new RewindRegistry(null);
        registry.register(counter);
        EngineStepper stepper = in -> {
            counter.value = counter.value * 31 + in.frameIndex();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        List<String> reports = new ArrayList<>();
        RewindDeterminismAuditor auditor = new RewindDeterminismAuditor(reports::add);
        RewindController controller = build(registry, stepper, 4);
        controller.setDeterminismAuditor(auditor);

        for (int f = 1; f <= 12; f++) {
            stepper.step(new Bk2FrameInput(f, 0, 0, false, ""));
            controller.recordExternalStep();
        }
        assertEquals(0, auditor.divergentSegmentCount());
        assertTrue(reports.isEmpty());
    }

    @Test
    public void hiddenStateProducesDivergenceReport() {
        LeakyCounter leaky = new LeakyCounter();
        RewindRegistry registry = new RewindRegistry(null);
        registry.register(leaky);
        EngineStepper stepper = in -> {
            leaky.value += leaky.hidden;
            leaky.hidden++;
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        List<String> reports = new ArrayList<>();
        RewindDeterminismAuditor auditor = new RewindDeterminismAuditor(reports::add);
        RewindController controller = build(registry, stepper, 4);
        controller.setDeterminismAuditor(auditor);

        for (int f = 1; f <= 4; f++) {
            stepper.step(new Bk2FrameInput(f, 0, 0, false, ""));
            controller.recordExternalStep();
        }
        assertEquals(6, leaky.value,
                "audit replay must restore the registered live snapshot after detecting divergence");

        for (int f = 5; f <= 8; f++) {
            stepper.step(new Bk2FrameInput(f, 0, 0, false, ""));
            controller.recordExternalStep();
        }
        assertEquals(1, auditor.divergentSegmentCount(),
                "first divergent segment must be detected, then the auditor disarmed");
        assertTrue(reports.stream().anyMatch(r -> r.contains("leaky")),
                "report should name the divergent subsystem key");
    }

    @Test
    public void replayOnlyKeysAreReported() {
        var liveEntries = new LinkedHashMap<String, Object>();
        liveEntries.put("counter", 7);
        var replayedEntries = new LinkedHashMap<String, Object>();
        replayedEntries.put("counter", 7);
        replayedEntries.put("replay-only", 9);

        List<String> reports = new ArrayList<>();
        RewindDeterminismAuditor auditor = new RewindDeterminismAuditor(reports::add);

        assertTrue(auditor.report(4, 8,
                new CompositeSnapshot(liveEntries),
                new CompositeSnapshot(replayedEntries)));
        assertTrue(reports.stream().anyMatch(r -> r.contains("replay-only")),
                "report should name keys that exist only in replayed snapshots");
    }

    @Test
    public void auditExceptionsRestoreLiveStateAndDisarm() {
        LeakyCounter leaky = new LeakyCounter();
        RewindRegistry registry = new RewindRegistry(null);
        registry.register(leaky);
        EngineStepper stepper = in -> {
            leaky.value += leaky.hidden;
            leaky.hidden++;
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        AtomicInteger reports = new AtomicInteger();
        RewindDeterminismAuditor auditor = new RewindDeterminismAuditor(report -> {
            reports.incrementAndGet();
            throw new RuntimeException("report sink unavailable");
        });
        RewindController controller = build(registry, stepper, 4);
        controller.setDeterminismAuditor(auditor);

        for (int f = 1; f <= 4; f++) {
            stepper.step(new Bk2FrameInput(f, 0, 0, false, ""));
            assertDoesNotThrow(controller::recordExternalStep,
                    "audit replay/report failure must not escape live frame recording");
        }
        assertEquals(6, leaky.value,
                "registered live snapshot state must be restored even when audit reporting throws");
        assertEquals(1, reports.get(), "first audited boundary should attempt one report");

        for (int f = 5; f <= 8; f++) {
            stepper.step(new Bk2FrameInput(f, 0, 0, false, ""));
            assertDoesNotThrow(controller::recordExternalStep,
                    "auditor should stay disarmed after the first audit exception");
        }
        assertEquals(1, reports.get(),
                "disarmed auditor must not retry the broken reporter at the next keyframe boundary");
    }
}
