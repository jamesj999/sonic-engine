package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.boss.BossChildComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression: boss component children must not be carried across a seamless act
 * reload.
 *
 * <p>The seamless carry snapshot ({@link ObjectManager#snapshotPersistentDynamicObjectsForTransition()})
 * preserves persistent <em>dynamic</em> objects so they survive the manager rebuild
 * and are re-offset into the new act (ROM {@code Offset_ObjectsDuringTransition}),
 * matching the end signpost. Boss component children are also persistent — but only
 * so they survive the off-screen cull <em>during the fixed-arena fight</em>, not so
 * they ride a level reload. ROM {@code Load_Level} clears {@code Dynamic_object_RAM},
 * deleting the whole boss object group.
 *
 * <p>The concrete failure: the AIZ1 miniboss is a placed cutscene object whose
 * body/arm/flame-barrel children are spawned dynamically. At the AIZ1-&gt;AIZ2 fire
 * reload the placed parent is dropped (not in the dynamic snapshot) but the persistent
 * children were carried with no offset, stranding the (art-less, invisible) body and
 * its still-hurting flame barrels partway through AIZ2.
 */
class TestSeamlessCarryExcludesBossChildren {

    @BeforeEach
    void setUp() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void bossChildrenAreNotCarriedButParentStillIs() {
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

        // Precondition: the boss and all its children are persistent dynamic objects,
        // so before the fix every one of them is collected by the carry snapshot.
        assertTrue(boss.isPersistent(), "EHZ boss should be persistent");
        assertFalse(boss.getChildComponents().isEmpty(),
                "EHZ boss should have spawned child components");
        for (BossChildComponent child : boss.getChildComponents()) {
            assertTrue(((ObjectInstance) child).isPersistent(),
                    "boss children inherit the boss's persistence");
        }

        List<ObjectInstance> carried = manager.snapshotPersistentDynamicObjectsForTransition();

        // Non-boss-child persistent objects (signposts, and here the boss body itself)
        // still ride the reload so they can be re-offset into the new act.
        assertTrue(carried.contains(boss),
                "a persistent non-boss-child object must still be carried across the reload");

        // Boss component children must NOT ride the reload.
        for (BossChildComponent child : boss.getChildComponents()) {
            assertFalse(carried.contains(child),
                    "boss child " + child.getClass().getSimpleName()
                            + " must not be carried across a seamless act reload "
                            + "(ROM Load_Level clears Dynamic_object_RAM)");
        }
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
