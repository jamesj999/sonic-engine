package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.OOZBurnerFlameObjectInstance;
import com.openggf.game.sonic2.objects.OOZPoppingPlatformObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
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
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2OozBurnerFlameGraphRewind {
    private static final ObjectSpawn MATCHING_PLATFORM_SPAWN =
            new ObjectSpawn(0x080, 0x240, Sonic2ObjectIds.OOZ_POPPING_PLATFORM, 0, 0, false, 0, 70);
    private static final ObjectSpawn DISTRACTOR_PLATFORM_SPAWN =
            new ObjectSpawn(0x080, 0x280, Sonic2ObjectIds.OOZ_POPPING_PLATFORM, 0, 0, false, 0, 71);
    private static final ObjectSpawn DIVERGENT_FLAME_SPAWN =
            new ObjectSpawn(0x0C0, 0x270, Sonic2ObjectIds.OOZ_POPPING_PLATFORM, 0, 0, false, 0, 72);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void oozBurnerFlameGraphRestoresFreshParentBackrefAndActiveScalarState() {
        Harness harness = Harness.create(List.of(MATCHING_PLATFORM_SPAWN, DISTRACTOR_PLATFORM_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        spawnFlameChildren(objectManager);
        OOZPoppingPlatformObjectInstance sourceParent =
                platformAt(objectManager, MATCHING_PLATFORM_SPAWN);
        OOZPoppingPlatformObjectInstance sourceDistractor =
                platformAt(objectManager, DISTRACTOR_PLATFORM_SPAWN);
        OOZBurnerFlameObjectInstance sourceFlame =
                flameAt(objectManager, MATCHING_PLATFORM_SPAWN.x(), MATCHING_PLATFORM_SPAWN.y() - 0x10);
        OOZBurnerFlameObjectInstance sourceDistractorFlame =
                flameAt(objectManager, DISTRACTOR_PLATFORM_SPAWN.x(), DISTRACTOR_PLATFORM_SPAWN.y() - 0x10);

        setIntField(sourceParent, "currentY", MATCHING_PLATFORM_SPAWN.y() - 0x30);
        sourceFlame.update(0, new TestablePlayableSprite("sonic", (short) 0, (short) 0));
        assertEquals(0x9B, sourceFlame.getCollisionFlags(),
                "precondition: captured flame scalar state is active");

        ObjectRefId parentId = objectId(objectManager, sourceParent);
        ObjectRefId distractorId = objectId(objectManager, sourceDistractor);
        ObjectRefId flameId = objectId(objectManager, sourceFlame);
        ObjectRefId distractorFlameId = objectId(objectManager, sourceDistractorFlame);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceFlame);
        OOZBurnerFlameObjectInstance divergentFlame = objectManager.createDynamicObject(
                () -> new OOZBurnerFlameObjectInstance(DIVERGENT_FLAME_SPAWN, sourceDistractor));
        assertEquals(2, liveObjects(objectManager, OOZBurnerFlameObjectInstance.class).size(),
                "diverge step should leave one captured distractor flame and one unrelated flame");

        registry.restore(snapshot);

        OOZPoppingPlatformObjectInstance restoredParent =
                objectById(objectManager, OOZPoppingPlatformObjectInstance.class, parentId);
        OOZPoppingPlatformObjectInstance restoredDistractor =
                objectById(objectManager, OOZPoppingPlatformObjectInstance.class, distractorId);
        OOZBurnerFlameObjectInstance restoredFlame =
                objectById(objectManager, OOZBurnerFlameObjectInstance.class, flameId);
        OOZBurnerFlameObjectInstance restoredDistractorFlame =
                objectById(objectManager, OOZBurnerFlameObjectInstance.class, distractorFlameId);

        assertEquals(2, liveObjects(objectManager, OOZPoppingPlatformObjectInstance.class).size(),
                "restore must not drop or duplicate OOZ popping platforms");
        assertEquals(2, liveObjects(objectManager, OOZBurnerFlameObjectInstance.class).size(),
                "restore must not drop or duplicate OOZ burner flames");
        assertNotSame(sourceParent, restoredParent, "restore must recreate the matching parent");
        assertNotSame(sourceDistractor, restoredDistractor, "restore must recreate the distractor parent");
        assertNotSame(sourceFlame, restoredFlame, "restore must recreate the removed flame");
        assertNotSame(sourceDistractorFlame, restoredDistractorFlame,
                "restore must recreate the distractor flame too");
        assertNotSame(divergentFlame, restoredFlame, "restore must drop divergent flame objects");

        assertSame(restoredParent, readObjectField(restoredFlame, "parent"),
                "flame parent must resolve to the matching restored OOZ platform");
        assertNotSame(sourceParent, readObjectField(restoredFlame, "parent"),
                "flame parent must not retain the stale pre-restore platform");
        assertNotSame(restoredDistractor, readObjectField(restoredFlame, "parent"),
                "flame parent must not relink to the nearby same-X distractor platform");
        assertEquals(0x9B, restoredFlame.getCollisionFlags(),
                "active flame collision scalar must round-trip through compact restore");
    }

    @Test
    void genericRecreateDropsOozBurnerFlameWhenNoMatchingLiveParentExists() {
        Harness harness = Harness.create(List.of(DISTRACTOR_PLATFORM_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        ObjectSpawn missingParentFlameSpawn = new ObjectSpawn(
                MATCHING_PLATFORM_SPAWN.x(),
                MATCHING_PLATFORM_SPAWN.y() - 0x10,
                Sonic2ObjectIds.OOZ_POPPING_PLATFORM,
                0,
                0,
                false,
                0,
                73);

        ObjectInstance recreated = ObjectRewindDynamicCodecs.genericRecreate(
                new ObjectManagerSnapshot.DynamicObjectEntry(
                        OOZBurnerFlameObjectInstance.class.getName(),
                        missingParentFlameSpawn,
                        0,
                        emptyState()),
                new DynamicObjectRecreateContext(objectManager));

        assertNull(recreated,
                "generic recreate must drop an OOZ flame when no live platform matches the old codec predicate");
    }

    @Test
    void oozBurnerFlameUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(OOZPoppingPlatformObjectInstance.class),
                "OOZPoppingPlatformObjectInstance must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(OOZBurnerFlameObjectInstance.class),
                "OOZBurnerFlameObjectInstance must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        OOZPoppingPlatformObjectInstance.class.getName()),
                "OOZPoppingPlatformObjectInstance must not keep an explicit S2 dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        OOZBurnerFlameObjectInstance.class.getName()),
                "OOZBurnerFlameObjectInstance must not keep an explicit S2 dynamic codec");
    }

    private static void spawnFlameChildren(ObjectManager objectManager) {
        objectManager.update(0, new TestablePlayableSprite("sonic", (short) 0, (short) 0), List.of(), 0);
        assertEquals(2, liveObjects(objectManager, OOZPoppingPlatformObjectInstance.class).size(),
                "fixture should place both OOZ popping platform parents");
        assertEquals(2, liveObjects(objectManager, OOZBurnerFlameObjectInstance.class).size(),
                "each managed OOZ popping platform should spawn one burner flame through its real update path");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
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
            return new Harness(objectManager, services);
        }
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

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return liveObjects(objectManager, type).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static OOZPoppingPlatformObjectInstance platformAt(
            ObjectManager objectManager, ObjectSpawn spawn) {
        return liveObjects(objectManager, OOZPoppingPlatformObjectInstance.class).stream()
                .filter(platform -> platform.getSpawn().equals(spawn))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing platform for " + spawn));
    }

    private static OOZBurnerFlameObjectInstance flameAt(
            ObjectManager objectManager, int x, int y) {
        return liveObjects(objectManager, OOZBurnerFlameObjectInstance.class).stream()
                .filter(flame -> flame.getSpawn().x() == x && flame.getSpawn().y() == y)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing flame at " + x + "," + y));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS2OozBurnerFlameGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof com.openggf.level.objects.AbstractObjectInstance aoi
                ? aoi.getSlotIndex()
                : -1;
    }

    private static PerObjectRewindSnapshot emptyState() {
        return new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0,
                false, false, 0, -1, null, null, null);
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
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
