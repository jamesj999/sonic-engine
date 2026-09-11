package com.openggf.level.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestS1CounterPlacementRememberedParity {

    @Test
    void forwardCounterAdvancesAcrossRememberedTrackedSpawn() {
        ObjectSpawn remembered = trackedSpawn(0x0280, 0);
        ObjectSpawn nextTracked = trackedSpawn(0x0300, 1);
        ObjectPlacementController placement = new ObjectPlacementController(
                List.of(remembered, nextTracked),
                () -> 320);
        placement.enableCounterBasedRespawn();

        placement.update(0x0000);
        placement.markRemembered(remembered);
        placement.update(0x0080);

        assertEquals(2, placement.getFwdCounter(),
                "S1 ObjPosLoad increments the forward respawn counter before OPL_SpawnObj skips remembered objects");
    }

    private static ObjectSpawn trackedSpawn(int x, int layoutIndex) {
        return new ObjectSpawn(x, 0x0100, 0x2D, 0, 0, true, 0x0100, layoutIndex);
    }
}
