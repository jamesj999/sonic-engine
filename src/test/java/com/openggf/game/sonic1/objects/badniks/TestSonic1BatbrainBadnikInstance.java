package com.openggf.game.sonic1.objects.badniks;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class TestSonic1BatbrainBadnikInstance {

    @Test
    void flyUpUsesNativeObjHitCeilingDistanceWhenRehanging() throws Exception {
        Sonic1BatbrainBadnikInstance batbrain = new Sonic1BatbrainBadnikInstance(
                new ObjectSpawn(0x0F60, 0x048C, 0x55, 0, 0, false, 0));
        setPrivateInt(batbrain, "state", 3);
        SubpixelMotion.State motion = getMotionState(batbrain);
        motion.xSub = 0x3456;
        motion.ySub = 0x1200;

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkNativeUpwardCeilingDist(0x0F60, 0x048C, 0x0C))
                    .thenReturn(new TerrainCheckResult(-1, (byte) 0, 0));

            batbrain.updateMovement(0, null);

            terrain.verify(() -> ObjectTerrainUtils.checkNativeUpwardCeilingDist(0x0F60, 0x048C, 0x0C));
            assertEquals(0x048D, batbrain.getY(),
                    "ROM sub.w d1,obY must preserve the native upward-probe distance");
            assertEquals(0x3456, motion.xSub,
                    "ROM andi.w on obX must leave the fractional position word intact");
            assertEquals(0x1200, motion.ySub,
                    "ROM sub.w on obY must leave the fractional position word intact");
        }
    }

    private static SubpixelMotion.State getMotionState(Object target) throws Exception {
        Field field = target.getClass().getDeclaredField("motionState");
        field.setAccessible(true);
        return (SubpixelMotion.State) field.get(target);
    }

    private static void setPrivateInt(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
