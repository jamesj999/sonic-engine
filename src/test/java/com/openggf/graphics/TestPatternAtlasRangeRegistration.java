package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestPatternAtlasRangeRegistration {
    private static final int SYNTHETIC_BASE = PatternAtlasRange.CONTINUE_SCREEN.endExclusive();

    @Test
    public void adjacentHalfOpenRangesAreValid() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        atlas.registerRange(SYNTHETIC_BASE, 0x1000, "First");

        assertDoesNotThrow(() -> atlas.registerRange(SYNTHETIC_BASE + 0x1000,
                0x1000, "Adjacent"));
    }

    @Test
    public void declaredPatternAtlasRangesDoNotOverlap() {
        PatternAtlasRange[] ranges = PatternAtlasRange.values();

        for (int i = 0; i < ranges.length; i++) {
            for (int j = i + 1; j < ranges.length; j++) {
                PatternAtlasRange first = ranges[i];
                PatternAtlasRange second = ranges[j];

                boolean overlaps = first.base() < second.endExclusive()
                        && second.base() < first.endExclusive();
                assertFalse(overlaps, first.name() + " overlaps " + second.name());
            }
        }
    }

    @Test
    public void partiallyOverlappingRangesFailFast() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        atlas.registerRange(SYNTHETIC_BASE, 0x2000, "First");

        assertThrows(IllegalArgumentException.class,
            () -> atlas.registerRange(SYNTHETIC_BASE + 0x1000, 0x2000, "Partial"));
    }

    @Test
    public void exactDuplicateRangesFailFast() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        atlas.registerRange(SYNTHETIC_BASE, 0x1000, "First");

        assertThrows(IllegalArgumentException.class,
            () -> atlas.registerRange(SYNTHETIC_BASE, 0x1000, "Duplicate"));
    }

    @Test
    public void enclosingRangesFailFast() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        atlas.registerRange(SYNTHETIC_BASE + 0x1000, 0x1000, "Inner");

        assertThrows(IllegalArgumentException.class,
            () -> atlas.registerRange(SYNTHETIC_BASE, 0x3000, "Outer"));
    }

    @Test
    public void governedVirtualRangesMayBeCachedHeadless() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        assertNotNull(atlas.cachePatternHeadless(new com.openggf.level.Pattern(),
                PatternAtlasRange.OBJECTS.base()));
    }

    @Test
    public void ungovernedVirtualPatternIdsFailFast() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        assertThrows(IllegalArgumentException.class,
                () -> atlas.cachePatternHeadless(new com.openggf.level.Pattern(), 0x19000));
    }

    @Test
    public void registeredRangesTestSeamIsReadOnly() {
        PatternAtlas atlas = new PatternAtlas(256, 256);
        atlas.registerRange(PatternAtlasRange.OBJECTS);

        assertEquals(1, atlas.registeredRangesForTesting().size());
        assertThrows(UnsupportedOperationException.class,
                () -> atlas.registeredRangesForTesting().clear());
    }
}
