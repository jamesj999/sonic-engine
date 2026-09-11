package com.openggf.game.sonic2;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.events.Sonic2CNZEvents;
import com.openggf.game.sonic2.runtime.CnzRuntimeState;
import com.openggf.game.sonic2.runtime.CnzRuntimeStateView;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelManager;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2CnzMutationPipeline {

    private static final int ACT_2 = 1;
    private static final int LEFT_WALL_X = 80;
    private static final int RIGHT_WALL_X = 84;
    private static final int WALL_Y = 12;
    private static final int WALL_BLOCK = 0xF9;
    private static final int EMPTY_BLOCK = 0xDD;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameModuleRegistry.reset();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void cnzArenaWallPlacementAndRemovalWaitForQueuedFlush() {
        LevelManager levelManager = GameServices.level();
        SyntheticCnzLevel level = new SyntheticCnzLevel();
        levelManager.setLevel(level);

        Sonic2CNZEvents events = new Sonic2CNZEvents();
        events.init(ACT_2);
        CnzRuntimeState state = new CnzRuntimeStateView(Sonic2LevelEventManager.ZONE_CNZ, ACT_2, events);

        assertFalse(state.leftArenaWallPlaced());
        assertFalse(state.rightArenaWallPlaced());
        assertFalse(state.bossSpawned());

        GameServices.camera().setX((short) 0x2890);

        events.update(ACT_2, 1);
        events.update(ACT_2, 2);

        assertTrue(state.leftArenaWallPlaced());
        assertTrue(state.rightArenaWallPlaced());
        assertTrue(state.bossArenaActive());
        assertTrue(state.bossSpawnPending());
        assertFalse(state.bossSpawned());

        assertEquals(0, level.getMapValue(LEFT_WALL_X, WALL_Y));
        assertEquals(0, level.getMapValue(RIGHT_WALL_X, WALL_Y));

        levelManager.flushQueuedLayoutMutations();

        assertEquals(WALL_BLOCK, level.getMapValue(LEFT_WALL_X, WALL_Y));
        assertEquals(WALL_BLOCK, level.getMapValue(RIGHT_WALL_X, WALL_Y));

        events.onBossDefeated();

        assertTrue(state.rightArenaWallPlaced());

        assertEquals(WALL_BLOCK, level.getMapValue(LEFT_WALL_X, WALL_Y));
        assertEquals(WALL_BLOCK, level.getMapValue(RIGHT_WALL_X, WALL_Y));

        levelManager.flushQueuedLayoutMutations();

        assertEquals(WALL_BLOCK, level.getMapValue(LEFT_WALL_X, WALL_Y));
        assertEquals(EMPTY_BLOCK, level.getMapValue(RIGHT_WALL_X, WALL_Y));
    }

    private static final class SyntheticCnzLevel extends AbstractLevel {

        private SyntheticCnzLevel() {
            super(0);
            palettes = new Palette[] { new Palette(), new Palette(), new Palette(), new Palette() };
            patterns = new Pattern[] { new Pattern() };
            patternCount = patterns.length;
            chunks = new Chunk[] { buildChunk() };
            chunkCount = chunks.length;
            blocks = new Block[256];
            blocks[0] = buildBlock();
            for (int i = 1; i < blocks.length; i++) {
                blocks[i] = new Block();
            }
            blockCount = blocks.length;
            solidTiles = new SolidTile[] { emptySolidTile() };
            solidTileCount = solidTiles.length;
            map = new Map(2, 256, 16);
            minX = 0;
            maxX = 0x4000;
            minY = 0;
            maxY = 0x1000;
            objects = Collections.emptyList();
            rings = Collections.emptyList();
        }

        private static Chunk buildChunk() {
            Chunk chunk = new Chunk();
            int[] state = new int[Chunk.PATTERNS_PER_CHUNK + 2];
            state[0] = 1;
            chunk.restoreState(state);
            return chunk;
        }

        private static Block buildBlock() {
            Block block = new Block();
            block.setChunkDesc(0, 0, new ChunkDesc(0));
            return block;
        }

        private static SolidTile emptySolidTile() {
            return new SolidTile(0, new byte[SolidTile.TILE_SIZE_IN_ROM],
                    new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0);
        }

        int getMapValue(int x, int y) {
            return map.getValue(0, x, y) & 0xFF;
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }
    }
}
