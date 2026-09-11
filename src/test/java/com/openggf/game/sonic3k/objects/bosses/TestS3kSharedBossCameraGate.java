package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.camera.Camera;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSharedBossCameraGate {
    private static final S3kSharedBossCameraGate.LockBounds LOCK =
            new S3kSharedBossCameraGate.LockBounds(0x0200, 0x0220, 0x1200, 0x1240);

    @Test
    void leftApproachTracksLiveCameraXUntilNativeLockTarget() {
        Camera camera = new Camera();
        camera.setX((short) 0x1180);
        camera.setY((short) 0x0200);
        camera.setMinX((short) 0x1100);
        camera.setMaxX((short) 0x1300);
        S3kSharedBossCameraGate gate = new S3kSharedBossCameraGate();
        gate.begin(camera, LOCK, 0);

        assertFalse(gate.update(camera, null));
        assertEquals(0x1180, camera.getMinX() & 0xFFFF,
                "loc_85D06 copies Camera_X_pos into Camera_min_X_pos while approaching from the left");
        assertEquals(0x1180, camera.getMinXTarget() & 0xFFFF,
                "the shared Camera setter must not leave an independent easing target ahead of Camera_X_pos");

        camera.setX((short) 0x1200);
        assertTrue(gate.update(camera, null));
        assertEquals(0x1200, camera.getMinX() & 0xFFFF);
        assertEquals(0x1240, camera.getMaxX() & 0xFFFF);
    }

    @Test
    void belowRightApproachKeepsTimerAndAxesIndependentAndStartsMusicOnce() {
        Camera camera = new Camera();
        camera.setX((short) 0x1300);
        camera.setY((short) 0x0300);
        camera.setMinX((short) 0x1000);
        camera.setMaxX((short) 0x1400);
        camera.setMinY((short) 0x0100);
        camera.setMaxYTarget((short) 0x0400);
        AtomicInteger musicStarts = new AtomicInteger();
        S3kSharedBossCameraGate gate = new S3kSharedBossCameraGate();
        gate.begin(camera, LOCK, 2);

        assertFalse(gate.update(camera, musicStarts::incrementAndGet));
        assertEquals(0x1300, camera.getMaxX() & 0xFFFF,
                "right approach ratchets Camera_max_X_pos to the live camera position");
        assertEquals(0x0100, camera.getMinY() & 0xFFFF,
                "below approach remains unlocked above target max Y plus $60");
        assertEquals(0, musicStarts.get());

        camera.setX((short) 0x1240);
        camera.setY((short) 0x0280);
        assertFalse(gate.update(camera, musicStarts::incrementAndGet),
                "both axes may finish before the independent music timer");
        assertEquals(0x1200, camera.getMinX() & 0xFFFF);
        assertEquals(0x1240, camera.getMaxX() & 0xFFFF);
        assertEquals(0x0200, camera.getMinY() & 0xFFFF);
        assertEquals(0x0220, camera.getMaxYTarget() & 0xFFFF);
        assertEquals(0, musicStarts.get());

        assertTrue(gate.update(camera, musicStarts::incrementAndGet));
        assertEquals(1, musicStarts.get());
        assertTrue(gate.update(camera, musicStarts::incrementAndGet));
        assertEquals(1, musicStarts.get(), "completed continuation must not replay music");
    }
}
