package com.openggf.game.sonic3k;

import com.openggf.GameLoop;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.BonusStageState;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.WaterSystem;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestBonusStageReturnWaterRestore {
    private static final int ZONE_HCZ = Sonic3kZoneIds.ZONE_HCZ;
    private static final int ACT_1 = 0;
    private static final int LATE_HCZ1_CAMERA_X = 0x3600;
    private static final int HCZ1_LATE_WATER_LEVEL = 0x06A0;

    @BeforeAll
    public static void configure() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @Test
    public void aiz1BonusReturnOverridesFreshIntroSuppressionWithoutSkippedOwner() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        GraphicsManager.getInstance().initHeadless();
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_AIZ, ACT_1)
                .build();

        LevelManager levelManager = GameServices.level();
        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                GameModuleRegistry.getCurrent().getObjectArtProvider();
        var beforeReturn = artProvider.capture();
        assertFalse(levelManager.isTitleCardRequested(),
                "fresh AIZ intro suppression should begin without a title request");
        assertEquals(-1, beforeReturn.titleCardTeardownTicks(),
                "fresh AIZ intro suppression should not create a skipped-title owner");

        levelManager.setBonusStageReturnCheckpointIndex(0);
        try {
            LevelLoadContext returnLoad = new LevelLoadContext();
            returnLoad.setShowTitleCard(true);
            levelManager.requestTitleCardIfNeeded(returnLoad);
        } finally {
            levelManager.clearBonusStageReturn();
        }

        assertTrue(levelManager.isTitleCardRequested(),
                "bonus return must preserve the mandatory title request for GameLoop");
        assertEquals(beforeReturn, artProvider.capture(),
                "requesting the real return title must not start a competing skipped owner");
    }

    @Test
    public void bonusStageReturn_restoresSavedWaterLevelImmediately() throws Exception {
        GraphicsManager.getInstance().initHeadless();
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_HCZ, ACT_1)
                .build();

        InputHandler inputHandler = new InputHandler();
        GameLoop loop = new GameLoop(inputHandler);

        LevelManager levelManager = GameServices.level();
        WaterSystem waterSystem = GameServices.water();
        Camera camera = fixture.camera();
        AbstractPlayableSprite player = fixture.sprite();

        camera.setX((short) LATE_HCZ1_CAMERA_X);

        waterSystem.updateDynamic(ZONE_HCZ, ACT_1, camera.getX(), camera.getY());
        waterSystem.update();

        assertEquals(HCZ1_LATE_WATER_LEVEL, waterSystem.getWaterLevelY(ZONE_HCZ, ACT_1),
                "Sanity check: HCZ1 water should already be at the later threshold before saving");

        int zoneAndAct = (levelManager.getCurrentZone() << 8) | levelManager.getCurrentAct();
        int apparentZoneAndAct = (levelManager.getCurrentZone() << 8) | levelManager.getApparentAct();
        int ringCount = levelManager.getLevelGamestate() != null
                ? levelManager.getLevelGamestate().getRings()
                : 0;
        long timerFrames = levelManager.getLevelGamestate() != null
                ? levelManager.getLevelGamestate().getTimerFrames()
                : 0;

        BonusStageState savedState = new BonusStageState(
                zoneAndAct,
                apparentZoneAndAct,
                ringCount,
                0,
                0,
                0,
                0,
                0,
                player.getCentreX(),
                player.getCentreY(),
                camera.getX(),
                camera.getY(),
                player.getTopSolidBit(),
                player.getLrbSolidBit(),
                camera.getMaxY(),
                timerFrames,
                HCZ1_LATE_WATER_LEVEL
        );

        BonusStageProvider provider = GameModuleRegistry.getCurrent().getBonusStageProvider();

        Method doEnterBonusStage = GameLoop.class.getDeclaredMethod(
                "doEnterBonusStage", BonusStageProvider.class, BonusStageType.class, BonusStageState.class);
        doEnterBonusStage.setAccessible(true);
        doEnterBonusStage.invoke(loop, provider, BonusStageType.GLOWING_SPHERE, savedState);

        Method doExitBonusStage = GameLoop.class.getDeclaredMethod(
                "doExitBonusStage", BonusStageProvider.class, BonusStageState.class);
        doExitBonusStage.setAccessible(true);
        doExitBonusStage.invoke(loop, provider, savedState);

        assertEquals(HCZ1_LATE_WATER_LEVEL, waterSystem.getWaterLevelY(ZONE_HCZ, ACT_1),
                "Bonus-stage return should restore the saved water level immediately");
    }
}
