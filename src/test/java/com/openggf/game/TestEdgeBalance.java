package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
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
 * Tests edge balance feature gating per game module.
 *
 * S1: Simple balance - single animation state, always forces facing toward edge,
 *     no precarious check. ROM: s1disasm/_incObj/01 Sonic.asm:354-375
 *
 * S2/S3K: Extended balance - 4 states (facing toward/away x safe/precarious),
 *         secondary floor probe for precarious detection.
 *         ROM: s2.asm:36246-36373
 */
class TestEdgeBalance {

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        GameModuleRegistry.reset();
    }

    static Stream<Arguments> edgeBalanceProvider() {
        return Stream.of(
                Arguments.of(new Sonic1GameModule(), false, "S1"),
                Arguments.of(new Sonic2GameModule(), true, "S2"),
                Arguments.of(new Sonic3kGameModule(), true, "S3K")
        );
    }

    @ParameterizedTest(name = "{2} extendedEdgeBalance = {1}")
    @MethodSource("edgeBalanceProvider")
    void extendedEdgeBalanceMatchesModule(GameModule module, boolean expected, String label) {
        GameModuleRegistry.setCurrent(module);
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);

        GameRules fs = sprite.getGameRules();
        assertNotNull(fs, "Feature set should be set");
        assertEquals(expected, fs.playerAnimation().extendedEdgeBalance(), label + " extended edge balance");
    }

    @Test
    void sonic1_singleBalanceState() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);

        GameRules fs = sprite.getGameRules();
        assertFalse(fs.playerAnimation().extendedEdgeBalance(), "S1 balance mode should be simple (not extended)");

        assertEquals(0, sprite.getBalanceState(), "Initial balance state should be 0");
        assertFalse(sprite.isBalancing(), "Should not be balancing initially");
    }

}


