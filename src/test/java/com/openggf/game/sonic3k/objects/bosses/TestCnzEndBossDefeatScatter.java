package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzEndBossDefeatScatter {

    @Test
    void bodyHalvesUseNativeOffsetsFramesAndConstantIndexedVelocities() {
        CnzEndBossInstance boss = boss();
        CnzEndBossDefeatDebrisChild left = new CnzEndBossDefeatDebrisChild(boss, -8, -0x300);
        CnzEndBossDefeatDebrisChild right = new CnzEndBossDefeatDebrisChild(boss, 8, 0x300);
        left.setServices(servicesWithCamera(0x46C0, 0x0200));
        right.setServices(servicesWithCamera(0x46C0, 0x0200));

        assertEquals(boss.getCentreX() - 0x14, left.getCentreX());
        assertEquals(boss.getCentreX() + 0x14, right.getCentreX());
        assertEquals(0x0B, left.frameForTest());
        assertEquals(0x0C, right.frameForTest());
        assertEquals(-0x100, left.xVelocityForTest());
        assertEquals(0x100, right.xVelocityForTest());
        assertEquals(-0x100, left.yVelocityForTest());
        assertEquals(-0x100, right.yVelocityForTest());

        int leftY = left.getCentreY();
        left.update(0, null);
        assertEquals(boss.getCentreX() - 0x15, left.getCentreX());
        assertEquals(leftY - 1, left.getCentreY());
        assertEquals(-0xC8, left.yVelocityForTest(),
                "Obj_FlickerMove applies MoveSprite gravity after moving with the old velocity");
        assertFalse(left.visibleForTest(),
                "Obj_FlickerMove's initial bit-0 bchg skips the first flicker draw");
        left.update(1, null);
        assertTrue(left.visibleForTest(), "Obj_FlickerMove alternates rendering each frame");
    }

    @Test
    void flickerMoveUsesNativeCoarseBackAndCameraYDeletionBounds() {
        int cameraX = 0x4700;
        int cameraY = 0x0200;
        int coarseBack = (cameraX - 0x80) & 0xFF80;

        assertFalse(S3kBossFlickerMove.isOutsideNativeBounds(
                coarseBack + 0x280, cameraY + 0x180, cameraX, cameraY));
        assertTrue(S3kBossFlickerMove.isOutsideNativeBounds(
                coarseBack + 0x300, cameraY, cameraX, cameraY));
        assertTrue(S3kBossFlickerMove.isOutsideNativeBounds(
                coarseBack, cameraY + 0x181, cameraX, cameraY));
        assertTrue(S3kBossFlickerMove.isOutsideNativeBounds(
                coarseBack, cameraY - 0x81, cameraX, cameraY));
    }

    @Test
    void armsScatterInPlaceWithSubtypeIndexedVelocityAndNoCollision() {
        int[][] expectedVelocities = {
                {-0x100, -0x100},
                {0x100, -0x100},
                {-0x200, -0x200},
                {0x200, -0x200}
        };
        for (int subtype = 0; subtype < expectedVelocities.length; subtype++) {
            CnzEndBossArmChild arm = new CnzEndBossArmChild(boss(), subtype << 6);
            arm.setServices(servicesWithCamera(0x46C0, 0x0200));
            arm.update(0, null);
            int startX = arm.getCentreX();
            int startY = arm.getCentreY();

            arm.beginDefeatScatter();

            assertEquals(1, arm.frameForTest());
            assertEquals(0, arm.getCollisionFlags());
            assertEquals(expectedVelocities[subtype][0], arm.xVelocityForTest());
            assertEquals(expectedVelocities[subtype][1], arm.yVelocityForTest());
            assertEquals(startX, arm.getCentreX(), "bit-4 setup converts the existing slot in place");
            assertEquals(startY, arm.getCentreY(), "bit-4 setup does not dispatch Obj_FlickerMove yet");
            assertTrue(arm.visibleForTest(), "the bit-4 setup frame still draws mapping frame 1");
            arm.update(1, null);
            assertEquals(startX + (expectedVelocities[subtype][0] >> 8), arm.getCentreX());
            assertEquals(startY + (expectedVelocities[subtype][1] >> 8), arm.getCentreY());
            assertEquals(expectedVelocities[subtype][1] + 0x38, arm.yVelocityForTest(),
                    "Obj_FlickerMove applies gravity after the indexed velocity move");
            assertFalse(arm.visibleForTest(), "the first Obj_FlickerMove dispatch skips drawing");
        }
    }

    @Test
    void magnetScatterSparksUseFrameAOpposedVelocitiesAndSecondFlip() {
        CnzEndBossMagnetChild.DefeatSpark left =
                new CnzEndBossMagnetChild.DefeatSpark(0x4740 - 8, 0x0254, 0);
        CnzEndBossMagnetChild.DefeatSpark right =
                new CnzEndBossMagnetChild.DefeatSpark(0x4740 + 8, 0x0254, 1);
        left.setServices(servicesWithCamera(0x46C0, 0x0200));
        right.setServices(servicesWithCamera(0x46C0, 0x0200));

        assertEquals(0x0A, left.frameForTest());
        assertEquals(0x0A, right.frameForTest());
        assertEquals(-0x200, left.xVelocityForTest());
        assertEquals(0x200, right.xVelocityForTest());
        assertEquals(-0x200, left.yVelocityForTest());
        assertEquals(-0x200, right.yVelocityForTest());
        assertFalse(left.horizontalFlipForTest());
        assertTrue(right.horizontalFlipForTest());

        left.update(0, null);
        right.update(0, null);
        assertEquals(0x4740 - 10, left.getCentreX());
        assertEquals(0x4740 + 10, right.getCentreX());
        assertEquals(-0x200 + 0x38, left.yVelocityForTest());
        assertEquals(-0x200 + 0x38, right.yVelocityForTest());
        assertFalse(left.visibleForTest());
        assertFalse(right.visibleForTest());
    }

    @Test
    void risingMagnetIgnoresFloorOverlapAndThumpsOncePerDescendingImpact() throws Exception {
        CnzEndBossInstance boss = boss();
        int[] thumps = {0};
        CnzEndBossMagnetChild magnet = new CnzEndBossMagnetChild(boss);
        magnet.setServices(new StubObjectServices() {
            @Override public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> null, List::of);
            }

            @Override public void playSfx(int soundId) {
                thumps[0]++;
            }
        });
        magnet.beginDrop();

        setInt(magnet, "yVelocity", 0x100);
        int firstImpactY = magnet.getCentreY();
        magnet.resolveFloorContact(0);
        assertEquals(1, thumps[0]);
        assertEquals(firstImpactY, magnet.getCentreY(),
                "exact floor contact dispatches the callback without changing y_pos");
        assertEquals(-0x80, magnet.yVelocityForTest());

        int risingY = magnet.getCentreY();
        magnet.resolveFloorContact(-3);
        assertEquals(1, thumps[0], "the rising half of a bounce must not re-trigger FloorThump");
        assertEquals(risingY, magnet.getCentreY(), "rising overlap must not floor-snap the magnet");
        assertEquals(-0x80, magnet.yVelocityForTest());

        setInt(magnet, "yVelocity", 0x70);
        magnet.resolveFloorContact(-1);
        assertEquals(2, thumps[0], "the next descending impact emits exactly one new thump");
        assertEquals(0x70, magnet.yVelocityForTest(),
                "the low-speed landing changes routine without clearing y_vel");
        assertTrue(magnet.isLanded());
    }

    @Test
    void armsRealignToSavedPhaseBeforeResettingForNextCycle() throws Exception {
        CnzEndBossInstance boss = boss();
        CnzEndBossArmChild arm = new CnzEndBossArmChild(boss, 0);
        arm.setServices(new StubObjectServices());

        setRoutine(boss, CnzEndBossInstance.Routine.ALIGN);
        arm.update(0, null);
        assertEquals(0, arm.angleForTest(), "ALIGN has not set the parent bit-3 equivalent yet");
        assertEquals(1, arm.frameForTest());

        setRoutine(boss, CnzEndBossInstance.Routine.CHARGE);
        arm.update(1, null);
        assertEquals(1, arm.angleForTest(), "CHARGE begins the native bit-3 interval");
        assertEquals(3, arm.frameForTest());

        setRoutine(boss, CnzEndBossInstance.Routine.WIND_DOWN);
        for (int frame = 2; frame <= 66; frame++) {
            arm.update(frame, null);
        }
        assertEquals(66, arm.angleForTest(), "WIND_DOWN moves before each wait callback");
        assertTrue(arm.isRealigningForTest(),
                "loc_6EA70 enters saved-phase realignment when speed is already one");

        setRoutine(boss, CnzEndBossInstance.Routine.DESCEND);
        arm.update(67, null);
        assertEquals(67, arm.angleForTest(),
                "loc_6EA92 keeps realigning after the parent clears bit 3 for descent");
        assertTrue(arm.isRealigningForTest());

        int frame = 68;
        while (arm.isRealigningForTest() && frame < 0x200) {
            arm.update(frame++, null);
        }
        assertEquals(0, arm.angleForTest(), "realignment stops on the phase saved at activation");
        assertFalse(arm.isRealigningForTest());

        arm.update(frame++, null);
        assertEquals(1, arm.frameForTest(), "loc_6EAB2 resets after observing cleared parent bit 3");

        setRoutine(boss, CnzEndBossInstance.Routine.ALIGN);
        arm.update(frame++, null);
        assertEquals(0, arm.angleForTest(), "the next ALIGN remains inactive at the saved phase");
        setRoutine(boss, CnzEndBossInstance.Routine.CHARGE);
        arm.update(frame, null);
        assertEquals(1, arm.angleForTest());
    }

    @Test
    void defeatWaitStartsOnTheObjectPassAfterBossDefeatedInstallsIt() throws Exception {
        CnzEndBossInstance boss = boss();
        boss.setServices(new StubObjectServices());
        setRoutine(boss, CnzEndBossInstance.Routine.DEFEATED);
        setInt(boss, "defeatWaitTimer", 0x3F);
        setBoolean(boss, "defeatWaitJustStarted", true);

        boss.update(0, null);
        assertEquals(0x3F, field(boss, "defeatWaitTimer").getInt(boss),
                "BossDefeated installs the wait; its first decrement belongs to the next object pass");

        boss.update(1, null);
        assertEquals(0x3E, field(boss, "defeatWaitTimer").getInt(boss));
    }

    private static CnzEndBossInstance boss() {
        return new CnzEndBossInstance(new ObjectSpawn(
                0x4740, 0x0240, 0xA7, 0, 0, false, 0));
    }

    private static StubObjectServices servicesWithCamera(int x, int y) {
        Camera camera = new Camera() {
            @Override public short getX() { return (short) x; }
            @Override public short getY() { return (short) y; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
        return new StubObjectServices() {
            @Override public Camera camera() { return camera; }
        };
    }

    private static void setRoutine(CnzEndBossInstance boss, CnzEndBossInstance.Routine routine)
            throws Exception {
        field(boss, "routine").set(boss, routine);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        field(target, name).setInt(target, value);
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception {
        field(target, name).setBoolean(target, value);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
