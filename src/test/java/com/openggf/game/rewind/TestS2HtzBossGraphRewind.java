package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.bosses.HTZBossFlamethrower;
import com.openggf.game.sonic2.objects.bosses.HTZBossLavaBall;
import com.openggf.game.sonic2.objects.bosses.HTZBossSmokeParticle;
import com.openggf.game.sonic2.objects.bosses.Sonic2HTZBossInstance;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2HtzBossGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0580, Sonic2ObjectIds.HTZ_BOSS, 0, 0, false, 10);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void htzBossHazardGraphRestoresWithoutDropsDoublesOrStaleReferences() throws Exception {
        Harness harness = Harness.create(List.of(BOSS_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        HtzGraph before = HtzGraph.spawnRepresentativeFamily(objectManager);
        before.writeDistinctScalarState();

        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        FlamethrowerScalars beforeFlame = FlamethrowerScalars.read(before.flamethrower());
        LavaBallScalars beforeLeft = LavaBallScalars.read(before.leftBall());
        LavaBallScalars beforeRight = LavaBallScalars.read(before.rightBall());
        SmokeScalars beforeSmoke = SmokeScalars.read(before.smoke());

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        HtzGraph replacement = HtzGraph.spawnReplacementFamily(objectManager);
        assertNotEquals(beforeIds.get("flamethrower"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacement.flamethrower()));

        rewindRegistry.restore(snapshot);

        HtzGraph restored = HtzGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate any HTZ boss hazard graph object");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured HTZ boss hazard identities");

        assertSame(restored.boss(), readObjectField(restored.flamethrower(), "parent"),
                "restored flamethrower must point at the restored live HTZ boss");
        assertSame(restored.boss(), readObjectField(restored.leftBall(), "parent"),
                "restored left lava ball must point at the restored live HTZ boss");
        assertSame(restored.boss(), readObjectField(restored.rightBall(), "parent"),
                "restored right lava ball must point at the restored live HTZ boss");

        assertNotSame(before.boss(), restored.boss());
        assertNotSame(before.flamethrower(), restored.flamethrower());
        assertNotSame(before.leftBall(), restored.leftBall());
        assertNotSame(before.rightBall(), restored.rightBall());
        assertNotSame(before.smoke(), restored.smoke());

        assertEquals(beforeFlame, FlamethrowerScalars.read(restored.flamethrower()),
                "ObjectManager restore must reapply captured flamethrower scalar state");
        assertEquals(beforeLeft, LavaBallScalars.read(restored.leftBall()),
                "ObjectManager restore must reapply captured left lava ball scalar state");
        assertEquals(beforeRight, LavaBallScalars.read(restored.rightBall()),
                "ObjectManager restore must reapply captured right lava ball scalar state");
        assertEquals(beforeSmoke, SmokeScalars.read(restored.smoke()),
                "ObjectManager restore must reapply captured smoke scalar state");
    }

    @Test
    void htzBossParentUsesGenericRecreateWithoutExplicitS2Codec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2HTZBossInstance.class),
                "Sonic2HTZBossInstance must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic2HTZBossInstance.class.getName()),
                "Sonic2HTZBossInstance must not keep an explicit S2 dynamic codec");
    }

    @Test
    void htzBossHazardRecreateDropsChildWhenNoLiveHtzBossExists() {
        // A destroyed/swept HTZ boss is a legitimate rewind state: the flamethrower
        // hazard has no parent to bind to, so recreate must drop it (return null)
        // rather than throw. Its live update already self-expires with a dead parent.
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
        ObjectManagerSnapshot.DynamicObjectEntry entry = new ObjectManagerSnapshot.DynamicObjectEntry(
                HTZBossFlamethrower.class.getName(),
                new ObjectSpawn(0x3040, 0x0500, Sonic2ObjectIds.HTZ_BOSS, 4, 0, false, 22),
                0,
                state);

        ObjectInstance recreated = ObjectRewindDynamicCodecs.genericRecreate(
                entry, new DynamicObjectRecreateContext(objectManager));
        assertNull(recreated,
                "flamethrower hazard must be dropped when no HTZ boss survives to parent it");
    }

    @Test
    void unmanagedRawHtzBossParentReferenceStillRequiresRegisteredIdentity() throws Exception {
        Sonic2HTZBossInstance unmanagedParent = new Sonic2HTZBossInstance(BOSS_SPAWN);
        HTZBossFlamethrower child =
                new HTZBossFlamethrower(unmanagedParent, 0x3040, 0x0500, false);
        RequiredParentReferenceFixture fixture =
                new RequiredParentReferenceFixture((ObjectInstance) readObjectField(child, "parent"));
        RewindCaptureContext context =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "non-null raw object references must still require registered rewind identities");
    }

    private static final class RequiredParentReferenceFixture {
        ObjectInstance object;

        private RequiredParentReferenceFixture(ObjectInstance parent) {
            this.object = parent;
        }
    }

    private record Harness(ObjectManager objectManager) {
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

    private record HtzGraph(
            Sonic2HTZBossInstance boss,
            HTZBossFlamethrower flamethrower,
            HTZBossLavaBall leftBall,
            HTZBossLavaBall rightBall,
            HTZBossSmokeParticle smoke) {

        static HtzGraph spawnRepresentativeFamily(ObjectManager objectManager) {
            Sonic2HTZBossInstance boss = only(objectManager, Sonic2HTZBossInstance.class);
            return spawnFamily(objectManager, boss, 0x3040, 0x0500);
        }

        static HtzGraph spawnReplacementFamily(ObjectManager objectManager) {
            Sonic2HTZBossInstance boss = only(objectManager, Sonic2HTZBossInstance.class);
            return spawnFamily(objectManager, boss, 0x3100, 0x0520);
        }

        private static HtzGraph spawnFamily(
                ObjectManager objectManager,
                Sonic2HTZBossInstance boss,
                int x,
                int y) {
            HTZBossFlamethrower flamethrower = objectManager.createDynamicObject(
                    () -> new HTZBossFlamethrower(boss, x, y - 0x1C, false));
            HTZBossLavaBall leftBall = objectManager.createDynamicObject(
                    () -> new HTZBossLavaBall(boss, x, y, true, true));
            HTZBossLavaBall rightBall = objectManager.createDynamicObject(
                    () -> new HTZBossLavaBall(boss, x, y, false, true));
            HTZBossSmokeParticle smoke = objectManager.createDynamicObject(
                    () -> new HTZBossSmokeParticle(x, y - 0x28));
            return new HtzGraph(boss, flamethrower, leftBall, rightBall, smoke);
        }

        static HtzGraph fromLiveObjects(ObjectManager objectManager) throws Exception {
            List<HTZBossLavaBall> lavaBalls = liveObjects(objectManager, HTZBossLavaBall.class);
            assertEquals(2, lavaBalls.size(), "expected exactly two live HTZ boss lava balls");
            return new HtzGraph(
                    only(objectManager, Sonic2HTZBossInstance.class),
                    only(objectManager, HTZBossFlamethrower.class),
                    lavaBallBySide(lavaBalls, true),
                    lavaBallBySide(lavaBalls, false),
                    only(objectManager, HTZBossSmokeParticle.class));
        }

        void writeDistinctScalarState() throws Exception {
            writeIntField(flamethrower, "currentX", 0x3051);
            writeIntField(flamethrower, "currentY", 0x04E2);
            writeIntField(flamethrower, "xVel", -7);
            writeBooleanField(flamethrower, "flipped", true);
            writeIntField(flamethrower, "animFrame", 1);
            writeIntField(flamethrower, "animTimer", 2);

            writeLavaScalars(leftBall, 0x2FE0, 0x0521, 0x2FE00000, 0x05210000,
                    -0x1200, -0x3300, true, false, 1, 2);
            writeLavaScalars(rightBall, 0x3090, 0x0528, 0x30900000, 0x05280000,
                    0x1600, -0x2A00, false, true, 0, 1);

            writeIntField(smoke, "x", 0x3012);
            writeIntField(smoke, "y", 0x04C8);
            writeIntField(smoke, "xFixed", 0x30120000);
            writeIntField(smoke, "yFixed", 0x04C80000);
            writeIntField(smoke, "animFrame", 2);
            writeIntField(smoke, "animTimer", 3);
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
            ids.put("flamethrower", table.idFor(flamethrower));
            ids.put("leftBall", table.idFor(leftBall));
            ids.put("rightBall", table.idFor(rightBall));
            ids.put("smoke", table.idFor(smoke));
            return ids;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            objectManager.removeDynamicObject(flamethrower);
            objectManager.removeDynamicObject(leftBall);
            objectManager.removeDynamicObject(rightBall);
            objectManager.removeDynamicObject(smoke);
        }

        private List<ObjectInstance> objects() {
            return List.of(boss, flamethrower, leftBall, rightBall, smoke);
        }
    }

    private record FlamethrowerScalars(
            int currentX,
            int currentY,
            int xVel,
            boolean flipped,
            int animFrame,
            int animTimer) {
        static FlamethrowerScalars read(HTZBossFlamethrower flamethrower) throws Exception {
            return new FlamethrowerScalars(
                    readIntField(flamethrower, "currentX"),
                    readIntField(flamethrower, "currentY"),
                    readIntField(flamethrower, "xVel"),
                    readBooleanField(flamethrower, "flipped"),
                    readIntField(flamethrower, "animFrame"),
                    readIntField(flamethrower, "animTimer"));
        }
    }

    private record LavaBallScalars(
            int currentX,
            int currentY,
            int xFixed,
            int yFixed,
            int xVel,
            int yVel,
            boolean leftBall,
            boolean fromLeftSide,
            int animFrame,
            int animTimer) {
        static LavaBallScalars read(HTZBossLavaBall lavaBall) throws Exception {
            return new LavaBallScalars(
                    readIntField(lavaBall, "currentX"),
                    readIntField(lavaBall, "currentY"),
                    readIntField(lavaBall, "xFixed"),
                    readIntField(lavaBall, "yFixed"),
                    readIntField(lavaBall, "xVel"),
                    readIntField(lavaBall, "yVel"),
                    readBooleanField(lavaBall, "leftBall"),
                    readBooleanField(lavaBall, "fromLeftSide"),
                    readIntField(lavaBall, "animFrame"),
                    readIntField(lavaBall, "animTimer"));
        }
    }

    private record SmokeScalars(
            int x,
            int y,
            int xFixed,
            int yFixed,
            int animFrame,
            int animTimer) {
        static SmokeScalars read(HTZBossSmokeParticle smoke) throws Exception {
            return new SmokeScalars(
                    readIntField(smoke, "x"),
                    readIntField(smoke, "y"),
                    readIntField(smoke, "xFixed"),
                    readIntField(smoke, "yFixed"),
                    readIntField(smoke, "animFrame"),
                    readIntField(smoke, "animTimer"));
        }
    }

    private static void writeLavaScalars(
            HTZBossLavaBall lavaBall,
            int currentX,
            int currentY,
            int xFixed,
            int yFixed,
            int xVel,
            int yVel,
            boolean leftBall,
            boolean fromLeftSide,
            int animFrame,
            int animTimer) throws Exception {
        writeIntField(lavaBall, "currentX", currentX);
        writeIntField(lavaBall, "currentY", currentY);
        writeIntField(lavaBall, "xFixed", xFixed);
        writeIntField(lavaBall, "yFixed", yFixed);
        writeIntField(lavaBall, "xVel", xVel);
        writeIntField(lavaBall, "yVel", yVel);
        writeBooleanField(lavaBall, "leftBall", leftBall);
        writeBooleanField(lavaBall, "fromLeftSide", fromLeftSide);
        writeIntField(lavaBall, "animFrame", animFrame);
        writeIntField(lavaBall, "animTimer", animTimer);
    }

    private static HTZBossLavaBall lavaBallBySide(
            List<HTZBossLavaBall> lavaBalls,
            boolean leftBall) throws Exception {
        for (HTZBossLavaBall lavaBall : lavaBalls) {
            if (readBooleanField(lavaBall, "leftBall") == leftBall) {
                return lavaBall;
            }
        }
        throw new AssertionError("missing " + (leftBall ? "left" : "right") + " HTZ lava ball");
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

    private static int readIntField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean readBooleanField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static Object readObjectField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void writeIntField(Object target, String fieldName, int value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void writeBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
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
