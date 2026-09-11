package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1FragmentGraphRewind {
    private static final int WALL_FRAGMENT_SUBTYPE = (2 << 4) | 5;
    private static final int SMASH_FRAGMENT_SUBTYPE = 3;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s1DestructionFragmentsRestoreFreshWithoutDropsOrDoubles() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        Sonic1BreakableWallObjectInstance.WallFragmentInstance beforeWall =
                objectManager.createDynamicObject(() -> new Sonic1BreakableWallObjectInstance.WallFragmentInstance(
                        0x0100, 0x0120, 0x0200, -0x0100, 2, 5, null, null));
        Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance beforeSmash =
                objectManager.createDynamicObject(() -> new Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance(
                        0x0140, 0x0128, -0x0100, -0x0080, 3, null, null));
        seedFragment(beforeWall, 0x0110, 0x0130, 0x011000, 0x013000, 0x0240, -0x00C0);
        seedFragment(beforeSmash, 0x0150, 0x0138, 0x015000, 0x013800, -0x0120, -0x0060);

        ObjectRefId wallId = objectId(objectManager, beforeWall);
        ObjectRefId smashId = objectId(objectManager, beforeSmash);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(beforeWall);
        objectManager.removeDynamicObject(beforeSmash);
        Sonic1BreakableWallObjectInstance.WallFragmentInstance replacementWall =
                objectManager.createDynamicObject(() -> new Sonic1BreakableWallObjectInstance.WallFragmentInstance(
                        0x0180, 0x0100, 0, 0, 0, 0, null, null));
        Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance replacementSmash =
                objectManager.createDynamicObject(() -> new Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance(
                        0x01C0, 0x0100, 0, 0, 0, null, null));

        rewindRegistry.restore(snapshot);

        assertEquals(1, liveFragments(objectManager,
                        Sonic1BreakableWallObjectInstance.WallFragmentInstance.class).size(),
                "restore must keep exactly the captured wall fragment");
        assertEquals(1, liveFragments(objectManager,
                        Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance.class).size(),
                "restore must keep exactly the captured smash-block fragment");
        Sonic1BreakableWallObjectInstance.WallFragmentInstance restoredWall = assertInstanceOf(
                Sonic1BreakableWallObjectInstance.WallFragmentInstance.class,
                objectWithId(objectManager, wallId));
        Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance restoredSmash = assertInstanceOf(
                Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance.class,
                objectWithId(objectManager, smashId));

        assertNotSame(beforeWall, restoredWall, "restore must recreate the removed wall fragment");
        assertNotSame(beforeSmash, restoredSmash, "restore must recreate the removed smash-block fragment");
        assertNotSame(replacementWall, restoredWall, "restore must drop unrelated replacement wall fragments");
        assertNotSame(replacementSmash, restoredSmash,
                "restore must drop unrelated replacement smash-block fragments");
        assertEquals(WALL_FRAGMENT_SUBTYPE, restoredWall.getSpawn().subtype(),
                "wall fragment spawn must preserve frame/index metadata for render-piece reconstruction");
        assertEquals(SMASH_FRAGMENT_SUBTYPE, restoredSmash.getSpawn().subtype(),
                "smash-block fragment spawn must preserve index metadata for render-piece reconstruction");
        assertFragmentState(restoredWall, 0x0110, 0x0130, 0x011000, 0x013000, 0x0240, -0x00C0);
        assertFragmentState(restoredSmash, 0x0150, 0x0138, 0x015000, 0x013800, -0x0120, -0x0060);
    }

    @Test
    void s1DestructionFragmentsUseGenericRecreateWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        Sonic1BreakableWallObjectInstance.WallFragmentInstance.class),
                "wall fragments must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance.class),
                "smash-block fragments must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1BreakableWallObjectInstance.WallFragmentInstance.class.getName()),
                "wall fragments must not use an explicit dynamic rewind codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1SmashBlockObjectInstance.SmashBlockFragmentInstance.class.getName()),
                "smash-block fragments must not use an explicit dynamic rewind codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(), new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static ObjectInstance objectWithId(ObjectManager objectManager, ObjectRefId id) {
        List<ObjectInstance> matches = objectManager.getActiveObjects().stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext().requireIdentityTable().idFor(object)))
                .toList();
        assertEquals(1, matches.size(), "expected one live object for rewind id " + id);
        return matches.getFirst();
    }

    private static <T extends ObjectInstance> List<T> liveFragments(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(TestSonic1FragmentGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof com.openggf.level.objects.AbstractObjectInstance aoi
                ? aoi.getSlotIndex()
                : -1;
    }

    private static void seedFragment(
            ObjectInstance fragment,
            int posX,
            int posY,
            int subX,
            int subY,
            int velX,
            int velY) {
        writeInt(fragment, "posX", posX);
        writeInt(fragment, "posY", posY);
        writeInt(fragment, "subX", subX);
        writeInt(fragment, "subY", subY);
        writeInt(fragment, "velX", velX);
        writeInt(fragment, "velY", velY);
    }

    private static void assertFragmentState(
            ObjectInstance fragment,
            int posX,
            int posY,
            int subX,
            int subY,
            int velX,
            int velY) {
        assertEquals(posX, readInt(fragment, "posX"), "fragment posX must restore");
        assertEquals(posY, readInt(fragment, "posY"), "fragment posY must restore");
        assertEquals(subX, readInt(fragment, "subX"), "fragment subX must restore");
        assertEquals(subY, readInt(fragment, "subY"), "fragment subY must restore");
        assertEquals(velX, readInt(fragment, "velX"), "fragment velX must restore");
        assertEquals(velY, readInt(fragment, "velY"), "fragment velY must restore");
    }

    private static int readInt(Object target, String fieldName) {
        try {
            Field field = field(target.getClass(), fieldName);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            Field field = field(target.getClass(), fieldName);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Field field(Class<?> type, String fieldName) throws NoSuchFieldException {
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

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
        };
    }
}
