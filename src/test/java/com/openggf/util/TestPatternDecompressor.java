package com.openggf.util;

import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestPatternDecompressor {

    @Test
    public void testFromBytesEmptyInput() {
        Pattern[] result = PatternDecompressor.fromBytes(new byte[0]);
        assertEquals(0, result.length);
    }

    @Test
    public void testFromBytesNullInput() {
        Pattern[] result = PatternDecompressor.fromBytes(null);
        assertEquals(0, result.length);
    }

    @Test
    public void testFromBytesSinglePattern() {
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_ROM];
        data[0] = 0x30; // First nibble = 3, second nibble = 0
        Pattern[] result = PatternDecompressor.fromBytes(data);
        assertEquals(1, result.length);
        assertEquals(3, result[0].getPixel(0, 0));
        assertEquals(0, result[0].getPixel(1, 0));
    }

    @Test
    public void testFromBytesTwoPatterns() {
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_ROM * 2];
        data[0] = 0x10;
        data[Pattern.PATTERN_SIZE_IN_ROM] = 0x20;
        Pattern[] result = PatternDecompressor.fromBytes(data);
        assertEquals(2, result.length);
        assertEquals(1, result[0].getPixel(0, 0));
        assertEquals(2, result[1].getPixel(0, 0));
    }

    @Test
    public void testFromBytesBounded() {
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_ROM * 5];
        Pattern[] result = PatternDecompressor.fromBytes(data, 3);
        assertEquals(3, result.length);
    }

    @Test
    public void testFromBytesBoundedExceedingCount() {
        byte[] data = new byte[Pattern.PATTERN_SIZE_IN_ROM * 2];
        Pattern[] result = PatternDecompressor.fromBytes(data, 10);
        assertEquals(2, result.length);
    }
}


