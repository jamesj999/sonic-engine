package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.AizMinibossArmChild;
import com.openggf.game.sonic3k.objects.AizMinibossBarrelShotChild;
import com.openggf.game.sonic3k.objects.AizMinibossBarrelShotFlareChild;
import com.openggf.game.sonic3k.objects.AizMinibossBodyChild;
import com.openggf.game.sonic3k.objects.AizMinibossCutsceneInstance;
import com.openggf.game.sonic3k.objects.AizMinibossFlameBarrelChild;
import com.openggf.game.sonic3k.objects.AizMinibossFlameChild;
import com.openggf.game.sonic3k.objects.AizMinibossInstance;
import com.openggf.game.sonic3k.objects.AizMinibossNapalmProjectile;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.AbstractBossInstance;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kAizMinibossGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.AIZ_MINIBOSS, 0, 0, false, 10);
    private static final ObjectSpawn CUTSCENE_BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.AIZ_MINIBOSS_CUTSCENE, 0, 0, false, 10);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void aizMinibossGraphRestoresWithoutDropsDoublesOrStaleReferences() throws Exception {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        AizGraph before = AizGraph.spawnRepresentativeFamily(objectManager);

        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        AizGraph replacement = AizGraph.spawnRepresentativeFamily(objectManager);
        assertNotEquals(beforeIds.get("body"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacement.body()));

        rewindRegistry.restore(snapshot);

        AizGraph restored = AizGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate any AIZ miniboss graph object");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured dynamic object identities");

        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(before, restored);
    }

    @Test
    void aizMinibossParentUsesGenericRecreateWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(AizMinibossInstance.class),
                "AizMinibossInstance must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizMinibossInstance.class.getName()),
                "AizMinibossInstance must not keep an explicit S3K dynamic rewind codec");
    }

    @Test
    void aiz1CutsceneMinibossRestoresSharedTrackedChildren() throws Exception {
        Harness harness = Harness.createWithSpawn(CUTSCENE_BOSS_SPAWN);
        ObjectManager objectManager = harness.objectManager();
        AizMinibossCutsceneInstance boss = only(objectManager, AizMinibossCutsceneInstance.class);
        AizMinibossBodyChild body = objectManager.createDynamicObject(
                () -> new AizMinibossBodyChild(boss));
        AizMinibossArmChild arm = objectManager.createDynamicObject(
                () -> new AizMinibossArmChild(boss));
        AizMinibossFlameBarrelChild barrel = objectManager.createDynamicObject(
                () -> new AizMinibossFlameBarrelChild(boss, 0, true));
        boss.getChildComponents().addAll(List.of(body, arm, barrel));

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(body);
        objectManager.removeDynamicObject(arm);
        objectManager.removeDynamicObject(barrel);

        rewindRegistry.restore(snapshot);

        AizMinibossCutsceneInstance restoredBoss =
                only(objectManager, AizMinibossCutsceneInstance.class);
        AizMinibossBodyChild restoredBody = only(objectManager, AizMinibossBodyChild.class);
        AizMinibossArmChild restoredArm = only(objectManager, AizMinibossArmChild.class);
        AizMinibossFlameBarrelChild restoredBarrel =
                only(objectManager, AizMinibossFlameBarrelChild.class);
        assertEquals(3, restoredBoss.getChildComponents().size());
        assertSame(restoredBoss, readObjectField(restoredBody, "parent"));
        assertSame(restoredBoss, readObjectField(restoredArm, "parent"));
        assertSame(restoredBoss, readObjectField(restoredBarrel, "parent"));
    }

    @Test
    void missingRequiredObjectReferencesStillFailWhenTargetHasNoRewindIdentity() {
        Harness externalHarness = Harness.createWithBoss();
        AizGraph external = AizGraph.spawnRepresentativeFamily(externalHarness.objectManager());
        RequiredReferenceFixture fixture = new RequiredReferenceFixture(external.shot());
        RewindCaptureContext context =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertEquals(true, thrown.getMessage().contains("no registered id for object reference"),
                "non-null required object references must still require registered rewind identities");
    }

    private static void assertAllReferencesPointAtRestoredGraph(AizGraph graph) throws Exception {
        assertSame(graph.boss(), readObjectField(graph.body(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.arm(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.napalm(), "parent"));
        assertSame(graph.barrel2(), readObjectField(graph.napalm(), "barrel"),
                "FallingShot must relink to the existing barrel that spawned it");
        assertSame(graph.boss(), readObjectField(graph.barrel0(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.barrel1(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.barrel2(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.flame(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.shot(), "parent"));
        assertSame(graph.barrel2(), readObjectField(graph.shot(), "barrel"),
                "barrel shot must relink to the selected nearest flame barrel");
        assertSame(graph.barrel2(), readObjectField(graph.flare(), "anchor"),
                "barrel shot flare must follow production topology and relink to the flame barrel");
        assertNotSame(graph.shot(), readObjectField(graph.flare(), "anchor"),
                "barrel shot flare must not relink to the shot object");
    }

    private static void assertRestoredObjectsAreFresh(AizGraph before, AizGraph restored) {
        assertNotSame(before.body(), restored.body());
        assertNotSame(before.arm(), restored.arm());
        assertNotSame(before.napalm(), restored.napalm());
        assertNotSame(before.barrel0(), restored.barrel0());
        assertNotSame(before.barrel1(), restored.barrel1());
        assertNotSame(before.barrel2(), restored.barrel2());
        assertNotSame(before.flame(), restored.flame());
        assertNotSame(before.shot(), restored.shot());
        assertNotSame(before.flare(), restored.flare());
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithBoss() {
            return createWithSpawn(BOSS_SPAWN);
        }

        static Harness createWithSpawn(ObjectSpawn spawn) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(spawn),
                    new Sonic3kObjectRegistry(),
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

    private static final class RequiredReferenceFixture {
        ObjectInstance object;

        private RequiredReferenceFixture(ObjectInstance object) {
            this.object = object;
        }
    }

    private record AizGraph(
            AizMinibossInstance boss,
            AizMinibossBodyChild body,
            AizMinibossArmChild arm,
            AizMinibossNapalmProjectile napalm,
            AizMinibossFlameBarrelChild barrel0,
            AizMinibossFlameBarrelChild barrel1,
            AizMinibossFlameBarrelChild barrel2,
            AizMinibossFlameChild flame,
            AizMinibossBarrelShotChild shot,
            AizMinibossBarrelShotFlareChild flare) {

        static AizGraph spawnRepresentativeFamily(ObjectManager objectManager) {
            AizMinibossInstance boss = only(objectManager, AizMinibossInstance.class);
            AizMinibossBodyChild body = objectManager.createDynamicObject(
                    () -> new AizMinibossBodyChild(boss));
            AizMinibossArmChild arm = objectManager.createDynamicObject(
                    () -> new AizMinibossArmChild(boss));
            AizMinibossFlameBarrelChild barrel0 = objectManager.createDynamicObject(
                    () -> new AizMinibossFlameBarrelChild(boss, 0, false));
            AizMinibossFlameBarrelChild barrel1 = objectManager.createDynamicObject(
                    () -> new AizMinibossFlameBarrelChild(boss, 1, false));
            AizMinibossFlameBarrelChild barrel2 = objectManager.createDynamicObject(
                    () -> new AizMinibossFlameBarrelChild(boss, 2, false));
            AizMinibossNapalmProjectile napalm = objectManager.createDynamicObject(
                    () -> new AizMinibossNapalmProjectile(
                            boss, barrel2, barrel2.getX(), barrel2.getY() + 4, 2));
            AizMinibossFlameChild flame = objectManager.createDynamicObject(
                    () -> new AizMinibossFlameChild(boss, -0x64, 4, 0));
            AizMinibossBarrelShotChild shot = objectManager.createDynamicObject(
                    () -> newBarrelShot(boss, barrel2, barrel2.getX(), barrel2.getY() + 4));
            AizMinibossBarrelShotFlareChild flare = objectManager.createDynamicObject(
                    () -> new AizMinibossBarrelShotFlareChild(barrel2));
            return new AizGraph(boss, body, arm, napalm, barrel0, barrel1, barrel2, flame, shot, flare);
        }

        static AizGraph fromLiveObjects(ObjectManager objectManager) {
            List<AizMinibossFlameBarrelChild> barrels =
                    liveObjects(objectManager, AizMinibossFlameBarrelChild.class);
            assertEquals(3, barrels.size(), "expected exactly three live AIZ miniboss flame barrels");
            return new AizGraph(
                    only(objectManager, AizMinibossInstance.class),
                    only(objectManager, AizMinibossBodyChild.class),
                    only(objectManager, AizMinibossArmChild.class),
                    only(objectManager, AizMinibossNapalmProjectile.class),
                    barrelByIndex(barrels, 0),
                    barrelByIndex(barrels, 1),
                    barrelByIndex(barrels, 2),
                    only(objectManager, AizMinibossFlameChild.class),
                    only(objectManager, AizMinibossBarrelShotChild.class),
                    only(objectManager, AizMinibossBarrelShotFlareChild.class));
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            for (ObjectInstance object : objects()) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            var table = objectManager.captureIdentityContext().requireIdentityTable();
            ids.put("boss", table.idFor(boss));
            ids.put("body", table.idFor(body));
            ids.put("arm", table.idFor(arm));
            ids.put("napalm", table.idFor(napalm));
            ids.put("barrel0", table.idFor(barrel0));
            ids.put("barrel1", table.idFor(barrel1));
            ids.put("barrel2", table.idFor(barrel2));
            ids.put("flame", table.idFor(flame));
            ids.put("shot", table.idFor(shot));
            ids.put("flare", table.idFor(flare));
            return ids;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            for (ObjectInstance object : objects()) {
                if (object != boss) {
                    objectManager.removeDynamicObject(object);
                }
            }
        }

        private List<ObjectInstance> objects() {
            return List.of(boss, body, arm, napalm, barrel0, barrel1, barrel2, flame, shot, flare);
        }
    }

    private static AizMinibossBarrelShotChild newBarrelShot(
            AbstractBossInstance boss,
            AizMinibossFlameBarrelChild barrel,
            int x,
            int y) {
        try {
            Class<?> modeClass = Class.forName(
                    "com.openggf.game.sonic3k.objects.AizMinibossBarrelShotChild$Mode");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object mode = Enum.valueOf((Class<? extends Enum>) modeClass, "ADVANCED_COLLIDING");
            Constructor<AizMinibossBarrelShotChild> ctor =
                    AizMinibossBarrelShotChild.class.getDeclaredConstructor(
                            AbstractBossInstance.class,
                            AizMinibossFlameBarrelChild.class,
                            int.class,
                            int.class,
                            modeClass);
            ctor.setAccessible(true);
            return ctor.newInstance(boss, barrel, x, y, mode);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct AIZ miniboss barrel shot", e);
        }
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

    private static Object readObjectField(Object target, String fieldName) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static AizMinibossFlameBarrelChild barrelByIndex(
            List<AizMinibossFlameBarrelChild> barrels,
            int barrelIndex) {
        List<AizMinibossFlameBarrelChild> matches = barrels.stream()
                .filter(barrel -> readIntField(barrel, "barrelIndex") == barrelIndex)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one AIZ miniboss barrel index " + barrelIndex);
        return matches.getFirst();
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
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
