package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ROM {@code Hel_Main} allocates a real SST slot per non-parent spike through
 * {@code FindFreeObj} and gives it routine 8 ({@code Hel_ChildSpike});
 * {@code Hel_ChkDel .deleteHelix} frees every stored child slot when the parent
 * leaves range (docs/s1disasm/_incObj/17 GHZ Spiked Pole Helix.asm:46-90,
 * 120-145).
 * <p>
 * The slot pressure is the point: modelling the spikes as one instance with an
 * internal array left the SST 30 slots emptier than ROM in GHZ3, so the
 * spilled-ring {@code RLoss_Count} loop found 31 free slots instead of 19
 * (docs/s1disasm/_incObj/25, 37 Rings.asm:234-252).
 */
public class TestSonic1SpikedPoleHelixChildSlots {

    @BeforeEach
    public void setUp() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    public void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    private static ObjectManager newManager(ObjectManager[] holder) {
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;
        return manager;
    }

    @Test
    public void helixOccupiesOneSstSlotPerSpike() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectManager manager = newManager(holder);

        int spikes = 0x10;
        Sonic1SpikedPoleHelixObjectInstance helix =
                new Sonic1SpikedPoleHelixObjectInstance(
                        new ObjectSpawn(0x0100, 0x0060, 0x17, spikes, 0, false, 0));
        manager.addDynamicObjectAtSlot(helix, 40);

        manager.update(0, null, null, 1);

        long children = manager.getActiveObjects().stream()
                .filter(o -> o instanceof
                        Sonic1SpikedPoleHelixObjectInstance.HelixSpikeChild)
                .count();
        assertEquals(spikes - 1, children,
                "Hel_Main allocates one SST slot per non-parent spike");

        for (ObjectInstance object : manager.getActiveObjects()) {
            if (object instanceof AbstractObjectInstance aoi) {
                assertTrue(aoi.getSlotIndex() >= 0,
                        "every spike must hold a real SST slot");
            }
        }
    }

    @Test
    public void helixStopsBuildingWhenObjectRamIsFull() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectManager manager = newManager(holder);

        // ROM: the build loop branches to Hel_ParentSpike the first time
        // FindFreeObj reports object RAM full
        // (docs/s1disasm/_incObj/17 GHZ Spiked Pole Helix.asm:50).
        int spikes = 0x10;
        Sonic1SpikedPoleHelixObjectInstance helix =
                new Sonic1SpikedPoleHelixObjectInstance(
                        new ObjectSpawn(0x0100, 0x0060, 0x17, spikes, 0, false, 0));
        manager.addDynamicObjectAtSlot(helix, 40);
        manager.reserveAllButNFreeSlots(4);

        manager.update(0, null, null, 1);

        long children = manager.getActiveObjects().stream()
                .filter(o -> o instanceof
                        Sonic1SpikedPoleHelixObjectInstance.HelixSpikeChild)
                .count();
        assertEquals(4, children,
                "the helix keeps only the spikes FindFreeObj could place");
    }
}
