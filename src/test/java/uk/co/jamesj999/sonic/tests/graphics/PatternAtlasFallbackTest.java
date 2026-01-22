package uk.co.jamesj999.sonic.tests.graphics;

import org.junit.Test;
import uk.co.jamesj999.sonic.graphics.PatternAtlas;
import uk.co.jamesj999.sonic.level.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PatternAtlasFallbackTest {

    @Test
    public void allocatesSecondAtlasWhenCapacityExceeded() {
        PatternAtlas atlas = new PatternAtlas(8, 8); // 1 slot per atlas
        Pattern patternA = new Pattern();
        Pattern patternB = new Pattern();
        Pattern patternC = new Pattern();

        PatternAtlas.Entry first = atlas.cachePattern(null, patternA, 0);
        assertNotNull(first);
        assertEquals(0, first.atlasIndex());

        PatternAtlas.Entry second = atlas.cachePattern(null, patternB, 1);
        assertNotNull(second);
        assertEquals(1, second.atlasIndex());
        assertEquals(2, atlas.getAtlasCount());

        PatternAtlas.Entry third = atlas.cachePattern(null, patternC, 2);
        assertNull(third);
    }
}
