package com.openggf.game.sonic2.objects;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMTZSpringWallObjectInstance {

    @Test
    void springWallBypassesSharedOffscreenSolidGate() {
        MTZSpringWallObjectInstance wall = new MTZSpringWallObjectInstance(
                new ObjectSpawn(0x1000, 0x0200, 0x66, 0, 0, false, 0),
                "MTZSpringWall");

        assertTrue(wall.bypassesOffscreenSolidGate(),
                "Obj66 runs solid collision before its explicit DeleteObject tail");
    }

    @Test
    void springWallUsesSolidObjectAlwaysInclusiveRightEdge() {
        MTZSpringWallObjectInstance wall = new MTZSpringWallObjectInstance(
                new ObjectSpawn(0x1000, 0x0200, 0x66, 0, 0, false, 0),
                "MTZSpringWall");

        assertTrue(wall.usesInclusiveRightEdge(),
                "S2 SolidObject_cont rejects the right edge with bhi, so relX == width*2 is still contact");
    }
}
