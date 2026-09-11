package com.openggf.game.sonic2.specialstage;

import com.openggf.game.session.EngineContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static com.openggf.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;
import com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;

/**
 * Unit tests for Sonic2SpecialStageManager.
 * These tests don't require the ROM file.
 */
public class Sonic2SpecialStageManagerTest {

    private static final Logger MANAGER_LOGGER =
            Logger.getLogger(Sonic2SpecialStageManager.class.getName());

    @Test
    void disabledFineDiagnosticsDoNotReadTheWallClock() {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        CountingDiagnosticClock clock = new CountingDiagnosticClock();
        manager.setDiagnosticClockForTesting(clock);
        manager.setFineDiagnosticsOverrideForTesting(false);

        for (int update = 0; update < 180; update++) {
            manager.runTimingDiagnosticsForTesting(update % 5 == 0);
        }

        assertEquals(0, clock.nanoTimeReads,
                "Disabled diagnostics must not sample the high-resolution clock");
        assertEquals(0, clock.currentTimeMillisReads,
                "Disabled diagnostics must not sample the wall clock");
    }

    @Test
    void timingDiagnosticsDoNotChangeComparisonState() {
        Sonic2SpecialStageManager enabled = new Sonic2SpecialStageManager();
        CountingDiagnosticClock enabledClock = new CountingDiagnosticClock();
        enabled.setDiagnosticClockForTesting(enabledClock);
        enabled.setFineDiagnosticsOverrideForTesting(true);

        Sonic2SpecialStageManager disabled = new Sonic2SpecialStageManager();
        CountingDiagnosticClock disabledClock = new CountingDiagnosticClock();
        disabled.setDiagnosticClockForTesting(disabledClock);
        disabled.setFineDiagnosticsOverrideForTesting(false);

        for (int update = 0; update < 180; update++) {
            boolean trackAdvanced = update % 5 == 0;
            enabled.runTimingDiagnosticsForTesting(trackAdvanced);
            disabled.runTimingDiagnosticsForTesting(trackAdvanced);
        }

        assertTrue(enabledClock.nanoTimeReads > 0,
                "Enabled diagnostics should exercise the injected clock seam");
        assertTrue(enabledClock.currentTimeMillisReads > 0,
                "Enabled diagnostics should exercise wall-clock reporting");
        assertEquals(disabled.captureComparisonState(), enabled.captureComparisonState(),
                "Timing diagnostics must not alter gameplay comparison state");
    }

    @Test
    void runtimeFineLoggingTransitionStartsAFreshDiagnosticEpoch() {
        Level previousLevel = MANAGER_LOGGER.getLevel();
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        CountingDiagnosticClock clock = new CountingDiagnosticClock();
        clock.configure(100L, 1_000L, 10L, 10L);
        manager.setDiagnosticClockForTesting(clock);

        try {
            MANAGER_LOGGER.setLevel(Level.FINE);
            manager.runTimingDiagnosticsForTesting(true);

            MANAGER_LOGGER.setLevel(Level.INFO);
            clock.configure(5_000_000_000L, 10_000L, 10L, 10L);
            manager.runTimingDiagnosticsForTesting(true);

            MANAGER_LOGGER.setLevel(Level.FINE);
            manager.runTimingDiagnosticsForTesting(true);

            Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
            assertEquals(0, snapshot.frameSampleCount,
                    "The first sample after re-enabling diagnostics must start a fresh FPS epoch");
            assertEquals(0L, snapshot.frameSampleSum);
            assertEquals(1, snapshot.diagnosticUpdateCount,
                    "The first enabled update must not inherit counters from the prior epoch");
            assertEquals(1, snapshot.diagnosticTrackAdvances);
            assertEquals(10_000L, snapshot.diagnosticWallStartTime,
                    "The wall-time epoch must restart at the re-enable boundary");
        } finally {
            MANAGER_LOGGER.setLevel(previousLevel);
        }
    }

    @Test
    void resetAndReinitializationClearDiagnosticTimingState() {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        CountingDiagnosticClock clock = new CountingDiagnosticClock();
        clock.configure(100L, 1_000L, 10L, 10L);
        manager.setDiagnosticClockForTesting(clock);
        manager.setFineDiagnosticsOverrideForTesting(true);

        manager.runTimingDiagnosticsForTesting(true);
        manager.runTimingDiagnosticsForTesting(false);
        assertTrue(manager.captureRewindSnapshot().lastFrameTime > 0);

        manager.reset();
        assertDiagnosticEpochCleared(manager);

        clock.configure(9_000_000_000L, 20_000L, 10L, 10L);
        manager.runTimingDiagnosticsForTesting(true);
        Sonic2SpecialStageSnapshot restarted = manager.captureRewindSnapshot();
        assertEquals(0, restarted.frameSampleCount,
                "The first post-reset sample must establish a new FPS epoch");
        assertEquals(1, restarted.diagnosticUpdateCount);

        manager.runTimingDiagnosticsForTesting(false);
        manager.prepareForInitialization();
        assertDiagnosticEpochCleared(manager);
    }

