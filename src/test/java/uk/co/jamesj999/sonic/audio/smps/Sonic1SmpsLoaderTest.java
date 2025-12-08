package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class Sonic1SmpsLoaderTest {
    private Rom rom;
    private Sonic1SmpsLoader loader;

    @Before
    public void setUp() {
        rom = Mockito.mock(Rom.class);
        loader = new Sonic1SmpsLoader(rom);
    }

    @Test
    public void testFindMusicOffset() throws IOException {
        // Mock pointer table at 0x071A9C
        // For music ID 0x81 (index 0), pointer address is 0x071A9C.
        // Return 0x123456
        when(rom.read32BitAddr(0x071A9C)).thenReturn(0x123456);

        int offset = loader.findMusicOffset(0x81);
        assertEquals(0x123456, offset);

        // ID 0x82 (index 1), pointer address 0x071A9C + 4
        when(rom.read32BitAddr(0x071A9C + 4)).thenReturn(0x654321);
        int offset2 = loader.findMusicOffset(0x82);
        assertEquals(0x654321, offset2);
    }

    @Test
    public void testFindMusicOffsetInvalidId() {
        assertEquals(-1, loader.findMusicOffset(0x80)); // Below 0x81
    }

    @Test
    public void testLoadMusic() throws IOException {
        when(rom.read32BitAddr(0x071A9C)).thenReturn(0x1000);
        byte[] dummyData = new byte[0x2000];
        dummyData[0] = 0x00;
        dummyData[1] = 0x10; // Voice Ptr 0x0010
        dummyData[2] = 0x01; // 1 FM
        dummyData[3] = 0x00; // 0 PSG
        dummyData[6] = 0x00;
        dummyData[7] = 0x20; // DAC Ptr 0x0020
        when(rom.readBytes(0x1000, 0x2000)).thenReturn(dummyData);

        AbstractSmpsData data = loader.loadMusic(0x81);
        assertNotNull(data);
        assertEquals(0x10, data.getVoicePtr());
    }
}
