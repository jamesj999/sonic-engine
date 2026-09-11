package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotMachinePanelAllocation {
    private static final int WARMUP_OPERATIONS = 10_000;
    private static final int MEASURED_OPERATIONS = 250_000;
    private static final int MEASURED_BATCHES = 7;

    @Test
    void liveRuntimePanelSyncAllocatesNothingAfterWarmup() throws Exception {
        ThreadMXBean bean = allocationBeanOrSkip();
        Scenario[] scenarios = scenarios();
        S3kSlotBonusStageRuntime[] runtimes = new S3kSlotBonusStageRuntime[scenarios.length];
        S3kSlotMachinePanelAnimator[] animators = new S3kSlotMachinePanelAnimator[scenarios.length];
        for (int i = 0; i < scenarios.length; i++) {
            runtimes[i] = runtimeWithState(scenarios[i].state());
            animators[i] = preparedAnimator(
                    scenarios[i].faces(), scenarios[i].nextFaces(), scenarios[i].offsetPixels());
        }

        for (int i = 0; i < WARMUP_OPERATIONS; i++) {
            syncAll(runtimes, animators);
        }

        long[] allocatedBytes = new long[MEASURED_BATCHES];
        long[] elapsedNanos = new long[MEASURED_BATCHES];
        long threadId = Thread.currentThread().threadId();
        for (int batch = 0; batch < MEASURED_BATCHES; batch++) {
            long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
            long started = System.nanoTime();
            for (int i = 0; i < MEASURED_OPERATIONS; i++) {
                syncAll(runtimes, animators);
            }
            elapsedNanos[batch] = System.nanoTime() - started;
            allocatedBytes[batch] = bean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        }

        System.out.printf("slot-panel-live allocations=%s nanos=%s%n",
                Arrays.toString(allocatedBytes), Arrays.toString(elapsedNanos));
        assertEquals(0L, median(allocatedBytes),
                "warmed fallback, spinning, and resolved live synchronization must be allocation-free "
                        + "at the seven-batch median");
    }

    @Test
    void liveRuntimeScalarOutputMatchesImmutableSnapshotsAndPanelPixels() throws Exception {
        byte[][] facePixels = patternedFaces();
        EngineContext previousServices = EngineServices.current();
        try {
            for (Scenario scenario : scenarios()) {
                S3kSlotBonusStageRuntime runtime = runtimeWithState(scenario.state());
                S3kSlotMachineDisplayState compatibility = runtime.slotMachineDisplayStateForTest();
                assertArrayEquals(scenario.faces(), compatibility.faces(), scenario.name());
                assertArrayEquals(scenario.nextFaces(), compatibility.nextFaces(), scenario.name());
                assertArrayEquals(scenario.offsetPixels(),
                        offsetPixels(compatibility.offsets()), scenario.name());

                S3kSlotMachinePanelAnimator animator = initializedAnimator(facePixels);
                RecordingGraphicsManager graphics = new RecordingGraphicsManager();
                EngineServices.configure(withGraphics(previousServices, graphics));
                runtime.syncSlotMachinePanel(animator);

                assertArrayEquals(scenario.faces(), intArrayField(animator, "lastFaces"), scenario.name());
                assertArrayEquals(scenario.nextFaces(), intArrayField(animator, "lastNextFaces"), scenario.name());
                assertArrayEquals(scenario.offsetPixels(),
                        intArrayField(animator, "lastOffsetPixels"), scenario.name());
                assertEquals(48, graphics.uploadedPatterns.size(), scenario.name());
                for (int reel = 0; reel < 3; reel++) {
                    assertEquals(scenario.reelChecksums()[reel],
                            checksum(graphics.uploadedPatterns.subList(reel * 16, reel * 16 + 16)),
                            scenario.name() + " reel " + reel);
                    for (int pattern = 0; pattern < 16; pattern++) {
                        assertEquals(0x200 + reel * 0x10 + pattern,
                                graphics.uploadedIds.get(reel * 16 + pattern),
                                scenario.name() + " atlas order");
                    }
                }
            }
        } finally {
            EngineServices.configure(previousServices);
        }
    }

    @Test
    void visiblePanelPixelsStayExactAtOffsetsZeroOneAndThirtyOne() {
        byte[][] faces = patternedFaces();

        assertEquals(0xC4D8B305L, checksum(
                S3kSlotMachinePanelAnimator.buildVisibleWindowPatternsForTest(
                        faces, 2, 3, 0f)));
        assertEquals(0x8728FBDDL, checksum(
                S3kSlotMachinePanelAnimator.buildVisibleWindowPatternsForTest(
                        faces, 2, 3, 1 / 32f)));
        assertEquals(0xB272E985L, checksum(
                S3kSlotMachinePanelAnimator.buildVisibleWindowPatternsForTest(
                        faces, 2, 3, 31 / 32f)));
    }

    private static S3kSlotMachinePanelAnimator preparedAnimator(
            int[] faces, int[] nextFaces, int[] offsetPixels) throws Exception {
        S3kSlotMachinePanelAnimator animator = new S3kSlotMachinePanelAnimator();
        setField(animator, "initialized", true);
        System.arraycopy(faces, 0, intArrayField(animator, "lastFaces"), 0, 3);
        System.arraycopy(nextFaces, 0, intArrayField(animator, "lastNextFaces"), 0, 3);
        System.arraycopy(offsetPixels, 0, intArrayField(animator, "lastOffsetPixels"), 0, 3);
        return animator;
    }

    private static S3kSlotMachinePanelAnimator initializedAnimator(byte[][] faces) throws Exception {
        S3kSlotMachinePanelAnimator animator = new S3kSlotMachinePanelAnimator();
        setField(animator, "initialized", true);
        Field field = S3kSlotMachinePanelAnimator.class.getDeclaredField("facePixels");
        field.setAccessible(true);
        byte[][] target = (byte[][]) field.get(animator);
        for (int i = 0; i < faces.length; i++) {
            System.arraycopy(faces[i], 0, target[i], 0, faces[i].length);
        }
        return animator;
    }

    private static S3kSlotBonusStageRuntime runtimeWithState(S3kSlotStageState state) throws Exception {
        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        Field field = S3kSlotBonusStageRuntime.class.getDeclaredField("slotStageState");
        field.setAccessible(true);
        field.set(runtime, state);
        return runtime;
    }

    private static void syncAll(
            S3kSlotBonusStageRuntime[] runtimes, S3kSlotMachinePanelAnimator[] animators) {
        for (int i = 0; i < runtimes.length; i++) {
            runtimes[i].syncSlotMachinePanel(animators[i]);
        }
    }

    private static Scenario[] scenarios() {
        S3kSlotStageState fallback = S3kSlotStageState.bootstrap();
        fallback.setOptionCycleDisplaySymbols(3, 4, 6);
        fallback.setOptionCycleOffsets(0, 8, 248);

        S3kSlotStageState spinning = S3kSlotStageState.bootstrap();
        spinning.setOptionCycleReelWords(0x00F8, 0x0108, 0x0200);

        S3kSlotStageState resolved = S3kSlotStageState.bootstrap();
        resolved.setOptionCycleState(0x18);
        resolved.setOptionCycleLastPrize(0);
        resolved.setOptionCycleTargetPackedBC(0x21);
        resolved.setOptionCycleTargetReelA(5);

        return new Scenario[] {
                new Scenario("fallback",
                        fallback,
                        new int[] {3, 4, 6},
                        new int[] {0, 6, 3},
                        new int[] {0, 1, 31},
                        new long[] {0x5D4B50E0L, 0x66CA6B04L, 0x192B485FL}),
                new Scenario("spinning",
                        spinning,
                        new int[] {0, 1, 2},
                        new int[] {1, 2, 5},
                        new int[] {31, 1, 0},
                        new long[] {0x0B15D3DEL, 0xD3F86B28L, 0xC4D8B305L}),
                new Scenario("resolved",
                        resolved,
                        new int[] {1, 2, 5},
                        new int[] {2, 5, 4},
                        new int[] {0, 0, 0},
                        new long[] {0x647AAC18L, 0xC4D8B305L, 0xF6DC8FD7L})
        };
    }

    private static int[] offsetPixels(float[] offsets) {
        return new int[] {
                Math.floorMod(Math.round(offsets[0] * 32), 32),
                Math.floorMod(Math.round(offsets[1] * 32), 32),
                Math.floorMod(Math.round(offsets[2] * 32), 32)
        };
    }

    private static int[] intArrayField(S3kSlotMachinePanelAnimator animator, String name) throws Exception {
        Field field = S3kSlotMachinePanelAnimator.class.getDeclaredField(name);
        field.setAccessible(true);
        return (int[]) field.get(animator);
    }

    private static void setField(S3kSlotMachinePanelAnimator animator, String name, boolean value) throws Exception {
        Field field = S3kSlotMachinePanelAnimator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(animator, value);
    }

    private static byte[][] patternedFaces() {
        byte[][] faces = new byte[8][32 * 32];
        for (int face = 0; face < faces.length; face++) {
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    faces[face][y * 32 + x] = (byte) ((face * 3 + x + y * 5) & 0x0F);
                }
            }
        }
        return faces;
    }

    private static long checksum(Pattern[] patterns) {
        return checksum(Arrays.asList(patterns));
    }

    private static long checksum(List<Pattern> patterns) {
        CRC32 checksum = new CRC32();
        for (Pattern pattern : patterns) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    checksum.update(pattern.getPixel(x, y));
                }
            }
        }
        return checksum.getValue();
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        ThreadMXBean bean = (ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        assertTrue(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) >= 0,
                "current-thread allocation reads unavailable");
        return bean;
    }

    private static EngineContext withGraphics(EngineContext base, GraphicsManager graphics) {
        return new EngineContext(
                base.configuration(),
                graphics,
                base.audio(),
                base.roms(),
                base.profiler(),
                base.debugOverlay(),
                base.playbackDebug(),
                base.romDetection(),
                base.crossGameFeatures());
    }

    private static final class RecordingGraphicsManager extends GraphicsManager {
        private final List<Pattern> uploadedPatterns = new ArrayList<>();
        private final List<Integer> uploadedIds = new ArrayList<>();

        @Override
        public void updatePatternTexture(Pattern pattern, int patternId) {
            uploadedPatterns.add(pattern);
            uploadedIds.add(patternId);
        }
    }

    private record Scenario(
            String name,
            S3kSlotStageState state,
            int[] faces,
            int[] nextFaces,
            int[] offsetPixels,
            long[] reelChecksums) {
    }
}
