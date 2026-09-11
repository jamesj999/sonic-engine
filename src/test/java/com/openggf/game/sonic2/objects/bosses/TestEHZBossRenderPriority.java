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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Render-ordering regression test for the EHZ Act 2 boss (Obj56).
 *
 * <p>The drillcone/spike on the front of the boss car must render in front of
 * the ground vehicle body. In the ROM the spike has {@code priority(a0) = 2}
 * while the ground vehicle has {@code priority(a0) = 3} (s2.asm:63194 Obj56_Init);
 * lower priority draws on top. Both pieces previously shared engine bucket 4,
 * so the spike (spawned last, higher slot) lost the within-bucket slot tiebreak
 * and was drawn behind the car.
 */
public class TestEHZBossRenderPriority {

    @BeforeEach
    public void setUp() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    public void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    public void spikeRendersInFrontOfGroundVehicle() {
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

        // Lower bucket draws later (on top). Spike must be in front of the car body.
        assertTrue(spike.getPriorityBucket() < vehicle.getPriorityBucket(),
                "Drillcone/spike (bucket " + spike.getPriorityBucket()
                        + ") must render in front of the ground vehicle (bucket "
                        + vehicle.getPriorityBucket() + ")");
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
