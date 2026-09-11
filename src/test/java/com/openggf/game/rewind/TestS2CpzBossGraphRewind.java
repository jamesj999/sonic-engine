package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.bosses.CPZBossContainer;
import com.openggf.game.sonic2.objects.bosses.CPZBossContainerExtend;
import com.openggf.game.sonic2.objects.bosses.CPZBossContainerFloor;
import com.openggf.game.sonic2.objects.bosses.CPZBossDripper;
import com.openggf.game.sonic2.objects.bosses.CPZBossFlame;
import com.openggf.game.sonic2.objects.bosses.CPZBossGunk;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipe;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipePump;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipeSegment;
import com.openggf.game.sonic2.objects.bosses.CPZBossPump;
import com.openggf.game.sonic2.objects.bosses.CPZBossRobotnik;
import com.openggf.game.sonic2.objects.bosses.CPZBossSmokePuff;
import com.openggf.game.sonic2.objects.bosses.Sonic2CPZBossInstance;
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

class TestS2CpzBossGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0100, Sonic2ObjectIds.CPZ_BOSS, 0, 0, false, 10);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cpzBossGraphRestoresWithoutDropsDoublesOrStaleReferences() throws Exception {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        CpzGraph before = CpzGraph.withConstructionChildrenPlusSecondaries(objectManager);

        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        CpzGraph replacement = CpzGraph.spawnReplacementChildren(objectManager);
        assertNotEquals(beforeIds.get("container"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacement.container()));

        rewindRegistry.restore(snapshot);

