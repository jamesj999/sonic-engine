package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kDecorativeObjectRewind {

    private static final ObjectSpawn FOREGROUND_PLANT_SPAWN =
            new ObjectSpawn(0x02C0, 0x0120, Sonic3kObjectIds.AIZ_FOREGROUND_PLANT,
                    0x61, 0, false, 41);
    private static final ObjectSpawn ANIMATED_STILL_SPAWN =
            new ObjectSpawn(0x0340, 0x00F0, Sonic3kObjectIds.ANIMATED_STILL_SPRITE,
                    1, 0, false, 42);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x500, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void decorativeObjectsRestoreFreshWithoutDropsAndPreserveState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        AizForegroundPlantInstance plant = objectManager.createDynamicObject(
                () -> new AizForegroundPlantInstance(FOREGROUND_PLANT_SPAWN));
        AnimatedStillSpriteInstance animated = objectManager.createDynamicObject(
                () -> new AnimatedStillSpriteInstance(ANIMATED_STILL_SPAWN));
        seedAnimatedState(animated);

        ObjectRefId plantId = objectId(objectManager, plant);
        ObjectRefId animatedId = objectId(objectManager, animated);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(plant);
        objectManager.removeDynamicObject(animated);
        AizForegroundPlantInstance replacementPlant = objectManager.createDynamicObject(
                () -> new AizForegroundPlantInstance(
                        new ObjectSpawn(0x0100, 0x0100,
                                Sonic3kObjectIds.AIZ_FOREGROUND_PLANT,
                                0, 0, false, 43)));
        AnimatedStillSpriteInstance replacementAnimated = objectManager.createDynamicObject(
                () -> new AnimatedStillSpriteInstance(
                        new ObjectSpawn(0x0140, 0x0100,
                                Sonic3kObjectIds.ANIMATED_STILL_SPRITE,
                                0, 0, false, 44)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, AizForegroundPlantInstance.class).size(),
                "restore must keep exactly the captured foreground plant");
        assertEquals(1, liveObjects(objectManager, AnimatedStillSpriteInstance.class).size(),
                "restore must keep exactly the captured animated still sprite");

        AizForegroundPlantInstance restoredPlant =
                objectById(objectManager, AizForegroundPlantInstance.class, plantId);
        AnimatedStillSpriteInstance restoredAnimated =
                objectById(objectManager, AnimatedStillSpriteInstance.class, animatedId);

        assertNotSame(plant, restoredPlant, "restore must recreate the foreground plant");
        assertNotSame(animated, restoredAnimated, "restore must recreate the animated still sprite");
        assertNotSame(replacementPlant, restoredPlant, "restore must drop the divergent foreground plant");
        assertNotSame(replacementAnimated, restoredAnimated,
                "restore must drop the divergent animated still sprite");

        assertEquals(FOREGROUND_PLANT_SPAWN, restoredPlant.getSpawn(),
                "foreground plant spawn must restore exactly");
        assertEquals(0x02C0, readIntField(restoredPlant, "origX"),
                "foreground plant X anchor must be rebuilt from spawn");
        assertEquals(0x0120, readIntField(restoredPlant, "origY"),
                "foreground plant Y anchor must be rebuilt from spawn");
        assertEquals(1, readIntField(restoredPlant, "mappingFrame"),
                "foreground plant mapping frame must be rebuilt from subtype");
        assertEquals(6, readIntField(restoredPlant, "scrollRate"),
                "foreground plant scroll rate must be rebuilt from subtype");

        assertEquals(ANIMATED_STILL_SPAWN, restoredAnimated.getSpawn(),
                "animated still sprite spawn must restore exactly");
        assertEquals(3, readIntField(restoredAnimated, "animDelay"),
                "animated still sprite delay must be rebuilt from subtype");
        assertArrayEquals(new int[]{5, 6, 7, 8},
                readIntArrayField(restoredAnimated, "animFrames"),
                "animated still sprite script must be rebuilt from subtype");
        assertSeededAnimatedState(restoredAnimated);
    }

    @Test
    void decorativeObjectsUseRewindRecreatableWithoutExplicitCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(AizForegroundPlantInstance.class),
                "AizForegroundPlantInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(AnimatedStillSpriteInstance.class),
                "AnimatedStillSpriteInstance must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizForegroundPlantInstance.class.getName()),
                "AizForegroundPlantInstance must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AnimatedStillSpriteInstance.class.getName()),
                "AnimatedStillSpriteInstance must not keep an explicit S3K dynamic codec");
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

    private static void seedAnimatedState(AnimatedStillSpriteInstance animated) {
        setIntField(animated, "animScriptIndex", 2);
        setIntField(animated, "animTimer", 0x12);
        setIntField(animated, "currentGlobalFrame", 7);
    }

    private static void assertSeededAnimatedState(AnimatedStillSpriteInstance animated) {
        assertEquals(2, readIntField(animated, "animScriptIndex"),
                "animated still sprite script index must restore exactly");
        assertEquals(0x12, readIntField(animated, "animTimer"),
                "animated still sprite timer must restore exactly");
        assertEquals(7, readIntField(animated, "currentGlobalFrame"),
                "animated still sprite frame must restore exactly");
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
                .sorted(Comparator.comparingInt(TestS3kDecorativeObjectRewind::slotIndex))
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

    private static int[] readIntArrayField(Object target, String fieldName) {
        try {
            int[] value = (int[]) findField(target.getClass(), fieldName).get(target);
            return Arrays.copyOf(value, value.length);
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
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x500; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
