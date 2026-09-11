package com.openggf.game.sonic3k;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.scroll.Sonic3kZoneConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.LevelConstants;
import com.openggf.level.LevelManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Guard for the rewind-keyframe isolation contract on S3K runtime overlays.
 *
 * <p>LevelSnapshot captures the live Block/Chunk arrays by reference, so any
 * runtime mutation must clone the array and install freshly constructed
 * entries (the DirectLevelMutationSurface pattern). The AIZ intro terrain swap
 * and AIZ2 battleship terrain load run {@code applyChunkOverlay} mid-act; if
 * those mutate Chunk objects in place, every previously captured rewind
 * keyframe silently adopts the post-overlay terrain and collision.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kLevelOverlayRewindIsolation {

    private Sonic3kLevel level;
    private Object oldSkipIntros;

    @BeforeEach
    void setUp() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        GraphicsManager.getInstance().initHeadless();

        Sonic sprite = new Sonic(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE),
                (short) 100, (short) 400);
        GameServices.sprites().addSprite(sprite);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sprite);
        camera.setFrozen(false);

        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(Sonic3kZoneConstants.ZONE_AIZ, 0);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);

        level = (Sonic3kLevel) levelManager.getCurrentLevel();
    }

    @AfterEach
    void tearDown() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros != null ? oldSkipIntros : false);
    }

    @Test
    void chunkOverlayLeavesPreviouslyCapturedChunkArrayUntouched() {
        Chunk[] keyframeChunks = level.chunksReference();
        int chunkIndex = 1;
        int[] keyframeState = keyframeChunks[chunkIndex].saveState();

        byte[] overlay = new byte[Chunk.CHUNK_SIZE_IN_ROM];
        Arrays.fill(overlay, (byte) 0x11);
        level.applyChunkOverlay(overlay, chunkIndex * Chunk.CHUNK_SIZE_IN_ROM, false);

        assertNotSame(keyframeChunks, level.chunksReference(),
                "overlay must install a cloned chunks array, not write into the captured one");
        assertArrayEquals(keyframeState, keyframeChunks[chunkIndex].saveState(),
                "captured keyframe chunk must keep its pre-overlay contents");
        assertFalse(Arrays.equals(keyframeState, level.chunksReference()[chunkIndex].saveState()),
                "live level must show the overlaid chunk contents");
    }

    @Test
    void blockOverlayLeavesPreviouslyCapturedBlockArrayUntouched() {
        Block[] keyframeBlocks = level.blocksReference();
        int blockIndex = 1;
        int[] keyframeState = keyframeBlocks[blockIndex].saveState();

        byte[] overlay = new byte[LevelConstants.BLOCK_SIZE_IN_ROM];
        Arrays.fill(overlay, (byte) 0x22);
        level.applyBlockOverlay(overlay, blockIndex * LevelConstants.BLOCK_SIZE_IN_ROM, false);

        assertNotSame(keyframeBlocks, level.blocksReference(),
                "overlay must install a cloned blocks array, not write into the captured one");
        assertArrayEquals(keyframeState, keyframeBlocks[blockIndex].saveState(),
                "captured keyframe block must keep its pre-overlay contents");
        assertFalse(Arrays.equals(keyframeState, level.blocksReference()[blockIndex].saveState()),
                "live level must show the overlaid block contents");
    }

    @Test
    void restoreChunksLeavesPreviouslyCapturedChunkArrayUntouched() {
        int chunkIndex = 1;
        int[][] preOverlaySnapshot = level.snapshotChunks();

        byte[] overlay = new byte[Chunk.CHUNK_SIZE_IN_ROM];
        Arrays.fill(overlay, (byte) 0x11);
        level.applyChunkOverlay(overlay, chunkIndex * Chunk.CHUNK_SIZE_IN_ROM, false);

        // A rewind keyframe captured between overlay and restore shares the
        // post-overlay array; restoreChunks must not mutate its entries.
        Chunk[] keyframeChunks = level.chunksReference();
        int[] keyframeState = keyframeChunks[chunkIndex].saveState();

        level.restoreChunks(preOverlaySnapshot);

        assertNotSame(keyframeChunks, level.chunksReference(),
                "restore must install a cloned chunks array, not write into the captured one");
        assertArrayEquals(keyframeState, keyframeChunks[chunkIndex].saveState(),
                "captured keyframe chunk must keep its pre-restore contents");
        assertArrayEquals(preOverlaySnapshot[chunkIndex], level.chunksReference()[chunkIndex].saveState(),
                "live level must show the restored chunk contents");
    }
}
