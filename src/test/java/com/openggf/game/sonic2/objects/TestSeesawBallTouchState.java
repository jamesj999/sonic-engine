package com.openggf.game.sonic2.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseProvider.TouchRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSeesawBallTouchState {

    @Test
    void touchRegionUsesRomCenterPositionInsteadOfSpriteTopLeft() {
        SeesawObjectInstance parent = new SeesawObjectInstance(
                new ObjectSpawn(0x2340, 0x0770, 0x14, 0, 0, false, 0),
                "Seesaw");
        SeesawBallObjectInstance ball = new SeesawBallObjectInstance(
                0x2340,
                0x0780,
                0x2368,
                0x0790,
                parent,
                false);

        assertEquals(0x235C, ball.getX(), "Obj14 sprite X remains top-left for render bounds");
        assertEquals(0x0788, ball.getY(), "Obj14 sprite Y remains top-left for render bounds");

        TouchRegion region = ball.getMultiTouchRegions()[0];
        assertEquals(0x2368, region.x(),
                "S2 TouchResponse reads Obj14 x_pos from SST, which is the ball center");
        assertEquals(0x0790, region.y(),
                "S2 TouchResponse reads Obj14 y_pos from SST, which is the ball center");
        assertEquals(0x8B, region.collisionFlags(),
                "Obj14_Ball_Init stores collision_flags=$8B for the harmful ball");
    }
}
