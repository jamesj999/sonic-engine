package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesAiz1Instance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2AInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesRockChild;
import com.openggf.game.sonic3k.objects.CutsceneKnuxCnz2WallInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
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
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kCutsceneKnucklesGraphRewind {
    private static final ObjectSpawn AIZ_PARENT_SPAWN =
            new ObjectSpawn(0x1400, 0x0440, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0, 0, false, 30);
    private static final ObjectSpawn AIZ_ROCK_SPAWN =
            new ObjectSpawn(0x1400, 0x0460, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0, 0, false, 31);
    private static final ObjectSpawn CNZ_PARENT_SPAWN =
            new ObjectSpawn(0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 40);
    private static final ObjectSpawn CNZ_WALL_SPAWN =
            new ObjectSpawn(0x1CE0, 0x0214, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 41);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x700, 0);
    }

    @AfterEach
    void tearDown() {
        CutsceneKnucklesCnz2AInstance.clearActiveInstanceForTests();
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void aizRockChildRestoresAgainstFreshParentAndKeepsCapturedState() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CutsceneKnucklesAiz1Instance sourceParent = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesAiz1Instance(AIZ_PARENT_SPAWN));
        setIntField(sourceParent, "routine", 2);
        CutsceneKnucklesRockChild sourceRock = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesRockChild(AIZ_ROCK_SPAWN, sourceParent));
        setIntField(sourceRock, "mappingFrame", 1);
        setBooleanField(sourceRock, "broken", true);

        ObjectRefId parentId = objectId(objectManager, sourceParent);
        ObjectRefId rockId = objectId(objectManager, sourceRock);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceRock);
        CutsceneKnucklesRockChild divergentRock = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesRockChild(
                        new ObjectSpawn(0x1600, 0x0500, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0, 0, false, 32),
                        sourceParent));
        assertEquals(1, liveObjects(objectManager, CutsceneKnucklesRockChild.class).size(),
                "diverge step should leave one unrelated AIZ rock child before restore");

        rewindRegistry.restore(snapshot);

        CutsceneKnucklesAiz1Instance restoredParent =
                objectById(objectManager, CutsceneKnucklesAiz1Instance.class, parentId);
        CutsceneKnucklesRockChild restoredRock =
                objectById(objectManager, CutsceneKnucklesRockChild.class, rockId);
        assertEquals(1, liveObjects(objectManager, CutsceneKnucklesAiz1Instance.class).size(),
                "restore must keep exactly one AIZ cutscene parent");
        assertEquals(1, liveObjects(objectManager, CutsceneKnucklesRockChild.class).size(),
                "restore must keep exactly one AIZ rock child");
        assertNotSame(sourceParent, restoredParent, "restore must recreate the AIZ parent");
        assertNotSame(sourceRock, restoredRock, "restore must recreate the AIZ rock child");
        assertNotSame(divergentRock, restoredRock, "restore must drop the divergent AIZ rock child");
        assertSame(restoredParent, readObjectField(restoredRock, "parent"),
                "AIZ rock parent must resolve to the restored parent by captured identity");
        assertNotSame(sourceParent, readObjectField(restoredRock, "parent"),
                "AIZ rock must not retain the stale pre-restore parent");
        assertEquals(1, readIntField(restoredRock, "mappingFrame"),
                "AIZ rock mappingFrame must restore from compact state");
        assertTrue(readBooleanField(restoredRock, "broken"),
                "AIZ rock broken flag must restore from compact state");
    }

    @Test
    void cnzWallRestoresAgainstFreshParentRelinksBackrefAndDoesNotDuplicate() {
        Harness harness = Harness.create(List.of(CNZ_PARENT_SPAWN), 0x1C80, 0x0080);
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CutsceneKnucklesCnz2AInstance sourceParent = only(objectManager, CutsceneKnucklesCnz2AInstance.class);
        setEnumField(sourceParent, "phase", "CAMERA_LOCK");
        setIntField(sourceParent, "timer", 10);
        CutsceneKnuxCnz2WallInstance sourceWall = objectManager.createDynamicObject(
                () -> new CutsceneKnuxCnz2WallInstance(CNZ_WALL_SPAWN, sourceParent));
        assertSame(sourceWall, readObjectField(sourceParent, "blockingWall"),
                "precondition: rewind-created CNZ wall must attach to its parent back-reference");

        ObjectRefId parentId = objectId(objectManager, sourceParent);
        ObjectRefId wallId = objectId(objectManager, sourceWall);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceWall);
        CutsceneKnuxCnz2WallInstance divergentWall = objectManager.createDynamicObject(
                () -> new CutsceneKnuxCnz2WallInstance(
                        new ObjectSpawn(0x1A00, 0x0200, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 42),
                        sourceParent));
        assertEquals(1, liveObjects(objectManager, CutsceneKnuxCnz2WallInstance.class).size(),
                "diverge step should leave one unrelated CNZ wall before restore");

        rewindRegistry.restore(snapshot);

        CutsceneKnucklesCnz2AInstance restoredParent =
                objectById(objectManager, CutsceneKnucklesCnz2AInstance.class, parentId);
        CutsceneKnuxCnz2WallInstance restoredWall =
                objectById(objectManager, CutsceneKnuxCnz2WallInstance.class, wallId);
        assertEquals(1, liveObjects(objectManager, CutsceneKnucklesCnz2AInstance.class).size(),
                "restore must keep exactly one CNZ cutscene parent");
        assertEquals(1, liveObjects(objectManager, CutsceneKnuxCnz2WallInstance.class).size(),
                "restore must keep exactly one CNZ blocking wall");
        assertNotSame(sourceParent, restoredParent, "restore must recreate the CNZ parent");
        assertNotSame(sourceWall, restoredWall, "restore must recreate the CNZ wall");
        assertNotSame(divergentWall, restoredWall, "restore must drop the divergent CNZ wall");
        assertSame(restoredParent, readObjectField(restoredWall, "owner"),
                "CNZ wall owner must resolve to the restored parent by captured identity");
        assertNotSame(sourceParent, readObjectField(restoredWall, "owner"),
                "CNZ wall must not retain the stale pre-restore parent");
        assertSame(restoredWall, readObjectField(restoredParent, "blockingWall"),
                "CNZ parent blockingWall must point at the restored wall");

        restoredParent.update(1, null);
        assertEquals(1, liveObjects(objectManager, CutsceneKnuxCnz2WallInstance.class).size(),
                "post-restore CNZ parent update must not spawn a duplicate wall");

        restoredParent.setDestroyed(true);
        restoredWall.update(2, null);
        assertTrue(restoredWall.isDestroyed(),
                "CNZ wall deletion must still follow the restored parent destroyed state");
    }

    @Test
    void cutsceneKnucklesChildrenUseRewindRecreatableWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(CutsceneKnucklesCnz2AInstance.class),
                "CNZ2 Knuckles cutscene parent must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CutsceneKnucklesRockChild.class),
                "AIZ rock child must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CutsceneKnuxCnz2WallInstance.class),
                "CNZ wall must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CutsceneKnucklesCnz2AInstance.class.getName()),
                "CNZ2 Knuckles cutscene parent must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CutsceneKnucklesRockChild.class.getName()),
                "AIZ rock child must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CutsceneKnuxCnz2WallInstance.class.getName()),
                "CNZ wall must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureFailsWhenCutsceneKnucklesRequiredParentHasNoRewindIdentity() {
        Harness aizHarness = Harness.create(List.of());
        CutsceneKnucklesAiz1Instance unmanagedAizParent = new CutsceneKnucklesAiz1Instance(AIZ_PARENT_SPAWN);
        unmanagedAizParent.setServices(aizHarness.services());
        CutsceneKnucklesRockChild rock = aizHarness.objectManager().createDynamicObject(
                () -> new CutsceneKnucklesRockChild(AIZ_ROCK_SPAWN, unmanagedAizParent));
        assertSame(unmanagedAizParent, readObjectField(rock, "parent"),
                "precondition: AIZ rock parent is outside ObjectManager identity registration");

        IllegalStateException aizThrown = assertThrows(
                IllegalStateException.class, registryFor(aizHarness.objectManager())::capture);
        assertTrue(aizThrown.getMessage().contains("no registered id for object reference"),
                "missing AIZ parent identity must fail loudly");

        Harness cnzHarness = Harness.create(List.of());
        CutsceneKnucklesCnz2AInstance unmanagedCnzParent =
                new CutsceneKnucklesCnz2AInstance(CNZ_PARENT_SPAWN);
        unmanagedCnzParent.setServices(cnzHarness.services());
        CutsceneKnuxCnz2WallInstance wall = cnzHarness.objectManager().createDynamicObject(
                () -> new CutsceneKnuxCnz2WallInstance(CNZ_WALL_SPAWN, unmanagedCnzParent));
        assertSame(unmanagedCnzParent, readObjectField(wall, "owner"),
                "precondition: CNZ wall owner is outside ObjectManager identity registration");

        IllegalStateException cnzThrown = assertThrows(
                IllegalStateException.class, registryFor(cnzHarness.objectManager())::capture);
        assertTrue(cnzThrown.getMessage().contains("no registered id for object reference"),
                "missing CNZ owner identity must fail loudly");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create(List<ObjectSpawn> spawns) {
            return create(spawns, 0, 0);
        }

        static Harness create(List<ObjectSpawn> spawns, int cameraX, int cameraY) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera(cameraX, cameraY);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(cameraX);
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return liveObjects(objectManager, type).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> live = liveObjects(objectManager, type);
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kCutsceneKnucklesGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
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

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
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

    private static void setEnumField(Object target, String fieldName, String enumConstant) {
        try {
            Field field = findField(target.getClass(), fieldName);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Enum<?> value = Enum.valueOf((Class<? extends Enum>) field.getType(), enumConstant);
            field.set(target, value);
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

    private static Camera mockCamera(int cameraX, int cameraY) {
        return new Camera() {
            @Override public short getX() { return (short) cameraX; }
            @Override public short getY() { return (short) cameraY; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x700; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
