package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.Sonic1SmpsData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies SMPS header pointer endian handling switches based on ROM choice.
 */
public class TestSmpsDataEndianParsing {

    @Test
    public void testLittleEndianParsingForSonic2() {
        // voice ptr bytes little-endian: 0x34 0x12 -> 0x1234 expected
        byte[] data = new byte[8];
        data[0] = 0x34;
        data[1] = 0x12;
        // In Sonic2SmpsData, read16 uses offset 0 for voice ptr
        AbstractSmpsData smps = new Sonic2SmpsData(data);
        assertEquals(0x1234, smps.getVoicePtr());
    }

    @Test
    public void testBigEndianParsingForSonic1() {
        // voice ptr bytes big-endian: 0x12 0x34 -> 0x1234 expected
        byte[] data = new byte[8];
        data[0] = 0x12;
        data[1] = 0x34;
        // In Sonic1SmpsData, read16 uses offset 0 for voice ptr
        AbstractSmpsData smps = new Sonic1SmpsData(data);
        assertEquals(0x1234, smps.getVoicePtr());
    }

}



