package com.openggf.game.sonic2.objects;

import com.openggf.audio.GameSound;
import com.openggf.camera.Camera;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLaserObjectInstance {

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void firesWhenRomRenderBoundsOverlapViewport() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        RecordingServices services = new RecordingServices();
        services.withCamera(cameraAt(0));
        LaserObjectInstance laser = new LaserObjectInstance(
                new ObjectSpawn(0x0170, 0x0080, Sonic2ObjectIds.LASER, 0x76, 0, false, 0));
        laser.setServices(services);

        laser.update(0, null);

        assertEquals(List.of(Sonic2AudioConstants.SFX_LARGE_LASER), services.soundIds,
                "ObjB9 waits on render_flags.on_screen, which BuildSprites sets from width_pixels=$60");
        assertEquals(0x0170, laser.getX(),
                "ObjB9 sets x_vel on the activation frame; ObjectMove starts on routine 4's next frame");
    }

    @Test
    void movesLeftAfterActivationFrame() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        RecordingServices services = new RecordingServices();
        services.withCamera(cameraAt(0));
        LaserObjectInstance laser = new LaserObjectInstance(
                new ObjectSpawn(0x0170, 0x0080, Sonic2ObjectIds.LASER, 0x76, 0, false, 0));
        laser.setServices(services);

        laser.update(0, null);
        laser.update(1, null);

        assertEquals(0x0160, laser.getX(),
                "ObjB9 routine 4 applies x_vel=-$1000 via ObjectMove after the activation frame");
        assertFalse(laser.isDestroyed());
    }

    private static Camera cameraAt(int x) {
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) x);
        return camera;
    }

    private static final class RecordingServices extends TestObjectServices {
        private final List<Integer> soundIds = new ArrayList<>();

        @Override
        public void playSfx(int soundId) {
            soundIds.add(soundId);
        }

        @Override
        public void playSfx(GameSound sound) {
        }
    }
}
