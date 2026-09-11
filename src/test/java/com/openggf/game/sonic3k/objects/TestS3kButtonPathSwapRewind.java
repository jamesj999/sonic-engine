package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kButtonPathSwapRewind {

    private static final ObjectSpawn BUTTON_SPAWN =
            new ObjectSpawn(0x02E0, 0x0140, Sonic3kObjectIds.BUTTON, 0x75, 0, false, 61);
    private static final ObjectSpawn PATH_SWAP_SPAWN =
            new ObjectSpawn(0x0340, 0x0100, Sonic3kObjectIds.PATH_SWAP, 0, 0, false, 62);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        Sonic3kLevelTriggerManager.reset();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x500, 0);
    }

    @AfterEach
    void tearDown() {
        Sonic3kLevelTriggerManager.reset();
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void buttonAndPathSwapRestoreFreshWithoutDropsAndPreserveState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic3kButtonObjectInstance button = objectManager.createDynamicObject(
                () -> new Sonic3kButtonObjectInstance(BUTTON_SPAWN));
        Sonic3kPathSwapObjectInstance pathSwap = objectManager.createDynamicObject(
                () -> new Sonic3kPathSwapObjectInstance(PATH_SWAP_SPAWN));
        seedButtonState(button);

        ObjectRefId buttonId = objectId(objectManager, button);
        ObjectRefId pathSwapId = objectId(objectManager, pathSwap);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(button);
        objectManager.removeDynamicObject(pathSwap);
        Sonic3kButtonObjectInstance replacementButton = objectManager.createDynamicObject(
                () -> new Sonic3kButtonObjectInstance(
                        new ObjectSpawn(0x0100, 0x0100,
                                Sonic3kObjectIds.BUTTON, 0, 0, false, 63)));
        Sonic3kPathSwapObjectInstance replacementPathSwap = objectManager.createDynamicObject(
                () -> new Sonic3kPathSwapObjectInstance(
                        new ObjectSpawn(0x0140, 0x0100,
                                Sonic3kObjectIds.PATH_SWAP, 0, 0, false, 64)));
        Sonic3kLevelTriggerManager.reset();

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, Sonic3kButtonObjectInstance.class).size(),
                "restore must keep exactly the captured S3K button");
        assertEquals(1, liveObjects(objectManager, Sonic3kPathSwapObjectInstance.class).size(),
                "restore must keep exactly the captured S3K path swap marker");

        Sonic3kButtonObjectInstance restoredButton =
                objectById(objectManager, Sonic3kButtonObjectInstance.class, buttonId);
        Sonic3kPathSwapObjectInstance restoredPathSwap =
                objectById(objectManager, Sonic3kPathSwapObjectInstance.class, pathSwapId);

        assertNotSame(button, restoredButton, "restore must recreate the button");
        assertNotSame(pathSwap, restoredPathSwap, "restore must recreate the path swap marker");
        assertNotSame(replacementButton, restoredButton, "restore must drop the divergent button");
        assertNotSame(replacementPathSwap, restoredPathSwap,
                "restore must drop the divergent path swap marker");

        assertEquals(BUTTON_SPAWN, restoredButton.getSpawn(),
                "button spawn must restore exactly");
        assertEquals(PATH_SWAP_SPAWN, restoredPathSwap.getSpawn(),
                "path swap spawn must restore exactly");
        assertButtonConstructorState(restoredButton);
        assertSeededButtonState(restoredButton);

        restoredButton.update(0, null);

        assertTrue(Sonic3kLevelTriggerManager.testBit(5, 7),
                "restored standing latch must press the captured subtype's trigger bit on next update");
        assertEquals(1, readIntField(restoredButton, "mappingFrame"),
                "pressed button mapping frame must remain visible after the post-restore update");
    }

    @Test
    void buttonAndPathSwapUseRewindRecreatableWithoutExplicitCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic3kButtonObjectInstance.class),
                "Sonic3kButtonObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic3kPathSwapObjectInstance.class),
                "Sonic3kPathSwapObjectInstance must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic3kButtonObjectInstance.class.getName()),
                "Sonic3kButtonObjectInstance must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic3kPathSwapObjectInstance.class.getName()),
                "Sonic3kPathSwapObjectInstance must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
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
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static void seedButtonState(Sonic3kButtonObjectInstance button) {
        setBooleanField(button, "contactStanding", true);
        setIntField(button, "mappingFrame", 1);
    }

    private static void assertButtonConstructorState(Sonic3kButtonObjectInstance button) {
        assertEquals(5, readIntField(button, "triggerIndex"),
                "button trigger index must rebuild from subtype");
        assertEquals(7, readIntField(button, "triggerBit"),
                "button trigger bit must rebuild from subtype bit 6");
        assertTrue(readBooleanField(button, "toggleMode"),
                "button toggle mode must rebuild from subtype bit 4");
        assertTrue(readBooleanField(button, "topSolid"),
                "button top-solid mode must rebuild from subtype bit 5");
        assertEquals(BUTTON_SPAWN.y() + 4, readIntField(button, "adjustedY"),
                "button adjusted Y must rebuild from spawn");
        assertEquals(BUTTON_SPAWN.y() + 4, button.getY(),
                "button Y accessor must use rebuilt adjusted Y");
    }

    private static void assertSeededButtonState(Sonic3kButtonObjectInstance button) {
        assertTrue(readBooleanField(button, "contactStanding"),
                "button standing latch must restore exactly");
        assertEquals(1, readIntField(button, "mappingFrame"),
                "button mapping frame must restore exactly");
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

    private static <T extends ObjectInstance> List<T> liveObjects(
            ObjectManager objectManager,
            Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kButtonPathSwapRewind::slotIndex))
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
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x500; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
