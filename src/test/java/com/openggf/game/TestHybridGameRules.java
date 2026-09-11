package com.openggf.game;

import com.openggf.game.rules.CrossGameRuleComposer;
import com.openggf.game.rules.GameRules;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestHybridGameRules {

    @BeforeEach
    public void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        CrossGameFeatureProvider.getInstance().resetState();
    }

    @AfterEach
    public void tearDown() {
        CrossGameFeatureProvider.getInstance().resetState();
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    public void composerDonatesCapabilitiesWhilePreservingHostRuntimeRules() {
        DonorCapabilities donorCapabilities = new Sonic2GameModule().getDonorCapabilities();

        GameRules hybrid = CrossGameRuleComposer.compose(
                GameRules.SONIC_1,
                GameRules.SONIC_2,
                donorCapabilities);

        assertTrue(hybrid.playerCapability().spindashEnabled(), "Hybrid should enable donor spindash");
        assertNotNull(hybrid.playerCapability().spindashSpeedTable(), "Hybrid should have donor speed table");
        assertEquals(9, hybrid.playerCapability().spindashSpeedTable().length, "Speed table has 9 entries");

        assertSame(GameRules.SONIC_1.playerMovement(), hybrid.playerMovement());
        assertSame(GameRules.SONIC_1.collision(), hybrid.collision());
        assertSame(GameRules.SONIC_1.camera(), hybrid.camera());
        assertSame(GameRules.SONIC_1.ring(), hybrid.ring());
        assertEquals(CollisionModel.UNIFIED, hybrid.collision().collisionModel());
        assertTrue(hybrid.camera().waterShimmerEnabled());
    }

    @Test
    public void pureGameRulesStillDifferAtTheirTypedHomes() {
        assertFalse(GameRules.SONIC_1.playerCapability().spindashEnabled(), "S1 spindash disabled");
        assertTrue(GameRules.SONIC_2.playerCapability().spindashEnabled(), "S2 spindash enabled");
        assertEquals(CollisionModel.DUAL_PATH, GameRules.SONIC_2.collision().collisionModel(), "S2 has DUAL_PATH");
        assertEquals(120, GameRules.SONIC_2.camera().lookScrollDelay(), "S2 has look scroll delay");
    }

    @Test
    public void crossGameProviderNotActiveByDefault() {
        assertFalse(CrossGameFeatureProvider.isActive(), "CrossGameFeatureProvider should not be active by default");
    }

    @Test
    public void resetClearsActiveState() {
        CrossGameFeatureProvider.getInstance().resetState();
        assertFalse(CrossGameFeatureProvider.isActive(), "After reset, should not be active");
    }

    @Test
    public void s1SpriteGetsS1RulesWithoutCrossGame() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);

        GameRules rules = sprite.getGameRules();

        assertNotNull(rules, "Rules should be set");
        assertFalse(rules.playerCapability().spindashEnabled(), "S1 spindash disabled without cross-game");
        assertEquals(CollisionModel.UNIFIED, rules.collision().collisionModel(), "S1 collision model");
    }
}
