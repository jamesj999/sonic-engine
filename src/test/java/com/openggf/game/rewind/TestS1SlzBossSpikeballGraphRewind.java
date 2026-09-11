package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.objects.Sonic1SeesawObjectInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1SLZBossInstance;
import com.openggf.game.sonic1.objects.bosses.Sonic1SLZBossSpikeball;
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

import java.lang.reflect.Constructor;
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

class TestS1SlzBossSpikeballGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0180, 0x0100, Sonic1ObjectIds.SLZ_BOSS, 0, 0, false, 30);
    private static final ObjectSpawn WRONG_SEESAW =
            new ObjectSpawn(0x0080, 0x0180, Sonic1ObjectIds.SEESAW, 1, 0, false, 31);
    private static final ObjectSpawn TARGET_SEESAW =
            new ObjectSpawn(0x0100, 0x0180, Sonic1ObjectIds.SEESAW, 1, 0, false, 32);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void spikeballRestoresFreshWithIdentityScalarsAndOriginMatchedLiveRelinks() {
        Harness harness = Harness.create(List.of(BOSS_SPAWN, WRONG_SEESAW, TARGET_SEESAW));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic1SLZBossInstance boss = only(objectManager, Sonic1SLZBossInstance.class);
        Sonic1SeesawObjectInstance target = liveSeesawAt(objectManager, TARGET_SEESAW.x(), TARGET_SEESAW.y());
        Sonic1SeesawObjectInstance wrong = liveSeesawAt(objectManager, WRONG_SEESAW.x(), WRONG_SEESAW.y());
        Sonic1SLZBossSpikeball before = objectManager.createDynamicObject(
                () -> new Sonic1SLZBossSpikeball(boss, target, 0x0160, 0x0120));
        seedFlyingScalars(before);

        SlzSpikeballGraph beforeGraph = SlzSpikeballGraph.fromLiveObjects(objectManager);
        Map<String, ObjectRefId> beforeIds = beforeGraph.ids(objectManager);
        Map<String, Integer> beforeScalars = spikeballScalarSnapshot(before);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(before);
        Sonic1SLZBossSpikeball replacement = objectManager.createDynamicObject(
                () -> new Sonic1SLZBossSpikeball(boss, wrong, 0x00A0, 0x0120));
        assertNotEquals(beforeIds.get("spikeball"), objectId(objectManager, replacement),
                "post-snapshot divergent SLZ spikeball must not alias captured identity");

        registry.restore(snapshot);

        SlzSpikeballGraph restoredGraph = SlzSpikeballGraph.fromLiveObjects(objectManager);
        Sonic1SLZBossSpikeball restored = restoredGraph.spikeball();
        Sonic1SLZBossInstance restoredBoss = restoredGraph.boss();
        Sonic1SeesawObjectInstance restoredTarget =
                liveSeesawAt(objectManager, TARGET_SEESAW.x(), TARGET_SEESAW.y());
        Sonic1SeesawObjectInstance restoredWrong =
                liveSeesawAt(objectManager, WRONG_SEESAW.x(), WRONG_SEESAW.y());

        assertEquals(beforeGraph.counts(), restoredGraph.counts(),
                "restore must not drop or duplicate the SLZ boss spikeball graph");
        assertEquals(beforeIds, restoredGraph.ids(objectManager),
                "restore must preserve captured SLZ boss spikeball graph identities");
        assertEquals(beforeScalars, spikeballScalarSnapshot(restored),
                "compact restore must round-trip meaningful SLZ spikeball scalar state");
        assertNotSame(before, restored, "restore must recreate the removed SLZ boss spikeball");
        assertNotSame(replacement, restored, "restore must drop the post-snapshot SLZ replacement");
        assertNotSame(boss, restoredBoss,
                "test harness disables in-place restore so stale boss refs are observable");
        assertSame(restoredBoss, readObject(restored, "boss"),
                "restored spikeball must point at the restored live SLZ boss");
        assertSame(restoredTarget, readObject(restored, "seesaw"),
                "restored spikeball must relink by captured seesaw origin");
        assertNotSame(restoredWrong, readObject(restored, "seesaw"),
                "restored spikeball must not relink to the first live boss seesaw");
        assertNotSame(boss, readObject(restored, "boss"),
                "restored spikeball must not retain a stale pre-restore boss reference");
        assertNotSame(target, readObject(restored, "seesaw"),
                "restored spikeball must not retain a stale pre-restore seesaw reference");
    }

    @Test
    void restoreRebuildsTheBossSeesawScanCacheSoAlignmentCanStillMatch() {
        Harness harness = Harness.create(List.of(BOSS_SPAWN, TARGET_SEESAW));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic1SLZBossInstance boss = only(objectManager, Sonic1SLZBossInstance.class);
        Sonic1SeesawObjectInstance seesaw = liveSeesawAt(objectManager, TARGET_SEESAW.x(), TARGET_SEESAW.y());

        // Seed the boss's lazily-populated seesaw cache as it would be mid-fight,
        // after a real update() has already run scanForSeesaws() once.
        seedScannedSeesaws(boss, seesaw);
        assertEquals(1, seesawCacheSize(boss), "harness setup sanity: cache seeded before capture");

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        registry.restore(snapshot);

        Sonic1SLZBossInstance restored = only(objectManager, Sonic1SLZBossInstance.class);
        assertNotSame(boss, restored, "test harness disables in-place restore so recreate is exercised");

        // The list itself is not part of the default rewind field capture, so
        // recreate legitimately starts it empty. What must NOT happen is a
        // stale "already scanned" flag permanently blocking the lazy re-scan:
        // drive one real update() frame (as gameplay would every frame after
        // a rewind) and confirm the cache actually repopulates.
        restored.update(1, null);
        assertEquals(1, seesawCacheSize(restored),
                "a restored SLZ boss must re-scan for seesaws on its next update() frame; if a "
                        + "restored 'already scanned' flag blocks that re-scan the cache stays "
                        + "permanently empty and Robotnik never drops another spikeball");
    }

    @Test
    void directGenericRecreateDropsNonFragmentWhenBossOrMatchingSeesawIsMissing() {
        PerObjectRewindSnapshot state = capturedNonFragmentState();

        Harness noBoss = Harness.create(List.of(TARGET_SEESAW));
        assertNull(genericRecreate(noBoss.objectManager(), state),
                "non-fragment SLZ spikeball recreate must fail without a live SLZ boss");

        Harness noMatchingSeesaw = Harness.create(List.of(BOSS_SPAWN, WRONG_SEESAW));
        assertNull(genericRecreate(noMatchingSeesaw.objectManager(), state),
                "non-fragment SLZ spikeball recreate must fail without an origin-matching live seesaw");
    }

    @Test
    void directGenericRecreateUsesCapturedOriginWhenMultipleBossSeesawsAreLive() {
        PerObjectRewindSnapshot state = capturedNonFragmentState();
        Harness harness = Harness.create(List.of(BOSS_SPAWN, WRONG_SEESAW, TARGET_SEESAW));

        Sonic1SLZBossSpikeball restored = assertInstanceOf(
                Sonic1SLZBossSpikeball.class,
                genericRecreate(harness.objectManager(), state),
                "origin-matched SLZ spikeball generic recreate must return an instance");

        assertSame(only(harness.objectManager(), Sonic1SLZBossInstance.class), readObject(restored, "boss"));
        assertSame(liveSeesawAt(harness.objectManager(), TARGET_SEESAW.x(), TARGET_SEESAW.y()),
                readObject(restored, "seesaw"),
                "generic recreate must choose the captured origin seesaw, not the first live seesaw");
    }

    @Test
    void fragmentSpikeballRestoresWithoutBossOrSeesawThroughGenericRecreate() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        Sonic1SLZBossSpikeball fragment = objectManager.createDynamicObject(
                () -> newFragment(0x0120, 0x0140, -0x0100, -0x0340));
        seedFragmentScalars(fragment);

        ObjectRefId beforeId = objectId(objectManager, fragment);
        Map<String, Integer> beforeScalars = spikeballScalarSnapshot(fragment);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(fragment);
        registry.restore(snapshot);

        Sonic1SLZBossSpikeball restored = only(objectManager, Sonic1SLZBossSpikeball.class);
        assertNotSame(fragment, restored, "restore must recreate the removed SLZ spikeball fragment");
        assertEquals(beforeId, objectId(objectManager, restored),
                "SLZ spikeball fragment dynamic identity must be preserved");
        assertEquals(beforeScalars, spikeballScalarSnapshot(restored),
                "fragment compact scalar state must survive generic recreate plus restore");
        assertEquals("FRAGMENT", readEnumName(restored, "currentState"),
                "fragment restore must preserve fragment state");
        assertNull(readObject(restored, "boss"), "fragment restore must not require a boss");
        assertNull(readObject(restored, "seesaw"), "fragment restore must not require a seesaw");
    }

    @Test
    void slzBossSpikeballUsesRewindRecreatableWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1SLZBossSpikeball.class),
                "SLZ boss spikeball must restore through RewindRecreatable graph recreate");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1SLZBossSpikeball.class.getName()),
                "SLZ boss spikeball must not keep an explicit S1 dynamic rewind codec");
    }

    @Test
    void slzBossParentUsesGenericRecreateWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1SLZBossInstance.class),
                "Sonic1SLZBossInstance must restore through RewindRecreatable graph recreate");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1SLZBossInstance.class.getName()),
                "Sonic1SLZBossInstance must not keep an explicit S1 dynamic rewind codec");
    }

    private static PerObjectRewindSnapshot capturedNonFragmentState() {
        Harness harness = Harness.create(List.of(BOSS_SPAWN, WRONG_SEESAW, TARGET_SEESAW));
        Sonic1SLZBossSpikeball spikeball = harness.objectManager().createDynamicObject(
                () -> new Sonic1SLZBossSpikeball(
                        only(harness.objectManager(), Sonic1SLZBossInstance.class),
                        liveSeesawAt(harness.objectManager(), TARGET_SEESAW.x(), TARGET_SEESAW.y()),
                        0x0160,
                        0x0120));
        seedFlyingScalars(spikeball);
        return spikeball.captureRewindState(harness.objectManager().captureIdentityContext());
    }

    private static ObjectInstance genericRecreate(ObjectManager objectManager, PerObjectRewindSnapshot state) {
        ObjectSpawn spawn = new ObjectSpawn(
                0x0160,
                0x0120,
                Sonic1ObjectIds.SLZ_BOSS_SPIKEBALL,
                0,
                0,
                false,
                0);
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(
                        Sonic1SLZBossSpikeball.class.getName(), spawn, 0, state);
        return ObjectRewindDynamicCodecs.genericRecreate(
                entry, new DynamicObjectRecreateContext(objectManager));
    }

    private static void seedFlyingScalars(Sonic1SLZBossSpikeball spikeball) {
        writeEnum(spikeball, "currentState", "FLYING");
        writeInt(spikeball, "xPos", 0x0144 << 16);
        writeInt(spikeball, "yPos", 0x0168 << 16);
        writeInt(spikeball, "xVel", -0x00F4);
        writeInt(spikeball, "yVel", -0x0960);
        writeInt(spikeball, "storedFrame", 2);
        writeInt(spikeball, "displayFrame", 0);
        writeInt(spikeball, "subtypeCounter", 0x20);
        writeInt(spikeball, "frameToggleTimer", 7);
        writeInt(spikeball, "frameToggleDuration", 5);
        writeInt(spikeball, "fragmentAnimCounter", 0);
    }

    private static void seedFragmentScalars(Sonic1SLZBossSpikeball spikeball) {
        writeInt(spikeball, "xPos", 0x0120 << 16);
        writeInt(spikeball, "yPos", 0x0140 << 16);
        writeInt(spikeball, "xVel", -0x0120);
        writeInt(spikeball, "yVel", -0x0240);
        writeInt(spikeball, "storedFrame", 0);
        writeInt(spikeball, "displayFrame", 1);
        writeInt(spikeball, "subtypeCounter", 0x20);
        writeInt(spikeball, "frameToggleTimer", 3);
        writeInt(spikeball, "frameToggleDuration", 2);
        writeInt(spikeball, "fragmentAnimCounter", 9);
    }

    @SuppressWarnings("unchecked")
    private static void seedScannedSeesaws(Sonic1SLZBossInstance boss, Sonic1SeesawObjectInstance... seesawsToAdd) {
        List<Sonic1SeesawObjectInstance> cache = (List<Sonic1SeesawObjectInstance>) readObject(boss, "seesaws");
        for (Sonic1SeesawObjectInstance seesaw : seesawsToAdd) {
            cache.add(seesaw);
        }
        // Mirrors the pre-fix "already scanned" latch so a captured snapshot
        // matches a real mid-fight state (scan already ran once). Tolerant of
        // the field's removal by the fix — the cache-emptiness gate no longer
        // needs it, but the fixture must still reproduce the pre-fix state
        // when run against the pre-fix code.
        try {
            findField(boss.getClass(), "seesawsScanned").setBoolean(boss, true);
        } catch (NoSuchFieldException ignored) {
            // Fix removed the flag; the isEmpty()-gated cache makes it moot.
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to seed seesawsScanned", e);
        }
    }

    private static int seesawCacheSize(Sonic1SLZBossInstance boss) {
        return ((List<?>) readObject(boss, "seesaws")).size();
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

    private static Sonic1SLZBossSpikeball newFragment(int x, int y, int xVel, int yVel) {
        try {
            Constructor<Sonic1SLZBossSpikeball> ctor =
                    Sonic1SLZBossSpikeball.class.getDeclaredConstructor(
                            int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(x, y, xVel, yVel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct SLZ spikeball fragment", e);
        }
    }

    private static Sonic1SeesawObjectInstance liveSeesawAt(ObjectManager objectManager, int x, int y) {
        List<Sonic1SeesawObjectInstance> matches = liveObjects(objectManager, Sonic1SeesawObjectInstance.class)
                .stream()
                .filter(seesaw -> seesaw.getSpawn().x() == x && seesaw.getSpawn().y() == y)
                .toList();
        assertEquals(1, matches.size(), "expected one live S1 seesaw at " + x + "," + y);
        return matches.getFirst();
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

    private static Map<String, Integer> spikeballScalarSnapshot(Sonic1SLZBossSpikeball spikeball) {
        Map<String, Integer> scalars = new LinkedHashMap<>();
        scalars.put("state", stateOrdinal(spikeball));
        scalars.put("xPos", readInt(spikeball, "xPos"));
        scalars.put("yPos", readInt(spikeball, "yPos"));
        scalars.put("xVel", readInt(spikeball, "xVel"));
        scalars.put("yVel", readInt(spikeball, "yVel"));
        scalars.put("seesawX", readInt(spikeball, "seesawX"));
        scalars.put("seesawY", readInt(spikeball, "seesawY"));
        scalars.put("storedFrame", readInt(spikeball, "storedFrame"));
        scalars.put("displayFrame", readInt(spikeball, "displayFrame"));
        scalars.put("subtypeCounter", readInt(spikeball, "subtypeCounter"));
        scalars.put("frameToggleTimer", readInt(spikeball, "frameToggleTimer"));
        scalars.put("frameToggleDuration", readInt(spikeball, "frameToggleDuration"));
        scalars.put("fragmentAnimCounter", readInt(spikeball, "fragmentAnimCounter"));
        return scalars;
    }

    private static int stateOrdinal(Sonic1SLZBossSpikeball spikeball) {
        Object state = readObject(spikeball, "currentState");
        return state instanceof Enum<?> enumValue ? enumValue.ordinal() : -1;
    }

    private static String readEnumName(Object target, String fieldName) {
        Object value = readObject(target, fieldName);
        return value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void writeEnum(Object target, String fieldName, String value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.set(target, Enum.valueOf((Class<Enum>) field.getType(), value));
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

    private record SlzSpikeballGraph(
            Sonic1SLZBossInstance boss,
            Sonic1SeesawObjectInstance wrongSeesaw,
            Sonic1SeesawObjectInstance targetSeesaw,
            Sonic1SLZBossSpikeball spikeball) {

        static SlzSpikeballGraph fromLiveObjects(ObjectManager objectManager) {
            return new SlzSpikeballGraph(
                    only(objectManager, Sonic1SLZBossInstance.class),
                    liveSeesawAt(objectManager, WRONG_SEESAW.x(), WRONG_SEESAW.y()),
                    liveSeesawAt(objectManager, TARGET_SEESAW.x(), TARGET_SEESAW.y()),
                    only(objectManager, Sonic1SLZBossSpikeball.class));
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            counts.put(Sonic1SLZBossInstance.class, 1);
            counts.put(Sonic1SeesawObjectInstance.class, 2);
            counts.put(Sonic1SLZBossSpikeball.class, 1);
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("boss", objectId(objectManager, boss));
            ids.put("wrongSeesaw", objectId(objectManager, wrongSeesaw));
            ids.put("targetSeesaw", objectId(objectManager, targetSeesaw));
            ids.put("spikeball", objectId(objectManager, spikeball));
            return ids;
        }
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            int leftmostSpawnX = spawns.stream()
                    .mapToInt(ObjectSpawn::x)
                    .min()
                    .orElse(0);
            Camera camera = mockCamera(Math.max(0, leftmostSpawnX - 0x80));
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

    private static Camera mockCamera(int cameraX) {
        return new Camera() {
            @Override public short getX() { return (short) cameraX; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
