package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPoindexterBadnikInstance {

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void objWaitOffscreenSuppressesMovementAndCollisionUntilSetupCompletes() {
        PoindexterBadnikInstance poindexter = poindexter();

        AbstractObjectInstance.updateCameraBounds(0, 0, 0x100, 0x180, 0);
        poindexter.update(0, playerAt(0x260, 0x100));

        assertEquals(0x200, poindexter.getX());
        assertEquals(0x100, poindexter.getY());
        assertEquals(0, poindexter.getCollisionFlags(),
                "Obj_WaitOffscreen keeps collision_flags clear while the temporary offscreen object is active");
        assertTrue(poindexter.isWaitingForOnscreenForTest());

        putPoindexterOnScreen();
        poindexter.update(1, playerAt(0x260, 0x100));

        assertEquals(0x200, poindexter.getX(),
                "loc_85B02 only restores the normal object pointer; Obj_Poindexter setup runs next tick");
        assertEquals(0x100, poindexter.getY());
        assertEquals(0, poindexter.getCollisionFlags());
        assertFalse(poindexter.isWaitingForOnscreenForTest());
        assertFalse(poindexter.isInitializedForTest());

        poindexter.update(2, playerAt(0x180, 0x100));

        assertEquals(0x200, poindexter.getX(),
                "loc_88298 initializes velocity and timers but does not run MoveSprite2");
        assertEquals(0x100, poindexter.getY());
        assertEquals(0, poindexter.getCollisionFlags(),
                "ObjDat_Poindexter seeds collision_flags=0 until routine 2 writes $0A/$86");
        assertTrue(poindexter.isInitializedForTest());

        poindexter.update(3, playerAt(0x180, 0x100));

        assertEquals(0x0A, poindexter.getCollisionFlags(),
                "loc_882E6 writes the vulnerable Poindexter collision flags after the first active movement tick");
    }

    @Test
    void velocityTracksPlayerPositionAtSetupAfterOffscreenRestore() {
        PoindexterBadnikInstance poindexter = poindexter();

        AbstractObjectInstance.updateCameraBounds(0, 0, 0x100, 0x180, 0);
        poindexter.update(0, playerAt(0x260, 0x100));
        putPoindexterOnScreen();
        poindexter.update(1, playerAt(0x260, 0x100));
        poindexter.update(2, playerAt(0x180, 0x100));

        assertEquals(-0x40, poindexter.getXVelocityForTest(),
                "Set_VelocityXTrackSonic runs during restored setup, not when placement first creates the object");
    }

    @Test
    void waitOffscreenUsesRenderExclusiveBottomEdge() {
        PoindexterBadnikInstance poindexter = poindexter();

        AbstractObjectInstance.updateCameraBounds(0x180, 0, 0x2E0, 0xE0, 0);
        poindexter.update(0, playerAt(0x260, 0x100));

        assertTrue(poindexter.isWaitingForOnscreenForTest(),
                "Render_Sprites rejects the bottom edge when y == bottom + $20");

        AbstractObjectInstance.updateCameraBounds(0x180, 0, 0x2E0, 0xE1, 0);
        poindexter.update(1, playerAt(0x260, 0x100));

        assertFalse(poindexter.isWaitingForOnscreenForTest(),
                "One pixel inside the Render_Sprites $20 band restores the normal routine");
    }

    @Test
    void exposesRomRenderBoundsFromObjData() {
        PoindexterBadnikInstance poindexter = poindexter();

        assertEquals(0x14, poindexter.getOnScreenHalfWidth(),
                "ObjDat_Poindexter width_pixels byte is $14");
        assertEquals(0x14, poindexter.getOnScreenHalfHeight(),
                "ObjDat_Poindexter height_pixels byte is $14");
    }

    private static PoindexterBadnikInstance poindexter() {
        return new PoindexterBadnikInstance(new ObjectSpawn(
                0x200, 0x100, Sonic3kObjectIds.POINDEXTER, 0, 0, false, 0));
    }

    private static void putPoindexterOnScreen() {
        AbstractObjectInstance.updateCameraBounds(0x180, 0, 0x2E0, 0x180, 0);
    }

    private static TestablePlayableSprite playerAt(int x, int y) {
        return new TestablePlayableSprite("sonic", (short) x, (short) y);
    }
}
