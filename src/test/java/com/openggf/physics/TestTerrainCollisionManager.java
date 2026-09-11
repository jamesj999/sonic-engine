package com.openggf.physics;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestTerrainCollisionManager {

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void testSensorResultOrdering() {
        AbstractPlayableSprite sprite = new AbstractPlayableSprite("test", (short) 0, (short) 0) {
            @Override
            protected void defineSpeeds() {
            }

            @Override
            protected void createSensorLines() {
            }

            @Override
            public void draw() {
            }
        };

        SensorResult first = new SensorResult((byte) 1, (byte) 0, 0, Direction.DOWN);
        SensorResult second = new SensorResult((byte) 2, (byte) 0, 0, Direction.DOWN);

        Sensor[] sensors = new Sensor[] {
                new TestSensor(sprite, first),
                new TestSensor(sprite, second)
        };

        SensorResult[] results = GameServices.terrainCollision().getSensorResult(sensors);
        assertSame(first, results[0]);
        assertSame(second, results[1]);
    }

    private static final class TestSensor extends Sensor {
        private final SensorResult result;

        private TestSensor(AbstractPlayableSprite sprite, SensorResult result) {
            super(sprite, Direction.DOWN, (byte) 0, (byte) 0, true);
            this.result = result;
        }

        @Override
        protected SensorResult doScan(short dx, short dy) {
            return result;
        }
    }
}


