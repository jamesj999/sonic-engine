package com.openggf.game;

import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: verify that getter values under the new PhysicsModifiers
 * path match the original hardcoded Sonic 2 values with/without water/shoes.
 */
class TestPhysicsProfileRegression {

    private TestableSprite sprite;

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        sprite = new TestableSprite("test", (short) 100, (short) 100);
    }

    @ParameterizedTest(name = "normal {0} = {1}")
    @CsvSource({
            "runAccel,    12",
            "runDecel,   128",
            "friction,    12",
            "max,       1536",
            "jump,      1664"
    })
    void normalCondition(String getter, int expected) {
        assertEquals(expected, callGetter(sprite, getter));
    }

    @ParameterizedTest(name = "water {0} = {1}")
    @CsvSource({
            "runAccel,     6",
            "runDecel,    64",
            "friction,     6",
            "max,        768",
            "jump,       896"
    })
    void waterCondition(String getter, int expected) {
        sprite.setInWater(true);
        assertEquals(expected, callGetter(sprite, getter));
    }

    @ParameterizedTest(name = "speedShoes {0} = {1}")
    @CsvSource({
            "runAccel,    24",
            "runDecel,   128",
            "friction,    24",
            "max,       3072"
    })
    void speedShoesCondition(String getter, int expected) {
        sprite.setTestSpeedShoes(true);
        assertEquals(expected, callGetter(sprite, getter));
    }

    @ParameterizedTest(name = "water+shoes {0} = {1}")
    @CsvSource({
            "runAccel,     6",
            "max,        768"
    })
    void waterAndShoesCondition(String getter, int expected) {
        sprite.setInWater(true);
        sprite.setTestSpeedShoes(true);
        assertEquals(expected, callGetter(sprite, getter));
    }

    @Test
    void gameRules_areSet() {
        assertNotNull(sprite.getGameRules(), "Feature set should be populated");
        assertTrue(sprite.getGameRules().playerCapability().spindashEnabled(), "S2 spindash enabled");
    }

    @Test
    void physicsModifiers_isSet() {
        assertNotNull(sprite.getPhysicsModifiers(), "Modifiers should be populated");
        assertEquals(0x28, sprite.getPhysicsModifiers().waterGravityReduction(),
                "Water gravity reduction");
        assertEquals(0x20, sprite.getPhysicsModifiers().waterHurtGravityReduction(),
                "Water hurt gravity reduction");
    }

    private static int callGetter(TestableSprite s, String name) {
        return switch (name) {
            case "runAccel" -> s.getRunAccel();
            case "runDecel" -> s.getRunDecel();
            case "friction" -> s.getFriction();
            case "max" -> s.getMax();
            case "jump" -> s.getJump();
            default -> throw new IllegalArgumentException("Unknown getter: " + name);
        };
    }

    /**
     * Minimal test subclass.
     */
    private static class TestableSprite extends AbstractPlayableSprite {
        public TestableSprite(String code, short x, short y) {
            super(code, x, y);
        }

        @Override
        public void draw() {
        }

        @Override
        public void defineSpeeds() {
            runAccel = 12;
            runDecel = 128;
            friction = 12;
            max = 1536;
            jump = 1664;
            slopeRunning = 32;
            slopeRollingDown = 80;
            slopeRollingUp = 20;
            rollDecel = 32;
            minStartRollSpeed = 128;
            minRollSpeed = 128;
            maxRoll = 4096;
            rollHeight = 28;
            runHeight = 38;
            standXRadius = 9;
            standYRadius = 19;
            rollXRadius = 7;
            rollYRadius = 14;
        }

        @Override
        protected void createSensorLines() {
        }

        public void setTestSpeedShoes(boolean shoes) {
            this.speedShoes = shoes;
        }
    }
}


