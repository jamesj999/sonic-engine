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
public class TestSonic1SbzRomMappings {

    @Test
    public void buttonRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_BUTTON_ADDR);

        assertEquals(List.of(2, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x0B, 2, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x0B, 2, 2, 0x7FC, true, true, 3, true),
                romFrames.get(2).pieces().get(0));
        assertEquals(romFrames.get(1), romFrames.get(3));
    }

    @Test
    public void sbzFalseFloorRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_FALSE_FLOOR_ADDR);

        assertEquals(List.of(1, 2, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, -8, 1, 2, 0x0E, false, false, 0, false),
                romFrames.get(4).pieces().get(1));
    }

    @Test
    public void sbzFlamethrowerRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_FLAMETHROWER_ADDR);

        assertEquals(List.of(1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
                        1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-5, 0x28, 2, 2, 0x14, false, false, 2, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x0C, -0x19, 3, 4, 8, true, false, 0, false),
                romFrames.get(10).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-7, 0x28, 2, 2, 0x18, false, false, 2, false),
                romFrames.get(11).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-4, 0x20, 1, 2, 0, true, false, 0, false),
                romFrames.get(21).pieces().get(5));
    }

    @Test
    public void sbzGirderRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_GIRDER_ADDR);

        assertEquals(List.of(12),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x60, -0x18, 4, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x60, 0, 4, 3, 0, false, true, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0x40, 0, 4, 3, 6, false, true, 0, false),
                romFrames.get(0).pieces().get(11));
    }

    @Test
    public void sbzTrapDoorRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_TRAP_DOOR_ADDR);

        assertEquals(List.of(4, 8, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x40, -0x0C, 4, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x42, 0x12, 3, 3, 0x1C, true, true, 0, false),
                romFrames.get(1).pieces().get(3));
        assertEquals(new SpriteMappingPiece(0x34, 0x20, 3, 4, 0x25, false, true, 0, false),
                romFrames.get(2).pieces().get(3));
    }

    @Test
    public void sbzSmallDoorRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_SMALL_DOOR_ADDR);

        assertEquals(List.of(2, 2, 2, 2, 2, 2, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -0x20, 2, 4, 0, true, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, 0x10, 2, 4, 0, true, false, 0, false),
                romFrames.get(4).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-8, 0x20, 2, 4, 0, true, false, 0, false),
                romFrames.get(8).pieces().get(1));
    }

    @Test
    public void ballHogRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_BALL_HOG_ADDR);

        assertEquals(List.of(2, 2, 2, 2, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x0C, -0x11, 3, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x0C, -1, 3, 3, 0x0F, false, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x0C, 4, 3, 2, 0x18, false, false, 0, false),
                romFrames.get(2).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x0C, -0x0C, 3, 3, 0x1E, false, false, 0, false),
                romFrames.get(3).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x27, false, false, 0, false),
                romFrames.get(4).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x2B, false, false, 0, false),
                romFrames.get(5).pieces().get(0));
    }

    @Test
    public void sbzSpinningPlatformRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_SPINNING_PLATFORM_ADDR);

        assertEquals(List.of(2, 2, 2, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -8, 2, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, 0, 3, 2, 0x0A, false, false, 0, false),
                romFrames.get(2).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-8, 0, 2, 2, 0x10, false, true, 0, false),
                romFrames.get(4).pieces().get(1));
    }

    @Test
    public void runningDiscRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_RUNNING_DISC_ADDR);

        assertEquals(List.of(1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
    }

    @Test
    public void sbzVanishingPlatformRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_VANISHING_PLATFORM_ADDR);

        assertEquals(List.of(1, 1, 1, 0),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -8, 4, 4, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 4, 0x10, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-4, -8, 1, 4, 0x18, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
    }

    @Test
    public void sbzElectrocuterRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_ELECTROCUTER_ADDR);

        assertEquals(List.of(2, 3, 5, 4, 6, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 1, 0, false, false, 3, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, 0, 2, 3, 2, false, false, 2, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x24, -0x0A, 4, 2, 0x0C, true, false, 0, false),
                romFrames.get(2).pieces().get(4));
        assertEquals(new SpriteMappingPiece(-0x40, -0x0A, 4, 2, 0x0C, true, true, 0, false),
                romFrames.get(5).pieces().get(3));
    }

    @Test
    public void sbzSawRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_SAW_ADDR);

        assertEquals(List.of(7, 7, 4, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-4, -0x3C, 1, 2, 0x20, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0, 4, 4, 0, true, true, 0, false),
                romFrames.get(0).pieces().get(6));
        assertEquals(new SpriteMappingPiece(-0x20, -0x20, 4, 4, 0x10, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0, 4, 4, 0x10, true, true, 0, false),
                romFrames.get(3).pieces().get(3));
    }

    @Test
    public void sbzStomperDoorRomMappingsKeepExpectedTableShapeAndSheetRemaps() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_STOMPER_DOOR_ADDR);

        assertEquals(List.of(4, 8, 8, 8, 14),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x40, -0x0C, 4, 3, 0x1AF, false, false, 1, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x20, -0x0C, 4, 3, 0x1AF, true, false, 1, false),
                romFrames.get(0).pieces().get(3));
        assertEquals(new SpriteMappingPiece(-0x1C, -0x20, 4, 1, 0x0C, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x1C, -0x18, 4, 3, 0x13, false, false, 1, false),
                romFrames.get(1).pieces().get(2));
        assertEquals(new SpriteMappingPiece(-0x80, -0x40, 4, 4, 0, false, false, 0, false),
                romFrames.get(4).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x80, 0x20, 4, 4, 0x58, false, false, 0, false),
                romFrames.get(4).pieces().get(13));

        List<SpriteMappingFrame> combinedSheetFrames =
                Sonic1ObjectArtProvider.createStomperDoorMappingsFromRom(romFrames, 0x40);
        assertEquals(4, combinedSheetFrames.size());
        assertEquals(new SpriteMappingPiece(-0x40, -0x0C, 4, 3, 0x40, false, false, 1, false),
                combinedSheetFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x20, -0x0C, 4, 3, 0x43, false, false, 1, false),
                combinedSheetFrames.get(0).pieces().get(1));
        assertEquals(romFrames.get(1), combinedSheetFrames.get(1));

        List<SpriteMappingFrame> bigDoorFrames =
                Sonic1ObjectArtProvider.createSbz3BigDoorMappingsFromRom(romFrames, 0x120);
        assertEquals(List.of(14), bigDoorFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x80, -0x40, 4, 4, 0x120, false, false, 0, false),
                bigDoorFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x80, 0x20, 4, 4, 0x178, false, false, 0, false),
                bigDoorFrames.get(0).pieces().get(13));
    }

    @Test
    public void sbzJunctionRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SBZ_JUNCTION_ADDR);

        assertEquals(List.of(
                        6, 6, 6, 6, 6, 6, 6, 6,
                        6, 6, 6, 6, 6, 6, 6, 6, 12),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x30, -0x18, 2, 2, 0x22, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x30, 0x08, 2, 2, 0x22, false, true, 0, false),
                romFrames.get(0).pieces().get(1));
        assertEquals(new SpriteMappingPiece(0x20, -0x18, 2, 2, 0x22, true, false, 0, false),
                romFrames.get(8).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x20, -0x38, 4, 2, 9, false, false, 0, false),
                romFrames.get(16).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x28, 0, 2, 4, 0x1A, true, true, 0, false),
                romFrames.get(16).pieces().get(11));
    }

    @Test
    public void bombRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_BOMB_ADDR);

        assertEquals(List.of(3, 3, 3, 3, 3, 3, 2, 2, 1, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x0C, -0x0F, 3, 3, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x04, -0x19, 1, 2, 0x21, false, false, 0, false),
                romFrames.get(5).pieces().get(2));
        assertEquals(new SpriteMappingPiece(-0x0C, 0x09, 3, 1, 0x12, false, false, 0, false),
                romFrames.get(7).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x04, -0x19, 1, 2, 0x25, false, false, 0, false),
                romFrames.get(9).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x04, -0x04, 1, 1, 0x28, false, false, 0, false),
                romFrames.get(11).pieces().get(0));
    }
}
