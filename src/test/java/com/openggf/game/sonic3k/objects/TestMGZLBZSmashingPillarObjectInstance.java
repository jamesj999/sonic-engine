package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMGZLBZSmashingPillarObjectInstance {

    @Test
    void shallowGroundedSquashEdgeRejoinsPushPath() {
        MGZLBZSmashingPillarObjectInstance pillar =
                new MGZLBZSmashingPillarObjectInstance(
                        new ObjectSpawn(0x04C4, 0x0754, 0x20, 0x0C, 0, false, 0));

        assertTrue(pillar.groundedSquashEdgeSideContactSetsPush(),
                "SolidObjectFull loc_1E126 rejoins loc_1E042 when abs(d0) is below $10");
    }
}
