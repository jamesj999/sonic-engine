package com.openggf.tests.trace;

import com.openggf.trace.*;

import com.openggf.game.GameServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.fail;

@Disabled("Debug utility: intentionally fails at the first ring mismatch while investigating parity.")
class DebugS1Ghz1RingParity {

    @Test
    void printFirstRingMismatch() throws Exception {
        Path traceDir = Path.of("src/test/resources/traces/s1/ghz1_fullrun");
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

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_1, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(meta.bk2FrameOffset())
                    .build();

            var objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
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
                if ((expected.frame() >= 311 && expected.frame() <= 315)
                        || (expected.frame() >= 1740 && expected.frame() <= 1744)) {
                    dumpMonitorState("PRE", expected.frame(), fixture.sprite());
                }
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
                if ((expected.frame() >= 311 && expected.frame() <= 315)
                        || (expected.frame() >= 1740 && expected.frame() <= 1744)) {
                    dumpMonitorState("POST", expected.frame(), fixture.sprite());
                }

                int actualRings = fixture.sprite().getRingCount();
                if (expected.rings() != previousExpectedRings || actualRings != previousActualRings) {
                    System.out.printf(
                            "frame=%d expectedRings=%d actualRings=%d x=0x%04X y=0x%04X cam=0x%04X hurt=%s onObject=%s%n",
                            expected.frame(),
                            expected.rings(),
                            actualRings,
                            fixture.sprite().getCentreX(),
                            fixture.sprite().getCentreY(),
                            GameServices.camera().getX(),
                            fixture.sprite().isHurt(),
                            fixture.sprite().isOnObject());
                    previousExpectedRings = expected.rings();
                    previousActualRings = actualRings;
                }

                if (expected.rings() != actualRings) {
                    fail(String.format(
                            "First ring mismatch at frame %d: expected=%d actual=%d x=0x%04X y=0x%04X cam=0x%04X hurt=%s onObject=%s",
                            expected.frame(),
                            expected.rings(),
                            actualRings,
                            fixture.sprite().getCentreX(),
                            fixture.sprite().getCentreY(),
                            GameServices.camera().getX(),
                            fixture.sprite().isHurt(),
                            fixture.sprite().isOnObject()));
                }
            }
        } finally {
            sharedLevel.dispose();
        }
    }

    private static void dumpMonitorState(String phase, int frame,
                                         com.openggf.sprites.playable.AbstractPlayableSprite player) throws Exception {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return;
        }

        System.out.printf(
                "%s frame=%d rings=%d x=0x%04X y=0x%04X cam=0x%04X%n",
                phase,
                frame,
                player.getRingCount(),
                player.getCentreX(),
                player.getCentreY(),
                GameServices.camera().getX());

        objectManager.getActiveObjects().stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .sorted(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex))
                .filter(instance -> instance.getSlotIndex() >= 33 && instance.getSlotIndex() <= 44)
                .forEach(instance -> {
                    try {
                        System.out.printf(
                                "  slot=%d cls=%s x=0x%04X y=0x%04X iconActive=%s effectApplied=%s iconVelY=0x%04X iconWait=%d broken=%s%n",
                                instance.getSlotIndex(),
                                instance.getClass().getSimpleName(),
                                instance.getX(),
                                instance.getY(),
                                readBooleanField(instance, "iconActive"),
                                readBooleanField(instance, "effectApplied"),
                                readIntField(instance, "iconVelY") & 0xFFFF,
                                readIntField(instance, "iconWaitFrames"),
                                readBooleanField(instance, "broken"));
                    } catch (ReflectiveOperationException ignored) {
                        System.out.printf(
                                "  slot=%d cls=%s x=0x%04X y=0x%04X%n",
                                instance.getSlotIndex(),
                                instance.getClass().getSimpleName(),
                                instance.getX(),
                                instance.getY());
                    }
                });
    }

    private static boolean readBooleanField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static int readIntField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
