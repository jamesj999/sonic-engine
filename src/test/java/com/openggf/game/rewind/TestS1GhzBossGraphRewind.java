package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.objects.bosses.GHZBossWreckingBall;
import com.openggf.game.sonic1.objects.bosses.Sonic1GHZBossInstance;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1GhzBossGraphRewind {

    private static final int APPROACH_TARGET_X = 0x2A00;
    private static final int DESCENT_TARGET_Y = 0x338;

    /**
     * Frames GBall_Vanish takes to turn the wrecking ball into an explosion after
     * Eggman is defeated: BGHZ_BossGenericTimer is seeded with GBall_PosData's last
     * chain entry $60 and the routine ends on the frame the decrement underflows.
     */
    private static final int VANISH_UPDATES = 0x61;

    private static final ObjectSpawn BOSS_SPAWN = new ObjectSpawn(
            0x0100,
            0x0100,
            Sonic1ObjectIds.GHZ_BOSS,
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
    void ghzBossGraphRestoresWreckingBallAndParentSideLinks() {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) APPROACH_TARGET_X, (short) DESCENT_TARGET_Y);

        Sonic1GHZBossInstance boss = only(objectManager, Sonic1GHZBossInstance.class);
        spawnWreckingBallThroughBossUpdate(boss, player);
        GHZBossWreckingBall ball = only(objectManager, GHZBossWreckingBall.class);
        seedBallScalars(ball);

        GhzGraph before = GhzGraph.fromLiveObjects(objectManager);
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        Map<String, Integer> beforeScalars = before.scalarSnapshot();
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(ball);
        GHZBossWreckingBall divergentBall = objectManager.createDynamicObject(
                () -> new GHZBossWreckingBall(boss));
        assertNotEquals(beforeIds.get("ball"), objectId(objectManager, divergentBall),
                "post-snapshot divergent dynamic child must not alias captured GHZ ball identity");

        registry.restore(snapshot);

        GhzGraph restored = GhzGraph.fromLiveObjects(objectManager);
        assertEquals(before.counts(), restored.counts(),
                "restore must not drop or duplicate the GHZ boss wrecking ball");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured GHZ wrecking ball identity");
        assertEquals(beforeScalars, restored.scalarSnapshot(),
                "compact restore must round-trip meaningful GHZ wrecking ball scalar state");

        assertNotSame(before.boss(), restored.boss(),
                "test harness disables in-place restore so stale parent refs are observable");
        assertNotSame(before.ball(), restored.ball(),
                "restore must recreate the removed GHZ wrecking ball");
        assertSame(restored.boss(), readObject(restored.ball(), "parent"),
                "restored GHZ ball must point at the restored live GHZ boss");
        assertNotSame(before.boss(), readObject(restored.ball(), "parent"),
                "restored GHZ ball must not retain a stale pre-restore boss reference");
        assertSame(restored.ball(), readObject(restored.boss(), "wreckingBall"),
                "GHZ boss wreckingBall field must point at the restored live ball");
        assertEquals(1, identityFrequency(childComponents(restored.boss()), restored.ball()),
                "GHZ boss childComponents must contain the restored ball exactly once");

        int ballCount = liveObjects(objectManager, GHZBossWreckingBall.class).size();
        spawnWreckingBallThroughBossUpdate(restored.boss(), player);
        assertEquals(ballCount, liveObjects(objectManager, GHZBossWreckingBall.class).size(),
                "post-restore boss update must not spawn a duplicate wrecking ball");
        assertSame(restored.ball(), readObject(restored.boss(), "wreckingBall"),
                "post-restore update must keep the restored ball as the boss-owned child");
    }

    @Test
    void bossPrunesChildDestroyedDuringItsOwnUpdateBeforeSameFrameRewindCapture() {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) APPROACH_TARGET_X, (short) DESCENT_TARGET_Y);
        Sonic1GHZBossInstance boss = only(objectManager, Sonic1GHZBossInstance.class);
        spawnWreckingBallThroughBossUpdate(boss, player);
        GHZBossWreckingBall ball = only(objectManager, GHZBossWreckingBall.class);

        // GHZBossWreckingBall self-destructs once its managed parent is defeated,
        // but not instantly: ROM GBall_Vanish seeds BGHZ_BossGenericTimer from
        // GBall_PosData's last chain entry $60 and decrements it once per frame
        // (subq.b #1,BGHZ_BossGenericTimer(a0) / bpl.s GBall_Display4 /
        // move.b #id_Explosion,obID(a0) — docs/s1disasm/_incObj/"3D, 48 Boss -
        // GHZ Main and Wrecking Ball.asm":487, 589-598), so the ball becomes an
        // explosion on the frame the timer underflows: $60 + 1 = $61 updates.
        // Drive whole V-int-consistent frames so AbstractBossChild.shouldUpdate
        // sees the parent already updated at the same V_int_run_count.
        int vIntRunCount = boss.getState().lastUpdatedVIntRunCount;
        boss.getState().defeated = true;
        int defeatUpdates = 0;
        while (!ball.isDestroyed() && defeatUpdates < 2 * VANISH_UPDATES) {
            objectManager.update(++vIntRunCount, player, List.of(), 0, false);
            defeatUpdates++;
        }

        assertTrue(ball.isDestroyed(), "precondition: the managed child must self-destruct during parent update");
        assertEquals(VANISH_UPDATES, defeatUpdates,
                "GBall_Vanish must count BGHZ_BossGenericTimer down from $60 before the ball becomes an explosion");
        assertFalse(objectManager.getActiveObjects().contains(ball),
                "the same object pass must remove the destroyed child's managed identity");
        assertFalse(childComponents(boss).contains(ball),
                "boss childComponents must not retain an identity removed in this frame");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();
        assertDoesNotThrow(() -> registry.restore(snapshot),
                "same-frame rewind capture/restore must retain reference closure after child self-destruction");

        Sonic1GHZBossInstance restoredBoss = only(objectManager, Sonic1GHZBossInstance.class);
        assertTrue(childComponents(restoredBoss).isEmpty(),
                "restored parent must not regain the destroyed child through a stale collection link");
    }

    @Test
    void genericRecreateDropsGhzWreckingBallWhenRequiredLiveParentIsMissing() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();

        assertNull(genericRecreate(objectManager),
                "GHZ wrecking ball generic recreate must drop when no live GHZ boss exists");
    }

    @Test
    void ghzWreckingBallUsesRewindRecreatableWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(GHZBossWreckingBall.class),
                "GHZ wrecking ball must restore through RewindRecreatable");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(GHZBossWreckingBall.class.getName()),
                "GHZ wrecking ball must not keep an explicit S1 dynamic rewind codec");
    }

    @Test
    void ghzBossParentUsesGenericRecreateWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1GHZBossInstance.class),
                "Sonic1GHZBossInstance must restore through RewindRecreatable graph recreate");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1GHZBossInstance.class.getName()),
                "Sonic1GHZBossInstance must not keep an explicit S1 dynamic rewind codec");
    }

    private static void spawnWreckingBallThroughBossUpdate(
            Sonic1GHZBossInstance boss,
            TestablePlayableSprite player) {
        boss.getState().routineSecondary = 2;
        boss.getState().xFixed = APPROACH_TARGET_X << 16;
        boss.getState().yFixed = DESCENT_TARGET_Y << 16;
        boss.getState().x = APPROACH_TARGET_X;
        boss.getState().y = DESCENT_TARGET_Y;
        boss.getState().xVel = 0;
        boss.getState().yVel = 0;
        boss.update(boss.getState().lastUpdatedVIntRunCount + 1, player);
    }

    private static ObjectInstance genericRecreate(ObjectManager objectManager) {
        ObjectSpawn spawn = new ObjectSpawn(
                APPROACH_TARGET_X,
                DESCENT_TARGET_Y,
                Sonic1ObjectIds.BOSS_BALL,
                0,
                0,
                false,
                0);
        return ObjectRewindDynamicCodecs.genericRecreate(
                new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry(
                        GHZBossWreckingBall.class.getName(), spawn, 0, emptyState()),
                new DynamicObjectRecreateContext(objectManager));
    }

    private static PerObjectRewindSnapshot emptyState() {
        return new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0,
                false, false, 0, -1, null, null, null);
    }

    private static void seedBallScalars(GHZBossWreckingBall ball) {
        writeInt(ball, "angle", 0x3456);
        writeInt(ball, "swingParam", 0x0120);
        writeBoolean(ball, "swingForward", true);
        writeBoolean(ball, "chainFullyExtended", true);
        writeInt(ball, "verticalOffset", 0x20);
        writeInt(ball, "anchorX", 0x2A10);
        writeInt(ball, "anchorY", 0x0348);
        writeInt(ball, "ballFrame", 0);
        writeBoolean(ball, "parentDefeated", false);
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
    private static List<BossChildComponent> childComponents(Sonic1GHZBossInstance boss) {
        return (List<BossChildComponent>) readObject(boss, "childComponents");
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

    private record GhzGraph(Sonic1GHZBossInstance boss, GHZBossWreckingBall ball) {
        static GhzGraph fromLiveObjects(ObjectManager objectManager) {
            return new GhzGraph(
                    only(objectManager, Sonic1GHZBossInstance.class),
                    only(objectManager, GHZBossWreckingBall.class));
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            counts.put(Sonic1GHZBossInstance.class, 1);
            counts.put(GHZBossWreckingBall.class, 1);
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("boss", objectId(objectManager, boss));
            ids.put("ball", objectId(objectManager, ball));
            return ids;
        }

        Map<String, Integer> scalarSnapshot() {
            Map<String, Integer> scalars = new LinkedHashMap<>();
            scalars.put("angle", readInt(ball, "angle"));
            scalars.put("swingParam", readInt(ball, "swingParam"));
            scalars.put("swingForward", readBoolean(ball, "swingForward") ? 1 : 0);
            scalars.put("chainFullyExtended", readBoolean(ball, "chainFullyExtended") ? 1 : 0);
            scalars.put("verticalOffset", readInt(ball, "verticalOffset"));
            scalars.put("anchorX", readInt(ball, "anchorX"));
            scalars.put("anchorY", readInt(ball, "anchorY"));
            scalars.put("ballFrame", readInt(ball, "ballFrame"));
            scalars.put("parentDefeated", readBoolean(ball, "parentDefeated") ? 1 : 0);
            return scalars;
        }
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithBoss() {
            return create(List.of(BOSS_SPAWN));
        }

        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
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
            return new Harness(objectManager);
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
