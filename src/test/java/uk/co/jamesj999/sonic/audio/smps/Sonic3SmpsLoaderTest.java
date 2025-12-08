package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

public class Sonic3SmpsLoaderTest {
    private Rom rom;
    private Sonic3SmpsLoader loader;

    @Before
    public void setUp() {
        rom = Mockito.mock(Rom.class);
        loader = new Sonic3SmpsLoader(rom);
    }

    @Test
    public void testFindMusicOffset() throws IOException {
        // ID 1 (Angel Island)
        // Music Pointer List 0x0E761A
        // Music Bank List 0x0E6B48

        // Mock Pointer (2 bytes)
        // index 1 -> address 0x0E761A + 2 = 0x0E761C
        // Value 0x8000 (standard bank window start)
        when(rom.readByte(0x0E761C)).thenReturn((byte) 0x00);
        when(rom.readByte(0x0E761D)).thenReturn((byte) 0x80); // 0x8000

        // Mock Bank (1 byte)
        // index 1 -> address 0x0E6B48 + 1
        // Value 0x02
        when(rom.readByte(0x0E6B48 + 1)).thenReturn((byte) 0x02);

        // Expected Offset:
        // BankBase 0x080000
        // BankOffset = 2 * 0x8000 = 0x10000
        // Total Bank Start = 0x090000
        // Ptr = 0x8000. Ptr & 0x7FFF = 0x0000.
        // Result = 0x090000.

        int offset = loader.findMusicOffset(1);
        assertEquals(0x090000, offset);
    }

    @Test
    public void testFindMusicOffsetWithOffsetInBank() throws IOException {
        // ID 2
        // index 2 -> 0x0E761A + 4 = 0x0E761E
        // Value 0x8123 (0x23 at byte 0, 0x81 at byte 1)
        when(rom.readByte(0x0E761E)).thenReturn((byte) 0x23);
        when(rom.readByte(0x0E761F)).thenReturn((byte) 0x81); // 0x8123

        // Bank index 2 -> 0x0E6B48 + 2
        // Value 0x05
        when(rom.readByte(0x0E6B48 + 2)).thenReturn((byte) 0x05);

        // Expected:
        // Bank 5 -> 0x080000 + (5 * 0x8000) = 0x080000 + 0x28000 = 0x0A8000
        // Ptr 0x8123 & 0x7FFF = 0x0123
        // Result = 0x0A8000 + 0x0123 = 0x0A8123

        int offset = loader.findMusicOffset(2);
        assertEquals(0x0A8123, offset);
    }
}
