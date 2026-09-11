package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.EggPrisonButtonObjectInstance;
import com.openggf.game.sonic2.objects.EggPrisonObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2EggPrisonButtonGraphRewind {
    private static final ObjectSpawn PRISON_SPAWN =
            new ObjectSpawn(0x180, 0x180, Sonic2ObjectIds.EGG_PRISON, 0, 0, false, 0, 60);
    private static final ObjectSpawn DISTRACTOR_SPAWN =
            new ObjectSpawn(0x1C0, 0x180, Sonic2ObjectIds.EGG_PRISON, 0, 0, false, 0, 61);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void eggPrisonButtonGraphRestoresFreshButtonParentBackrefAndScalars() {
        Harness harness = Harness.create(List.of(PRISON_SPAWN, DISTRACTOR_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        spawnButtons(objectManager);
        EggPrisonObjectInstance sourceParent = parentAt(PRISON_SPAWN, objectManager);
        EggPrisonButtonObjectInstance sourceButton = buttonAt(PRISON_SPAWN, objectManager);
        EggPrisonObjectInstance distractorParent = parentAt(DISTRACTOR_SPAWN, objectManager);
        EggPrisonButtonObjectInstance distractorButton = buttonAt(DISTRACTOR_SPAWN, objectManager);
        pressButton(sourceButton);
        setIntField(sourceButton, "currentY", PRISON_SPAWN.y() - 40 + 3);

        ObjectRefId parentId = objectId(objectManager, sourceParent);
        ObjectRefId buttonId = objectId(objectManager, sourceButton);
        ObjectRefId distractorParentId = objectId(objectManager, distractorParent);
        ObjectRefId distractorButtonId = objectId(objectManager, distractorButton);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceButton);
        EggPrisonButtonObjectInstance divergentButton = objectManager.createDynamicObject(
                () -> new EggPrisonButtonObjectInstance(
                        new ObjectSpawn(0x200, 0x1C0, Sonic2ObjectIds.EGG_PRISON, 0, 0, false, 0, 62),
                        distractorParent));
        assertEquals(2, liveObjects(objectManager, EggPrisonButtonObjectInstance.class).size(),
                "diverge step should leave one captured distractor and one unrelated button");

        rewindRegistry.restore(snapshot);

        EggPrisonObjectInstance restoredParent =
                objectById(objectManager, EggPrisonObjectInstance.class, parentId);
        EggPrisonButtonObjectInstance restoredButton =
                objectById(objectManager, EggPrisonButtonObjectInstance.class, buttonId);
        EggPrisonObjectInstance restoredDistractorParent =
                objectById(objectManager, EggPrisonObjectInstance.class, distractorParentId);
        EggPrisonButtonObjectInstance restoredDistractorButton =
                objectById(objectManager, EggPrisonButtonObjectInstance.class, distractorButtonId);

        assertEquals(2, liveObjects(objectManager, EggPrisonObjectInstance.class).size(),
                "restore must not drop or duplicate Egg Prison parents");
        assertEquals(2, liveObjects(objectManager, EggPrisonButtonObjectInstance.class).size(),
                "restore must not drop or duplicate Egg Prison buttons");
        assertNotSame(sourceParent, restoredParent, "restore must recreate the captured parent");
        assertNotSame(sourceButton, restoredButton, "restore must recreate the captured button");
        assertNotSame(divergentButton, restoredButton, "restore must drop divergent button objects");
        assertNotSame(distractorParent, restoredDistractorParent,
                "restore must recreate the distractor parent too");
        assertNotSame(distractorButton, restoredDistractorButton,
                "restore must recreate the distractor button too");

        assertSame(restoredParent, readObjectField(restoredButton, "parent"),
                "button parent must resolve to the matching restored Egg Prison parent");
        assertNotSame(sourceParent, readObjectField(restoredButton, "parent"),
                "button parent must not retain the stale pre-restore parent");
        assertNotSame(restoredDistractorParent, readObjectField(restoredButton, "parent"),
                "button parent must not relink to a distractor Egg Prison");
        assertSame(restoredButton, readObjectField(restoredParent, "buttonObject"),
                "parent buttonObject must point back at the restored button");

        assertEquals(PRISON_SPAWN.y() - 40, readIntField(restoredButton, "baseY"),
                "spawn-derived final baseY must be recomputed from the captured spawn");
        assertEquals(PRISON_SPAWN.y() - 40 + 3, readIntField(restoredButton, "currentY"),
                "button currentY scalar must round-trip");
        assertTrue(readBooleanField(restoredButton, "triggered"),
                "button triggered scalar must round-trip");
    }

    @Test
    void detachedEggPrisonButtonRoundTripsWithNullParent() {
        Harness harness = Harness.create(List.of(PRISON_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        spawnButtons(objectManager);
        EggPrisonButtonObjectInstance sourceButton = buttonAt(PRISON_SPAWN, objectManager);
        sourceButton.detachFromParent();
        setIntField(sourceButton, "currentY", PRISON_SPAWN.y() - 40 + 8);

        ObjectRefId buttonId = objectId(objectManager, sourceButton);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceButton);
        rewindRegistry.restore(snapshot);

        EggPrisonButtonObjectInstance restoredButton =
                objectById(objectManager, EggPrisonButtonObjectInstance.class, buttonId);
        assertNull(readObjectField(restoredButton, "parent"),
                "detached button parent must round-trip as null");
        assertEquals(PRISON_SPAWN.y() - 40 + 8, readIntField(restoredButton, "currentY"),
                "detached button scalar state must still round-trip");
    }

    @Test
    void genericRecreateRestoresDetachedEggPrisonButtonWithNoLiveParent() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        EggPrisonButtonObjectInstance sourceButton =
                new EggPrisonButtonObjectInstance(PRISON_SPAWN, null);
        setIntField(sourceButton, "currentY", PRISON_SPAWN.y() - 40 + 8);

        ObjectManagerSnapshot.DynamicObjectEntry entry = new ObjectManagerSnapshot.DynamicObjectEntry(
                EggPrisonButtonObjectInstance.class.getName(),
                PRISON_SPAWN,
                0,
                sourceButton.captureRewindState());

        ObjectInstance recreated = ObjectRewindDynamicCodecs.genericRecreate(
                entry, new DynamicObjectRecreateContext(objectManager));

        assertNotNull(recreated,
                "genericRecreate must not require a live Egg Prison parent for detached buttons");
        EggPrisonButtonObjectInstance restoredButton =
                (EggPrisonButtonObjectInstance) recreated;
        restoredButton.restoreRewindState(entry.state());
        assertNull(readObjectField(restoredButton, "parent"),
                "detached button must recreate with no parent when no live parent exists");
        assertEquals(PRISON_SPAWN.y() - 40 + 8, readIntField(restoredButton, "currentY"),
                "detached button scalar state must still restore after generic recreate");
    }

    @Test
    void captureFailsWhenManagedButtonHasUnmanagedNonNullParent() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        EggPrisonObjectInstance unmanagedParent =
                new EggPrisonObjectInstance(PRISON_SPAWN, "unmanaged");
        unmanagedParent.setServices(harness.services());
        EggPrisonButtonObjectInstance button = objectManager.createDynamicObject(
                () -> new EggPrisonButtonObjectInstance(PRISON_SPAWN, unmanagedParent));
        assertSame(unmanagedParent, readObjectField(button, "parent"),
                "precondition: button parent is outside ObjectManager identity registration");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, registryFor(objectManager)::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing non-null required parent identity must fail loudly");
    }

    @Test
    void eggPrisonGraphUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(EggPrisonObjectInstance.class),
                "EggPrisonObjectInstance must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(EggPrisonButtonObjectInstance.class),
                "EggPrisonButtonObjectInstance must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        EggPrisonObjectInstance.class.getName()),
                "EggPrisonObjectInstance must not keep an explicit S2 dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        EggPrisonButtonObjectInstance.class.getName()),
                "EggPrisonButtonObjectInstance must not keep an explicit S2 dynamic codec");
    }

    private static void spawnButtons(ObjectManager objectManager) {
        objectManager.update(0, new TestablePlayableSprite("sonic", (short) 0, (short) 0), List.of(), 0);
        assertEquals(liveObjects(objectManager, EggPrisonObjectInstance.class).size(),
                liveObjects(objectManager, EggPrisonButtonObjectInstance.class).size(),
                "each managed Egg Prison parent should spawn one button through its real update path");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
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

    private static EggPrisonObjectInstance parentAt(ObjectSpawn spawn, ObjectManager objectManager) {
        return liveObjects(objectManager, EggPrisonObjectInstance.class).stream()
                .filter(parent -> parent.getSpawn() == spawn)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing parent at " + spawn));
    }

    private static EggPrisonButtonObjectInstance buttonAt(ObjectSpawn spawn, ObjectManager objectManager) {
        return liveObjects(objectManager, EggPrisonButtonObjectInstance.class).stream()
                .filter(button -> button.getSpawn() == spawn)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing button at " + spawn));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS2EggPrisonButtonGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof com.openggf.level.objects.AbstractObjectInstance aoi
                ? aoi.getSlotIndex()
                : -1;
    }

    private static void pressButton(EggPrisonButtonObjectInstance button) {
        setBooleanField(button, "triggered", true);
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
