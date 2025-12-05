package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SmpsDataTest {

    @Test
    public void testSmpsHeaderParsingSonic2() {
        // Construct a mock SMPS header for Sonic 2 (Little Endian)
        // 00: Voice Ptr (0x1000)
        // 02: FM Channels (1) - Usually count of FM channels, but sometimes total? Let's assume code treats it as FM count loops.
        //     Code says: int channels = data[2] & 0xFF; // DAC + FM
        // 03: PSG Channels (1)
        // 04: Tempo Div (0)
        // 05: Tempo (0)
        // 06: DAC Ptr (0x2000)
        // 08: FM1 Ptr (0x3000)
        // 0A: FM1 Key (0x10)
        // 0B: FM1 Vol (0x20)
        // 0C: PSG1 Ptr (0x4000)
        // 0E: PSG1 Key (0x30)
        // 0F: PSG1 Vol (0x40)

        byte[] data = new byte[32];

        // Voice Ptr: 0x1000 -> 00 10
        data[0] = 0x00;
        data[1] = 0x10;

        // FM Channels: 1
        data[2] = 0x01;

        // PSG Channels: 1
        data[3] = 0x01;

        // DAC Ptr: 0x2000 -> 00 20
        data[6] = 0x00;
        data[7] = 0x20;

        // FM1 Ptr: 0x3000 -> 00 30
        data[8] = 0x00;
        data[9] = 0x30;

        // FM1 Key: 0x10
        data[10] = 0x10;
        // FM1 Vol: 0x20
        data[11] = 0x20;

        // PSG1 Ptr: 0x4000 -> 00 40
        data[12] = 0x00;
        data[13] = 0x40;

        // PSG1 Key: 0x30
        data[14] = 0x30;
        // PSG1 Vol: 0x40
        data[15] = 0x40;

        // Force Little Endian (Sonic 2)
        SmpsData smpsData = new SmpsData(data, 0, true);

        // Verify DAC Pointer
        assertEquals("DAC Pointer should be 0x2000", 0x2000, smpsData.getDacPointer());

        // Verify FM1 Pointer
        int[] fmPtrs = smpsData.getFmPointers();
        assertEquals("Should have 1 FM channel", 1, fmPtrs.length);
        assertEquals("FM1 Pointer should be 0x3000", 0x3000, fmPtrs[0]);

        // Verify FM1 Key/Vol
        int[] fmKeys = smpsData.getFmKeyOffsets();
        assertEquals("FM1 Key should be 0x10", 0x10, fmKeys[0]);
        int[] fmVols = smpsData.getFmVolumeOffsets();
        assertEquals("FM1 Vol should be 0x20", 0x20, fmVols[0]);

        // Verify PSG1 Pointer
        int[] psgPtrs = smpsData.getPsgPointers();
        assertEquals("Should have 1 PSG channel", 1, psgPtrs.length);
        assertEquals("PSG1 Pointer should be 0x4000", 0x4000, psgPtrs[0]);

        // Verify PSG1 Key/Vol
        int[] psgKeys = smpsData.getPsgKeyOffsets();
        assertEquals("PSG1 Key should be 0x30", 0x30, psgKeys[0]);
        int[] psgVols = smpsData.getPsgVolumeOffsets();
        assertEquals("PSG1 Vol should be 0x40", 0x40, psgVols[0]);
    }
}
