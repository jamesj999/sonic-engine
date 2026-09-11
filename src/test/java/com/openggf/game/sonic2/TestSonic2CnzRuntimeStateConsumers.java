package com.openggf.game.sonic2;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
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
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2CnzRuntimeStateConsumers {

    private static final int ACT_2 = 1;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void cnzRuntimeStateDoesNotExistOutsideCnz() {
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();
        manager.initLevel(Sonic2LevelEventManager.ZONE_EHZ, 0);

        assertTrue(GameServices.zoneRuntimeRegistry().currentAs(CnzRuntimeState.class).isEmpty());
    }

    @Test
    void cnzRuntimeStateViewTracksBossArenaSemantics() {
        LevelManager levelManager = GameServices.level();
        levelManager.setLevel(new SyntheticCnzLevel());

        Sonic2CNZEvents events = new Sonic2CNZEvents();
        events.init(ACT_2);
        CnzRuntimeState state = new CnzRuntimeStateView(Sonic2LevelEventManager.ZONE_CNZ, ACT_2, events);

        assertFalse(state.bossArenaActive());
        assertFalse(state.bossSpawnPending());
        assertFalse(state.bossSpawned());
        assertFalse(state.leftArenaWallPlaced());
        assertFalse(state.rightArenaWallPlaced());
        assertEquals(0, state.eventRoutine());

        GameServices.camera().setX((short) 0x2890);
        events.update(ACT_2, 1);
        events.update(ACT_2, 2);

        assertTrue(state.bossArenaActive());
        assertTrue(state.bossSpawnPending());
        assertFalse(state.bossSpawned());
        assertTrue(state.leftArenaWallPlaced());
        assertTrue(state.rightArenaWallPlaced());
        assertEquals(4, state.eventRoutine());

        GameServices.camera().setY((short) 0x4E0);
        for (int frame = 0; frame < 0x5A; frame++) {
            events.update(ACT_2, 3 + frame);
        }

        assertTrue(state.bossArenaActive());
        assertFalse(state.bossSpawnPending());
        assertTrue(state.bossSpawned());
        assertEquals(6, state.eventRoutine());
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

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }
    }
}
