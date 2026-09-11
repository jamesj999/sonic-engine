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
public class TestSonic1LzRomMappings {

    @Test
    public void harpoonRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_HARPOON_ADDR);

        assertEquals(List.of(1, 1, 2, 1, 1, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -4, 2, 1, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x10, -4, 3, 1, 3, false, false, 0, false),
                romFrames.get(2).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-4, -0x10, 1, 3, 0x0F, false, false, 0, false),
                romFrames.get(5).pieces().get(1));
    }

    @Test
    public void jawsRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_JAWS_ADDR);

        assertEquals(List.of(2, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x0C, 4, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x10, -0x0B, 2, 2, 0x18, false, true, 0, false),
                romFrames.get(2).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0x10, -0x0B, 2, 2, 0x1C, false, true, 0, false),
                romFrames.get(3).pieces().get(1));
    }

    @Test
    public void burrobotRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_BURROBOT_ADDR);

        assertEquals(List.of(2, 2, 2, 2, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x14, 3, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x18, -0x0C, 2, 3, 0x4B, false, false, 0, false),
                romFrames.get(5).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x0C, 0x04, 3, 2, 9, false, false, 0, false),
                romFrames.get(6).pieces().get(1));
    }

    @Test
    public void orbinautRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_ORBINAUT_ADDR);

        assertEquals(List.of(1, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x0C, -0x0C, 3, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x0C, -0x0C, 3, 3, 9, false, false, 1, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x0C, -0x0C, 3, 3, 0x12, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x1B, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
    }

    @Test
    public void flappingDoorRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_FLAPPING_DOOR_ADDR);

        assertEquals(List.of(2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x20, 2, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-5, 6, 4, 4, 8, false, true, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0, 0x18, 4, 2, 0x18, false, true, 0, false),
                romFrames.get(2).pieces().get(1));
    }

    @Test
    public void breakablePoleRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_BREAKABLE_POLE_ADDR);

        assertEquals(List.of(2, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-4, -0x20, 1, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-4, 0, 1, 4, 0, false, true, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-4, -0x10, 2, 2, 4, false, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-4, 0x10, 1, 2, 0, false, true, 0, false),
                romFrames.get(1).pieces().get(3));
    }

    @Test
    public void movingBlockRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_MOVING_BLOCK_ADDR);

        assertEquals(List.of(1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -8, 4, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
    }

    @Test
    public void labyrinthBlockRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_BLOCK_ADDR);

        assertEquals(List.of(1, 2, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x20, -0x0C, 4, 3, 0x69, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, -0x0C, 4, 3, 0x75, false, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x11A, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x5FA, true, true, 3, true),
                romFrames.get(3).pieces().get(0));
    }

    @Test
    public void conveyorRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_CONVEYOR_ADDR);

        assertEquals(List.of(1, 1, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x10, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x30, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -8, 4, 2, 0x40, false, false, 0, false),
                romFrames.get(4).pieces().get(0));
    }

    @Test
    public void bubblesRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_BUBBLES_ADDR);

        assertEquals(List.of(
                        1, 1, 1, 1, 1, 1, 1, 4, 4,
                        1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                        1, 1, 1, 0),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-4, -4, 1, 1, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0, 2, 2, 0x24, true, true, 0, false),
                romFrames.get(7).pieces().get(3));
        assertEquals(new SpriteMappingPiece(-8, -12, 2, 3, 0x44, false, false, 1, false),
                romFrames.get(13).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x70, false, false, 0, false),
                romFrames.get(21).pieces().get(0));
    }

    @Test
    public void waterfallRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_WATERFALL_ADDR);

        assertEquals(List.of(1, 2, 2, 1, 2, 1, 1, 1, 2, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x10, 2, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x18, 0, 4, 1, 0x21, false, false, 0, false),
                romFrames.get(8).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0, -0x10, 3, 4, 0x61, false, false, 0, false),
                romFrames.get(11).pieces().get(1));
    }

    @Test
    public void splashRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_SPLASH_ADDR);

        assertEquals(List.of(2, 2, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x0E, 2, 1, 0x6D, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x16, 4, 3, 0x74, false, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x10, -0x1E, 4, 4, 0x80, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
    }

    @Test
    public void lzSpikeballChainRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_SPIKEBALL_CHAIN_ADDR);

        assertEquals(List.of(1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 4, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x14, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
    }

    @Test
    public void gargoyleRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_LZ_GARGOYLE_ADDR);

        assertEquals(List.of(3, 3, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(0, -0x10, 2, 1, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -8, 4, 2, 2, false, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-8, -4, 2, 1, 0x0D, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -4, 2, 1, 0x0F, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
    }
}
