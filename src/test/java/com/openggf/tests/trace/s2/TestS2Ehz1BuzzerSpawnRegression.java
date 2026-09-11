package com.openggf.tests.trace.s2;

import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@RequiresRom(SonicGame.SONIC_2)
class TestS2Ehz1BuzzerSpawnRegression {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int FIRST_FLAME_FRAME = 127;
    private static final int FIRST_PARENT_SLOT = 18;
    private static final int FIRST_FLAME_SLOT = 19;
    private static final int FIRST_BUZZER_X = 0x0330;
    private static final int FIRST_BUZZER_Y = 0x01F8;
    private static final int LATE_PARENT_APPEAR_FRAME = 1301;
    private static final int LATE_FLAME_APPEAR_FRAME = 1302;
    private static final int LATE_BUZZER_X = 0x08B0;
    private static final int LATE_BUZZER_Y = 0x01E0;
    private static final int PRE_APPEARANCE_FRAME = 1300;
    private static final int POSITION_PROBE_FRAME = 1800;
    private static final int EXPECTED_PARENT_SLOT = 19;
    private static final int EXPECTED_PARENT_X = 0x0885;
    private static final int EXPECTED_PARENT_Y = 0x01E0;

    @Test
    void buzzerParentDoesNotExistInRomSlotBeforeRecordedAppearance() throws Exception {
        ObjectProbe probe = replayToFrame(PRE_APPEARANCE_FRAME);
        try {
            assertNull(probe.findSlot(EXPECTED_PARENT_SLOT),
                    () -> "Buzzer parent loaded before ROM appearance: " + probe.describeBuzzers());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void buzzerInitSpawnsExhaustFlameInNextSlot() throws Exception {
        ObjectProbe probe = replayToFrame(FIRST_FLAME_FRAME);
        try {
            ObservedBuzzer parent = probe.findSlot(FIRST_PARENT_SLOT);
            ObservedBuzzer flame = probe.findSlot(FIRST_FLAME_SLOT);
            assertNotNull(parent,
                    () -> "Expected initial Buzzer parent missing: " + probe.describeBuzzers());
            assertNotNull(flame,
                    () -> "Expected initial Buzzer flame missing: " + probe.describeBuzzers());
            assertEquals(FIRST_BUZZER_X, parent.x(),
                    () -> "Initial Buzzer parent X drifted: " + probe.describeBuzzers());
            assertEquals(FIRST_BUZZER_Y, parent.y(),
                    () -> "Initial Buzzer parent Y drifted: " + probe.describeBuzzers());
            assertEquals(FIRST_BUZZER_X, flame.x(),
                    () -> "Initial Buzzer flame X drifted: " + probe.describeBuzzers());
            assertEquals(FIRST_BUZZER_Y, flame.y(),
                    () -> "Initial Buzzer flame Y drifted: " + probe.describeBuzzers());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void laterBuzzerAppearsAtRecordedFrame() throws Exception {
        ObjectProbe probe = replayToFrame(LATE_PARENT_APPEAR_FRAME);
        try {
            assertEquals(1, probe.findBuzzersAt(LATE_BUZZER_X, LATE_BUZZER_Y).size(),
                    () -> "Expected one later Buzzer body on its appearance frame: "
                            + probe.describeBuzzers());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void laterBuzzerSpawnsFlameOnFollowingFrame() throws Exception {
        ObjectProbe probe = replayToFrame(LATE_FLAME_APPEAR_FRAME);
        try {
            assertEquals(2, probe.findBuzzersAt(LATE_BUZZER_X, LATE_BUZZER_Y).size(),
                    () -> "Expected later Buzzer body and flame on the next frame: "
                            + probe.describeBuzzers());
        } finally {
            probe.dispose();
        }
    }

    @Test
    void buzzerParentMatchesRecordedSlotAndPositionBeforeFirstContact() throws Exception {
        ObjectProbe probe = replayToFrame(POSITION_PROBE_FRAME);
        try {
            ObservedBuzzer buzzer = probe.findSlot(EXPECTED_PARENT_SLOT);
            assertNotNull(buzzer,
                    () -> "Expected Buzzer parent missing from ROM slot before first contact: "
                            + probe.describeBuzzers());
            assertEquals(EXPECTED_PARENT_X, buzzer.x(),
                    () -> "EHZ1 Buzzer parent X drifted before first contact: "
                            + buzzer + " all=" + probe.describeBuzzers());
            assertEquals(EXPECTED_PARENT_Y, buzzer.y(),
                    () -> "EHZ1 Buzzer parent Y drifted before first contact: "
                            + buzzer + " all=" + probe.describeBuzzers());
        } finally {
            probe.dispose();
        }
    }

    private static ObjectProbe replayToFrame(int targetFrame) throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);

        Path bk2Path;
        try (var files = Files.list(TRACE_DIR)) {
            bk2Path = files
                    .filter(path -> path.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + TRACE_DIR);

        TraceData trace = TraceData.load(TRACE_DIR);
        TraceMetadata meta = trace.metadata();
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_2, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1).replayStart();

            for (int i = replayStart.startingTraceIndex(); i <= targetFrame; i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase phase =
                        TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
            }

            return new ObjectProbe(sharedLevel);
        } catch (Throwable t) {
            sharedLevel.dispose();
            throw t;
        }
    }

    private static ObservedBuzzer toObservedBuzzer(ObjectInstance instance) {
        int slot = instance instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
        return new ObservedBuzzer(slot, instance.getX() & 0xFFFF, instance.getY() & 0xFFFF);
    }

    private record ObjectProbe(SharedLevel sharedLevel) {
        ObservedBuzzer findSlot(int slot) {
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager == null) {
                return null;
            }
            return objectManager.getActiveObjects().stream()
                    .filter(instance -> !instance.isDestroyed())
                    .filter(instance -> instance.getSpawn() != null)
                    .filter(instance -> instance.getSpawn().objectId() == 0x4B)
                    .filter(instance -> instance instanceof AbstractObjectInstance aoi && aoi.getSlotIndex() == slot)
                    .map(TestS2Ehz1BuzzerSpawnRegression::toObservedBuzzer)
                    .findFirst()
                    .orElse(null);
        }

        List<ObservedBuzzer> findBuzzersAt(int x, int y) {
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager == null) {
                return List.of();
            }
            return objectManager.getActiveObjects().stream()
                    .filter(instance -> !instance.isDestroyed())
                    .filter(instance -> instance.getSpawn() != null)
                    .filter(instance -> instance.getSpawn().objectId() == 0x4B)
                    .map(TestS2Ehz1BuzzerSpawnRegression::toObservedBuzzer)
                    .filter(buzzer -> buzzer.x() == x && buzzer.y() == y)
                    .sorted(Comparator.comparingInt(ObservedBuzzer::slot))
                    .toList();
        }

        String describeBuzzers() {
            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager == null) {
                return "<no object manager>";
            }
            return objectManager.getActiveObjects().stream()
                    .filter(instance -> !instance.isDestroyed())
                    .filter(instance -> instance.getSpawn() != null)
                    .filter(instance -> instance.getSpawn().objectId() == 0x4B)
                    .map(TestS2Ehz1BuzzerSpawnRegression::toObservedBuzzer)
                    .sorted(Comparator.comparingInt(ObservedBuzzer::slot))
                    .map(ObservedBuzzer::toString)
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("<none>");
        }

        void dispose() {
            sharedLevel.dispose();
        }
    }

    private record ObservedBuzzer(int slot, int x, int y) {
        @Override
        public String toString() {
            return String.format("slot=%d x=0x%04X y=0x%04X", slot, x, y);
        }
    }
}
