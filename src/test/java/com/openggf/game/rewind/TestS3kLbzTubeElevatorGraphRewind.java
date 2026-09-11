package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.LbzTubeElevatorInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kLbzTubeElevatorGraphRewind {
    private static final String OVERLAY_CLASS =
            "com.openggf.game.sonic3k.objects.LbzTubeElevatorInstance$OverlayChild";
    private static final ObjectSpawn ELEVATOR_SPAWN =
            new ObjectSpawn(0x1200, 0x0520, Sonic3kObjectIds.LBZ_TUBE_ELEVATOR, 0, 0, false, 15);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void tubeElevatorOverlayRestoresFreshRelinkParentAndDoesNotDuplicate() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        LbzTubeElevatorInstance sourceElevator = objectManager.createDynamicObject(
                () -> new LbzTubeElevatorInstance(ELEVATOR_SPAWN));
        sourceElevator.update(0, null);
        ObjectInstance sourceOverlay = onlyOverlay(objectManager);
        ObjectRefId elevatorId = objectId(objectManager, sourceElevator);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceOverlay);
        ObjectInstance divergentOverlay = objectManager.createDynamicObject(
                () -> constructOverlay(sourceElevator));
        assertEquals(1, liveOverlays(objectManager).size(),
                "diverge step should leave one unrelated tube overlay before restore");

        rewindRegistry.restore(snapshot);

        LbzTubeElevatorInstance restoredElevator =
                objectById(objectManager, LbzTubeElevatorInstance.class, elevatorId);
        List<ObjectInstance> restoredOverlays = liveOverlays(objectManager);
        assertEquals(1, restoredOverlays.size(),
                "restore must keep exactly the captured tube overlay");
        ObjectInstance restoredOverlay = restoredOverlays.getFirst();
        assertNotSame(sourceElevator, restoredElevator,
                "restore must recreate the LBZ tube elevator parent");
        assertNotSame(sourceOverlay, restoredOverlay,
                "restore must recreate the LBZ tube overlay");
        assertNotSame(divergentOverlay, restoredOverlay,
                "restore must drop the divergent tube overlay");
        assertSame(restoredElevator, readObjectField(restoredOverlay, "parent"),
                "tube overlay parent must resolve to the restored LBZ tube elevator");
        assertNotSame(sourceElevator, readObjectField(restoredOverlay, "parent"),
                "tube overlay must not retain the stale pre-restore parent");
        assertSame(restoredOverlay, readObjectField(restoredElevator, "overlayChild"),
                "restored LBZ tube elevator must point at the restored overlay child");

        restoredElevator.update(1, null);
        assertEquals(1, liveOverlays(objectManager).size(),
                "post-restore update must not spawn a duplicate tube overlay");
    }

    @Test
    void tubeElevatorFamilyUsesGenericRecreateWithoutExplicitS3kCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(LbzTubeElevatorInstance.class),
                "LBZ tube elevator must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(OVERLAY_CLASS)),
                "LBZ tube overlay must restore through generic graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        LbzTubeElevatorInstance.class.getName()),
                "LBZ tube elevator must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(OVERLAY_CLASS),
                "LBZ tube overlay must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
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
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectInstance constructOverlay(LbzTubeElevatorInstance parent) {
        try {
            Class<?> overlayType = Class.forName(OVERLAY_CLASS);
            Constructor<?> ctor = overlayType.getDeclaredConstructor(LbzTubeElevatorInstance.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct LBZ tube overlay", e);
        }
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager,
            Class<T> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectInstance onlyOverlay(ObjectManager objectManager) {
        List<ObjectInstance> overlays = liveOverlays(objectManager);
        assertEquals(1, overlays.size(), "expected exactly one live LBZ tube overlay");
        return overlays.getFirst();
    }

    private static List<ObjectInstance> liveOverlays(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(OVERLAY_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.get(target);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
