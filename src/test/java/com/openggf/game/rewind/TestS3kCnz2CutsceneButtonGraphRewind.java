package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Cnz2CutsceneButtonInstance;
import com.openggf.game.sonic3k.objects.CnzLightsFlashChildInstance;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2AInstance;
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

class TestS3kCnz2CutsceneButtonGraphRewind {
    private static final ObjectSpawn BUTTON_SPAWN =
            new ObjectSpawn(0x1E00, 0x0320, Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 30);
    private static final ObjectSpawn FLASH_SPAWN =
            new ObjectSpawn(0x1E00, 0x0324, Sonic3kObjectIds.CUTSCENE_BUTTON, 0, 0, false, 31);
    private static final ObjectSpawn DIVERGENT_BUTTON_SPAWN =
            new ObjectSpawn(0x2000, 0x0360, Sonic3kObjectIds.CUTSCENE_BUTTON, 6, 0, false, 32);
    private static final ObjectSpawn DIVERGENT_FLASH_SPAWN =
            new ObjectSpawn(0x2000, 0x0364, Sonic3kObjectIds.CUTSCENE_BUTTON, 0, 0, false, 33);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        CutsceneKnucklesCnz2AInstance.clearActiveInstanceForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void restoredUnpressedButtonUsesRecreatedActiveCutsceneForProximity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Cnz2CutsceneButtonInstance sourceButton = objectManager.createDynamicObject(
                () -> new Cnz2CutsceneButtonInstance(BUTTON_SPAWN));
        CutsceneKnucklesCnz2AInstance sourceCutscene = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesCnz2AInstance(new ObjectSpawn(
                        0x1E00, 0x0324, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 29)));
        setEnumField(sourceCutscene, "phase", "CAMERA_LOCK");
        CutsceneKnucklesCnz2AInstance.setActiveInstanceForTests(sourceCutscene);
        ObjectRefId buttonId = objectId(objectManager, sourceButton);
        ObjectRefId cutsceneId = objectId(objectManager, sourceCutscene);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(sourceButton);
        objectManager.removeDynamicObject(sourceCutscene);
        registryFor(objectManager).restore(snapshot);

        Cnz2CutsceneButtonInstance restoredButton =
                objectById(objectManager, Cnz2CutsceneButtonInstance.class, buttonId);
        CutsceneKnucklesCnz2AInstance restoredCutscene =
                objectById(objectManager, CutsceneKnucklesCnz2AInstance.class, cutsceneId);
        assertNotSame(sourceCutscene, restoredCutscene);
        assertSame(restoredCutscene, CutsceneKnucklesCnz2AInstance.getActiveInstance(),
                "post-restore relink must replace the stale static cutscene identity");

        restoredButton.update(0, null);

        assertTrue(readBooleanField(restoredButton, "pressed"),
                "proximity polling must resolve the recreated live cutscene, not the pre-restore object");
    }

    @Test
    void cnz2CutsceneButtonRestoresSpawnedFlashLinkWithoutDropsOrStaleRefs() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Cnz2CutsceneButtonInstance sourceButton = objectManager.createDynamicObject(
                () -> new Cnz2CutsceneButtonInstance(BUTTON_SPAWN));
        CnzLightsFlashChildInstance sourceFlash = objectManager.createDynamicObject(
                () -> new CnzLightsFlashChildInstance(FLASH_SPAWN, false));
        setBooleanField(sourceButton, "pressed", true);
        setObjectField(sourceButton, "spawnedFlash", sourceFlash);
        setBooleanField(sourceFlash, "restoreAfter", true);
        setIntField(sourceFlash, "step", 4);
        setIntField(sourceFlash, "timer", 7);

        ObjectRefId buttonId = objectId(objectManager, sourceButton);
        ObjectRefId flashId = objectId(objectManager, sourceFlash);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(sourceFlash);
        objectManager.removeDynamicObject(sourceButton);
        Cnz2CutsceneButtonInstance divergentButton = objectManager.createDynamicObject(
                () -> new Cnz2CutsceneButtonInstance(DIVERGENT_BUTTON_SPAWN));
        CnzLightsFlashChildInstance divergentFlash = objectManager.createDynamicObject(
                () -> new CnzLightsFlashChildInstance(DIVERGENT_FLASH_SPAWN, false));
        setObjectField(divergentButton, "spawnedFlash", divergentFlash);
        assertEquals(1, liveObjects(objectManager, Cnz2CutsceneButtonInstance.class).size(),
                "diverge step should leave one unrelated CNZ2 cutscene button before restore");
        assertEquals(1, liveObjects(objectManager, CnzLightsFlashChildInstance.class).size(),
                "diverge step should leave one unrelated CNZ lights flash before restore");

        registryFor(objectManager).restore(snapshot);

        Cnz2CutsceneButtonInstance restoredButton =
                objectById(objectManager, Cnz2CutsceneButtonInstance.class, buttonId);
        CnzLightsFlashChildInstance restoredFlash =
                objectById(objectManager, CnzLightsFlashChildInstance.class, flashId);
        assertEquals(1, liveObjects(objectManager, Cnz2CutsceneButtonInstance.class).size(),
                "restore must keep exactly the captured CNZ2 cutscene button");
        assertEquals(1, liveObjects(objectManager, CnzLightsFlashChildInstance.class).size(),
                "restore must keep exactly the captured CNZ lights flash");
        assertNotSame(sourceButton, restoredButton, "restore must recreate the CNZ2 cutscene button");
        assertNotSame(sourceFlash, restoredFlash, "restore must recreate the CNZ lights flash");
        assertNotSame(divergentButton, restoredButton, "restore must drop the divergent CNZ2 cutscene button");
        assertNotSame(divergentFlash, restoredFlash, "restore must drop the divergent CNZ lights flash");
        assertSame(restoredFlash, readObjectField(restoredButton, "spawnedFlash"),
                "CNZ2 button spawnedFlash must resolve to the restored flash child");
        assertSame(restoredButton, readObjectField(restoredFlash, "owner"),
                "CNZ lights flash owner must relink from the authoritative restored button reference");
        assertNotSame(sourceFlash, readObjectField(restoredButton, "spawnedFlash"),
                "CNZ2 button must not retain the stale pre-restore flash child");
        assertTrue(readBooleanField(restoredButton, "pressed"),
                "button pressed state must restore from compact state");
        assertEquals(BUTTON_SPAWN.subtype(), readIntField(restoredButton, "subtype"),
                "button subtype must restore from spawn/compact state");
        assertTrue(readBooleanField(restoredFlash, "restoreAfter"),
                "flash restoreAfter must restore from compact state");
        assertEquals(4, readIntField(restoredFlash, "step"),
                "flash step must restore from compact state");
        assertEquals(7, readIntField(restoredFlash, "timer"),
                "flash timer must restore from compact state");
    }

    @Test
    void cnz2CutsceneButtonUsesGenericRecreateWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Cnz2CutsceneButtonInstance.class),
                "CNZ2 cutscene button must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Cnz2CutsceneButtonInstance.class.getName()),
                "CNZ2 cutscene button must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureFailsWhenCnz2ButtonFlashHasNoRewindIdentity() {
        Harness harness = Harness.create();
        Cnz2CutsceneButtonInstance button = harness.objectManager().createDynamicObject(
                () -> new Cnz2CutsceneButtonInstance(BUTTON_SPAWN));
        CnzLightsFlashChildInstance unmanagedFlash = new CnzLightsFlashChildInstance(FLASH_SPAWN, false);
        unmanagedFlash.setServices(harness.services());
        setObjectField(button, "spawnedFlash", unmanagedFlash);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, registryFor(harness.objectManager())::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing CNZ flash identity must fail loudly");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(camera.getX());
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

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kCnz2CutsceneButtonGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance instance ? instance.getSlotIndex() : -1;
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

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setEnumField(Object target, String fieldName, String constant) {
        try {
            Field field = findField(target.getClass(), fieldName);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object value = Enum.valueOf((Class<? extends Enum>) field.getType(), constant);
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

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0x1D80; }
            @Override public short getY() { return 0x0200; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x700; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
