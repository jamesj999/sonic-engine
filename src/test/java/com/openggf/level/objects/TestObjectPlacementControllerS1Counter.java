package com.openggf.level.objects;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestObjectPlacementControllerS1Counter {

    @Test
    void counterForwardScanStopsWhenInlineFindFreeObjFails() {
        ObjectSpawn first = spawn(0x0280, 0);
        ObjectSpawn second = spawn(0x0290, 1);
        ObjectSpawn third = spawn(0x02A0, 2);
        ObjectPlacementController placement =
                new ObjectPlacementController(List.of(first, second, third), () -> 320);
        placement.enableCounterBasedRespawn();

        placement.updateAndLoad(0x0000, (spawn, counter) -> true);

        List<Integer> attempted = new ArrayList<>();
        placement.updateAndLoad(0x0080, (spawn, counter) -> {
            attempted.add(spawn.x());
            return false;
        });

        assertEquals(List.of(0x0280), attempted,
                "S1 ObjPosLoad must stop the forward scan on FindFreeObj failure "
                        + "(docs/s1disasm/s1disasm/_inc/ObjPosLoad.asm:191-214,277-279)");
        assertEquals(0, placement.getActiveSpawns().size(),
                "A failed FindFreeObj must not leave the spawn active without an SST slot");
        assertEquals(0, placement.getCursorIndex(),
                "The right cursor must remain on the entry whose FindFreeObj failed");
    }

    private static ObjectSpawn spawn(int x, int layoutIndex) {
        return new ObjectSpawn(x, 0x0100, 0x2D, 0, 0, false, layoutIndex);
    }
}
