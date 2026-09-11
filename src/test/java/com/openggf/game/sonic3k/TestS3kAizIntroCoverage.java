package com.openggf.game.sonic3k;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Block;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Map;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;

import java.util.LinkedHashMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAizIntroCoverage {
    private Object oldSkipIntros;
    private Object oldMainCharacter;

    @BeforeEach
    public void setUp() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    public void tearDown() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
    }

    @Test
    public void aizIntroTransitionLoadsMainLevelChunkCoverage() throws Exception {
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        GameServices.sprites().addSprite(sonic);

        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sonic);
        camera.setFrozen(false);

        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);

        Level level = levelManager.getCurrentLevel();
        assertNotNull(level, "AIZ1 level should be loaded");
        Map map = level.getMap();
        assertNotNull(map, "AIZ1 map should be loaded");
        HeadlessTestRunner runner = new HeadlessTestRunner(sonic);

        int fgWidth = Math.max(1, level.getLayerWidthBlocks(0));
        int fgHeight = Math.max(1, level.getLayerHeightBlocks(0));
        int bgWidth = Math.max(1, level.getLayerWidthBlocks(1));
        int bgHeight = Math.max(1, level.getLayerHeightBlocks(1));

        int initialChunkCount = level.getChunkCount();
        int initialInvalidRefs = 0;
        int maxUsedChunkIndex = 0;
        TreeSet<Integer> invalidColumns = new TreeSet<>();

        initialInvalidRefs += scanLayer(level, map, 0, fgWidth, fgHeight, initialChunkCount, invalidColumns);
        initialInvalidRefs += scanLayer(level, map, 1, bgWidth, bgHeight, initialChunkCount, invalidColumns);

        for (int y = 0; y < fgHeight; y++) {
            for (int x = 0; x < fgWidth; x++) {
                int blockIndex = Byte.toUnsignedInt(map.getValue(0, x, y));
                Block block = level.getBlock(blockIndex);
                for (int cy = 0; cy < 8; cy++) {
                    for (int cx = 0; cx < 8; cx++) {
                        int idx = block.getChunkDesc(cx, cy).getChunkIndex();
                        if (idx > maxUsedChunkIndex) {
                            maxUsedChunkIndex = idx;
                        }
                    }
                }
            }
        }
        for (int y = 0; y < bgHeight; y++) {
            for (int x = 0; x < bgWidth; x++) {
                int blockIndex = Byte.toUnsignedInt(map.getValue(1, x, y));
                Block block = level.getBlock(blockIndex);
                for (int cy = 0; cy < 8; cy++) {
                    for (int cx = 0; cx < 8; cx++) {
                        int idx = block.getChunkDesc(cx, cy).getChunkIndex();
                        if (idx > maxUsedChunkIndex) {
                            maxUsedChunkIndex = idx;
                        }
                    }
                }
            }
        }

        assertEquals(0, initialInvalidRefs, "Initial AIZ chunk array should cover all block references");

        // Run until the intro reaches the ROM transition point (camera X >= $1400),
        // then allow a short settle period for overlay application.
        int guard = 5000;
        while (guard-- > 0 && (camera.getX() & 0xFFFF) < 0x1400) {
            runner.stepFrame(false, false, false, false, false);
        }
        for (int i = 0; i < 32; i++) {
            runner.stepFrame(false, false, false, false, false);
        }

        Level postTransitionLevel = levelManager.getCurrentLevel();
        assertNotNull(postTransitionLevel, "AIZ1 level should remain loaded after intro transition");
        int postChunkCount = postTransitionLevel.getChunkCount();
        int postInvalidRefs = 0;
        invalidColumns.clear();
        postInvalidRefs += scanLayer(postTransitionLevel, map, 0, fgWidth, fgHeight, postChunkCount, invalidColumns);
        postInvalidRefs += scanLayer(postTransitionLevel, map, 1, bgWidth, bgHeight, postChunkCount, invalidColumns);

        System.out.println("AIZ intro coverage: initialChunkCount=" + initialChunkCount
                + " postChunkCount=" + postChunkCount
                + " maxUsedChunkIndex=" + maxUsedChunkIndex
                + " initialInvalidRefs=" + initialInvalidRefs
                + " postInvalidRefs=" + postInvalidRefs
                + " invalidColumns=" + invalidColumns);

        assertEquals(0, postInvalidRefs, "Post-transition AIZ map should not reference missing 16x16 chunks");
    }

    /**
     * Diagnostic: dump the full BG tile data chain before and after the terrain swap.
     * Traces Map â†’ Block â†’ ChunkDesc â†’ Chunk â†’ PatternDesc â†’ Pattern for every
     * BG tile, comparing pre- and post-transition states to find exactly which
     * tiles change and whether any produce corrupt references.
     */
    @Test
    public void diagnoseBgTileChainAtTransition() throws Exception {
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        GameServices.sprites().addSprite(sonic);

        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sonic);
        camera.setFrozen(false);

        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);

        Level level = levelManager.getCurrentLevel();
        assertNotNull(level);
        Map map = level.getMap();
        assertNotNull(map);

        int bgWidth = Math.max(1, level.getLayerWidthBlocks(1));
        int bgHeight = Math.max(1, level.getLayerHeightBlocks(1));

        System.out.println("=== BG DIAGNOSTIC ===");
        System.out.println("BG map dimensions: " + bgWidth + "x" + bgHeight + " blocks"
                + " (" + (bgWidth * 128) + "x" + (bgHeight * 128) + " px)");
        System.out.println("chunkCount=" + level.getChunkCount()
                + " patternCount=" + level.getPatternCount());

        // Snapshot BG tile chain BEFORE transition
        java.util.Map<String, int[]> preTiles = snapshotBgTiles(level, map, bgWidth, bgHeight);

        // Run to transition
        HeadlessTestRunner runner = new HeadlessTestRunner(sonic);
        int guard = 5000;
        while (guard-- > 0 && (camera.getX() & 0xFFFF) < 0x1400) {
            runner.stepFrame(false, false, false, false, false);
        }
        for (int i = 0; i < 32; i++) {
            runner.stepFrame(false, false, false, false, false);
        }

        Level postLevel = levelManager.getCurrentLevel();
        System.out.println("POST chunkCount=" + postLevel.getChunkCount()
                + " patternCount=" + postLevel.getPatternCount());

        // Snapshot BG tile chain AFTER transition
        java.util.Map<String, int[]> postTiles = snapshotBgTiles(postLevel, map, bgWidth, bgHeight);

        // Compare: find tiles that changed
        int changedTiles = 0;
        int patternOutOfRange = 0;
        int chunkOutOfRange = 0;
        int nonEmptyFromEmptyChunk = 0;
        int emptyPatternButVisible = 0;

        StringBuilder details = new StringBuilder();
        for (var entry : postTiles.entrySet()) {
            String key = entry.getKey();
            int[] post = entry.getValue();
            int[] pre = preTiles.get(key);

            int chunkIdx = post[0];
            int patternIdx = post[1];
            int patternCount = postLevel.getPatternCount();
            int chunkCount = postLevel.getChunkCount();

            if (chunkIdx >= chunkCount) {
                chunkOutOfRange++;
                if (details.length() < 2000) {
                    details.append("  CHUNK_OOR ").append(key)
                            .append(" chunkIdx=").append(chunkIdx)
                            .append(" >= chunkCount=").append(chunkCount).append('\n');
                }
            }

            if (patternIdx >= patternCount && patternIdx != 0) {
                patternOutOfRange++;
                if (details.length() < 2000) {
                    details.append("  PATTERN_OOR ").append(key)
                            .append(" patternIdx=").append(patternIdx)
                            .append(" >= patternCount=").append(patternCount).append('\n');
                }
            }

            // Check if a tile from an extended (empty) chunk has non-zero pattern index
            if (chunkIdx >= 600 && patternIdx != 0) {
                nonEmptyFromEmptyChunk++;
                if (details.length() < 2000) {
                    details.append("  NONEMPTY_FROM_EXTENDED ").append(key)
                            .append(" chunkIdx=").append(chunkIdx)
                            .append(" patternIdx=").append(patternIdx).append('\n');
                }
            }

            // Check if pattern data is truly empty (all zero pixels)
            if (patternIdx > 0 && patternIdx < patternCount) {
                Pattern pattern = postLevel.getPattern(patternIdx);
                boolean allZero = true;
                for (int py = 0; py < 8 && allZero; py++) {
                    for (int px = 0; px < 8 && allZero; px++) {
                        if (pattern.getPixel(px, py) != 0) {
                            allZero = false;
                        }
                    }
                }
                // If pattern was cleared but chunk still references it with non-zero desc
                if (allZero && pre != null && pre[1] != patternIdx) {
                    emptyPatternButVisible++;
                }
            }

            if (pre != null && (pre[0] != post[0] || pre[1] != post[1])) {
                changedTiles++;
            }
        }

        System.out.println("BG tile changes: " + changedTiles + " tiles changed");
        System.out.println("  chunkOutOfRange=" + chunkOutOfRange
                + " patternOutOfRange=" + patternOutOfRange
                + " nonEmptyFromEmptyChunk=" + nonEmptyFromEmptyChunk
                + " emptyPatternButVisible=" + emptyPatternButVisible);
        if (details.length() > 0) {
            System.out.println("Details:\n" + details);
        }

        // Dump a sample of BG tiles to understand what patterns are used
        System.out.println("=== BG PATTERN INDEX HISTOGRAM (post-transition) ===");
        java.util.Map<Integer, Integer> histogram = new LinkedHashMap<>();
        for (int[] tile : postTiles.values()) {
            histogram.merge(tile[1], 1, Integer::sum);
        }
        histogram.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(20)
                .forEach(e -> System.out.println("  patternIdx=" + e.getKey()
                        + " count=" + e.getValue()
                        + (e.getKey() >= postLevel.getPatternCount() ? " OOR!" : "")));

        assertEquals(0, chunkOutOfRange, "No BG chunk references should be out of range");
        assertEquals(0, patternOutOfRange, "No BG pattern references should be out of range");
        assertEquals(0, nonEmptyFromEmptyChunk, "No non-empty patterns from extended (empty) chunks");
        assertEquals(0, emptyPatternButVisible, "No empty patterns referenced by changed tile descriptors");
    }

    /**
     * Snapshot all BG tiles as keyâ†’[chunkIndex, patternIndex].
     * Key format: "bX_bY_cX_cY_pX_pY" (block pos, chunk-in-block pos, pattern-in-chunk pos).
     */
    private static java.util.Map<String, int[]> snapshotBgTiles(
            Level level, Map map, int bgWidth, int bgHeight) {
        java.util.Map<String, int[]> tiles = new LinkedHashMap<>();
        for (int by = 0; by < bgHeight; by++) {
            for (int bx = 0; bx < bgWidth; bx++) {
                int blockIndex = Byte.toUnsignedInt(map.getValue(1, bx, by));
                if (blockIndex >= level.getBlockCount()) continue;
                Block block = level.getBlock(blockIndex);
                for (int cy = 0; cy < 8; cy++) {
                    for (int cx = 0; cx < 8; cx++) {
                        ChunkDesc chunkDesc = block.getChunkDesc(cx, cy);
                        int chunkIdx = chunkDesc.getChunkIndex();
                        if (chunkIdx < level.getChunkCount()) {
                            Chunk chunk = level.getChunk(chunkIdx);
                            for (int py = 0; py < 2; py++) {
                                for (int px = 0; px < 2; px++) {
                                    PatternDesc pd = chunk.getPatternDesc(px, py);
                                    String key = bx + "_" + by + "_" + cx + "_" + cy + "_" + px + "_" + py;
                                    tiles.put(key, new int[]{chunkIdx, pd.getPatternIndex()});
                                }
                            }
                        }
                    }
                }
            }
        }
        return tiles;
    }

    private static int scanLayer(Level level,
                                 Map map,
                                 int layer,
                                 int width,
                                 int height,
                                 int chunkCount,
                                 TreeSet<Integer> invalidColumns) {
        int invalid = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int blockIndex = Byte.toUnsignedInt(map.getValue(layer, x, y));
                Block block = level.getBlock(blockIndex);
                for (int cy = 0; cy < 8; cy++) {
                    for (int cx = 0; cx < 8; cx++) {
                        int idx = block.getChunkDesc(cx, cy).getChunkIndex();
                        if (idx >= chunkCount) {
                            invalid++;
                            invalidColumns.add(x);
                        }
                    }
                }
            }
        }
        return invalid;
    }
}


