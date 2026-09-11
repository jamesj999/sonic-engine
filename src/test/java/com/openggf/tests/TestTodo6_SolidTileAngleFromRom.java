package com.openggf.tests;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.level.SolidTile;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TODO #6 coverage: SolidTile "TODO add angle recalculations" (SolidTile.java line 21).
 * <p>
 * The SolidTile constructor stores the raw angle byte from ROM (ColCurveMap / "Curve and
 * resistance mapping.bin") and provides getAngle(hFlip, vFlip) for flip transformations.
 * This test verifies that the ROM's ColCurveMap data at SOLID_TILE_ANGLE_ADDR matches
 * expected angle values for known collision tile indices.
 * <p>
 * The angle map has 0x100 entries (one byte per collision tile index). Index 0 is unused.
 * <p>
 * Reference:
 * - docs/s2disasm/s2.asm line 89595: ColCurveMap: BINCLUDE "collision/Curve and resistance mapping.bin"
 * - docs/s2disasm/s2.asm line 42986: move.b (a2,d0.w),(a4) ; get angle from AngleMap
 * - Sonic2Constants.SOLID_TILE_ANGLE_ADDR = 0x42D50
 * - Sonic2Constants.SOLID_TILE_ANGLE_SIZE = 0x100
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestTodo6_SolidTileAngleFromRom {
    /**
     * Verify that flat floor tiles (e.g., tile 1 = full solid block) have angle 0xFF.
     * Angle 0xFF means "no angle" - the tile is a full solid block with no slope.
     * This is the canonical flat floor collision tile used throughout the game.
     */
    @Test
    public void testFullSolidBlockAngle() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        // Tile index 1 is typically the full solid block (all 16 height values = 16)
        byte angle = rom.readByte(Sonic2Constants.SOLID_TILE_ANGLE_ADDR + 1);
        assertEquals((byte) 0xFF, angle, "Full solid tile (index 1) should have angle 0xFF");
    }

    /**
     * Verify SolidTile correctly stores the ROM angle value and applies flip transforms.
     * This cross-references the ROM data with the SolidTile class behavior.
     */
    @Test
    public void testSolidTileConstructorUsesRomAngle() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();

        // Read the height and angle data for a known sloped tile
        // We use tile index 2 which should be a slope
        byte angleFromRom = rom.readByte(Sonic2Constants.SOLID_TILE_ANGLE_ADDR + 2);

        // Construct a SolidTile with this angle
        byte[] heights = rom.readBytes(
                Sonic2Constants.SOLID_TILE_VERTICAL_MAP_ADDR + 2 * SolidTile.TILE_SIZE_IN_ROM,
                SolidTile.TILE_SIZE_IN_ROM);
        byte[] widths = rom.readBytes(
                Sonic2Constants.SOLID_TILE_HORIZONTAL_MAP_ADDR + 2 * SolidTile.TILE_SIZE_IN_ROM,
                SolidTile.TILE_SIZE_IN_ROM);

        SolidTile tile = new SolidTile(2, heights, widths, angleFromRom);

        // Verify the base angle matches
        assertEquals(angleFromRom, tile.getAngle(), "SolidTile should store exact ROM angle");

        // Verify no-flip returns the same angle
        assertEquals(angleFromRom, tile.getAngle(false, false), "No-flip should return original angle");
    }

    /**
     * Verify that the disassembly's flip transformation matches our SolidTile implementation.
     * <p>
     * From s2.asm lines 42988-42998:
     * - H-flip: neg.b (a4)
     * - V-flip: addi.b #$40,(a4); neg.b (a4); subi.b #$40,(a4)
     * Which is equivalent to: 0x80 - angle
     */
    @Test
    public void testFlipTransformMatchesDisassembly() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();

        // Pick a sloped tile with a known non-trivial angle
        // Read through angles to find one in the ~0x10-0x30 range (gentle slope)
        byte[] angles = rom.readBytes(Sonic2Constants.SOLID_TILE_ANGLE_ADDR,
                Sonic2Constants.SOLID_TILE_ANGLE_SIZE);

        for (int i = 2; i < angles.length; i++) {
            int rawAngle = angles[i] & 0xFF;
            if (rawAngle > 0x10 && rawAngle < 0x30) {
                // Found a gentle slope tile - test flip transforms

                byte[] heights = rom.readBytes(
                        Sonic2Constants.SOLID_TILE_VERTICAL_MAP_ADDR + i * SolidTile.TILE_SIZE_IN_ROM,
                        SolidTile.TILE_SIZE_IN_ROM);
                byte[] widths = rom.readBytes(
                        Sonic2Constants.SOLID_TILE_HORIZONTAL_MAP_ADDR + i * SolidTile.TILE_SIZE_IN_ROM,
                        SolidTile.TILE_SIZE_IN_ROM);

                SolidTile tile = new SolidTile(i, heights, widths, angles[i]);

                // H-flip: neg.b angle = -angle (two's complement)
                byte expectedHFlip = (byte) (-rawAngle);
                assertEquals(expectedHFlip, tile.getAngle(true, false), String.format("Tile %d H-flip angle", i));

                // V-flip: 0x80 - angle
                byte expectedVFlip = (byte) (0x80 - rawAngle);
                assertEquals(expectedVFlip, tile.getAngle(false, true), String.format("Tile %d V-flip angle", i));

                return; // Found and tested a suitable tile
            }
        }
        // If no suitable tile found, that's unexpected
        assertTrue(false, "Should find at least one gentle slope tile in angle data");
    }
}


