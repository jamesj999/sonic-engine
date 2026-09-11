package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.BossChildComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class TestS2WfzBossGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0400, Sonic2ObjectIds.WFZ_BOSS, 0, 0, false, 10);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void wfzBossGraphRestoresWithoutDropsDoublesOrStaleReferences() throws Exception {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        WfzGraph before = WfzGraph.spawnRepresentativeFamily(objectManager);

        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        WfzGraph replacement = WfzGraph.spawnReplacementFamily(objectManager);
        assertNotEquals(beforeIds.get("leftWall"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacement.leftWall()));

        rewindRegistry.restore(snapshot);

        WfzGraph restored = WfzGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate any WFZ boss graph object");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured WFZ child dynamic identities");

        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(before, restored);
    }

    @Test
    void wfzBossParentUsesGenericRecreateWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2WFZBossInstance.class),
                "Sonic2WFZBossInstance must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic2WFZBossInstance.class.getName()),
                "Sonic2WFZBossInstance must not keep an explicit S2 dynamic rewind codec");
    }

    @Test
    void laserWallVisibilityPhaseRoundTripsThroughGenericRewind() {
        Harness harness = Harness.createWithBoss();
        WfzGraph graph = WfzGraph.spawnRepresentativeFamily(harness.objectManager());
        Sonic2WFZBossInstance.WFZLaserWall wall = graph.leftWall();

        wall.update(1, null);
        boolean capturedPhase = wall.isVisibleThisFrameForTest();
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(harness.objectManager().rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        wall.update(2, null);
        assertNotEquals(capturedPhase, wall.isVisibleThisFrameForTest(),
                "precondition: wall must advance away from the captured display phase");

        rewindRegistry.restore(snapshot);

        Sonic2WFZBossInstance.WFZLaserWall restored =
                WfzGraph.fromLiveObjects(harness.objectManager()).leftWall();
        assertEquals(capturedPhase, restored.isVisibleThisFrameForTest(),
                "restore must recover the captured wall display phase");
        restored.update(2, null);
        assertNotEquals(capturedPhase, restored.isVisibleThisFrameForTest(),
                "the restored phase must advance exactly once on the next frame");
    }

    @Test
    void missingRequiredObjectReferencesStillFailWhenTargetHasNoRewindIdentity() {
        Harness externalHarness = Harness.createWithBoss();
        WfzGraph external = WfzGraph.spawnRepresentativeFamily(externalHarness.objectManager());
        RequiredReferenceFixture fixture = new RequiredReferenceFixture(external.platform());
        RewindCaptureContext context =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertEquals(true, thrown.getMessage().contains("no registered id for object reference"),
                "non-null required object references must still require registered rewind identities");
    }

    private static void assertAllReferencesPointAtRestoredGraph(WfzGraph graph) throws Exception {
        assertSame(graph.boss(), readObjectField(graph.leftWall(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.rightWall(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.platform(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.hurt(), "parent"));
        assertSame(graph.leftWall(), readObjectField(graph.boss(), "leftWall"),
                "restored boss leftWall must relink to the restored wall on the left side");
        assertSame(graph.rightWall(), readObjectField(graph.boss(), "rightWall"),
                "restored boss rightWall must relink to the restored wall on the right side");
        assertSame(graph.hurt(), readObjectField(graph.platform(), "hurtChild"),
                "restored platform must point at its restored hurt child");
        assertSame(graph.platform(), readObjectField(graph.hurt(), "platformParent"),
                "restored hurt child must point at its restored platform");
        assertSame(graph.boss(), readObjectField(graph.platformReleaser(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.laserShooter(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.laser(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.robotnik(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.robotnikPlatform(), "parent"));
        assertSame(graph.platformReleaser(), readObjectField(graph.boss(), "platformReleaser"),
                "restored boss platformReleaser must relink to the restored child");
        assertSame(graph.laserShooter(), readObjectField(graph.boss(), "laserShooter"),
                "restored boss laserShooter must relink to the restored child");
        assertSame(graph.laser(), readObjectField(graph.boss(), "laser"),
                "restored boss laser must relink to the restored child");
        assertSame(graph.robotnik(), readObjectField(graph.boss(), "robotnik"),
                "restored boss robotnik must relink to the restored child");
        assertSame(graph.robotnikPlatform(), readObjectField(graph.robotnik(), "robotnikPlatform"),
                "restored Robotnik must relink to the restored platform child");
        assertSame(graph.robotnik(), readObjectField(graph.robotnikPlatform(), "robotnikParent"),
                "restored Robotnik platform must point at the restored Robotnik");

        List<BossChildComponent> components = graph.boss().getChildComponents();
        assertEquals(1, countIdentity(components, graph.leftWall()));
        assertEquals(1, countIdentity(components, graph.rightWall()));
        assertEquals(1, countIdentity(components, graph.platform()));
        assertEquals(1, countIdentity(components, graph.hurt()));
        assertEquals(1, countIdentity(components, graph.platformReleaser()));
        assertEquals(1, countIdentity(components, graph.laserShooter()));
        assertEquals(1, countIdentity(components, graph.laser()));
        assertEquals(1, countIdentity(components, graph.robotnik()));
        assertEquals(1, countIdentity(components, graph.robotnikPlatform()));
    }

    private static void assertRestoredObjectsAreFresh(WfzGraph before, WfzGraph restored) {
        assertNotSame(before.boss(), restored.boss());
        assertNotSame(before.leftWall(), restored.leftWall());
        assertNotSame(before.rightWall(), restored.rightWall());
        assertNotSame(before.platform(), restored.platform());
        assertNotSame(before.hurt(), restored.hurt());
        assertNotSame(before.platformReleaser(), restored.platformReleaser());
        assertNotSame(before.laserShooter(), restored.laserShooter());
        assertNotSame(before.laser(), restored.laser());
        assertNotSame(before.robotnik(), restored.robotnik());
        assertNotSame(before.robotnikPlatform(), restored.robotnikPlatform());
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithBoss() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(BOSS_SPAWN),
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

    private static final class RequiredReferenceFixture {
        ObjectInstance object;

        private RequiredReferenceFixture(ObjectInstance object) {
            this.object = object;
        }
    }

    private record WfzGraph(
            Sonic2WFZBossInstance boss,
            Sonic2WFZBossInstance.WFZLaserWall leftWall,
            Sonic2WFZBossInstance.WFZLaserWall rightWall,
            Sonic2WFZBossInstance.WFZFloatingPlatform platform,
            Sonic2WFZBossInstance.WFZPlatformHurt hurt,
            Sonic2WFZBossInstance.WFZPlatformReleaser platformReleaser,
            Sonic2WFZBossInstance.WFZLaserShooter laserShooter,
            Sonic2WFZBossInstance.WFZLaser laser,
            Sonic2WFZBossInstance.WFZRobotnik robotnik,
            Sonic2WFZBossInstance.WFZRobotnikPlatform robotnikPlatform) {

        static WfzGraph spawnRepresentativeFamily(ObjectManager objectManager) {
            Sonic2WFZBossInstance boss = only(objectManager, Sonic2WFZBossInstance.class);
            return spawnFamily(objectManager, boss, 0x0120, 0x0460);
        }

        static WfzGraph spawnReplacementFamily(ObjectManager objectManager) {
            Sonic2WFZBossInstance boss = only(objectManager, Sonic2WFZBossInstance.class);
            return spawnFamily(objectManager, boss, 0x0220, 0x0480);
        }

        private static WfzGraph spawnFamily(
                ObjectManager objectManager,
                Sonic2WFZBossInstance boss,
                int platformX,
                int platformY) {
            int wallY = boss.getY() + 0x60;
            Sonic2WFZBossInstance.WFZLaserWall leftWall = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZLaserWall(boss, boss.getSpawnX() - 0x88, wallY));
            Sonic2WFZBossInstance.WFZLaserWall rightWall = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZLaserWall(boss, boss.getSpawnX() + 0x88, wallY));
            Sonic2WFZBossInstance.WFZFloatingPlatform platform = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZFloatingPlatform(boss, platformX, platformY));
            Sonic2WFZBossInstance.WFZPlatformHurt hurt = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZPlatformHurt(boss, platform));
            Sonic2WFZBossInstance.WFZPlatformReleaser platformReleaser = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZPlatformReleaser(boss));
            Sonic2WFZBossInstance.WFZLaserShooter laserShooter = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZLaserShooter(boss));
            Sonic2WFZBossInstance.WFZLaser laser = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZLaser(boss));
            Sonic2WFZBossInstance.WFZRobotnik robotnik = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZRobotnik(boss));
            Sonic2WFZBossInstance.WFZRobotnikPlatform robotnikPlatform = objectManager.createDynamicObject(
                    () -> new Sonic2WFZBossInstance.WFZRobotnikPlatform(boss, robotnik));

            try {
                writeObjectField(boss, "leftWall", leftWall);
                writeObjectField(boss, "rightWall", rightWall);
                writeObjectField(platform, "hurtChild", hurt);
                writeObjectField(boss, "platformReleaser", platformReleaser);
                writeObjectField(boss, "laserShooter", laserShooter);
                writeObjectField(boss, "laser", laser);
                writeObjectField(boss, "robotnik", robotnik);
                writeObjectField(robotnik, "robotnikPlatform", robotnikPlatform);
            } catch (Exception e) {
                throw new AssertionError("Unable to wire WFZ graph fixture", e);
            }
            addChildComponentOnce(boss, leftWall);
            addChildComponentOnce(boss, rightWall);
            addChildComponentOnce(boss, platform);
            addChildComponentOnce(boss, hurt);
            addChildComponentOnce(boss, platformReleaser);
            addChildComponentOnce(boss, laserShooter);
            addChildComponentOnce(boss, laser);
            addChildComponentOnce(boss, robotnik);
            addChildComponentOnce(boss, robotnikPlatform);
            return new WfzGraph(
                    boss,
                    leftWall,
                    rightWall,
                    platform,
                    hurt,
                    platformReleaser,
                    laserShooter,
                    laser,
                    robotnik,
                    robotnikPlatform);
        }

        static WfzGraph fromLiveObjects(ObjectManager objectManager) {
            List<Sonic2WFZBossInstance.WFZLaserWall> walls =
                    liveObjects(objectManager, Sonic2WFZBossInstance.WFZLaserWall.class);
            assertEquals(2, walls.size(), "expected exactly two live WFZ laser walls");
            Sonic2WFZBossInstance boss = only(objectManager, Sonic2WFZBossInstance.class);
            return new WfzGraph(
                    boss,
                    wallBySide(walls, boss, true),
                    wallBySide(walls, boss, false),
                    only(objectManager, Sonic2WFZBossInstance.WFZFloatingPlatform.class),
                    only(objectManager, Sonic2WFZBossInstance.WFZPlatformHurt.class),
                    only(objectManager, Sonic2WFZBossInstance.WFZPlatformReleaser.class),
                    only(objectManager, Sonic2WFZBossInstance.WFZLaserShooter.class),
                    only(objectManager, Sonic2WFZBossInstance.WFZLaser.class),
                    only(objectManager, Sonic2WFZBossInstance.WFZRobotnik.class),
                    only(objectManager, Sonic2WFZBossInstance.WFZRobotnikPlatform.class));
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
            ids.put("leftWall", table.idFor(leftWall));
            ids.put("rightWall", table.idFor(rightWall));
            ids.put("platform", table.idFor(platform));
            ids.put("hurt", table.idFor(hurt));
            ids.put("platformReleaser", table.idFor(platformReleaser));
            ids.put("laserShooter", table.idFor(laserShooter));
            ids.put("laser", table.idFor(laser));
            ids.put("robotnik", table.idFor(robotnik));
            ids.put("robotnikPlatform", table.idFor(robotnikPlatform));
            return ids;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            objectManager.removeDynamicObject(leftWall);
            objectManager.removeDynamicObject(rightWall);
            objectManager.removeDynamicObject(platform);
            objectManager.removeDynamicObject(hurt);
            objectManager.removeDynamicObject(platformReleaser);
            objectManager.removeDynamicObject(laserShooter);
            objectManager.removeDynamicObject(laser);
            objectManager.removeDynamicObject(robotnik);
            objectManager.removeDynamicObject(robotnikPlatform);
        }

        private List<ObjectInstance> objects() {
            return List.of(
                    boss,
                    leftWall,
                    rightWall,
                    platform,
                    hurt,
                    platformReleaser,
                    laserShooter,
                    laser,
                    robotnik,
                    robotnikPlatform);
        }
    }

    private static Sonic2WFZBossInstance.WFZLaserWall wallBySide(
            List<Sonic2WFZBossInstance.WFZLaserWall> walls,
            Sonic2WFZBossInstance boss,
            boolean left) {
        return walls.stream()
                .filter(wall -> left ? wall.getX() < boss.getSpawnX() : wall.getX() > boss.getSpawnX())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + (left ? "left" : "right") + " WFZ wall"));
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

    private static void addChildComponentOnce(Sonic2WFZBossInstance boss, BossChildComponent component) {
        if (!boss.getChildComponents().contains(component)) {
            boss.getChildComponents().add(component);
        }
    }

    private static int countIdentity(List<BossChildComponent> components, BossChildComponent target) {
        int count = 0;
        for (BossChildComponent component : components) {
            if (component == target) {
                count++;
            }
        }
        return count;
    }

    private static Object readObjectField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void writeObjectField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> startType, String fieldName) throws NoSuchFieldException {
        Class<?> type = startType;
        while (type != null) {
            try {
                return type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
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
