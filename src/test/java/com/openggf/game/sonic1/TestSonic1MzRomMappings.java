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
public class TestSonic1MzRomMappings {

    @Test
    public void fireballRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_FIREBALL_ADDR);

        assertEquals(List.of(1, 1, 1, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x18, 2, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x10, 2, 3, 0x10, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -8, 3, 2, 0x26, false, false, 0, false),
                romFrames.get(5).pieces().get(0));
    }

    @Test
    public void glassBlockRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_MZ_GLASS_ADDR);

        assertEquals(List.of(12, 2, 10),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x20, -0x48, 4, 1, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0x40, 4, 1, 0, true, true, 0, false),
                romFrames.get(0).pieces().get(11));
        assertEquals(new SpriteMappingPiece(-0x10, 8, 2, 3, 0x14, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0x30, 4, 1, 0, true, true, 0, false),
                romFrames.get(2).pieces().get(9));
    }

    @Test
    public void lavaGeyserRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_MZ_LAVA_GEYSER_ADDR);

        assertEquals(List.of(2, 2, 4, 4, 6, 6, 2, 2, 10, 10, 10, 6, 6, 6, 16, 16, 16, 6, 6, 0),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x18, -0x14, 3, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, -0x14, 3, 4, 0x18, true, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x20, 0x10, 4, 4, 0x40, false, false, 0, false),
                romFrames.get(8).pieces().get(8));
        assertEquals(new SpriteMappingPiece(0, 0x70, 4, 4, 0x60, true, false, 0, false),
                romFrames.get(16).pieces().get(15));
        assertEquals(new SpriteMappingPiece(0, -0x28, 4, 3, 0x90, true, false, 0, false),
                romFrames.get(17).pieces().get(5));
    }

    @Test
    public void mzLargeGrassyPlatformRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_MZ_LARGE_GRASSY_PLATFORM_ADDR);

        assertEquals(List.of(13, 10, 6),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x40, -0x28, 2, 3, 0x57, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x30, -0x10, 2, 2, 0x53, false, false, 0, false),
                romFrames.get(0).pieces().get(12));
        assertEquals(new SpriteMappingPiece(-0x20, -0x40, 4, 4, 0x27, false, false, 0, false),
                romFrames.get(1).pieces().get(3));
        assertEquals(new SpriteMappingPiece(0, 0x10, 4, 4, 1, false, false, 0, false),
                romFrames.get(2).pieces().get(5));
    }

    @Test
    public void basaranRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_BASARAN_ADDR);

        assertEquals(List.of(1, 3, 4, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x0C, 2, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x0C, 0x02, 1, 1, 0x27, false, false, 0, false),
                romFrames.get(1).pieces().get(2));
        assertEquals(new SpriteMappingPiece(-0x10, 0, 4, 1, 0x16, false, false, 0, false),
                romFrames.get(2).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0x0C, -2, 1, 1, 0x27, false, false, 0, false),
                romFrames.get(3).pieces().get(3));
    }

    @Test
    public void mzBrickRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_MZ_BRICK_ADDR);

        assertEquals(List.of(1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 1, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
    }

    @Test
    public void mzSmashBlockRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_MZ_SMASH_BLOCK_ADDR);

        assertEquals(List.of(2, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, 0, 4, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 2, 2, 0, false, false, 0, true),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0, 2, 2, 0, false, false, 0, true),
                romFrames.get(1).pieces().get(3));
    }

    @Test
    public void mzSbzMovingBlockRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_MZ_SBZ_MOVING_BLOCK_ADDR);

        assertEquals(List.of(1, 2, 4, 4, 3),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -8, 4, 4, 8, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, -8, 4, 4, 8, false, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x20, -8, 4, 1, 0, false, false, 1, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x20, -8, 4, 3, 0, true, false, 0, false),
                romFrames.get(3).pieces().get(3));
        assertEquals(new SpriteMappingPiece(0x10, -8, 4, 4, 8, false, false, 0, false),
                romFrames.get(4).pieces().get(2));
    }

    @Test
    public void pushBlockRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_PUSH_BLOCK_ADDR);

        assertEquals(List.of(1, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 8, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x40, -0x10, 4, 4, 8, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x20, -0x10, 4, 4, 8, false, false, 0, false),
                romFrames.get(1).pieces().get(3));
    }

    @Test
    public void collapsingFloorRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_COLLAPSING_FLOOR_ADDR);

        assertEquals(List.of(4, 8, 4, 8),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x20, -8, 4, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x10, 8, 2, 2, 0, false, false, 0, false),
                romFrames.get(1).pieces().get(7));
        assertEquals(new SpriteMappingPiece(-0x20, 8, 4, 2, 8, false, false, 0, false),
                romFrames.get(2).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0x10, 8, 2, 2, 0x0C, false, false, 0, false),
                romFrames.get(3).pieces().get(7));
    }

    @Test
    public void caterkillerRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_CATERKILLER_ADDR);

        assertEquals(List.of(
                        1, 1, 1, 1, 1, 1, 1, 1,
                        1, 1, 1, 1, 1, 1, 1, 1,
                        1, 1, 1, 1, 1, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x0E, 2, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x15, 2, 3, 0, false, false, 0, false),
                romFrames.get(7).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x0C, false, false, 0, false),
                romFrames.get(8).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x0F, 2, 2, 0x0C, false, false, 0, false),
                romFrames.get(15).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x0E, 2, 3, 0x06, false, false, 0, false),
                romFrames.get(16).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x15, 2, 3, 0x06, false, false, 0, false),
                romFrames.get(23).pieces().get(0));
    }

    @Test
    public void mzChainedStomperRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_MZ_CHAINED_STOMPER_ADDR);

        assertEquals(List.of(5, 5, 1, 2, 4, 6, 8, 10, 10, 5, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x38, -0x0C, 2, 3, 0x00, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x24, 4, 4, 0x0F, false, true, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-4, -0x80, 1, 2, 0x3F, false, false, 0, false),
                romFrames.get(7).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x14, 4, 4, 0x2F, false, false, 0, false),
                romFrames.get(10).pieces().get(0));
    }

    @Test
    public void mzLavaWallRomMappingsKeepExpectedTableShapeAndFinalTileIds() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_MZ_LAVA_WALL_ADDR);

        assertEquals(List.of(9, 9, 9, 9, 8),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(0x20, -0x20, 4, 4, 0x60, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x20, 0, 4, 4, 0x72A, true, true, 3, true),
                romFrames.get(0).pieces().get(2));

        List<SpriteMappingFrame> remapped = Sonic1ObjectArtProvider.createLavaWallMappingsFromRom(romFrames);
        assertEquals(new SpriteMappingPiece(0x20, -0x20, 4, 4, 0x408, false, false, 0, false),
                remapped.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x3C, 0, 4, 4, 0x418, false, false, 0, false),
                remapped.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0x20, 0, 4, 4, 0x2D2, false, false, 0, false),
                remapped.get(0).pieces().get(2));
        assertEquals(new SpriteMappingPiece(0x20, -0x20, 4, 4, 0x2D2, false, false, 0, false),
                remapped.get(4).pieces().get(0));
    }
}
