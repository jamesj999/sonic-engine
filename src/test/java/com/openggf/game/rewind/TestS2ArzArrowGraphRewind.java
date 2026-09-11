package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.bosses.ARZBossArrow;
import com.openggf.game.sonic2.objects.bosses.ARZBossEyes;
import com.openggf.game.sonic2.objects.bosses.Sonic2ARZBossInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2ArzArrowGraphRewind {

    private static final ObjectSpawn CAPTURED_BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0100, Sonic2ObjectIds.ARZ_BOSS, 0, 0, false, 31, 0x0110);
    private static final ObjectSpawn DISTRACTOR_BOSS_SPAWN =
            new ObjectSpawn(0x0150, 0x0108, Sonic2ObjectIds.ARZ_BOSS, 0, 0, false, 32, 0x0220);
    private static final ObjectSpawn CAPTURED_EYES_SPAWN =
            new ObjectSpawn(0x0190, 0x0120, Sonic2ObjectIds.ARZ_BOSS, 0x08, 0, false, 41);
    private static final ObjectSpawn DISTRACTOR_EYES_SPAWN =
            new ObjectSpawn(0x0162, 0x0111, Sonic2ObjectIds.ARZ_BOSS, 0x08, 0, false, 42);
    private static final ObjectSpawn ARROW_SPAWN =
            new ObjectSpawn(0x0160, 0x0110, Sonic2ObjectIds.ARZ_BOSS, 0x06, 1, false, 43);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void arrowGraphRestoresCapturedReferencesOverNearestFallbackAndPreservesScalars() throws Exception {
        Harness harness = Harness.createWithLayoutBosses();
        ObjectManager objectManager = harness.objectManager();
        Sonic2ARZBossInstance capturedBoss = bossAt(objectManager, CAPTURED_BOSS_SPAWN);
        Sonic2ARZBossInstance distractorBoss = bossAt(objectManager, DISTRACTOR_BOSS_SPAWN);
        setBossPosition(capturedBoss, 0x0200, 0x0140);
        setBossPosition(distractorBoss, 0x015F, 0x0110);

        ARZBossEyes capturedEyes = objectManager.createDynamicObject(() -> new ARZBossEyes(CAPTURED_EYES_SPAWN));
        ARZBossEyes distractorEyes = objectManager.createDynamicObject(() -> new ARZBossEyes(DISTRACTOR_EYES_SPAWN));
        ARZBossArrow capturedArrow = objectManager.createDynamicObject(
                () -> new ARZBossArrow(ARROW_SPAWN, capturedBoss, capturedEyes, false));
        seedArrowScalars(capturedArrow);

        Map<Class<?>, Integer> beforeCounts = countGraphObjects(objectManager);
        Map<String, ObjectRefId> beforeIds = ids(objectManager, capturedBoss, capturedEyes,
                distractorBoss, distractorEyes, capturedArrow);

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(capturedArrow);
        ARZBossArrow replacementArrow = objectManager.createDynamicObject(
                () -> new ARZBossArrow(ARROW_SPAWN, distractorBoss, distractorEyes, true));
        assertNotEquals(beforeIds.get("arrow"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacementArrow));

        rewindRegistry.restore(snapshot);

        Map<Class<?>, Integer> restoredCounts = countGraphObjects(objectManager);
        assertEquals(beforeCounts, restoredCounts,
                "restore must keep the captured ARZ graph object counts");

        ARZBossGraph restored = ARZBossGraph.fromLiveObjects(objectManager);
        assertEquals(beforeIds, ids(objectManager, restored.capturedBoss(), restored.capturedEyes(),
                restored.distractorBoss(), restored.distractorEyes(), restored.arrow()),
                "restore must preserve captured ARZ graph identities");

        assertNotSame(capturedArrow, restored.arrow());
        assertNotSame(replacementArrow, restored.arrow());
        assertSame(restored.capturedBoss(), readObjectField(restored.arrow(), "mainBoss"));
        assertNotSame(restored.distractorBoss(), readObjectField(restored.arrow(), "mainBoss"));
        assertArrowScalars(restored.arrow());
    }

    @Test
    void postInitArrowSurvivesRewindAfterEyesExpire() throws Exception {
        Harness harness = Harness.create(List.of(CAPTURED_BOSS_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        Sonic2ARZBossInstance capturedBoss = bossAt(objectManager, CAPTURED_BOSS_SPAWN);
        setBossPosition(capturedBoss, 0x0200, 0x0140);

        ARZBossEyes expiredEyes = objectManager.createDynamicObject(() -> new ARZBossEyes(CAPTURED_EYES_SPAWN));
        ARZBossArrow arrow = objectManager.createDynamicObject(
                () -> new ARZBossArrow(ARROW_SPAWN, capturedBoss, expiredEyes, false));
        arrow.update(0, null);
        objectManager.removeDynamicObject(expiredEyes);
        seedArrowScalars(arrow);
        Map<String, ObjectRefId> beforeIds = ids(objectManager, capturedBoss, arrow);

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(arrow);
        ARZBossArrow replacementArrow = objectManager.createDynamicObject(
                () -> new ARZBossArrow(ARROW_SPAWN, capturedBoss, null, true));
        assertNotEquals(beforeIds.get("arrow"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacementArrow));

        rewindRegistry.restore(snapshot);

        ARZBossArrow restoredArrow = only(objectManager, ARZBossArrow.class);
        assertNotSame(arrow, restoredArrow);
        assertNotSame(replacementArrow, restoredArrow);
        assertEquals(beforeIds, ids(objectManager, only(objectManager, Sonic2ARZBossInstance.class), restoredArrow));
        assertSame(only(objectManager, Sonic2ARZBossInstance.class), readObjectField(restoredArrow, "mainBoss"));
        assertNull(readObjectField(restoredArrow, "eyes"),
                "post-init ARZ arrows must not keep expired eyes as a captured graph edge");
        assertArrowScalars(restoredArrow);
    }

    @Test
    void genericRecreateRequiresBossButAllowsMissingEyes() {
        ObjectSpawn spawn = ARROW_SPAWN;

        assertNull(genericRecreateArrow(Harness.create(List.of()).objectManager(), spawn),
                "ARZ arrow cannot recreate without a boss target");

        Harness bossOnly = Harness.create(List.of(CAPTURED_BOSS_SPAWN));
        assertInstanceOf(ARZBossArrow.class, genericRecreateArrow(bossOnly.objectManager(), spawn),
                "post-init ARZ arrows can recreate with only a boss target after eyes expire");

        Harness eyesOnly = Harness.create(List.of());
        eyesOnly.objectManager().createDynamicObject(() -> new ARZBossEyes(CAPTURED_EYES_SPAWN));
        assertNull(genericRecreateArrow(eyesOnly.objectManager(), spawn),
                "ARZ arrow cannot recreate with only an eyes target");
    }

    @Test
    void unmanagedRequiredArrowReferencesStillFailCapture() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        ARZBossArrow arrow = objectManager.createDynamicObject(() -> new ARZBossArrow(
                ARROW_SPAWN,
                new Sonic2ARZBossInstance(CAPTURED_BOSS_SPAWN),
                null,
                false));

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, rewindRegistry::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"));
        assertSame(arrow, only(objectManager, ARZBossArrow.class));
    }

    @Test
    void arrowAndEyesUseGenericGraphRecreateWithoutExplicitS2Codec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2ARZBossInstance.class),
                "Sonic2ARZBossInstance must support generic RewindRecreatable recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(ARZBossArrow.class),
                "ARZBossArrow must restore through RewindRecreatable graph recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(ARZBossEyes.class),
                "ARZBossEyes must support generic RewindRecreatable recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic2ARZBossInstance.class.getName()),
                "Sonic2ARZBossInstance must not have an explicit S2 dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ARZBossArrow.class.getName()),
                "ARZBossArrow must not have an explicit S2 dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ARZBossEyes.class.getName()),
                "ARZBossEyes graph support must not add an explicit S2 dynamic rewind codec");
    }

    private static ObjectInstance genericRecreateArrow(ObjectManager objectManager, ObjectSpawn spawn) {
        ObjectManagerSnapshot.DynamicObjectEntry entry = new ObjectManagerSnapshot.DynamicObjectEntry(
                ARZBossArrow.class.getName(), spawn, -1, null);
        return ObjectRewindDynamicCodecs.genericRecreate(entry, new DynamicObjectRecreateContext(objectManager));
    }

    private static void seedArrowScalars(ARZBossArrow arrow) throws Exception {
        setIntField(arrow, "x", 0x016A);
        setIntField(arrow, "y", 0x0118);
        setIntField(arrow, "renderFlags", 1);
        setIntField(arrow, "routineState", 4);
        setIntField(arrow, "mappingFrame", 6);
        setIntField(arrow, "collisionFlags", 0xB0);
        setIntField(arrow, "xVel", -3);
        setIntField(arrow, "yVel", 7);
        setIntField(arrow, "arrowTimer", 0x1C);
        setIntField(arrow, "arrowAnim", 1);
        setIntField(arrow, "arrowAnimLast", 0);
        setIntField(arrow, "arrowAnimFrame", 9);
        setIntField(arrow, "arrowAnimTimer", 2);
    }

    private static void assertArrowScalars(ARZBossArrow arrow) throws Exception {
        assertEquals(0x016A, arrow.getX());
        assertEquals(0x0118, arrow.getY());
        assertEquals(1, readIntField(arrow, "renderFlags"));
        assertEquals(4, readIntField(arrow, "routineState"));
        assertEquals(6, readIntField(arrow, "mappingFrame"));
        assertEquals(0xB0, arrow.getCollisionFlags());
        assertEquals(-3, readIntField(arrow, "xVel"));
        assertEquals(7, readIntField(arrow, "yVel"));
        assertEquals(0x1C, readIntField(arrow, "arrowTimer"));
        assertEquals(1, readIntField(arrow, "arrowAnim"));
        assertEquals(0, readIntField(arrow, "arrowAnimLast"));
        assertEquals(9, readIntField(arrow, "arrowAnimFrame"));
        assertEquals(2, readIntField(arrow, "arrowAnimTimer"));
        assertEquals(false, readBooleanField(arrow, "fromRightPillar"));
    }

    private static Map<Class<?>, Integer> countGraphObjects(ObjectManager objectManager) {
        Map<Class<?>, Integer> counts = new LinkedHashMap<>();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!object.isDestroyed()
                    && (object.getClass() == Sonic2ARZBossInstance.class
                    || object.getClass() == ARZBossEyes.class
                    || object.getClass() == ARZBossArrow.class)) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, ObjectRefId> ids(
            ObjectManager objectManager,
            Sonic2ARZBossInstance capturedBoss,
            ARZBossEyes capturedEyes,
            Sonic2ARZBossInstance distractorBoss,
            ARZBossEyes distractorEyes,
            ARZBossArrow arrow) {
        var table = objectManager.captureIdentityContext().requireIdentityTable();
        Map<String, ObjectRefId> ids = new LinkedHashMap<>();
        ids.put("capturedBoss", table.idFor(capturedBoss));
        ids.put("capturedEyes", table.idFor(capturedEyes));
        ids.put("distractorBoss", table.idFor(distractorBoss));
        ids.put("distractorEyes", table.idFor(distractorEyes));
        ids.put("arrow", table.idFor(arrow));
        return ids;
    }

    private static Map<String, ObjectRefId> ids(
            ObjectManager objectManager,
            Sonic2ARZBossInstance capturedBoss,
            ARZBossArrow arrow) {
        var table = objectManager.captureIdentityContext().requireIdentityTable();
        Map<String, ObjectRefId> ids = new LinkedHashMap<>();
        ids.put("capturedBoss", table.idFor(capturedBoss));
        ids.put("arrow", table.idFor(arrow));
        return ids;
    }

    private static Sonic2ARZBossInstance bossAt(ObjectManager objectManager, ObjectSpawn spawn) {
        List<Sonic2ARZBossInstance> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == Sonic2ARZBossInstance.class)
                .map(Sonic2ARZBossInstance.class::cast)
                .filter(object -> object.getSpawn().rawYWord() == spawn.rawYWord())
                .toList();
        assertEquals(1, matches.size(), "expected one ARZ boss for rawYWord " + spawn.rawYWord());
        return matches.getFirst();
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static void setBossPosition(Sonic2ARZBossInstance boss, int x, int y) {
        boss.getState().x = x;
        boss.getState().y = y;
        boss.getState().xFixed = x << 16;
        boss.getState().yFixed = y << 16;
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        Field field = field(target, fieldName);
        field.setInt(target, value);
    }

    private static int readIntField(Object target, String fieldName) throws Exception {
        return field(target, fieldName).getInt(target);
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

    private record ARZBossGraph(
            Sonic2ARZBossInstance capturedBoss,
            ARZBossEyes capturedEyes,
            Sonic2ARZBossInstance distractorBoss,
            ARZBossEyes distractorEyes,
            ARZBossArrow arrow) {

        static ARZBossGraph fromLiveObjects(ObjectManager objectManager) {
            List<Sonic2ARZBossInstance> bosses = objectManager.getActiveObjects().stream()
                    .filter(object -> object.getClass() == Sonic2ARZBossInstance.class && !object.isDestroyed())
                    .map(Sonic2ARZBossInstance.class::cast)
                    .toList();
            List<ARZBossEyes> eyes = objectManager.getActiveObjects().stream()
                    .filter(object -> object.getClass() == ARZBossEyes.class && !object.isDestroyed())
                    .map(ARZBossEyes.class::cast)
                    .toList();
            assertEquals(2, bosses.size(), "expected two ARZ bosses after restore");
            assertEquals(2, eyes.size(), "expected two ARZ eyes after restore");
            Sonic2ARZBossInstance capturedBoss = bosses.stream()
                    .filter(boss -> boss.getSpawn().rawYWord() == CAPTURED_BOSS_SPAWN.rawYWord())
                    .findFirst()
                    .orElseThrow();
            Sonic2ARZBossInstance distractorBoss = bosses.stream()
                    .filter(boss -> boss.getSpawn().rawYWord() == DISTRACTOR_BOSS_SPAWN.rawYWord())
                    .findFirst()
                    .orElseThrow();
            ARZBossEyes capturedEyes = eyes.stream()
                    .filter(eye -> eye.getSpawn().rawYWord() == CAPTURED_EYES_SPAWN.rawYWord())
                    .findFirst()
                    .orElseThrow();
            ARZBossEyes distractorEyes = eyes.stream()
                    .filter(eye -> eye.getSpawn().rawYWord() == DISTRACTOR_EYES_SPAWN.rawYWord())
                    .findFirst()
                    .orElseThrow();
            ARZBossArrow arrow = assertInstanceOf(ARZBossArrow.class, only(objectManager, ARZBossArrow.class));
            return new ARZBossGraph(capturedBoss, capturedEyes, distractorBoss, distractorEyes, arrow);
        }
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithLayoutBosses() {
            return create(List.of(CAPTURED_BOSS_SPAWN, DISTRACTOR_BOSS_SPAWN));
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
