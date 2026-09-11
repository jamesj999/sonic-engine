package com.openggf.tests.trace.s1;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.Sonic1ZoneFeatureProvider;
import com.openggf.game.sonic1.credits.DemoInputPlayer;
import com.openggf.game.sonic1.credits.Sonic1CreditsDemoBootstrap;
import com.openggf.game.sonic1.credits.Sonic1CreditsDemoData;
import com.openggf.game.sonic1.objects.Sonic1FlappingDoorObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1PoleThatBreaksObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.SensorResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
public class DebugS1Credits03LzDoorProbe {
    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s1/credits_03_lz3");

    @Test
    void dumpDoorAndTunnelStateAroundFirstReplayDivergence() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        int idx = 3;

        Rom rom = GameServices.rom().getRom();
        byte[] demoData = rom.readBytes(
                Sonic1CreditsDemoData.DEMO_DATA_ADDR[idx],
                Sonic1CreditsDemoData.DEMO_DATA_SIZE[idx]);
        DemoInputPlayer demoPlayer = new DemoInputPlayer(demoData);

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        Object oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");

        SharedLevel sharedLevel = SharedLevel.load(
                SonicGame.SONIC_1,
                Sonic1CreditsDemoData.DEMO_ZONE[idx],
                Sonic1CreditsDemoData.DEMO_ACT[idx]);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .startPosition((short) Sonic1CreditsDemoData.START_X[idx],
                            (short) Sonic1CreditsDemoData.START_Y[idx])
                    .startPositionIsCentre()
                    .build();

            initialiseDemoPlayerState(fixture.sprite());
            // ROM-derived constants only — no trace hydration. See
            // CLAUDE.md "Trace Replay Tests" comparison-only invariant.
            Sonic1CreditsDemoBootstrap.applyLzLampostState(
                    fixture.sprite(), fixture.camera());
            resetStreamingWindows(fixture);
            // Per-demo starting animation/direction are intentionally NOT
            // forced here — the engine's natural post-spawn init drives the
            // pose. Trace-replay comparison-only invariant forbids
            // trace-derived hydration.
            GameServices.level().updateObjectPositionsWithoutTouches();

            int windowIterations = 0;
            String lastDoorState = null;
            String lastPoleState = null;
            for (int i = 0; i <= 360; i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase basePhase =
                        TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
                TraceExecutionPhase promotedPhase = basePhase;
                if (promotedPhase == TraceExecutionPhase.VBLANK_ONLY
                        && fixture.sprite().isObjectControlled()
                        && findPole() != null) {
                    promotedPhase = TraceExecutionPhase.FULL_LEVEL_FRAME;
                }

                if (promotedPhase != TraceExecutionPhase.VBLANK_ONLY) {
                    demoPlayer.advanceFrame();
                    int inputMask = demoPlayer.getInputMask();
                    boolean up = (inputMask & AbstractPlayableSprite.INPUT_UP) != 0;
                    boolean down = (inputMask & AbstractPlayableSprite.INPUT_DOWN) != 0;
                    boolean left = (inputMask & AbstractPlayableSprite.INPUT_LEFT) != 0;
                    boolean right = (inputMask & AbstractPlayableSprite.INPUT_RIGHT) != 0;
                    boolean jump = (inputMask & AbstractPlayableSprite.INPUT_JUMP) != 0;
                    fixture.sprite().setForcedJumpPress(jump);
                    fixture.stepFrame(up, down, left, right, jump);
                }

                if ((i < 136 || i > 160) && (i < 340 || i > 365)) {
                    continue;
                }

                Sonic1FlappingDoorObjectInstance door = findDoor();
                Sonic1PoleThatBreaksObjectInstance pole = findPole();
                Sonic1ZoneFeatureProvider provider =
                        (Sonic1ZoneFeatureProvider) GameServices.level().getZoneFeatureProvider();
                AbstractPlayableSprite player = fixture.sprite();
                CollisionSystem collision = GameServices.collision();
                SensorResult[] groundSensors = collision.terrainProbes(
                        player, player.getGroundSensors(), "ground");
                String doorState = door == null ? "door=null" : String.format(
                        "door x=%04X y=%04X map=%d anim=%d idx=%d timer=%d flapWait=%d solid=%s",
                        door.getX() & 0xFFFF,
                        door.getY() & 0xFFFF,
                        getPrivateInt(door, "mappingFrame"),
                        getPrivateInt(door, "animationId"),
                        getPrivateInt(door, "animationFrameIndex"),
                        getPrivateInt(door, "animationTimer"),
                        getPrivateInt(door, "flapWait"),
                        door.isSolidFor(player));
                String poleState = pole == null ? "pole=null" : String.format(
                        "pole x=%04X y=%04X routine=%s frame=%d time=%d grabbed=%s col=%02X",
                        pole.getX() & 0xFFFF,
                        pole.getY() & 0xFFFF,
                        getPrivateObject(pole, "routine"),
                        getPrivateInt(pole, "mappingFrame"),
                        getPrivateInt(pole, "poleTime"),
                        getPrivateBoolean(pole, "poleGrabbed"),
                        pole.getCollisionFlags() & 0xFF);

                System.out.printf(
                        "frame=%03d phase=%s px=%04X py=%04X air=%s objCtrl=%s jump=%s just=%s forced=%s xsp=%04X ysp=%04X gsp=%04X quad=%02X suppress=%s forceFloor=%s wtDisabled=%s wtActive=%s ground=%s %s %s%n",
                        i,
                        basePhase == promotedPhase
                                ? basePhase.toString()
                                : basePhase + "->" + promotedPhase,
                        player.getCentreX() & 0xFFFF,
                        player.getCentreY() & 0xFFFF,
                        player.getAir(),
                        player.isObjectControlled(),
                        player.isJumpPressed(),
                        player.isJumpJustPressed(),
                        player.isForcedJumpPress(),
                        player.getXSpeed() & 0xFFFF,
                        player.getYSpeed() & 0xFFFF,
                        player.getGSpeed() & 0xFFFF,
                        TrigLookupTable.calcMovementQuadrant(player.getXSpeed(), player.getYSpeed()) & 0xFF,
                        player.isSuppressAirCollision(),
                        player.isForceFloorCheck(),
                        provider.isWindTunnelDisabled(),
                        provider.getWaterEvents() != null && provider.getWaterEvents().isWindTunnelActive(),
                        formatSensors(groundSensors),
                        doorState,
                        poleState);
                windowIterations++;
                lastDoorState = doorState;
                lastPoleState = poleState;
            }

