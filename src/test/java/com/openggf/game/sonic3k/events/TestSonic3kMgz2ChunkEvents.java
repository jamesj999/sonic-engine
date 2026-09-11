package com.openggf.game.sonic3k.events;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.data.Rom;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelManager;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the MGZ Act 2 chunk-event terrain swaps.
 *
 * <p>ROM: {@code MGZ2_ChunkEvent} / {@code MGZ2_ModifyChunk}
 * (sonic3k.asm:106791-106926) and the replacement data tables in
 * {@code Lockon S3/Screen Events.asm}.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kMgz2ChunkEvents {

    private static final int QUAKE_CHUNK_DATA_ADDR = 0x3CBBB4;
    private static final int QUAKE_CHUNK_DATA_SIZE = 0x1080;
    private static final int MUTATED_LEFT_BLOCK_INDEX = 0xB1;
    private static final int MUTATED_RIGHT_BLOCK_INDEX = 0xEA;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
        GameServices.level().setLevel(new SyntheticMgzLevel());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void firstChunkTrigger_armsOnlyAfterQuakeStartsContinuousShake() throws IOException {
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);
        Sonic3kMGZEvents events = eventsWithQuakeChunkData();
        events.init(1);

        events.update(1, 0);
        GameServices.camera().setX((short) 0x7E0);
        events.update(1, 1);
        assertTrue(events.isScreenShakeActive(), "quake spawn should enable continuous shaking");

        player.setCentreX((short) 0x0F70);
        player.setCentreY((short) 0x0520);
        events.update(1, 2);

        assertEquals(4, events.getChunkEventRoutine(),
                "first MGZ2 chunk trigger should arm routine 4 once shaking is active");
        SyntheticMgzLevel level = (SyntheticMgzLevel) GameServices.level().getCurrentLevel();
        assertArrayEquals(expectedBlockState(0x100), level.getBlock(MUTATED_LEFT_BLOCK_INDEX).saveState(),
                "the first chunk mutation should execute on the same frame the trigger arms");
    }

    @Test
    void firstChunkEvent_appliesRealBlockStates_onRomCadence() throws IOException {
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);
        Sonic3kMGZEvents events = eventsWithQuakeChunkData();
        events.init(1);

        events.update(1, 0);
        GameServices.camera().setX((short) 0x7E0);
        events.update(1, 1);
        player.setCentreX((short) 0x0F70);
        player.setCentreY((short) 0x0520);
        events.update(1, 2);

        SyntheticMgzLevel level = (SyntheticMgzLevel) GameServices.level().getCurrentLevel();
        int[] initialBlock17 = level.getBlock(MUTATED_LEFT_BLOCK_INDEX).saveState();
        int[] initialBlock74 = level.getBlock(MUTATED_RIGHT_BLOCK_INDEX).saveState();

        assertArrayEquals(expectedBlockState(0x100), level.getBlock(MUTATED_LEFT_BLOCK_INDEX).saveState(),
                "first block replacement should match MGZ2_QuakeChunks[$100]");
        assertArrayEquals(expectedBlockState(0x500), level.getBlock(MUTATED_RIGHT_BLOCK_INDEX).saveState(),
                "second block replacement should match MGZ2_QuakeChunks[$500]");

        for (int frame = 3; frame <= 8; frame++) {
            events.update(1, frame);
        }

        assertArrayEquals(expectedBlockState(0x100), level.getBlock(MUTATED_LEFT_BLOCK_INDEX).saveState(),
                "left MGZ2 quake block should hold the first replacement state until the 7-frame cadence expires");
        assertArrayEquals(expectedBlockState(0x500), level.getBlock(MUTATED_RIGHT_BLOCK_INDEX).saveState(),
                "right MGZ2 quake block should hold the first replacement state until the 7-frame cadence expires");

        events.update(1, 9);

        assertArrayEquals(expectedBlockState(0x180), level.getBlock(MUTATED_LEFT_BLOCK_INDEX).saveState(),
                "second mutation step should advance the left quake block to MGZ2_QuakeChunks[$180]");
        assertArrayEquals(expectedBlockState(0x580), level.getBlock(MUTATED_RIGHT_BLOCK_INDEX).saveState(),
                "second mutation step should advance the right quake block to MGZ2_QuakeChunks[$580]");
        assertTrue(!Arrays.equals(initialBlock17, level.getBlock(MUTATED_LEFT_BLOCK_INDEX).saveState()));
        assertTrue(!Arrays.equals(initialBlock74, level.getBlock(MUTATED_RIGHT_BLOCK_INDEX).saveState()));
    }

    @Test
    void firstChunkEvent_withoutRomDataDoesNotUseDisassemblyFileFallback() {
        AbstractPlayableSprite player = placePlayer(0x790, 0x590);
        Sonic3kMGZEvents events = new MissingQuakeChunkDataEvents();
        events.init(1);

        events.update(1, 0);
        GameServices.camera().setX((short) 0x7E0);
        events.update(1, 1);
        player.setCentreX((short) 0x0F70);
        player.setCentreY((short) 0x0520);

        SyntheticMgzLevel level = (SyntheticMgzLevel) GameServices.level().getCurrentLevel();
        int[] originalLeft = level.getBlock(MUTATED_LEFT_BLOCK_INDEX).saveState();
        int[] originalRight = level.getBlock(MUTATED_RIGHT_BLOCK_INDEX).saveState();

        events.update(1, 2);

        assertArrayEquals(originalLeft, level.getBlock(MUTATED_LEFT_BLOCK_INDEX).saveState(),
                "production MGZ events should not read checked-in disassembly chunk data when ROM data is unavailable");
        assertArrayEquals(originalRight, level.getBlock(MUTATED_RIGHT_BLOCK_INDEX).saveState(),
                "production MGZ events should leave quake replacement blocks unchanged without ROM data");
    }

    private static AbstractPlayableSprite placePlayer(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(player);
        return player;
    }

    private static int[] expectedBlockState(int offset) throws IOException {
        byte[] data = quakeChunkDataFromRom();
        int[] state = new int[64];
        for (int i = 0; i < state.length; i++) {
            int byteIndex = offset + i * 2;
            state[i] = ((data[byteIndex] & 0xFF) << 8) | (data[byteIndex + 1] & 0xFF);
        }
        return state;
    }

    private static Sonic3kMGZEvents eventsWithQuakeChunkData() throws IOException {
        return new FixtureQuakeChunkDataEvents(quakeChunkDataFromRom());
    }

    private static byte[] quakeChunkDataFromRom() throws IOException {
        java.io.File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        try (Rom rom = new Rom()) {
            if (!rom.open(romFile.getAbsolutePath())) {
                throw new IOException("Failed to open configured S3K ROM");
            }
            return rom.readBytes(QUAKE_CHUNK_DATA_ADDR, QUAKE_CHUNK_DATA_SIZE);
        }
    }

    private static final class FixtureQuakeChunkDataEvents extends Sonic3kMGZEvents {
        private final byte[] quakeChunkData;

        private FixtureQuakeChunkDataEvents(byte[] quakeChunkData) {
            this.quakeChunkData = quakeChunkData;
        }

        @Override
        protected byte[] loadMgzQuakeChunkData() {
            return quakeChunkData;
        }
    }

    private static final class MissingQuakeChunkDataEvents extends Sonic3kMGZEvents {
        @Override
        protected byte[] loadMgzQuakeChunkData() {
            return null;
        }
    }

    private static final class SyntheticMgzLevel extends AbstractLevel {

        private SyntheticMgzLevel() {
            super(0);
            palettes = new Palette[] { new Palette(), new Palette(), new Palette(), new Palette() };
            patterns = new Pattern[] { new Pattern(), new Pattern(), new Pattern(), new Pattern() };
            patternCount = patterns.length;
            chunks = new Chunk[] { buildChunk() };
            chunkCount = chunks.length;
            blocks = buildBlocks();
            blockCount = blocks.length;
            solidTiles = new SolidTile[] {
                    new SolidTile(0, new byte[SolidTile.TILE_SIZE_IN_ROM],
                            new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0)
            };
            solidTileCount = solidTiles.length;
            map = new Map(2, 1, 1);
            map.setValue(0, 0, 0, (byte) 0);
            map.setValue(1, 0, 0, (byte) 0);
            objects = new ArrayList<ObjectSpawn>();
            rings = new ArrayList<RingSpawn>();
            ringSpriteSheet = null;
            minX = 0;
            maxX = 0x8000;
            minY = 0;
            maxY = 0x1000;
        }

        private static Chunk buildChunk() {
            Chunk chunk = new Chunk();
            chunk.restoreState(new int[] { 0, 1, 2, 3, 0, 0 });
            return chunk;
        }

        private static Block[] buildBlocks() {
            Block[] blocks = new Block[0x100];
            for (int blockIndex = 0; blockIndex < blocks.length; blockIndex++) {
                Block block = new Block();
                int[] state = new int[64];
                for (int i = 0; i < state.length; i++) {
                    state[i] = (blockIndex * 64 + i) & 0x03FF;
                }
                block.restoreState(state);
                blocks[blockIndex] = block;
            }
            return blocks;
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }
    }
}
