package com.openggf.game.sonic1;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1EndingRomMappings {

    @Test
    public void endingEmeraldRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_END_EMERALDS_ADDR);

        assertEquals(List.of(1, 1, 1, 1, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x00, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x04, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x10, false, false, 2, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x18, false, false, 1, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x14, false, false, 2, false),
                romFrames.get(4).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x08, false, false, 0, false),
                romFrames.get(5).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x0C, false, false, 0, false),
                romFrames.get(6).pieces().get(0));
    }

    @Test
    public void endingSthRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_END_STH_ADDR);

        assertEquals(List.of(3),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x30, -0x10, 4, 4, 0x00, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x10, false, false, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0x10, -0x10, 4, 4, 0x20, false, false, 0, false),
                romFrames.get(0).pieces().get(2));
    }
}
