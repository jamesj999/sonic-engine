package com.openggf.tests.trace;

import com.openggf.trace.*;

import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.fail;

class DebugS1Mz1RingParity {

    @Test
    void printFirstRingMismatch() throws Exception {
        Path traceDir = Path.of("src/test/resources/traces/s1/mz1_fullrun");
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path;
        try (var files = Files.list(traceDir)) {
            bk2Path = files
                    .filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        TraceMetadata meta = trace.metadata();

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_1, 1, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(meta.bk2FrameOffset())
                    .build();

            ObjectManager om = GameServices.level().getObjectManager();
            if (om != null) {
                om.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            int preTraceOsc = meta.preTraceOscillationFrames();
            for (int i = 0; i < preTraceOsc; i++) {
                com.openggf.game.OscillationManager.update(-(preTraceOsc - i));
            }

            int previousExpectedRings = trace.getFrame(0).rings();
            int previousActualRings = fixture.sprite().getRingCount();

            for (int i = 0; i < trace.frameCount(); i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase phase =
                        TraceExecutionModel.forGame(meta.game()).phaseFor(previous, expected);
                System.out.printf("phase=%s gameplay=%04X vblank=%04X lag=%04X%n",
                        phase,
                        expected.gameplayFrameCounter(),
                        expected.vblankCounter(),
                        expected.lagCounter());
                if (expected.frame() >= 754 && expected.frame() <= 763) {
                    System.out.printf(
                            "PRE  frame=%d x=0x%04X y=0x%04X cam=0x%04X onObject=%s rings=%d%n",
                            expected.frame(),
                            fixture.sprite().getCentreX(),
                            fixture.sprite().getCentreY(),
                            GameServices.camera().getX(),
                            fixture.sprite().isOnObject(),
                            fixture.sprite().getRingCount());
                }
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
                if (expected.frame() >= 754 && expected.frame() <= 763) {
                    System.out.printf(
                            "POST frame=%d x=0x%04X y=0x%04X cam=0x%04X onObject=%s rings=%d%n",
                            expected.frame(),
                            fixture.sprite().getCentreX(),
                            fixture.sprite().getCentreY(),
                            GameServices.camera().getX(),
                            fixture.sprite().isOnObject(),
                            fixture.sprite().getRingCount());
                }

                int actualRings = fixture.sprite().getRingCount();
                if ((expected.frame() >= 100 && expected.frame() <= 104)
                        || (expected.frame() >= 360 && expected.frame() <= 366)
                        || (expected.frame() >= 517 && expected.frame() <= 520)
                        || (expected.frame() >= 595 && expected.frame() <= 601)
                        || (expected.frame() >= 688 && expected.frame() <= 697)
                        || (expected.frame() >= 754 && expected.frame() <= 763)) {
                    dumpRingState(expected.frame(), fixture.sprite());
                }
                if (expected.rings() != previousExpectedRings || actualRings != previousActualRings) {
                    System.out.printf(
                            "frame=%d expectedRings=%d actualRings=%d x=0x%04X y=0x%04X cam=0x%04X%n",
                            expected.frame(),
                            expected.rings(),
                            actualRings,
                            fixture.sprite().getCentreX(),
                            fixture.sprite().getCentreY(),
                            GameServices.camera().getX());
                    previousExpectedRings = expected.rings();
                    previousActualRings = actualRings;
                }

                if (expected.rings() != actualRings) {
                    fail(String.format(
                            "First ring mismatch at frame %d: expected=%d actual=%d x=0x%04X y=0x%04X cam=0x%04X",
                            expected.frame(),
                            expected.rings(),
                            actualRings,
                            fixture.sprite().getCentreX(),
                            fixture.sprite().getCentreY(),
                            GameServices.camera().getX()));
                }
            }
        } finally {
            sharedLevel.dispose();
        }
    }

    private static void dumpRingState(int frame, com.openggf.sprites.playable.AbstractPlayableSprite player) {
        RingManager ringManager = GameServices.level().getRingManager();
        if (ringManager == null) {
            return;
        }

        int playerCentreX = player.getCentreX();
        int playerCentreY = player.getCentreY();
        int playerLeft = playerCentreX - 8;
        int actualYRadius = player.getYRadius();
        int playerTopMinus3 = playerCentreY - Math.max(1, actualYRadius - 3);
        int playerTopFullRadius = playerCentreY - Math.max(1, actualYRadius);
        System.out.printf(
                "DEBUG frame=%d playerLeft=0x%04X yRadius=%d invuln=%d rolling=%s onObject=%s camY=0x%04X maxY=0x%04X maxYTarget=0x%04X topMinus3=0x%04X topFull=0x%04X%n",
                frame, playerLeft, actualYRadius, player.getInvulnerableFrames(),
                player.getRolling(), player.isOnObject(),
                GameServices.camera().getY() & 0xFFFF,
                GameServices.camera().getMaxY() & 0xFFFF,
                GameServices.camera().getMaxYTarget() & 0xFFFF,
                playerTopMinus3, playerTopFullRadius);

        ringManager.getActiveSpawns().stream()
                .filter(ring -> Math.abs(ring.x() - playerCentreX) <= 0x30)
                .sorted(Comparator.comparingInt(RingSpawn::x))
                .forEach(ring -> {
                    boolean collected = ringManager.isCollected(ring);
                    int dx = ring.x() - 6 - playerLeft;
                    int dyMinus3 = ring.y() - 6 - playerTopMinus3;
                    int dyFull = ring.y() - 6 - playerTopFullRadius;
                    int dyTopLeftPlus8 = (ring.y() + 8) - 6 - playerTopMinus3;
                    System.out.printf(
                            "  ring x=0x%04X y=0x%04X collected=%s dx=%d dyMinus3=%d dyFull=%d dyTopLeftPlus8=%d%n",
                            ring.x(), ring.y(), collected, dx, dyMinus3, dyFull, dyTopLeftPlus8);
                });

        dumpLostRings(frame, ringManager, playerLeft, playerTopMinus3, actualYRadius);
        if (frame >= 517 && frame <= 520) {
            dumpObjectSlots();
        }
    }

    private static void dumpObjectSlots() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return;
        }

