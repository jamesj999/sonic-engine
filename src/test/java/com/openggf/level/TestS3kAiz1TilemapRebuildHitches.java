package com.openggf.level;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AIZ1 runtime art/terrain handovers must not rebuild both full-level tilemaps
 * on the frame they publish. The ROM pays no such cost (Events_fg_5 only
 * redraws Plane A as the camera scrolls), and a full FG+BG rebuild here was a
 * visible frame hitch right after the intro handed over control.
 *
 * <p>Lives in {@code com.openggf.level} to read the tilemap manager's
 * package-private full-rebuild counter and drive the render-side ensure calls
 * that headless frame stepping does not perform.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz1TilemapRebuildHitches {
    private static final int TERRAIN_SWAP_X = 0x1400;
    private static final int FIRE_OVERLAY_STAGE_X = 0x2E00;

    private Object oldSkipIntros;
    private Object oldMainCharacter;

    @BeforeEach
    public void setUp() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
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
    public void introTerrainSwapUsesPrebuiltTilemapsInsteadOfFullRebuild() throws Exception {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        LevelManager levelManager = loadAiz1(sonic);
        Camera camera = GameServices.camera();
        LevelTilemapManager tilemaps = levelManager.getTilemapManager();
        assertNotNull(tilemaps);
        assertTrue(levelManager.hasPrebuiltTilemaps(),
                "AIZ1 intro load should pre-build the post-$1400 tilemaps");

        HeadlessTestRunner runner = new HeadlessTestRunner(sonic);
        ensureTilemaps(levelManager);
        int fullRebuildsBeforeSwap = tilemaps.bgFullRebuildCount;
        byte[] fgBeforeSwap = tilemaps.getForegroundTilemapData();
        byte[] bgBeforeSwap = tilemaps.getBackgroundTilemapData();

        int swapFrame = -1;
        for (int frame = 1; frame <= 2400; frame++) {
            runner.stepFrame(false, false, false, true, false);
            ensureTilemaps(levelManager);
            if (AizPlaneIntroInstance.isMainLevelPhaseActive()) {
                swapFrame = frame;
                break;
            }
            assertSame(fgBeforeSwap, tilemaps.getForegroundTilemapData(),
                    "FG tilemap must not be rebuilt before the terrain swap (frame " + frame + ")");
            assertSame(bgBeforeSwap, tilemaps.getBackgroundTilemapData(),
                    "BG tilemap must not be rebuilt before the terrain swap (frame " + frame + ")");
        }
        assertTrue(swapFrame > 0, "camera never reached the $1400 terrain swap");
        assertTrue((camera.getX() & 0xFFFF) >= TERRAIN_SWAP_X);
        assertFalse(levelManager.hasPrebuiltTilemaps(), "swap should consume the pre-built tilemaps");
        assertEquals(fullRebuildsBeforeSwap, tilemaps.bgFullRebuildCount,
                "terrain swap frame must swap in pre-built data, not run a full BG rebuild");

        assertRetainedTilemapsMatchFullRebuild(levelManager, tilemaps);
    }

    @Test
    public void fireOverlayArtRefreshesPatternLookupWithoutTilemapRebuild() throws Exception {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        LevelManager levelManager = loadAiz1(sonic);
        Camera camera = GameServices.camera();
        LevelTilemapManager tilemaps = levelManager.getTilemapManager();
        Sonic3kAIZEvents aizEvents = ((Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider()).getAizEventsForTest();
        assertNotNull(aizEvents);

        // Known-good ground start shared with TestS3kAiz1SkipHeadless, just
        // short of the $2E00 fire-overlay staging threshold.
        sonic.setX((short) 11164);
        sonic.setY((short) 951);
        sonic.setAir(false);
        sonic.setControlLocked(false);
        camera.updatePosition(true);
        sonic.updateSensors(sonic.getX(), sonic.getY());

        HeadlessTestRunner runner = new HeadlessTestRunner(sonic);
        ensureTilemaps(levelManager);
        int fullRebuildsBefore = tilemaps.bgFullRebuildCount;
        byte[] bgBefore = tilemaps.getBackgroundTilemapData();

        // The hollow-tree reveal before $2E00 writes tile descriptors straight
        // into the FG tilemap (Plane A writes, as the ROM does), so the FG is
        // compared by identity across the load frame rather than against a
        // from-layout rebuild.
        int loadedFrame = -1;
        byte[] fgBeforeLoadFrame = null;
        byte[] lookupBeforeLoadFrame = null;
        for (int frame = 1; frame <= 900; frame++) {
            fgBeforeLoadFrame = tilemaps.getForegroundTilemapData();
            lookupBeforeLoadFrame = tilemaps.getPatternLookupData();
            runner.stepFrame(false, false, false, true, false);
            ensureTilemaps(levelManager);
            if (aizEvents.isFireOverlayTilesLoaded()) {
                loadedFrame = frame;
                break;
            }
        }
        assertTrue(loadedFrame > 0, "fire overlay tiles never loaded; camera=0x"
                + Integer.toHexString(camera.getX() & 0xFFFF));
        assertTrue((camera.getX() & 0xFFFF) >= FIRE_OVERLAY_STAGE_X);
        assertSame(bgBefore, tilemaps.getBackgroundTilemapData(),
                "fire overlay is pattern-only art; the BG tilemap must not be rebuilt");
        assertEquals(fullRebuildsBefore, tilemaps.bgFullRebuildCount);
        assertSame(fgBeforeLoadFrame, tilemaps.getForegroundTilemapData(),
                "fire overlay is pattern-only art; the FG tilemap must not be rebuilt");
        assertNotSame(lookupBeforeLoadFrame, tilemaps.getPatternLookupData(),
                "fire overlay art must refresh the pattern atlas lookup");
    }

    private static LevelManager loadAiz1(Sonic sonic) throws Exception {
        GameServices.sprites().addSprite(sonic);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sonic);
        camera.setFrozen(false);
        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);
        return levelManager;
    }

    private static void ensureTilemaps(LevelManager levelManager) {
        levelManager.ensureForegroundTilemapData();
        levelManager.ensureBackgroundTilemapData();
    }

    /** The retained tilemap bytes must equal what a from-scratch rebuild produces now. */
    private static void assertRetainedTilemapsMatchFullRebuild(LevelManager levelManager,
                                                               LevelTilemapManager tilemaps) {
        byte[] fgRetained = tilemaps.getForegroundTilemapData().clone();
        byte[] bgRetained = tilemaps.getBackgroundTilemapData().clone();
        int fgWidth = tilemaps.getForegroundTilemapWidthTiles();
        int bgWidth = tilemaps.getBackgroundTilemapWidthTiles();
        levelManager.invalidateAllTilemaps();
        ensureTilemaps(levelManager);
        assertEquals(fgWidth, tilemaps.getForegroundTilemapWidthTiles());
        assertEquals(bgWidth, tilemaps.getBackgroundTilemapWidthTiles());
        assertArrayEquals(fgRetained, tilemaps.getForegroundTilemapData(),
                "pre-built FG tilemap differs from a full rebuild");
        assertArrayEquals(bgRetained, tilemaps.getBackgroundTilemapData(),
                "pre-built BG tilemap differs from a full rebuild");
    }
}
