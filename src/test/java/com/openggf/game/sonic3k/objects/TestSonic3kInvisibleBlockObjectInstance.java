package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kInvisibleBlockObjectInstance {

    @Test
    void stationaryLeftSideContactClearsResidualGroundVelocity() {
        Sonic3kInvisibleBlockObjectInstance block = new Sonic3kInvisibleBlockObjectInstance(
                new ObjectSpawn(0x1FC4, 0x07B4, 0x28, 0x02, 0, false, 0));

        assertTrue(block.zeroXSpeedStopsOnLeftSideContact(),
                "S3K SolidObject_cont treats x_vel == 0 as not moving away on the left side");
    }
}
