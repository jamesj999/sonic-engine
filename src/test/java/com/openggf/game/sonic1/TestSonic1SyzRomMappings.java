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
public class TestSonic1SyzRomMappings {

    @Test
    public void springRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SPRING_ADDR);

        assertEquals(List.of(2, 1, 3, 1, 1, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -8, 4, 1, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, 0, 4, 1, 4, false, false, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x10, -0x18, 4, 1, 0, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x10, 2, 4, 0, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x10, -0x10, 1, 4, 4, false, false, 0, false),
                romFrames.get(5).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, 8, 1, 1, 3, false, false, 0, false),
                romFrames.get(5).pieces().get(3));
    }

    @Test
    public void bumperRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SYZ_BUMPER_ADDR);

        assertEquals(List.of(2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 2, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, -0x10, 2, 4, 0, true, false, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(4, -0x0C, 1, 3, 8, true, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0, -0x10, 2, 4, 0x0E, true, false, 0, false),
                romFrames.get(2).pieces().get(1));
    }

    @Test
    public void yadrinRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_YADRIN_ADDR);

        assertEquals(List.of(5, 5, 5, 5, 5, 5),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x0C, -0x0C, 3, 1, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x04, 0x04, 3, 2, 0x31, false, false, 0, false),
                romFrames.get(0).pieces().get(4));
        assertEquals(new SpriteMappingPiece(-0x0C, -0x0C, 3, 2, 0x23, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x04, 0x04, 3, 2, 0x37, false, false, 0, false),
                romFrames.get(5).pieces().get(4));
    }

    @Test
    public void rollerRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_ROLLER_ADDR);

        assertEquals(List.of(2, 2, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x22, 4, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -2, 4, 2, 0x18, false, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x20, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x40, false, false, 0, false),
                romFrames.get(4).pieces().get(0));
    }

    @Test
    public void syzSpikeballChainRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SYZ_SPIKEBALL_CHAIN_ADDR);

        assertEquals(List.of(1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
    }

    @Test
    public void bigSpikedBallRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_BIG_SPIKED_BALL_ADDR);

        assertEquals(List.of(5, 1, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x18, 2, 1, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, 0x10, 2, 1, 0x16, false, false, 0, false),
                romFrames.get(0).pieces().get(4));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x20, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x18, 4, 2, 0x18, false, true, 0, false),
                romFrames.get(2).pieces().get(1));
    }

    @Test
    public void floatingBlockRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_FLOATING_BLOCK_ADDR);

        assertEquals(List.of(1, 4, 2, 4, 3, 1, 2, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x61, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0, 4, 4, 0x61, false, false, 0, false),
                romFrames.get(1).pieces().get(3));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0x21, false, false, 0, false),
                romFrames.get(5).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, 0, 2, 4, 0, false, true, 0, false),
                romFrames.get(6).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0x20, -0x10, 4, 4, 0x22, false, false, 0, false),
                romFrames.get(7).pieces().get(3));
    }

    @Test
    public void spinningLightRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SYZ_SPINNING_LIGHT_ADDR);

        assertEquals(List.of(2, 2, 2, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -8, 4, 1, 0x31, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, 0, 4, 1, 0x31, false, true, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x10, -8, 4, 1, 0x3D, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, 0, 4, 1, 0x45, false, true, 0, false),
                romFrames.get(5).pieces().get(1));
    }

    @Test
    public void platformRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_PLATFORM_SYZ_ADDR);

        assertEquals(List.of(3),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x20, -0x0A, 3, 4, 0x49, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x08, -0x0A, 3, 4, 0x55, false, false, 0, false),
                romFrames.get(0).pieces().get(2));
    }

    @Test
    public void bossBlockRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SYZ_BOSS_BLOCK_ADDR);

        assertEquals(List.of(2, 1, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 2, 0x71, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, 0, 4, 2, 0x79, false, false, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x71, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x75, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x79, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x7D, false, false, 0, false),
                romFrames.get(4).pieces().get(0));
    }
}
