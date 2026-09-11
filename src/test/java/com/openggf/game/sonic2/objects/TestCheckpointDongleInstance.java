package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCheckpointDongleInstance {
    private static final ObjectSpawn CHECKPOINT_SPAWN =
            new ObjectSpawn(0x2800, 0x0438, Sonic2ObjectIds.CHECKPOINT, 1, 0, false, 0);

    @Test
    void newlyAllocatedDongleKeepsRomDefaultPositionUntilRoutineRuns() {
        CheckpointObjectInstance parent = new CheckpointObjectInstance(CHECKPOINT_SPAWN, "Checkpoint");
        CheckpointDongleInstance dongle = new CheckpointDongleInstance(parent);

        assertEquals(0, dongle.getX(),
                "Obj79_CheckActivation fills objoff_30/32 but leaves child x_pos at RAM default until routine 6");
        assertEquals(0, dongle.getY(),
                "Obj79_CheckActivation fills objoff_30/32 but leaves child y_pos at RAM default until routine 6");

        dongle.update(0, null);

        assertEquals(0x2800, dongle.getX(),
                "Obj79_MoveDonglyThing publishes x_pos on the child's first routine-6 update");
        assertEquals(0x0418, dongle.getY(),
                "Obj79_MoveDonglyThing publishes y_pos from objoff_32 minus the first 12px sine offset");
    }
}
