package com.openggf.game.sonic2.objects;

import com.openggf.level.objects.AbstractObjectInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestLeafParticleObjectInstance {
    @BeforeEach
    void setUpCameraBounds() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void reversalGateUsesRomD7SlotParity() {
        LeafParticleObjectInstance evenSlotLeaf = new LeafParticleObjectInstance(100, 100, 0, 0, 0, 0);
        evenSlotLeaf.setSlotIndex(28);

        evenSlotLeaf.update(28, null);
        evenSlotLeaf.update(29, null);

        assertEquals(104, evenSlotLeaf.getY(),
                "Obj2C_Leaf reverses speed for even SST slots because d7=127-slot is odd");
    }

    @Test
    void reversalGateLeavesOddSlotsUnchanged() {
        LeafParticleObjectInstance oddSlotLeaf = new LeafParticleObjectInstance(100, 100, 0, 0, 0, 0);
        oddSlotLeaf.setSlotIndex(29);

        oddSlotLeaf.update(28, null);
        oddSlotLeaf.update(29, null);

        assertEquals(103, oddSlotLeaf.getY(),
                "Obj2C_Leaf does not reverse speed for odd SST slots because d7=127-slot is even");
    }
}
