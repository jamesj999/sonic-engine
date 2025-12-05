package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SmpsDataTest {

    @Test
    public void testSmpsHeaderParsingSonic2Standard() {
        // Construct a mock SMPS header for Sonic 2 (Little Endian)
        // Standard Offset 0x08 for FM Ptrs

        byte[] data = new byte[64];
        // 00: Voice Ptr (0x1000)
        data[0] = 0x00; data[1] = 0x10;
        // 02: FM Channels (1)
        data[2] = 0x01;
        // 03: PSG Channels (1)
        data[3] = 0x01;
        // 04: Div, 05: Tempo
        // 06: DAC Ptr (0x2000)
        data[6] = 0x00; data[7] = 0x20;

        // 0x08: FM1 Ptr (0x3000) -> Valid Z80 ptr (> 0x1380)
        data[8] = 0x00; data[9] = 0x30;
        // 0x0A: FM1 Key (0x10), 0x0B: FM1 Vol (0x20)
        data[10] = 0x10; data[11] = 0x20;

        // 0x0C: PSG1 Ptr (0x4000) -> Valid Z80 ptr
        data[12] = 0x00; data[13] = 0x40;

        // Context: Z80 Start 0x1380
        SmpsData smpsData = new SmpsData(data, 0x1380, true);

        // Verify FM1 Pointer (should be 0x3000)
        int[] fmPtrs = smpsData.getFmPointers();
        assertEquals("FM1 Pointer should be 0x3000", 0x3000, fmPtrs[0]);
    }

    @Test
    public void testSmpsHeaderParsingSonic2Shifted() {
        // Construct a mock SMPS header for Sonic 2 (Little Endian)
        // Shifted Offset 0x0A for FM Ptrs (simulating 0x8A behavior)

        byte[] data = new byte[64];
        // 00: Voice Ptr (0x1000)
        data[0] = 0x00; data[1] = 0x10;
        // 02: FM Channels (1)
        data[2] = 0x01;
        // 03: PSG Channels (1)
        data[3] = 0x01;
        // 04: Div, 05: Tempo
        // 06: DAC Ptr (0x2000)
        data[6] = 0x00; data[7] = 0x20;

        // 0x08: Padding / Garbage (e.g. 0x00 00 or just bad ptrs)
        // Set to something that is NOT a valid pointer (e.g. 0x0010 < 0x1380)
        data[8] = 0x10; data[9] = 0x00;

        // 0x0A: FM1 Ptr (0x3000) -> Valid Z80 ptr
        data[10] = 0x00; data[11] = 0x30;
        // 0x0C: FM1 Key/Vol
        data[12] = 0x10; data[13] = 0x20;

        // 0x0E: PSG1 Ptr (0x4000) -> Valid Z80 ptr
        data[14] = 0x00; data[15] = 0x40;

        // Context: Z80 Start 0x1380
        SmpsData smpsData = new SmpsData(data, 0x1380, true);

        // Verify FM1 Pointer (should be 0x3000 from 0x0A)
        int[] fmPtrs = smpsData.getFmPointers();
        assertEquals("FM1 Pointer should be 0x3000 (detected 0x0A)", 0x3000, fmPtrs[0]);
    }
}
