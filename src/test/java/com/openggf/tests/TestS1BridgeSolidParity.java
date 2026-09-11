package com.openggf.tests;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1BridgeObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1BridgeSolidParity {
    @Test
    void bridgeUsesFullPlatformWidthForNewLandings() {
        Sonic1BridgeObjectInstance bridge = new Sonic1BridgeObjectInstance(
                new ObjectSpawn(0x1FA8, 0x0318, Sonic1ObjectIds.BRIDGE, 0x0C, 0, false, 0x0318));

        SolidObjectParams params = bridge.getSolidParams();

        assertEquals(0x60, params.halfWidth());
        assertEquals(-8, params.offsetX());
        assertTrue(bridge.usesCollisionHalfWidthForTopLanding(),
                "S1 Bridge calls Plat_NoXCheck with Bri_Solid's final d1/d2 width; "
                        + "generic SolidObject +$B narrowing must not apply");
    }
}
