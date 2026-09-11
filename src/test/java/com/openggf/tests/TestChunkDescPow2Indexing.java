package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.level.Block;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Level;
import com.openggf.level.LevelConstants;
import com.openggf.level.LevelManager;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.Chunk;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Equivalence test for the power-of-two shift/mask fast path in
 * {@link LevelManager#getChunkDescAt}. Compares the production lookup against
 * an oracle that replicates the original double-modulo / division semantics
 * verbatim, across in-range, out-of-range, negative, and wrap-boundary
 * coordinates, for power-of-two and non-power-of-two level dimensions and
 * block pixel sizes (128 = S2/S3K, 256 = S1, 96 = synthetic fallback).
 */
public class TestChunkDescPow2Indexing {

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
    }

    // ------------------------------------------------------------------
    // Fixture level: every map cell points at its own Block, and every
    // block grid cell holds a unique ChunkDesc instance, so identity
    // comparison pins down exactly which (mapX, mapY, chunkX, chunkY)
    // the lookup resolved.
    // ------------------------------------------------------------------
    private static class GridLevel implements Level {
        final int widthBlocks;
        final int heightBlocks;
        final int blockPixel;
        private final Map map;
        private final Block[] blocks;

        GridLevel(int widthBlocks, int heightBlocks, int blockPixel) {
            this.widthBlocks = widthBlocks;
            this.heightBlocks = heightBlocks;
            this.blockPixel = blockPixel;
            this.map = new Map(2, widthBlocks, heightBlocks);
            this.blocks = new Block[widthBlocks * heightBlocks + 1];
            int gridSide = Math.max(8, blockPixel / LevelConstants.CHUNK_WIDTH);
            for (int by = 0; by < heightBlocks; by++) {
                for (int bx = 0; bx < widthBlocks; bx++) {
                    int index = 1 + by * widthBlocks + bx;
                    Block block = new Block(gridSide);
                    for (int cy = 0; cy < gridSide; cy++) {
                        for (int cx = 0; cx < gridSide; cx++) {
                            block.setChunkDesc(cx, cy, new ChunkDesc(0));
                        }
                    }
                    blocks[index] = block;
                    map.setValue(0, bx, by, (byte) index);
                    map.setValue(1, bx, by, (byte) index);
                }
            }
        }

        @Override public int getBlockPixelSize() { return blockPixel; }
        @Override public int getChunksPerBlockSide() { return blockPixel / LevelConstants.CHUNK_WIDTH; }
        @Override public int getPaletteCount() { return 0; }
        @Override public Palette getPalette(int index) { return null; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { return null; }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { return null; }
        @Override public int getBlockCount() { return blocks.length; }
        @Override public Block getBlock(int index) { return index >= 0 && index < blocks.length ? blocks[index] : null; }
        @Override public SolidTile getSolidTile(int index) { return null; }
        @Override public Map getMap() { return map; }
        @Override public List<ObjectSpawn> getObjects() { return java.util.Collections.emptyList(); }
        @Override public List<RingSpawn> getRings() { return java.util.Collections.emptyList(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 0; }
    }

    /** GridLevel with an S1-style loop remap: block 1 resolves to block 2 for low-plane collision. */
    private static final class LoopGridLevel extends GridLevel {
        LoopGridLevel(int widthBlocks, int heightBlocks, int blockPixel) {
            super(widthBlocks, heightBlocks, blockPixel);
        }

        @Override
        public int resolveCollisionBlockIndex(int blockIndex, int mapX, int mapY) {
            return blockIndex == 1 ? 2 : blockIndex;
        }
    }

    // ------------------------------------------------------------------
    // Oracle: verbatim copy of the pre-optimization arithmetic
    // (double-modulo X wrap, div/mod block math).
    // ------------------------------------------------------------------
    private static ChunkDesc oracle(GridLevel level, byte layer, int x, int y, boolean verticalWrap,
                                    boolean loopLowPlane) {
        int levelWidth = level.widthBlocks * level.blockPixel;
        int levelHeight = level.heightBlocks * level.blockPixel;
        int wrappedX = ((x % levelWidth) + levelWidth) % levelWidth;
        int wrappedY = y;
        if (layer == 1 || verticalWrap) {
            wrappedY = ((wrappedY % levelHeight) + levelHeight) % levelHeight;
        } else if (wrappedY < 0 || wrappedY >= levelHeight) {
            return null;
        }
        int mapX = wrappedX / level.blockPixel;
        int mapY = wrappedY / level.blockPixel;
        int blockIndex = level.getMap().getValue(layer, mapX, mapY) & 0xFF;
        if (loopLowPlane) {
            blockIndex = level.resolveCollisionBlockIndex(blockIndex, mapX, mapY);
        }
        if (blockIndex >= level.getBlockCount()) {
            return null;
        }
        Block block = level.getBlock(blockIndex);
        if (block == null) {
            return null;
        }
        return block.getChunkDesc((wrappedX % level.blockPixel) / LevelConstants.CHUNK_WIDTH,
                (wrappedY % level.blockPixel) / LevelConstants.CHUNK_HEIGHT);
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------
    private LevelManager inject(GridLevel level) throws Exception {
        LevelManager levelManager = GameServices.level();
        Field levelField = LevelManager.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(levelManager, level);
        Field sizeField = LevelManager.class.getDeclaredField("blockPixelSize");
        sizeField.setAccessible(true);
        sizeField.setInt(levelManager, level.getBlockPixelSize());
        Method cache = LevelManager.class.getDeclaredMethod("cacheLevelDimensions");
        cache.setAccessible(true);
        cache.invoke(levelManager);
        return levelManager;
    }

    private static void setVerticalWrap(LevelManager levelManager, boolean value) throws Exception {
        Field field = LevelManager.class.getDeclaredField("verticalWrapEnabled");
        field.setAccessible(true);
        field.setBoolean(levelManager, value);
    }

    /** Negative, in-range, wrap-boundary and far out-of-range sample coordinates for a dimension. */
    private static int[] interestingCoords(int sizePx) {
        List<Integer> coords = new ArrayList<>();
        int[] fixed = {
                Integer.MIN_VALUE, Integer.MIN_VALUE + 1,
                -2 * sizePx - 1, -sizePx - 1, -sizePx, -sizePx + 1,
                -129, -128, -127, -17, -16, -15, -1,
                0, 1, 15, 16, 17, 127, 128, 129,
                sizePx - 1, sizePx, sizePx + 1, 2 * sizePx - 3, 3 * sizePx + 5
        };
        for (int c : fixed) {
            coords.add(c);
        }
        for (int c = -2 * sizePx; c <= 2 * sizePx; c += 53) {
            coords.add(c);
        }
        return coords.stream().mapToInt(Integer::intValue).toArray();
    }

    private void assertEquivalent(GridLevel level, boolean verticalWrap) throws Exception {
        LevelManager levelManager = inject(level);
        setVerticalWrap(levelManager, verticalWrap);
        int widthPx = level.widthBlocks * level.blockPixel;
        int heightPx = level.heightBlocks * level.blockPixel;
        int checked = 0;
        for (byte layer = 0; layer <= 1; layer++) {
            for (int x : interestingCoords(widthPx)) {
                for (int y : interestingCoords(heightPx)) {
                    ChunkDesc expected = oracle(level, layer, x, y, verticalWrap, false);
                    ChunkDesc actual = levelManager.getChunkDescAt(layer, x, y);
                    assertSame(expected, actual,
                            "layer=" + layer + " x=" + x + " y=" + y + " vWrap=" + verticalWrap
                                    + " level=" + level.widthBlocks + "x" + level.heightBlocks
                                    + " blockPx=" + level.blockPixel);
                    checked++;
                }
            }
        }
        assertTrue(checked > 1000, "coordinate grid should be substantial, was " + checked);
    }

    // ------------------------------------------------------------------
    // Cases
    // ------------------------------------------------------------------

    @Test
    public void pow2LevelMatchesDivModOracle() throws Exception {
        // 4x2 blocks of 128px -> 512x256px: width, height and block size all powers of two.
        assertEquivalent(new GridLevel(4, 2, 128), false);
    }

    @Test
    public void pow2LevelMatchesDivModOracleWithVerticalWrap() throws Exception {
        // ROM: LZ3/SBZ2-style vertical foreground wrap.
        assertEquivalent(new GridLevel(4, 2, 128), true);
    }

    @Test
    public void s1StyleBlock256MatchesDivModOracle() throws Exception {
        // 2x2 blocks of 256px -> 512x512px, S1 block size (shift 8, 16x16 chunk grid).
        assertEquivalent(new GridLevel(2, 2, 256), false);
    }

    @Test
    public void nonPow2LevelWidthFallsBackAndMatchesOracle() throws Exception {
        // 3x3 blocks of 128px -> 384x384px: block math is shift/mask but the
        // X/Y wrap must take the double-modulo fallback.
        assertEquivalent(new GridLevel(3, 3, 128), false);
        assertEquivalent(new GridLevel(3, 3, 128), true);
    }

    @Test
    public void nonPow2BlockPixelSizeFallsBackAndMatchesOracle() throws Exception {
        // Synthetic 96px blocks: blockPixelShift must stay -1 and the whole
        // lookup must take the historical div/mod path.
        assertEquivalent(new GridLevel(3, 2, 96), false);
    }

    @Test
    public void loopLowPlaneOverloadMatchesDivModOracle() throws Exception {
        // S1 loop collision remap (block 1 -> block 2) through the 4-arg overload.
        LoopGridLevel level = new LoopGridLevel(4, 2, 128);
        LevelManager levelManager = inject(level);
        setVerticalWrap(levelManager, false);
        int widthPx = level.widthBlocks * level.blockPixel;
        int heightPx = level.heightBlocks * level.blockPixel;
        for (int x : interestingCoords(widthPx)) {
            for (int y : interestingCoords(heightPx)) {
                ChunkDesc expected = oracle(level, (byte) 0, x, y, false, true);
                ChunkDesc actual = levelManager.getChunkDescAt((byte) 0, x, y, true);
                assertSame(expected, actual, "loopLowPlane x=" + x + " y=" + y);
            }
        }
    }
}
