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
public class TestSonic1FzRomMappings {

    @Test
    public void fzLegsAndDamagedRomMappingsStayWithinFzEggmanPatternRange() throws Exception {
        // Nem_FzEggman ("Boss - Eggman after FZ Fight") is 0x4C patterns in REV01.
        // Map_FZLegs and Map_FZDamaged must remain within that local tile range.
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        int maxLegsTile = maxTileIndex(S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_FZ_LEGS_ADDR));
        int maxDamagedTile = maxTileIndex(S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_FZ_DAMAGED_ADDR));

        assertEquals(0x1F, maxLegsTile, "Map_FZLegs max tile should be $1F");
        assertEquals(0x4B, maxDamagedTile, "Map_FZDamaged max tile should be $4B");
    }

    @Test
    public void fzCylinderRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_FZ_EGGCYL_ADDR);

        assertEquals(List.of(6, 8, 10, 12, 13, 14, 14, 14, 14, 14, 14, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x20, -0x60, 4, 2, 0x00, false, false, 2, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, 0, 4, 1, 0x6A, false, false, 0, false),
                romFrames.get(11).pieces().get(1));
    }

    @Test
    public void seggRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_SEGG_ADDR);

        assertEquals(List.of(3, 4, 4, 4, 4, 4, 7, 5, 6, 8, 3),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x18, -4, 1, 1, 0x8F, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(-0x10, -0x20, 4, 2, 0x6F0, true, true, 1, false),
                romFrames.get(9).pieces().get(4));
    }

    @Test
    public void eggmanRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_EGGMAN_ADDR);

        assertEquals(List.of(6, 2, 2, 3, 3, 3, 3, 4, 1, 1, 0, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(-0x1C, -0x14, 1, 2, 0x0A, false, false, 0, false),
                romFrames.get(0).pieces().get(0));
        assertEquals(new SpriteMappingPiece(0x22, 8, 3, 1, 0x12A, false, true, 0, false),
                romFrames.get(11).pieces().get(1));
    }

    @Test
    public void bossItemsRomMappingsKeepExpectedTableShape() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        List<SpriteMappingFrame> romFrames = S1SpriteDataLoader.loadMappingFrames(
                reader, Sonic1Constants.MAP_BOSS_ITEMS_ADDR);

        assertEquals(List.of(1, 2, 1, 1, 1, 4, 2, 2),
                romFrames.stream().map(frame -> frame.pieces().size()).toList());
        assertEquals(new SpriteMappingPiece(0, -8, 1, 2, 0x13, true, false, 0, false),
                romFrames.get(5).pieces().get(2));
        assertEquals(new SpriteMappingPiece(0x10, 0, 3, 4, 0x1E, false, false, 0, false),
                romFrames.get(7).pieces().get(1));
    }

    private static int maxTileIndex(List<SpriteMappingFrame> frames) {
        int max = 0;
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                int lastTile = piece.tileIndex() + (piece.widthTiles() * piece.heightTiles()) - 1;
                if (lastTile > max) {
                    max = lastTile;
                }
            }
        }
        return max;
    }
}
