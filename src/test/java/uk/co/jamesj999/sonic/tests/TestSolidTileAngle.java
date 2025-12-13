package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.level.SolidTile;
import static org.junit.Assert.assertEquals;

public class TestSolidTileAngle {

    @Test
    public void testAngleFlipping() {
        byte[] heights = new byte[16];
        byte[] widths = new byte[16];

        // Case 1: Flat Floor (Angle 0)
        // Original: 0x00
        // H-Flip: -0 = 0x00
        // V-Flip: 0x80 - 0 = 0x80
        // Both: 0x80 + 0 = 0x80
        checkAngle((byte) 0x00, (byte) 0x00, (byte) 0x80, (byte) 0x80);

        // Case 2: 45 degree slope (Angle 0x20)
        // Original: 0x20
        // H-Flip: -0x20 = 0xE0
        // V-Flip: 0x80 - 0x20 = 0x60
        // Both: 0x80 + 0x20 = 0xA0
        checkAngle((byte) 0x20, (byte) 0xE0, (byte) 0x60, (byte) 0xA0);

        // Case 3: Vertical Wall Down (Angle 0x40)
        // Original: 0x40
        // H-Flip: -0x40 = 0xC0
        // V-Flip: 0x80 - 0x40 = 0x40
        // Both: 0x80 + 0x40 = 0xC0
        checkAngle((byte) 0x40, (byte) 0xC0, (byte) 0x40, (byte) 0xC0);

        // Case 4: Slope Up-Right (Angle 0xE0 / -0x20)
        // Original: 0xE0
        // H-Flip: -0xE0 = 0x20
        // V-Flip: 0x80 - 0xE0 = 0x80 - (-0x20) = 0xA0
        // Both: 0x80 + 0xE0 = 0x60
        checkAngle((byte) 0xE0, (byte) 0x20, (byte) 0xA0, (byte) 0x60);

        // Case 5: Flagged Tile (Angle 0xFF / -1)
        // Should ignore flips and return 0xFF
        checkAngle((byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF);
    }

    private void checkAngle(byte original, byte expectedH, byte expectedV, byte expectedBoth) {
        SolidTile tile = new SolidTile(0, new byte[16], new byte[16], original);
        assertEquals("No Flip", original, tile.getAngle(false, false));
        assertEquals("H Flip", expectedH, tile.getAngle(true, false));
        assertEquals("V Flip", expectedV, tile.getAngle(false, true));
        assertEquals("Both Flip", expectedBoth, tile.getAngle(true, true));
    }
}
