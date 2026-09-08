package com.openggf.game.sonic3k.objects;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.game.PlayerCharacter;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestFbzEndBossChildren {
    @Test
    void nestedTablesExpandToTheExactSteadyAndPeakGraph() {
        assertEquals(16, FbzEndBossInstance.nativeSteadyObjectCount());
        assertEquals(25, FbzEndBossInstance.nativePeakObjectCount());
        assertEquals(2, FbzEndBossArmChild.nativeCount());
        assertEquals(2, FbzEndBossJointChild.nativeCount());
        assertEquals(8, FbzEndBossChainLinkChild.nativeCount());
        assertEquals(1, FbzEndBossWeaponChild.nativeCount());
        assertEquals(-0x2C, FbzEndBossWeaponChild.NATIVE_ESCAPE_DY);
        assertEquals(9, FbzEndBossFlameChild.nativeVolleyCount());
        assertEquals(0x2FE4, FbzEndBossChainLinkChild.nativeRootTargetX(0x3000, 0));
        assertEquals(0x301C, FbzEndBossChainLinkChild.nativeRootTargetX(0x3000, 1));
        assertFalse(FbzEndBossChainLinkChild.nativeFlipX(0));
        assertTrue(FbzEndBossChainLinkChild.nativeFlipX(1));
    }

    @Test
    void armMotionAndDebrisTablesMatchChildObjDat70Ef4And70F24() {
        assertEquals(List.of(0x100, 0x100, -0x100, -0x100),
                FbzEndBossArmChild.nativeVelocities());
        assertEquals(List.of(7, 0, 0, 7), FbzEndBossArmChild.nativeWaits());
        assertEquals(List.of(
                new FbzEndBossDebrisChild.Spec(-8, -0x10, -0x200, -0x200, 0xC),
                new FbzEndBossDebrisChild.Spec(8, -0x10, 0x200, -0x200, 0xD),
                new FbzEndBossDebrisChild.Spec(-8, 0x10, -0x300, -0x200, 0xE),
                new FbzEndBossDebrisChild.Spec(8, 0x10, 0x300, -0x200, 0xF)),
                FbzEndBossDebrisChild.armDebrisTable());

        FbzEndBossDebrisChild fractional = new FbzEndBossDebrisChild(
                new ObjectSpawn(100, 100, FbzEndBossInstance.OBJECT_ID, 0xB, 0, false, 0));
        for (int frame = 0; frame < 4; frame++) fractional.update(frame, null);
        assertEquals(99, fractional.getX(), "-$40 must accumulate as -0.25 px/frame in native 8.8 velocity");
    }

    @Test
    void flameStackUsesExactOffsetsTimersAnimationsCollisionAndFireShieldReaction() {
        assertArrayEquals(new int[]{0x50,0x4D,0x4A,0x47,0x44,0x41,0x3E,0x3B,0},
                FbzEndBossFlameChild.nativeTimers());
        assertArrayEquals(new int[]{-0x68,-0x5C,-0x5C,-0x4C,-0x3C,-0x2C,-0x1C,-0x0C,-0x10},
                FbzEndBossFlameChild.nativeYOffsets());
        assertEquals(0x8B, FbzEndBossFlameChild.ACTIVE_COLLISION_FLAGS);
        assertEquals(0x10, FbzEndBossFlameChild.FIRE_SHIELD_REACTION);
    }

    @Test
    void flameRawMultiDelayScriptsActivateAndDeleteOnTheNativeFrames() {
        assertFlameTimeline(0, 108, expandedFrames(
                new int[]{0, 1, 2, 3, 4}, new int[]{2, 3, 4, 5, 6}));
        assertFlameTimeline(3, 102, expandedFrames(
                new int[]{0, 1, 2, 3, 4, 5}, new int[]{2, 2, 3, 4, 5, 6}));
        assertFlameTimeline(8, 67, expandedFrames(
                new int[]{4, 5, 6, 7, 4, 5, 6, 7, 4, 5, 6, 7, 4, 5, 6, 7},
                new int[]{3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3}));
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    void sharedBossExplosionUsesNativeFrameDelayColumnOrder() {
        S3kBossExplosionChild explosion = new S3kBossExplosionChild(0x3000, 0x600);
        explosion.setServices(new TestObjectServices());
        List<Integer> frames = new ArrayList<>();
        int updates = 0;
        while (!explosion.isDestroyed() && updates < 100) {
            explosion.update(updates++, null);
            if (!explosion.isDestroyed()) frames.add(explosion.mappingFrameForTest());
        }
        assertEquals(23, updates,
                "terminal $F4 keeps the SST for one entry before Delete_Current_Sprite");
        List<Integer> expected = new ArrayList<>(
                expandedFrames(new int[]{0, 1, 2, 3, 4, 5}, new int[]{1, 1, 2, 3, 4, 4}));
        expected.add(5); // terminal $F4 still submits the previous mapping
        assertEquals(expected, frames);
    }

    @Test
    void shipAndHeadSelectRobotnikOrEggRoboOnlyFromNativeP1Character() {
        assertFalse(FbzRobotnikHeadChild.usesEggRobo(PlayerCharacter.SONIC_ALONE));
        assertFalse(FbzRobotnikHeadChild.usesEggRobo(PlayerCharacter.TAILS_ALONE));
        assertFalse(FbzRobotnikHeadChild.usesEggRobo(PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(FbzRobotnikHeadChild.usesEggRobo(PlayerCharacter.KNUCKLES));
        assertEquals(0x0B, FbzEndBossShipChild.COMBAT_FRAME);
        assertEquals(5, FbzEndBossShipChild.ESCAPE_FRAME);
        assertEquals(4, FbzEndBossShipChild.NATIVE_CHILD_Y_OFFSET);
        assertEquals(-0x1C, FbzRobotnikHeadChild.NATIVE_Y_OFFSET);
    }

    private static void assertFlameTimeline(int subtype, int expectedDeletionUpdate,
                                            List<Integer> expectedDrawnFrames) {
        FbzEndBossInstance boss = new FbzEndBossInstance(
                new ObjectSpawn(0x3000, 0x600, FbzEndBossInstance.OBJECT_ID, 0, 0, false, 0));
        FbzEndBossWeaponChild weapon = new FbzEndBossWeaponChild(boss, 0, -0x28);
        FbzEndBossFlameChild flame = new FbzEndBossFlameChild(boss, weapon, subtype);
        flame.setServices(new TestObjectServices());

        List<Integer> drawnFrames = new ArrayList<>();
        int update = 0;
        while (!flame.isDestroyed() && update < 200) {
            flame.update(update, null);
            update++;
            if (!flame.isDestroyed() && flame.getCollisionFlags() == FbzEndBossFlameChild.ACTIVE_COLLISION_FLAGS) {
                drawnFrames.add(flame.mappingFrameForTest());
            }
        }

        assertEquals(expectedDeletionUpdate, update);
        assertEquals(expectedDrawnFrames, drawnFrames);
    }

    private static List<Integer> expandedFrames(int[] frames, int[] rawDelays) {
        List<Integer> expanded = new ArrayList<>();
        for (int i = 0; i < frames.length; i++) {
            for (int frame = 0; frame <= rawDelays[i]; frame++) expanded.add(frames[i]);
        }
        return expanded;
    }
}
