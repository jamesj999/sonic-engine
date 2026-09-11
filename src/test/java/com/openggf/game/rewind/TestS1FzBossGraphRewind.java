package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.objects.bosses.FZCylinder;
import com.openggf.game.sonic1.objects.bosses.FZPlasmaBall;
import com.openggf.game.sonic1.objects.bosses.FZPlasmaLauncher;
import com.openggf.game.sonic1.objects.bosses.Sonic1FZBossInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.BossChildComponent;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1FzBossGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN = new ObjectSpawn(
            0x0100,
            0x0100,
            Sonic1ObjectIds.FZ_BOSS,
            0,
            0,
            false,
            10);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void fzBossGraphRestoresChildrenLauncherBallsAndParentSideLinks() {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) Sonic1Constants.BOSS_FZ_X, (short) Sonic1Constants.BOSS_FZ_Y);

        Sonic1FZBossInstance boss = only(objectManager, Sonic1FZBossInstance.class);
        boss.update(0, player);
        FZPlasmaLauncher launcher = only(objectManager, FZPlasmaLauncher.class);
        List<FZCylinder> cylinders = liveObjects(objectManager, FZCylinder.class);
        assertEquals(4, cylinders.size(), "placed FZ boss update must spawn four live cylinders");

        FZPlasmaBall ballA = objectManager.createDynamicObject(
                () -> new FZPlasmaBall(launcher, 0x2588, 0x053C, 0x24A4));
        FZPlasmaBall ballB = objectManager.createDynamicObject(
                () -> new FZPlasmaBall(launcher, 0x2588, 0x053C, 0x2440));
        seedCylinderScalars(cylinders);
        seedLauncherScalars(launcher, List.of(ballA, ballB));
        seedBallScalars(ballA, 0x24A4, 0x2490, 0x0550, 0x0123, 0x0140, 77, true);
        seedBallScalars(ballB, 0x2440, 0x2470, 0x0560, -0x0100, 0x0120, 88, false);

        FzGraph before = FzGraph.fromLiveObjects(objectManager);
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        Map<String, Integer> beforeScalars = before.scalarSnapshot();
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        before.removeDynamicChildren(objectManager);
        FZPlasmaLauncher replacementLauncher = objectManager.createDynamicObject(
                () -> new FZPlasmaLauncher(boss));
        FZPlasmaBall replacementBall = objectManager.createDynamicObject(
                () -> new FZPlasmaBall(replacementLauncher, 0x2600, 0x053C, 0x2600));
        assertNotEquals(beforeIds.get("ball0"), objectId(objectManager, replacementBall),
                "post-snapshot divergent dynamic child must not alias captured ball identity");

        registry.restore(snapshot);

        FzGraph restored = FzGraph.fromLiveObjects(objectManager);
        assertEquals(before.counts(), restored.counts(),
                "restore must not drop or duplicate FZ boss dynamic graph objects");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured FZ dynamic object identities");
        assertEquals(beforeScalars, restored.scalarSnapshot(),
                "compact restore must round-trip meaningful FZ child scalar state");

        assertRestoredChildrenAreFresh(before, restored);
        assertRestoredChildrenPointAtLiveParents(before, restored);
        assertBossLinks(restored);
        assertLauncherLinks(restored);

        int childComponentCount = childComponents(restored.boss()).size();
        restored.boss().update(20, player);
        assertEquals(childComponentCount, childComponents(restored.boss()).size(),
                "post-restore boss update must not spawn duplicate child components");
        assertEquals(4, liveObjects(objectManager, FZCylinder.class).size(),
                "post-restore boss update must keep exactly four live cylinders");
        assertEquals(1, liveObjects(objectManager, FZPlasmaLauncher.class).size(),
                "post-restore boss update must keep exactly one live launcher");

        restored.launcher().update(21, player);
        assertEquals(2, liveObjects(objectManager, FZPlasmaBall.class).size(),
                "post-restore launcher update must not treat restored balls as gone");
        assertEquals(2, readInt(restored.launcher(), "activeBallCount"),
                "launcher activeBallCount must remain coherent after updateWaitForBalls");
        assertEquals(2, readInt(restored.launcher(), "launcherState"),
                "launcher must remain in wait-for-balls state while restored balls are live");
    }

    @Test
    void plasmaBallsAllocateAfterLauncherEvenWhenAnEarlierSlotIsFree() {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) Sonic1Constants.BOSS_FZ_X, (short) Sonic1Constants.BOSS_FZ_Y);

        Sonic1FZBossInstance boss = only(objectManager, Sonic1FZBossInstance.class);
        boss.update(0, player);
        FZPlasmaLauncher launcher = only(objectManager, FZPlasmaLauncher.class);
        FZCylinder earlierCylinder = liveObjects(objectManager, FZCylinder.class).stream()
                .filter(cylinder -> cylinder.getSlotIndex() < launcher.getSlotIndex())
                .findFirst()
                .orElseThrow();
        objectManager.removeDynamicObject(earlierCylinder);
        harness.camera().setX((short) Sonic1Constants.BOSS_FZ_X);

        writeInt(launcher, "launcherState", 1);
        objectManager.update(Sonic1Constants.BOSS_FZ_X, player, List.of(), 1, false, false, false);

        List<FZPlasmaBall> balls = liveObjects(objectManager, FZPlasmaBall.class);
        assertEquals(4, balls.size());
        assertTrue(balls.stream().allMatch(ball -> ball.getSlotIndex() > launcher.getSlotIndex()),
                "BossPlasma_Loop uses FindNextFreeObj, so balls must never occupy a slot "
                        + "that executes before their launcher");
    }

    @Test
    void genericRecreateDropsFzChildrenWhenRequiredLiveParentIsMissing() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();

        assertNull(genericRecreate(objectManager, FZCylinder.class, Sonic1ObjectIds.EGGMAN_CYLINDER),
                "FZ cylinder generic recreate must drop when no live FZ boss exists");
        assertNull(genericRecreate(objectManager, FZPlasmaLauncher.class, Sonic1ObjectIds.BOSS_PLASMA),
                "FZ launcher generic recreate must drop when no live FZ boss exists");
        assertNull(genericRecreate(objectManager, FZPlasmaBall.class, Sonic1ObjectIds.BOSS_PLASMA),
                "FZ plasma ball generic recreate must drop when no live launcher exists");
    }

    @Test
    void fzBossGraphChildrenUseRewindRecreatableWithoutExplicitS1DynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(FZCylinder.class),
                "FZCylinder must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(FZPlasmaLauncher.class),
                "FZPlasmaLauncher must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(FZPlasmaBall.class),
                "FZPlasmaBall must restore through RewindRecreatable");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(FZCylinder.class.getName()),
                "FZCylinder must not keep an explicit S1 dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(FZPlasmaLauncher.class.getName()),
                "FZPlasmaLauncher must not keep an explicit S1 dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(FZPlasmaBall.class.getName()),
                "FZPlasmaBall must not keep an explicit S1 dynamic rewind codec");
    }

    @Test
    void fzBossParentUsesGenericRecreateWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1FZBossInstance.class),
                "Sonic1FZBossInstance must restore through RewindRecreatable graph recreate");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1FZBossInstance.class.getName()),
                "Sonic1FZBossInstance must not keep an explicit S1 dynamic rewind codec");
    }

    private static ObjectInstance genericRecreate(
            ObjectManager objectManager,
            Class<? extends ObjectInstance> type,
            int objectId) {
        ObjectSpawn spawn = new ObjectSpawn(0x2588, 0x053C, objectId, 0, 0, false, 0);
        return ObjectRewindDynamicCodecs.genericRecreate(
                new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry(
                        type.getName(), spawn, 0, emptyState()),
                new DynamicObjectRecreateContext(objectManager));
    }

    private static PerObjectRewindSnapshot emptyState() {
        return new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0,
                false, false, 0, -1, null, null, null);
    }

    private static void seedCylinderScalars(List<FZCylinder> cylinders) {
        for (FZCylinder cylinder : cylinders) {
            int subtype = readInt(cylinder, "subtype");
            writeInt(cylinder, "direction", subtype <= 2 ? -1 : 1);
            writeInt(cylinder, "extensionFixed", (subtype <= 2 ? -0x20 : 0x30) << 16);
            writeBoolean(cylinder, "active", true);
            writeBoolean(cylinder, "drivesBossPosition", subtype == 0);
            writeInt(cylinder, "currentFrame", subtype + 1);
        }
    }

    private static void seedLauncherScalars(FZPlasmaLauncher launcher, List<FZPlasmaBall> balls) {
        activeBalls(launcher).clear();
        activeBalls(launcher).addAll(balls);
        writeInt(launcher, "launcherState", 2);
        writeInt(launcher, "activeBallCount", balls.size());
        writeBoolean(launcher, "activated", true);
        writeInt(launcher, "animFrame", 2);
        writeInt(launcher, "animTimer", 9);
    }

    private static void seedBallScalars(
            FZPlasmaBall ball,
            int targetX,
            int posX,
            int posY,
            int xVel,
            int yVel,
            int lifetime,
            boolean hasCollision) {
        writeInt(ball, "phase", 4);
        writeInt(ball, "targetX", targetX);
        writeInt(ball, "posX", posX);
        writeInt(ball, "posY", posY);
        writeInt(ball, "posXFixed", posX << 16);
        writeInt(ball, "posYFixed", posY << 16);
        writeInt(ball, "xVel", xVel);
        writeInt(ball, "yVel", yVel);
        writeInt(ball, "lifetime", lifetime);
        writeInt(ball, "animId", 1);
        writeInt(ball, "animPrevId", 0);
        writeInt(ball, "animScriptFrame", 2);
        writeInt(ball, "animTimeFrame", 3);
        writeInt(ball, "animFrame", 4);
        writeBoolean(ball, "hasCollision", hasCollision);
    }

    private static void assertRestoredChildrenAreFresh(FzGraph before, FzGraph restored) {
        for (int i = 0; i < 4; i++) {
            assertNotSame(before.cylinders().get(i), restored.cylinders().get(i),
                    "restore must recreate removed FZ cylinder " + i);
        }
        assertNotSame(before.launcher(), restored.launcher(),
                "restore must recreate removed FZ plasma launcher");
        for (int i = 0; i < before.balls().size(); i++) {
            assertNotSame(before.balls().get(i), restored.balls().get(i),
                    "restore must recreate removed FZ plasma ball " + i);
        }
    }

    private static void assertRestoredChildrenPointAtLiveParents(FzGraph before, FzGraph restored) {
        assertNotSame(before.boss(), restored.boss(),
                "test harness disables in-place restore so stale parent refs are observable");
        for (FZCylinder cylinder : restored.cylinders()) {
            assertSame(restored.boss(), readObject(cylinder, "parent"),
                    "restored cylinder must point at restored live FZ boss");
            assertNotSame(before.boss(), readObject(cylinder, "parent"),
                    "restored cylinder must not retain stale pre-restore boss reference");
        }
        assertSame(restored.boss(), readObject(restored.launcher(), "parent"),
                "restored launcher must point at restored live FZ boss");
        assertNotSame(before.boss(), readObject(restored.launcher(), "parent"),
                "restored launcher must not retain stale pre-restore boss reference");
        for (FZPlasmaBall ball : restored.balls()) {
            assertSame(restored.launcher(), readObject(ball, "launcher"),
                    "restored plasma ball must point at restored live launcher");
            assertNotSame(before.launcher(), readObject(ball, "launcher"),
                    "restored plasma ball must not retain stale pre-restore launcher reference");
        }
    }

    private static void assertBossLinks(FzGraph graph) {
        Object[] cylinderArray = (Object[]) readObject(graph.boss(), "cylinders");
        assertNotNull(cylinderArray, "FZ boss cylinder array must be restored");
        assertEquals(4, cylinderArray.length);
        for (FZCylinder cylinder : graph.cylinders()) {
            int subtype = readInt(cylinder, "subtype");
            assertSame(cylinder, cylinderArray[subtype >> 1],
                    "FZ boss cylinder array must index restored cylinder by subtype");
        }
        assertSame(graph.launcher(), readObject(graph.boss(), "plasmaLauncher"),
                "FZ boss plasmaLauncher field must point at restored live launcher");
        assertTrue(readBoolean(graph.boss(), "childComponentsSpawned"),
                "FZ boss childComponentsSpawned must stay true after graph restore");

        List<BossChildComponent> children = childComponents(graph.boss());
        assertEquals(5, children.size(),
                "FZ boss childComponents must contain four cylinders and one launcher exactly once");
        for (FZCylinder cylinder : graph.cylinders()) {
            assertEquals(1, identityFrequency(children, cylinder),
                    "restored cylinder must be present exactly once in childComponents");
        }
        assertEquals(1, identityFrequency(children, graph.launcher()),
                "restored launcher must be present exactly once in childComponents");
    }

    private static void assertLauncherLinks(FzGraph graph) {
        List<FZPlasmaBall> activeBalls = activeBalls(graph.launcher());
        assertEquals(graph.balls().size(), activeBalls.size(),
                "FZ launcher activeBalls must contain restored live balls");
        for (FZPlasmaBall ball : graph.balls()) {
            assertEquals(1, identityFrequency(activeBalls, ball),
                    "restored plasma ball must be present exactly once in activeBalls");
        }
        assertEquals(graph.balls().size(), readInt(graph.launcher(), "activeBallCount"),
                "FZ launcher activeBallCount must match restored activeBalls");
    }

    private static int identityFrequency(List<?> values, Object expected) {
        int count = 0;
        for (Object value : values) {
            if (value == expected) {
                count++;
            }
        }
        return count;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = liveObjects(objectManager, type);
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<BossChildComponent> childComponents(Sonic1FZBossInstance boss) {
        return (List<BossChildComponent>) readObject(boss, "childComponents");
    }

    @SuppressWarnings("unchecked")
    private static List<FZPlasmaBall> activeBalls(FZPlasmaLauncher launcher) {
        return (List<FZPlasmaBall>) readObject(launcher, "activeBalls");
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

    private record FzGraph(
            Sonic1FZBossInstance boss,
            List<FZCylinder> cylinders,
            FZPlasmaLauncher launcher,
            List<FZPlasmaBall> balls) {

        static FzGraph fromLiveObjects(ObjectManager objectManager) {
            List<FZCylinder> cylinders = new ArrayList<>(liveObjects(objectManager, FZCylinder.class));
            cylinders.sort((a, b) -> Integer.compare(readInt(a, "subtype"), readInt(b, "subtype")));
            List<FZPlasmaBall> balls = new ArrayList<>(liveObjects(objectManager, FZPlasmaBall.class));
            balls.sort((a, b) -> Integer.compare(readInt(a, "targetX"), readInt(b, "targetX")));
            return new FzGraph(
                    only(objectManager, Sonic1FZBossInstance.class),
                    List.copyOf(cylinders),
                    only(objectManager, FZPlasmaLauncher.class),
                    List.copyOf(balls));
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            counts.put(Sonic1FZBossInstance.class, 1);
            counts.put(FZCylinder.class, cylinders.size());
            counts.put(FZPlasmaLauncher.class, 1);
            counts.put(FZPlasmaBall.class, balls.size());
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("boss", objectId(objectManager, boss));
            for (int i = 0; i < cylinders.size(); i++) {
                ids.put("cylinder" + i, objectId(objectManager, cylinders.get(i)));
            }
            ids.put("launcher", objectId(objectManager, launcher));
            for (int i = 0; i < balls.size(); i++) {
                ids.put("ball" + i, objectId(objectManager, balls.get(i)));
            }
            return ids;
        }

        Map<String, Integer> scalarSnapshot() {
            Map<String, Integer> scalars = new LinkedHashMap<>();
            for (FZCylinder cylinder : cylinders) {
                String prefix = "c" + readInt(cylinder, "subtype") + ".";
                scalars.put(prefix + "subtype", readInt(cylinder, "subtype"));
                scalars.put(prefix + "baseX", readInt(cylinder, "baseX"));
                scalars.put(prefix + "baseY", readInt(cylinder, "baseY"));
                scalars.put(prefix + "direction", readInt(cylinder, "direction"));
                scalars.put(prefix + "extensionFixed", readInt(cylinder, "extensionFixed"));
                scalars.put(prefix + "active", readBoolean(cylinder, "active") ? 1 : 0);
                scalars.put(prefix + "frame", readInt(cylinder, "currentFrame"));
            }
            scalars.put("launcher.state", readInt(launcher, "launcherState"));
            scalars.put("launcher.count", readInt(launcher, "activeBallCount"));
            scalars.put("launcher.activated", readBoolean(launcher, "activated") ? 1 : 0);
            scalars.put("launcher.animFrame", readInt(launcher, "animFrame"));
            scalars.put("launcher.animTimer", readInt(launcher, "animTimer"));
            for (int i = 0; i < balls.size(); i++) {
                FZPlasmaBall ball = balls.get(i);
                String prefix = "b" + i + ".";
                scalars.put(prefix + "targetX", readInt(ball, "targetX"));
                scalars.put(prefix + "phase", readInt(ball, "phase"));
                scalars.put(prefix + "xVel", readInt(ball, "xVel"));
                scalars.put(prefix + "yVel", readInt(ball, "yVel"));
                scalars.put(prefix + "lifetime", readInt(ball, "lifetime"));
                scalars.put(prefix + "posX", readInt(ball, "posX"));
                scalars.put(prefix + "posY", readInt(ball, "posY"));
                scalars.put(prefix + "collision", readBoolean(ball, "hasCollision") ? 1 : 0);
            }
            return scalars;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            for (FZCylinder cylinder : cylinders) {
                objectManager.removeDynamicObject(cylinder);
            }
            objectManager.removeDynamicObject(launcher);
            for (FZPlasmaBall ball : balls) {
                objectManager.removeDynamicObject(ball);
            }
        }
    }

    private record Harness(ObjectManager objectManager, TestCamera camera) {
        static Harness createWithBoss() {
            return create(List.of(BOSS_SPAWN));
        }

        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            TestCamera camera = new TestCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic1ObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager, camera);
        }
    }

    private static final class TestCamera extends Camera {
        private short x;

        @Override
        public void setX(short x) {
            this.x = x;
        }

        @Override public short getX() { return x; }
        @Override public short getY() { return 0; }
        @Override public short getWidth() { return 320; }
        @Override public short getHeight() { return 224; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }
}
