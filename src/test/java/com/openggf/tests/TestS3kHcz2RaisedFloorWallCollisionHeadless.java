package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kHCZEvents;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kHcz2RaisedFloorWallCollisionHeadless {
    private static final int PRIME_END_SEQUENCE_TOP_LEFT_X = 0x0C40;
    private static final int PRIME_END_SEQUENCE_TOP_LEFT_Y = 0x0700;
    private static final int START_TOP_LEFT_X = 15860;
    private static final int START_TOP_LEFT_Y = 2270;
    private static final int MAX_FALL_FRAMES = 24;
    private static final int MAX_WALK_FRAMES = 96;
    private static final int LARGE_UPWARD_STEP_PIXELS = 8;
    private static final int MAX_PRIME_FRAMES = 8;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private AbstractPlayableSprite player;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_HCZ, 1);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        player = fixture.sprite();
    }

    @Test
    void leftWallContactDoesNotSnapSonicUpwardAfterRaisedFloorSequence() {
        StringBuilder trace = new StringBuilder();

        primePostWallSequenceState(trace);
        placePlayerAtOverlayTopLeft(START_TOP_LEFT_X, START_TOP_LEFT_Y);

        trace.append("afterPrime bgCollisionFlag=")
                .append(GameServices.gameState().isBackgroundCollisionFlag())
                .append('\n');
        waitForGrounded(trace);

        TraceFrame previous = snapshot(-1);
        trace.append(previous.describe()).append('\n');

        for (int frame = 0; frame < MAX_WALK_FRAMES; frame++) {
            fixture.stepFrame(false, false, true, false, false);
            TraceFrame current = snapshot(frame);
            trace.append(current.describe()).append('\n');

            boolean collisionCandidate = current.pushing
                    || current.centreX >= previous.centreX
                    || current.xSpeed > 0;
            boolean largeUpwardSnap = previous.centreY - current.centreY >= LARGE_UPWARD_STEP_PIXELS;

            assertFalse(collisionCandidate && current.ySpeed == 0 && largeUpwardSnap,
                    "HCZ2 left wall collision snapped Sonic upward before pushing him away.\n" + trace);

            previous = current;
        }
    }

    private void primePostWallSequenceState(StringBuilder trace) {
        Sonic3kHCZEvents events = hczEvents();
        trace.append("primeStart bgCollisionFlag=")
                .append(GameServices.gameState().isBackgroundCollisionFlag())
                .append(" eventsFg5=")
                .append(events.isEventsFg5())
                .append('\n');

        // Let the HCZ2 wall-chase path arm from the real act start first.
        fixture.stepFrame(false, false, false, false, false);
        trace.append("primeFrame start ").append(snapshot(-100).describe())
                .append(" bgCollisionFlag=")
                .append(GameServices.gameState().isBackgroundCollisionFlag())
                .append('\n');

        // Then move the camera beyond HCZ2_Resize's $C00 threshold so the event
        // manager has to transition out of the wall-chase path in production order.
        placePlayerAtOverlayTopLeft(PRIME_END_SEQUENCE_TOP_LEFT_X, PRIME_END_SEQUENCE_TOP_LEFT_Y);
        for (int frame = 0; frame < MAX_PRIME_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            trace.append("primeFrame ").append(frame)
                    .append(' ')
                    .append(snapshot(-90 + frame).describe())
                    .append(" bgCollisionFlag=")
                    .append(GameServices.gameState().isBackgroundCollisionFlag())
                    .append(" eventsFg5=")
                    .append(events.isEventsFg5())
                    .append('\n');
        }

        assertTrue(!GameServices.gameState().isBackgroundCollisionFlag(),
                "HCZ2 wall-chase BG collision should be off after the end-of-sequence prime.\n" + trace);
    }

    private void placePlayerAtOverlayTopLeft(int topLeftX, int topLeftY) {
        player.setX((short) topLeftX);
        player.setY((short) topLeftY);
        player.setAir(true);
        player.setJumping(false);
        player.setPushing(false);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        fixture.camera().updatePosition(true);
        GameServices.level().getObjectManager().reset(fixture.camera().getX());
    }

    private Sonic3kHCZEvents hczEvents() {
        return ((Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider()).getHczEvents();
    }

    private void waitForGrounded(StringBuilder trace) {
        for (int frame = 0; frame < MAX_FALL_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            TraceFrame current = snapshot(frame - MAX_FALL_FRAMES);
            trace.append(current.describe()).append('\n');
            if (!player.getAir()) {
                return;
            }
        }
        throw new AssertionError("Sonic did not become grounded within " + MAX_FALL_FRAMES + " frames.\n" + trace);
    }

    private TraceFrame snapshot(int frame) {
        int centreX = player.getCentreX();
        int centreY = player.getCentreY();
        int xRadius = player.getXRadius();
        int yRadius = player.getYRadius();
        int feetY = centreY + yRadius;
        int leftX = centreX - xRadius;
        int rightX = centreX + xRadius;
        return new TraceFrame(
                frame,
                player.getX(),
                player.getY(),
                centreX,
                centreY,
                player.getXSpeed(),
                player.getYSpeed(),
                player.getGSpeed(),
                player.getAir(),
                player.getPushing(),
                player.getAngle() & 0xFF,
                String.valueOf(player.getGroundMode()),
                describeSensor(player.getGroundSensors(), 0),
                describeSensor(player.getGroundSensors(), 1),
                describeSensor(player.getPushSensors(), 0),
                describeSensor(player.getPushSensors(), 1),
                describeTerrain(ObjectTerrainUtils.checkFloorDist(centreX, feetY)),
                describeTerrain(ObjectTerrainUtils.checkFloorDist(leftX, feetY)),
                describeTerrain(ObjectTerrainUtils.checkFloorDist(rightX, feetY)),
                describeTerrain(ObjectTerrainUtils.checkLeftWallDist(leftX, centreY)),
                describeTerrain(ObjectTerrainUtils.checkLeftWallDist(leftX, feetY - 1)),
                GameServices.level().getForegroundTileDescriptorAtWorld(centreX, feetY),
                GameServices.level().getForegroundTileDescriptorAtWorld(leftX, feetY),
                GameServices.level().getForegroundTileDescriptorAtWorld(rightX, feetY));
    }

    private String describeSensor(Sensor[] sensors, int index) {
        if (sensors == null || index < 0 || index >= sensors.length) {
            return "none";
        }
        Sensor sensor = sensors[index];
        SensorResult result = sensor.getCurrentResult();
        String resultText = (result == null)
                ? "null"
                : String.format("dist=%d angle=%02X tile=%d dir=%s",
                result.distance(), result.angle() & 0xFF, result.tileId(), result.direction());
        return String.format("active=%s off=(%d,%d) result=%s",
                sensor.isActive(), sensor.getX(), sensor.getY(), resultText);
    }

    private String describeTerrain(TerrainCheckResult result) {
        if (result == null) {
            return "null";
        }
        if (!result.foundSurface()) {
            return "none";
        }
        return String.format("dist=%d angle=%02X tile=%d",
                result.distance(), result.angle() & 0xFF, result.tileIndex());
    }

    private record TraceFrame(
            int frame,
            int x,
            int y,
            int centreX,
            int centreY,
            int xSpeed,
            int ySpeed,
            int gSpeed,
            boolean air,
            boolean pushing,
            int angle,
            String groundMode,
            String leftGroundSensor,
            String rightGroundSensor,
            String leftPushSensor,
            String rightPushSensor,
            String floorCentre,
            String floorLeft,
            String floorRight,
            String leftWallCentre,
            String leftWallFeet,
            int fgCentreFeetDescriptor,
            int fgLeftFeetDescriptor,
            int fgRightFeetDescriptor) {

        String describe() {
            return String.format(
                    "frame=%d topLeft=(%d,%d) centre=(%d,%d) xSpeed=%d ySpeed=%d gSpeed=%d air=%s pushing=%s angle=%02X mode=%s "
                            + "groundL=[%s] groundR=[%s] pushL=[%s] pushR=[%s] "
                            + "floorC=[%s] floorL=[%s] floorR=[%s] leftWallC=[%s] leftWallFeet=[%s] "
                            + "fgFeet=(%04X,%04X,%04X)",
                    frame, x, y, centreX, centreY, xSpeed, ySpeed, gSpeed, air, pushing, angle, groundMode,
                    leftGroundSensor, rightGroundSensor, leftPushSensor, rightPushSensor,
                    floorCentre, floorLeft, floorRight, leftWallCentre, leftWallFeet,
                    fgCentreFeetDescriptor & 0xFFFF, fgLeftFeetDescriptor & 0xFFFF, fgRightFeetDescriptor & 0xFFFF);
        }
    }
}
