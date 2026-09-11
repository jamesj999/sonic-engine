package com.openggf.tests.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Engine-level proof of the MTZ3 slot-recycle invariant: after an object is
 * deleted (ROM {@code DeleteObject} clears the slot's identity), the next
 * allocation reuses the same SST slot, and {@link ObjectManager#objectIdInSlot(int)}
 * — the slot→occupant identity authority the sidekick despawn comparator reads —
 * reports the NEW occupant's id, never the previous occupant's id and never
 * {@code -1}.
 *
 * <p>This is the {@code ObjectManager}-level companion to
 * {@code TestSlotAllocator.recycle_releasedSlotIsReusedAndIdentityCleared}, which
 * proves only allocator-level slot reuse. The Task 1.7 occupancy oracle is the
 * per-frame integration proof of the same invariant across a real trace.
 */
@ExtendWith(SingletonResetExtension.class)
@FullReset
public class TestObjectManagerSlotRecycleIdentity {

    private static final int OBJECT_ID_A = 0x78;
    private static final int OBJECT_ID_B = 0x2A;

    private ObjectManager objectManager;
    private ObjectServices objectServices;

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        objectServices = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public com.openggf.camera.Camera camera() {
                return GameServices.camera();
            }
        };
        objectManager = new ObjectManager(List.of(), new Sonic2LayoutRegistry(), 0, null, null,
                null, GameServices.camera(), objectServices);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void recycledSlotReportsNewOccupantId() {
        // Spawn object A into a dynamic slot via the manager.
        TestObject a = new TestObject(new ObjectSpawn(0, 0, OBJECT_ID_A, 0, 0, false, 0));
        objectManager.addDynamicObject(a);
        int slot = a.getSlotIndex();
        assertTrue(slot >= ObjectSlotLayout.SONIC_2.firstDynamicSlot(),
                "Object A should occupy a dynamic SST slot, got " + slot);
        assertEquals(OBJECT_ID_A, objectManager.objectIdInSlot(slot),
                "Slot should report object A's id while A is live");

        // Destroy A and run the unload so the slot is released (ROM DeleteObject
        // zeroes the slot's identity and frees it for reallocation).
        a.setDestroyed(true);
        objectManager.releaseSlot(a);
        assertNotEquals(OBJECT_ID_A, objectManager.objectIdInSlot(slot),
                "After A is deleted, the freed slot must not still report A's id");

        // Spawn object B, which recycles the SAME slot (linear-first-empty).
        TestObject b = new TestObject(new ObjectSpawn(0, 0, OBJECT_ID_B, 0, 0, false, 0));
        objectManager.addDynamicObject(b);
        assertEquals(slot, b.getSlotIndex(),
                "Object B should recycle the slot A vacated");

        // The recycled slot now reports the NEW occupant's id (not A's, not -1).
        assertEquals(OBJECT_ID_B, objectManager.objectIdInSlot(slot),
                "Recycled slot must report object B's id, the new occupant");
        assertNotEquals(OBJECT_ID_A, objectManager.objectIdInSlot(slot),
                "Recycled slot must NOT report the old occupant A's id");
        assertFalse(objectManager.objectIdInSlot(slot) == -1,
                "Recycled slot must NOT report empty (-1) while B is live");
    }

    /** Registry whose layout is Sonic 2 (firstDynamicSlot = 16); creates no objects. */
    private static final class Sonic2LayoutRegistry implements ObjectRegistry {
        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_2;
        }

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "noop";
        }
    }

    /** Minimal renderless dynamic object usable in the headless harness. */
    private static final class TestObject extends AbstractObjectInstance {
        private TestObject(ObjectSpawn spawn) {
            super(spawn, "test-object");
        }

        @Override
        public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {
            // no rendering in headless tests
        }
    }
}
