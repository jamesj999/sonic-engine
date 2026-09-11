package com.openggf.tests.graphics;

import org.junit.jupiter.api.Test;
import com.openggf.graphics.PatternAtlas;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Pattern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class PatternAtlasFallbackTest {

    @Test
    public void allocatesSecondAtlasWhenCapacityExceeded() {
        PatternAtlas atlas = new PatternAtlas(8, 8); // 1 slot per atlas
        Pattern patternA = new Pattern();
        Pattern patternB = new Pattern();
        Pattern patternC = new Pattern();

        // Use headless caching since we don't have GL context in tests
        PatternAtlas.Entry first = atlas.cachePatternHeadless(patternA, 0);
        assertNotNull(first);
        assertEquals(0, first.atlasIndex());

        PatternAtlas.Entry second = atlas.cachePatternHeadless(patternB, 1);
        assertNotNull(second);
        assertEquals(1, second.atlasIndex());
        assertEquals(2, atlas.getAtlasCount());

        PatternAtlas.Entry third = atlas.cachePatternHeadless(patternC, 2);
        assertNull(third);
    }

    @Test
    public void batchUploadPreservesPatternXAxisAndYAxis() throws Exception {
        PatternAtlas atlas = new PatternAtlas(8, 8);
        atlas.beginBatch();

        Pattern pattern = new Pattern();
        pattern.setPixel(1, 6, (byte) 5);

        PatternAtlas.Entry entry = atlas.cachePatternHeadless(pattern, PatternAtlasRange.OBJECTS.base());
        assertNotNull(entry);

        Method uploadPattern = PatternAtlas.class.getDeclaredMethod("uploadPattern", Pattern.class, PatternAtlas.Entry.class);
        uploadPattern.setAccessible(true);
        uploadPattern.invoke(atlas, pattern, entry);

        Field cpuPixelsField = PatternAtlas.class.getDeclaredField("cpuPixels");
        cpuPixelsField.setAccessible(true);
        byte[][] cpuPixels = (byte[][]) cpuPixelsField.get(atlas);
        byte[] page = cpuPixels[entry.atlasIndex()];

        assertEquals(5, page[6 * 8 + 1] & 0xFF,
                "pattern pixel should be uploaded to matching x/y coordinates");
        assertEquals(0, page[1 * 8 + 6] & 0xFF,
                "pattern upload must not transpose x and y");
    }

    @Test
    public void batchUploadPreservesTwoDistinctPixelsWithoutTranspose() throws Exception {
        PatternAtlas atlas = new PatternAtlas(8, 8);
        atlas.beginBatch();

        Pattern pattern = new Pattern();
        pattern.setPixel(0, 7, (byte) 3);
        pattern.setPixel(7, 0, (byte) 9);

        PatternAtlas.Entry entry = atlas.cachePatternHeadless(pattern, 0x99);
        assertNotNull(entry);

        Method uploadPattern = PatternAtlas.class.getDeclaredMethod("uploadPattern", Pattern.class, PatternAtlas.Entry.class);
        uploadPattern.setAccessible(true);
        uploadPattern.invoke(atlas, pattern, entry);

        Field cpuPixelsField = PatternAtlas.class.getDeclaredField("cpuPixels");
        cpuPixelsField.setAccessible(true);
        byte[][] cpuPixels = (byte[][]) cpuPixelsField.get(atlas);
        byte[] page = cpuPixels[entry.atlasIndex()];

        assertEquals(3, page[7 * 8] & 0xFF);
        assertEquals(9, page[7] & 0xFF);
    }
}


