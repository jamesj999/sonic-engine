package com.openggf.game.sonic2.events;

import com.openggf.camera.Camera;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSonic2ZoneEvents {

    @Test
    void sidekickBoundsSyncMirrorsRomTailsMaxYTargetWrite() {
        Camera camera = new Camera();
        camera.setMinX((short) 0x2A00);
        camera.setMaxX((short) 0x2B40);
        camera.setMaxY((short) 0x062C);
        camera.setMaxYTarget((short) 0x05D0);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails_p2", (short) 0, (short) 0);
        tails.setCpuControlled(true);
        tails.setCpuController(new SidekickCpuController(tails, null));

        SpriteManager sprites = mock(SpriteManager.class);
        when(sprites.getSidekicks()).thenReturn(List.of(tails));

        TestableZoneEvents events = new TestableZoneEvents(camera, sprites);

        events.syncForTest();

        assertEquals(0x2A00, tails.getCpuController().getMinXBound(Integer.MIN_VALUE));
        assertEquals(0x2B40, tails.getCpuController().getMaxXBound(Integer.MIN_VALUE));
        assertEquals(0x05D0, tails.getCpuController().getMaxYBound(Integer.MIN_VALUE),
                "S2 event sync mirrors Tails_Max_Y_pos writes from Camera_Max_Y_pos_target, "
                        + "not Sonic_LevelBound's max(current,target) kill-plane fix");
    }

    private static final class TestableZoneEvents extends Sonic2ZoneEvents {
        private final Camera camera;
        private final SpriteManager sprites;

        private TestableZoneEvents(Camera camera, SpriteManager sprites) {
            this.camera = camera;
            this.sprites = sprites;
        }

        @Override
        public void update(int act, int frameCounter) {
        }

        @Override
        protected Camera camera() {
            return camera;
        }

        @Override
        protected SpriteManager spriteManager() {
            return sprites;
        }

        private void syncForTest() {
            syncSidekickBoundsToCamera();
        }
    }
}
