package com.openggf.tests.trace.s1;

import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceExecutionModel;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiresRom(SonicGame.SONIC_1)
class TestS1Mz1BatbrainEncounterRegression {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s1/mz1_fullrun");
    private static final int TARGET_FRAME = Integer.getInteger("bat.frame", 3192);

    @Test
    void nearestBatbrainStillMatchesRecordedRomSlotAndPositionBeforeBounce() throws Exception {
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
        TraceEvent.ObjectNear expectedBatbrain = findExpectedBatbrain(trace, TARGET_FRAME);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_1, 1, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(meta.bk2FrameOffset())
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = meta.preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            for (int i = 0; i <= TARGET_FRAME; i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(meta.game()).phaseFor(previous, expected);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
            }

            ObjectManager liveObjectManager = GameServices.level().getObjectManager();
            String allBatbrains = describeBatbrains(
                    liveObjectManager,
                    fixture.sprite().getCentreX(),
                    fixture.sprite().getCentreY());
            String slotSummary = describeActiveSlots(liveObjectManager);
            ObservedBadnik observed = findNearestBatbrain(
                    liveObjectManager,
                    fixture.sprite().getCentreX(),
                    fixture.sprite().getCentreY());
            assertNotNull(observed,
                    "Expected a live Batbrain near Sonic at frame " + TARGET_FRAME
                            + " but found: " + allBatbrains + " slots=" + slotSummary);
            assertEquals(expectedBatbrain.slot(), observed.slot(),
                    () -> "Batbrain slot drifted before the first MZ1 bounce: "
                            + observed + " all=" + allBatbrains + " slots=" + slotSummary);
            assertEquals(expectedBatbrain.x() & 0xFFFF, observed.x(),
                    () -> "Batbrain X drifted before the first MZ1 bounce: "
                            + observed + " all=" + allBatbrains + " slots=" + slotSummary);
            assertEquals(expectedBatbrain.y() & 0xFFFF, observed.y(),
                    () -> "Batbrain Y drifted before the first MZ1 bounce: "
                            + observed + " all=" + allBatbrains + " slots=" + slotSummary);
        } finally {
            sharedLevel.dispose();
        }
    }

    private static TraceEvent.ObjectNear findExpectedBatbrain(TraceData trace, int frame) {
        return trace.getEventsForFrame(frame).stream()
                .filter(TraceEvent.ObjectNear.class::isInstance)
                .map(TraceEvent.ObjectNear.class::cast)
                .filter(event -> event.slot() == 75)
                .filter(event -> "0x55".equals(event.objectType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected ROM Batbrain event missing at frame " + frame));
    }

    private static ObservedBadnik findNearestBatbrain(ObjectManager objectManager, int playerX, int playerY) {
        if (objectManager == null) {
            return null;
        }
        return objectManager.getActiveObjects().stream()
                .filter(instance -> !instance.isDestroyed())
                .filter(instance -> instance.getSpawn() != null)
                .filter(instance -> instance.getSpawn().objectId() == 0x55)
                .map(instance -> toObservedBadnik(instance, playerX, playerY))
                .min(Comparator.comparingInt(ObservedBadnik::distanceSquared))
                .orElse(null);
    }

    private static String describeBatbrains(ObjectManager objectManager, int playerX, int playerY) {
        if (objectManager == null) {
            return "<no object manager>";
        }
        StringBuilder sb = new StringBuilder();
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance.isDestroyed() || instance.getSpawn() == null || instance.getSpawn().objectId() != 0x55) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append(toObservedBadnik(instance, playerX, playerY));
        }
        return sb.isEmpty() ? "<none>" : sb.toString();
    }

    private static String describeActiveSlots(ObjectManager objectManager) {
        if (objectManager == null) {
            return "<no object manager>";
        }
        return objectManager.getActiveObjects().stream()
                .filter(instance -> !instance.isDestroyed())
                .filter(instance -> instance instanceof AbstractObjectInstance)
                .map(AbstractObjectInstance.class::cast)
                .sorted(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex))
                .map(instance -> String.format("s%d:0x%02X",
                        instance.getSlotIndex(),
                        instance.getSpawn() != null ? instance.getSpawn().objectId() & 0xFF : 0))
                .reduce((left, right) -> left + " " + right)
                .orElse("<none>");
    }

    private static ObservedBadnik toObservedBadnik(ObjectInstance instance, int playerX, int playerY) {
        int slot = instance instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
        int x = instance.getX() & 0xFFFF;
        int y = instance.getY() & 0xFFFF;
        int dx = x - playerX;
        int dy = y - playerY;
        return new ObservedBadnik(slot, x, y, dx * dx + dy * dy);
    }

    private record ObservedBadnik(int slot, int x, int y, int distanceSquared) {
        @Override
        public String toString() {
            return String.format("slot=%d x=0x%04X y=0x%04X d2=%d",
                    slot, x & 0xFFFF, y & 0xFFFF, distanceSquared);
        }
    }
}
