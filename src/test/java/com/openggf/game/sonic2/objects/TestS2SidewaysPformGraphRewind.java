package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
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

class TestS2SidewaysPformGraphRewind {
    private static final ObjectSpawn PARENT_SPAWN =
            new ObjectSpawn(0x220, 0x140, Sonic2ObjectIds.SIDEWAYS_PFORM, 0x06, 0, false, 0, 121);
    private static final int CHILD_X = PARENT_SPAWN.x() + 0x40;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void sidewaysPformGraphRestoresPairWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(0, null, null, 0, false);

        SidewaysPformObjectInstance beforeParent = parentPlatform(objectManager);
        SidewaysPformObjectInstance beforeChild = childPlatform(objectManager);
        assertSame(beforeChild, readLinkedPlatform(beforeParent),
                "fixture parent must link to the spawned child");
        assertSame(beforeParent, readLinkedPlatform(beforeChild),
                "fixture child must link back to the parent");
        setIntField(beforeParent, "direction", 1);
        setIntField(beforeChild, "direction", 1);
        setIntField(beforeChild, "x", CHILD_X + 7);

        ObjectRefId parentId = objectId(objectManager, beforeParent);
        ObjectRefId childId = objectId(objectManager, beforeChild);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(beforeChild);

        rewindRegistry.restore(snapshot);

        SidewaysPformObjectInstance restoredParent =
                objectById(objectManager, SidewaysPformObjectInstance.class, parentId);
        SidewaysPformObjectInstance restoredChild =
                objectById(objectManager, SidewaysPformObjectInstance.class, childId);

        assertEquals(2, livePlatforms(objectManager).size(),
                "restore must keep exactly the captured Sideways platform pair");
        assertNotSame(beforeParent, restoredParent, "restore must recreate the placed parent");
        assertNotSame(beforeChild, restoredChild, "restore must recreate the linked child");
        assertSame(restoredChild, readLinkedPlatform(restoredParent),
                "restored parent must point at the restored child");
        assertSame(restoredParent, readLinkedPlatform(restoredChild),
                "restored child must point at the restored parent");
        assertNotSame(beforeChild, readLinkedPlatform(restoredParent),
                "restored parent must not retain a stale pre-restore child ref");
        assertNotSame(beforeParent, readLinkedPlatform(restoredChild),
                "restored child must not retain a stale pre-restore parent ref");

