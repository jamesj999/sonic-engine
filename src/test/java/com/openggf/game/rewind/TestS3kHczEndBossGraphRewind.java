package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossBlade;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeImpactExplosion;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeSplash;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeWaterChute;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossRobotnikShip;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossTurbine;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossWaterColumn;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestS3kHczEndBossGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.HCZ_END_BOSS, 0, 0, false, 10);
    private static final SonicConfigurationService DEFAULT_CONFIGURATION =
            createDefaultConfiguration();

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void hczEndBossGraphRestoresWithoutDropsDoublesOrStaleReferences() throws Exception {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        HczGraph before = HczGraph.spawnRepresentativeFamily(objectManager);

        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        HczGraph replacement = HczGraph.spawnReplacementFamily(objectManager);
        assertNotEquals(beforeIds.get("ship"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacement.ship()));

        rewindRegistry.restore(snapshot);

        HczGraph restored = HczGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate any HCZ end-boss graph object");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured dynamic object identities");

        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(before, restored);
        assertEquals(before.dependentState(), restored.dependentState(),
                "nested HCZ children must retain their captured scalar state");
    }

    @Test
    void missingRequiredObjectReferencesStillFailWhenTargetHasNoRewindIdentity() {
        Harness externalHarness = Harness.createWithBoss();
        HczGraph external = HczGraph.spawnRepresentativeFamily(externalHarness.objectManager());
        RequiredReferenceFixture fixture = new RequiredReferenceFixture(external.waterColumn());
        RewindCaptureContext context =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertEquals(true, thrown.getMessage().contains("no registered id for object reference"),
                "non-null required object references must still require registered rewind identities");
    }

    private static void assertAllReferencesPointAtRestoredGraph(HczGraph graph) throws Exception {
        assertSame(graph.boss(), readObjectField(graph.ship(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.turbine(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.blade0(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.blade1(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.blade2(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.splash(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.impact(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.chute0(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.chute1(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.chute2(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.chute3(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.chute4(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.waterColumn(), "boss"));
        assertSame(graph.turbine(), readObjectField(graph.waterColumn(), "turbine"));
        assertSame(graph.waterColumn(), readObjectField(graph.turbine(), "waterColumn"),
                "water column restore must relink the turbine back-reference");

        for (ObjectInstance child : graph.dynamicChildren()) {
            assertSame(graph.boss(), readObjectField(child, "parent"),
                    child.getClass().getSimpleName() + " must inherit the restored boss parent");
        }
    }

    private static void assertRestoredObjectsAreFresh(HczGraph before, HczGraph restored) {
        assertNotSame(before.ship(), restored.ship());
        assertNotSame(before.turbine(), restored.turbine());
        assertNotSame(before.blade0(), restored.blade0());
        assertNotSame(before.blade1(), restored.blade1());
        assertNotSame(before.blade2(), restored.blade2());
        assertNotSame(before.splash(), restored.splash());
        assertNotSame(before.impact(), restored.impact());
        assertNotSame(before.chute0(), restored.chute0());
        assertNotSame(before.chute1(), restored.chute1());
        assertNotSame(before.chute2(), restored.chute2());
        assertNotSame(before.chute3(), restored.chute3());
        assertNotSame(before.chute4(), restored.chute4());
        assertNotSame(before.waterColumn(), restored.waterColumn());
        assertEquals(before.dependentChildren().size(), restored.dependentChildren().size());
        for (int i = 0; i < before.dependentChildren().size(); i++) {
            assertNotSame(before.dependentChildren().get(i), restored.dependentChildren().get(i),
                    "nested HCZ child must be recreated, not retained in place");
        }
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithBoss() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(BOSS_SPAWN),
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

    private record HczGraph(
            HczEndBossInstance boss,
            HczEndBossRobotnikShip ship,
            HczEndBossTurbine turbine,
            HczEndBossBlade blade0,
            HczEndBossBlade blade1,
            HczEndBossBlade blade2,
            HczEndBossBladeSplash splash,
            HczEndBossBladeImpactExplosion impact,
            HczEndBossBladeWaterChute chute0,
            HczEndBossBladeWaterChute chute1,
            HczEndBossBladeWaterChute chute2,
             HczEndBossBladeWaterChute chute3,
             HczEndBossBladeWaterChute chute4,
             HczEndBossWaterColumn waterColumn,
             List<ObjectInstance> dependentChildren) {

        static HczGraph spawnRepresentativeFamily(ObjectManager objectManager) {
            HczEndBossInstance boss = only(objectManager, HczEndBossInstance.class);
            invokeNoArg(boss, "spawnChildren");
            HczEndBossTurbine turbine = only(objectManager, HczEndBossTurbine.class);
            spawnWaterColumnViaTurbine(boss, turbine);
            HczEndBossWaterColumn column = only(objectManager, HczEndBossWaterColumn.class);
            column.update(0, null);
            invokeNoArg(column, "onSpinupComplete");
            only(objectManager, HczEndBossRobotnikShip.class).update(0, null);
            HczEndBossBlade blade0 = bladeBySubtype(objectManager, 0);
            setIntField(blade0, "currentX", 0x4140);
            invokeNoArg(blade0, "spawnSplash");
            invokeNoArg(blade0, "spawnWaterChute");
            objectManager.createDynamicObject(
                    () -> new HczEndBossBladeImpactExplosion(boss, 0x4140, 0x07F7));
            return fromLiveObjects(objectManager);
        }

        static HczGraph spawnReplacementFamily(ObjectManager objectManager) {
            return spawnRepresentativeFamily(objectManager);
        }

        static HczGraph fromLiveObjects(ObjectManager objectManager) {
            List<HczEndBossBlade> blades = liveObjects(objectManager, HczEndBossBlade.class);
            List<HczEndBossBladeWaterChute> chutes =
                    liveObjects(objectManager, HczEndBossBladeWaterChute.class).stream()
                            .sorted(Comparator.comparingInt(chute -> readIntField(chute, "slotIndex")))
                            .toList();
            assertEquals(3, blades.size(), "expected exactly three live HCZ end-boss blades");
            assertEquals(5, chutes.size(), "expected exactly five live HCZ blade water chutes");
            List<ObjectInstance> dependentChildren = TestS3kHczEndBossGraphRewind.dependentChildren(objectManager);
            return new HczGraph(
                    only(objectManager, HczEndBossInstance.class),
                    only(objectManager, HczEndBossRobotnikShip.class),
                    only(objectManager, HczEndBossTurbine.class),
                    bladeBySubtype(blades, 0),
                    bladeBySubtype(blades, 2),
                    bladeBySubtype(blades, 4),
                    only(objectManager, HczEndBossBladeSplash.class),
                    only(objectManager, HczEndBossBladeImpactExplosion.class),
                    chutes.get(0),
                    chutes.get(1),
                    chutes.get(2),
                    chutes.get(3),
                    chutes.get(4),
                    only(objectManager, HczEndBossWaterColumn.class),
                    dependentChildren);
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
            ids.put("ship", table.idFor(ship));
            ids.put("turbine", table.idFor(turbine));
            ids.put("blade0", table.idFor(blade0));
            ids.put("blade1", table.idFor(blade1));
            ids.put("blade2", table.idFor(blade2));
            ids.put("splash", table.idFor(splash));
            ids.put("impact", table.idFor(impact));
            ids.put("chute0", table.idFor(chute0));
            ids.put("chute1", table.idFor(chute1));
            ids.put("chute2", table.idFor(chute2));
            ids.put("chute3", table.idFor(chute3));
            ids.put("chute4", table.idFor(chute4));
            ids.put("waterColumn", table.idFor(waterColumn));
            Map<String, Integer> ordinals = new HashMap<>();
            for (ObjectInstance child : dependentChildren) {
                String key = child.getClass().getName() + "#"
                        + ordinals.merge(child.getClass().getName(), 1, Integer::sum);
                ids.put(key, table.idFor(child));
            }
            return ids;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            for (ObjectInstance object : dynamicChildren()) {
                objectManager.removeDynamicObject(object);
            }
        }

        private List<ObjectInstance> objects() {
            List<ObjectInstance> objects = new ArrayList<>(List.of(
                    boss, ship, turbine, blade0, blade1, blade2, splash, impact,
                    chute0, chute1, chute2, chute3, chute4, waterColumn));
            objects.addAll(dependentChildren);
            return objects;
        }

        private List<ObjectInstance> dynamicChildren() {
            List<ObjectInstance> children = new ArrayList<>(List.of(
                    ship, turbine, blade0, blade1, blade2, splash, impact,
                    chute0, chute1, chute2, chute3, chute4, waterColumn));
            children.addAll(dependentChildren);
            return children;
        }

        Map<String, List<Integer>> dependentState() {
            Map<String, List<Integer>> state = new LinkedHashMap<>();
            state.put("HczEndBossWaterSurfaceChild.xOffset", fieldValues("HczEndBossWaterSurfaceChild", "xOffset"));
            state.put("HczEndBossBubbleParticle.waitTimer", fieldValues("HczEndBossBubbleParticle", "waitTimer"));
            return state;
        }

        private List<Integer> fieldValues(String simpleName, String fieldName) {
            return dependentChildren.stream()
                    .filter(child -> child.getClass().getSimpleName().equals(simpleName))
                    .map(child -> readIntField(child, fieldName))
                    .toList();
        }
    }

    private static List<ObjectInstance> dependentChildren(ObjectManager objectManager) {
        Map<String, Integer> expected = Map.of(
                "HczEndBossLowerHousing", 1,
                "HczEndBossRobotnikHead", 1,
                "HczEndBossWaterSurfaceChild", 2,
                "HczEndBossBubbleParticle", 20,
                "HczEndBossWaterSprayChild", 1);
        List<ObjectInstance> children = objectManager.getActiveObjects().stream()
                .filter(object -> expected.containsKey(object.getClass().getSimpleName()) && !object.isDestroyed())
                .toList();
        Map<String, Long> counts = new HashMap<>();
        for (ObjectInstance child : children) {
            counts.merge(child.getClass().getSimpleName(), 1L, Long::sum);
        }
        expected.forEach((type, count) -> assertEquals(count.longValue(), counts.getOrDefault(type, 0L),
                "expected exact managed HCZ child-slot count for " + type));
        return children;
    }

    private static void spawnWaterColumnViaTurbine(HczEndBossInstance boss, HczEndBossTurbine turbine) {
        boss.setPropellerActive(true);
        for (int frame = 1; frame <= 256 && readObjectField(turbine, "waterColumn") == null; frame++) {
            turbine.update(frame, null);
        }
        assertNotNull(readObjectField(turbine, "waterColumn"),
                "production turbine ACTIVE path must spawn and retain its water column");
    }

    private static HczEndBossBlade bladeBySubtype(ObjectManager objectManager, int subtype) {
        return bladeBySubtype(liveObjects(objectManager, HczEndBossBlade.class), subtype);
    }

    private static HczEndBossBlade bladeBySubtype(List<HczEndBossBlade> blades, int subtype) {
        List<HczEndBossBlade> matches = blades.stream()
                .filter(blade -> readIntField(blade, "subtype") == subtype)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one HCZ blade subtype " + subtype);
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

    private static Object readObjectField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
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

    private static void invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + methodName + " on " + target.getClass(), e);
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

    private static SonicConfigurationService createDefaultConfiguration() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(
                TestSessionOutputPaths.diagnostics("rewind")
                        .resolve("rewind-hcz-endboss-graph-config"));
        config.resetToDefaults();
        return config;
    }
}
