package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestWFZStickBadnikInstance {

    @Test
    void usesRomObjectIdAndCollisionSize() {
        WFZStickBadnikInstance stick = new WFZStickBadnikInstance(
                new ObjectSpawn(0x1000, 0x0400, Sonic2ObjectIds.WFZ_STICK, 0, 0, false, 0));

        assertEquals(0xBF, Sonic2ObjectIds.WFZ_STICK);
        assertEquals(0x04, stick.getCollisionFlags(),
                "ObjBF LoadSubObject data uses collision size index 4");
        assertEquals(4, stick.getOnScreenHalfWidth(),
                "ObjBF LoadSubObject data sets width_pixels to 4");
    }

    @Test
    void animatesFramesZeroOneTwoWithRomDelay() {
        WFZStickBadnikInstance stick = new WFZStickBadnikInstance(
                new ObjectSpawn(0x1000, 0x0400, Sonic2ObjectIds.WFZ_STICK, 0, 0, false, 0));

        assertEquals(0, stick.getAnimFrameForTesting());
        stick.update(0, null);
        assertEquals(0, stick.getAnimFrameForTesting());
        stick.update(1, null);
        assertEquals(1, stick.getAnimFrameForTesting());
        stick.update(2, null);
        assertEquals(1, stick.getAnimFrameForTesting());
        stick.update(3, null);
        assertEquals(2, stick.getAnimFrameForTesting());
        stick.update(4, null);
        assertEquals(2, stick.getAnimFrameForTesting());
        stick.update(5, null);
        assertEquals(0, stick.getAnimFrameForTesting());
    }
}
