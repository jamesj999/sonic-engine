package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.*;
import com.openggf.game.sonic2.scroll.SwScrlHtz;
import com.openggf.game.sonic2.scroll.BackgroundCamera;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.openggf.level.LevelTilemapManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test: dumps the HTZ BG tilemap data at earthquake Y positions
 * to verify whether the CPU-side tilemap contains valid (non-empty) tile
 * descriptors for the lava/cave BG art.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestHtzBgTilemapDiagnostic {
    private static final int HTZ_ZONE = 4;
    private static final int HTZ_ACT = 0;

    private LevelManager levelManager;
    private LevelTilemapManager tilemapManager;

    /**
     * Triggers the BG tilemap build via LevelManager's private ensureBackgroundTilemapData(),
     * then returns the built data from the public getters on LevelTilemapManager.
     */
    private byte[] forceBuildBgTilemap() throws Exception {
        // Force dirty so the build actually runs
        tilemapManager.setBackgroundTilemapDirty(true);
        tilemapManager.setPatternLookupDirty(true);
        Method ensureBg = LevelManager.class.getDeclaredMethod("ensureBackgroundTilemapData");
        ensureBg.setAccessible(true);
        ensureBg.invoke(levelManager);
        return tilemapManager.getBackgroundTilemapData();
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestEnvironment.resetAll();
        GraphicsManager.getInstance().initHeadless();

        SonicConfigurationService configService = SonicConfigurationService.getInstance();
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        Sonic sprite = new Sonic(mainCode, (short) 0x1800, (short) 0x450);
        GameServices.sprites().addSprite(sprite);

        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sprite);
        camera.setFrozen(false);

        levelManager = GameServices.level();
        levelManager.loadZoneAndAct(HTZ_ZONE, HTZ_ACT);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);
        tilemapManager = levelManager.getTilemapManager();
        assertNotNull(tilemapManager, "TilemapManager must be initialized after level load");
    }

    @Test
    public void vdpWrapHeightDetection() throws Exception {
        // Force the tilemap build that normally happens during rendering
        byte[] data = forceBuildBgTilemap();
        int wrapValue = tilemapManager.getBackgroundVdpWrapHeightTiles();
        int widthTiles = tilemapManager.getBackgroundTilemapWidthTiles();
        int heightTiles = tilemapManager.getBackgroundTilemapHeightTiles();

        System.out.println("=== VDP Wrap Height Detection Diagnostic ===");
        System.out.println("Tilemap: " + widthTiles + "x" + heightTiles + " tiles");

        // Scan rows to show pattern distribution
        int lastRealArtRow = -1;
        for (int y = heightTiles - 1; y >= 0; y--) {
            int maxPat = 0;
            for (int x = 0; x < widthTiles; x++) {
                int offset = (y * widthTiles + x) * 4;
                int r = data[offset] & 0xFF;
                int g = data[offset + 1] & 0xFF;
                int pat = r + ((g & 0x07) << 8);
                if (pat > maxPat) maxPat = pat;
            }
            if (maxPat >= 2 && lastRealArtRow < 0) {
                lastRealArtRow = y;
            }
            if (y <= 35 || y >= 253 || y == lastRealArtRow) {
                System.out.printf("  row %3d: maxPat=%d%n", y, maxPat);
            }
        }

        int actualHeight = (lastRealArtRow >= 0) ? lastRealArtRow + 1 : 0;
        System.out.println("Last row with real art (pat>=2): " + lastRealArtRow);
        System.out.println("Actual data height: " + actualHeight + " tiles");
        System.out.println("backgroundVdpWrapHeightTiles = " + wrapValue);

        assertTrue(wrapValue == 32, "VDP wrap height should be 32 for HTZ");
    }

    @Test
    public void bgMapLayerHasValidDataAtEarthquakePositions() {
        Level level = levelManager.getCurrentLevel();
        assertNotNull(level, "Level must be loaded");
        Map map = level.getMap();
        assertNotNull(map, "Map must be loaded");

        System.out.println("=== HTZ BG Map Layer Diagnostic ===");
        System.out.println("Map dimensions: " + map.getWidth() + "x" + map.getHeight() + " blocks");
        System.out.println("Block count: " + level.getBlockCount());
        System.out.println("Chunk count: " + level.getChunkCount());
        System.out.println("Pattern count: " + level.getPatternCount());
        System.out.println();

        // Check BG layer (layer 1) at various Y positions
        // Earthquake scroll Y â‰ˆ 784 â†’ block row 6 (784/128=6.125)
        int[] testBlockRows = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        int nonEmptyRowCount = 0;

        for (int blockRow : testBlockRows) {
            int nonEmptyBlocks = 0;
            int validChunks = 0;
            int nonZeroPatterns = 0;

            for (int blockCol = 0; blockCol < Math.min(4, map.getWidth()); blockCol++) {
                byte blockValue = map.getValue(1, blockCol, blockRow); // layer 1 = BG
                int blockIndex = blockValue & 0xFF;

                if (blockIndex > 0 && blockIndex < level.getBlockCount()) {
                    nonEmptyBlocks++;
                    Block block = level.getBlock(blockIndex);
                    if (block != null) {
                        // Sample first chunk in the block
                        ChunkDesc cd = block.getChunkDesc(0, 0);
                        int chunkIdx = cd.getChunkIndex();
                        if (chunkIdx >= 0 && chunkIdx < level.getChunkCount()) {
                            validChunks++;
                            Chunk chunk = level.getChunk(chunkIdx);
                            if (chunk != null) {
                                PatternDesc pd = chunk.getPatternDesc(0, 0);
                                if (pd.getPatternIndex() > 0) {
                                    nonZeroPatterns++;
                                }
                            }
                        }
                    }
                }
            }

            boolean hasData = nonEmptyBlocks > 0;
            if (hasData) nonEmptyRowCount++;
            System.out.printf("  BG row %2d: blocks=%d/%d, validChunks=%d, nonZeroPatterns=%d %s%n",
                    blockRow, nonEmptyBlocks, Math.min(4, map.getWidth()),
                    validChunks, nonZeroPatterns,
                    blockRow == 6 ? " <-- earthquake Y" : "");
        }

        System.out.println();
        System.out.println("Non-empty BG rows: " + nonEmptyRowCount + "/" + testBlockRows.length);
        // S2 BG map data only populates the first 2 block rows (256px), matching the VDP
        // plane height. The engine wraps via the tilemap shader (WrapY=true, TilemapHeight=32).
        assertTrue(nonEmptyRowCount >= 2, "BG map should have data in first 2 rows (VDP plane height)");
    }

    @Test
    public void bgTilemapDataContainsValidTilesAtEarthquakeY() throws Exception {
        // Force build of BG tilemap data
        byte[] data = forceBuildBgTilemap();
        assertNotNull(data, "BG tilemap data must be built");
        int widthTiles = tilemapManager.getBackgroundTilemapWidthTiles();
        int heightTiles = tilemapManager.getBackgroundTilemapHeightTiles();

        System.out.println("=== BG Tilemap Data Diagnostic ===");
        System.out.println("Tilemap dimensions: " + widthTiles + "x" + heightTiles + " tiles");
        System.out.println("Data array size: " + data.length + " bytes (expected "
                + (widthTiles * heightTiles * 4) + ")");
        assertEquals(widthTiles * heightTiles * 4, data.length);

        // Check tiles at various Y positions
        // Earthquake alignedBgY â‰ˆ 784 â†’ tile Y = 784/8 = 98
        int[] testTileYPositions = {0, 10, 20, 30, 50, 80, 98, 100, 110, 120, 130, 200, 255};

        System.out.println();
        System.out.println("Tile data at various Y positions (first 10 tiles each row):");

        for (int tileY : testTileYPositions) {
            if (tileY >= heightTiles) continue;

            int nonEmptyTiles = 0;
            int totalTilesChecked = Math.min(10, widthTiles);
            StringBuilder sb = new StringBuilder();

            for (int tileX = 0; tileX < totalTilesChecked; tileX++) {
                int offset = (tileY * widthTiles + tileX) * 4;
                int r = data[offset] & 0xFF;
                int g = data[offset + 1] & 0xFF;
                int alpha = data[offset + 3] & 0xFF;

                int patternIndex = r + ((g & 0x07) << 8);
                int paletteIndex = (g >> 3) & 0x03;
                boolean priority = (g & 0x80) != 0;

                if (alpha > 0 && patternIndex > 0) {
                    nonEmptyTiles++;
                }

                if (tileX < 4) {
                    sb.append(String.format("[pat=%d,pal=%d,pri=%b,a=%d] ",
                            patternIndex, paletteIndex, priority, alpha));
                }
            }

            String marker = "";
            if (tileY >= 96 && tileY <= 100) marker = " <-- earthquake zone";

            System.out.printf("  tileY=%3d: %d/%d non-empty %s %s%n",
                    tileY, nonEmptyTiles, totalTilesChecked, sb.toString().trim(), marker);

            // At earthquake Y positions, the RAW tilemap is correctly empty -
            // the shader's VDPWrapHeight wraps tileY back into valid range.
            // Verify the WRAPPED position has valid tiles instead.
            if (tileY >= 96 && tileY <= 100) {
                int wrappedY = tileY % 32;
                int wrappedNonEmpty = 0;
                for (int tileX = 0; tileX < Math.min(10, widthTiles); tileX++) {
                    int wOffset = (wrappedY * widthTiles + tileX) * 4;
                    int wR = data[wOffset] & 0xFF;
                    int wG = data[wOffset + 1] & 0xFF;
                    int wAlpha = data[wOffset + 3] & 0xFF;
                    int wPatIdx = wR + ((wG & 0x07) << 8);
                    if (wAlpha > 0 && wPatIdx > 0) {
                        wrappedNonEmpty++;
                    }
                }
                assertTrue(wrappedNonEmpty > 0, "VDP-wrapped tileY=" + wrappedY + " (from earthquake Y=" + tileY
                        + ") should have non-empty tiles");
            }
        }

        // Also check a random sampling of the FULL tilemap for completeness
        int totalNonEmpty = 0;
        for (int tileY = 0; tileY < heightTiles; tileY++) {
            for (int tileX = 0; tileX < widthTiles; tileX++) {
                int offset = (tileY * widthTiles + tileX) * 4;
                int alpha = data[offset + 3] & 0xFF;
                int r = data[offset] & 0xFF;
                int g = data[offset + 1] & 0xFF;
                int patternIndex = r + ((g & 0x07) << 8);
                if (alpha > 0 && patternIndex > 0) {
                    totalNonEmpty++;
                }
            }
        }

        int totalTiles = widthTiles * heightTiles;
        float fillPercent = (100.0f * totalNonEmpty) / totalTiles;
        System.out.printf("%nTotal non-empty tiles: %d/%d (%.1f%%)%n", totalNonEmpty, totalTiles, fillPercent);
        assertTrue(fillPercent > 10.0f, "BG tilemap should have substantial data (>10% fill)");
    }

    @Test
    public void bgTilemapMountainTilePresence() throws Exception {
        // Check whether the BG tilemap contains HTZ dynamic mountain tile indices ($0500-$051F)
        byte[] data = forceBuildBgTilemap();
        int widthTiles = tilemapManager.getBackgroundTilemapWidthTiles();
        int heightTiles = tilemapManager.getBackgroundTilemapHeightTiles();

        Level level = levelManager.getCurrentLevel();
        int patternCount = level.getPatternCount();
        System.out.println("=== HTZ BG Mountain Tile Presence ===");
        System.out.println("Tilemap: " + widthTiles + "x" + heightTiles + " tiles");
        System.out.println("Pattern count: " + patternCount
                + " (mountain range $0500-$0517 = indices 1280-1303)");

        // Scan FULL tilemap for mountain/cloud tile indices
        int mountainTiles = 0;  // patIdx in $0500-$0517
        int cloudTiles = 0;     // patIdx in $0518-$051F
        int maxPatIdx = 0;
        int[] patIdxHistogram = new int[8]; // 0: 0-0xFF, 1: 0x100-0x1FF, etc.

        // Only scan the VDP-visible rows (0-31) since those wrap
        for (int tileY = 0; tileY < Math.min(32, heightTiles); tileY++) {
            for (int tileX = 0; tileX < widthTiles; tileX++) {
                int off = (tileY * widthTiles + tileX) * 4;
                int r = data[off] & 0xFF;
                int g = data[off + 1] & 0xFF;
                int patIdx = r + ((g & 0x07) << 8);

                if (patIdx > maxPatIdx) maxPatIdx = patIdx;
                int bucket = Math.min(patIdx >> 8, patIdxHistogram.length - 1);
                patIdxHistogram[bucket]++;

                if (patIdx >= 0x0500 && patIdx <= 0x0517) mountainTiles++;
                if (patIdx >= 0x0518 && patIdx <= 0x051F) cloudTiles++;
            }
        }

        System.out.println("Max pattern index in VDP rows 0-31: 0x"
                + Integer.toHexString(maxPatIdx) + " (" + maxPatIdx + ")");
        System.out.println("Mountain tiles ($0500-$0517): " + mountainTiles);
        System.out.println("Cloud tiles ($0518-$051F): " + cloudTiles);
        System.out.println("Pattern index distribution (VDP rows 0-31):");
        for (int i = 0; i < patIdxHistogram.length; i++) {
            if (patIdxHistogram[i] > 0) {
                System.out.printf("  $%X00-$%XFF: %d tiles%n", i, i, patIdxHistogram[i]);
            }
        }

        // HTZ's BG tilemap must actually contain the dynamic mountain-range
        // ($0500-$0517) and cloud ($0518-$051F) tile indices, and must not
        // reference patterns past the loaded pattern set.
        assertTrue(mountainTiles > 0,
                "HTZ BG tilemap should contain dynamic mountain tiles ($0500-$0517)");
        assertTrue(cloudTiles > 0,
                "HTZ BG tilemap should contain dynamic cloud tiles ($0518-$051F)");
        assertTrue(maxPatIdx < patternCount,
                "BG VDP rows must not reference a pattern index (" + maxPatIdx
                        + ") at or beyond the loaded pattern count (" + patternCount + ")");

        // Also scan the raw BG block/chunk data to see what chunks reference
        System.out.println("\n--- Direct chunk pattern scan (BG rows 0-1, cols 0-7) ---");
        Map map = level.getMap();
        for (int blockRow = 0; blockRow < 2; blockRow++) {
            for (int blockCol = 0; blockCol < Math.min(8, map.getWidth()); blockCol++) {
                int blockIndex = map.getValue(1, blockCol, blockRow) & 0xFF;
                if (blockIndex == 0 || blockIndex >= level.getBlockCount()) continue;
                Block block = level.getBlock(blockIndex);
                if (block == null) continue;

                // Scan all 8x8 chunks in the block
                int blockChunks = 128 / 16; // 8 chunks per block dimension
                for (int cy = 0; cy < blockChunks; cy++) {
                    for (int cx = 0; cx < blockChunks; cx++) {
                        ChunkDesc cd = block.getChunkDesc(cx, cy);
                        int chunkIdx = cd.getChunkIndex();
                        if (chunkIdx < 0 || chunkIdx >= level.getChunkCount()) continue;
                        Chunk chunk = level.getChunk(chunkIdx);
                        if (chunk == null) continue;

                        for (int py = 0; py < 2; py++) {
                            for (int px = 0; px < 2; px++) {
                                PatternDesc pd = chunk.getPatternDesc(px, py);
                                int pidx = pd.getPatternIndex();
                                if (pidx >= 0x0500 && pidx <= 0x051F) {
                                    System.out.printf("  block(%d,%d) chunk(%d,%d)[%d] pat(%d,%d) â†’ $%04X%n",
                                            blockCol, blockRow, cx, cy, chunkIdx, px, py, pidx);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dump all unique pattern indices found in VDP rows 0-31
        java.util.TreeSet<Integer> uniquePatterns = new java.util.TreeSet<>();
        for (int tileY = 0; tileY < Math.min(32, heightTiles); tileY++) {
            for (int tileX = 0; tileX < widthTiles; tileX++) {
                int off = (tileY * widthTiles + tileX) * 4;
                int r = data[off] & 0xFF;
                int g = data[off + 1] & 0xFF;
                int patIdx = r + ((g & 0x07) << 8);
                if (patIdx > 1) uniquePatterns.add(patIdx);
            }
        }
        System.out.println("\nAll unique pattern indices in VDP rows 0-31 (count=" + uniquePatterns.size() + "):");
        StringBuilder sb = new StringBuilder();
        for (int p : uniquePatterns) {
            sb.append(String.format("$%04X ", p));
        }
        System.out.println("  " + sb.toString().trim());

        // The key assertion: if patternCount >= 0x0520, the BG chunks should reference them
        if (patternCount >= 0x0520) {
            System.out.println("\nPattern array includes mountain range - checking if BG references them...");
            // This is informational - mountain tiles may or may not be in the BG layout
        } else {
            System.out.println("\nPattern count (" + patternCount + ") < 0x0520 - "
                    + "mountain tiles would be OUT OF RANGE if referenced");
        }
    }

    @Test
    public void bgMapColumnSparsity() {
        Level level = levelManager.getCurrentLevel();
        assertNotNull(level, "Level must be loaded");
        Map map = level.getMap();
        assertNotNull(map, "Map must be loaded");

        int mapWidth = map.getWidth();
        int mapHeight = map.getHeight();
        System.out.println("=== HTZ BG Map Column Sparsity ===");
        System.out.println("Map width=" + mapWidth + " height=" + mapHeight + " (in blocks, each 128px)");
        System.out.println();

        int maxCol = Math.min(128, mapWidth);
        int maxRow = Math.min(16, mapHeight);

        // Scan columns 0..maxCol-1, rows 0 and 1
        System.out.println("--- BG layer (1) block indices per column, rows 0-1 ---");
        int firstZeroCol = -1;
        int lastNonZeroCol = -1;

        for (int col = 0; col < maxCol; col++) {
            int val0 = map.getValue(1, col, 0) & 0xFF;
            int val1 = (1 < mapHeight) ? (map.getValue(1, col, 1) & 0xFF) : -1;

            boolean nonZero = (val0 != 0) || (val1 > 0);
            if (nonZero) lastNonZeroCol = col;
            if (!nonZero && firstZeroCol < 0 && col > 0) firstZeroCol = col;

            // Print every column up to 8, then only non-zero and key columns
            if (col < 8 || col == 48 || nonZero || col == maxCol - 1) {
                String marker = "";
                if (col == 48) marker = " <-- bgTilemapBaseX=6144 queries here";
                System.out.printf("  col %3d: row0=0x%02X (%3d)  row1=0x%02X (%3d) %s%s%n",
                        col, val0, val0, val1 & 0xFF, val1 & 0xFF,
                        nonZero ? "[NON-ZERO]" : "[ZERO]",
                        marker);
            }
        }

        System.out.println();
        System.out.println("Last non-zero BG column: " + lastNonZeroCol);
        System.out.println("First all-zero BG column (after col 0): " + firstZeroCol);
        System.out.println("Pixel extent of non-zero BG data: " + ((lastNonZeroCol + 1) * 128) + " px");
        System.out.println();

        // Summary: count non-zero vs zero columns
        int nonZeroCols = 0;
        int zeroCols = 0;
        for (int col = 0; col < maxCol; col++) {
            int val0 = map.getValue(1, col, 0) & 0xFF;
            int val1 = (1 < mapHeight) ? (map.getValue(1, col, 1) & 0xFF) : 0;
            if (val0 != 0 || val1 != 0) nonZeroCols++;
            else zeroCols++;
        }
        System.out.println("Non-zero BG columns: " + nonZeroCols + " / " + maxCol);
        System.out.println("Zero BG columns: " + zeroCols + " / " + maxCol);

        // Also dump ALL rows for columns 0, 1, 2, 3, 48 to see full vertical extent
        System.out.println();
        System.out.println("--- Full row scan for key columns ---");
        int[] keyCols = {0, 1, 2, 3, 48};
        for (int col : keyCols) {
            if (col >= mapWidth) continue;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  col %3d:", col));
            for (int row = 0; row < maxRow && row < mapHeight; row++) {
                int val = map.getValue(1, col, row) & 0xFF;
                sb.append(String.format(" r%d=0x%02X", row, val));
            }
            System.out.println(sb);
        }

        // The real question: does column 48 have any data?
        System.out.println();
        if (48 < mapWidth) {
            int col48row0 = map.getValue(1, 48, 0) & 0xFF;
            int col48row1 = (1 < mapHeight) ? (map.getValue(1, 48, 1) & 0xFF) : 0;
            System.out.println("Column 48 (bgTilemapBaseX=6144): row0=" + col48row0 + " row1=" + col48row1);
            if (col48row0 == 0 && col48row1 == 0) {
                System.out.println("  --> EMPTY: bgTilemapBaseX=6144 reads zero block data!");
            } else {
                System.out.println("  --> Has data.");
            }
        } else {
            System.out.println("Column 48 is beyond map width (" + mapWidth + ")!");
        }
    }

    @Test
    public void dumpBgTilemapAtEarthquakePosition() throws Exception {
        Level level = levelManager.getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        System.out.println("=== HTZ EARTHQUAKE BG DIAGNOSTIC (CURRENT CODE) ===");
        System.out.println("Pattern count: " + level.getPatternCount());
        System.out.println("Camera: X=" + GameServices.camera().getX()
                + " Y=" + GameServices.camera().getY());

        // Build BG tilemap
        byte[] data = forceBuildBgTilemap();
        int widthTiles = tilemapManager.getBackgroundTilemapWidthTiles();
        int heightTiles = tilemapManager.getBackgroundTilemapHeightTiles();

        System.out.println("BG tilemap: " + widthTiles + "x" + heightTiles + " tiles");
        System.out.println("BG tilemap data size: " + data.length + " bytes");

        // Dump VDP rows 0-31 (first 64 tile columns = 512px = VDP plane width)
        System.out.println("\n--- VDP rows 0-31 tile summary (first 64 columns) ---");
        int mountainCount = 0;
        int cloudCount = 0;
        java.util.TreeSet<Integer> uniquePatterns = new java.util.TreeSet<>();

        for (int row = 0; row < Math.min(32, heightTiles); row++) {
            int nonEmpty = 0;
            int mtns = 0;
            int clds = 0;
            int maxPat = 0;
            StringBuilder firstFew = new StringBuilder();

            for (int col = 0; col < Math.min(64, widthTiles); col++) {
                int off = (row * widthTiles + col) * 4;
                int r = data[off] & 0xFF;
                int g = data[off + 1] & 0xFF;
                int alpha = data[off + 3] & 0xFF;
                int patIdx = r + ((g & 0x07) << 8);
                int pal = (g >> 3) & 0x03;

                if (alpha > 0 && patIdx > 0) nonEmpty++;
                if (patIdx > maxPat) maxPat = patIdx;
                if (patIdx >= 0x0500 && patIdx <= 0x0517) { mtns++; mountainCount++; }
                if (patIdx >= 0x0518 && patIdx <= 0x051F) { clds++; cloudCount++; }
                if (patIdx > 1) uniquePatterns.add(patIdx);

                if (col < 6) {
                    firstFew.append(String.format("$%04X/p%d ", patIdx, pal));
                }
            }

            System.out.printf("  row %2d: %2d/%d non-empty, maxPat=$%04X, mtns=%d, clds=%d | %s%n",
                    row, nonEmpty, Math.min(64, widthTiles), maxPat, mtns, clds, firstFew);
        }

        System.out.println("\nTotal mountain tiles (rows 0-31): " + mountainCount);
        System.out.println("Total cloud tiles (rows 0-31): " + cloudCount);

        // Dump mountain pattern pixel data after DynamicHtz update
        System.out.println("\n--- Mountain pattern pixel data ($0500-$0517) before DynamicHtz ---");
        for (int patIdx = 0x0500; patIdx <= 0x0517; patIdx++) {
            if (patIdx >= level.getPatternCount()) {
                System.out.printf("  $%04X: OUT OF RANGE%n", patIdx);
                continue;
            }
            Pattern pattern = level.getPattern(patIdx);
            if (pattern == null) {
                System.out.printf("  $%04X: NULL%n", patIdx);
                continue;
            }
            int nonZeroPixels = 0;
            for (int py = 0; py < 8; py++)
                for (int px = 0; px < 8; px++)
                    if (pattern.getPixel(px, py) != 0) nonZeroPixels++;
            System.out.printf("  $%04X: %d/64 non-zero pixels%n", patIdx, nonZeroPixels);
        }

        // Check VDPWrapHeight
        System.out.println("\nbackgroundVdpWrapHeightTiles: " + tilemapManager.getBackgroundVdpWrapHeightTiles());

        // Check cachedBgWidthPx
        try {
            Field bgWidthField = LevelManager.class.getDeclaredField("cachedBgWidthPx");
            bgWidthField.setAccessible(true);
            int bgWidthPx = bgWidthField.getInt(levelManager);
            System.out.println("cachedBgWidthPx: " + bgWidthPx);
        } catch (NoSuchFieldException e) {
            System.out.println("cachedBgWidthPx: FIELD NOT FOUND");
        }

        // Check bgTilemapBaseX
        System.out.println("bgTilemapBaseX: " + tilemapManager.getBgTilemapBaseX());

        Map map = level.getMap();
        System.out.println("\nMap dimensions: " + map.getWidth() + "x" + map.getHeight() + " blocks");
        int lastNonZeroCol = -1;
        for (int col = 0; col < map.getWidth(); col++) {
            for (int row = 0; row < Math.min(2, map.getHeight()); row++) {
                if ((map.getValue(1, col, row) & 0xFF) != 0) {
                    lastNonZeroCol = col;
                }
            }
        }
        System.out.println("Last non-zero BG column: " + lastNonZeroCol
                + " (" + ((lastNonZeroCol + 1) * 128) + " px)");

        System.out.println("\n=== END DIAGNOSTIC ===");
    }

    @Test
    public void dynamicHtzProducesNonZeroPatterns() throws Exception {
        Level level = levelManager.getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        // Get DynamicHtz and htzHandler from Sonic2ScrollHandlerProvider via reflection
        ParallaxManager pm = GameServices.parallax();
        Field providerField = ParallaxManager.class.getDeclaredField("scrollProvider");
        providerField.setAccessible(true);
        Object scrollProvider = providerField.get(pm);
        assertNotNull(scrollProvider, "scrollProvider should be initialized");

        Field dynamicField = scrollProvider.getClass().getDeclaredField("dynamicHtz");
        dynamicField.setAccessible(true);
        Object dynamicHtz = dynamicField.get(scrollProvider);
        assertNotNull(dynamicHtz, "DynamicHtz should be initialized");

        Field htzHandlerField = scrollProvider.getClass().getDeclaredField("htzHandler");
        htzHandlerField.setAccessible(true);
        Object htzHandler = htzHandlerField.get(scrollProvider);
        assertNotNull(htzHandler, "htzHandler should be initialized");

        // Verify PatchHTZTiles pre-filled patterns with actual cliff art at load time
        int totalBefore = 0;
        for (int patIdx = 0x0500; patIdx <= 0x0517; patIdx++) {
            Pattern p = level.getPattern(patIdx);
            for (int py = 0; py < 8; py++)
                for (int px = 0; px < 8; px++)
                    if (p.getPixel(px, py) != 0) totalBefore++;
        }
        System.out.println("Mountain pattern non-zero pixels AT LOAD (PatchHTZTiles): "
                + totalBefore + "/1536");
        assertTrue(totalBefore > 0, "PatchHTZTiles should pre-fill mountain patterns with cliff art");

        // Call DynamicHtz.update() with earthquake camera position
        java.lang.reflect.Method updateMethod = dynamicHtz.getClass().getDeclaredMethod(
                "update", Level.class, int.class, SwScrlHtz.class);
        updateMethod.setAccessible(true);
        updateMethod.invoke(dynamicHtz, level, 0x1800, htzHandler);

        // Verify patterns are NON-ZERO after update
        int totalAfter = 0;
        StringBuilder sb = new StringBuilder();
        for (int patIdx = 0x0500; patIdx <= 0x0517; patIdx++) {
            Pattern p = level.getPattern(patIdx);
            int count = 0;
            for (int py = 0; py < 8; py++)
                for (int px = 0; px < 8; px++)
                    if (p.getPixel(px, py) != 0) count++;
            totalAfter += count;
            sb.append(String.format("  $%04X: %d/64 non-zero pixels%n", patIdx, count));
        }
        System.out.println("Mountain pattern non-zero pixels AFTER DynamicHtz.update(): "
                + totalAfter + "/1536");
        System.out.print(sb);

        assertTrue(totalAfter > 0, "DynamicHtz.update() should produce non-zero mountain pattern data");

        // Also check cloud patterns
        int cloudBefore = 0;
        for (int patIdx = 0x0518; patIdx <= 0x051F; patIdx++) {
            Pattern p = level.getPattern(patIdx);
            int count = 0;
            for (int py = 0; py < 8; py++)
                for (int px = 0; px < 8; px++)
                    if (p.getPixel(px, py) != 0) count++;
            cloudBefore += count;
        }
        System.out.println("\nCloud pattern non-zero pixels AFTER DynamicHtz.update(): "
                + cloudBefore + "/512");
    }

    @Test
    public void countBgPriorityTilesInChunks() throws Exception {
        Level level = levelManager.getCurrentLevel();
        assertNotNull(level, "Level must be loaded");
        Map map = level.getMap();
        assertNotNull(map, "Map must be loaded");

        System.out.println("=== HTZ BG Priority Tile Diagnostic ===");
        System.out.println("Map dimensions: " + map.getWidth() + "x" + map.getHeight() + " blocks");
        System.out.println("Block count: " + level.getBlockCount());
        System.out.println("Chunk count: " + level.getChunkCount());
        System.out.println("Pattern count: " + level.getPatternCount());
        System.out.println();

        // --- Part 1: Scan BG map blocks/chunks/patterns for priority bit ---
        int totalPriorityTiles = 0;
        int totalNonPriorityTiles = 0;
        java.util.TreeSet<Integer> chunksWithPriority = new java.util.TreeSet<>();
        java.util.ArrayList<String> prioritySamples = new java.util.ArrayList<>();

        int mapWidth = map.getWidth();
        int mapHeight = map.getHeight();
        int blockGridSide = 8; // Sonic 2: 8x8 chunks per block

        for (int blockRow = 0; blockRow < mapHeight; blockRow++) {
            for (int blockCol = 0; blockCol < mapWidth; blockCol++) {
                int blockIndex = map.getValue(1, blockCol, blockRow) & 0xFF; // layer 1 = BG
                if (blockIndex == 0 || blockIndex >= level.getBlockCount()) continue;

                Block block = level.getBlock(blockIndex);
                if (block == null) continue;

                for (int cy = 0; cy < blockGridSide; cy++) {
                    for (int cx = 0; cx < blockGridSide; cx++) {
                        ChunkDesc cd = block.getChunkDesc(cx, cy);
                        int chunkIdx = cd.getChunkIndex();
                        if (chunkIdx < 0 || chunkIdx >= level.getChunkCount()) continue;

                        Chunk chunk = level.getChunk(chunkIdx);
                        if (chunk == null) continue;

                        for (int py = 0; py < 2; py++) {
                            for (int px = 0; px < 2; px++) {
                                PatternDesc pd = chunk.getPatternDesc(px, py);
                                if (pd.getPriority()) {
                                    totalPriorityTiles++;
                                    chunksWithPriority.add(chunkIdx);
                                    if (prioritySamples.size() < 20) {
                                        prioritySamples.add(String.format(
                                                "block(%d,%d)[blkIdx=%d] chunk(%d,%d)[chkIdx=%d] pat(%d,%d): "
                                                        + "patIdx=$%04X, pal=%d, hFlip=%b, vFlip=%b, priority=TRUE, rawDesc=$%04X",
                                                blockCol, blockRow, blockIndex, cx, cy, chunkIdx, px, py,
                                                pd.getPatternIndex(), pd.getPaletteIndex(),
                                                pd.getHFlip(), pd.getVFlip(), pd.get()));
                                    }
                                } else {
                                    totalNonPriorityTiles++;
                                }
                            }
                        }
                    }
                }
            }
        }

        System.out.println("--- Part 1: BG chunk/pattern priority scan ---");
        System.out.println("Total BG tiles with priority bit set: " + totalPriorityTiles);
        System.out.println("Total BG tiles without priority bit:  " + totalNonPriorityTiles);
        System.out.println("Chunk indices with priority tiles (" + chunksWithPriority.size() + "): "
                + chunksWithPriority);
        System.out.println();
        if (!prioritySamples.isEmpty()) {
            System.out.println("Sample priority tiles (up to 20):");
            for (String s : prioritySamples) {
                System.out.println("  " + s);
            }
        } else {
            System.out.println("NO priority tiles found in BG map chunks.");
        }
        System.out.println();

        // --- Part 2: Also scan ALL unique chunks used by BG map (not just priority ones) ---
        // Show which distinct chunk indices are used and their priority status
        java.util.TreeSet<Integer> allBgChunks = new java.util.TreeSet<>();
        for (int blockRow = 0; blockRow < mapHeight; blockRow++) {
            for (int blockCol = 0; blockCol < mapWidth; blockCol++) {
                int blockIndex = map.getValue(1, blockCol, blockRow) & 0xFF;
                if (blockIndex == 0 || blockIndex >= level.getBlockCount()) continue;
                Block block = level.getBlock(blockIndex);
                if (block == null) continue;
                for (int cy = 0; cy < blockGridSide; cy++) {
                    for (int cx = 0; cx < blockGridSide; cx++) {
                        ChunkDesc cd = block.getChunkDesc(cx, cy);
                        int chunkIdx = cd.getChunkIndex();
                        if (chunkIdx >= 0 && chunkIdx < level.getChunkCount()) {
                            allBgChunks.add(chunkIdx);
                        }
                    }
                }
            }
        }
        System.out.println("Total distinct chunk indices used in BG map: " + allBgChunks.size());
        System.out.println("All BG chunk indices: " + allBgChunks);
        System.out.println();

        // --- Part 3: Check the built BG tilemap data for priority bit in byte[1] ---
        System.out.println("--- Part 3: Built tilemap data priority scan ---");
        byte[] data = forceBuildBgTilemap();
        int widthTiles = tilemapManager.getBackgroundTilemapWidthTiles();
        int heightTiles = tilemapManager.getBackgroundTilemapHeightTiles();

        int tilemapPriorityCount = 0;
        int tilemapNonPriorityCount = 0;
        int tilemapPriorityCountVdpRows = 0; // Only VDP rows 0-31
        java.util.ArrayList<String> tilemapPrioritySamples = new java.util.ArrayList<>();

        for (int tileY = 0; tileY < heightTiles; tileY++) {
            for (int tileX = 0; tileX < widthTiles; tileX++) {
                int offset = (tileY * widthTiles + tileX) * 4;
                int g = data[offset + 1] & 0xFF;
                int alpha = data[offset + 3] & 0xFF;
                boolean priority = (g & 0x80) != 0;
                int r = data[offset] & 0xFF;
                int patIdx = r + ((g & 0x07) << 8);
                int pal = (g >> 3) & 0x03;

                if (alpha > 0 && patIdx > 0) {
                    if (priority) {
                        tilemapPriorityCount++;
                        if (tileY < 32) tilemapPriorityCountVdpRows++;
                        if (tilemapPrioritySamples.size() < 20) {
                            tilemapPrioritySamples.add(String.format(
                                    "tile(%d,%d): patIdx=$%04X, pal=%d, g=0x%02X",
                                    tileX, tileY, patIdx, pal, g));
                        }
                    } else {
                        tilemapNonPriorityCount++;
                    }
                }
            }
        }

        System.out.println("Tilemap dimensions: " + widthTiles + "x" + heightTiles);
        System.out.println("Tilemap tiles with priority bit (0x80 in byte[1]):    " + tilemapPriorityCount);
        System.out.println("Tilemap tiles with priority bit in VDP rows 0-31:     " + tilemapPriorityCountVdpRows);
        System.out.println("Tilemap tiles without priority bit (non-empty):       " + tilemapNonPriorityCount);
        System.out.println();
        if (!tilemapPrioritySamples.isEmpty()) {
            System.out.println("Sample priority tiles in tilemap (up to 20):");
            for (String s : tilemapPrioritySamples) {
                System.out.println("  " + s);
            }
        } else {
            System.out.println("NO priority tiles found in built tilemap data.");
        }

        // --- Part 4: Also check the already-built backgroundTilemapData field ---
        System.out.println();
        System.out.println("--- Part 4: backgroundTilemapData field (from ensureBackgroundTilemapData) ---");
        {
            // Force build
            byte[] bgData = forceBuildBgTilemap();
            int bgWidth = tilemapManager.getBackgroundTilemapWidthTiles();
            int bgHeight = tilemapManager.getBackgroundTilemapHeightTiles();

            int bgPriCount = 0;
            for (int i = 0; i < bgData.length / 4; i++) {
                int g = bgData[i * 4 + 1] & 0xFF;
                int alpha = bgData[i * 4 + 3] & 0xFF;
                int r = bgData[i * 4] & 0xFF;
                int patIdx = r + ((g & 0x07) << 8);
                if (alpha > 0 && patIdx > 0 && (g & 0x80) != 0) {
                    bgPriCount++;
                }
            }
            System.out.println("backgroundTilemapData dimensions: " + bgWidth + "x" + bgHeight);
            System.out.println("backgroundTilemapData priority tile count: " + bgPriCount);
        }

        System.out.println();
        System.out.println("=== END PRIORITY DIAGNOSTIC ===");
    }
}


