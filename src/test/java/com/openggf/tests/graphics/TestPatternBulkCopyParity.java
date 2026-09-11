package com.openggf.tests.graphics;

import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPatternBulkCopyParity {
    @Test
    void copyFromPreservesAllPixelsInRowMajorOrder() {
        Pattern src = new Pattern();
        Pattern dst = new Pattern();
        byte value = 1;
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                src.setPixel(x, y, value++);
            }
        }

        dst.copyFrom(src);

        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                assertEquals(src.getPixel(x, y), dst.getPixel(x, y));
            }
        }
    }

    @Test
    void copyIntoWritesAllPixelsInRowMajorOrder() throws Exception {
        Pattern pattern = new Pattern();
        byte value = 1;
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                pattern.setPixel(x, y, value++);
            }
        }

        byte[] dst = new byte[80];
        Arrays.fill(dst, (byte) 0x7F);

        Method copyInto = Pattern.class.getDeclaredMethod("copyInto", byte[].class, int.class);
        copyInto.invoke(pattern, dst, 8);

        for (int i = 0; i < 8; i++) {
            assertEquals((byte) 0x7F, dst[i], "prefix outside copy range should be untouched");
        }
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                int dstIndex = 8 + y * Pattern.PATTERN_WIDTH + x;
                assertEquals(pattern.getPixel(x, y), dst[dstIndex]);
            }
        }
        for (int i = 72; i < dst.length; i++) {
            assertEquals((byte) 0x7F, dst[i], "suffix outside copy range should be untouched");
        }
    }

    @Test
    void copyRowIntoWritesRequestedRowAtOffset() throws Exception {
        Pattern pattern = new Pattern();
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                pattern.setPixel(x, y, (byte) (y * 16 + x));
            }
        }

        byte[] dst = new byte[24];
        Arrays.fill(dst, (byte) 0x55);

        Method copyRowInto = Pattern.class.getDeclaredMethod("copyRowInto", int.class, byte[].class, int.class);
        copyRowInto.invoke(pattern, 3, dst, 5);

        byte[] expectedRow = new byte[Pattern.PATTERN_WIDTH];
        for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
            expectedRow[x] = pattern.getPixel(x, 3);
        }
        assertArrayEquals(expectedRow, Arrays.copyOfRange(dst, 5, 13));
        for (int i = 0; i < 5; i++) {
            assertEquals((byte) 0x55, dst[i], "prefix outside row copy should be untouched");
        }
        for (int i = 13; i < dst.length; i++) {
            assertEquals((byte) 0x55, dst[i], "suffix outside row copy should be untouched");
        }
    }
}
