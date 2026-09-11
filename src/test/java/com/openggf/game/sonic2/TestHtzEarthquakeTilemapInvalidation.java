package com.openggf.game.sonic2;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.LevelTilemapManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestHtzEarthquakeTilemapInvalidation {
    private static final int HTZ_ZONE = 4;
    private static final int HTZ_ACT = 0;

    private LevelManager levelManager;
    private LevelTilemapManager tilemapManager;

    @BeforeEach
    void setUp() throws Exception {
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
        assertNotNull(tilemapManager, "TilemapManager must exist after level load");
    }

    @Test
    void enteringEarthquakeMarksBackgroundTilemapDirtyForHtzOverlayRebuild() throws Exception {
        ensureBackgroundTilemapData();
        assertFalse(tilemapManager.isBackgroundTilemapDirty(),
                "Initial background build should clear the dirty flag");

        ((Sonic2LevelEventManager) GameServices.module().getLevelEventProvider())
                .getHtzEvents().setEarthquakeActive(true);

        assertTrue(tilemapManager.isBackgroundTilemapDirty(),
                "Entering HTZ earthquake mode must dirty the BG tilemap so the cave/lava overlay rebuilds");
    }

    private void ensureBackgroundTilemapData() throws Exception {
        Method ensureBg = LevelManager.class.getDeclaredMethod("ensureBackgroundTilemapData");
        ensureBg.setAccessible(true);
        ensureBg.invoke(levelManager);
    }
}
