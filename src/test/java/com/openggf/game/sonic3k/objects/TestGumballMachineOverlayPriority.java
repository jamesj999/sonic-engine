package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestGumballMachineOverlayPriority {

    @Test
    void bodyOverlayChild_usesBucket3AndStaysLowPriority() {
        GumballMachineObjectInstance.BodyOverlayChild overlay =
                new GumballMachineObjectInstance.BodyOverlayChild(
                        new ObjectSpawn(0x100, 0x180, 0x86, 0x00, 0, false, 0), -0x28);

        assertEquals(3, overlay.getPriorityBucket());
        assertFalse(overlay.isHighPriority());
    }
}


