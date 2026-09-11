package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.objects.bosses.Sonic1BossBlockInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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

class TestS1SyzBossBlockGraphRewind {

    private static final int STATE_GRABBED = 1;
    private static final int STATE_FRAGMENT = 3;

    private static final ObjectSpawn BOSS_SPAWN = new ObjectSpawn(
            0x0100,
            0x0100,
            Sonic1ObjectIds.SYZ_BOSS,
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
    void grabbedBossBlockRestoresFreshWithIdentityScalarsAndLiveBossRelink() {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic1SYZBossInstance boss = only(objectManager, Sonic1SYZBossInstance.class);
        Sonic1BossBlockInstance block = objectManager.createDynamicObject(
                () -> new Sonic1BossBlockInstance(4));
        block.setGrabbedByBoss(boss);
        seedGrabbedBlockScalars(block);

        SyzBlockGraph before = SyzBlockGraph.fromLiveObjects(objectManager);
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        Map<String, Integer> beforeScalars = before.scalarSnapshot();
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(block);
        Sonic1BossBlockInstance divergentBlock = objectManager.createDynamicObject(
                () -> new Sonic1BossBlockInstance(7));
        divergentBlock.setGrabbedByBoss(boss);
        assertNotEquals(beforeIds.get("block"), objectId(objectManager, divergentBlock),
                "post-snapshot divergent SYZ block must not alias captured identity");

        registry.restore(snapshot);

        SyzBlockGraph restored = SyzBlockGraph.fromLiveObjects(objectManager);
        assertEquals(before.counts(), restored.counts(),
                "restore must not drop or duplicate the SYZ boss block graph");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured SYZ boss block identity");
        assertEquals(beforeScalars, restored.scalarSnapshot(),
                "compact restore must round-trip meaningful SYZ boss block scalar state");
        assertNotSame(before.boss(), restored.boss(),
                "test harness disables in-place restore so stale boss refs are observable");
        assertNotSame(before.block(), restored.block(),
                "restore must recreate the removed SYZ boss block");
        assertSame(restored.boss(), readObject(restored.block(), "grabbingBoss"),
                "restored SYZ boss block must point at the restored live SYZ boss");
        assertNotSame(before.boss(), readObject(restored.block(), "grabbingBoss"),
                "restored SYZ boss block must not retain a stale pre-restore boss reference");
    }

    @Test
    void fragmentBossBlockRestoresColumnFragmentPhysicsAndFrameThroughGenericRecreate() {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic1BossBlockInstance fragment = objectManager.createDynamicObject(
                () -> new Sonic1BossBlockInstance(0));
        seedFragmentScalars(fragment);
        ObjectRefId beforeId = objectId(objectManager, fragment);
        Map<String, Integer> beforeScalars = blockScalarSnapshot(fragment);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(fragment);
        Sonic1BossBlockInstance divergentBlock = objectManager.createDynamicObject(
                () -> new Sonic1BossBlockInstance(9));
        assertNotEquals(beforeId, objectId(objectManager, divergentBlock),
                "post-snapshot divergent SYZ fragment replacement must not alias captured identity");

        registry.restore(snapshot);

        Sonic1BossBlockInstance restored = only(objectManager, Sonic1BossBlockInstance.class);
        assertNotSame(fragment, restored, "restore must recreate the removed SYZ boss block fragment");
        assertEquals(beforeId, objectId(objectManager, restored),
                "SYZ boss block fragment dynamic identity must be preserved");
        assertEquals(beforeScalars, blockScalarSnapshot(restored),
                "fragment scalar and fixed-point state must survive generic recreate plus compact restore");
        assertEquals(-1, readInt(restored, "blockColumn"),
                "fragment restore must repair the column-ctor placeholder back to fragment column -1");
        assertEquals(STATE_FRAGMENT, readInt(restored, "blockState"),
                "fragment restore must preserve fragment state");
    }

    @Test
    void directGenericRecreateWithoutLiveSyzBossReturnsBlockWithNullGrabbingBoss() {
        Harness harness = Harness.create(List.of());

        ObjectInstance result = genericRecreate(harness.objectManager());

        Sonic1BossBlockInstance block = assertInstanceOf(Sonic1BossBlockInstance.class, result,
                "SYZ boss block generic recreate must return a block even when no live boss exists");
        assertNull(readObject(block, "grabbingBoss"),
                "no-live-boss recreate must preserve the old codec behavior of leaving grabbingBoss null");
    }

    @Test
    void syzBossBlockUsesRewindRecreatableWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1BossBlockInstance.class),
                "SYZ boss block must restore through RewindRecreatable");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1BossBlockInstance.class.getName()),
                "SYZ boss block must not keep an explicit S1 dynamic rewind codec");
    }

    @Test
    void syzBossParentUsesGenericRecreateWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1SYZBossInstance.class),
                "Sonic1SYZBossInstance must restore through RewindRecreatable graph recreate");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1SYZBossInstance.class.getName()),
                "Sonic1SYZBossInstance must not keep an explicit S1 dynamic rewind codec");
    }

    private static ObjectInstance genericRecreate(ObjectManager objectManager) {
        ObjectSpawn spawn = new ObjectSpawn(
                0x2C90,
                0x0582,
                Sonic1ObjectIds.SYZ_BOSS_BLOCK,
                4,
                0,
                false,
                0);
        return ObjectRewindDynamicCodecs.genericRecreate(
                new ObjectManagerSnapshot.DynamicObjectEntry(
                        Sonic1BossBlockInstance.class.getName(), spawn, 0, emptyState()),
                new DynamicObjectRecreateContext(objectManager));
    }

    private static void seedGrabbedBlockScalars(Sonic1BossBlockInstance block) {
        writeInt(block, "x", 0x2C90);
        writeInt(block, "y", 0x04EE);
        writeInt(block, "blockColumn", 4);
        writeInt(block, "blockState", STATE_GRABBED);
        writeInt(block, "xVel", 0x0120);
        writeInt(block, "yVel", -0x0180);
        writeInt(block, "xFixed", 0x2C90 << 16);
        writeInt(block, "yFixed", 0x04EE << 16);
        writeInt(block, "fragmentFrame", 0);
    }

    private static void seedFragmentScalars(Sonic1BossBlockInstance block) {
        writeInt(block, "x", 0x2C68);
        writeInt(block, "y", 0x0574);
        writeInt(block, "blockColumn", -1);
        writeInt(block, "blockState", STATE_FRAGMENT);
        writeInt(block, "xVel", -0x0180);
        writeInt(block, "yVel", -0x0200);
        writeInt(block, "xFixed", 0x2C68 << 16);
        writeInt(block, "yFixed", 0x0574 << 16);
        writeInt(block, "fragmentFrame", 3);
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

    private static Map<String, Integer> blockScalarSnapshot(Sonic1BossBlockInstance block) {
        Map<String, Integer> scalars = new LinkedHashMap<>();
        scalars.put("x", readInt(block, "x"));
        scalars.put("y", readInt(block, "y"));
        scalars.put("blockColumn", readInt(block, "blockColumn"));
        scalars.put("blockState", readInt(block, "blockState"));
        scalars.put("xVel", readInt(block, "xVel"));
        scalars.put("yVel", readInt(block, "yVel"));
        scalars.put("xFixed", readInt(block, "xFixed"));
        scalars.put("yFixed", readInt(block, "yFixed"));
        scalars.put("fragmentFrame", readInt(block, "fragmentFrame"));
        return scalars;
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

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
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

    private record SyzBlockGraph(Sonic1SYZBossInstance boss, Sonic1BossBlockInstance block) {
        static SyzBlockGraph fromLiveObjects(ObjectManager objectManager) {
            return new SyzBlockGraph(
                    only(objectManager, Sonic1SYZBossInstance.class),
                    only(objectManager, Sonic1BossBlockInstance.class));
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            counts.put(Sonic1SYZBossInstance.class, 1);
            counts.put(Sonic1BossBlockInstance.class, 1);
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("boss", objectId(objectManager, boss));
            ids.put("block", objectId(objectManager, block));
            return ids;
        }

        Map<String, Integer> scalarSnapshot() {
            return blockScalarSnapshot(block);
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
