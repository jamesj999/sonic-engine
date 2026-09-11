package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.objects.HCZSnakeBlocksObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.physics.TrigLookupTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for HCZ Snake Blocks (Object 0x67) motion algorithm.
 *
 * <p>Validates the cosine-interpolated square path from ROM sub_25770:
 * 4 quadrants, 256 frames each, with 128-frame corner waits created
 * by clamping angle to 0x80 minimum before the trig lookup.
 */
@ExtendWith(SingletonResetExtension.class)
class TestS3kHczSnakeBlocksObject {

    private static final int OBJECT_ID = 0x67;
    private static final int BASE_X = 0x0100;
    private static final int BASE_Y = 0x0600;

    @BeforeEach
    void setUp() {
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void registryCreatesSnakeBlocksInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x1200, 0x0600, OBJECT_ID, 0x00, 0x00, false, 0));
        assertInstanceOf(HCZSnakeBlocksObjectInstance.class, instance);
    }

    @Test
    void continuedRideUsesPostMoveXAsItsZeroDeltaCarryReference() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x00, 0x00);

        assertFalse(instance.carriesRiderOnHorizontalMove(null),
                "Obj67 loads its post-movement x_pos into d4 before SolidObjectFull");
    }

    @Test
    void motionClampsAngleDuringCornerWait() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x00, 0x00);
        instance.update(0, null);
        assertEquals(BASE_X - 64, instance.getX());
        assertEquals(BASE_Y - 64, instance.getY());
    }

    @Test
    void motionUsesCosineSweepDuringMovement() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x00, 0x00);
        for (int i = 0; i < 0xC0; i++) {
            instance.update(i, null);
        }
        assertEquals(BASE_X, instance.getX(), "Mid-sweep: cosine is 0 at angle 0xC0");
        assertEquals(BASE_Y - 64, instance.getY(), "Mid-sweep: y stays at baseY - 64");
    }

    @Test
    void quadrantAdvancesOnAngleWrapCW() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x00, 0x00);
        for (int i = 0; i < 256; i++) {
            instance.update(i, null);
        }
        assertEquals(BASE_X + 64, instance.getX(), "Quadrant 1 corner: x = baseX + 64");
        assertEquals(BASE_Y - 64, instance.getY(), "Quadrant 1 corner: y = baseY - 64");
    }

    @Test
    void fullSquareCycleReturnsToStart() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x00, 0x00);
        instance.update(0, null);
        int firstX = instance.getX();
        int firstY = instance.getY();
        for (int i = 1; i <= 1024; i++) {
            instance.update(i, null);
        }
        assertEquals(firstX, instance.getX(), "Full cycle: X returns to start");
        assertEquals(firstY, instance.getY(), "Full cycle: Y returns to start");
    }

    @Test
    void ccwDirectionDecrementsQuadrant() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x80, 0x00);
        instance.update(0, null);
        int expectedCos = TrigLookupTable.cosHex(0xFF) >> 2;
        assertEquals(BASE_X - 64, instance.getX(), "CCW quadrant 3: x = baseX - d2");
        assertEquals(BASE_Y - expectedCos, instance.getY(), "CCW quadrant 3: y = baseY - cos/4");
    }

    @Test
    void despawnsWhenBasePositionMovesPastCameraCullRange() {
        HCZSnakeBlocksObjectInstance instance = new HCZSnakeBlocksObjectInstance(
                new ObjectSpawn(0x1400, 0x0600, OBJECT_ID, 0x00, 0x00, false, 0));
        instance.setServices(TestEnvironment.objectServices());
        GameServices.camera().setX((short) 0);
        instance.update(0, null);
        assertTrue(instance.isDestroyed());
    }

    @Test
    void despawnsWhenCameraIsFarAheadOfObject() {
        HCZSnakeBlocksObjectInstance instance = new HCZSnakeBlocksObjectInstance(
                new ObjectSpawn(0x0100, 0x0600, OBJECT_ID, 0x00, 0x00, false, 0));
        instance.setServices(TestEnvironment.objectServices());
        GameServices.camera().setX((short) 0x1400);
        instance.update(0, null);
        assertTrue(instance.isDestroyed(), "Should despawn when camera is far ahead");
    }

    @Test
    void doesNotDespawnWhenCameraIsWithinCoarseBackWindow() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x00, 0x00);
        GameServices.camera().setX((short) 0x0180);
        instance.update(0, null);
        assertFalse(instance.isDestroyed());
    }

    @Test
    void despawnsOnceCameraMovesOneChunkPastBaseXPlus128() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x00, 0x00);
        GameServices.camera().setX((short) 0x0200);
        instance.update(0, null);
        assertTrue(instance.isDestroyed(),
                "Camera coarse-back should advance to 0x0180 here, putting the base X behind the allowed window");
    }

    @Test
    void verifiesAllFourCorners() {
        HCZSnakeBlocksObjectInstance instance = createInstance(0x00, 0x00);

        instance.update(0, null);
        assertEquals(BASE_X - 64, instance.getX(), "Q0 corner X (top-left)");
        assertEquals(BASE_Y - 64, instance.getY(), "Q0 corner Y (top-left)");

        for (int i = 1; i < 256; i++) instance.update(i, null);
        instance.update(256, null);
        assertEquals(BASE_X + 64, instance.getX(), "Q1 corner X (top-right)");
        assertEquals(BASE_Y - 64, instance.getY(), "Q1 corner Y (top-right)");

        for (int i = 257; i < 512; i++) instance.update(i, null);
        instance.update(512, null);
        assertEquals(BASE_X + 64, instance.getX(), "Q2 corner X (bottom-right)");
        assertEquals(BASE_Y + 64, instance.getY(), "Q2 corner Y (bottom-right)");

        for (int i = 513; i < 768; i++) instance.update(i, null);
        instance.update(768, null);
        assertEquals(BASE_X - 64, instance.getX(), "Q3 corner X (bottom-left)");
        assertEquals(BASE_Y + 64, instance.getY(), "Q3 corner Y (bottom-left)");
    }

    /**
     * Verify layout data: 5 blocks per group, angle spacing 0x16,
     * leading block (highest angle 0x58) is last in the layout file.
     */
    @Test
    void snakeGroupAngleSpacing() {
        int[] angles = {0x00, 0x16, 0x2C, 0x42, 0x58};
        for (int startAngle : angles) {
            HCZSnakeBlocksObjectInstance block = createInstance(startAngle, 0x01);
            assertFalse(block.isDestroyed());
            block.update(0, null);
            assertFalse(block.isDestroyed(),
                    "Block with angle 0x" + Integer.toHexString(startAngle)
                            + " should stay active in the default camera window");
        }
    }

    private HCZSnakeBlocksObjectInstance createInstance(int subtype, int renderFlags) {
        HCZSnakeBlocksObjectInstance instance = new HCZSnakeBlocksObjectInstance(
                new ObjectSpawn(BASE_X, BASE_Y, OBJECT_ID, subtype, renderFlags, false, 0));
        instance.setServices(TestEnvironment.objectServices());
        GameServices.camera().setX((short) 0);
        return instance;
    }
}

