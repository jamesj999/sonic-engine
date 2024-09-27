package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.games.Sonic2;
import uk.co.jamesj999.sonic.level.Level;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestRomLogic {
    @Test
    public void testRomLogic() throws IOException {
        Rom rom = new Rom();
        rom.open("Sonic The Hedgehog 2 (W) (REV01) [!].gen");
        Game game = new Sonic2(rom);

        assertTrue(game.isCompatible());

        int storedChecksum = rom.readChecksum();
        int actualChecksum = rom.calculateChecksum();

        assertEquals(actualChecksum,storedChecksum);

        Level level = game.loadLevel(2);
        level.getBlockCount();
    }

}
