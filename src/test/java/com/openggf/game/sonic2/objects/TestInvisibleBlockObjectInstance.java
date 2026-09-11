package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInvisibleBlockObjectInstance {

    @Test
    void subtypeDecodesSolidObjectAlwaysDimensions() {
        InvisibleBlockObjectInstance block = new InvisibleBlockObjectInstance(
                new ObjectSpawn(0x1560, 0x02A0, Sonic2ObjectIds.INVISIBLE_BLOCK, 0x33, 0, false, 0),
                "InvisibleBlock");

        SolidObjectParams params = block.getSolidParams();

        assertEquals(0x20 + 0x0B, params.halfWidth());
        assertEquals(0x20, params.airHalfHeight());
        assertEquals(0x21, params.groundHalfHeight());
    }

    @Test
    void solidObjectAlwaysBypassesOffscreenGate() {
        InvisibleBlockObjectInstance block = new InvisibleBlockObjectInstance(
                new ObjectSpawn(0x1560, 0x02A0, Sonic2ObjectIds.INVISIBLE_BLOCK, 0x33, 0, false, 0),
                "InvisibleBlock");

        assertTrue(block.bypassesOffscreenSolidGate(),
                "Obj74 calls SolidObject_Always, which resolves even when offscreen");
    }

    @Test
    void solidObjectAlwaysAirborneStaleStandingBitReturnsNoContact() {
        InvisibleBlockObjectInstance block = new InvisibleBlockObjectInstance(
                new ObjectSpawn(0x13C0, 0x05AC, Sonic2ObjectIds.INVISIBLE_BLOCK, 0x33, 0, false, 0),
                "InvisibleBlock");

        assertTrue(block.airborneStaleStandingBitReturnsNoContact(null),
                "SolidObject_Always_SingleCharacter clears stale support and returns d4=0 "
                        + "when this object's standing bit is set and the player is airborne");
    }

    @Test
    void solidObjectAlwaysUsesLiveYRadiusForBottomOverlap() {
        InvisibleBlockObjectInstance block = new InvisibleBlockObjectInstance(
                new ObjectSpawn(0x13C0, 0x05AC, Sonic2ObjectIds.INVISIBLE_BLOCK, 0x71, 0, false, 0),
                "InvisibleBlock");

        assertTrue(block.fullSolidBottomOverlapUsesCurrentYRadiusOnly(null),
                "Obj74 SolidObject_cont doubles live y_radius(a1), so rolling lower-half contact "
                        + "must use the rolling radius");
    }
}
