package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.objects.boss.BossChildComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Off-screen persistence regression test for the EHZ Act 2 boss (Obj56).
 *
 * <p>The boss is event-spawned (not respawn-tracked) and oscillates between
 * {@code 0x28A0} and {@code 0x2B08} while the arena camera is locked at
 * {@code minX=0x28F0 / maxX=0x2940}. As the boss travels right, the leading
 * drillcone/spike ({@code body + 0x36}) crosses the engine's 128px-rounded
 * out-of-range cull threshold one chunk ahead of the body. As an ordinary
 * dynamic object it would then be unloaded and never rebuilt, leaving the boss
 * with its drillcone missing when it returns. The ROM keeps boss parts alive
 * with the boss for the whole fixed-arena fight, so the boss and its children
 * must report as persistent (immune to the off-screen cull).
 */
public class TestEHZBossPersistence {

    @BeforeEach
    public void setUp() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    public void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    public void bossAndChildrenSurviveOffscreenCull() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = cameraAtOrigin();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public Camera camera() {
                return camera;
            }
        };
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        ObjectSpawn spawn = new ObjectSpawn(0x29D0, 0x426, Sonic2ObjectIds.EHZ_BOSS,
                0x81, 0, false, 0);
        Sonic2EHZBossInstance boss =
                manager.createDynamicObject(() -> new Sonic2EHZBossInstance(spawn));

        assertTrue(boss.isPersistent(),
                "EHZ boss must be persistent so it survives oscillating past the arena edge");

        EHZBossSpike spike = findChild(boss, EHZBossSpike.class);
        EHZBossGroundVehicle vehicle = findChild(boss, EHZBossGroundVehicle.class);
        assertNotNull(spike, "EHZ boss should spawn a spike child");
        assertNotNull(vehicle, "EHZ boss should spawn a ground vehicle child");

        assertTrue(spike.isPersistent(),
                "Drillcone/spike must inherit the boss's persistence so it is not culled "
                        + "independently when it leads the boss past the screen edge");
        assertTrue(vehicle.isPersistent(),
                "Boss children must inherit the boss's persistence");
    }

    @Test
    public void destroyingDefeatedBossDespawnsFlyingPartsButKeepsGroundDebris() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = cameraAtOrigin();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public Camera camera() {
                return camera;
            }
        };
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        ObjectSpawn spawn = new ObjectSpawn(0x29D0, 0x426, Sonic2ObjectIds.EHZ_BOSS,
                0x81, 0, false, 0);
        Sonic2EHZBossInstance boss =
                manager.createDynamicObject(() -> new Sonic2EHZBossInstance(spawn));

        EHZBossSpike spike = findChild(boss, EHZBossSpike.class);
        EHZBossGroundVehicle vehicle = findChild(boss, EHZBossGroundVehicle.class);
        assertNotNull(spike, "EHZ boss should spawn a spike child");
        assertNotNull(vehicle, "EHZ boss should spawn a ground vehicle child");

        // ROM loc_2F46E deletes the main body when it flies off-screen; that cascades
        // to the flying parts (the spike self-deletes once its parent is gone) while the
        // wrecked ground vehicle persists as debris (loc_2F5F6 never DeleteObjects it).
        boss.setDestroyed(true);

        assertTrue(spike.isDestroyed(),
                "Drillcone/spike must despawn when the boss body is destroyed on fly-off");
        assertFalse(vehicle.isDestroyed(),
                "Wrecked ground vehicle must persist as debris after the boss body is destroyed");
    }

    private static <T extends AbstractBossChild> T findChild(
            Sonic2EHZBossInstance boss, Class<T> type) {
        for (BossChildComponent child : boss.getChildComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
        }
        return null;
    }

    private static Camera cameraAtOrigin() {
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        return camera;
    }
}
