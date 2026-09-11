package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.EngineServices;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.openggf.tests.TestablePlayableSprite;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests spindash feature gating per game module.
 * S1 module: spindash should be disabled and never enter spindash state.
 * S2 module: spindash should be enabled.
 */
class TestSpindashGating {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    static Stream<Arguments> spindashGatingProvider() {
        return Stream.of(
                Arguments.of(new Sonic1GameModule(), false, "S1 spindash disabled"),
                Arguments.of(new Sonic2GameModule(), true, "S2 spindash enabled")
        );
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("spindashGatingProvider")
    void spindashEnabledMatchesModule(GameModule module, boolean expectedEnabled, String label) {
        GameModuleRegistry.setCurrent(module);
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);

        GameRules fs = sprite.getGameRules();
        assertNotNull(fs, "Feature set should be set");
        assertEquals(expectedEnabled, fs.playerCapability().spindashEnabled(), label);

        if (expectedEnabled) {
            assertNotNull(fs.playerCapability().spindashSpeedTable(), label + " speed table");
            assertEquals(9, fs.playerCapability().spindashSpeedTable().length, label + " speed table entries");
        } else {
            assertNull(fs.playerCapability().spindashSpeedTable(), label + " no speed table");
        }
    }

    @Test
    void sonic1Module_spindashFlagNeverSet() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);

        assertFalse(sprite.getSpindash(), "Spindash should not be active");
    }

    @Test
    void moduleSwitch_updatesGameRules() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        assertTrue(sprite.getGameRules().playerCapability().spindashEnabled(), "Initially S2 spindash");

        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        sprite.resetState();
        assertFalse(sprite.getGameRules().playerCapability().spindashEnabled(),
                "After switch to S1, spindash disabled");
    }
}


