package com.openggf.graphics;

import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestVirtualPatternIdRanges {

    @Test
    void governedVirtualIdsRemainDistinctAcrossAtlasPages() {
        PatternAtlas atlas = new PatternAtlas(8, 8);
        int nativeId = 0x7ff;
        int virtualId = PatternAtlasRange.SIDEKICK_BANKS.base() + 0x123;

        PatternAtlas.Entry nativeEntry = atlas.cachePatternHeadless(new Pattern(), nativeId);
        PatternAtlas.Entry virtualEntry = atlas.cachePatternHeadless(new Pattern(), virtualId);

        assertNotNull(nativeEntry);
        assertNotNull(virtualEntry);
        assertEquals(nativeId, nativeEntry.patternId());
        assertEquals(virtualId, virtualEntry.patternId());
        assertEquals(0, nativeEntry.atlasIndex());
        assertEquals(1, virtualEntry.atlasIndex());
        assertSame(virtualEntry, atlas.getEntry(virtualId));
        assertNotSame(nativeEntry, atlas.getEntry(virtualId));
    }
}
