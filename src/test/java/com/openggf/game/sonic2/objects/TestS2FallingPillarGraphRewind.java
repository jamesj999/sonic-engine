package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GraphicsManager;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2FallingPillarGraphRewind {
    private static final ObjectSpawn PARENT_SPAWN =
            new ObjectSpawn(0x240, 0x180, Sonic2ObjectIds.FALLING_PILLAR, 0, 0, false, 0, 122);
    private static final int CHILD_Y = PARENT_SPAWN.y() + 0x30;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void fallingPillarGraphRestoresPairWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(0, null, null, 0, false);

        FallingPillarObjectInstance beforeParent = parentPillar(objectManager);
        FallingPillarObjectInstance beforeChild = childPillar(objectManager);
        assertSame(beforeChild, beforeParent.getChildInstance(),
                "fixture parent must link to the spawned lower section");
        assertTrue(beforeParent.isChildSpawned(), "fixture parent must remember that the child was spawned");
        setIntField(beforeChild, "routineSecondary", 4);
        setIntField(beforeChild, "shakeTimer", 3);
        setIntField(beforeChild, "y", CHILD_Y + 5);

        ObjectRefId parentId = objectId(objectManager, beforeParent);
        ObjectRefId childId = objectId(objectManager, beforeChild);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(beforeChild);

        rewindRegistry.restore(snapshot);

        FallingPillarObjectInstance restoredParent =
                objectById(objectManager, FallingPillarObjectInstance.class, parentId);
        FallingPillarObjectInstance restoredChild =
                objectById(objectManager, FallingPillarObjectInstance.class, childId);

        assertEquals(2, livePillars(objectManager).size(),
                "restore must keep exactly the captured Falling Pillar pair");
        assertNotSame(beforeParent, restoredParent, "restore must recreate the placed parent");
        assertNotSame(beforeChild, restoredChild, "restore must recreate the lower-section child");
        assertSame(restoredChild, restoredParent.getChildInstance(),
                "restored parent must point at the restored lower-section child");
        assertNotSame(beforeChild, restoredParent.getChildInstance(),
                "restored parent must not retain a stale pre-restore child ref");

        assertFalse(readBooleanField(restoredParent, "isChild"),
                "parent child-mode scalar must round-trip through compact restore");
        assertTrue(readBooleanField(restoredChild, "isChild"),
                "child child-mode scalar must round-trip through compact restore");
        assertTrue(restoredParent.isChildSpawned(),
                "parent child-spawned flag must round-trip");
        assertEquals(4, readIntField(restoredChild, "routineSecondary"),
                "child routine scalar must round-trip");
        assertEquals(3, readIntField(restoredChild, "shakeTimer"),
                "child shake timer scalar must round-trip");
        assertEquals(CHILD_Y + 5, restoredChild.getY(),
                "child y scalar must round-trip");
    }

    @Test
    void fallingPillarChildLinkStillRequiresRewindIdentity() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.update(0, null, null, 0, false);
        FallingPillarObjectInstance parent = parentPillar(objectManager);
        FallingPillarObjectInstance unmanagedChild = parent.createChild();
        setChildInstance(parent, unmanagedChild);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, registryFor(objectManager)::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing non-null child identity must fail loudly");
    }

    @Test
    void fallingPillarUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(FallingPillarObjectInstance.class),
                "Falling Pillar must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        FallingPillarObjectInstance.class.getName()),
                "Falling Pillar must not use an explicit S2 dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic2ObjectRegistry(),
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

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return livePillars(objectManager).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static FallingPillarObjectInstance parentPillar(ObjectManager objectManager) {
        List<FallingPillarObjectInstance> parents = livePillars(objectManager).stream()
                .filter(pillar -> !readBooleanField(pillar, "isChild"))
                .toList();
        assertEquals(1, parents.size(),
                "expected one Falling Pillar parent: " + describePillars(objectManager));
        return parents.getFirst();
    }

    private static FallingPillarObjectInstance childPillar(ObjectManager objectManager) {
        List<FallingPillarObjectInstance> children = livePillars(objectManager).stream()
                .filter(pillar -> readBooleanField(pillar, "isChild"))
                .toList();
        assertEquals(1, children.size(),
                "expected one Falling Pillar child: " + describePillars(objectManager));
        return children.getFirst();
    }

    private static List<FallingPillarObjectInstance> livePillars(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(FallingPillarObjectInstance.class::isInstance)
                .map(FallingPillarObjectInstance.class::cast)
                .filter(pillar -> !pillar.isDestroyed())
                .toList();
    }

    private static String describePillars(ObjectManager objectManager) {
        return livePillars(objectManager).stream()
                .map(pillar -> "x=" + pillar.getSpawn().x()
                        + ", y=" + pillar.getSpawn().y()
                        + ", child=" + readBooleanField(pillar, "isChild")
                        + ", layout=" + pillar.getSpawn().layoutIndex()
                        + ", destroyed=" + pillar.isDestroyed())
                .toList()
                .toString();
    }

    private static void setChildInstance(
            FallingPillarObjectInstance pillar, FallingPillarObjectInstance child) {
        try {
            Field field = FallingPillarObjectInstance.class.getDeclaredField("childInstance");
            field.setAccessible(true);
            field.set(pillar, child);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public short getMaxY() { return 0x700; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
