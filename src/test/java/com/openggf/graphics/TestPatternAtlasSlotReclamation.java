package com.openggf.graphics;

import com.openggf.graphics.PatternAtlas.Entry;
import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestPatternAtlasSlotReclamation {

    @Test
    public void reclaimedSlotsAreReused() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        Pattern p1 = new Pattern();
        atlas.cachePatternHeadless(p1, 0x20000);
        Entry e1 = atlas.getEntry(0x20000);
        assertNotNull(e1);

        atlas.removeEntry(0x20000);
        assertNull(atlas.getEntry(0x20000));

        atlas.cachePatternHeadless(p1, 0x20001);
        Entry e2 = atlas.getEntry(0x20001);
        assertNotNull(e2);

        // New entry reuses the freed slot
        assertEquals(e1.tileX(), e2.tileX());
        assertEquals(e1.tileY(), e2.tileY());
    }

    @Test
    public void aliasRemovalDoesNotFreeSharedSlot() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        Pattern p1 = new Pattern();
        atlas.cachePatternHeadless(p1, 0x20000);
        Entry original = atlas.getEntry(0x20000);

        atlas.aliasEntry(0x20001, 0x20000);
        Entry alias = atlas.getEntry(0x20001);
        assertEquals(original.slot(), alias.slot());

        // Remove the alias — should NOT free the slot
        atlas.removeEntry(0x20001);

        // Original still works, slot not corrupted
        Entry stillThere = atlas.getEntry(0x20000);
        assertNotNull(stillThere);
        assertEquals(original.slot(), stillThere.slot());

        // Now remove the original — slot should be freed
        atlas.removeEntry(0x20000);

        // New entry should reuse the freed slot
        atlas.cachePatternHeadless(p1, 0x20002);
        Entry reused = atlas.getEntry(0x20002);
        assertEquals(original.tileX(), reused.tileX());
        assertEquals(original.tileY(), reused.tileY());
    }

    @Test
    public void hasCapacityReflectsFreeSlots() {
        // Use a tiny atlas so we can exhaust it
        PatternAtlas atlas = new PatternAtlas(16, 16);
        // 16x16 atlas = 2x2 tiles = 4 slots per page

        Pattern p = new Pattern();
        // Fill all 4 slots
        for (int i = 0; i < 4; i++) {
            assertNotNull(atlas.cachePatternHeadless(p, 0x20000 + i),
                "Should have capacity for slot " + i);
        }

        // Remove one entry to free a slot
        atlas.removeEntry(0x20000);

        // Should reuse the freed slot, not allocate from a new page
        Entry reused = atlas.cachePatternHeadless(p, 0x20010);
        assertNotNull(reused);
        // Verify it's on the same atlas page (index 0), not a new page
        assertEquals(0, reused.atlasIndex());
    }
}


