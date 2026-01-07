package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2PlayerArt;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.sprites.art.SpriteArtSet;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class Sonic2PlayerArtTest {

    @Test
    public void sonicMappingFramesMatchRev01() throws Exception {
        File romFile = RomTestUtils.ensureRomAvailable();
        Rom rom = new Rom();
        rom.open(romFile.getAbsolutePath());
        RomByteReader reader = RomByteReader.fromRom(rom);
        Sonic2PlayerArt artLoader = new Sonic2PlayerArt(reader);
        SpriteArtSet sonic = artLoader.loadForCharacter("sonic");

        assertEquals(214, sonic.mappingFrames().size());
        assertEquals(sonic.mappingFrames().size(), sonic.dplcFrames().size());
        assertEquals(Sonic2Constants.ART_UNC_SONIC_SIZE / Pattern.PATTERN_SIZE_IN_ROM, sonic.artTiles().length);
        assertFalse(sonic.mappingFrames().isEmpty());
        assertEquals(Sonic2Constants.SONIC_ANIM_SCRIPT_COUNT, sonic.animationSet().getScriptCount());
    }

    @Test
    public void tailsMappingFramesMatchRev01() throws Exception {
        File romFile = RomTestUtils.ensureRomAvailable();
        Rom rom = new Rom();
        rom.open(romFile.getAbsolutePath());
        RomByteReader reader = RomByteReader.fromRom(rom);
        Sonic2PlayerArt artLoader = new Sonic2PlayerArt(reader);
        SpriteArtSet tails = artLoader.loadForCharacter("tails");

        assertEquals(139, tails.mappingFrames().size());
        assertEquals(tails.mappingFrames().size(), tails.dplcFrames().size());
        assertEquals(Sonic2Constants.ART_UNC_TAILS_SIZE / Pattern.PATTERN_SIZE_IN_ROM, tails.artTiles().length);
        assertFalse(tails.mappingFrames().isEmpty());
    }
}
