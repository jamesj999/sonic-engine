package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import com.openggf.level.objects.ObjectSpawn;
import static org.junit.jupiter.api.Assertions.*;

public class TestAizEmeraldScatterInstance {

    @Test
    public void mappingFrameIsDerivedFromSubtype() {
        // ROM: mapping_frame = subtype >> 1
        // Subtypes: 0, 2, 4, 6, 8, 10, 12 -> frames: 0, 1, 2, 3, 4, 5, 6
        int[] subtypes = {0, 2, 4, 6, 8, 10, 12};
        for (int subtype : subtypes) {
            var spawn = new ObjectSpawn(100, 100, 0, subtype, 0, false, 0);
            var emerald = new AizEmeraldScatterInstance(spawn);
            assertEquals(subtype >> 1, emerald.getMappingFrame(), "subtype " + subtype);
        }
    }

    @Test
    public void startsInFallingPhase() {
        var spawn = new ObjectSpawn(100, 100, 0, 0, 0, false, 0);
        var emerald = new AizEmeraldScatterInstance(spawn);
        assertEquals(AizEmeraldScatterInstance.Phase.FALLING, emerald.getPhase());
    }

    @Test
    public void initialVelocityIsCorrect() {
        var spawn = new ObjectSpawn(100, 100, 0, 0, 0, false, 0);
        var emerald = new AizEmeraldScatterInstance(spawn);
        assertEquals(-0x40, emerald.getXVel());
        assertEquals(-0x700, emerald.getYVel());
    }

    @Test
    public void pickupCheckUsesSubtypeBit1ForDirection() {
        // subtype 0 (bit 1 = 0): collected when Knuckles moves RIGHT (positive x_vel)
        var spawn0 = new ObjectSpawn(100, 100, 0, 0, 0, false, 0);
        var em0 = new AizEmeraldScatterInstance(spawn0);
        assertTrue(em0.canBeCollectedByVelocity(0x600));   // positive vel, bit1=0 -> collect
        assertFalse(em0.canBeCollectedByVelocity(-0x600)); // negative vel, bit1=0 -> wrong direction

        // subtype 2 (bit 1 = 1): collected when Knuckles moves LEFT (negative x_vel)
        var spawn2 = new ObjectSpawn(100, 100, 0, 2, 0, false, 0);
        var em2 = new AizEmeraldScatterInstance(spawn2);
        assertTrue(em2.canBeCollectedByVelocity(-0x600));  // negative vel, bit1=1 -> collect
        assertFalse(em2.canBeCollectedByVelocity(0x600));  // positive vel, bit1=1 -> wrong direction
    }

    @Test
    public void pickupDirectionForAllSubtypes() {
        // Subtypes 0, 4, 8, 12 (bit 1 clear) -> collect on rightward pass
        for (int subtype : new int[]{0, 4, 8, 12}) {
            var spawn = new ObjectSpawn(100, 100, 0, subtype, 0, false, 0);
            var em = new AizEmeraldScatterInstance(spawn);
            assertTrue(em.canBeCollectedByVelocity(0x600), "subtype " + subtype + " should collect on right");
            assertFalse(em.canBeCollectedByVelocity(-0x600), "subtype " + subtype + " should NOT collect on left");
        }

        // Subtypes 2, 6, 10 (bit 1 set) -> collect on leftward pass
        for (int subtype : new int[]{2, 6, 10}) {
            var spawn = new ObjectSpawn(100, 100, 0, subtype, 0, false, 0);
            var em = new AizEmeraldScatterInstance(spawn);
            assertTrue(em.canBeCollectedByVelocity(-0x600), "subtype " + subtype + " should collect on left");
            assertFalse(em.canBeCollectedByVelocity(0x600), "subtype " + subtype + " should NOT collect on right");
        }
    }

    @Test
    public void zeroVelocityCollectsWhenBit1Clear() {
        // ROM: bmi.s (branch if negative), so >= 0 means collect
        // Zero velocity (adjusted >= 0) should collect for bit1=0
        var spawn = new ObjectSpawn(100, 100, 0, 0, 0, false, 0);
        var em = new AizEmeraldScatterInstance(spawn);
        assertTrue(em.canBeCollectedByVelocity(0));
    }

    @Test
    public void zeroVelocityCollectsWhenBit1Set() {
        // subtype 2 (bit1=1): adjusted = -0 = 0, which is >= 0 -> collect
        var spawn = new ObjectSpawn(100, 100, 0, 2, 0, false, 0);
        var em = new AizEmeraldScatterInstance(spawn);
        assertTrue(em.canBeCollectedByVelocity(0));
    }

    @Test
    public void isPersistent() {
        var spawn = new ObjectSpawn(100, 100, 0, 0, 0, false, 0);
        var emerald = new AizEmeraldScatterInstance(spawn);
        assertTrue(emerald.isPersistent());
    }

    @Test
    public void positionInitializedFromSpawn() {
        var spawn = new ObjectSpawn(0x500, 0x200, 0, 4, 0, false, 0);
        var emerald = new AizEmeraldScatterInstance(spawn);
        assertEquals(0x500, emerald.getX());
        assertEquals(0x200, emerald.getY());
    }
}


