package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

/**
 * ROM parity for the MHZ mushroom parachute's steering angle (sub_3F7E2) and
 * wall collision (sub_3F7AE), ported per {@code Obj_MHZMushroomParachute}
 * (asm 83843) / {@code loc_3F51C} (asm 83872-83876).
 */
class TestMhzMushroomParachuteSteering {
    private static final int MHZ_MUSHROOM_PARACHUTE = 0x12;

    @BeforeEach
    void keepMhzFixtureCameraOnParachute() {
        com.openggf.level.objects.AbstractObjectInstance.updateCameraBounds(0x1400, 0x0480, 0x1600, 0x0680, 0);
    }

    @AfterEach
    void resetCameraBounds() {
        com.openggf.level.objects.AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void holdingLeftSweepsAngleFullSwing() {
        MhzMushroomParachuteObjectInstance parachute = new MhzMushroomParachuteObjectInstance(
                new ObjectSpawn(0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);
        parachute.setServices(new TestObjectServices());
        parachute.update(0, player);
        player.setDirectionalInputPressed(false, false, true, false);

        for (int i = 0; i < 8; i++) {
            parachute.tickSteeringForTest();
        }

        assertTrue(parachute.angleForTest() >= 0x10,
                "ROM sub_3F7E2 (loc_3F800) forces the angle non-negative before +2 while LEFT is held, "
                        + "so it should sweep upward toward $80 rather than oscillate 0<->2, got "
                        + parachute.angleForTest());
    }

    @Test
    void holdingRightSweepsAngleFullSwingFromRest() {
        MhzMushroomParachuteObjectInstance parachute = new MhzMushroomParachuteObjectInstance(
                new ObjectSpawn(0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 1, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);
        parachute.setServices(new TestObjectServices());
        parachute.update(0, player);
        player.setDirectionalInputPressed(false, false, false, true);

        for (int i = 0; i < 8; i++) {
            parachute.tickSteeringForTest();
        }

        assertEquals(0x90, parachute.angleForTest(),
                "ROM sub_3F7E2 (loc_3F814) forces the angle non-positive before +2 while RIGHT is held, so "
                        + "starting from $80 (signed -128) each tick should add 2 unsigned and sweep the "
                        + "signed value toward $00 ($80 -> $90 after 8 ticks), not oscillate $80<->$82, got "
                        + Integer.toHexString(parachute.angleForTest()));
    }

    @Test
    void wallCollisionPushesParachuteOutOfRightWallAfterMove() {
        MhzMushroomParachuteObjectInstance parachute = new MhzMushroomParachuteObjectInstance(
                new ObjectSpawn(0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);
        parachute.setServices(new TestObjectServices());
        parachute.update(0, player);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-5, (byte) 0, 0));

            parachute.update(1, player);
        }

        assertEquals(0x14FD, parachute.getX(),
                "sub_3F7AE runs after MoveSprite2 (loc_3F51C: sub_3F7E2 -> MoveSprite2 -> sub_3F7AE) and "
                        + "pushes x_pos back out of the wall by the sensor's penetration distance: "
                        + "angle $00 moves x_pos to $1502, then the -5 right-wall penetration should "
                        + "pull it back to $14FD");
    }

    @Test
    void wallCollisionPushesParachuteOutOfLeftWallAfterMove() {
        MhzMushroomParachuteObjectInstance parachute = new MhzMushroomParachuteObjectInstance(
                new ObjectSpawn(0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);
        parachute.setServices(new TestObjectServices());
        parachute.update(0, player);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-5, (byte) 0, 0));
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());

            parachute.update(1, player);
        }

        assertEquals(0x1507, parachute.getX(),
                "ROM sub_3F7AE's left-wall sensor (sub_FD32) applies `sub.w d1,x_pos` when d1<0, so a "
                        + "negative (penetrating) left-wall distance must push x_pos AWAY from the wall "
                        + "(increase it), not deeper into it: angle $00 moves x_pos to $1502, then the -5 "
                        + "left-wall penetration should push it out to $1507");
    }

    private static TestablePlayableSprite playerAtGrabWindow(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setYSpeed((short) 0x200);
        player.setXSpeed((short) 0x120);
        player.setGSpeed((short) 0x120);
        player.setAir(true);
        return player;
    }
}
