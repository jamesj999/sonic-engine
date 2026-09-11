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
public class TestSonic1ResultsRomMappings {

    @Test
    public void gotThroughActCardRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFramesWithSignedOffsets(
                reader, Sonic1Constants.MAP_RESULTS_GOT_ADDR);

        assertEquals(List.of(8, 6, 6, 7, 7, 13, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x48, -8, 2, 2, 0x3E, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x30, -8, 2, 2, 0x3E, false, false, 0, false),
                romFrames.get(0).pieces().get(7));
        assertEquals(new SpriteMappingPiece(-0x33, -9, 2, 1, 0x6E, false, false, 0, false),
                romFrames.get(2).pieces().get(4));
        assertEquals(new SpriteMappingPiece(-0x14, 0x04, 4, 1, 0x53, false, false, 0, false),
                romFrames.get(6).pieces().get(0));
    }

    @Test
    public void specialStageResultRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFramesWithSignedOffsets(
                reader, Sonic1Constants.MAP_RESULTS_SPECIAL_STAGE_ADDR);

        assertEquals(List.of(13, 6, 7, 13, 4, 4, 3, 12, 15),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x70, -8, 2, 2, 0x08, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x60, -8, 2, 2, 0x3E, false, false, 0, false),
                romFrames.get(0).pieces().get(12));
        assertEquals(new SpriteMappingPiece(-0x64, -8, 2, 2, 0x3E, false, false, 0, false),
                romFrames.get(7).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x78, -8, 2, 2, 0x26, false, false, 0, false),
                romFrames.get(8).pieces().get(14));
    }

    @Test
    public void composedResultsMappingsPreserveRuntimeFrameContract() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> frames = Sonic1ResultsMappingLoader.load(reader);

        assertEquals(List.of(8, 6, 4, 7, 7, 13, 2, 2, 2, 2, 13, 12, 15),
                frames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x48, -8, 2, 2, 0x4E, false, false, 0, false),
                frames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x50, -8, 4, 2, 0x15A, false, false, 0, false),
                frames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x33, -9, 2, 1, 0x7E, false, false, 0, false),
                frames.get(9).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x28, -8, 4, 2, 0x00, false, false, 0, false),
                frames.get(3).pieces().get(5));
        assertEquals(new SpriteMappingPiece(0x28, -8, 4, 2, 0x08, false, false, 0, false),
                frames.get(4).pieces().get(5));
        assertEquals(new SpriteMappingPiece(-0x64, -8, 2, 2, 0x4E, false, false, 0, false),
                frames.get(11).pieces().get(0));
    }

    @Test
    public void endingSonicRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_END_SONIC_ADDR);

        assertEquals(List.of(2, 3, 2, 2, 2, 3, 7, 24),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x14, 3, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x14, 3, 2, 0x2A, true, false, 0, false),
                romFrames.get(4).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x4E, 4, 1, 0x4A, false, false, 0, false),
                romFrames.get(6).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x10, 2, 3, 0x13D, false, false, 0, false),
                romFrames.get(7).pieces().get(23));
    }
}
