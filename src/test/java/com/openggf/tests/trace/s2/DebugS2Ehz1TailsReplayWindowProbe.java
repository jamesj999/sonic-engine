package com.openggf.tests.trace.s2;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.objects.SignpostObjectInstance;
import com.openggf.level.ChunkDesc;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

class DebugS2Ehz1TailsReplayWindowProbe {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int DEFAULT_START_FRAME = 850;
    private static final int DEFAULT_END_FRAME = 890;
    private static boolean firstSonicSubMismatchPrinted;
    private static boolean firstSonicPixelMismatchPrinted;
    private static boolean firstSubMismatchPrinted;
    private static boolean firstPixelMismatchPrinted;

    @Test
    void dumpEarlyTailsPlatformWindow() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR), "Trace directory not found: " + TRACE_DIR);
        TraceData trace = TraceData.load(TRACE_DIR);
        TraceMetadata meta = trace.metadata();
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);
        firstSonicSubMismatchPrinted = false;
        firstSonicPixelMismatchPrinted = false;
        firstSubMismatchPrinted = false;
        firstPixelMismatchPrinted = false;
        int startFrame = Integer.getInteger("debug.startFrame", DEFAULT_START_FRAME);
        int endFrame = Integer.getInteger("debug.endFrame", DEFAULT_END_FRAME);

        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_2, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .withRecording(findBk2File())
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                    .build();

            ObjectManager objectManager = GameServices.level().getObjectManager();
            if (objectManager != null) {
                objectManager.initVblaCounter(trace.initialVblankCounter() - 1);
            }

            TraceReplayBootstrap.ReplayStartState replayStart =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1).replayStart();

            int framesStepped = 0;
            TraceFrame previous = null;
            for (int i = replayStart.startingTraceIndex(); i <= endFrame; i++) {
                TraceFrame current = trace.getFrame(i);
                previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase phase =
                        TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                framesStepped++;

                maybePrintFirstSonicPixelMismatch(current);
                maybePrintFirstSonicSubMismatch(current);
                maybePrintFirstPixelMismatch(current);
                maybePrintFirstSubMismatch(current);
                if (current.frame() >= startFrame) {
                    dumpFrame(current, bk2Input, phase);
                }
            }

            // Oracle: the Tails platform window must replay through endFrame, stepping
            // exactly one trace frame per index from the bootstrap start to endFrame, and
            // invoking all four first-mismatch detectors on each frame.
            assertTrue(replayStart.startingTraceIndex() <= endFrame,
                    "Tails window bounds inverted (start=" + replayStart.startingTraceIndex()
                            + " end=" + endFrame + ")");
            assertTrue(framesStepped > 0, "Tails window stepped no frames");
            assertEquals(endFrame - replayStart.startingTraceIndex() + 1, framesStepped,
                    "Tails window frame count drifted");
            // characterization guard: the requested dump window end must lie at or beyond the
            // dump start, so dumpFrame and the four first-mismatch detectors are exercised over
            // a real window rather than an empty one.
            assertTrue(endFrame >= startFrame,
                    "dump window inverted (startFrame=" + startFrame + " endFrame=" + endFrame + ")");
        } finally {
            sharedLevel.dispose();
        }
    }

    private static void dumpFrame(TraceFrame current, int bk2Input, TraceExecutionPhase phase) {
        AbstractPlayableSprite sonic = GameServices.sprites() != null
                ? GameServices.sprites().getSprite("sonic") instanceof AbstractPlayableSprite playable
                    ? playable : null
                : null;
        SpriteManager spriteManager = GameServices.sprites();
        AbstractPlayableSprite tails = spriteManager != null && !spriteManager.getSidekicks().isEmpty()
                ? spriteManager.getSidekicks().getFirst()
                : null;

        ObjectRef sonicRide = ridingObject(sonic);
        ObjectRef tailsRide = ridingObject(tails);

        SidekickCpuController controller = tails != null ? tails.getCpuController() : null;
        Integer despawnCounter = readControllerInt(controller, "despawnCounter");
        Integer controlCounter = readControllerInt(controller, "controlCounter");
        Integer lastInteractObjectId = readControllerInt(controller, "lastInteractObjectId");
        int latchedInteractObjectId = tails != null ? tails.getLatchedSolidObjectId() & 0xFF : 0;
        boolean tailsOnScreen = GameServices.camera() != null && tails != null
                && GameServices.camera().isOnScreen(tails);
        String groundSensors = formatGroundSensors(tails);
        String ceilingSensors = formatCeilingSensors(tails);
        String quadrantInfo = formatAirQuadrant(tails);
        String groundSensorDetails = formatGroundSensorWorldDetails(tails);
        int followDelay = 16;
        int leaderInput = sonic != null ? sonic.getInputHistory(followDelay) & 0xFFFF : 0;
        int leaderStatus = sonic != null ? sonic.getStatusHistory(followDelay) & 0xFF : 0;
        int leaderX = sonic != null ? sonic.getCentreX(followDelay) & 0xFFFF : 0;
        int leaderY = sonic != null ? sonic.getCentreY(followDelay) & 0xFFFF : 0;
        int dx = sonic != null && tails != null ? leaderX - (tails.getCentreX() & 0xFFFF) : 0;
        int dy = sonic != null && tails != null ? leaderY - (tails.getCentreY() & 0xFFFF) : 0;
        int levelFrameCounter = GameServices.level() != null ? GameServices.level().getFrameCounter() : -1;
        String wallProbe = formatCalcRoomInFront(tails);
        SignpostObjectInstance signpost = findNearestSignpost();
        String signpostState = formatSignpostState(signpost);
        boolean sonicLock = sonic != null && sonic.isControlLocked();
        boolean sonicObjectControlled = sonic != null && sonic.isObjectControlled();
        boolean sonicForceRight = sonic != null && sonic.isForceInputRight();
        boolean sonicQueuedLock = sonic != null && Boolean.TRUE.equals(readField(sonic, "hasQueuedControlLockedState"));
        boolean sonicQueuedForceRight =
                sonic != null && Boolean.TRUE.equals(readField(sonic, "hasQueuedForceInputRightState"));
        Object sonicQueuedLockValue = sonic != null ? readField(sonic, "queuedControlLocked") : null;
        Object sonicQueuedForceRightValue = sonic != null ? readField(sonic, "queuedForceInputRight") : null;

        System.out.printf(
                "frame=%d phase=%s in=%04X/%04X cam=%04X "
                        + "lvlFc=%04X "
                        + "expS=(%04X,%04X) actS=(%04X,%04X) expSSub=(%04X,%04X) actSSub=(%04X,%04X) "
                        + "expSSpd=(%04X,%04X,%04X) actSSpd=(%04X,%04X,%04X) "
                        + "expT=(%04X,%04X) actT=(%04X,%04X) "
                        + "expTSub=(%04X,%04X) actTSub=(%04X,%04X) "
                        + "expTSpd=(%04X,%04X,%04X) actTSpd=(%04X,%04X,%04X) "
                        + "expStatus=%02X expOn=%02X actAir=%d actRoll=%d actOn=%d actDir=%s actAng=%02X "
                        + "actLay=%d top=%02X lrb=%02X hi=%d "
                        + "ctrl=%s ds=%d cc=%d onScr=%d lastId=%02X latch=%02X l=%d r=%d u=%d d=%d jump=%d leadIn=%04X leadSt=%02X "
                        + "leadPos=(%04X,%04X) dx=%d dy=%d moveLock=%d push=%d ride=%s sonicRide=%s "
                        + "sonicAir=%d sonicRoll=%d sonicLock=%d sonicObj=%d sonicYRad=%d sonicStandY=%d sonicForceR=%d qLock=%d qLockV=%s "
                        + "qForceR=%d qForceRV=%s goal=%s wall=%s nearby=%s switchers=%s%n",
                current.frame(),
                phase,
                current.input() & 0xFFFF,
                bk2Input & 0xFFFF,
                current.cameraX() & 0xFFFF,
                levelFrameCounter & 0xFFFF,
                current.x() & 0xFFFF,
                current.y() & 0xFFFF,
                sonic != null ? sonic.getCentreX() & 0xFFFF : 0,
                sonic != null ? sonic.getCentreY() & 0xFFFF : 0,
                current.xSub() & 0xFFFF,
                current.ySub() & 0xFFFF,
                sonic != null ? sonic.getXSubpixelRaw() & 0xFFFF : 0,
                sonic != null ? sonic.getYSubpixelRaw() & 0xFFFF : 0,
                current.xSpeed() & 0xFFFF,
                current.ySpeed() & 0xFFFF,
                current.gSpeed() & 0xFFFF,
                sonic != null ? sonic.getXSpeed() & 0xFFFF : 0,
                sonic != null ? sonic.getYSpeed() & 0xFFFF : 0,
                sonic != null ? sonic.getGSpeed() & 0xFFFF : 0,
                current.sidekick() != null ? current.sidekick().x() & 0xFFFF : 0,
                current.sidekick() != null ? current.sidekick().y() & 0xFFFF : 0,
                tails != null ? tails.getCentreX() & 0xFFFF : 0,
                tails != null ? tails.getCentreY() & 0xFFFF : 0,
                current.sidekick() != null ? current.sidekick().xSub() & 0xFFFF : 0,
                current.sidekick() != null ? current.sidekick().ySub() & 0xFFFF : 0,
                tails != null ? tails.getXSubpixelRaw() & 0xFFFF : 0,
                tails != null ? tails.getYSubpixelRaw() & 0xFFFF : 0,
                current.sidekick() != null ? current.sidekick().xSpeed() & 0xFFFF : 0,
                current.sidekick() != null ? current.sidekick().ySpeed() & 0xFFFF : 0,
                current.sidekick() != null ? current.sidekick().gSpeed() & 0xFFFF : 0,
                tails != null ? tails.getXSpeed() & 0xFFFF : 0,
                tails != null ? tails.getYSpeed() & 0xFFFF : 0,
                tails != null ? tails.getGSpeed() & 0xFFFF : 0,
                current.sidekick() != null ? current.sidekick().statusByte() & 0xFF : 0,
                current.sidekick() != null ? current.sidekick().standOnObj() & 0xFF : 0,
                tails != null && tails.getAir() ? 1 : 0,
                tails != null && tails.getRolling() ? 1 : 0,
                tails != null && tails.isOnObject() ? 1 : 0,
                tails != null ? tails.getDirection().name() : "NONE",
                tails != null ? tails.getAngle() & 0xFF : 0,
                tails != null ? tails.getLayer() & 0xFF : -1,
                tails != null ? tails.getTopSolidBit() & 0xFF : -1,
                tails != null ? tails.getLrbSolidBit() & 0xFF : -1,
                tails != null && tails.isHighPriority() ? 1 : 0,
                controller != null ? controller.getState().name() : "NONE",
                despawnCounter != null ? despawnCounter : -1,
                controlCounter != null ? controlCounter : -1,
                tailsOnScreen ? 1 : 0,
                lastInteractObjectId != null ? lastInteractObjectId & 0xFF : 0,
                latchedInteractObjectId,
                controller != null && controller.getInputLeft() ? 1 : 0,
                controller != null && controller.getInputRight() ? 1 : 0,
                controller != null && controller.getInputUp() ? 1 : 0,
                controller != null && controller.getInputDown() ? 1 : 0,
                controller != null && controller.getInputJump() ? 1 : 0,
                leaderInput,
                leaderStatus,
                leaderX,
                leaderY,
                dx,
                dy,
                tails != null ? tails.getMoveLockTimer() : -1,
                tails != null && tails.getPushing() ? 1 : 0,
                tailsRide,
                sonicRide,
                sonic != null && sonic.getAir() ? 1 : 0,
                sonic != null && sonic.getRolling() ? 1 : 0,
                sonicLock ? 1 : 0,
                sonicObjectControlled ? 1 : 0,
                sonic != null ? sonic.getYRadius() : -1,
                sonic != null ? sonic.getStandYRadius() : -1,
                sonicForceRight ? 1 : 0,
                sonicQueuedLock ? 1 : 0,
                sonicQueuedLockValue,
                sonicQueuedForceRight ? 1 : 0,
                sonicQueuedForceRightValue,
                signpostState,
                wallProbe,
                nearbyObjects(tails),
                nearbyPlaneSwitchers(tails));
        if (current.frame() >= 872 && current.frame() <= 878) {
            System.out.printf("  sensors=%s%n", groundSensors);
        }
        if ((current.frame() >= 140 && current.frame() <= 147)
                || (current.frame() >= 2118 && current.frame() <= 2125)) {
            System.out.printf("  air=%s ground=%s ceiling=%s%n", quadrantInfo, groundSensors, ceilingSensors);
            System.out.printf("  ground-detail=%s%n", groundSensorDetails);
        }
    }

    private static SignpostObjectInstance findNearestSignpost() {
        ObjectManager objectManager = GameServices.level() != null ? GameServices.level().getObjectManager() : null;
        if (objectManager == null) {
            return null;
        }
        return objectManager.getActiveObjects().stream()
                .filter(SignpostObjectInstance.class::isInstance)
                .map(SignpostObjectInstance.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static String formatSignpostState(SignpostObjectInstance signpost) {
        if (signpost == null) {
            return "-";
        }
        int slot = signpost instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
        return String.format(
                "slot=%d state=%s spawned=%s walkFrame=%s",
                slot,
                readField(signpost, "routineState"),
                readField(signpost, "resultsSpawned"),
                readField(signpost, "walkOffEnteredVIntRunCount"));
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            return "<missing>";
        } catch (ReflectiveOperationException e) {
            return "<err>";
        }
    }

    private static Integer readControllerInt(SidekickCpuController controller, String fieldName) {
        if (controller == null) {
            return null;
        }
        try {
            Field field = SidekickCpuController.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Integer) field.get(controller);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read SidekickCpuController." + fieldName, e);
        }
    }

    private static void maybePrintFirstSonicSubMismatch(TraceFrame current) {
        if (firstSonicSubMismatchPrinted) {
            return;
        }
        AbstractPlayableSprite sonic = GameServices.sprites() != null
                ? GameServices.sprites().getSprite("sonic") instanceof AbstractPlayableSprite playable
                    ? playable : null
                : null;
        if (sonic == null) {
            return;
        }
        if (sonic.getXSubpixelRaw() != current.xSub()
                || sonic.getYSubpixelRaw() != current.ySub()) {
            firstSonicSubMismatchPrinted = true;
            System.out.printf(
                    "FIRST SONIC SUB MISMATCH frame=%d expSub=(%04X,%04X) actSub=(%04X,%04X) "
                            + "expS=(%04X,%04X) actS=(%04X,%04X) expSpd=(%04X,%04X,%04X) "
                            + "actSpd=(%04X,%04X,%04X)%n",
                    current.frame(),
                    current.xSub() & 0xFFFF,
                    current.ySub() & 0xFFFF,
                    sonic.getXSubpixelRaw() & 0xFFFF,
                    sonic.getYSubpixelRaw() & 0xFFFF,
                    current.x() & 0xFFFF,
                    current.y() & 0xFFFF,
                    sonic.getCentreX() & 0xFFFF,
                    sonic.getCentreY() & 0xFFFF,
                    current.xSpeed() & 0xFFFF,
                    current.ySpeed() & 0xFFFF,
                    current.gSpeed() & 0xFFFF,
                    sonic.getXSpeed() & 0xFFFF,
                    sonic.getYSpeed() & 0xFFFF,
                    sonic.getGSpeed() & 0xFFFF);
        }
    }

    private static void maybePrintFirstSonicPixelMismatch(TraceFrame current) {
        if (firstSonicPixelMismatchPrinted) {
            return;
        }
        AbstractPlayableSprite sonic = GameServices.sprites() != null
                ? GameServices.sprites().getSprite("sonic") instanceof AbstractPlayableSprite playable
                    ? playable : null
                : null;
        if (sonic == null) {
            return;
        }
        if (sonic.getCentreX() != current.x() || sonic.getCentreY() != current.y()) {
            firstSonicPixelMismatchPrinted = true;
            System.out.printf(
                    "FIRST SONIC PIXEL MISMATCH frame=%d expS=(%04X,%04X) actS=(%04X,%04X) "
                            + "expSub=(%04X,%04X) actSub=(%04X,%04X) expSpd=(%04X,%04X,%04X) "
                            + "actSpd=(%04X,%04X,%04X)%n",
                    current.frame(),
                    current.x() & 0xFFFF,
                    current.y() & 0xFFFF,
                    sonic.getCentreX() & 0xFFFF,
                    sonic.getCentreY() & 0xFFFF,
                    current.xSub() & 0xFFFF,
                    current.ySub() & 0xFFFF,
                    sonic.getXSubpixelRaw() & 0xFFFF,
                    sonic.getYSubpixelRaw() & 0xFFFF,
                    current.xSpeed() & 0xFFFF,
                    current.ySpeed() & 0xFFFF,
                    current.gSpeed() & 0xFFFF,
                    sonic.getXSpeed() & 0xFFFF,
                    sonic.getYSpeed() & 0xFFFF,
                    sonic.getGSpeed() & 0xFFFF);
        }
    }

    private static void maybePrintFirstSubMismatch(TraceFrame current) {
        if (firstSubMismatchPrinted || current.sidekick() == null) {
            return;
        }
        SpriteManager spriteManager = GameServices.sprites();
        AbstractPlayableSprite tails = spriteManager != null && !spriteManager.getSidekicks().isEmpty()
                ? spriteManager.getSidekicks().getFirst()
                : null;
        if (tails == null) {
            return;
        }
        if (tails.getXSubpixelRaw() != current.sidekick().xSub()
                || tails.getYSubpixelRaw() != current.sidekick().ySub()) {
            firstSubMismatchPrinted = true;
            System.out.printf(
                    "FIRST TAILS SUB MISMATCH frame=%d expSub=(%04X,%04X) actSub=(%04X,%04X) "
                            + "expT=(%04X,%04X) actT=(%04X,%04X) expSpd=(%04X,%04X,%04X) "
                            + "actSpd=(%04X,%04X,%04X)%n",
                    current.frame(),
                    current.sidekick().xSub() & 0xFFFF,
                    current.sidekick().ySub() & 0xFFFF,
                    tails.getXSubpixelRaw() & 0xFFFF,
                    tails.getYSubpixelRaw() & 0xFFFF,
                    current.sidekick().x() & 0xFFFF,
                    current.sidekick().y() & 0xFFFF,
                    tails.getCentreX() & 0xFFFF,
                    tails.getCentreY() & 0xFFFF,
                    current.sidekick().xSpeed() & 0xFFFF,
                    current.sidekick().ySpeed() & 0xFFFF,
                    current.sidekick().gSpeed() & 0xFFFF,
                    tails.getXSpeed() & 0xFFFF,
                    tails.getYSpeed() & 0xFFFF,
                    tails.getGSpeed() & 0xFFFF);
        }
    }

    private static void maybePrintFirstPixelMismatch(TraceFrame current) {
        if (firstPixelMismatchPrinted || current.sidekick() == null) {
            return;
        }
        SpriteManager spriteManager = GameServices.sprites();
        AbstractPlayableSprite tails = spriteManager != null && !spriteManager.getSidekicks().isEmpty()
                ? spriteManager.getSidekicks().getFirst()
                : null;
        if (tails == null) {
            return;
        }
        if (tails.getCentreX() != current.sidekick().x() || tails.getCentreY() != current.sidekick().y()) {
            firstPixelMismatchPrinted = true;
            System.out.printf(
                    "FIRST TAILS PIXEL MISMATCH frame=%d expT=(%04X,%04X) actT=(%04X,%04X) "
                            + "expSub=(%04X,%04X) actSub=(%04X,%04X) expSpd=(%04X,%04X,%04X) "
                            + "actSpd=(%04X,%04X,%04X)%n",
                    current.frame(),
                    current.sidekick().x() & 0xFFFF,
                    current.sidekick().y() & 0xFFFF,
                    tails.getCentreX() & 0xFFFF,
                    tails.getCentreY() & 0xFFFF,
                    current.sidekick().xSub() & 0xFFFF,
                    current.sidekick().ySub() & 0xFFFF,
                    tails.getXSubpixelRaw() & 0xFFFF,
                    tails.getYSubpixelRaw() & 0xFFFF,
                    current.sidekick().xSpeed() & 0xFFFF,
                    current.sidekick().ySpeed() & 0xFFFF,
                    current.sidekick().gSpeed() & 0xFFFF,
                    tails.getXSpeed() & 0xFFFF,
                    tails.getYSpeed() & 0xFFFF,
                    tails.getGSpeed() & 0xFFFF);
        }
    }

    private static void applyPreTraceState(TraceData trace, HeadlessTestFixture fixture) {
        // Trace data is read-only comparison context.
    }

    private static String nearbyObjects(AbstractPlayableSprite tails) {
        ObjectManager objectManager = GameServices.level() != null
                ? GameServices.level().getObjectManager()
                : null;
        if (tails == null || objectManager == null) {
            return "-";
        }
        int tailsX = tails.getCentreX() & 0xFFFF;
        int tailsY = tails.getCentreY() & 0xFFFF;
        return objectManager.getActiveObjects().stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(obj -> obj.getSpawn() != null)
                .sorted(Comparator.comparingInt(obj -> {
                    int dx = Math.abs((obj.getX() & 0xFFFF) - tailsX);
                    int dy = Math.abs((obj.getY() & 0xFFFF) - tailsY);
                    return dx + dy;
                }))
                .limit(6)
                .map(obj -> String.format("slot=%d id=%02X sub=%02X rf=%02X %s @%04X,%04X",
                        obj.getSlotIndex(),
                        obj.getSpawn() != null ? obj.getSpawn().objectId() & 0xFF : 0,
                        obj.getSpawn() != null ? obj.getSpawn().subtype() & 0xFF : 0,
                        obj.getSpawn() != null ? obj.getSpawn().renderFlags() & 0xFF : 0,
                        obj.getClass().getSimpleName(),
                        obj.getX() & 0xFFFF,
                        obj.getY() & 0xFFFF))
                .toList()
                .stream()
                .reduce((left, right) -> left + " | " + right)
                .orElse("-");
    }

    private static String nearbyPlaneSwitchers(AbstractPlayableSprite tails) {
        ObjectManager objectManager = GameServices.level() != null
                ? GameServices.level().getObjectManager()
                : null;
        if (tails == null || objectManager == null) {
            return "-";
        }
        int tailsX = tails.getCentreX() & 0xFFFF;
        int tailsY = tails.getCentreY() & 0xFFFF;
        return objectManager.getActiveSpawns().stream()
                .filter(spawn -> spawn.objectId() == 0x03)
                .sorted(Comparator.comparingInt(spawn -> {
                    int dx = Math.abs((spawn.x() & 0xFFFF) - tailsX);
                    int dy = Math.abs((spawn.y() & 0xFFFF) - tailsY);
                    return dx + dy;
                }))
                .limit(3)
                .map(spawn -> String.format("(%04X,%04X sub=%02X rf=%02X side=%d)",
                        spawn.x() & 0xFFFF,
                        spawn.y() & 0xFFFF,
                        spawn.subtype() & 0xFF,
                        spawn.renderFlags() & 0xFF,
                        objectManager.getPlaneSwitcherSideState(spawn)))
                .reduce((left, right) -> left + " | " + right)
                .orElse("-");
    }

    private static String formatGroundSensors(AbstractPlayableSprite sprite) {
        return formatSensors(sprite != null ? sprite.getGroundSensors() : null);
    }

    private static String formatCeilingSensors(AbstractPlayableSprite sprite) {
        return formatSensors(sprite != null ? sprite.getCeilingSensors() : null);
    }

    private static String formatSensors(Sensor[] sensors) {
        if (sensors == null || sensors.length < 2) {
            return "-";
        }
        return "L=" + formatSensor(sensors[0].scan()) + " R=" + formatSensor(sensors[1].scan());
    }

    private static String formatAirQuadrant(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return "-";
        }
        int motionAngle = TrigLookupTable.calcAngle(sprite.getXSpeed(), sprite.getYSpeed()) & 0xFF;
        int quadrant = ((motionAngle - 0x20) & 0xC0) & 0xFF;
        return String.format("motion=%02X quad=%02X", motionAngle, quadrant);
    }

    private static String formatGroundSensorWorldDetails(AbstractPlayableSprite sprite) {
        if (sprite == null || GameServices.level() == null) {
            return "-";
        }
        Sensor[] sensors = sprite.getGroundSensors();
        if (sensors == null || sensors.length < 2) {
            return "-";
        }
        return "L=" + describeGroundSensor(sprite, sensors[0]) + " R=" + describeGroundSensor(sprite, sensors[1]);
    }

    private static String describeGroundSensor(AbstractPlayableSprite sprite, Sensor sensor) {
        int worldX = sprite.getCentreX() + sensor.getX();
        int worldY = sprite.getCentreY() + sensor.getY();
        ChunkDesc desc = GameServices.level().getChunkDescAt(
                sprite.getLayer(),
                worldX,
                worldY,
                sprite.isLoopLowPlane());
        SolidTile tile = desc == null ? null : GameServices.level().getSolidTileForChunkDesc(desc, sprite.getTopSolidBit());
        int index = worldX & 0x0F;
        if (desc != null && desc.getHFlip()) {
            index = 15 - index;
        }
        Integer metric = null;
        if (tile != null) {
            int rawMetric = tile.getHeightAt((byte) index);
            if (rawMetric != 0 && rawMetric != 16 && desc != null && desc.getVFlip()) {
                rawMetric = -rawMetric;
            }
            metric = rawMetric;
        }
        return String.format("p=(%04X,%04X) idx=%02X tile=%s hf=%d vf=%d metric=%s",
                worldX & 0xFFFF,
                worldY & 0xFFFF,
                index & 0xFF,
                tile != null ? String.format("%d", tile.getIndex()) : "-",
                desc != null && desc.getHFlip() ? 1 : 0,
                desc != null && desc.getVFlip() ? 1 : 0,
                metric != null ? String.format("%d", metric) : "-");
    }

    private static String formatSensor(SensorResult result) {
        if (result == null) {
            return "null";
        }
        return String.format("(d=%d a=%02X tid=%d dir=%s)",
                (int) result.distance(),
                result.angle() & 0xFF,
                result.tileId(),
                result.direction());
    }

    private static String formatCalcRoomInFront(AbstractPlayableSprite sprite) {
        if (sprite == null || sprite.getPushSensors() == null || sprite.getPushSensors().length < 2) {
            return "-";
        }
        int angle = sprite.getAngle() & 0xFF;
        short gSpeed = sprite.getGSpeed();
        int angleCheck = (angle + 0x40) & 0xFF;
        if ((angleCheck & 0x80) != 0 || gSpeed == 0) {
            return String.format("skip(a=%02X gs=%04X)", angle, gSpeed & 0xFFFF);
        }
        int sensorIndex = gSpeed >= 0 ? 1 : 0;
        Sensor sensor = sprite.getPushSensors()[sensorIndex];
        short predictedDx = (short) (((sprite.getXSubpixel() & 0xFF) + sprite.getXSpeed()) >> 8);
        short predictedDy = (short) (((sprite.getYSubpixel() & 0xFF) + sprite.getYSpeed()) >> 8);
        int wallRotation = gSpeed < 0 ? 0x40 : 0xC0;
        int wallRotatedAngle = (angle + wallRotation) & 0xFF;
        short dynamicYOffset = (short) (((wallRotatedAngle & 0x38) == 0) ? 8 : 0);
        int predictedX = sprite.getCentreX() + predictedDx;
        int predictedY = sprite.getCentreY() + predictedDy + dynamicYOffset;

        boolean wasActive = sensor.isActive();
        byte savedY = sensor.getY();
        sensor.setActive(true);
        sensor.setOffset(sensor.getX(), (byte) 0);
        SensorResult result = sensor.scan(predictedDx, (short) (predictedDy + dynamicYOffset));
        sensor.setOffset(sensor.getX(), savedY);
        sensor.setActive(wasActive);
        var objectTerrain = gSpeed < 0
                ? ObjectTerrainUtils.checkLeftWallDist(predictedX, predictedY)
                : ObjectTerrainUtils.checkRightWallDist(predictedX, predictedY);

        return String.format("s%d dx=%d dy=%d yo=%d p=(%04X,%04X) res=%s obj=(d=%d a=%02X tid=%d)",
                sensorIndex,
                predictedDx,
                predictedDy,
                dynamicYOffset,
                predictedX & 0xFFFF,
                predictedY & 0xFFFF,
                formatSensor(result),
                objectTerrain.distance(),
                objectTerrain.angle() & 0xFF,
                objectTerrain.tileIndex());
    }

    private static ObjectRef ridingObject(AbstractPlayableSprite sprite) {
        if (sprite == null || GameServices.level() == null) {
            return new ObjectRef(-1, -1, -1, -1);
        }
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return new ObjectRef(-1, -1, -1, -1);
        }
        ObjectInstance instance = objectManager.getRidingObject(sprite);
        if (!(instance instanceof AbstractObjectInstance aoi)) {
            return new ObjectRef(-1, -1, -1, -1);
        }
        int objectId = aoi.getSpawn() != null ? aoi.getSpawn().objectId() : -1;
        return new ObjectRef(
                aoi.getSlotIndex(),
                objectId,
                aoi.getX() & 0xFFFF,
                aoi.getY() & 0xFFFF);
    }

    private static Path findBk2File() throws Exception {
        try (var files = Files.list(TRACE_DIR)) {
            return files.filter(path -> path.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private record ObjectRef(int slot, int objectId, int x, int y) {
        @Override
        public String toString() {
            if (slot < 0) {
                return "-";
            }
            return String.format("slot=%d id=%02X @%04X,%04X", slot, objectId & 0xFF, x, y);
        }
    }
}