        objectManager.getActiveObjects().stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(instance -> instance.getSlotIndex() >= 32 && instance.getSlotIndex() <= 60)
                .sorted(java.util.Comparator.comparingInt(AbstractObjectInstance::getSlotIndex))
                .forEach(instance -> System.out.printf(
                        "  activeSlot[%d]=%s spawn=(0x%04X,0x%04X)%n",
                        instance.getSlotIndex(),
                        instance.getClass().getSimpleName(),
                        instance.getX(),
                        instance.getY()));
    }

    private static void dumpLostRings(int frame, RingManager ringManager, int playerLeft, int playerTopMinus3,
                                      int actualYRadius) {
        try {
            Field lostRingsField = RingManager.class.getDeclaredField("lostRings");
            lostRingsField.setAccessible(true);
            Object lostRings = lostRingsField.get(ringManager);

            Field activeRingCountField = lostRings.getClass().getDeclaredField("activeRingCount");
            activeRingCountField.setAccessible(true);
            int activeRingCount = activeRingCountField.getInt(lostRings);

            Field ringPoolField = lostRings.getClass().getDeclaredField("ringPool");
            ringPoolField.setAccessible(true);
            Object ringPool = ringPoolField.get(lostRings);

            System.out.printf("  lostRings active=%d%n", activeRingCount);
            for (int i = 0; i < activeRingCount; i++) {
                Object ring = Array.get(ringPool, i);
                if (ring == null) {
                    continue;
                }

                boolean active = invokeBoolean(ring, "isActive");
                boolean collected = invokeBoolean(ring, "isCollected");
                if (!active) {
                    continue;
                }

                int ringX = invokeInt(ring, "getX");
                int ringY = invokeInt(ring, "getY");
                boolean forceDump = frame >= 688 && frame <= 697;
                if (!forceDump && Math.abs(ringX - (playerLeft + 8)) > 0x40) {
                    continue;
                }

                int dx = ringX - 6 - playerLeft;
                int dyMinus3 = ringY - 6 - playerTopMinus3;
                int collectFrame = readIntField(ring, "sparkleStartVIntRunCount");
                int yVel = readIntField(ring, "yVel");
                int lifetime = readIntField(ring, "lifetime");
                int slotIndex = readOptionalIntField(ring, "slotIndex", -1);
                int phaseOffset = readOptionalIntField(ring, "phaseOffset", -1);
                System.out.printf(
                        "  lostRing[%d] slot=%d phase=0x%02X x=0x%04X y=0x%04X dx=%d dyMinus3=%d yRadius=%d yVel=0x%04X lifetime=%d collected=%s sparkleStart=%d%n",
                        i, slotIndex, phaseOffset, ringX, ringY, dx, dyMinus3, actualYRadius,
                        yVel & 0xFFFF, lifetime, collected, collectFrame);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to inspect lost rings", e);
        }
    }

    private static boolean invokeBoolean(Object target, String methodName) throws ReflectiveOperationException {
        var method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (boolean) method.invoke(target);
    }

    private static int invokeInt(Object target, String methodName) throws ReflectiveOperationException {
        var method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (int) method.invoke(target);
    }

    private static int readIntField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static int readOptionalIntField(Object target, String fieldName, int fallback)
            throws IllegalAccessException {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (NoSuchFieldException ignored) {
            return fallback;
        }
    }
}
