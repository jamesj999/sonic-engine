package com.openggf.level;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.Sonic3kPlcLoader;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.graphics.GraphicsManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The AIZ1 fire hand-off reloads the level as act 2 while the fire curtain
 * covers the screen. That reload used to construct the level, build its
 * object art sheets, and build both tilemaps on the reload frame (about 40 ms
 * headless); it now installs a build prepared during the fire event's own
 * rise-and-wait. The prepared path must be invisible to gameplay: same reload
 * frame, same positions, same tilemap bytes, same object art.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz1FireTransitionPreparedReload {
    private static final int MAX_FRAMES = 2000;

    private Object oldSkipIntros;
    private Object oldMainCharacter;

    @BeforeEach
    public void setUp() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    public void tearDown() {
        LevelLoadPreparer.setEnabledForTests(true);
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
    }

    @Test
    public void act2ReloadInstallsPreparedBuildWithoutTilemapRebuild() throws Exception {
        Outcome prepared = runFireTransition(true);

        assertEquals(1, prepared.preparedInstalls, "act 2 reload should install the prepared build");
        assertEquals(0, prepared.bgFullRebuildsAfterReload,
                "prepared tilemaps must be adopted; no full BG rebuild on or after the reload frame");
        assertFalse(prepared.prebuiltLeftOver, "prebuilt tilemaps should be consumed by the reload");
        assertArrayEquals(prepared.fgAtReload, prepared.fgFullRebuild,
                "prepared FG tilemap differs from a from-scratch build of the installed level");
        assertArrayEquals(prepared.bgAtReload, prepared.bgFullRebuild,
                "prepared BG tilemap differs from a from-scratch build of the installed level");
        assertFalse(prepared.levelArtKeys.isEmpty(), "act 2 level art sheets should be registered");
    }

    @Test
    public void preparedReloadMatchesSynchronousReload() throws Exception {
        Outcome prepared = runFireTransition(true);
        Outcome synchronous = runFireTransition(false);

        assertEquals(0, synchronous.preparedInstalls);
        assertEquals(synchronous.reloadFrame, prepared.reloadFrame, "reload frame must not move");
        assertEquals(synchronous.playerX, prepared.playerX, "player X after reload");
        assertEquals(synchronous.playerY, prepared.playerY, "player Y after reload");
        assertEquals(synchronous.cameraX, prepared.cameraX, "camera X after reload");
        assertEquals(synchronous.cameraY, prepared.cameraY, "camera Y after reload");
        assertArrayEquals(synchronous.fgAtReload, prepared.fgAtReload, "FG tilemap at reload");
        assertArrayEquals(synchronous.bgAtReload, prepared.bgAtReload, "BG tilemap at reload");
        assertEquals(synchronous.levelArtKeys, prepared.levelArtKeys, "registered level art");
        assertEquals(synchronous.patternCount, prepared.patternCount, "installed pattern count");
    }

    private record Outcome(int reloadFrame, int preparedInstalls, int bgFullRebuildsAfterReload,
                           boolean prebuiltLeftOver, byte[] fgAtReload, byte[] bgAtReload,
                           byte[] fgFullRebuild, byte[] bgFullRebuild,
                           int playerX, int playerY, int cameraX, int cameraY,
                           List<String> levelArtKeys, int patternCount) {
    }

    private static Outcome runFireTransition(boolean preparedLoads) throws Exception {
        LevelLoadPreparer.setEnabledForTests(preparedLoads);
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        GameServices.sprites().addSprite(sonic);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sonic);
        camera.setFrozen(false);
        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);
        Sonic3kAIZEvents aizEvents = ((Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider()).getAizEventsForTest();
        assertNotNull(aizEvents);

        // Known-good ground start shared with TestS3kAiz1SkipHeadless, short of
        // the $2E00 fire-overlay staging threshold.
        sonic.setX((short) 11164);
        sonic.setY((short) 951);
        sonic.setAir(false);
        sonic.setControlLocked(false);
        camera.updatePosition(true);
        sonic.updateSensors(sonic.getX(), sonic.getY());

        HeadlessTestRunner runner = new HeadlessTestRunner(sonic);
        int preparedInstallsBefore = levelManager.preparedLevelInstallCount();
        boolean fired = false;
        int reloadFrame = -1;
        int settleFrames = 0;
        byte[] fgAtReload = null;
        byte[] bgAtReload = null;
        int bgFullRebuildsAfterReload = -1;
        boolean prebuiltLeftOver = false;
        for (int frame = 1; frame <= MAX_FRAMES; frame++) {
            runner.stepFrame(false, false, false, !fired, false);
            LevelManager current = GameServices.level();
            current.ensureForegroundTilemapData();
            current.ensureBackgroundTilemapData();
            if (!fired && aizEvents.isFireOverlayTilesLoaded()) {
                // The boss defeat sets Events_fg_5; drive it directly.
                aizEvents.setEventsFg5(true);
                fired = true;
            }
            if (fired && reloadFrame < 0 && current.getCurrentAct() == 1) {
                reloadFrame = frame;
                LevelTilemapManager tilemaps = current.getTilemapManager();
                fgAtReload = tilemaps.getForegroundTilemapData().clone();
                bgAtReload = tilemaps.getBackgroundTilemapData().clone();
                bgFullRebuildsAfterReload = tilemaps.bgFullRebuildCount;
                prebuiltLeftOver = current.hasPrebuiltTilemaps();
            }
            if (reloadFrame > 0 && ++settleFrames >= 8) {
                break;
            }
        }
        assertTrue(fired, "fire overlay never staged; camera=0x"
                + Integer.toHexString(camera.getX() & 0xFFFF));
        assertTrue(reloadFrame > 0, "act 2 reload never happened");

        LevelManager current = GameServices.level();
        LevelTilemapManager tilemaps = current.getTilemapManager();
        int bgRebuilds = Math.max(bgFullRebuildsAfterReload, tilemaps.bgFullRebuildCount);
        current.invalidateAllTilemaps();
        current.ensureForegroundTilemapData();
        current.ensureBackgroundTilemapData();
        byte[] fgFull = tilemaps.getForegroundTilemapData().clone();
        byte[] bgFull = tilemaps.getBackgroundTilemapData().clone();

        List<String> artKeys = List.of();
        var renderManager = current.getObjectRenderManager();
        if (renderManager != null
                && renderManager.getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            artKeys = provider.getAffectedRendererKeys(
                    List.of(new Sonic3kPlcLoader.TileRange(0, 0x800)));
        }
        return new Outcome(reloadFrame, current.preparedLevelInstallCount() - preparedInstallsBefore, bgRebuilds,
                prebuiltLeftOver, fgAtReload, bgAtReload, fgFull, bgFull,
                sonic.getCentreX(), sonic.getCentreY(),
                camera.getX() & 0xFFFF, camera.getY() & 0xFFFF,
                artKeys, current.getCurrentLevel().getPatternCount());
    }
}
