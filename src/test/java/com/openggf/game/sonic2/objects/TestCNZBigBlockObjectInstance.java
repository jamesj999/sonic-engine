package com.openggf.game.sonic2.objects;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCNZBigBlockObjectInstance {

    @Test
    void outOfRangeReferenceUsesSavedOriginInsteadOfOscillatingPosition() {
        ObjectSpawn spawn = new ObjectSpawn(0x0F00, 0x03A0, 0xD4, 0x00, 0x01, false, 0x23A0);
        CNZBigBlockObjectInstance block = new CNZBigBlockObjectInstance(spawn, "CNZBigBlock");

        assertEquals(0x0F60, block.getX(),
                "x-flipped ObjD4 starts $60 pixels right of its saved origin");
        assertEquals(0x0F00, block.getOutOfRangeReferenceX(),
                "ObjD4 passes objoff_30, not current x_pos, to MarkObjGone2");
    }

    @Test
    void onScreenBoundsUseRomWidthPixels() {
        CNZBigBlockObjectInstance block = new CNZBigBlockObjectInstance(
                new ObjectSpawn(0x1000, 0x0200, 0xD4, 0x00, 0x00, false, 0x0200),
                "CNZBigBlock");

        assertEquals(0x20, block.getOnScreenHalfWidth());
        assertEquals(0x20, block.getOnScreenHalfHeight());
    }

    @Test
    void horizontalTargetEqualityAcceleratesNegativeLikeRomBhiBranch() throws Exception {
        CNZBigBlockObjectInstance block = new CNZBigBlockObjectInstance(
                new ObjectSpawn(0x0F00, 0x03A0, 0xD4, 0x00, 0x00, false, 0x23A0),
                "CNZBigBlock");
        setIntField(block, "x", 0x0F00);
        setIntField(block, "xSub", 0xFF00);
        setIntField(block, "xVel", 0);

        block.update(0, null);

        assertEquals(0x0F00, block.getX(),
                "ObjD4_Horizontal uses `bhi.s`; target == x_pos takes the negative acceleration branch");
    }

    @Test
    void verticalTargetEqualityAcceleratesNegativeLikeRomBhiBranch() throws Exception {
        CNZBigBlockObjectInstance block = new CNZBigBlockObjectInstance(
                new ObjectSpawn(0x0F00, 0x03A0, 0xD4, 0x02, 0x02, false, 0x23A0),
                "CNZBigBlock");
        setIntField(block, "y", 0x03A0);
        setIntField(block, "ySub", 0xFF00);
        setIntField(block, "yVel", 0);

        block.update(0, null);

        assertEquals(0x03A0, block.getY(),
                "ObjD4_Vertical uses `bhi.s`; target == y_pos takes the negative acceleration branch");
    }

    private static void setIntField(CNZBigBlockObjectInstance block, String fieldName, int value) throws Exception {
        Field field = CNZBigBlockObjectInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(block, value);
    }
}