    @Test
    void fiveSecondDiagnosticBranchReportsWithoutInfiniteFps() {
        Level previousLevel = MANAGER_LOGGER.getLevel();
        boolean previousUseParentHandlers = MANAGER_LOGGER.getUseParentHandlers();
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);

        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        CountingDiagnosticClock clock = new CountingDiagnosticClock();
        clock.configure(1_000L, 1_000L, 0L, 100L);
        manager.setDiagnosticClockForTesting(clock);
        manager.setFineDiagnosticsOverrideForTesting(true);

        try {
            MANAGER_LOGGER.setLevel(Level.FINE);
            MANAGER_LOGGER.setUseParentHandlers(false);
            MANAGER_LOGGER.addHandler(handler);
            for (int update = 0; update < 61; update++) {
                manager.runTimingDiagnosticsForTesting(update % 5 == 0);
            }
        } finally {
            MANAGER_LOGGER.removeHandler(handler);
            MANAGER_LOGGER.setUseParentHandlers(previousUseParentHandlers);
            MANAGER_LOGGER.setLevel(previousLevel);
        }

        assertTrue(messages.stream().anyMatch(message -> message.startsWith("DIAGNOSTIC:")),
                "A deterministic five-second epoch must exercise the periodic report branch");
        assertFalse(messages.stream().anyMatch(message -> message.contains("Infinity")),
                "Non-positive frame intervals must not produce an infinite FPS diagnostic");
    }

    @Test
    void diagnosticsGateCoversNormalAndAlignmentUpdatePaths() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();
        setField(manager, "initialized", true);
        setField(manager, "trackAnimator", animator);
        CountingDiagnosticClock clock = new CountingDiagnosticClock();
        manager.setDiagnosticClockForTesting(clock);
        manager.setFineDiagnosticsOverrideForTesting(false);
        manager.update();
        assertEquals(0, clock.nanoTimeReads,
                "The normal update path must honor the disabled diagnostics gate");

        manager.setFineDiagnosticsOverrideForTesting(true);
        manager.update();
        int normalNanoReads = clock.nanoTimeReads;
        int normalWallReads = clock.currentTimeMillisReads;
        assertTrue(normalNanoReads > 0);
        assertTrue(normalWallReads > 0);

        setField(manager, "alignmentTestMode", true);
        manager.update();
        assertEquals(normalNanoReads + 1, clock.nanoTimeReads,
                "The alignment path should refresh only the fine diagnostic frame timestamp");
        assertEquals(normalWallReads, clock.currentTimeMillisReads,
                "The alignment path must not count as a completed diagnostic update");
    }

    @Test
    public void testManagerConstruction() {
        Sonic2SpecialStageManager instance1 = assertDoesNotThrow(() -> new Sonic2SpecialStageManager(),
                "Default construction should not require configured EngineContext");
        Sonic2SpecialStageManager instance2 = assertDoesNotThrow(() -> new Sonic2SpecialStageManager(),
                "Repeated default construction should stay bootstrap-safe");

        assertNotNull(instance1, "Manager instance should not be null");
        assertNotSame(instance1, instance2, "Separate constructions should yield separate instances");
    }

    @Test
    public void testInjectedDebugConstructionDoesNotRequireConfiguredEngineServices() {
        Sonic2SpecialStageSpriteDebug debug = new Sonic2SpecialStageSpriteDebug();

        Sonic2SpecialStageManager manager = assertDoesNotThrow(() -> new Sonic2SpecialStageManager(debug),
                "Injected debug construction should not require configured EngineContext");

        assertNotNull(manager, "Manager instance should not be null");
    }

    @Test
    public void testNotInitializedByDefault() {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        manager.reset();
        assertFalse(manager.isInitialized(), "Manager should not be initialized by default");
    }

    @Test
    void terminalPassCompletionRejectsAnAbsentPendingPassWithoutMutation() {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageSnapshot before = manager.captureRewindSnapshot();

        assertThrows(IllegalStateException.class,
                manager::completeTerminalPreStartPassWithoutVint);

        Sonic2SpecialStageSnapshot after = manager.captureRewindSnapshot();
        assertEquals(before.frameCounter, after.frameCounter);
        assertEquals(before.renderFrameCounter, after.renderFrameCounter);
        assertEquals(before.lastDrawingIndex, after.lastDrawingIndex);
        assertEquals(before.recurringMainPassPending, after.recurringMainPassPending);
        assertEquals(before.trackAnimator, after.trackAnimator);
    }

    @Test
    public void testH32Dimensions() {
        assertEquals(256, Sonic2SpecialStageManager.H32_WIDTH, "H32 width should be 256 pixels");
        assertEquals(224, Sonic2SpecialStageManager.H32_HEIGHT, "H32 height should be 224 pixels");
    }

    @Test
    public void testSegmentAnimationLengths() {
        assertEquals(24, ANIM_TURN_THEN_RISE.length, "SEGMENT_TURN_THEN_RISE animation should have 24 frames");
        assertEquals(24, ANIM_TURN_THEN_DROP.length, "SEGMENT_TURN_THEN_DROP animation should have 24 frames");
        assertEquals(12, ANIM_TURN_THEN_STRAIGHT.length, "SEGMENT_TURN_THEN_STRAIGHT animation should have 12 frames");
        assertEquals(16, ANIM_STRAIGHT.length, "SEGMENT_STRAIGHT animation should have 16 frames");
        assertEquals(11, ANIM_STRAIGHT_THEN_TURN.length, "SEGMENT_STRAIGHT_THEN_TURN animation should have 11 frames");
    }

    @Test
    public void testAnimBaseDurations() {
        assertEquals(60, ANIM_BASE_DURATIONS[0], "First duration should be 60");
        assertEquals(30, ANIM_BASE_DURATIONS[1], "Second duration should be 30");
        assertEquals(15, ANIM_BASE_DURATIONS[2], "Third duration should be 15");
        assertEquals(10, ANIM_BASE_DURATIONS[3], "Fourth duration should be 10");
        assertEquals(8, ANIM_BASE_DURATIONS[4], "Fifth duration should be 8");
        assertEquals(6, ANIM_BASE_DURATIONS[5], "Sixth duration should be 6");
        assertEquals(5, ANIM_BASE_DURATIONS[6], "Seventh duration should be 5");
        assertEquals(0, ANIM_BASE_DURATIONS[7], "Eighth duration should be 0");
    }

    @Test
    public void testSegmentFrameCounts() {
        assertEquals(5, SEGMENT_FRAME_COUNTS.length, "Should have 5 segment types");
        assertEquals(24, SEGMENT_FRAME_COUNTS[SEGMENT_TURN_THEN_RISE], "TurnThenRise should have 24 frames");
        assertEquals(24, SEGMENT_FRAME_COUNTS[SEGMENT_TURN_THEN_DROP], "TurnThenDrop should have 24 frames");
        assertEquals(12, SEGMENT_FRAME_COUNTS[SEGMENT_TURN_THEN_STRAIGHT], "TurnThenStraight should have 12 frames");
        assertEquals(16, SEGMENT_FRAME_COUNTS[SEGMENT_STRAIGHT], "Straight should have 16 frames");
        assertEquals(11, SEGMENT_FRAME_COUNTS[SEGMENT_STRAIGHT_THEN_TURN], "StraightThenTurn should have 11 frames");
    }

    private static final class CountingDiagnosticClock implements DiagnosticClock {
        private int nanoTimeReads;
        private int currentTimeMillisReads;
        private long nanos = 16_666_667L;
        private long millis = 17L;
        private long nanoStep = 16_666_667L;
        private long millisStep = 17L;

        private void configure(long nanos, long millis, long nanoStep, long millisStep) {
            this.nanos = nanos;
            this.millis = millis;
            this.nanoStep = nanoStep;
            this.millisStep = millisStep;
        }

        @Override
        public long nanoTime() {
            nanoTimeReads++;
            long value = nanos;
            nanos += nanoStep;
            return value;
        }

        @Override
        public long currentTimeMillis() {
            currentTimeMillisReads++;
            long value = millis;
            millis += millisStep;
            return value;
        }
    }

    private static void assertDiagnosticEpochCleared(Sonic2SpecialStageManager manager) {
        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        assertEquals(0L, snapshot.diagnosticWallStartTime);
        assertEquals(0, snapshot.diagnosticUpdateCount);
        assertEquals(0, snapshot.diagnosticTrackAdvances);
        assertEquals(0L, snapshot.lastFrameTime);
        assertEquals(0, snapshot.frameSampleCount);
        assertEquals(0L, snapshot.frameSampleSum);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    public void testTrackFrameOffsets() {
        assertEquals(TRACK_FRAME_COUNT, TRACK_FRAME_OFFSETS.length, "Should have 56 track frame offsets");
        assertEquals(TRACK_FRAME_COUNT, TRACK_FRAME_SIZES.length, "Should have 56 track frame sizes");

        assertEquals(0x0CA904, TRACK_FRAME_OFFSETS[0], "First frame offset should be 0x0CA904");
        assertEquals(1188, TRACK_FRAME_SIZES[0], "First frame size should be 1188");

        long expectedEnd = TRACK_FRAME_OFFSETS[TRACK_FRAME_COUNT - 1] + TRACK_FRAME_SIZES[TRACK_FRAME_COUNT - 1];
        assertEquals(TRACK_FRAMES_END, expectedEnd, "Last frame should end at TRACK_FRAMES_END");
    }
}