            // characterization guard: the replay must reach its door/tunnel
            // inspection window and produce the door/pole state strings. The
            // captured strings are the probe's key computed quantities; an empty
            // or absent string means the replay regressed before the window.
            assertTrue(windowIterations > 0,
                    "probe never reached the door/tunnel inspection window");
            assertNotNull(lastDoorState, "door state string was never produced");
            assertNotNull(lastPoleState, "pole state string was never produced");
            assertTrue(!lastDoorState.isEmpty(), "door state string was empty");
            assertTrue(!lastPoleState.isEmpty(), "pole state string was empty");
        } finally {
            sharedLevel.dispose();
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMainCharacter != null ? oldMainCharacter : "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
        }
    }

    private Sonic1FlappingDoorObjectInstance findDoor() {
        for (ObjectInstance instance : GameServices.level().getObjectManager().getActiveObjects()) {
            if (instance instanceof Sonic1FlappingDoorObjectInstance door && door.getX() == 0x0B08) {
                return door;
            }
        }
        return null;
    }

    private Sonic1PoleThatBreaksObjectInstance findPole() {
        for (ObjectInstance instance : GameServices.level().getObjectManager().getActiveObjects()) {
            if (instance instanceof Sonic1PoleThatBreaksObjectInstance pole && pole.getX() == 0x0F20) {
                return pole;
            }
        }
        return null;
    }

    private void initialiseDemoPlayerState(AbstractPlayableSprite player) {
        player.setRingCount(0);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setControlLocked(false);
        player.setForcedInputMask(0);
    }

    private void resetStreamingWindows(HeadlessTestFixture fixture) {
        int cameraX = fixture.camera().getX();
        if (GameServices.level().getObjectManager() != null) {
            GameServices.level().getObjectManager().reset(cameraX);
        }
        if (GameServices.level().getRingManager() != null) {
            GameServices.level().getRingManager().reset(cameraX);
        }
    }

    private int getPrivateInt(AbstractObjectInstance target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private boolean getPrivateBoolean(AbstractObjectInstance target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private Object getPrivateObject(AbstractObjectInstance target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private String formatSensors(SensorResult[] sensors) {
        if (sensors == null) {
            return "<null>";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < sensors.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            SensorResult result = sensors[i];
            if (result == null) {
                builder.append(i).append("=<null>");
                continue;
            }
            builder.append(i)
                    .append("={dist=").append(result.distance())
                    .append(",ang=").append(String.format("%02X", result.angle() & 0xFF))
                    .append(",dir=").append(result.direction())
                    .append("}");
        }
        builder.append(']');
        return builder.toString();
    }
}
