package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.objects.SpringHelper;
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
 * Tests collision model differentiation between Sonic 1 (unified) and Sonic 2/3K (dual-path).
 * <p>
 * Sonic 1 locks solid bits to 0x0C/0x0D — setters are no-ops.
 * Sonic 2/3K allows dynamic switching via plane switchers and springs.
 */
class TestCollisionModel {

    @BeforeEach
    void setUp() {
        ensureBootstrapRuntime();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    // ========================================
    // Feature set constants (parameterized)
    // ========================================

    static Stream<Arguments> featureSetProvider() {
        return Stream.of(
                Arguments.of(GameRules.SONIC_1, CollisionModel.UNIFIED, false, "S1"),
                Arguments.of(GameRules.SONIC_2, CollisionModel.DUAL_PATH, true, "S2"),
                Arguments.of(GameRules.SONIC_3K, CollisionModel.DUAL_PATH, true, "S3K")
        );
    }

    @ParameterizedTest(name = "{3} collision model")
    @MethodSource("featureSetProvider")
    void featureSetCollisionModel(GameRules fs, CollisionModel expectedModel,
                                  boolean expectedHasDual, String label) {
        assertEquals(expectedModel, fs.collision().collisionModel(), label + " collision model");
        assertEquals(expectedHasDual, (fs.collision().collisionModel() == CollisionModel.DUAL_PATH), label + " dual paths");
    }

    // ========================================
    // Setter guarding (parameterized)
    // ========================================

    static Stream<Arguments> setterGuardProvider() {
        ensureBootstrapRuntime();
        return Stream.of(
                // S1: setters are no-ops
                Arguments.of(new Sonic1GameModule(), "topSolidBit", 0x0C, (byte) 0x0E, 0x0C, "S1 top ignored"),
                Arguments.of(new Sonic1GameModule(), "lrbSolidBit", 0x0D, (byte) 0x0F, 0x0D, "S1 lrb ignored"),
                // S2: setters work
                Arguments.of(new Sonic2GameModule(), "topSolidBit", 0x0C, (byte) 0x0E, 0x0E, "S2 top works"),
                Arguments.of(new Sonic2GameModule(), "lrbSolidBit", 0x0D, (byte) 0x0F, 0x0F, "S2 lrb works")
        );
    }

    @ParameterizedTest(name = "{4}")
    @MethodSource("setterGuardProvider")
    void solidBitSetterGuarding(GameModule module, String setter, int initial,
                                byte newValue, int expected, String label) {
        GameModuleRegistry.setCurrent(module);
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);

        if ("topSolidBit".equals(setter)) {
            assertEquals(initial, sprite.getTopSolidBit(), "Initial " + setter);
            sprite.setTopSolidBit(newValue);
            assertEquals(expected, sprite.getTopSolidBit(), label);
        } else {
            assertEquals(initial, sprite.getLrbSolidBit(), "Initial " + setter);
            sprite.setLrbSolidBit(newValue);
            assertEquals(expected, sprite.getLrbSolidBit(), label);
        }
    }

    // ========================================
    // Module switch
    // ========================================

    @Test
    void moduleSwitch_s2ToS1_settersBecomGuarded() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);

        sprite.setTopSolidBit((byte) 0x0E);
        assertEquals(0x0E, sprite.getTopSolidBit(), "S2 allows change");

        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        sprite.resetState();

        assertEquals(0x0C, sprite.getTopSolidBit(), "After reset, topSolidBit default");

        sprite.setTopSolidBit((byte) 0x0E);
        assertEquals(0x0C, sprite.getTopSolidBit(), "S1 guards setter after switch");
    }

    // ========================================
    // SpringHelper guarding (parameterized)
    // ========================================

    static Stream<Arguments> springHelperProvider() {
        ensureBootstrapRuntime();
        return Stream.of(
                Arguments.of(new Sonic1GameModule(), 0x0C, 0x0D, "S1 spring no-op"),
                Arguments.of(new Sonic2GameModule(), 0x0E, 0x0F, "S2 spring works")
        );
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("springHelperProvider")
    void springHelperLayerBits(GameModule module, int expectedTop, int expectedLrb, String label) {
        GameModuleRegistry.setCurrent(module);
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);

        SpringHelper.applyCollisionLayerBits(sprite, 0x08);
        assertEquals(expectedTop, sprite.getTopSolidBit(), label + " topSolidBit");
        assertEquals(expectedLrb, sprite.getLrbSolidBit(), label + " lrbSolidBit");
    }

    private static void ensureBootstrapRuntime() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        if (SessionManager.getCurrentGameplayMode() == null) {
            TestEnvironment.activeGameplayMode();
        }
    }
}


