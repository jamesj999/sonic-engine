package com.openggf.tests;
import org.junit.jupiter.api.Test;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.Sonic2;
import com.openggf.level.Level;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads a ROM as Sonic2, checks if the game is compatible, reads and validates the checksum, then loads a level.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestRomLogic {
    @Test
    public void testRomLogic() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        Game game = new Sonic2(rom);

        assertTrue(game.isCompatible());

        int storedChecksum = rom.readChecksum();
        int actualChecksum = rom.calculateChecksum();

        assertEquals(actualChecksum,storedChecksum);

        Level level = game.loadLevel(0);
        level.getBlockCount();
    }

}


