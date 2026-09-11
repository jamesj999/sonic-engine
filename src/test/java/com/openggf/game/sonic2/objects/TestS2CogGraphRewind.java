package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestS2CogGraphRewind {
    private static final String SLOT_CHILD_CLASS =
            "com.openggf.game.sonic2.objects.CogObjectInstance$CogSlotChildInstance";
    private static final ObjectSpawn PARENT_SPAWN =
            new ObjectSpawn(0x260, 0x150, Sonic2ObjectIds.COG, 0x00, 0, false, 0, 124);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cogGraphRestoresSlotChildrenWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(0, null, null, 0, false);

        CogObjectInstance beforeParent = parentCog(objectManager);
        List<ObjectInstance> beforeChildren = liveSlotChildren(objectManager);
        assertEquals(7, beforeChildren.size(),
                "fixture must spawn seven slot-pressure children: " + describeObjects(objectManager));
        for (ObjectInstance child : beforeChildren) {
            assertSame(beforeParent, readObjectField(child, "parent"),
                    "fixture slot child must link to the live Cog parent");
        }

        setIntField(beforeParent, "baseX", PARENT_SPAWN.x() + 23);
        setIntField(beforeParent, "baseY", PARENT_SPAWN.y() + 31);
        setIntField(beforeParent, "rotationPhase", 0x30);
        setBooleanField(beforeParent, "childrenSpawned", true);
        invokeNoArg(beforeParent, "updateToothPositions");

        ObjectRefId parentId = objectId(objectManager, beforeParent);
        List<ObjectRefId> childIds = beforeChildren.stream()
                .map(child -> objectId(objectManager, child))
                .toList();
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        beforeChildren.forEach(objectManager::removeDynamicObject);

        rewindRegistry.restore(snapshot);

        CogObjectInstance restoredParent = objectById(objectManager, parentId);
        List<ObjectInstance> restoredChildren = childIds.stream()
                .map(id -> childById(objectManager, id))
                .toList();
        List<ObjectInstance> parentChildren = readObjectList(restoredParent, "slotChildren");

        assertEquals(1, liveParents(objectManager).size(),
                "restore must keep exactly the captured Cog parent");
        assertEquals(7, liveSlotChildren(objectManager).size(),
                "restore must keep exactly the captured Cog slot children");
        assertEquals(new HashSet<>(childIds).size(), childIds.size(),
                "fixture must capture distinct slot-child identities");
        assertEquals(new HashSet<>(restoredChildren).size(), restoredChildren.size(),
                "restore must not duplicate a slot child under multiple captured ids");
        assertNotSame(beforeParent, restoredParent, "restore must recreate the Cog parent");
        assertEquals(PARENT_SPAWN.x() + 23, restoredParent.getX(),
                "parent baseX scalar must round-trip");
        assertEquals(PARENT_SPAWN.y() + 31, restoredParent.getY(),
                "parent baseY scalar must round-trip");
        assertEquals(0x30, readIntField(restoredParent, "rotationPhase"),
                "parent rotation scalar must round-trip");
        assertEquals(7, parentChildren.size(),
                "restored parent must track exactly its seven restored slot children");
        assertEquals(Set.copyOf(restoredChildren), Set.copyOf(parentChildren),
                "restored parent child list must contain the restored slot children");

        for (int i = 0; i < restoredChildren.size(); i++) {
            ObjectInstance restoredChild = restoredChildren.get(i);
            assertNotSame(beforeChildren.get(i), restoredChild,
                    "restore must recreate each captured Cog slot child");
            assertSame(restoredParent, readObjectField(restoredChild, "parent"),
                    "restored Cog slot child must point to the restored parent");
            assertNotSame(beforeParent, readObjectField(restoredChild, "parent"),
                    "restored Cog slot child must not retain a stale pre-restore parent ref");
        }
    }

    @Test
    void cogUsesRewindRecreatableWithoutExplicitDynamicCodec() throws ClassNotFoundException {
        Class<?> childClass = Class.forName(SLOT_CHILD_CLASS);
        assertTrue(RewindRecreatable.class.isAssignableFrom(CogObjectInstance.class),
                "Cog must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(childClass),
                "Cog slot child must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(CogObjectInstance.class.getName()),
                "Cog must not use an explicit S2 dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(SLOT_CHILD_CLASS),
                "Cog slot child must not use an explicit S2 dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            LevelManager levelManager = mock(LevelManager.class);
            when(levelManager.getFrameCounter()).thenReturn(0);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public LevelManager levelManager() { return levelManager; }
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

    private static CogObjectInstance objectById(ObjectManager objectManager, ObjectRefId id) {
        return liveParents(objectManager).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored Cog parent " + id));
    }

    private static ObjectInstance childById(ObjectManager objectManager, ObjectRefId id) {
        return liveSlotChildren(objectManager).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored Cog slot child " + id));
    }

    private static CogObjectInstance parentCog(ObjectManager objectManager) {
        List<CogObjectInstance> parents = liveParents(objectManager);
        assertEquals(1, parents.size(),
                "expected one Cog parent: " + describeObjects(objectManager));
        return parents.getFirst();
    }

    private static List<CogObjectInstance> liveParents(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(CogObjectInstance.class::isInstance)
                .map(CogObjectInstance.class::cast)
                .filter(parent -> !parent.isDestroyed())
                .toList();
    }

    private static List<ObjectInstance> liveSlotChildren(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(SLOT_CHILD_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static String describeObjects(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .map(object -> object.getClass().getSimpleName()
                        + "@(" + object.getX() + "," + object.getY() + ")"
                        + ", destroyed=" + object.isDestroyed())
                .toList()
                .toString();
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ObjectInstance> readObjectList(Object target, String fieldName) {
        Object value = readObjectField(target, fieldName);
        assertTrue(value instanceof List<?>, "expected list field " + fieldName);
        return (List<ObjectInstance>) value;
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

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void invokeNoArg(Object target, String methodName) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + methodName + " on " + target.getClass(), e);
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
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
