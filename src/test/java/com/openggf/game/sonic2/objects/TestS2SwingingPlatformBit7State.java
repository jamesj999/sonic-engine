package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.OscillationManager;
import com.openggf.game.OscillationSnapshot;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TestS2SwingingPlatformBit7State {
    private static final ObjectSpawn BIT7_SPAWN =
            new ObjectSpawn(0x300, 0x180, Sonic2ObjectIds.SWINGING_PLATFORM, 0x88, 0, false, 0, 451);
    private static final ObjectSpawn NORMAL_SPAWN =
            new ObjectSpawn(0x300, 0x180, Sonic2ObjectIds.SWINGING_PLATFORM, 0x08, 0, false, 0, 452);
    private static final SolidContact STANDING_CONTACT =
            new SolidContact(true, false, false, true, false);
    private static final ObjectRegistry SWINGING_PLATFORM_REGISTRY = new ObjectRegistry() {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return new SwingingPlatformObjectInstance(spawn, "SwingingPlatform");
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "SwingingPlatform";
        }
    };

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        OscillationManager.reset();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
        OscillationManager.reset();
    }

    @Test
    void subtype88SpawnsIndependentFallingChildOnlyWhenStandingAtOscillatorZero() {
        setOscillatorHighByte(0x18, 0);
        Harness harness = Harness.create(List.of(BIT7_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        SwingingPlatformObjectInstance parent = harness.createdPlatforms().getFirst();

        parent.onSolidContact(null, STANDING_CONTACT, 1);
        parent.update(1, null);

        List<SwingingPlatformObjectInstance> platforms = livePlatforms(objectManager);
        assertEquals(2, platforms.size(), "ROM Obj15_State4 copies the parent into a State6 child");

        SwingingPlatformObjectInstance child = platforms.stream()
                .filter(platform -> platform != parent)
                .findFirst()
                .orElseThrow();
        assertNotSame(parent, child);
        assertEquals(3, readIntField(parent, "platformMappingFrame"),
                "ROM Obj15_State4 sets mapping_frame 3 on the parent after splitting");
        assertEquals(parent.getX(), child.getX(), "child starts at the current platform x_pos");
        assertEquals(parent.getY(), child.getY(), "child starts at the current platform y_pos");
    }

    @Test
    void subtype88ChildFallsToY720ThenBobsFromOscillator14() {
        setOscillatorHighByte(0x18, 0);
        Harness harness = Harness.create(List.of(BIT7_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        SwingingPlatformObjectInstance parent = harness.createdPlatforms().getFirst();
        parent.onSolidContact(null, STANDING_CONTACT, 1);
        parent.update(1, null);

        SwingingPlatformObjectInstance child = livePlatforms(objectManager).stream()
                .filter(platform -> platform != parent)
                .findFirst()
                .orElseThrow();

        for (int frame = 2; frame < 220; frame++) {
            child.update(frame, null);
        }

        assertEquals(0x720, child.getY(), "State6 clamps the falling child to y=$720");

        setOscillatorHighByte(0x14, 0x20);
        child.update(200, null);

        assertEquals(0x730, child.getY(),
                "State6 bob phase uses objoff_38 + (Oscillating_Data+$14 >> 1)");
    }

    @Test
    void nonBit7SubtypeKeepsNormalSinglePlatformBehavior() {
        setOscillatorHighByte(0x18, 0);
        Harness harness = Harness.create(List.of(NORMAL_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        SwingingPlatformObjectInstance platform = harness.createdPlatforms().getFirst();

        platform.onSolidContact(null, STANDING_CONTACT, 1);
        platform.update(1, null);

        assertEquals(1, livePlatforms(objectManager).size(),
                "normal Obj15 subtypes must not use the bit-7 State4 split path");
    }

    private static List<SwingingPlatformObjectInstance> livePlatforms(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(SwingingPlatformObjectInstance.class::isInstance)
                .map(SwingingPlatformObjectInstance.class::cast)
                .filter(platform -> !platform.isDestroyed())
                .toList();
    }

    private static void setOscillatorHighByte(int offset, int highByte) {
        OscillationSnapshot snapshot = OscillationManager.snapshot();
        int[] values = snapshot.values();
        int index = offset / 4;
        values[index] = ((highByte & 0xFF) << 8) | (values[index] & 0x00FF);
        OscillationManager.restore(new OscillationSnapshot(
                values,
                snapshot.deltas(),
                snapshot.activeSpeeds(),
                snapshot.activeLimits(),
                snapshot.control(),
                snapshot.lastFrame(),
                snapshot.suppressedUpdates()));
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record Harness(ObjectManager objectManager, List<SwingingPlatformObjectInstance> createdPlatforms) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    SWINGING_PLATFORM_REGISTRY,
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            List<SwingingPlatformObjectInstance> created = new java.util.ArrayList<>();
            for (ObjectSpawn spawn : spawns) {
                created.add(objectManager.createDynamicObject(() ->
                        new SwingingPlatformObjectInstance(spawn, "SwingingPlatform")));
            }
            return new Harness(objectManager, created);
        }
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0x200; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
        };
    }
}
