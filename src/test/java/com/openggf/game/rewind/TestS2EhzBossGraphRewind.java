package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.bosses.EHZBossGroundVehicle;
import com.openggf.game.sonic2.objects.bosses.EHZBossPropeller;
import com.openggf.game.sonic2.objects.bosses.EHZBossSpike;
import com.openggf.game.sonic2.objects.bosses.EHZBossVehicleTop;
import com.openggf.game.sonic2.objects.bosses.EHZBossWheel;
import com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.AbstractBossChild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2EhzBossGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x00F0, Sonic2ObjectIds.EHZ_BOSS, 0, 0, false, 12);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void ehzBossChildGraphRestoresWithoutDropsDoublesOrStaleReferences() throws Exception {
        Harness harness = Harness.create(List.of(BOSS_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        EhzGraph before = EhzGraph.fromLiveObjects(objectManager);
        before.writeDistinctPositions();

        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        Map<String, Position> beforePositions = before.positions();

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeChildren(objectManager);

        rewindRegistry.restore(snapshot);

        EhzGraph restored = EhzGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate EHZ boss child graph objects");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured EHZ boss child identities");
        assertEquals(beforePositions, restored.positions(),
                "restore must reapply captured EHZ boss child positions");

        for (AbstractBossChild child : restored.children()) {
            assertSame(restored.boss(), readObjectField(child, "parent"),
                    child.getClass().getSimpleName() + " must point at the restored live EHZ boss");
        }

        assertNotSame(before.boss(), restored.boss());
        for (int i = 0; i < before.children().size(); i++) {
            assertNotSame(before.children().get(i), restored.children().get(i));
        }
    }

    @Test
    void ehzBossParentUsesGenericRecreateWithoutExplicitS2Codec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2EHZBossInstance.class),
                "Sonic2EHZBossInstance must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic2EHZBossInstance.class.getName()),
                "Sonic2EHZBossInstance must not keep an explicit S2 dynamic codec");
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

    private record EhzGraph(
            Sonic2EHZBossInstance boss,
            EHZBossVehicleTop vehicleTop,
            EHZBossGroundVehicle groundVehicle,
            EHZBossPropeller propeller,
            List<EHZBossWheel> wheels,
            EHZBossSpike spike) {

        static EhzGraph fromLiveObjects(ObjectManager objectManager) {
            List<EHZBossWheel> wheels = liveObjects(objectManager, EHZBossWheel.class).stream()
                    .sorted(Comparator.comparingInt(EHZBossWheel::getCurrentX)
                            .thenComparingInt(EHZBossWheel::getPriorityBucket))
                    .toList();
            assertEquals(3, wheels.size(), "expected exactly three live EHZ boss wheels");
            return new EhzGraph(
                    only(objectManager, Sonic2EHZBossInstance.class),
                    only(objectManager, EHZBossVehicleTop.class),
                    only(objectManager, EHZBossGroundVehicle.class),
                    only(objectManager, EHZBossPropeller.class),
                    wheels,
                    only(objectManager, EHZBossSpike.class));
        }

        void writeDistinctPositions() {
            vehicleTop.setPosition(0x2A10, 0x0410);
            groundVehicle.setPosition(0x2A20, 0x0420);
            propeller.setPosition(0x2A30, 0x0430);
            wheels.get(0).setPosition(0x2A40, 0x0440);
            wheels.get(1).setPosition(0x2A50, 0x0450);
            wheels.get(2).setPosition(0x2A60, 0x0460);
            spike.setPosition(0x2A70, 0x0470);
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            for (ObjectInstance object : objects()) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            var table = objectManager.captureIdentityContext().requireIdentityTable();
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("boss", table.idFor(boss));
            ids.put("vehicleTop", table.idFor(vehicleTop));
            ids.put("groundVehicle", table.idFor(groundVehicle));
            ids.put("propeller", table.idFor(propeller));
            for (int i = 0; i < wheels.size(); i++) {
                ids.put("wheel" + i, table.idFor(wheels.get(i)));
            }
            ids.put("spike", table.idFor(spike));
            return ids;
        }

        Map<String, Position> positions() {
            Map<String, Position> positions = new LinkedHashMap<>();
            positions.put("vehicleTop", Position.of(vehicleTop));
            positions.put("groundVehicle", Position.of(groundVehicle));
            positions.put("propeller", Position.of(propeller));
            for (int i = 0; i < wheels.size(); i++) {
                positions.put("wheel" + i, Position.of(wheels.get(i)));
            }
            positions.put("spike", Position.of(spike));
            return positions;
        }

        void removeChildren(ObjectManager objectManager) {
            for (AbstractBossChild child : children()) {
                objectManager.removeDynamicObject(child);
            }
        }

        List<AbstractBossChild> children() {
            List<AbstractBossChild> children = new ArrayList<>();
            children.add(vehicleTop);
            children.add(groundVehicle);
            children.add(propeller);
            children.addAll(wheels);
            children.add(spike);
            return children;
        }

        private List<ObjectInstance> objects() {
            List<ObjectInstance> objects = new ArrayList<>();
            objects.add(boss);
            objects.addAll(children());
            return objects;
        }
    }

    private record Position(int x, int y) {
        static Position of(AbstractBossChild child) {
            return new Position(child.getCurrentX(), child.getCurrentY());
        }
    }

    private static Object readObjectField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getMinX() { return 0; }
            @Override public short getMaxX() { return 0x4000; }
        };
    }

    private static <T> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = liveObjects(objectManager, type);
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .filter(object -> !object.isDestroyed())
                .map(type::cast)
                .toList();
    }
}
