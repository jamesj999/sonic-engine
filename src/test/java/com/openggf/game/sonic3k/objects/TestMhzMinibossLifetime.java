package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.audio.AudioManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestMhzMinibossLifetime {
    @BeforeEach
    void initializeGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @ParameterizedTest
    @ValueSource(ints = {-0x400, 0x400})
    void offscreenBossRetainsItsSlotAndResumesWhenCameraReturns(int cameraDelta) {
        Camera camera = new Camera();
        camera.setX((short) 0x4298);
        camera.setY((short) 0x0710);
        ObjectManager[] holder = new ObjectManager[1];
        AudioManager audio = mock(AudioManager.class);
        ObjectPlayerQuery players = new ObjectPlayerQuery(() -> null, List::of);
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public AudioManager audioManager() { return audio; }
            @Override public ObjectPlayerQuery playerQuery() { return players; }
            @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
            @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
        };
        services.zoneRuntimeRegistry().install(new MhzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS));
        ObjectManager manager = new ObjectManager(List.of(), new Sonic3kObjectRegistry(),
                0, null, null, GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;
        manager.reset(0x4298);
        MhzMinibossInstance boss = manager.createDynamicObject(() -> new MhzMinibossInstance(
                new ObjectSpawn(0, 0, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0)));
        manager.update(0x4298, null, List.of(), 0, false);
        int slot = boss.getSlotIndex();
        List<MhzMinibossFlameInstance> flames = manager.getActiveObjects().stream()
                .filter(MhzMinibossFlameInstance.class::isInstance)
                .map(MhzMinibossFlameInstance.class::cast).toList();
        assertEquals(2, flames.size());
        // loc_75392 waits on the previous Draw_And_Touch_Sprite visibility bit.
        boss.getState().routine = 8;
        boss.getState().xVel = 0x400;
        boss.getState().yVel = 0;
        boss.getState().y = 0x0790;
        boss.getState().yFixed = 0x0790 << 16;
        boss.setCustomFlag(0x2E, 0x4F);
        int waitingX = boss.getX();
        camera.setX((short) (0x4298 + cameraDelta));
        AbstractObjectInstance.updateCameraBounds(camera.getX() & 0xFFFF, 0x710, 320, 224, 0);
        manager.refreshPostCameraRenderState();
        assertEquals(0, boss.getState().renderFlags & 0x80);

        manager.update(camera.getX() & 0xFFFF, null, List.of(), 1, false);

        assertTrue(manager.getActiveObjects().contains(boss),
                "Obj_MHZMiniboss ends in Draw_And_Touch_Sprite, not an out-of-range deletion helper");
        assertEquals(slot, boss.getSlotIndex());
        assertTrue(manager.getActiveObjects().containsAll(flames),
                "both parent-owned thrusters must survive the same offscreen interval");
        assertFalse(boss.isDestroyed());
        assertEquals(waitingX, boss.getX());
        assertEquals(0x4F, boss.getCustomFlag(0x2E));

        camera.setX((short) 0x4298);
        AbstractObjectInstance.updateCameraBounds(0x4298, 0x710, 320, 224, 0);
        manager.refreshPostCameraRenderState();
        manager.update(0x4298, null, List.of(), 2, false);
        assertEquals(waitingX + 4, boss.getX(), "the same live boss resumes its native dash");
        assertEquals(0x4E, boss.getCustomFlag(0x2E));

        boss.setDestroyed(true);
        manager.update(0x4298, null, List.of(), 3, false);
        manager.update(0x4298, null, List.of(), 4, false);
        assertFalse(manager.getActiveObjects().contains(boss));
        assertTrue(flames.stream().noneMatch(manager.getActiveObjects()::contains),
                "persistence must not prevent explicit boss/child cleanup");
    }
}
