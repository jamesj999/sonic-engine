package uk.co.jamesj999.sonic.level;

import org.junit.Test;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2;

import java.io.IOException;
import static org.junit.Assert.*;

public class TestBackgroundScroll {

    private static final int LEVEL_SELECT_ADDR = 0x9454;
    private static final int BG_SCROLL_TABLE_ADDR = 0x00C296;

    class MockRom extends Rom {
        @Override
        public byte readByte(long offset) throws IOException {
            if (offset >= LEVEL_SELECT_ADDR && offset < LEVEL_SELECT_ADDR + 100) {
                int index = (int) (offset - LEVEL_SELECT_ADDR);
                int levelIdx = index / 2;
                boolean isAct = (index % 2) != 0;

                if (levelIdx == 0)
                    return 0; // Zone 0
                if (levelIdx == 1)
                    return (byte) (isAct ? 1 : 0); // Zone 0
                if (levelIdx == 2)
                    return (byte) (isAct ? 0 : 2); // Zone 2
                if (levelIdx == 11)
                    return (byte) (isAct ? 0 : 11); // Zone 11
                if (levelIdx == 13)
                    return (byte) (isAct ? 0 : 13); // Zone 13

                return 0;
            }
            return 0;
        }

        @Override
        public int read16BitAddr(long offset) throws IOException {
            if (offset >= BG_SCROLL_TABLE_ADDR && offset < BG_SCROLL_TABLE_ADDR + 64) {
                int index = (int) (offset - BG_SCROLL_TABLE_ADDR) / 2;
                switch (index) {
                    case 0:
                        return 0x0022; // 0x00C2B8 (Clear)
                    case 2:
                        return 0x004E; // 0x00C2E4 (Common)
                    case 11:
                        return 0x009C; // 0x00C332 (Act dependent)
                    case 13:
                        return 0x00DC; // 0x00C372 (Multi layer)
                    default:
                        return 0;
                }
            }
            return 0;
        }
    }

    @Test
    public void testEhzScroll() {
        MockRom rom = new MockRom();
        Sonic2 game = new Sonic2(rom);

        // Level 0 (EHZ 1)
        int[] scroll = game.getBackgroundScroll(0, 100, 200);

        // EHZ uses 0x00C2B8 (Clear)
        // Expect 0, 0
        assertEquals(0, scroll[0]);
        assertEquals(0, scroll[1]);
    }

    @Test
    public void testCpzScroll() {
        MockRom rom = new MockRom();
        Sonic2 game = new Sonic2(rom);

        // Level 2 (CPZ 1) -> Zone 2
        // Uses 0x00C2E4
        // X = camX >>> 2
        // Y = camY >>> 3
        int camX = 100;
        int camY = 200;
        int[] scroll = game.getBackgroundScroll(2, camX, camY);

        assertEquals((camX >>> 2), scroll[0]);
        assertEquals((camY >>> 3), scroll[1]);
    }

    @Test
    public void testActDependentScroll() {
        MockRom rom = new MockRom();
        Sonic2 game = new Sonic2(rom);

        // Level 11 -> Zone 11 -> 0x00C332
        // Act 0 (Act 1)
        // d0 = (d0 | 3) - 0x140
        // ee0c = d0
        // ee08 = 0

        int camX = 500;
        int camY = 500;
        int[] scroll = game.getBackgroundScroll(11, camX, camY);

        int expectedX = ((camX | 3) - 0x140) & 0xFFFF;
        assertEquals(expectedX, scroll[0]);
        assertEquals(0, scroll[1]);
    }

    @Test
    public void testMultiLayerScroll() {
        MockRom rom = new MockRom();
        Sonic2 game = new Sonic2(rom);

        // Level 13 -> Zone 13 -> 0x00C372
        // X = camX >>> 2
        // Y = camY >>> 3 (cumulative)

        int camX = 128;
        int camY = 64;
        int[] scroll = game.getBackgroundScroll(13, camX, camY);

        assertEquals((camX >>> 2), scroll[0]);
        // The implementation at 0x00C372 explicitly says "NOT cameraY >> 3!", so we
        // trust the code.
        assertEquals((camY >>> 2), scroll[1]);
    }
}
