package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1LavaGeyserGraphRewind {
    private static final ObjectSpawn MAKER_SPAWN =
            new ObjectSpawn(0x0100, 0x0370, Sonic1ObjectIds.LAVA_GEYSER_MAKER, 1, 0, false, 60);
    private static final ObjectSpawn HEAD_SPAWN =
            new ObjectSpawn(0x0100, 0x0120, Sonic1ObjectIds.LAVA_GEYSER, 0, 0, false, 61);
    private static final ObjectSpawn BODY_SPAWN =
            new ObjectSpawn(0x0100, 0x0180, Sonic1ObjectIds.LAVA_GEYSER, 0, 0, false, 62);
    private static final ObjectSpawn THIRD_SPAWN =
            new ObjectSpawn(0x0100, 0x0220, Sonic1ObjectIds.LAVA_GEYSER, 1, 0, false, 63);
    private static final ObjectSpawn REPLACEMENT_MAKER_SPAWN =
            new ObjectSpawn(0x0180, 0x0370, Sonic1ObjectIds.LAVA_GEYSER_MAKER, 1, 0, false, 64);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void lavaGeyserFamilyRestoresFreshWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic1LavaGeyserMakerObjectInstance beforeMaker = objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserMakerObjectInstance(MAKER_SPAWN));
        seedMaker(beforeMaker, 8, 42, 4, 1, 2, 18, true);
        Sonic1LavaGeyserObjectInstance beforeHead = objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserObjectInstance(
                        HEAD_SPAWN, Sonic1LavaGeyserObjectInstance.Role.HEAD, null, beforeMaker, false));
        seedHead(beforeHead, 0, 0x0100, 0x0120, -0x120, 0x0370, 2, 1, 2, 0x12, false, true);
        Sonic1LavaGeyserObjectInstance beforeBody = objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserObjectInstance(
                        BODY_SPAWN, Sonic1LavaGeyserObjectInstance.Role.BODY, beforeHead, beforeMaker, false));
        seedBody(beforeBody, 0, 0x0100, 0x0180, 0x03D0, 1, 6, 0x0C, true);
        Sonic1LavaGeyserObjectInstance beforeThird = objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserObjectInstance(
                        THIRD_SPAWN, Sonic1LavaGeyserObjectInstance.Role.HEAD, null, beforeMaker, true));
        seedHead(beforeThird, 1, 0x0100, 0x0220, 0x60, 0x0370, 2, 0, 1, 7, false, true);

        ObjectRefId makerId = objectId(objectManager, beforeMaker);
        ObjectRefId headId = objectId(objectManager, beforeHead);
        ObjectRefId bodyId = objectId(objectManager, beforeBody);
        ObjectRefId thirdId = objectId(objectManager, beforeThird);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(beforeThird);
        objectManager.removeDynamicObject(beforeBody);
        objectManager.removeDynamicObject(beforeHead);
        objectManager.removeDynamicObject(beforeMaker);
        Sonic1LavaGeyserMakerObjectInstance replacementMaker = objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserMakerObjectInstance(REPLACEMENT_MAKER_SPAWN));
        Sonic1LavaGeyserObjectInstance replacementHead = objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserObjectInstance(
                        new ObjectSpawn(0x0180, 0x0130, Sonic1ObjectIds.LAVA_GEYSER, 0, 0, false, 65),
                        Sonic1LavaGeyserObjectInstance.Role.HEAD, null, replacementMaker, false));

        rewindRegistry.restore(snapshot);

        assertEquals(1, liveMakers(objectManager).size(), "restore must keep exactly the captured maker");
        assertEquals(3, liveGeysers(objectManager).size(),
                "restore must keep exactly the captured lavafall head, body, and third piece");
        Sonic1LavaGeyserMakerObjectInstance restoredMaker =
                assertInstanceOf(Sonic1LavaGeyserMakerObjectInstance.class, objectWithId(objectManager, makerId));
        Sonic1LavaGeyserObjectInstance restoredHead =
                assertInstanceOf(Sonic1LavaGeyserObjectInstance.class, objectWithId(objectManager, headId));
        Sonic1LavaGeyserObjectInstance restoredBody =
                assertInstanceOf(Sonic1LavaGeyserObjectInstance.class, objectWithId(objectManager, bodyId));
        Sonic1LavaGeyserObjectInstance restoredThird =
                assertInstanceOf(Sonic1LavaGeyserObjectInstance.class, objectWithId(objectManager, thirdId));

        assertNotSame(beforeMaker, restoredMaker, "restore must recreate the removed maker");
        assertNotSame(beforeHead, restoredHead, "restore must recreate the removed head");
        assertNotSame(beforeBody, restoredBody, "restore must recreate the removed body");
        assertNotSame(beforeThird, restoredThird, "restore must recreate the removed third piece");
        assertNotSame(replacementMaker, restoredMaker, "restore must drop unrelated replacement makers");
        assertNotSame(replacementHead, restoredHead, "restore must drop unrelated replacement heads");
        assertEquals("HEAD", roleName(restoredHead), "main lavafall piece must restore as a head");
        assertEquals("BODY", roleName(restoredBody), "column piece must restore as a body");
        assertEquals("HEAD", roleName(restoredThird), "third lavafall piece must restore as a head");
        assertFalse(readBoolean(restoredHead, "behindPriority"), "main head must keep foreground priority");
        assertFalse(readBoolean(restoredBody, "behindPriority"), "body must keep foreground priority");
        assertTrue(readBoolean(restoredThird, "behindPriority"), "third piece must keep behind priority");

        assertSame(restoredMaker, readObject(restoredHead, "makerParent"),
                "head makerParent must point at the restored maker");
        assertSame(restoredMaker, readObject(restoredBody, "makerParent"),
                "body makerParent must point at the restored maker");
        assertSame(restoredMaker, readObject(restoredThird, "makerParent"),
                "third-piece makerParent must point at the restored maker");
        assertSame(restoredHead, readObject(restoredBody, "parentGeyser"),
                "body parentGeyser must point at the restored head");
        assertNotSame(beforeMaker, readObject(restoredHead, "makerParent"),
                "head must not keep a stale maker ref");
        assertNotSame(beforeHead, readObject(restoredBody, "parentGeyser"),
                "body must not keep a stale parent-head ref");

        assertEquals(0x0120, readInt(restoredHead, "currentY"), "head currentY scalar must restore");
        assertEquals(0x0180, readInt(restoredBody, "currentY"), "body currentY scalar must restore");
        assertEquals(0x0220, readInt(restoredThird, "currentY"), "third currentY scalar must restore");
        assertEquals(-0x120, readInt(restoredHead, "velY"), "head velocity scalar must restore");
        assertEquals(0x60, readInt(restoredThird, "velY"), "third-piece velocity scalar must restore");
        assertTrue(readBoolean(restoredHead, "initialized"), "head initialized guard must restore");
        assertTrue(readBoolean(restoredThird, "initialized"), "third-piece initialized guard must restore");

        AbstractObjectInstance.resetCameraBoundsForTests();
        restoredHead.update(1, null);
        assertFalse(restoredHead.isDestroyed(), "restored head must remain live after its first update");
        assertFalse(restoredBody.isDestroyed(), "restored body must remain live after the head update");
        assertFalse(restoredThird.isDestroyed(), "restored third piece must remain live after the head update");
        assertEquals(3, liveGeysers(objectManager).size(),
                "restored initialized head update must not spawn duplicate body pieces");
        assertSame(restoredHead, readObject(restoredBody, "parentGeyser"),
                "body must stay linked to the restored head after update");
    }

    @Test
    void lavaGeyserBodyParentRefStillRequiresRewindIdentity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        Sonic1LavaGeyserMakerObjectInstance maker = objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserMakerObjectInstance(MAKER_SPAWN));
        Sonic1LavaGeyserObjectInstance unmanagedHead = new Sonic1LavaGeyserObjectInstance(
                HEAD_SPAWN, Sonic1LavaGeyserObjectInstance.Role.HEAD, null, maker, false);
        objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserObjectInstance(
                        BODY_SPAWN, Sonic1LavaGeyserObjectInstance.Role.BODY, unmanagedHead, maker, false));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> registryFor(objectManager).capture(),
                "required lava geyser parent refs must fail loudly when the target has no rewind identity");
        assertTrue(thrown.getMessage().contains("no registered id for object reference"));
    }

    @Test
    void lavaGeyserMakerRefStillRequiresRewindIdentity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        Sonic1LavaGeyserMakerObjectInstance unmanagedMaker =
                new Sonic1LavaGeyserMakerObjectInstance(MAKER_SPAWN);
        objectManager.createDynamicObject(
                () -> new Sonic1LavaGeyserObjectInstance(
                        HEAD_SPAWN, Sonic1LavaGeyserObjectInstance.Role.HEAD, null, unmanagedMaker, false));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> registryFor(objectManager).capture(),
                "required lava geyser maker refs must fail loudly when the target has no rewind identity");
        assertTrue(thrown.getMessage().contains("no registered id for object reference"));
    }

    @Test
    void lavaGeyserFamilyUsesGenericRecreateWithoutExplicitDynamicCodec() {
        assertTrue(SpawnRewindRecreatable.class.isAssignableFrom(Sonic1LavaGeyserMakerObjectInstance.class),
                "lava geyser maker must restore through spawn-based generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1LavaGeyserObjectInstance.class),
                "lava geyser pieces must restore through graph-aware generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1LavaGeyserMakerObjectInstance.class.getName()),
                "lava geyser maker must not keep an explicit S1 dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1LavaGeyserObjectInstance.class.getName()),
                "lava geyser pieces must not keep an explicit S1 dynamic rewind codec");
    }

    private static void seedMaker(
            Sonic1LavaGeyserMakerObjectInstance maker,
            int routine,
            int timer,
            int currentAnim,
            int animFrameIndex,
            int animTimer,
            int displayFrame,
            boolean visible) {
        writeInt(maker, "routine", routine);
        writeInt(maker, "timer", timer);
        writeInt(maker, "currentAnim", currentAnim);
        writeInt(maker, "animFrameIndex", animFrameIndex);
        writeInt(maker, "animTimer", animTimer);
        writeInt(maker, "displayFrame", displayFrame);
        writeBoolean(maker, "visible", visible);
    }

    private static void seedHead(
            Sonic1LavaGeyserObjectInstance geyser,
            int subtype,
            int currentX,
            int currentY,
            int velY,
            int originY,
            int headAnimId,
            int animFrameIndex,
            int animTimer,
            int displayFrame,
            boolean pendingDelete,
            boolean initialized) {
        writeInt(geyser, "subtype", subtype);
        writeInt(geyser, "currentX", currentX);
        writeInt(geyser, "currentY", currentY);
        writeInt(geyser, "velY", velY);
        writeInt(geyser, "originY", originY);
        writeInt(geyser, "headAnimId", headAnimId);
        writeInt(geyser, "animFrameIndex", animFrameIndex);
        writeInt(geyser, "animTimer", animTimer);
        writeInt(geyser, "displayFrame", displayFrame);
        writeBoolean(geyser, "pendingDelete", pendingDelete);
        writeBoolean(geyser, "initialized", initialized);
    }

    private static void seedBody(
            Sonic1LavaGeyserObjectInstance geyser,
            int subtype,
            int currentX,
            int currentY,
            int originY,
            int columnAnimFrame,
            int columnAnimTimer,
            int displayFrame,
            boolean initialized) {
        writeInt(geyser, "subtype", subtype);
        writeInt(geyser, "currentX", currentX);
        writeInt(geyser, "currentY", currentY);
        writeInt(geyser, "originY", originY);
        writeInt(geyser, "columnAnimFrame", columnAnimFrame);
        writeInt(geyser, "columnAnimTimer", columnAnimTimer);
        writeInt(geyser, "displayFrame", displayFrame);
        writeBoolean(geyser, "initialized", initialized);
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(), new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
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

    private static ObjectInstance objectWithId(ObjectManager objectManager, ObjectRefId id) {
        List<ObjectInstance> matches = objectManager.getActiveObjects().stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext().requireIdentityTable().idFor(object)))
                .toList();
        assertEquals(1, matches.size(), "expected one live object for rewind id " + id);
        return matches.getFirst();
    }

    private static List<Sonic1LavaGeyserMakerObjectInstance> liveMakers(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == Sonic1LavaGeyserMakerObjectInstance.class)
                .map(Sonic1LavaGeyserMakerObjectInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(TestSonic1LavaGeyserGraphRewind::slotIndex))
                .toList();
    }

    private static List<Sonic1LavaGeyserObjectInstance> liveGeysers(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == Sonic1LavaGeyserObjectInstance.class)
                .map(Sonic1LavaGeyserObjectInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(TestSonic1LavaGeyserGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof com.openggf.level.objects.AbstractObjectInstance aoi
                ? aoi.getSlotIndex()
                : -1;
    }

    private static String roleName(Sonic1LavaGeyserObjectInstance geyser) {
        return readObject(geyser, "role").toString();
    }

    private static int readInt(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBoolean(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObject(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void writeBoolean(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Walk superclass chain.
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
