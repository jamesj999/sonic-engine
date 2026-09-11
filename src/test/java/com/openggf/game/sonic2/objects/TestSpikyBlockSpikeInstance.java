package com.openggf.game.sonic2.objects;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestSpikyBlockSpikeInstance {

    @Test
    void spikeTouchResponseDoesNotRequireRenderFlag() {
        SpikyBlockSpikeInstance spike = new SpikyBlockSpikeInstance(
                new ObjectSpawn(0x1910, 0x0510, 0x68, 0, 0, false, 0),
                "SpikyBlock-Spike",
                0,
                0);

        assertEquals(0x84, spike.getCollisionFlags(),
                "Obj68_Init seeds the spike child from Obj68_CollisionFlags");
        assertFalse(spike.requiresRenderFlagForTouch(),
                "S2 Touch_Loop scans Obj68 collision_flags directly; render_flags bit 7 only gates the spike movement SFX");
        assertFalse(spike.getTouchResponseProfile().requiresRenderFlagForTouch());
        assertFalse(spike.getTouchResponseProfile(false).requiresRenderFlagForTouch());
    }
}
