package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.BossChildComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2DezBombGraphRewind {

    private static final String BOMB_CLASS =
            "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$BombChild";
    private static final ObjectSpawn DISTRACTOR_BOSS_SPAWN =
            new ObjectSpawn(0x0080, 0x00F0, Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 41, 0);
    private static final ObjectSpawn CAPTURED_BOSS_SPAWN =
            new ObjectSpawn(0x00E0, 0x00F0, Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 42, 1);
    private static final ObjectSpawn BOMB_SPAWN =
            new ObjectSpawn(0x00F0, 0x0110, Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 91);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void bombGraphRestoreRelinksNearestLiveBossAndPreservesScalars() throws Exception {
        Harness harness = Harness.createWithBosses();
        ObjectManager objectManager = harness.objectManager();
        Sonic2DeathEggRobotInstance capturedBoss = bossAt(objectManager, CAPTURED_BOSS_SPAWN);
        Sonic2DeathEggRobotInstance distractorBoss = bossAt(objectManager, DISTRACTOR_BOSS_SPAWN);

        ObjectInstance capturedBomb = objectManager.createDynamicObject(
                () -> newBomb(capturedBoss, BOMB_SPAWN.x(), BOMB_SPAWN.y(), 0x060, -0x800));
        addChild(capturedBoss, capturedBomb);
        seedBombScalars(capturedBomb);

        Map<String, ObjectRefId> beforeIds = ids(objectManager, capturedBoss, distractorBoss, capturedBomb);
        assertEquals(1, countLiveByClassName(objectManager, BOMB_CLASS));

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        removeChild(capturedBoss, capturedBomb);
        objectManager.removeDynamicObject(capturedBomb);
        ObjectInstance replacementBomb = objectManager.createDynamicObject(
                () -> newBomb(distractorBoss, 0x0090, 0x0100, -0x020, -0x500));
        addChild(distractorBoss, replacementBomb);
        assertNotEquals(beforeIds.get("bomb"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacementBomb));

        rewindRegistry.restore(snapshot);

        assertEquals(1, countLiveByClassName(objectManager, BOMB_CLASS),
                "restore must keep exactly the captured DEZ bomb dynamic");
        Sonic2DeathEggRobotInstance restoredCapturedBoss = bossAt(objectManager, CAPTURED_BOSS_SPAWN);
        Sonic2DeathEggRobotInstance restoredDistractorBoss = bossAt(objectManager, DISTRACTOR_BOSS_SPAWN);
        ObjectInstance restoredBomb = onlyByClassName(objectManager, BOMB_CLASS);

        assertEquals(beforeIds, ids(objectManager, restoredCapturedBoss, restoredDistractorBoss, restoredBomb),
                "restore must preserve the captured boss and bomb rewind identities");
        assertNotSame(capturedBomb, restoredBomb);
        assertNotSame(replacementBomb, restoredBomb);
        Object restoredParent = readObjectField(restoredBomb, "parent");
        assertSame(restoredCapturedBoss, restoredParent,
                "bomb must relink to the nearest restored Death Egg Robot boss");
        assertNotSame(capturedBoss, restoredParent,
                "bomb parent must not point at the stale pre-restore boss instance");
        assertNotSame(restoredDistractorBoss, restoredParent,
                "nearest-boss relink must not choose the first/farther live boss");

        assertTrue(restoredCapturedBoss.getChildComponents().contains(restoredBomb));
        assertFalse(restoredCapturedBoss.getChildComponents().contains(capturedBomb));
        assertFalse(restoredCapturedBoss.getChildComponents().contains(replacementBomb));
        assertFalse(restoredDistractorBoss.getChildComponents().contains(restoredBomb));
        assertEquals(1, restoredCapturedBoss.getChildComponents().stream()
                .filter(child -> child == restoredBomb)
                .count());
        assertBombScalars(restoredBomb);
    }

    @Test
    void genericRecreateReturnsNullWithoutLiveBossParent() {
        assertNull(genericRecreateBomb(Harness.create(List.of()).objectManager(), BOMB_SPAWN),
                "DEZ bombs cannot recreate without a live Death Egg Robot boss");
    }

    @Test
    void bombUsesRewindRecreatableWithoutExplicitS2DynamicCodec() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(bombClass()),
                "DEZ BombChild must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(BOMB_CLASS),
                "DEZ BombChild must not keep an explicit S2 dynamic rewind codec");
    }

    private static ObjectInstance genericRecreateBomb(ObjectManager objectManager, ObjectSpawn spawn) {
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(BOMB_CLASS, spawn, -1, null);
        return ObjectRewindDynamicCodecs.genericRecreate(entry, new DynamicObjectRecreateContext(objectManager));
    }

    private static ObjectInstance newBomb(
            Sonic2DeathEggRobotInstance boss,
            int x,
            int y,
            int xVel,
            int yVel) {
        try {
            Constructor<?> ctor = bombClass().getDeclaredConstructor(
                    Sonic2DeathEggRobotInstance.class, int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(boss, x, y, xVel, yVel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct DEZ BombChild", e);
        }
    }

    private static Class<?> bombClass() throws ClassNotFoundException {
        return Class.forName(BOMB_CLASS);
    }

    private static void addChild(Sonic2DeathEggRobotInstance boss, ObjectInstance bomb) {
        assertInstanceOf(BossChildComponent.class, bomb);
        boss.getChildComponents().add((BossChildComponent) bomb);
    }

    private static void removeChild(Sonic2DeathEggRobotInstance boss, ObjectInstance bomb) {
        boss.getChildComponents().remove(bomb);
    }

    private static void seedBombScalars(ObjectInstance bomb) throws Exception {
        setIntField(bomb, "currentX", BOMB_SPAWN.x());
        setIntField(bomb, "currentY", BOMB_SPAWN.y());
        setIntField(bomb, "xVel", -0x1A0);
        setIntField(bomb, "yVel", 0x2C0);
        setIntField(bomb, "groundTimer", 0x22);
        setBooleanField(bomb, "onGround", true);
        setBooleanField(bomb, "detonating", true);
        setIntField(bomb, "detonateFrame", 3);
        setIntField(bomb, "detonateTimer", 5);
        setIntField(bomb, "priority", 6);
        setIntField(bomb, "collisionFlags", 0x7E);
        invokeNoArg(bomb, "updateDynamicSpawn");
    }

    private static void assertBombScalars(ObjectInstance bomb) throws Exception {
        assertEquals(BOMB_SPAWN.x(), bomb.getX());
        assertEquals(BOMB_SPAWN.y(), bomb.getY());
        assertEquals(-0x1A0, readIntField(bomb, "xVel"));
        assertEquals(0x2C0, readIntField(bomb, "yVel"));
        assertEquals(0x22, readIntField(bomb, "groundTimer"));
        assertEquals(true, readBooleanField(bomb, "onGround"));
        assertEquals(true, readBooleanField(bomb, "detonating"));
        assertEquals(3, readIntField(bomb, "detonateFrame"));
        assertEquals(5, readIntField(bomb, "detonateTimer"));
        assertEquals(6, readIntField(bomb, "priority"));
        assertEquals(0x7E, readIntField(bomb, "collisionFlags"));
    }

    private static Sonic2DeathEggRobotInstance bossAt(ObjectManager objectManager, ObjectSpawn spawn) {
        List<Sonic2DeathEggRobotInstance> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == Sonic2DeathEggRobotInstance.class && !object.isDestroyed())
                .map(Sonic2DeathEggRobotInstance.class::cast)
                .filter(object -> object.getSpawn().x() == spawn.x() && object.getSpawn().y() == spawn.y())
                .toList();
        assertEquals(1, matches.size(), "expected one Death Egg Robot at " + spawn.x() + "," + spawn.y());
        return matches.getFirst();
    }

    private static ObjectInstance onlyByClassName(ObjectManager objectManager, String className) {
        List<ObjectInstance> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(className) && !object.isDestroyed())
                .toList();
        assertEquals(1, matches.size(), "expected one live " + className);
        return matches.getFirst();
    }

    private static int countLiveByClassName(ObjectManager objectManager, String className) {
        return (int) objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(className) && !object.isDestroyed())
                .count();
    }

    private static Map<String, ObjectRefId> ids(
            ObjectManager objectManager,
            Sonic2DeathEggRobotInstance capturedBoss,
            Sonic2DeathEggRobotInstance distractorBoss,
            ObjectInstance bomb) {
        var table = objectManager.captureIdentityContext().requireIdentityTable();
        Map<String, ObjectRefId> ids = new LinkedHashMap<>();
        ids.put("capturedBoss", table.idFor(capturedBoss));
        ids.put("distractorBoss", table.idFor(distractorBoss));
        ids.put("bomb", table.idFor(bomb));
        return ids;
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        field(target, fieldName).setInt(target, value);
    }

    private static int readIntField(Object target, String fieldName) throws Exception {
        return field(target, fieldName).getInt(target);
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        field(target, fieldName).setBoolean(target, value);
    }

    private static boolean readBooleanField(Object target, String fieldName) throws Exception {
        return field(target, fieldName).getBoolean(target);
    }

    private static Object readObjectField(Object target, String fieldName) throws Exception {
        return field(target, fieldName).get(target);
    }

    private static Field field(Object target, String fieldName) throws NoSuchFieldException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void invokeNoArg(Object target, String methodName) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                method.invoke(target);
                return;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithBosses() {
            return create(List.of(DISTRACTOR_BOSS_SPAWN, CAPTURED_BOSS_SPAWN));
        }

        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic2ObjectRegistry(),
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

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 4096; }
            @Override public short getHeight() { return 512; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
