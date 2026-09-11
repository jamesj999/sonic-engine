package com.openggf.sprites.managers;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.physics.Sensor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSpriteManagerCollisionOrder {

    private GameModule previousModule;

    @BeforeEach
    public void setUp() {
        previousModule = GameModuleRegistry.getCurrent();
    }

    @AfterEach
    public void tearDown() {
        if (previousModule != null) {
            GameModuleRegistry.setCurrent(previousModule);
        } else {
            GameModuleRegistry.reset();
        }
    }

    @Test
    public void testSonic1UsesPostMovementSolidPass() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        TestPlayableSprite sprite = new TestPlayableSprite();

        assertTrue(SpriteManager.requiresPostMovementSolidPass(sprite));
    }

    @Test
    public void testSonic2DoesNotUsePostMovementSolidPass() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestPlayableSprite sprite = new TestPlayableSprite();

        assertFalse(SpriteManager.requiresPostMovementSolidPass(sprite));
    }

    @Test
    public void testNullSpriteDoesNotUsePostMovementSolidPass() {
        assertFalse(SpriteManager.requiresPostMovementSolidPass(null));
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite() {
            super("TEST", (short) 0, (short) 0);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 0;
            runHeight = 0;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void draw() {
            // No-op for tests.
        }
    }
}


