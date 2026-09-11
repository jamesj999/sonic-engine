package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kHczWaterRushGraphRewind {
    private static final String BLOCK_CLASS =
            "com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance$WaterRushBlockChild";
    private static final ObjectSpawn WATER_RUSH_SPAWN =
            new ObjectSpawn(0x0580, 0x05a0, Sonic3kObjectIds.HCZ_WATER_RUSH, 0, 0, false, 0x05a0, 11);
    private static final ObjectSpawn DIVERGENT_BLOCK_SPAWN =
            new ObjectSpawn(0x0610, 0x0620, Sonic3kObjectIds.HCZ_WATER_RUSH, 0, 0, false, 18);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        HCZWaterRushObjectInstance.HCZBreakableBarState.reset();
        HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.reset();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
        HCZWaterRushObjectInstance.HCZBreakableBarState.reset();
        HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.reset();
    }

    @Test
    void waterRushBlockRestoresFreshExactStateParentAndConstructorSideEffect() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        HCZWaterRushObjectInstance sourceWaterRush =
                only(objectManager, HCZWaterRushObjectInstance.class);
        ObjectInstance sourceBlock = onlyBlock(objectManager);
        ObjectRefId waterRushId = objectId(objectManager, sourceWaterRush);
        ObjectRefId blockId = objectId(objectManager, sourceBlock);

        writeIntField(sourceBlock, "x", 0x0450);
        writeIntField(sourceBlock, "y", 0x0610);
        writeIntField(sourceBlock, "phase", 1);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceBlock);
        ObjectInstance divergentBlock = objectManager.createDynamicObject(
                () -> constructBlock(DIVERGENT_BLOCK_SPAWN, sourceWaterRush));
        writeIntField(divergentBlock, "x", 0x0660);
        writeIntField(divergentBlock, "y", 0x0670);
        writeIntField(divergentBlock, "phase", 0);
        HCZWaterRushObjectInstance.HCZBreakableBarState.setState(0);
        assertEquals(1, liveBlocks(objectManager).size(),
                "diverge step should leave one unrelated HCZ water-rush block before restore");

        rewindRegistry.restore(snapshot);

        HCZWaterRushObjectInstance restoredWaterRush =
                objectById(objectManager, HCZWaterRushObjectInstance.class, waterRushId);
        ObjectInstance restoredBlock = objectByIdRaw(objectManager, Class.forName(BLOCK_CLASS), blockId);
        assertEquals(1, liveBlocks(objectManager).size(),
                "restore must keep exactly the captured HCZ water-rush block");
        assertNotSame(sourceWaterRush, restoredWaterRush,
                "restore must recreate the HCZ water-rush parent");
        assertNotSame(sourceBlock, restoredBlock,
                "restore must recreate the HCZ water-rush block");
        assertNotSame(divergentBlock, restoredBlock,
                "restore must drop the divergent HCZ water-rush block");
        assertEquals(0x0450, readIntField(restoredBlock, "x"),
                "restored block must keep its captured x state");
        assertEquals(0x0610, readIntField(restoredBlock, "y"),
                "restored block must keep its captured y state");
        assertEquals(1, readIntField(restoredBlock, "phase"),
                "restored block must keep its captured phase");
        assertSame(restoredWaterRush, readObjectField(restoredBlock, "parent"),
                "water-rush block parent must resolve to the restored water-rush object");
        assertNotSame(sourceWaterRush, readObjectField(restoredBlock, "parent"),
                "water-rush block must not retain the stale pre-restore parent");
        assertEquals(3, HCZWaterRushObjectInstance.HCZBreakableBarState.getState(),
                "restore must rerun the parent constructor side effect");
    }

    @Test
    void waterRushFamilyUsesGenericRecreateWithoutExplicitS3kCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZWaterRushObjectInstance.class),
                "HCZ water rush must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(BLOCK_CLASS)),
                "HCZ water-rush block must restore through generic graph recreate");
        assertTrue(HCZWaterRushObjectInstance.class.isAnnotationPresent(RewindRecreateOnRestore.class),
                "HCZ water rush constructor mutates global state and must stay recreate-pinned");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        HCZWaterRushObjectInstance.class.getName()),
                "HCZ water rush must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(BLOCK_CLASS),
                "HCZ water-rush block must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(WATER_RUSH_SPAWN),
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(camera.getX());
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectInstance constructBlock(
            ObjectSpawn spawn,
            HCZWaterRushObjectInstance parent) {
        try {
            Class<?> blockType = Class.forName(BLOCK_CLASS);
            Constructor<?> ctor = blockType.getDeclaredConstructor(ObjectSpawn.class, HCZWaterRushObjectInstance.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(spawn, parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct HCZ water-rush block", e);
        }
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager,
            Class<T> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectInstance objectByIdRaw(
            ObjectManager objectManager,
            Class<?> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectInstance onlyBlock(ObjectManager objectManager) {
        List<ObjectInstance> blocks = liveBlocks(objectManager);
        assertEquals(1, blocks.size(), "expected exactly one live HCZ water-rush block");
        return blocks.getFirst();
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static List<ObjectInstance> liveBlocks(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(BLOCK_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0x0500; }
            @Override public short getY() { return 0x0500; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.get(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.getInt(target);
    }

    private static void writeIntField(Object target, String name, int value) throws Exception {
        Field field = field(target, name);
        field.setInt(target, value);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
