package com.openggf.game.sonic2.events;

import com.openggf.camera.Camera;
import com.openggf.level.WaterSystem;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2CPZEvents {

    @Test
    void cpzWaterRiseUsesPlayerRomCentreX() {
        TestEnvironment.resetAll();

        Camera camera = new Camera();
        RecordingWaterSystem water = new RecordingWaterSystem();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setWidth(18);
        player.setHeight(38);
        player.setCentreX((short) 0x1E80);
        player.setCentreY((short) 0x0400);
        camera.setFocusedSprite(player);

        Sonic2CPZEvents events = new TestableCPZEvents(camera, water);
        events.init(1);
        events.update(1, 1);

        assertTrue(events.isCpzWaterTriggered(),
                "CPZ water trigger compares against player x_pos, not top-left sprite bounds");
        assertEquals(0x0D, water.zoneId);
        assertEquals(1, water.actId);
        assertEquals(0x510, water.targetY);
    }

    private static final class TestableCPZEvents extends Sonic2CPZEvents {
        private final Camera camera;
        private final WaterSystem water;

        private TestableCPZEvents(Camera camera, WaterSystem water) {
            this.camera = camera;
            this.water = water;
        }

        @Override
        protected Camera camera() {
            return camera;
        }

        @Override
        protected WaterSystem waterSystem() {
            return water;
        }
    }

    private static final class RecordingWaterSystem extends WaterSystem {
        private int zoneId = -1;
        private int actId = -1;
        private int targetY = -1;

        @Override
        public void setWaterLevelTarget(int zoneId, int actId, int targetY) {
            this.zoneId = zoneId;
            this.actId = actId;
            this.targetY = targetY;
        }
    }
}
