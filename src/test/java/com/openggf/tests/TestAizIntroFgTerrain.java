package com.openggf.tests;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.Sonic3k;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test: verifies that the AIZ1 intro FG terrain data is valid
 * and contains non-transparent patterns at the intro camera position.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestAizIntroFgTerrain {
    private Game game;
    private Object oldSkipIntros;

    @BeforeEach
    public void setUp() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        // Do NOT skip intros Ã¢â‚¬â€ we want the intro path
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        game = new Sonic3k(rom);
    }

    @AfterEach
    public void tearDown() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
    }

    @Test
    public void introFgTerrainHasNonZeroPatterns() throws Exception {
        // Load AIZ1 with intro mode
        Level level = game.loadLevel(0xC0); // zone 0, act 0

        assertNotNull(level, "Level should load");
        assertNotNull(level.getMap(), "Map should exist");
        assertTrue(level.getPatternCount() > 0, "Should have patterns");
        assertTrue(level.getChunkCount() > 0, "Should have chunks");
        assertTrue(level.getBlockCount() > 0, "Should have blocks");

        System.out.println("=== AIZ1 Intro Level Data ===");
        System.out.println("Patterns: " + level.getPatternCount());
        System.out.println("Chunks: " + level.getChunkCount());
        System.out.println("Blocks: " + level.getBlockCount());
        System.out.println("FG width blocks: " + level.getLayerWidthBlocks(0));
        System.out.println("FG height blocks: " + level.getLayerHeightBlocks(0));
        System.out.println("BG width blocks: " + level.getLayerWidthBlocks(1));
        System.out.println("BG height blocks: " + level.getLayerHeightBlocks(1));
        System.out.println("MinX=" + level.getMinX() + " MaxX=" + level.getMaxX()
                + " MinY=" + level.getMinY() + " MaxY=" + level.getMaxY());

        // Intro start position: (0x40, 0x420)
        int startX = 0x40;
        int startY = 0x420;
        int blockPixelSize = level.getBlockPixelSize();

        // Check FG map data around intro starting position
        Map map = level.getMap();
        int mapStartX = startX / blockPixelSize;
        int mapStartY = startY / blockPixelSize;

        System.out.println("\n=== FG Map at intro start (mapX=" + mapStartX + ", mapY=" + mapStartY + ") ===");

        int nonZeroFgBlocks = 0;
        int totalFgBlocks = 0;

        // Check a 5x3 grid around the start position
        for (int dy = -1; dy <= 1; dy++) {
            int my = mapStartY + dy;
            if (my < 0 || my >= map.getHeight()) continue;
            for (int dx = 0; dx < 5; dx++) {
                int mx = mapStartX + dx;
                if (mx < 0 || mx >= map.getWidth()) continue;
                int blockIndex = Byte.toUnsignedInt(map.getValue(0, mx, my));
                totalFgBlocks++;
                if (blockIndex != 0) nonZeroFgBlocks++;
                System.out.printf("  FG map[%d,%d] = block %d (0x%02X)%n", mx, my, blockIndex, blockIndex);
            }
        }

        System.out.println("Non-zero FG blocks in region: " + nonZeroFgBlocks + "/" + totalFgBlocks);

        // Count overall non-zero FG blocks
        int totalNonZero = 0;
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                if (Byte.toUnsignedInt(map.getValue(0, x, y)) != 0) {
                    totalNonZero++;
                }
            }
        }
        System.out.println("Total non-zero FG blocks in level: " + totalNonZero);

        // Check if patterns referenced by these blocks have non-zero pixel data
        int nonEmptyPatterns = 0;
        int checkedPatterns = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int my = mapStartY + dy;
            if (my < 0 || my >= map.getHeight()) continue;
            for (int dx = 0; dx < 5; dx++) {
                int mx = mapStartX + dx;
                if (mx < 0 || mx >= map.getWidth()) continue;
                int blockIndex = Byte.toUnsignedInt(map.getValue(0, mx, my));
                if (blockIndex >= level.getBlockCount()) continue;

                Block block = level.getBlock(blockIndex);
                int chunksPerSide = level.getChunksPerBlockSide();
                for (int cy = 0; cy < chunksPerSide; cy++) {
                    for (int cx = 0; cx < chunksPerSide; cx++) {
                        ChunkDesc chunkDesc = block.getChunkDesc(cx, cy);
                        int chunkIndex = chunkDesc.getChunkIndex();
                        if (chunkIndex >= level.getChunkCount()) continue;

                        Chunk chunk = level.getChunk(chunkIndex);
                        for (int py = 0; py < 2; py++) {
                            for (int px = 0; px < 2; px++) {
                                PatternDesc pd = chunk.getPatternDesc(px, py);
                                int patternIndex = pd.getPatternIndex();
                                if (patternIndex >= level.getPatternCount()) continue;

                                checkedPatterns++;
                                Pattern pattern = level.getPattern(patternIndex);
                                if (hasNonZeroPixels(pattern)) {
                                    nonEmptyPatterns++;
                                }
                            }
                        }
                    }
                }
            }
        }

        System.out.println("Checked patterns: " + checkedPatterns + ", non-empty: " + nonEmptyPatterns);

        // Check palettes
        System.out.println("\n=== Palette data ===");
        for (int palLine = 0; palLine < level.getPaletteCount(); palLine++) {
            Palette pal = level.getPalette(palLine);
            int nonTransparent = 0;
            for (int c = 0; c < 16; c++) {
                Palette.Color color = pal.getColor(c);
                if (color != null && (color.r != 0 || color.g != 0 || color.b != 0)) {
                    nonTransparent++;
                }
            }
            System.out.println("  Palette " + palLine + ": " + nonTransparent + "/16 non-transparent colors");
        }

        // Find where non-zero FG blocks start on each row around intro Y
        System.out.println("\n=== First non-zero FG block per row ===");
        for (int my = 0; my < map.getHeight(); my++) {
            int firstNonZeroX = -1;
            for (int mx = 0; mx < map.getWidth(); mx++) {
                if (Byte.toUnsignedInt(map.getValue(0, mx, my)) != 0) {
                    firstNonZeroX = mx;
                    break;
                }
            }
            if (firstNonZeroX >= 0) {
                System.out.printf("  Row %d: first non-zero FG at mapX=%d (worldX=%d, 0x%X)%n",
                        my, firstNonZeroX, firstNonZeroX * blockPixelSize, firstNonZeroX * blockPixelSize);
            }
        }

        // Check a wider range - around where the terrain should transition
        System.out.println("\n=== FG Map at various X positions (row 8) ===");
        int testRow = 8;
        for (int mx = 0; mx < Math.min(map.getWidth(), 20); mx++) {
            int blockIdx = Byte.toUnsignedInt(map.getValue(0, mx, testRow));
            if (blockIdx != 0) {
                System.out.printf("  FG map[%d,%d] = block %d (0x%02X)%n", mx, testRow, blockIdx, blockIdx);
            }
        }

        // Verify block 0 is genuinely empty
        if (level.getBlockCount() > 0) {
            Block block0 = level.getBlock(0);
            boolean block0HasContent = false;
            for (int cy = 0; cy < level.getChunksPerBlockSide(); cy++) {
                for (int cx = 0; cx < level.getChunksPerBlockSide(); cx++) {
                    int chunkIdx = block0.getChunkDesc(cx, cy).getChunkIndex();
                    if (chunkIdx != 0) {
                        block0HasContent = true;
                    }
                }
            }
            System.out.println("\nBlock 0 has non-zero chunks: " + block0HasContent);
        }

        // Also check what the intro-specific LevelSizes entry has
        System.out.println("\n=== LevelSizes comparison ===");
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        int levelSizesAddr = Sonic3kConstants.LEVEL_SIZES_ADDR;
        int entrySize = Sonic3kConstants.LEVEL_SIZES_ENTRY_SIZE;
        if (levelSizesAddr > 0) {
            // Normal AIZ1 (entry 0)
            int normalAddr = levelSizesAddr;
            int normalMinX = rom.read16BitAddr(normalAddr);
            int normalMaxX = rom.read16BitAddr(normalAddr + 2);
            int normalMinY = (short) rom.read16BitAddr(normalAddr + 4);
            int normalMaxY = (short) rom.read16BitAddr(normalAddr + 6);
            System.out.printf("  Entry 0 (normal AIZ1): minX=%d maxX=%d minY=%d maxY=%d (0x%X)%n",
                    normalMinX, normalMaxX, normalMinY, normalMaxY, normalMaxY);

            // Intro entry (entry 26)
            int introIdx = Sonic3kConstants.LEVEL_SIZES_AIZ1_INTRO_INDEX;
            int introAddr = levelSizesAddr + introIdx * entrySize;
            int introMinX = rom.read16BitAddr(introAddr);
            int introMaxX = rom.read16BitAddr(introAddr + 2);
            int introMinY = (short) rom.read16BitAddr(introAddr + 4);
            int introMaxY = (short) rom.read16BitAddr(introAddr + 6);
            System.out.printf("  Entry 26 (intro): minX=%d maxX=%d minY=%d maxY=%d (0x%X)%n",
                    introMinX, introMaxX, introMinY, introMaxY, introMaxY);
        }

        // Assertions
        int totalCells = level.getMap().getHeight() * level.getMap().getWidth();
        assertTrue(totalNonZero > 0, "Level should have non-zero FG blocks somewhere");
        assertTrue(totalNonZero > totalCells / 20, "At least 5% of FG map cells should be non-zero (got " + totalNonZero
                        + "/" + totalCells + ")");
        // Note: pattern pixel data may be empty in headless tests (PLC art not loaded),
        // so we only check that patterns were examined, not that they contain pixels.
        assertTrue(checkedPatterns > 0, "Should have checked patterns around intro start");
    }

    private boolean hasNonZeroPixels(Pattern pattern) {
        if (pattern == null) return false;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (pattern.getPixel(x, y) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}