        CpzGraph restored = CpzGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate any CPZ boss graph object");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured dynamic object identities");

        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(before, restored);
    }

    @Test
    void cpzBossParentUsesGenericRecreateWithoutExplicitS2Codec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2CPZBossInstance.class),
                "Sonic2CPZBossInstance must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic2CPZBossInstance.class.getName()),
                "Sonic2CPZBossInstance must not keep an explicit S2 dynamic codec");
    }

    @Test
    void missingRequiredObjectReferencesStillFailWhenTargetHasNoRewindIdentity() {
        Harness externalHarness = Harness.createWithBoss();
        CPZBossFlame externalFlame = only(externalHarness.objectManager(), CPZBossFlame.class);
        RequiredReferenceFixture fixture = new RequiredReferenceFixture(externalFlame);
        RewindCaptureContext context =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertEquals(true, thrown.getMessage().contains("no registered id for object reference"),
                "non-null required object references must still require registered rewind identities");
    }

    @Test
    void smokePuffRestoresWithLiveBossReferenceAndExactScalarState() throws Exception {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        Sonic2CPZBossInstance boss = only(objectManager, Sonic2CPZBossInstance.class);
        CPZBossSmokePuff beforeSmoke = objectManager.createDynamicObject(
                () -> new CPZBossSmokePuff(spawn(0x2B58, 0x04B4, 22), boss));
        setIntField(beforeSmoke, "mappingFrame", 2);
        setIntField(beforeSmoke, "animFrameDuration", 3);

        ObjectRefId beforeId = objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(beforeSmoke);
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(beforeSmoke);
        CPZBossSmokePuff replacement = objectManager.createDynamicObject(
                () -> new CPZBossSmokePuff(spawn(0x2C00, 0x04C0, 23), boss));
        assertNotEquals(beforeId, objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacement));

        rewindRegistry.restore(snapshot);

        Sonic2CPZBossInstance restoredBoss = only(objectManager, Sonic2CPZBossInstance.class);
        CPZBossSmokePuff restoredSmoke = only(objectManager, CPZBossSmokePuff.class);
        assertNotSame(beforeSmoke, restoredSmoke);
        assertSame(restoredBoss, readObjectField(restoredSmoke, "mainBoss"));
        assertNotSame(boss, readObjectField(restoredSmoke, "mainBoss"));
        assertEquals(beforeId, objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(restoredSmoke));
        assertEquals(0x2B58, restoredSmoke.getX());
        assertEquals(0x04B4, restoredSmoke.getY());
        assertEquals(2, readIntField(restoredSmoke, "mappingFrame"));
        assertEquals(3, readIntField(restoredSmoke, "animFrameDuration"));
    }

    private static void assertAllReferencesPointAtRestoredGraph(CpzGraph graph) throws Exception {
        assertSame(graph.boss(), readObjectField(graph.container(), "mainBoss"));
        assertSame(graph.boss(), readObjectField(graph.flame(), "mainBoss"));
        assertSame(graph.boss(), readObjectField(graph.gunk(), "mainBoss"));
        assertSame(graph.boss(), readObjectField(graph.pipe(), "mainBoss"));
        assertSame(graph.boss(), readObjectField(graph.pump(), "mainBoss"));
        assertSame(graph.boss(), readObjectField(graph.robotnik(), "mainBoss"));
        assertSame(graph.boss(), readObjectField(graph.containerExtend(), "mainBoss"));
        assertSame(graph.container(), readObjectField(graph.containerExtend(), "container"));
        assertSame(graph.boss(), readObjectField(graph.containerFloor(), "mainBoss"));
        assertSame(graph.container(), readObjectField(graph.containerFloor(), "container"));
        assertSame(graph.boss(), readObjectField(graph.dripper(), "mainBoss"));
        assertSame(graph.pipe(), readObjectField(graph.dripper(), "parentPipe"));
        assertSame(graph.boss(), readObjectField(graph.pipePump(), "mainBoss"));
        assertSame(graph.pipe(), readObjectField(graph.pipePump(), "parentPipe"));
        assertSame(graph.boss(), readObjectField(graph.pipeSegment(), "mainBoss"));
        assertSame(graph.pipe(), readObjectField(graph.pipeSegment(), "parentPipe"));
    }

    private static void assertRestoredObjectsAreFresh(CpzGraph before, CpzGraph restored) {
        assertNotSame(before.container(), restored.container());
        assertNotSame(before.flame(), restored.flame());
        assertNotSame(before.gunk(), restored.gunk());
        assertNotSame(before.pipe(), restored.pipe());
        assertNotSame(before.pump(), restored.pump());
        assertNotSame(before.robotnik(), restored.robotnik());
        assertNotSame(before.containerExtend(), restored.containerExtend());
        assertNotSame(before.containerFloor(), restored.containerFloor());
        assertNotSame(before.dripper(), restored.dripper());
        assertNotSame(before.pipePump(), restored.pipePump());
        assertNotSame(before.pipeSegment(), restored.pipeSegment());
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithBoss() {
            return create(List.of(BOSS_SPAWN));
        }

        private static Harness create(List<ObjectSpawn> spawns) {
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
            // ROM Obj5D_Init is the boss's routine 0, executed from its own slot,
            // and it is what allocates the five child components
            // (docs/s2disasm/s2.asm:61628-61710). Drive that one execution here so
            // the graph under test is the one production builds.
            objectManager.getActiveObjects().stream()
                    .filter(o -> o instanceof Sonic2CPZBossInstance)
                    .forEach(o -> o.update(0, null));
            return new Harness(objectManager);
        }
    }

    private static final class RequiredReferenceFixture {
        ObjectInstance object;

        private RequiredReferenceFixture(ObjectInstance object) {
            this.object = object;
        }
    }

    private record CpzGraph(
            Sonic2CPZBossInstance boss,
            CPZBossContainer container,
            CPZBossFlame flame,
            CPZBossGunk gunk,
            CPZBossPipe pipe,
            CPZBossPump pump,
            CPZBossRobotnik robotnik,
            CPZBossContainerExtend containerExtend,
            CPZBossContainerFloor containerFloor,
            CPZBossDripper dripper,
            CPZBossPipePump pipePump,
            CPZBossPipeSegment pipeSegment) {

        static CpzGraph withConstructionChildrenPlusSecondaries(ObjectManager objectManager) {
            Sonic2CPZBossInstance boss = only(objectManager, Sonic2CPZBossInstance.class);
            CPZBossContainer container = only(objectManager, CPZBossContainer.class);
            CPZBossFlame flame = only(objectManager, CPZBossFlame.class);
            CPZBossGunk gunk = objectManager.createDynamicObject(
                    () -> new CPZBossGunk(spawn(0x2B80, 0x04B0, 13), boss, false));
            CPZBossPipe pipe = only(objectManager, CPZBossPipe.class);
            CPZBossPump pump = only(objectManager, CPZBossPump.class);
            CPZBossRobotnik robotnik = only(objectManager, CPZBossRobotnik.class);
            CPZBossContainerExtend extend = objectManager.createDynamicObject(
                    () -> new CPZBossContainerExtend(spawn(0x2B80, 0x04B0, 17), boss, container));
            CPZBossContainerFloor floor = objectManager.createDynamicObject(
                    () -> new CPZBossContainerFloor(spawn(0x2B80, 0x04B0, 18), boss, container, false));
            CPZBossDripper dripper = objectManager.createDynamicObject(
                    () -> new CPZBossDripper(spawn(0x2B80, 0x04D8, 19), boss, pipe));
            CPZBossPipePump pipePump = objectManager.createDynamicObject(
                    () -> new CPZBossPipePump(spawn(0x2B80, 0x04D8, 20), boss, pipe));
            CPZBossPipeSegment segment = objectManager.createDynamicObject(
                    () -> new CPZBossPipeSegment(spawn(0x2B80, 0x04D8, 21), boss, pipe, 0));
            return new CpzGraph(boss, container, flame, gunk, pipe, pump, robotnik,
                    extend, floor, dripper, pipePump, segment);
        }

        static CpzGraph spawnReplacementChildren(ObjectManager objectManager) {
            Sonic2CPZBossInstance boss = only(objectManager, Sonic2CPZBossInstance.class);
            CPZBossContainer container = objectManager.createDynamicObject(
                    () -> new CPZBossContainer(spawn(0x2B80, 0x04B0, 11), boss));
            CPZBossFlame flame = objectManager.createDynamicObject(
                    () -> new CPZBossFlame(spawn(0x2B80, 0x04B0, 12), boss));
            CPZBossGunk gunk = objectManager.createDynamicObject(
                    () -> new CPZBossGunk(spawn(0x2B80, 0x04B0, 13), boss, false));
            CPZBossPipe pipe = objectManager.createDynamicObject(
                    () -> new CPZBossPipe(spawn(0x2B80, 0x04D8, 14), boss));
            CPZBossPump pump = objectManager.createDynamicObject(
                    () -> new CPZBossPump(spawn(0x2B80, 0x04B0, 15), boss));
            CPZBossRobotnik robotnik = objectManager.createDynamicObject(
                    () -> new CPZBossRobotnik(spawn(0x2B80, 0x04B0, 16), boss));
            CPZBossContainerExtend extend = objectManager.createDynamicObject(
                    () -> new CPZBossContainerExtend(spawn(0x2B80, 0x04B0, 17), boss, container));
            CPZBossContainerFloor floor = objectManager.createDynamicObject(
                    () -> new CPZBossContainerFloor(spawn(0x2B80, 0x04B0, 18), boss, container, false));
            CPZBossDripper dripper = objectManager.createDynamicObject(
                    () -> new CPZBossDripper(spawn(0x2B80, 0x04D8, 19), boss, pipe));
            CPZBossPipePump pipePump = objectManager.createDynamicObject(
                    () -> new CPZBossPipePump(spawn(0x2B80, 0x04D8, 20), boss, pipe));
            CPZBossPipeSegment segment = objectManager.createDynamicObject(
                    () -> new CPZBossPipeSegment(spawn(0x2B80, 0x04D8, 21), boss, pipe, 0));
            return new CpzGraph(boss, container, flame, gunk, pipe, pump, robotnik,
                    extend, floor, dripper, pipePump, segment);
        }

        static CpzGraph fromLiveObjects(ObjectManager objectManager) {
            return new CpzGraph(
                    only(objectManager, Sonic2CPZBossInstance.class),
                    only(objectManager, CPZBossContainer.class),
                    only(objectManager, CPZBossFlame.class),
                    only(objectManager, CPZBossGunk.class),
                    only(objectManager, CPZBossPipe.class),
                    only(objectManager, CPZBossPump.class),
                    only(objectManager, CPZBossRobotnik.class),
                    only(objectManager, CPZBossContainerExtend.class),
                    only(objectManager, CPZBossContainerFloor.class),
                    only(objectManager, CPZBossDripper.class),
                    only(objectManager, CPZBossPipePump.class),
                    only(objectManager, CPZBossPipeSegment.class));
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
            ids.put("container", table.idFor(container));
            ids.put("flame", table.idFor(flame));
            ids.put("gunk", table.idFor(gunk));
            ids.put("pipe", table.idFor(pipe));
            ids.put("pump", table.idFor(pump));
            ids.put("robotnik", table.idFor(robotnik));
            ids.put("containerExtend", table.idFor(containerExtend));
            ids.put("containerFloor", table.idFor(containerFloor));
            ids.put("dripper", table.idFor(dripper));
            ids.put("pipePump", table.idFor(pipePump));
            ids.put("pipeSegment", table.idFor(pipeSegment));
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
            return List.of(boss, container, flame, gunk, pipe, pump, robotnik,
                    containerExtend, containerFloor, dripper, pipePump, pipeSegment);
        }
    }

    private static ObjectSpawn spawn(int x, int y, int layoutIndex) {
        return new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, 0, false, layoutIndex);
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static Object readObjectField(Object target, String fieldName) throws Exception {
        Field field = findField(target, fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int readIntField(Object target, String fieldName) throws Exception {
        Field field = findField(target, fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        Field field = findField(target, fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static Field findField(Object target, String fieldName) throws NoSuchFieldException {
        Class<?> type = target.getClass();
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
