package com.openggf.physics;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestTerrainCollisionManagerReset {

    @BeforeEach
    void setUp() {
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    /** Sensor stub that returns a fixed result without touching sprite state. */
    private static final class StubSensor extends Sensor {
        StubSensor() {
            super(null, Direction.DOWN, (byte) 0, (byte) 0, true);
        }

        @Override
        protected SensorResult doScan(short dx, short dy) {
            return new SensorResult((byte) 0, (byte) 1, 2, Direction.DOWN);
        }
    }

    @Test
    void resetStateClearsPooledResults() {
        TerrainCollisionManager tcm = GameServices.terrainCollision();

        // Populate the pooled-result buffer with a real scan so resetState has
        // something to clear.
        SensorResult[] results = tcm.getSensorResult(new Sensor[] { new StubSensor() });
        assertNotNull(results[0], "scan must populate the pooled buffer before reset");

        tcm.resetState();

        // Every slot of the pooled buffer must be cleared after reset.
        SensorResult[] pooled = tcm.pooledResultsForTest();
        for (int i = 0; i < pooled.length; i++) {
            assertNull(pooled[i], "pooled slot " + i + " must be null after resetState");
        }
    }
}


