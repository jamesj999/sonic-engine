package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic3k.objects.S3kSignpostInstance;
import com.openggf.game.sonic3k.objects.S3kSignpostStubChild;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestS3kSignpostStubGraphRewind {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void signpostStubGraphRestoresFreshAndRelinksRestoredSignpost() {
        Harness harness = Harness.create(new Sonic3kObjectRegistry());
        ObjectManager objectManager = harness.objectManager();
        S3kSignpostInstance sourceSignpost = objectManager.createDynamicObject(
                () -> new S3kSignpostInstance(0x180, 0));
        setIntField(sourceSignpost, "worldX", 0x1C0);
        setIntField(sourceSignpost, "worldY", 0x128);
        S3kSignpostStubChild sourceStub = objectManager.createDynamicObject(
                () -> new S3kSignpostStubChild(sourceSignpost));

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        ObjectManagerSnapshot.DynamicObjectEntry capturedSignpost =
                singleCapturedDynamic(snapshot, S3kSignpostInstance.class);
        ObjectManagerSnapshot.DynamicObjectEntry capturedStub =
                singleCapturedDynamic(snapshot, S3kSignpostStubChild.class);

        objectManager.removeDynamicObject(sourceStub);
        objectManager.removeDynamicObject(sourceSignpost);
        S3kSignpostInstance replacementSignpost = objectManager.createDynamicObject(
                () -> new S3kSignpostInstance(0x280, 1));
        setIntField(replacementSignpost, "worldX", 0x2A0);
        setIntField(replacementSignpost, "worldY", 0x180);
        S3kSignpostStubChild replacementStub = objectManager.createDynamicObject(
                () -> new S3kSignpostStubChild(replacementSignpost));

        rewindRegistry.restore(snapshot);

        assertEquals(1, liveObjects(objectManager, S3kSignpostInstance.class).size(),
                "restore must leave exactly one live S3K signpost");
        assertEquals(1, liveObjects(objectManager, S3kSignpostStubChild.class).size(),
                "restore must leave exactly one live S3K signpost stub");
        assertEquals(2, liveObjects(objectManager, ObjectInstance.class).stream()
                        .filter(object -> object.getClass() == S3kSignpostInstance.class
                                || object.getClass() == S3kSignpostStubChild.class)
                        .count(),
                "restore must not drop or duplicate the signpost/stub family");

        S3kSignpostInstance restoredSignpost =
                objectBySlot(objectManager, S3kSignpostInstance.class, capturedSignpost.slotIndex());
        S3kSignpostStubChild restoredStub =
                objectBySlot(objectManager, S3kSignpostStubChild.class, capturedStub.slotIndex());
        assertNotSame(sourceSignpost, restoredSignpost, "restore must recreate the removed signpost");
        assertNotSame(sourceStub, restoredStub, "restore must recreate the removed stub");
        assertNotSame(replacementSignpost, restoredSignpost,
                "restore must drop unrelated post-snapshot signposts");
        assertNotSame(replacementStub, restoredStub,
                "restore must drop unrelated post-snapshot stubs");
        assertEquals(capturedSignpost.slotIndex(), restoredSignpost.getSlotIndex(),
                "captured signpost dynamic slot identity must be preserved");
        assertEquals(capturedStub.slotIndex(), restoredStub.getSlotIndex(),
                "captured stub dynamic slot identity must be preserved");
        assertSame(restoredSignpost, readObjectField(restoredStub, "parent"),
                "restored stub must relink to the restored signpost");
        assertNotSame(sourceSignpost, readObjectField(restoredStub, "parent"),
                "restored stub must not retain the pre-restore signpost reference");
        assertEquals(restoredSignpost.getWorldX(), readIntField(restoredStub, "currentX"),
                "restored stub X must match the restored signpost");
        assertEquals(restoredSignpost.getWorldY() + 0x18, readIntField(restoredStub, "currentY"),
                "restored stub Y must match the restored signpost plus the post offset");
    }

    @Test
    void signpostStubDropsWhenNoLiveSignpostCanBeRelinked() {
        Harness harness = Harness.create(new Sonic3kObjectRegistry());
        ObjectManager objectManager = harness.objectManager();
        S3kSignpostInstance unmanagedParent = new S3kSignpostInstance(0x180, 0);
        setIntField(unmanagedParent, "worldX", 0x1C0);
        setIntField(unmanagedParent, "worldY", 0x128);
        S3kSignpostStubChild sourceStub = objectManager.createDynamicObject(
                () -> new S3kSignpostStubChild(unmanagedParent));

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = assertDoesNotThrow(rewindRegistry::capture,
                "transient signpost parent must not require a rewind identity when unmanaged");
        objectManager.removeDynamicObject(sourceStub);

        rewindRegistry.restore(snapshot);

        assertEquals(0, liveObjects(objectManager, S3kSignpostStubChild.class).size(),
                "stub must be dropped when no live signpost can be relinked");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(ObjectRegistry registry) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.<ObjectSpawn>of(),
                    registry,
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

    private static ObjectManagerSnapshot.DynamicObjectEntry singleCapturedDynamic(
            CompositeSnapshot snapshot, Class<? extends ObjectInstance> type) {
        ObjectManagerSnapshot objectSnapshot = (ObjectManagerSnapshot) snapshot.get("object-manager");
        List<ObjectManagerSnapshot.DynamicObjectEntry> matches = objectSnapshot.dynamicObjects().stream()
                .filter(entry -> type.getName().equals(entry.className()))
                .toList();
        assertEquals(1, matches.size(), "expected one captured dynamic " + type.getSimpleName());
        return matches.getFirst();
    }

    private static <T extends ObjectInstance> T objectBySlot(
            ObjectManager objectManager, Class<T> type, int slotIndex) {
        return liveObjects(objectManager, type).stream()
                .filter(object -> object instanceof AbstractObjectInstance aoi
                        && aoi.getSlotIndex() == slotIndex)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object in slot " + slotIndex));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> type.isAssignableFrom(object.getClass()) && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
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