        assertEquals(1, readIntField(restoredParent, "direction"),
                "parent direction scalar must round-trip");
        assertEquals(1, readIntField(restoredChild, "direction"),
                "child direction scalar must round-trip");
        assertEquals(CHILD_X + 7, restoredChild.getX(),
                "child x scalar must round-trip through compact restore");
    }

    @Test
    void sidewaysPformLinkedPlatformStillRequiresRewindIdentity() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.update(0, null, null, 0, false);
        SidewaysPformObjectInstance parent = parentPlatform(objectManager);
        SidewaysPformObjectInstance unmanagedChild = new SidewaysPformObjectInstance(
                new ObjectSpawn(0x340, 0x160, Sonic2ObjectIds.SIDEWAYS_PFORM, 0x06, 0, false, 0, 122),
                "SidewaysPform");
        setLinkedPlatform(parent, unmanagedChild);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, registryFor(objectManager)::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing non-null sibling identity must fail loudly");
    }

    @Test
    void sidewaysPformUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(SidewaysPformObjectInstance.class),
                "Sideways platforms must restore through RewindRecreatable");
    }

    @Test
    void sidewaysPlatformEndpointLifetimePinsNativeUltraAndSuperWideCoarseBuckets() {
        int cameraX = 0x1500;
        int base = S2ObjectWindowing.unloadCoarse(cameraX);
        int[] widths = {320, 528, 800};
        int[] lastSurvivingBucket = {0x280, 0x300, 0x400};
        int[] firstDeletingBucket = {0x300, 0x380, 0x480};

        for (int i = 0; i < widths.length; i++) {
            Harness harness = Harness.create(List.of(), cameraX, widths[i]);
            SidewaysPformObjectInstance survivor = harness.objectManager().createDynamicObject(
                    () -> new SidewaysPformObjectInstance(PARENT_SPAWN, "SidewaysPform"));
            setIntField(survivor, "minX", base + lastSurvivingBucket[i]);
            setIntField(survivor, "maxX", base + lastSurvivingBucket[i]);
            assertFalse(survivor.isCustomOutOfRange(cameraX),
                    "Obj7A's last endpoint bucket must survive at width " + widths[i]);

            SidewaysPformObjectInstance deleted = harness.objectManager().createDynamicObject(
                    () -> new SidewaysPformObjectInstance(PARENT_SPAWN, "SidewaysPform"));
            setIntField(deleted, "minX", base + firstDeletingBucket[i]);
            setIntField(deleted, "maxX", base + firstDeletingBucket[i]);
            assertTrue(deleted.isCustomOutOfRange(cameraX),
                    "Obj7A's first endpoint bucket beyond the compare must delete at width " + widths[i]);
        }
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            return create(spawns, 0, 320);
        }

        static Harness create(List<ObjectSpawn> spawns, int cameraX, int viewportWidth) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera(cameraX, viewportWidth);
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
        return livePlatforms(objectManager).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static SidewaysPformObjectInstance parentPlatform(ObjectManager objectManager) {
        List<SidewaysPformObjectInstance> parents = livePlatforms(objectManager).stream()
                .filter(TestS2SidewaysPformGraphRewind::isParentSpawn)
                .toList();
        assertEquals(1, parents.size(),
                "expected one Sideways platform parent: " + describePlatforms(objectManager));
        return parents.getFirst();
    }

    private static SidewaysPformObjectInstance childPlatform(ObjectManager objectManager) {
        List<SidewaysPformObjectInstance> children = livePlatforms(objectManager).stream()
                .filter(platform -> !isParentSpawn(platform))
                .toList();
        assertEquals(1, children.size(),
                "expected one Sideways platform child: " + describePlatforms(objectManager));
        return children.getFirst();
    }

    private static boolean isParentSpawn(SidewaysPformObjectInstance platform) {
        ObjectSpawn spawn = platform.getSpawn();
        return spawn.objectId() == PARENT_SPAWN.objectId()
                && spawn.subtype() == PARENT_SPAWN.subtype()
                && spawn.layoutIndex() == PARENT_SPAWN.layoutIndex();
    }

    private static List<SidewaysPformObjectInstance> livePlatforms(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(SidewaysPformObjectInstance.class::isInstance)
                .map(SidewaysPformObjectInstance.class::cast)
                .filter(platform -> !platform.isDestroyed())
                .toList();
    }

    private static String describePlatforms(ObjectManager objectManager) {
        return livePlatforms(objectManager).stream()
                .map(platform -> "x=" + platform.getSpawn().x()
                        + ", y=" + platform.getSpawn().y()
                        + ", subtype=0x" + Integer.toHexString(platform.getSpawn().subtype())
                        + ", layout=" + platform.getSpawn().layoutIndex()
                        + ", destroyed=" + platform.isDestroyed())
                .toList()
                .toString();
    }

    private static SidewaysPformObjectInstance readLinkedPlatform(SidewaysPformObjectInstance platform) {
        try {
            Field field = SidewaysPformObjectInstance.class.getDeclaredField("linkedPlatform");
            field.setAccessible(true);
            return (SidewaysPformObjectInstance) field.get(platform);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setLinkedPlatform(
            SidewaysPformObjectInstance platform, SidewaysPformObjectInstance linkedPlatform) {
        try {
            Field field = SidewaysPformObjectInstance.class.getDeclaredField("linkedPlatform");
            field.setAccessible(true);
            field.set(platform, linkedPlatform);
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
        return mockCamera(0, 320);
    }

    private static Camera mockCamera(int cameraX, int viewportWidth) {
        return new Camera() {
            @Override public short getX() { return (short) cameraX; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return (short) viewportWidth; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
