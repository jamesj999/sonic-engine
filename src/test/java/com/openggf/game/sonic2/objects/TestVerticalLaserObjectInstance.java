package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestVerticalLaserObjectInstance {

    @Test
    void childSpawnUsesObjB7IdentityAndRomCollisionFlags() {
        VerticalLaserObjectInstance laser = new VerticalLaserObjectInstance(
                new ObjectSpawn(0x1000, 0x0400, Sonic2ObjectIds.TILTING_PLATFORM, 0x72, 0, false, 0),
                0x1000,
                0x0400);

        assertEquals(Sonic2ObjectIds.VERTICAL_LASER, laser.getSpawn().objectId(),
                "ObjB6 child spawn should become ObjB7, matching loc_3B7F8");
        assertEquals(0xA9, laser.getCollisionFlags(),
                "ObjB7_SubObjData sets collision_flags to $A9");
        assertEquals(0x18, laser.getOnScreenHalfWidth(),
                "ObjB7_SubObjData sets width_pixels to $18");
    }

    @Test
    void deletesAfterRomCountdownExpires() {
        VerticalLaserObjectInstance laser = new VerticalLaserObjectInstance(
                new ObjectSpawn(0x1000, 0x0400, Sonic2ObjectIds.VERTICAL_LASER, 0x72, 0, false, 0),
                0x1000,
                0x0400);

        for (int frame = 0; frame < 33; frame++) {
            laser.update(frame, null);
        }

        assertTrue(laser.isDestroyed(),
                "ObjB7_Init sets objoff_2A to $20; ObjB7_Main deletes when the countdown reaches zero");
    }
}
