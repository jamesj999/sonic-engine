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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestS2MtzLongPlatformCogGraphRewind {
    private static final ObjectSpawn CHILD_PARENT_SPAWN =
            new ObjectSpawn(0x260, 0x150, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x80, 0, false, 0, 125);
    private static final ObjectSpawn STANDALONE_SPAWN =
            new ObjectSpawn(0x240, 0x180, Sonic2ObjectIds.MTZ_LONG_PLATFORM, 0x20, 1, false, 0, 126);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void mtzLongPlatformCogGraphRestoresChildWithoutDropsDoublesOrStaleParent() {
        Harness harness = Harness.create(List.of(CHILD_PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        stepFrames(objectManager, 2);

        MTZLongPlatformObjectInstance beforeParent = parentPlatform(objectManager);
        MTZLongPlatformCogInstance beforeChild = onlyCog(objectManager);
        assertSame(beforeParent, readObjectField(beforeChild, "parent"),
                "fixture child cog must link to the live MTZ long-platform parent");
        assertFalse(readBooleanField(beforeChild, "standalone"),
                "fixture child cog must capture child mode");

        setIntField(beforeParent, "currentDist", 6);
        setIntField(beforeChild, "mappingFrame", 2);

        ObjectRefId parentId = objectId(objectManager, beforeParent);
        ObjectRefId childId = objectId(objectManager, beforeChild);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(beforeChild);

        rewindRegistry.restore(snapshot);

        MTZLongPlatformObjectInstance restoredParent = platformById(objectManager, parentId);
        MTZLongPlatformCogInstance restoredChild = cogById(objectManager, childId);

        assertEquals(1, liveParents(objectManager).size(),
                "restore must keep exactly the captured MTZ long-platform parent");
        assertEquals(1, liveCogs(objectManager).size(),
                "restore must keep exactly the captured MTZ long-platform child cog");
        assertNotSame(beforeParent, restoredParent, "restore must recreate the MTZ long-platform parent");
        assertNotSame(beforeChild, restoredChild, "restore must recreate the MTZ long-platform child cog");
        assertSame(restoredParent, readObjectField(restoredChild, "parent"),
                "restored child cog must point to the restored parent");
        assertNotSame(beforeParent, readObjectField(restoredChild, "parent"),
                "restored child cog must not retain a stale pre-restore parent ref");
        assertFalse(readBooleanField(restoredChild, "standalone"),
                "restored child cog must remain in child mode");
        assertEquals(2, readIntField(restoredChild, "mappingFrame"),
                "child cog animation scalar must round-trip");
    }

    @Test
    void standaloneMtzLongPlatformCogRestoresWithoutParentRelink() {
        Harness harness = Harness.create(List.of(STANDALONE_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        stepFrames(objectManager, 1);

        MTZLongPlatformCogInstance beforeCog = onlyCog(objectManager);
        assertTrue(readBooleanField(beforeCog, "standalone"),
                "fixture cog must be the standalone Obj65 property-index-2 form");
        assertNull(readObjectField(beforeCog, "parent"),
                "standalone cog must not have a parent");

        setIntField(beforeCog, "mappingFrame", 1);

        ObjectRefId cogId = objectId(objectManager, beforeCog);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(beforeCog);

        rewindRegistry.restore(snapshot);

        MTZLongPlatformCogInstance restoredCog = cogById(objectManager, cogId);
        assertEquals(0, liveParents(objectManager).size(),
                "standalone cog restore must not synthesize an MTZ long-platform parent");
        assertNotSame(beforeCog, restoredCog, "restore must recreate the standalone cog");
        assertTrue(readBooleanField(restoredCog, "standalone"),
                "restored standalone cog must remain standalone");
        assertNull(readObjectField(restoredCog, "parent"),
                "restored standalone cog must not gain a parent");
        assertEquals(1, readIntField(restoredCog, "mappingFrame"),
                "standalone cog animation scalar must round-trip");
    }

    @Test
    void missingMtzLongPlatformParentDropsCapturedChildCogCleanly() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        MTZLongPlatformObjectInstance unmanagedParent = new MTZLongPlatformObjectInstance(CHILD_PARENT_SPAWN);
        MTZLongPlatformCogInstance child = objectManager.createDynamicObject(
                () -> new MTZLongPlatformCogInstance(
                        expectedChildX(CHILD_PARENT_SPAWN),
                        expectedChildY(CHILD_PARENT_SPAWN),
                        expectedChildXFlip(CHILD_PARENT_SPAWN),
                        unmanagedParent));

        assertFalse(readBooleanField(child, "standalone"),
                "fixture child cog must capture child mode without a managed parent");

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(child);

        rewindRegistry.restore(snapshot);

        assertEquals(0, liveCogs(objectManager).size(),
                "restore must drop a captured child cog when its parent is absent");
        assertEquals(0, liveParents(objectManager).size(),
                "restore must not miscreate a missing child cog as an MTZ long-platform parent");
    }

    @Test
    void mtzLongPlatformCogUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(MTZLongPlatformCogInstance.class),
                "MTZ long-platform cog must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        MTZLongPlatformCogInstance.class.getName()),
                "MTZ long-platform cog must not use an explicit S2 dynamic codec");
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

    private static void stepFrames(ObjectManager objectManager, int count) {
        for (int i = 0; i < count; i++) {
            objectManager.update(i, null, null, 0, false);
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

    private static MTZLongPlatformObjectInstance platformById(ObjectManager objectManager, ObjectRefId id) {
        return liveParents(objectManager).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored MTZ long-platform parent " + id));
    }

    private static MTZLongPlatformCogInstance cogById(ObjectManager objectManager, ObjectRefId id) {
        return liveCogs(objectManager).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored MTZ long-platform cog " + id));
    }

    private static MTZLongPlatformObjectInstance parentPlatform(ObjectManager objectManager) {
        List<MTZLongPlatformObjectInstance> parents = liveParents(objectManager);
        assertEquals(1, parents.size(),
                "expected one MTZ long-platform parent: " + describeObjects(objectManager));
        return parents.getFirst();
    }

    private static MTZLongPlatformCogInstance onlyCog(ObjectManager objectManager) {
        List<MTZLongPlatformCogInstance> cogs = liveCogs(objectManager);
        assertEquals(1, cogs.size(),
                "expected one MTZ long-platform cog: " + describeObjects(objectManager));
        return cogs.getFirst();
    }

    private static List<MTZLongPlatformObjectInstance> liveParents(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(MTZLongPlatformObjectInstance.class::isInstance)
                .map(MTZLongPlatformObjectInstance.class::cast)
                .filter(parent -> !parent.isDestroyed())
                .toList();
    }

    private static List<MTZLongPlatformCogInstance> liveCogs(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(MTZLongPlatformCogInstance.class::isInstance)
                .map(MTZLongPlatformCogInstance.class::cast)
                .filter(cog -> !cog.isDestroyed())
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

    private static int expectedChildX(ObjectSpawn parentSpawn) {
        int childX = parentSpawn.x() - 0x4C;
        if ((parentSpawn.renderFlags() & 0x01) == 0) {
            childX += 0x18;
        }
        return childX;
    }

    private static int expectedChildY(ObjectSpawn parentSpawn) {
        return parentSpawn.y() + 0x14;
    }

    private static boolean expectedChildXFlip(ObjectSpawn parentSpawn) {
        return (parentSpawn.renderFlags() & 0x01) == 0;
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
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
