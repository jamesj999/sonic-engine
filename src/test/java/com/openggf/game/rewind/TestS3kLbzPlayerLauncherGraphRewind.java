package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.PlayerRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.rewind.schema.RewindObjectStateBlob;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.LbzPlayerLauncherInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kLbzPlayerLauncherGraphRewind {
    private static final String ARM_CLASS =
            "com.openggf.game.sonic3k.objects.LbzPlayerLauncherInstance$LauncherArmChild";
    private static final ObjectSpawn LAUNCHER_SPAWN =
            new ObjectSpawn(0x1200, 0x0520, Sonic3kObjectIds.LBZ_PLAYER_LAUNCHER, 0x02, 1, false, 15);
    private static final ObjectSpawn ARM_SPAWN =
            new ObjectSpawn(0x1200, 0x0520, Sonic3kObjectIds.LBZ_PLAYER_LAUNCHER, 0x02, 1, false, 16);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void launcherArmRestoresFreshRelinkParentAndDoesNotDuplicate() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        LbzPlayerLauncherInstance sourceLauncher = objectManager.createDynamicObject(
                () -> new LbzPlayerLauncherInstance(LAUNCHER_SPAWN));
        ObjectInstance sourceArm = objectManager.createDynamicObject(
                () -> constructArm(sourceLauncher, null));
        setIntField(sourceArm, "routine", 2);
        setIntField(sourceArm, "angle", 0xA0);
        ObjectRefId launcherId = objectId(objectManager, sourceLauncher);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceArm);
        ObjectInstance divergentArm = objectManager.createDynamicObject(
                () -> constructArm(sourceLauncher, null));
        assertEquals(1, liveArms(objectManager).size(),
                "diverge step should leave one unrelated launcher arm before restore");

        rewindRegistry.restore(snapshot);

        LbzPlayerLauncherInstance restoredLauncher =
                objectById(objectManager, LbzPlayerLauncherInstance.class, launcherId);
        List<ObjectInstance> restoredArms = liveArms(objectManager);
        assertEquals(1, restoredArms.size(),
                "restore must keep exactly the captured launcher arm");
        ObjectInstance restoredArm = restoredArms.getFirst();
        assertNotSame(sourceLauncher, restoredLauncher,
                "restore must recreate the LBZ player launcher parent");
        assertNotSame(sourceArm, restoredArm, "restore must recreate the launcher arm");
        assertNotSame(divergentArm, restoredArm, "restore must drop the divergent launcher arm");
        assertSame(restoredLauncher, readObjectField(restoredArm, "parent"),
                "launcher arm parent must resolve to the restored LBZ player launcher");
        assertNotSame(sourceLauncher, readObjectField(restoredArm, "parent"),
                "launcher arm must not retain the stale pre-restore parent");
        assertEquals(2, readIntField(restoredArm, "routine"));
        assertEquals(0xA0, readIntField(restoredArm, "angle"));
    }

    @Test
    void launcherArmUsesGenericRecreateWithoutExplicitS3kCodec() throws Exception {
        Class<?> armType = Class.forName(ARM_CLASS);
        assertTrue(RewindRecreatable.class.isAssignableFrom(armType),
                "LBZ player launcher arm must restore through RewindRecreatable graph recreate");
    }

    @Test
    void launcherArmPlayerIdentityRoundTripsThroughCompactSchema() throws Exception {
        LbzPlayerLauncherInstance parent = new LbzPlayerLauncherInstance(LAUNCHER_SPAWN);
        Sonic capturedPlayer = new Sonic("captured_sidekick", (short) 0, (short) 0);
        AbstractObjectInstance sourceArm = (AbstractObjectInstance) constructArm(parent, capturedPlayer);

        ObjectRefId parentId = ObjectRefId.layout(5, 1, 0);
        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerObject(parent, parentId);
        captureTable.registerPlayer(capturedPlayer, PlayerRefId.sidekick(1));
        RewindObjectStateBlob blob = CompactFieldCapturer.captureDefaultObjectSubclassScalars(
                sourceArm, RewindCaptureContext.withIdentityTable(captureTable));

        Sonic restoredPlayer = new Sonic("restored_sidekick", (short) 0, (short) 0);
        AbstractObjectInstance restoredArm = (AbstractObjectInstance) constructArm(parent, null);
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerObject(parent, parentId);
        restoreTable.registerPlayer(restoredPlayer, PlayerRefId.sidekick(1));
        CompactFieldCapturer.restoreDefaultObjectSubclassScalars(
                restoredArm, blob, RewindCaptureContext.withIdentityTable(restoreTable));

        assertSame(restoredPlayer, readObjectField(restoredArm, "player"),
                "launcher arm ownership must restore by PlayerRefId, not stale Java identity");
    }

    @Test
    void launcherCounterMapRoundTripsWithReplacementPlayerIdentity() throws Exception {
        LbzPlayerLauncherInstance source = new LbzPlayerLauncherInstance(LAUNCHER_SPAWN);
        Sonic capturedPlayer = new Sonic("captured_counter_owner", (short) 0, (short) 0);
        counterMap(source).put(capturedPlayer, 3);
        RewindIdentityTable captureTable = new RewindIdentityTable();
        captureTable.registerPlayer(capturedPlayer, PlayerRefId.sidekick(1));
        RewindObjectStateBlob blob = CompactFieldCapturer.captureDefaultObjectSubclassScalars(
                source, RewindCaptureContext.withIdentityTable(captureTable));

        LbzPlayerLauncherInstance restored = new LbzPlayerLauncherInstance(LAUNCHER_SPAWN);
        Sonic restoredPlayer = new Sonic("restored_counter_owner", (short) 0, (short) 0);
        RewindIdentityTable restoreTable = new RewindIdentityTable();
        restoreTable.registerPlayer(restoredPlayer, PlayerRefId.sidekick(1));
        CompactFieldCapturer.restoreDefaultObjectSubclassScalars(
                restored, blob, RewindCaptureContext.withIdentityTable(restoreTable));

        assertEquals(3, counterMap(restored).get(restoredPlayer));
        assertFalse(counterMap(restored).containsKey(capturedPlayer),
                "rewind must resolve map keys to the restored player instance");
    }

    @Test
    void launcherArmResetsItsCapturedOwnerRatherThanFrameUpdatePlayer() throws Exception {
        LbzPlayerLauncherInstance parent = new LbzPlayerLauncherInstance(LAUNCHER_SPAWN);
        Sonic owner = new Sonic("arm_owner", (short) 0, (short) 0);
        Sonic bystander = new Sonic("frame_update_player", (short) 0, (short) 0);
        counterMap(parent).put(owner, 4);
        counterMap(parent).put(bystander, 3);
        setIntField(parent, "p1Counter", 3);
        setObjectField(parent, "p1CounterOwner", bystander);
        ObjectInstance arm = constructArm(parent, owner);

        for (int frame = 0; frame < 5; frame++) {
            arm.update(frame, bystander);
        }

        assertEquals(0, counterMap(parent).get(owner));
        assertEquals(3, counterMap(parent).get(bystander));
        assertEquals(3, readIntField(parent, "p1Counter"),
                "another player's arm must not reset the native P1 mirror counter");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
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
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectInstance constructArm(LbzPlayerLauncherInstance parent, PlayableEntity player) {
        try {
            Class<?> armType = Class.forName(ARM_CLASS);
            Constructor<?> ctor = armType.getDeclaredConstructor(
                    ObjectSpawn.class, LbzPlayerLauncherInstance.class, PlayableEntity.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(ARM_SPAWN, parent, player);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct LBZ player launcher arm", e);
        }
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertTrue(id != null, "ObjectManager identity table must register " + object.getClass());
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

    private static List<ObjectInstance> liveArms(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(ARM_CLASS))
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

    private static int readIntField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.getInt(target);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = field(target, name);
        field.setInt(target, value);
    }

    private static void setObjectField(Object target, String name, Object value) throws Exception {
        Field field = field(target, name);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<PlayableEntity, Integer> counterMap(Object target) throws Exception {
        return (Map<PlayableEntity, Integer>) readObjectField(target, "countersByPlayer");
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
