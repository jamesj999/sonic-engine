package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.LbzMinibossBoxKnuxInstance;
import com.openggf.game.sonic3k.objects.LbzMinibossInstance;
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
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kLbzMinibossGraphRewind {
    private static final ObjectSpawn BOX_SPAWN =
            new ObjectSpawn(0x3C40, 0x0936, Sonic3kObjectIds.LBZ_MINIBOSS_BOX_KNUX, 0, 0, false, 50);
    private static final ObjectSpawn LEFT_MINIBOSS_SPAWN =
            new ObjectSpawn(0x3C20, 0x0936, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 51);
    private static final ObjectSpawn RIGHT_MINIBOSS_SPAWN =
            new ObjectSpawn(0x3C60, 0x0936, Sonic3kObjectIds.LBZ_MINIBOSS, 2, 0, false, 52);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void knucklesMinibossesRestoreFreshWithRestoredBoxParentRefsAndScalars() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        LbzMinibossBoxKnuxInstance sourceBox = objectManager.createDynamicObject(
                () -> new LbzMinibossBoxKnuxInstance(BOX_SPAWN));
        LbzMinibossInstance sourceLeft = createMiniboss(objectManager, LEFT_MINIBOSS_SPAWN, sourceBox);
        LbzMinibossInstance sourceRight = createMiniboss(objectManager, RIGHT_MINIBOSS_SPAWN, sourceBox);
        sourceLeft.forceOpenForTest(0x3C20, 0x0930);
        sourceRight.forceOpenForTest(0x3C60, 0x0940);
        writeIntField(sourceLeft, "hitReactionTimer", 7);
        writeIntField(sourceRight, "waitTimer", 11);

        ObjectRefId boxId = objectId(objectManager, sourceBox);
        ObjectRefId leftId = objectId(objectManager, sourceLeft);
        ObjectRefId rightId = objectId(objectManager, sourceRight);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceLeft);
        LbzMinibossInstance divergentLeft = createMiniboss(
                objectManager,
                new ObjectSpawn(0x3D20, 0x0940, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 53),
                sourceBox);
        assertEquals(2, liveObjects(objectManager, LbzMinibossInstance.class).size(),
                "diverge step should leave one captured miniboss plus one unrelated miniboss before restore");

        rewindRegistry.restore(snapshot);

        LbzMinibossBoxKnuxInstance restoredBox =
                objectById(objectManager, LbzMinibossBoxKnuxInstance.class, boxId);
        LbzMinibossInstance restoredLeft =
                objectById(objectManager, LbzMinibossInstance.class, leftId);
        LbzMinibossInstance restoredRight =
                objectById(objectManager, LbzMinibossInstance.class, rightId);
        assertEquals(1, liveObjects(objectManager, LbzMinibossBoxKnuxInstance.class).size(),
                "restore must keep exactly one LBZ Knuckles box parent");
        assertEquals(2, liveObjects(objectManager, LbzMinibossInstance.class).size(),
                "restore must keep exactly the captured LBZ miniboss pair");
        assertNotSame(sourceBox, restoredBox, "restore must recreate the LBZ Knuckles box");
        assertNotSame(sourceLeft, restoredLeft, "restore must recreate the left miniboss");
        assertNotSame(sourceRight, restoredRight, "restore must recreate the right miniboss");
        assertNotSame(divergentLeft, restoredLeft, "restore must drop the divergent miniboss");
        assertSame(restoredBox, readObjectField(restoredLeft, "knucklesFightParent"),
                "left miniboss parent must resolve to the restored box");
        assertSame(restoredBox, readObjectField(restoredRight, "knucklesFightParent"),
                "right miniboss parent must resolve to the restored box");
        assertNotSame(sourceBox, readObjectField(restoredLeft, "knucklesFightParent"),
                "left miniboss must not retain the stale pre-restore box");
        assertEquals(7, restoredLeft.getHitReactionTimerForTest(),
                "left miniboss hit reaction timer must restore from compact state");
        assertEquals(11, restoredRight.getWaitTimerForTest(),
                "right miniboss wait timer must restore from compact state");
    }

    @Test
    void lbzMinibossUsesGenericRecreateWithoutExplicitS3kCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(LbzMinibossInstance.class),
                "LBZ miniboss must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(LbzMinibossInstance.class.getName()),
                "LBZ miniboss must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureFailsWhenKnucklesMinibossParentHasNoRewindIdentity() throws Exception {
        Harness harness = Harness.create();
        LbzMinibossBoxKnuxInstance unmanagedBox = new LbzMinibossBoxKnuxInstance(BOX_SPAWN);
        unmanagedBox.setServices(harness.services());
        LbzMinibossInstance miniboss = createMiniboss(
                harness.objectManager(), LEFT_MINIBOSS_SPAWN, unmanagedBox);
        assertSame(unmanagedBox, readObjectField(miniboss, "knucklesFightParent"),
                "precondition: miniboss parent is outside ObjectManager identity registration");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> registryFor(harness.objectManager()).capture());
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing LBZ miniboss parent identity must fail loudly");
    }

    private static LbzMinibossInstance createMiniboss(
            ObjectManager objectManager,
            ObjectSpawn spawn,
            LbzMinibossBoxKnuxInstance parent) {
        return objectManager.createDynamicObject(() -> {
            LbzMinibossInstance miniboss = new LbzMinibossInstance(spawn);
            miniboss.setKnucklesFightParent(parent);
            return miniboss;
        });
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
                    null,
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
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager,
            Class<T> type,
            ObjectRefId id) {
        return liveObjects(objectManager, type).stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(ObjectInstance::getX))
                .toList();
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        return field(target, name).getInt(target);
    }

    private static void writeIntField(Object target, String name, int value) throws Exception {
        field(target, name).setInt(target, value);
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

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0x3B80; }
            @Override public short getY() { return 0x0900; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
