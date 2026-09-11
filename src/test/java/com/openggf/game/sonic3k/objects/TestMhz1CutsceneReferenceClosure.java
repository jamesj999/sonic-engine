package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestMhz1CutsceneReferenceClosure {
    private static final ObjectSpawn BUTTON_SPAWN =
            new ObjectSpawn(0x0380, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0x31);
    private static final ObjectSpawn ACTOR_SPAWN =
            new ObjectSpawn(0x0608, 0x066C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0x37);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void unloadedMhz1KnucklesDetachesFromLiveButtonBeforeClosureValidation() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        Mhz1CutsceneButtonInstance button = objectManager.createDynamicObject(
                () -> new Mhz1CutsceneButtonInstance(BUTTON_SPAWN));
        CutsceneKnucklesMhz1Instance actor = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesMhz1Instance(ACTOR_SPAWN, button));
        setObjectField(button, "spawnedKnuckles", actor);
        setEnumField(actor, "routine", "EXIT");
        Object motion = readObjectField(actor, "motion");
        setIntField(motion, "x", -0x1000);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x80, (short) 0x80);
        harness.camera().setFocusedSprite(player);
        objectManager.update(0, player, List.of(), 0, false);

        assertFalse(objectManager.getActiveObjects().contains(actor));
        assertDoesNotThrow(objectManager::validateRewindReferenceClosure);
        assertNull(readObjectField(button, "spawnedKnuckles"));
    }

    private record Harness(ObjectManager objectManager, TestCamera camera) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            TestCamera camera = new TestCamera();
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public ObjectPlayerQuery playerQuery() {
                    return new ObjectPlayerQuery(camera::getFocusedSprite, List::of);
                }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
                @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
            };
            services.zoneRuntimeRegistry().install(new MhzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS));
            ObjectManager objectManager = new ObjectManager(
                    List.of(), new MhzTestRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(0);
            objectManager.setRewindInPlaceRestoreEnabledForTest(false);
            return new Harness(objectManager, camera);
        }
    }

    private static final class TestCamera extends Camera {
        private AbstractPlayableSprite focusedSprite;

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return 0; }
        @Override public short getY() { return 0; }
        @Override public short getWidth() { return 0x1000; }
        @Override public short getHeight() { return 0x1000; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }

    private static final class MhzTestRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnumField(Object target, String fieldName, String value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            Class<? extends Enum> enumType = (Class<? extends Enum>) field.getType().asSubclass(Enum.class);
            field.set(target, Enum.valueOf(enumType, value));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write enum " + fieldName + " on " + target.getClass(), e);
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
}
