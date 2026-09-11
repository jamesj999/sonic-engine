package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.sonic3k.Sonic3kLevelAnimationManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@RequiresRom(SonicGame.SONIC_3K)
public class TestSonic3kActTransitionZoneFeatures {
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_AIZ, 0);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @BeforeEach
    public void setUp() {
        HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition((short) 0x2F10, (short) 0x0200)
                .startPositionIsCentre()
                .build();
    }

    @Test
    public void zoneFeatureProviderPreservedAcrossSonic3kActTransition() throws Exception {
        LevelManager levelManager = GameServices.level();
        ZoneFeatureProvider before = levelManager.getZoneFeatureProvider();
        assertNotNull(before, "ZoneFeatureProvider should exist before transition");

        levelManager.executeActTransition(SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(Sonic3kZoneIds.ZONE_AIZ, 1)
                .preserveMusic(true)
                .build());

        assertSame(before, levelManager.getZoneFeatureProvider(), "S3K act transitions must reinitialize the existing provider so AIZ fire curtain state survives");
    }

    @Test
    public void seamlessActTransitionCarriesAndAdvancesGlobalChangeRingFrameState() {
        LevelManager levelManager = GameServices.level();
        Sonic3kLevelAnimationManager before = assertInstanceOf(
                Sonic3kLevelAnimationManager.class,
                levelManager.getAnimatedPatternManager());
        before.update();
        before.update();
        int beforeTransition = before.aizVineAngleWord();

        levelManager.applySeamlessTransition(SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(Sonic3kZoneIds.ZONE_AIZ, 1)
                .preserveMusic(true)
                .build());

        Sonic3kLevelAnimationManager after = assertInstanceOf(
                Sonic3kLevelAnimationManager.class,
                levelManager.getAnimatedPatternManager());
        assertEquals((beforeTransition + 0x180) & 0xFFFF, after.aizVineAngleWord(),
                "ROM $FEBA survives Load_Level and ChangeRingFrame still runs on the transition-only frame");
    }
}
