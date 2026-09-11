package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.objects.Sonic1SeesawBallObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SeesawObjectInstance;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.SeesawBallObjectInstance;
import com.openggf.game.sonic2.objects.SeesawObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class AbstractSeesawBallGraphRewindTest {

    private static final ObjectSpawn S1_TARGET_SEESAW =
            new ObjectSpawn(0x0080, 0x00B0, Sonic1ObjectIds.SEESAW, 0, 0, false, 10);
    private static final ObjectSpawn S1_WRONG_SEESAW =
            new ObjectSpawn(0x0100, 0x00B0, Sonic1ObjectIds.SEESAW, 0, 0, false, 11);
    private static final ObjectSpawn S2_TARGET_SEESAW =
            new ObjectSpawn(0x0080, 0x00C0, Sonic2ObjectIds.SEESAW, 0, 0, false, 20);
    private static final ObjectSpawn S2_WRONG_SEESAW =
            new ObjectSpawn(0x0100, 0x00C0, Sonic2ObjectIds.SEESAW, 0, 0, false, 21);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s1SeesawBallRestoresFreshWithIdentityScalarsAndIntendedParentRelink() {
        Harness harness = Harness.create(
                new Sonic1ObjectRegistry(), List.of(S1_TARGET_SEESAW, S1_WRONG_SEESAW));
        ObjectManager objectManager = harness.objectManager();
        Sonic1SeesawObjectInstance target = liveS1ParentAt(objectManager, S1_TARGET_SEESAW.x());
        Sonic1SeesawObjectInstance wrong = liveS1ParentAt(objectManager, S1_WRONG_SEESAW.x());

        Sonic1SeesawBallObjectInstance before = objectManager.createDynamicObject(
                () -> new Sonic1SeesawBallObjectInstance(target, S1_TARGET_SEESAW.x(), S1_TARGET_SEESAW.y(), false));
        adoptS1(target, before);
        writeInt(before, "xPos", (S1_WRONG_SEESAW.x() + 0x20) << 16);
        writeInt(before, "yPos", (S1_WRONG_SEESAW.y() - 0x18) << 16);
        writeInt(before, "xVel", 0x0123);
        writeInt(before, "yVel", -0x0456);
        writeInt(before, "storedFrame", 2);
        ObjectSpawn capturedSpawn = before.getSpawn();
        ObjectRefId beforeId = objectId(objectManager, before);

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        objectManager.removeDynamicObject(before);
        Sonic1SeesawBallObjectInstance replacement = objectManager.createDynamicObject(
                () -> new Sonic1SeesawBallObjectInstance(wrong, S1_WRONG_SEESAW.x(), S1_WRONG_SEESAW.y(), false));
        adoptS1(wrong, replacement);
        assertNotEquals(beforeId, objectId(objectManager, replacement));

        registry.restore(snapshot);

        List<Sonic1SeesawObjectInstance> parents = liveObjects(objectManager, Sonic1SeesawObjectInstance.class);
        List<Sonic1SeesawBallObjectInstance> balls = liveObjects(objectManager, Sonic1SeesawBallObjectInstance.class);
        assertEquals(2, parents.size(), "restore must keep both S1 seesaw parents");
        assertEquals(1, balls.size(), "restore must keep exactly one S1 seesaw ball");
        Sonic1SeesawBallObjectInstance restored = balls.getFirst();
        Sonic1SeesawObjectInstance restoredTarget = liveS1ParentAt(objectManager, S1_TARGET_SEESAW.x());
        Sonic1SeesawObjectInstance restoredWrong = liveS1ParentAt(objectManager, S1_WRONG_SEESAW.x());

        assertNotSame(before, restored, "restore must recreate the removed S1 ball");
        assertNotSame(replacement, restored, "restore must drop the post-snapshot S1 replacement");
        assertEquals(beforeId, objectId(objectManager, restored),
                "S1 ball dynamic rewind identity must be preserved");
        assertSame(restoredTarget, readObject(restored, "parent"),
                "S1 ball must relink to the captured parent origin, not the nearest current-position parent");
        assertNotSame(restoredWrong, readObject(restored, "parent"));
        assertSame(restored, readObject(restoredTarget, "ball"),
                "S1 parent must adopt the restored ball back-reference");
        assertTrue(readBoolean(restoredTarget, "ballSpawned"),
                "S1 parent ballSpawned must prevent duplicate spawn after restore");
        assertEquals(capturedSpawn.x(), restored.getSpawn().x(), "S1 xPos must restore exactly");
        assertEquals(capturedSpawn.y(), restored.getSpawn().y(), "S1 yPos must restore exactly");
        assertEquals(0x0123, readInt(restored, "xVel"), "S1 x velocity must restore exactly");
        assertEquals(-0x0456, readInt(restored, "yVel"), "S1 y velocity must restore exactly");
        assertEquals(2, readInt(restored, "storedFrame"), "S1 stored frame must restore exactly");
    }

    @Test
    void s2SeesawBallRestoresFreshWithIdentityScalarsAndIntendedParentRelink() {
        Harness harness = Harness.create(
                new Sonic2ObjectRegistry(), List.of(S2_TARGET_SEESAW, S2_WRONG_SEESAW));
        ObjectManager objectManager = harness.objectManager();
        SeesawObjectInstance target = liveS2ParentAt(objectManager, S2_TARGET_SEESAW.x());
        SeesawObjectInstance wrong = liveS2ParentAt(objectManager, S2_WRONG_SEESAW.x());

        SeesawBallObjectInstance before = objectManager.createDynamicObject(
                () -> new SeesawBallObjectInstance(
                        S2_TARGET_SEESAW.x(), S2_TARGET_SEESAW.y() + 0x10,
                        S2_TARGET_SEESAW.x() + 0x28, S2_TARGET_SEESAW.y() + 0x10,
                        target, false));
        adoptS2(target, before);
        writeInt(before, "xPos", (S2_WRONG_SEESAW.x() + 0x20) << 16);
        writeInt(before, "yPos", (S2_WRONG_SEESAW.y() - 0x18) << 16);
        writeInt(before, "xVel", -0x0234);
        writeInt(before, "yVel", 0x0567);
        writeInt(before, "storedAngle", 2);
        ObjectSpawn capturedSpawn = before.getSpawn();
        ObjectRefId beforeId = objectId(objectManager, before);

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        objectManager.removeDynamicObject(before);
        SeesawBallObjectInstance replacement = objectManager.createDynamicObject(
                () -> new SeesawBallObjectInstance(
                        S2_WRONG_SEESAW.x(), S2_WRONG_SEESAW.y() + 0x10,
                        S2_WRONG_SEESAW.x() + 0x28, S2_WRONG_SEESAW.y() + 0x10,
                        wrong, false));
        adoptS2(wrong, replacement);
        assertNotEquals(beforeId, objectId(objectManager, replacement));

        registry.restore(snapshot);

        List<SeesawObjectInstance> parents = liveObjects(objectManager, SeesawObjectInstance.class);
        List<SeesawBallObjectInstance> balls = liveObjects(objectManager, SeesawBallObjectInstance.class);
        assertEquals(2, parents.size(), "restore must keep both S2 seesaw parents");
        assertEquals(1, balls.size(), "restore must keep exactly one S2 seesaw ball");
        SeesawBallObjectInstance restored = balls.getFirst();
        SeesawObjectInstance restoredTarget = liveS2ParentAt(objectManager, S2_TARGET_SEESAW.x());
        SeesawObjectInstance restoredWrong = liveS2ParentAt(objectManager, S2_WRONG_SEESAW.x());

        assertNotSame(before, restored, "restore must recreate the removed S2 ball");
        assertNotSame(replacement, restored, "restore must drop the post-snapshot S2 replacement");
        assertEquals(beforeId, objectId(objectManager, restored),
                "S2 ball dynamic rewind identity must be preserved");
        assertSame(restoredTarget, readObject(restored, "parent"),
                "S2 ball must relink to the captured parent origin, not the nearest current-position parent");
        assertNotSame(restoredWrong, readObject(restored, "parent"));
        assertSame(restored, readObject(restoredTarget, "ball"),
                "S2 parent must adopt the restored ball back-reference");
        assertTrue(readBoolean(restoredTarget, "ballSpawned"),
                "S2 parent ballSpawned must prevent duplicate spawn after restore");
        assertEquals(capturedSpawn.x(), restored.getSpawn().x(), "S2 xPos must restore exactly");
        assertEquals(capturedSpawn.y(), restored.getSpawn().y(), "S2 yPos must restore exactly");
        assertEquals(-0x0234, readInt(restored, "xVel"), "S2 x velocity must restore exactly");
        assertEquals(0x0567, readInt(restored, "yVel"), "S2 y velocity must restore exactly");
        assertEquals(2, readInt(restored, "storedAngle"), "S2 stored angle must restore exactly");
    }

    @Test
    void directGenericRecreateWithoutLiveParentDropsSeesawBalls() {
        Harness s1Harness = Harness.create(new Sonic1ObjectRegistry(), List.of());
        assertNull(genericRecreate(
                        s1Harness.objectManager(),
                        Sonic1SeesawBallObjectInstance.class,
                        new ObjectSpawn(0x0200, 0x0300, Sonic1ObjectIds.SEESAW, 0, 0, false, 10)),
                "S1 generic recreate must drop the ball when no live seesaw parent exists");

        Harness s2Harness = Harness.create(new Sonic2ObjectRegistry(), List.of());
        assertNull(genericRecreate(
                        s2Harness.objectManager(),
                        SeesawBallObjectInstance.class,
                        new ObjectSpawn(0x0400, 0x0390, Sonic2ObjectIds.SEESAW, 0, 0, false, 20)),
                "S2 generic recreate must drop the ball when no live seesaw parent exists");
    }

    @Test
    void directGenericRecreateWithOnlyUnrelatedEligibleSeesawStillDropsSeesawBalls() {
        Harness s1Harness = Harness.create(new Sonic1ObjectRegistry(), List.of(S1_WRONG_SEESAW));
        Sonic1SeesawObjectInstance unrelatedS1 = liveS1ParentAt(s1Harness.objectManager(), S1_WRONG_SEESAW.x());
        writeBoolean(unrelatedS1, "ballSpawned", true);

        assertNull(genericRecreate(
                        s1Harness.objectManager(),
                        Sonic1SeesawBallObjectInstance.class,
                        new ObjectSpawn(
                                S1_WRONG_SEESAW.x() + 0x20,
                                S1_WRONG_SEESAW.y() - 0x18,
                                Sonic1ObjectIds.SEESAW,
                                0, 0, false, 10)),
                "S1 generic recreate must drop when only an unrelated eligible-looking seesaw exists");
        assertNull(readObject(unrelatedS1, "ball"),
                "S1 unrelated seesaw must not adopt a missing-parent ball");

        Harness s2Harness = Harness.create(new Sonic2ObjectRegistry(), List.of(S2_WRONG_SEESAW));
        SeesawObjectInstance unrelatedS2 = liveS2ParentAt(s2Harness.objectManager(), S2_WRONG_SEESAW.x());
        writeBoolean(unrelatedS2, "ballSpawned", true);

        assertNull(genericRecreate(
                        s2Harness.objectManager(),
                        SeesawBallObjectInstance.class,
                        new ObjectSpawn(
                                S2_WRONG_SEESAW.x() + 0x20,
                                S2_WRONG_SEESAW.y() - 0x18,
                                Sonic2ObjectIds.SEESAW,
                                0, 0, false, 20)),
                "S2 generic recreate must drop when only an unrelated eligible-looking seesaw exists");
        assertNull(readObject(unrelatedS2, "ball"),
                "S2 unrelated seesaw must not adopt a missing-parent ball");
    }

    @Test
    void seesawGraphUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1SeesawObjectInstance.class),
                "S1 seesaw parent must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1SeesawBallObjectInstance.class),
                "S1 seesaw ball must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(SeesawObjectInstance.class),
                "S2 seesaw parent must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(SeesawBallObjectInstance.class),
                "S2 seesaw ball must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1SeesawObjectInstance.class.getName()),
                "S1 seesaw parent must not keep an explicit dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1SeesawBallObjectInstance.class.getName()),
                "S1 seesaw ball must not keep an explicit dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(SeesawObjectInstance.class.getName()),
                "S2 seesaw parent must not keep an explicit dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(SeesawBallObjectInstance.class.getName()),
                "S2 seesaw ball must not keep an explicit dynamic rewind codec");
    }

    @Test
    void missingRequiredObjectReferencesStillFailWhenTargetHasNoRewindIdentity() {
        Harness externalHarness = Harness.create(new Sonic2ObjectRegistry(), List.of(S2_TARGET_SEESAW));
        SeesawObjectInstance externalParent = liveS2ParentAt(externalHarness.objectManager(), S2_TARGET_SEESAW.x());
        RequiredReferenceFixture fixture = new RequiredReferenceFixture(externalParent);
        RewindCaptureContext context = RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "non-transient unmanaged object refs must still require registered rewind identities");
    }

    private static ObjectInstance genericRecreate(
            ObjectManager objectManager,
            Class<? extends ObjectInstance> type,
            ObjectSpawn spawn) {
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(
                        type.getName(), spawn, 0, emptyState());
        return ObjectRewindDynamicCodecs.genericRecreate(
                entry, new DynamicObjectRecreateContext(objectManager));
    }

    private static PerObjectRewindSnapshot emptyState() {
        return new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0,
                false, false, 0, -1, null, null, null);
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private static Sonic1SeesawObjectInstance liveS1ParentAt(ObjectManager objectManager, int x) {
        List<Sonic1SeesawObjectInstance> matches = liveObjects(objectManager, Sonic1SeesawObjectInstance.class)
                .stream()
                .filter(parent -> parent.getSpawn().x() == x)
                .toList();
        assertEquals(1, matches.size(), "expected one live S1 seesaw at X " + x);
        return matches.getFirst();
    }

    private static SeesawObjectInstance liveS2ParentAt(ObjectManager objectManager, int x) {
        List<SeesawObjectInstance> matches = liveObjects(objectManager, SeesawObjectInstance.class)
                .stream()
                .filter(parent -> parent.getSpawn().x() == x)
                .toList();
        assertEquals(1, matches.size(), "expected one live S2 seesaw at X " + x);
        return matches.getFirst();
    }

    private static void adoptS1(Sonic1SeesawObjectInstance parent, Sonic1SeesawBallObjectInstance ball) {
        writeObject(parent, "ball", ball);
        writeBoolean(parent, "ballSpawned", true);
    }

    private static void adoptS2(SeesawObjectInstance parent, SeesawBallObjectInstance ball) {
        writeObject(parent, "ball", ball);
        writeBoolean(parent, "ballSpawned", true);
    }

    private static Object readObject(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
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

    private static void writeObject(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
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

    private record Harness(ObjectManager objectManager) {
        static Harness create(ObjectRegistry registry, List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns, registry, 0, null, null, GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static final class RequiredReferenceFixture {
        ObjectInstance object;

        private RequiredReferenceFixture(ObjectInstance object) {
            this.object = object;
        }
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
}
