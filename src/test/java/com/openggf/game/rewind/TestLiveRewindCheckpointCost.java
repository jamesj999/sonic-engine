package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in measurement of the gameplay-only checkpoint tradeoff on recorded EHZ play. */
@RequiresRom(SonicGame.SONIC_2)
@EnabledIfSystemProperty(named = "openggf.checkpoint.measure", matches = "true")
class TestLiveRewindCheckpointCost {
    @Test
    void compareCheckpointCadencesOnTheSameRecordedRoute() throws Exception {
        for (int interval : new int[] {60, 10}) {
            try {
                measure(interval);
            } finally {
                TestEnvironment.resetAll();
            }
        }
    }

    private void measure(int interval) throws Exception {
        Path movie;
        try (var files = Files.list(Path.of("src/test/resources/traces/s2/ehz1_fullrun"))) {
            movie = files.filter(p -> p.toString().endsWith(".bk2")).findFirst().orElseThrow();
        }
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(movie).withZoneAndAct(0, 0).build();
        var registry = SessionManager.getCurrentGameplayMode().getRewindRegistry();
        assertNotNull(registry);
        var store = new InMemoryKeyframeStore();
        var inputs = new InputSource() {
            public int frameCount() { return 1801; }
            public Bk2FrameInput read(int frame) { throw new AssertionError("external recording must not replay input"); }
        };
        var controller = new RewindController(registry, store, inputs,
                input -> { throw new AssertionError("external recording must not step gameplay"); }, 60);
        controller.setGameplayCheckpointInterval(interval);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long allocated = 0;
        long nanos = 0;
        for (int frame = 1; frame <= 1800; frame++) {
            fixture.stepFrameFromRecording();
            long before = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            assertTrue(controller.recordExternalStep());
            long elapsed = System.nanoTime() - start;
            if (frame > 300) {
                nanos += elapsed;
                allocated += bean.getThreadAllocatedBytes(thread) - before;
            }
        }
        var seen = new IdentityHashMap<Object, Boolean>();
        long bytes = 0;
        int snapshots = 0;
        for (int frame = 0; frame <= 1800; frame += interval) {
            var entry = store.latestAtOrBefore(frame).orElseThrow();
            bytes += RewindBenchmark.estimateStructuralSizeShared(entry.snapshot(), seen);
            snapshots++;
        }
        System.out.printf("CHECKPOINT_COST interval=%d measuredFrames=1500 checkpointNanosPerFrame=%d "
                        + "checkpointBytesPerFrame=%d retainedSnapshots=%d estimatedRetainedBytes=%d%n",
                interval, nanos / 1500, allocated / 1500, snapshots, bytes);
    }
}
