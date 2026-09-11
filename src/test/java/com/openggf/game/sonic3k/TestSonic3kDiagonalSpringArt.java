package com.openggf.game.sonic3k;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression test for the MGZ/MHZ diagonal spring art override.
 *
 * <p>The ROM loads {@code ArtNem_DiagonalSpring} to VRAM tile
 * {@code ArtTile_MGZMHZDiagonalSpring ($0478)} in MGZ/MHZ but
 * {@code ArtTile_DiagonalSpring ($043A)} everywhere else. The engine's
 * registry encodes that override in {@link Sonic3kPlcArtRegistry},
 * but the builder used to ignore the override and always read from
 * {@code ARTTILE_DIAGONAL_SPRING}, producing corrupted springs in MGZ.
 *
 * <p>These tests verify the builder now honours the passed base tile.
 */
public class TestSonic3kDiagonalSpringArt {

    @Test
    public void diagonalSpringBuilderSourcesPatternsFromPassedTileBase() {
        // Unique marker patterns at both the standard and MGZ/MHZ bases; the builder
        // should pull from whichever base it is passed.
        Pattern[] patterns = new Pattern[Sonic3kConstants.ARTTILE_MGZ_MHZ_DIAGONAL_SPRING + 0x1C];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = new Pattern();
        }
        StubLevel level = new StubLevel(patterns);
        Sonic3kObjectArt art = new Sonic3kObjectArt(level);

        ObjectSpriteSheet defaultSheet = art.buildSpringDiagonalSheet(
                Sonic3kConstants.ARTTILE_DIAGONAL_SPRING);
        assertNotNull(defaultSheet);
        assertEquals(Sonic3kConstants.ARTTILE_DIAGONAL_SPRING, art.getLastBuildStartTile());
        assertSame(patterns[Sonic3kConstants.ARTTILE_DIAGONAL_SPRING],
                defaultSheet.getPatterns()[0],
                "default base should source from $043A");

        ObjectSpriteSheet mgzSheet = art.buildSpringDiagonalSheet(
                Sonic3kConstants.ARTTILE_MGZ_MHZ_DIAGONAL_SPRING);
        assertNotNull(mgzSheet);
        assertEquals(Sonic3kConstants.ARTTILE_MGZ_MHZ_DIAGONAL_SPRING, art.getLastBuildStartTile());
        assertSame(patterns[Sonic3kConstants.ARTTILE_MGZ_MHZ_DIAGONAL_SPRING],
                mgzSheet.getPatterns()[0],
                "MGZ/MHZ override must source from $0478, not $043A");

        assertNotSame(defaultSheet.getPatterns()[0], mgzSheet.getPatterns()[0],
                "the two bases must resolve to distinct level patterns");
    }

    @Test
    public void diagonalSpringYellowBuilderHonoursPassedTileBase() {
        Pattern[] patterns = new Pattern[Sonic3kConstants.ARTTILE_MGZ_MHZ_DIAGONAL_SPRING + 0x1C];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = new Pattern();
        }
        StubLevel level = new StubLevel(patterns);
        Sonic3kObjectArt art = new Sonic3kObjectArt(level);

        ObjectSpriteSheet mgzSheet = art.buildSpringDiagonalYellowSheet(
                Sonic3kConstants.ARTTILE_MGZ_MHZ_DIAGONAL_SPRING);
        assertNotNull(mgzSheet);
        assertEquals(Sonic3kConstants.ARTTILE_MGZ_MHZ_DIAGONAL_SPRING, art.getLastBuildStartTile());
        assertSame(patterns[Sonic3kConstants.ARTTILE_MGZ_MHZ_DIAGONAL_SPRING],
                mgzSheet.getPatterns()[0]);
    }

    private static final class StubLevel implements Level {
        private final Pattern[] patterns;

        StubLevel(Pattern[] patterns) {
            this.patterns = patterns;
        }

        @Override public int getPaletteCount() { return 0; }
        @Override public Palette getPalette(int index) { throw new UnsupportedOperationException(); }
        @Override public int getPatternCount() { return patterns.length; }
        @Override public Pattern getPattern(int index) { return patterns[index]; }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { throw new UnsupportedOperationException(); }
        @Override public int getBlockCount() { return 0; }
        @Override public Block getBlock(int index) { throw new UnsupportedOperationException(); }
        @Override public SolidTile getSolidTile(int index) { throw new UnsupportedOperationException(); }
        @Override public Map getMap() { return null; }
        @Override public List<ObjectSpawn> getObjects() { return List.of(); }
        @Override public List<RingSpawn> getRings() { return List.of(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 0; }
    }
}
