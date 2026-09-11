package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kMhzEndBossGraphRewind {
    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x3C40, 0x0320, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 80);
    private static final String SIDEKICK_LOCK_CLASS =
            "com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance$MhzEndBossSidekickLockChild";
    private static final String WALKOFF_PREP_CLASS =
            "com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance$MhzEndBossWalkoffPrepChild";
    private static final String PLAYER_TWO_CARRY_CLASS =
            "com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance$MhzEndBossPlayerTwoCarryChild";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void mhzEndBossInvisibleControllersRestoreFreshRelinkParentAndPreserveFlags() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        MhzEndBossInstance sourceBoss = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(BOSS_SPAWN));
        ObjectInstance sourceSidekickLock = objectManager.createDynamicObject(
                TestS3kMhzEndBossGraphRewind::constructSidekickLock);
        ObjectInstance sourceWalkoff = objectManager.createDynamicObject(
                () -> constructWalkoffPrep(sourceBoss));
        ObjectInstance sourcePlayerTwoCarry = objectManager.createDynamicObject(
                TestS3kMhzEndBossGraphRewind::constructPlayerTwoCarry);
        writeBooleanField(sourceSidekickLock, "lockIssued", true);
        writeBooleanField(sourceWalkoff, "forcingWalkoff", true);
        writeBooleanField(sourcePlayerTwoCarry, "initialized", true);

        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        ObjectRefId sidekickLockId = objectId(objectManager, sourceSidekickLock);
        ObjectRefId walkoffId = objectId(objectManager, sourceWalkoff);
        ObjectRefId playerTwoCarryId = objectId(objectManager, sourcePlayerTwoCarry);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceSidekickLock);
        objectManager.removeDynamicObject(sourceWalkoff);
        objectManager.removeDynamicObject(sourcePlayerTwoCarry);
        objectManager.removeDynamicObject(sourceBoss);
        MhzEndBossInstance divergentBoss = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3D00, 0x0340, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 81)));
        ObjectInstance divergentWalkoff = objectManager.createDynamicObject(
                () -> constructWalkoffPrep(divergentBoss));
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "diverge step should leave one unrelated MHZ end boss before restore");
        assertEquals(1, liveObjectsByName(objectManager, WALKOFF_PREP_CLASS).size(),
                "diverge step should leave one unrelated walkoff helper before restore");

        rewindRegistry.restore(snapshot);

        MhzEndBossInstance restoredBoss = objectById(objectManager, MhzEndBossInstance.class, bossId);
        ObjectInstance restoredSidekickLock =
                objectByIdOfType(objectManager, Class.forName(SIDEKICK_LOCK_CLASS), sidekickLockId);
        ObjectInstance restoredWalkoff =
                objectByIdOfType(objectManager, Class.forName(WALKOFF_PREP_CLASS), walkoffId);
        ObjectInstance restoredPlayerTwoCarry =
                objectByIdOfType(objectManager, Class.forName(PLAYER_TWO_CARRY_CLASS), playerTwoCarryId);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "restore must keep exactly the captured MHZ end boss");
        assertEquals(1, liveObjectsByName(objectManager, SIDEKICK_LOCK_CLASS).size(),
                "restore must keep exactly the captured sidekick-lock helper");
        assertEquals(1, liveObjectsByName(objectManager, WALKOFF_PREP_CLASS).size(),
                "restore must keep exactly the captured walkoff helper");
        assertEquals(1, liveObjectsByName(objectManager, PLAYER_TWO_CARRY_CLASS).size(),
                "restore must keep exactly the captured player-two carry helper");
        assertNotSame(sourceBoss, restoredBoss, "restore must recreate the MHZ end boss");
        assertNotSame(sourceSidekickLock, restoredSidekickLock, "restore must recreate sidekick-lock helper");
        assertNotSame(sourceWalkoff, restoredWalkoff, "restore must recreate walkoff helper");
        assertNotSame(sourcePlayerTwoCarry, restoredPlayerTwoCarry, "restore must recreate player-two carry helper");
        assertNotSame(divergentBoss, restoredBoss, "restore must drop the divergent MHZ end boss");
        assertNotSame(divergentWalkoff, restoredWalkoff, "restore must drop the divergent walkoff helper");
        assertSame(restoredBoss, readObjectField(restoredWalkoff, "parent"),
                "walkoff helper parent must resolve to the restored MHZ end boss");
        assertNotSame(sourceBoss, readObjectField(restoredWalkoff, "parent"),
                "walkoff helper must not retain the stale pre-restore parent");
        assertTrue(readBooleanField(restoredSidekickLock, "lockIssued"));
        assertTrue(readBooleanField(restoredWalkoff, "forcingWalkoff"));
        assertTrue(readBooleanField(restoredPlayerTwoCarry, "initialized"));
    }

    @Test
    void mhzEndBossInvisibleControllersUseGenericRecreateWithoutExplicitCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(SIDEKICK_LOCK_CLASS)));
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(WALKOFF_PREP_CLASS)));
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(PLAYER_TWO_CARRY_CLASS)));
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(SIDEKICK_LOCK_CLASS));
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(WALKOFF_PREP_CLASS));
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(PLAYER_TWO_CARRY_CLASS));
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

    private static ObjectInstance objectByIdOfType(
            ObjectManager objectManager,
            Class<?> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .filter(object -> !object.isDestroyed())
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

    private static List<ObjectInstance> liveObjectsByName(ObjectManager objectManager, String className) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(className))
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(ObjectInstance::getX))
                .toList();
    }

    private static ObjectInstance constructSidekickLock() {
        return constructNoArg(SIDEKICK_LOCK_CLASS);
    }

    private static ObjectInstance constructPlayerTwoCarry() {
        return constructNoArg(PLAYER_TWO_CARRY_CLASS);
    }

    private static ObjectInstance constructNoArg(String className) {
        try {
            Class<?> cls = Class.forName(className);
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct " + className, e);
        }
    }

    private static ObjectInstance constructWalkoffPrep(MhzEndBossInstance parent) {
        try {
            Class<?> cls = Class.forName(WALKOFF_PREP_CLASS);
            Constructor<?> ctor = cls.getDeclaredConstructor(MhzEndBossInstance.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct MHZ walkoff prep helper", e);
        }
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static boolean readBooleanField(Object target, String name) throws Exception {
        return field(target, name).getBoolean(target);
    }

    private static void writeBooleanField(Object target, String name, boolean value) throws Exception {
        field(target, name).setBoolean(target, value);
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
            @Override public short getX() { return 0x3A00; }
            @Override public short getY() { return 0x0200; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
