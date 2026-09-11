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
import com.openggf.level.objects.ObjectRegistry;
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

class TestAizSpikedLogGraphRewind {

    private static final ObjectSpawn LOG_A =
            new ObjectSpawn(0x0100, 0x0120, Sonic3kObjectIds.AIZ_SPIKED_LOG, 0, 0, false, 10);
    private static final ObjectSpawn LOG_B =
            new ObjectSpawn(0x0260, 0x0160, Sonic3kObjectIds.AIZ_SPIKED_LOG, 0, 0, false, 11);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x400, 0x300, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void spikedLogChildrenRestoreFreshAndRelinkExactRestoredParents() {
        Harness harness = Harness.create(new Sonic3kObjectRegistry(), List.of(LOG_A, LOG_B));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        List<AizSpikedLogObjectInstance> sourceParents =
                liveObjects(objectManager, AizSpikedLogObjectInstance.class);
        assertEquals(2, sourceParents.size(), "precondition: two spiked logs must be live");
        AizSpikedLogObjectInstance sourceParentA = sourceParents.get(0);
        AizSpikedLogObjectInstance sourceParentB = sourceParents.get(1);

        sourceParentA.update(0, null);
        sourceParentB.update(0, null);
        List<AizSpikedLogObjectInstance.SpikedLogCollisionChild> sourceChildren =
                liveObjects(objectManager, AizSpikedLogObjectInstance.SpikedLogCollisionChild.class);
        assertEquals(2, sourceChildren.size(), "precondition: each log must spawn one spike child");
        AizSpikedLogObjectInstance.SpikedLogCollisionChild sourceChildA =
                childWithParent(sourceChildren, sourceParentA);
        AizSpikedLogObjectInstance.SpikedLogCollisionChild sourceChildB =
                childWithParent(sourceChildren, sourceParentB);
        setIntField(sourceChildA, "currentX", 0x0130);
        setIntField(sourceChildA, "currentY", 0x0144);
        setIntField(sourceChildB, "currentX", 0x0280);
        setIntField(sourceChildB, "currentY", 0x0178);

        ObjectRefId parentAId = objectId(objectManager, sourceParentA);
        ObjectRefId parentBId = objectId(objectManager, sourceParentB);
        ObjectRefId childAId = objectId(objectManager, sourceChildA);
        ObjectRefId childBId = objectId(objectManager, sourceChildB);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceChildA);
        objectManager.removeDynamicObject(sourceChildB);
        AizSpikedLogObjectInstance replacementParent =
                new AizSpikedLogObjectInstance(new ObjectSpawn(
                        0x0320, 0x0180, Sonic3kObjectIds.AIZ_SPIKED_LOG, 0, 0, false, 12));
        AizSpikedLogObjectInstance.SpikedLogCollisionChild replacementChild =
                objectManager.createDynamicObject(
                        () -> new AizSpikedLogObjectInstance.SpikedLogCollisionChild(
                                new ObjectSpawn(
                                        0x0320, 0x0180, Sonic3kObjectIds.AIZ_SPIKED_LOG, 0, 0, false, 13),
                                replacementParent));
        assertEquals(1, liveObjects(objectManager,
                        AizSpikedLogObjectInstance.SpikedLogCollisionChild.class).size(),
                "diverge step should leave one unrelated spike child before restore");

        rewindRegistry.restore(snapshot);

        AizSpikedLogObjectInstance restoredParentA =
                objectById(objectManager, AizSpikedLogObjectInstance.class, parentAId);
        AizSpikedLogObjectInstance restoredParentB =
                objectById(objectManager, AizSpikedLogObjectInstance.class, parentBId);
        AizSpikedLogObjectInstance.SpikedLogCollisionChild restoredChildA =
                objectById(objectManager, AizSpikedLogObjectInstance.SpikedLogCollisionChild.class, childAId);
        AizSpikedLogObjectInstance.SpikedLogCollisionChild restoredChildB =
                objectById(objectManager, AizSpikedLogObjectInstance.SpikedLogCollisionChild.class, childBId);

