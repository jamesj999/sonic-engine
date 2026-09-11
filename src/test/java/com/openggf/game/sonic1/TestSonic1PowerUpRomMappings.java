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
public class TestSonic1PowerUpRomMappings {

    @Test
    public void shieldAndInvincibilityRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SHIELD_ADDR, 8);

        assertEquals(List.of(0, 4, 4, 4, 4, 4, 4, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x18, -0x18, 3, 3, 0, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0, 3, 3, 9, false, true, 0, false),
                romFrames.get(1).pieces().get(3));
        assertEquals(new SpriteMappingPiece(-0x17, -0x18, 3, 3, 0x12, true, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x18, -0x18, 3, 3, 9, true, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x18, 0, 3, 3, 9, true, true, 0, false),
                romFrames.get(4).pieces().get(2));
        assertEquals(new SpriteMappingPiece(0, 0, 3, 3, 0x1B, false, true, 0, false),
                romFrames.get(7).pieces().get(3));
    }

    @Test
    public void pointsRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_POINTS_ADDR);

        assertEquals(List.of(1, 1, 1, 1, 1, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -4, 2, 1, 0, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -4, 3, 1, 6, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-4, -4, 1, 1, 6, false, false, 0, false),
                romFrames.get(4).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x0C, -4, 3, 1, 6, false, false, 0, false),
                romFrames.get(5).pieces().get(0));
        assertEquals(new SpriteMappingPiece(6, -4, 2, 1, 7, false, false, 0, false),
                romFrames.get(6).pieces().get(1));
    }

    @Test
    public void hiddenBonusRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_HIDDEN_BONUS_ADDR);

        assertEquals(List.of(0, 1, 1, 1),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x0C, 4, 3, 0x00, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x0C, 4, 3, 0x0C, false, false, 0, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x0C, 4, 3, 0x18, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
    }

    @Test
    public void animalRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());

        List<SpriteMappingFrame> animal1Frames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_ANIMAL1_ADDR, 3);
        List<SpriteMappingFrame> animal2Frames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_ANIMAL2_ADDR, 3);
        List<SpriteMappingFrame> animal3Frames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_ANIMAL3_ADDR, 3);

        assertEquals(List.of(1, 1, 1), animal1Frames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(List.of(1, 1, 1), animal2Frames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(List.of(1, 1, 1), animal3Frames.stream().map(frame -> frame.pieces().size()).toList());

        assertEquals(new SpriteMappingPiece(-8, -0x0C, 2, 3, 0x06, false, false, 0, false),
                animal1Frames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x0C, 2, 3, 0x0C, false, false, 0, false),
                animal1Frames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x0C, 2, 3, 0x00, false, false, 0, false),
                animal1Frames.get(2).pieces().get(0));

        assertEquals(new SpriteMappingPiece(-8, -4, 2, 2, 0x06, false, false, 0, false),
                animal2Frames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -4, 2, 2, 0x0A, false, false, 0, false),
                animal2Frames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x0C, 2, 3, 0x00, false, false, 0, false),
                animal2Frames.get(2).pieces().get(0));

        assertEquals(new SpriteMappingPiece(-0x0C, -4, 3, 2, 0x06, false, false, 0, false),
                animal3Frames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x0C, -4, 3, 2, 0x0C, false, false, 0, false),
                animal3Frames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -0x0C, 2, 3, 0x00, false, false, 0, false),
                animal3Frames.get(2).pieces().get(0));
    }

    @Test
    public void specialStageResultEmeraldRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SS_RESULT_EMERALDS_ADDR);

        assertEquals(List.of(1, 1, 1, 1, 1, 1, 0),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x04, false, false, 1, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x00, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x04, false, false, 2, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x04, false, false, 3, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x08, false, false, 1, false),
                romFrames.get(4).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-8, -8, 2, 2, 0x0C, false, false, 1, false),
                romFrames.get(5).pieces().get(0));
    }

    @Test
    public void prisonRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_PRISON_ADDR);

        assertEquals(List.of(7, 1, 6, 1, 2, 1, 0),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x10, -0x20, 4, 1, 0x00, false, false, 1, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0x10, 4, 2, 0x34, false, false, 1, false),
                romFrames.get(0).pieces().get(6));
        assertEquals(new SpriteMappingPiece(-0x0C, -8, 3, 2, 0x3C, false, false, 0, false),
                romFrames.get(1).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x20, 0, 3, 1, 0x42, false, false, 1, false),
                romFrames.get(2).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x0C, -8, 3, 2, 0x4F, false, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, 0, 4, 3, 0x61, false, false, 1, false),
                romFrames.get(4).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-8, -0x10, 2, 4, 0x6D, false, false, 1, false),
                romFrames.get(5).pieces().get(0));
    }

    @Test
    public void giantRingRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_GIANT_RING_ADDR);

        assertEquals(List.of(10, 8, 4, 8),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x18, -0x20, 3, 1, 0x00, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0x18, 3, 1, 0x29, false, false, 0, false),
                romFrames.get(0).pieces().get(9));
        assertEquals(new SpriteMappingPiece(8, -8, 2, 2, 0x41, false, false, 0, false),
                romFrames.get(1).pieces().get(4));
        assertEquals(new SpriteMappingPiece(4, -0x20, 1, 4, 0x52, true, false, 0, false),
                romFrames.get(2).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x18, -8, 2, 2, 0x41, true, false, 0, false),
                romFrames.get(3).pieces().get(4));
    }

    @Test
    public void giantRingFlashRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_GIANT_RING_FLASH_ADDR);

        assertEquals(List.of(2, 4, 4, 4, 4, 4, 2, 4),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(0, -0x20, 4, 4, 0x00, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x10, -0x20, 2, 4, 0x20, false, false, 0, false),
                romFrames.get(1).pieces().get(1));
        assertEquals(new SpriteMappingPiece(-0x20, -0x20, 4, 4, 0x34, true, false, 0, false),
                romFrames.get(3).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x20, -0x20, 2, 4, 0x20, true, false, 0, false),
                romFrames.get(5).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0, 0, 4, 4, 0x44, true, true, 0, false),
                romFrames.get(7).pieces().get(3));
    }
}
