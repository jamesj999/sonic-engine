package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTornadoSmokeObjectInstance {

    @Test
    void objectIdsMatchRomSmokePair() {
        assertEquals(0xC3, Sonic2ObjectIds.TORNADO_SMOKE);
        assertEquals(0xC4, Sonic2ObjectIds.TORNADO_SMOKE_2);
    }

    @Test
    void forcedOffsetInitializesLikeObjC3Init() {
        TornadoSmokeObjectInstance smoke = new TornadoSmokeObjectInstance(
                new ObjectSpawn(0x3000, 0x0400, Sonic2ObjectIds.TORNADO_SMOKE, 0x90, 0, false, 0),
                0x1C);

        assertEquals(0x3000 - 0x1C, smoke.getX());
        assertEquals(0x0410, smoke.getY());

        smoke.update(0, null);

        assertEquals(0x3000 - 0x1D, smoke.getX(), "ObjC3 moves left by $100 each frame");
        assertEquals(0x040F, smoke.getY(), "ObjC3 moves upward by $100 each frame");
    }

    @Test
    void advancesEveryEightTicksAndDeletesAfterFrameFour() {
        TornadoSmokeObjectInstance smoke = new TornadoSmokeObjectInstance(
                new ObjectSpawn(0x3000, 0x0400, Sonic2ObjectIds.TORNADO_SMOKE_2, 0x90, 0, false, 0),
                0);

        for (int frame = 0; frame < 8; frame++) {
            smoke.update(frame, null);
        }

        assertEquals(1, smoke.getMappingFrameForTesting());

        for (int frame = 8; frame < 40; frame++) {
            smoke.update(frame, null);
        }

        assertEquals(5, smoke.getMappingFrameForTesting());
        assertTrue(smoke.isDestroyed(), "ROM deletes ObjC3 when mapping_frame reaches 5");
    }

    @Test
    void remainsAliveThroughFrameFour() {
        TornadoSmokeObjectInstance smoke = new TornadoSmokeObjectInstance(
                new ObjectSpawn(0x3000, 0x0400, Sonic2ObjectIds.TORNADO_SMOKE, 0x90, 0, false, 0),
                0);

        for (int frame = 0; frame < 32; frame++) {
            smoke.update(frame, null);
        }

        assertEquals(4, smoke.getMappingFrameForTesting());
        assertFalse(smoke.isDestroyed());
    }
}
