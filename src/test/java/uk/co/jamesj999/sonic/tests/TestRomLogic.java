package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.games.Sonic2;
import uk.co.jamesj999.sonic.level.Level;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Loads a ROM as Sonic2, checks if the game is compatible, reads and validates the checksum, then loads a level.
 */
public class TestRomLogic {
    @Test
    public void testRomLogic() throws IOException {
        Rom rom = new Rom();
        String romFile = RomTestUtils.ensureRomAvailable().getAbsolutePath();
        rom.open(romFile);
        Game game = new Sonic2(rom);

        assertTrue(game.isCompatible());

        int storedChecksum = rom.readChecksum();
        int actualChecksum = rom.calculateChecksum();

        assertEquals(actualChecksum,storedChecksum);

        Level level = game.loadLevel(0);
        level.getBlockCount();
    }

}
