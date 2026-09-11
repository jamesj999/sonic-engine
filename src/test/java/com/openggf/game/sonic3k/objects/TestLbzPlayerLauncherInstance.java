package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLbzPlayerLauncherInstance {
    @Test
    void spriteOnScreenTestIgnoresVerticalDistanceInsideNativeCoarseWindow() {
        LbzPlayerLauncherInstance launcher = launcherAt(0x0300, 0x0800, 0x0100);

        launcher.update(0, null);

        assertFalse(launcher.isDestroyed(),
                "Sprite_OnScreen_Test is a coarse horizontal lifetime check");
    }

    @Test
    void spriteOnScreenTestDeletesLauncherBehindCoarseBackBoundary() {
        LbzPlayerLauncherInstance launcher = launcherAt(0x0100, 0x0200, 0x0300);

        launcher.update(0, null);

        assertTrue(launcher.isDestroyed());
    }

    private static LbzPlayerLauncherInstance launcherAt(int x, int y, int cameraX) {
        LbzPlayerLauncherInstance launcher = new LbzPlayerLauncherInstance(
                new ObjectSpawn(x, y, 0x15, 0, 0, false, 0));
        ObjectServices services = mock(ObjectServices.class, RETURNS_DEEP_STUBS);
        when(services.camera().getX()).thenReturn((short) cameraX);
        launcher.setServices(services);
        return launcher;
    }
}