        assertEquals(2, liveObjects(objectManager, AizSpikedLogObjectInstance.class).size(),
                "restore must keep exactly the captured two spiked-log parents");
        assertEquals(2, liveObjects(objectManager,
                        AizSpikedLogObjectInstance.SpikedLogCollisionChild.class).size(),
                "restore must keep exactly the captured two spike children");
        assertNotSame(sourceParentA, restoredParentA, "restore must recreate parent A");
        assertNotSame(sourceParentB, restoredParentB, "restore must recreate parent B");
        assertNotSame(sourceChildA, restoredChildA, "restore must recreate child A");
        assertNotSame(sourceChildB, restoredChildB, "restore must recreate child B");
        assertNotSame(replacementChild, restoredChildA, "restore must drop unrelated replacement child");
        assertNotSame(replacementChild, restoredChildB, "restore must drop unrelated replacement child");

        assertSame(restoredParentA, readObjectField(restoredChildA, "parent"),
                "child A parent must resolve to restored parent A by captured identity");
        assertSame(restoredParentB, readObjectField(restoredChildB, "parent"),
                "child B parent must resolve to restored parent B by captured identity");
        assertNotSame(sourceParentA, readObjectField(restoredChildA, "parent"),
                "child A must not retain the stale pre-restore parent");
        assertNotSame(sourceParentB, readObjectField(restoredChildB, "parent"),
                "child B must not retain the stale pre-restore parent");
        assertEquals(0x0130, readIntField(restoredChildA, "currentX"));
        assertEquals(0x0144, readIntField(restoredChildA, "currentY"));
        assertEquals(0x0280, readIntField(restoredChildB, "currentX"));
        assertEquals(0x0178, readIntField(restoredChildB, "currentY"));

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(parentAId, restoredTable.idFor(restoredParentA),
                "restored parent A must retain its captured rewind identity");
        assertEquals(parentBId, restoredTable.idFor(restoredParentB),
                "restored parent B must retain its captured rewind identity");
        assertEquals(childAId, restoredTable.idFor(restoredChildA),
                "restored child A must retain its captured rewind identity");
        assertEquals(childBId, restoredTable.idFor(restoredChildB),
                "restored child B must retain its captured rewind identity");

        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        AizSpikedLogObjectInstance.SpikedLogCollisionChild.class),
                "SpikedLogCollisionChild must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizSpikedLogObjectInstance.SpikedLogCollisionChild.class.getName()),
                "SpikedLogCollisionChild must restore through graph-tested generic recreate, not a codec");
    }

    @Test
    void spikedLogParentUsesGenericRecreateWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(AizSpikedLogObjectInstance.class),
                "AizSpikedLogObjectInstance must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizSpikedLogObjectInstance.class.getName()),
                "AizSpikedLogObjectInstance must not keep an explicit S3K dynamic rewind codec");
    }

    @Test
    void captureFailsForChildWhoseRequiredParentHasNoRewindIdentity() {
        Harness harness = Harness.create(new Sonic3kObjectRegistry(), List.of());
        ObjectManager objectManager = harness.objectManager();
        AizSpikedLogObjectInstance unmanagedParent =
                new AizSpikedLogObjectInstance(new ObjectSpawn(
                        0x0180, 0x0120, Sonic3kObjectIds.AIZ_SPIKED_LOG, 0, 0, false, 20));
        AizSpikedLogObjectInstance.SpikedLogCollisionChild child =
                objectManager.createDynamicObject(
                        () -> new AizSpikedLogObjectInstance.SpikedLogCollisionChild(
                                new ObjectSpawn(
                                        0x0180, 0x0120, Sonic3kObjectIds.AIZ_SPIKED_LOG, 0, 0, false, 21),
                                unmanagedParent));
        assertSame(unmanagedParent, readObjectField(child, "parent"),
                "precondition: child parent is outside ObjectManager identity registration");

        RewindRegistry rewindRegistry = registryFor(objectManager);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, rewindRegistry::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing required parent target must fail loudly");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(ObjectRegistry registry, List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
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

    private static AizSpikedLogObjectInstance.SpikedLogCollisionChild childWithParent(
            List<AizSpikedLogObjectInstance.SpikedLogCollisionChild> children,
            AizSpikedLogObjectInstance parent) {
        return children.stream()
                .filter(child -> readObjectField(child, "parent") == parent)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing spiked-log child for parent"));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestAizSpikedLogGraphRewind::slotIndex))
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
            @Override public short getWidth() { return 0x400; }
            @Override public short getHeight() { return 0x300; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
