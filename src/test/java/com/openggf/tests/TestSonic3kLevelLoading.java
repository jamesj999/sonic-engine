package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.game.DynamicStartPositionProvider;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3k;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.LevelData;
import com.openggf.level.Map;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import org.junit.jupiter.api.AfterEach;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestSonic3kLevelLoading {
    private Game game;
    private Object oldSkipIntros;

    @BeforeEach
    public void setUp() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        game = new Sonic3k(rom);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros != null ? oldSkipIntros : false);
    }

    @Test
    public void preparedBuildUsesCapturedCharacterWithoutWorkerServiceLookups() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            SessionManager.clear();
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
            int levelIndex = LevelData.S3K_ANGEL_ISLAND_2.getLevelIndex();
            Level expected = game.loadLevel(levelIndex);
            var task = ((Sonic3k) game).prepareLevelBuildTask(levelIndex,
                    com.openggf.game.sonic3k.events.S3kSeamlessMutationExecutor.MUTATION_AIZ1_POST_RELOAD_ACT2);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var prepared = executor.submit(() -> {
                    // Both mocks are thread-scoped. Any ambient access on the worker fails,
                    // including a swallowed RuntimeException fallback inside a loader.
                    try (var services = org.mockito.Mockito.mockStatic(
                            com.openggf.game.GameServices.class, invocation -> {
                                throw new AssertionError("Worker resolved GameServices." + invocation.getMethod().getName());
                            });
                         var sessions = org.mockito.Mockito.mockStatic(SessionManager.class, invocation -> {
                             throw new AssertionError("Worker resolved SessionManager." + invocation.getMethod().getName());
                         })) {
                        return task.call();
                    }
                }).get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(expected.getPatternCount(), prepared.level().getPatternCount());
                for (int color = 0; color < com.openggf.level.Palette.PALETTE_SIZE; color++) {
                    var expectedColor = expected.getPalette(0).getColor(color);
                    var actualColor = prepared.level().getPalette(0).getColor(color);
                    assertEquals(expectedColor.r, actualColor.r, "captured character palette red " + color);
                    assertEquals(expectedColor.g, actualColor.g, "captured character palette green " + color);
                    assertEquals(expectedColor.b, actualColor.b, "captured character palette blue " + color);
                }
            }
        } finally {
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldMain != null ? oldMain : "sonic");
        }
    }

    @Test
    public void aiz1UsesRomStartPosition() throws Exception {
        assertTrue(game instanceof DynamicStartPositionProvider);
        DynamicStartPositionProvider provider = (DynamicStartPositionProvider) game;

        int[] start = provider.getStartPosition(0, 0);
        assertNotNull(start);
        assertEquals(2, start.length);

        // Known AIZ1 Sonic start from skdisasm: X=$13A0, Y=$041A
        assertEquals(0x13A0, start[0]);
        assertEquals(0x041A, start[1]);

        // LevelData fallback should also match ROM values
        assertEquals(LevelData.S3K_ANGEL_ISLAND_1.getStartXPos(), start[0]);
        assertEquals(LevelData.S3K_ANGEL_ISLAND_1.getStartYPos(), start[1]);
    }

    @Test
    public void aiz1UsesKnucklesStartPositionWhenSessionSelectedTeamIsKnuckles() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        try {
            assertTrue(game instanceof DynamicStartPositionProvider);
            DynamicStartPositionProvider provider = (DynamicStartPositionProvider) game;

            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
            int[] expectedKnuckles = provider.getStartPosition(0, 0);

            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            SessionManager.openGameplaySession(new Sonic3kGameModule(),
                    SaveSessionContext.noSave("s3k", new SelectedTeam("knuckles", List.of()), 0, 0));
            int[] sessionKnuckles = provider.getStartPosition(0, 0);

            assertNotNull(expectedKnuckles);
            assertNotNull(sessionKnuckles);
            assertArrayEquals(expectedKnuckles, sessionKnuckles);
            assertNotEquals(0x40, sessionKnuckles[0]);
            assertNotEquals(0x420, sessionKnuckles[1]);
        } finally {
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldMain);
        }
    }

    @Test
    public void aiz1LoadsWithValidResourceReferences() throws Exception {
        Level level = game.loadLevel(LevelData.S3K_ANGEL_ISLAND_1.getLevelIndex());
        // ROM: AIZ1 LevelSizes entry 0 has maxY=$390; resize routine adjusts dynamically.
        assertEquals(0x390, level.getMaxY(), "AIZ1 should use normal LevelSizes maxY");
        assertLevelResourceIntegrity(level, 0, 0);
    }

    @Test
    public void fbz1LoadsWithValidResourceReferences() throws Exception {
        Level level = game.loadLevel(LevelData.S3K_FLYING_BATTERY_1.getLevelIndex());
        assertLevelResourceIntegrity(level, 4, 0);
    }

    @Test
    public void mgz1Has32RowMapWithCorrectBlocks() throws Exception {
        Level level = game.loadLevel(LevelData.S3K_MARBLE_GARDEN_1.getLevelIndex());
        assertNotNull(level);
        Map map = level.getMap();
        assertNotNull(map);

        // MGZ1 layout header: FG 96x32
        assertEquals(96, level.getLayerWidthBlocks(0), "FG width should be 96 blocks");
        assertEquals(32, level.getLayerHeightBlocks(0), "FG height should be 32 blocks");
        assertEquals(96, map.getWidth());
        assertEquals(32, map.getHeight());

        // MGZ1 boundaries from LevelSizes: minY=-$100, maxY=$1000
        assertEquals(-0x100, level.getMinY(), "MGZ1 minY should be -$100");
        assertEquals(0x1000, level.getMaxY(), "MGZ1 maxY should be $1000");

        // Verify block values at spawn area (row 30):
        // Columns 0-3 should be empty (block 0x00), columns 4+ should be solid (block 0x10)
        assertEquals(0x00, Byte.toUnsignedInt(map.getValue(0, 1, 30)),
                "Block at (1,30) should be empty");
        assertEquals(0x10, Byte.toUnsignedInt(map.getValue(0, 4, 30)),
                "Block at (4,30) should be solid");

        // Row 14 (what 0x800 wrapping would show at row 30) should be all solid
        assertEquals(0x10, Byte.toUnsignedInt(map.getValue(0, 1, 14)),
                "Block at (1,14) should be solid");

        // Row 28 should be solid at columns 0-3 (ledge above the shaft)
        assertEquals(0x10, Byte.toUnsignedInt(map.getValue(0, 1, 28)),
                "Block at (1,28) should be solid");

        assertLevelResourceIntegrity(level, 2, 0);
    }

    @Test
    public void mgz2BackgroundCollisionRejectsZeroPointerRows() throws Exception {
        Level level = game.loadLevel(LevelData.S3K_MARBLE_GARDEN_2.getLevelIndex());

        assertEquals(7, level.getLayerHeightBlocks(1),
                "MGZ2's visual background declares seven block rows");
        assertTrue(level.hasBackgroundCollisionRowAt(0x0000));
        assertTrue(level.hasBackgroundCollisionRowAt(0x0300));
        assertFalse(level.hasBackgroundCollisionRowAt(0x0380),
                "the first zero BG row-pointer must not wrap into visual row zero");
        assertFalse(level.hasBackgroundCollisionRowAt(0xFFB2),
                "state-eight's negative BG collision coordinate selects absent ROM row 31");
    }

    private void assertLevelResourceIntegrity(Level level, int zone, int act) throws Exception {
        assertNotNull(level);
        assertNotNull(level.getMap());
        assertTrue(level.getPatternCount() > 0);
        assertTrue(level.getChunkCount() > 0);
        assertTrue(level.getBlockCount() > 0);

        int maxMapBlockIndex = 0;
        for (int layer = 0; layer < level.getMap().getLayerCount(); layer++) {
            for (int y = 0; y < level.getMap().getHeight(); y++) {
                for (int x = 0; x < level.getMap().getWidth(); x++) {
                    int blockIndex = Byte.toUnsignedInt(level.getMap().getValue(layer, x, y));
                    if (blockIndex > maxMapBlockIndex) {
                        maxMapBlockIndex = blockIndex;
                    }
                }
            }
        }

        assertTrue(maxMapBlockIndex > 0, "Map should reference non-empty blocks");
        assertTrue(maxMapBlockIndex < level.getBlockCount(), "Map references invalid block index " + maxMapBlockIndex);

        int maxBlockChunkIndex = 0;
        for (int blockIdx = 0; blockIdx < level.getBlockCount(); blockIdx++) {
            Block block = level.getBlock(blockIdx);
            for (int y = 0; y < level.getChunksPerBlockSide(); y++) {
                for (int x = 0; x < level.getChunksPerBlockSide(); x++) {
                    int chunkIndex = block.getChunkDesc(x, y).getChunkIndex();
                    if (chunkIndex > maxBlockChunkIndex) {
                        maxBlockChunkIndex = chunkIndex;
                    }
                }
            }
        }
        assertTrue(maxBlockChunkIndex < level.getChunkCount(), "Blocks reference invalid chunk index " + maxBlockChunkIndex);

        int[] start = null;
        if (game instanceof DynamicStartPositionProvider provider) {
            start = provider.getStartPosition(zone, act);
        }
        if (start == null || start.length < 2) {
            LevelData fallback = LevelData.S3K_ANGEL_ISLAND_1;
            start = new int[]{fallback.getStartXPos(), fallback.getStartYPos()};
        }

        assertValidTilePathAt(level, start[0], start[1]);
    }

    private void assertValidTilePathAt(Level level, int worldX, int worldY) {
        Map map = level.getMap();
        int blockPixelSize = level.getBlockPixelSize();
        int blockX = Math.max(0, Math.min(map.getWidth() - 1, worldX / blockPixelSize));
        int blockY = Math.max(0, Math.min(map.getHeight() - 1, worldY / blockPixelSize));

        int blockIndex = Byte.toUnsignedInt(map.getValue(0, blockX, blockY));
        assertTrue(blockIndex < level.getBlockCount(), "Spawn region references invalid block index " + blockIndex);

        Block block = level.getBlock(blockIndex);
        int chunkSize = 16;
        int chunksPerSide = level.getChunksPerBlockSide();
        int localX = Math.floorMod(worldX, blockPixelSize);
        int localY = Math.floorMod(worldY, blockPixelSize);
        int chunkX = Math.min(chunksPerSide - 1, localX / chunkSize);
        int chunkY = Math.min(chunksPerSide - 1, localY / chunkSize);
        int chunkIndex = block.getChunkDesc(chunkX, chunkY).getChunkIndex();
        assertTrue(chunkIndex < level.getChunkCount(), "Spawn region references invalid chunk index " + chunkIndex);

        Chunk chunk = level.getChunk(chunkIndex);
        int patternX = (localX % chunkSize) / 8;
        int patternY = (localY % chunkSize) / 8;
        int patternIndex = chunk.getPatternDesc(patternX, patternY).getPatternIndex();
        assertTrue(patternIndex < level.getPatternCount(), "Spawn region references invalid pattern index " + patternIndex);
    }
}

