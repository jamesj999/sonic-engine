package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.objects.MCZBrickObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph-rewind coverage for the MCZ Obj75 spike-ball display child
 * ({@code MCZBrickObjectInstance$MCZBrickDisplayChild}). The child has no
 * probe-compatible constructor (private, parent-only) and restores through
 * {@link RewindRecreatable#recreateForRewind} against its live parent, so it
 * is classified graph-covered in the round-trip tail inventory with this test
 * as evidence.
 */
class TestS2MczBrickDisplayChildGraphRewind {

    private static final int SPIKE_BALL_SUBTYPE = 0x16;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void displayChildRestoresThroughLiveParentWithPreservedIdentity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();

        MCZBrickObjectInstance parent = objectManager.createDynamicObject(
                () -> new MCZBrickObjectInstance(
                        new ObjectSpawn(0x0400, 0x0300, 0x75, SPIKE_BALL_SUBTYPE, 0, false, 0),
                        "MCZBrick"));
        parent.update(0, null);

        AbstractObjectInstance before = displayChildOf(objectManager);
        assertNotNull(before, "spike-ball parent must spawn its display child on first update");
        assertTrue(before instanceof RewindRecreatable,
                "display child must restore through RewindRecreatable");
        ObjectRefId beforeId = objectId(objectManager, before);

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(before);

        registry.restore(snapshot);

        AbstractObjectInstance restored = displayChildOf(objectManager);
        assertNotNull(restored, "restore must recreate the removed display child");
        assertNotSame(before, restored, "restore must recreate, not alias, the display child");
        assertEquals(beforeId, objectId(objectManager, restored),
                "display child dynamic identity must be preserved");
        MCZBrickObjectInstance liveParent = objectManager.getActiveObjects().stream()
                .filter(MCZBrickObjectInstance.class::isInstance)
                .map(MCZBrickObjectInstance.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(liveParent, "restore must keep a live spike-ball parent");
        assertSame(restored, readField(liveParent, "displayChild"),
                "recreateForRewind must reattach the child to its live parent");
    }

    private static AbstractObjectInstance displayChildOf(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass().getSimpleName().equals("MCZBrickDisplayChild"))
                .map(AbstractObjectInstance.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static Object readField(Object target, String fieldName) {
        try {
            for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    // keep walking up
                }
            }
            throw new NoSuchFieldException(fieldName);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    null,
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
