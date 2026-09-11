package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestWFZUnknownBadnikInstance {

    @Test
    void objectIdAndCollisionMatchObjBBSubObjectData() {
        WFZUnknownBadnikInstance unknown = new WFZUnknownBadnikInstance(
                new ObjectSpawn(0x1000, 0x0400, Sonic2ObjectIds.WFZ_UNKNOWN, 0x7A, 0, false, 0));

        assertEquals(0xBB, Sonic2ObjectIds.WFZ_UNKNOWN);
        assertEquals(0x09, unknown.getCollisionFlags(),
                "ObjBB_SubObjData sets collision_flags to $09");
        assertEquals(0x0C, unknown.getOnScreenHalfWidth(),
                "ObjBB_SubObjData sets width_pixels to $0C");
    }

    @Test
    void removedObjectKeepsSingleMappingFrame() {
        WFZUnknownBadnikInstance unknown = new WFZUnknownBadnikInstance(
                new ObjectSpawn(0x1000, 0x0400, Sonic2ObjectIds.WFZ_UNKNOWN, 0x7A, 0, false, 0));

        unknown.update(0, null);

        assertEquals(0, unknown.getAnimFrameForTesting(),
                "ObjBB_Main does not animate; it only calls MarkObjGone");
    }
}
