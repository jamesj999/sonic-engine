package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.SeamlessLevelTransitionRequest.TransitionType;
import com.openggf.level.WaterSystem;
import com.openggf.level.animation.AnimatedPaletteManager;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Documents and verifies the intentional omissions in
 * {@link LevelManager#executeActTransition}.
 *
 * <p>In the S3K ROM, seamless act transitions only reload layout and collision,
 * rebuild object/ring managers, apply coordinate offsets, restore camera
 * bounds, and reinitialize level events. They do not reinitialize unrelated
 * zone/game state such as water or game modules. Animated content is the
 * exception in this engine: our managers bind act-specific scripts at
 * construction time, so they must be refreshed when the act changes.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestActTransitionIntentionalSkips {
    private static final int ZONE_EHZ = 0;
    private static final int ACT_1 = 0;
    private static final int ACT_2 = 1;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_EHZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition((short) 96, (short) 655)
                .build();

        GameServices.camera().setFocusedSprite(fixture.sprite());
        GameServices.camera().setFrozen(false);
    }

    private SeamlessLevelTransitionRequest transitionToAct2() {
        return SeamlessLevelTransitionRequest
                .builder(TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(ZONE_EHZ, ACT_2)
                .preserveMusic(true)
                .build();
    }

    @Test
    public void waterSystemInstancePreservedAcrossTransition() throws Exception {
        WaterSystem before = GameServices.water();
        assertNotNull(before, "WaterSystem should exist before transition");

        GameServices.level().executeActTransition(transitionToAct2());

        WaterSystem after = GameServices.water();
        assertSame(before, after, "WaterSystem instance must survive act transition");
    }

    @Test
    public void zoneFeatureProviderPreservedAcrossTransition() throws Exception {
        LevelManager lm = GameServices.level();
        ZoneFeatureProvider before = lm.getZoneFeatureProvider();

        lm.executeActTransition(transitionToAct2());

        ZoneFeatureProvider after = lm.getZoneFeatureProvider();
        assertSame(before, after, "ZoneFeatureProvider must survive act transition");
    }

    @Test
    public void gameModulePreservedAcrossTransition() throws Exception {
        LevelManager lm = GameServices.level();
        GameModule beforeLm = lm.getGameModule();
        GameModule beforeRegistry = GameModuleRegistry.getCurrent();
        assertNotNull(beforeLm, "GameModule should exist before transition");
        assertNotNull(beforeRegistry, "GameModuleRegistry should have a module before transition");

        lm.executeActTransition(transitionToAct2());

        GameModule afterLm = lm.getGameModule();
        GameModule afterRegistry = GameModuleRegistry.getCurrent();
        assertSame(beforeLm, afterLm, "LevelManager's GameModule must survive act transition");
        assertSame(beforeRegistry, afterRegistry, "GameModuleRegistry's module must survive act transition");
    }

    @Test
    public void gameInstancePreservedAcrossTransition() throws Exception {
        LevelManager lm = GameServices.level();
        Object beforeGame = lm.getGame();
        assertNotNull(beforeGame, "Game should exist before transition");

        lm.executeActTransition(transitionToAct2());

        Object afterGame = lm.getGame();
        assertSame(beforeGame, afterGame, "Game instance must survive act transition");
    }

    @Test
    public void animatedPatternManagerReinitializedAcrossTransition() throws Exception {
        LevelManager lm = GameServices.level();
        AnimatedPatternManager before = lm.getAnimatedPatternManager();
        assertNotNull(before, "AnimatedPatternManager should exist before transition");

        lm.executeActTransition(transitionToAct2());

        AnimatedPatternManager after = lm.getAnimatedPatternManager();
        assertNotNull(after, "AnimatedPatternManager should exist after transition");
        assertNotSame(before, after, "AnimatedPatternManager must refresh for the new act");
    }

    @Test
    public void animatedPaletteManagerReinitializedAcrossTransition() throws Exception {
        LevelManager lm = GameServices.level();
        AnimatedPaletteManager before = lm.getAnimatedPaletteManager();
        assertNotNull(before, "AnimatedPaletteManager should exist before transition");

        lm.executeActTransition(transitionToAct2());

        AnimatedPaletteManager after = lm.getAnimatedPaletteManager();
        assertNotNull(after, "AnimatedPaletteManager should exist after transition");
        assertNotSame(before, after, "AnimatedPaletteManager must refresh for the new act");
    }

    @Test
    public void objectManagerRebuiltAcrossTransition() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager before = lm.getObjectManager();
        assertNotNull(before, "ObjectManager should exist before transition");

        lm.executeActTransition(transitionToAct2());

        ObjectManager after = lm.getObjectManager();
        assertNotNull(after, "ObjectManager should exist after transition");
        assertNotSame(before, after, "ObjectManager must be a new instance");
    }

    @Test
    public void levelEventsReinitializedAcrossTransition() throws Exception {
        LevelManager lm = GameServices.level();
        LevelEventProvider provider = GameModuleRegistry.getCurrent().getLevelEventProvider();
        assertNotNull(provider, "LevelEventProvider should exist");

        lm.executeActTransition(transitionToAct2());

        assertEquals(ZONE_EHZ, lm.getCurrentZone(), "Zone should be EHZ after transition");
        assertEquals(ACT_2, lm.getCurrentAct(), "Act should be 2 (index 1) after transition");

        LevelEventProvider afterProvider = GameModuleRegistry.getCurrent().getLevelEventProvider();
        assertSame(provider, afterProvider, "LevelEventProvider instance is reused");
    }
}



