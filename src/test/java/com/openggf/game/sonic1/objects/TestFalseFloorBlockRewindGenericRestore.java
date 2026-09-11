package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.bosses.Sonic1FalseFloorInstance;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFalseFloorBlockRewindGenericRestore {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void falseFloorBlocksRestoreWithIdentityScalarsAndMasterRelink() throws Exception {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
        };

        ObjectSpawn spawn = new ObjectSpawn(160, 240,
                Sonic1ObjectIds.FALSE_FLOOR, 0, 0, false, 0);
        ObjectManager objectManager = new ObjectManager(
                List.of(spawn), new Sonic1ObjectRegistry(),
                0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = objectManager;
        objectManager.reset(0);

        Sonic1FalseFloorInstance master = findMaster(objectManager);
        assertNotNull(master, "false-floor master must be active after reset");
        master.update(1, null);

        List<Sonic1FalseFloorInstance.FalseFloorBlock> blocks = falseFloorBlocks(objectManager);
        assertEquals(8, blocks.size(), "master update must production-spawn 8 floor blocks");

        Map<Integer, BlockSnapshot> expectedByIndex = new HashMap<>();
        var identityTable = objectManager.captureIdentityContext().requireIdentityTable();
        for (Sonic1FalseFloorInstance.FalseFloorBlock block : blocks) {
            int blockIndex = readInt(block, "blockIndex");
            ObjectRefId objectId = identityTable.idFor(block);
            assertNotNull(objectId, "captured block must have a dynamic rewind identity");
            expectedByIndex.put(blockIndex, new BlockSnapshot(
                    blockIndex,
                    readInt(block, "currentX"),
                    readInt(block, "currentY"),
                    objectId));
        }
        assertEquals(8, expectedByIndex.size(), "blockIndex values must be unique");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        for (Sonic1FalseFloorInstance.FalseFloorBlock block : blocks) {
            objectManager.removeDynamicObject(block);
        }
        assertEquals(0, falseFloorBlocks(objectManager).size(),
                "test must remove the live blocks before restore");

        rewindRegistry.restore(snapshot);

        List<Sonic1FalseFloorInstance.FalseFloorBlock> restoredBlocks =
                falseFloorBlocks(objectManager);
        assertEquals(8, restoredBlocks.size(),
                "restore must recreate exactly the captured block set");

        var restoredIdentityTable = objectManager.captureIdentityContext().requireIdentityTable();
        Set<Integer> restoredIndexes = new HashSet<>();
        for (Sonic1FalseFloorInstance.FalseFloorBlock restored : restoredBlocks) {
            int blockIndex = readInt(restored, "blockIndex");
            BlockSnapshot expected = expectedByIndex.get(blockIndex);
            assertNotNull(expected, "unexpected restored blockIndex " + blockIndex);
            assertTrue(restoredIndexes.add(blockIndex),
                    "duplicate restored blockIndex " + blockIndex);
            assertEquals(expected.currentX(), readInt(restored, "currentX"),
                    "currentX scalar must restore for block " + blockIndex);
            assertEquals(expected.currentY(), readInt(restored, "currentY"),
                    "currentY scalar must restore for block " + blockIndex);
            assertEquals(expected.objectId(), restoredIdentityTable.idFor(restored),
                    "dynamic rewind identity must restore for block " + blockIndex);
        }
        assertEquals(expectedByIndex.keySet(), restoredIndexes,
                "restored block indexes must match the captured set exactly");

        Sonic1FalseFloorInstance restoredMaster = findMaster(objectManager);
        assertNotNull(restoredMaster, "master must still be active after restore");
        List<Sonic1FalseFloorInstance.FalseFloorBlock> relinked =
                readChildBlocks(restoredMaster);
        assertEquals(8, relinked.size(),
                "restored master childBlocks list must be relinked");
        assertEquals(new HashSet<>(restoredBlocks), new HashSet<>(relinked),
                "master childBlocks must reference the live restored block instances");
        for (int i = 0; i < relinked.size(); i++) {
            assertEquals(i, readInt(relinked.get(i), "blockIndex"),
                    "reattach order must preserve left-to-right blockIndex order");
        }

        Sonic1FalseFloorInstance.FalseFloorBlock first = relinked.get(0);
        assertFalse(readBoolean(first, "goSignal"), "precondition: first block not signaled");
        restoredMaster.signalDisintegrate();
        restoredMaster.update(2, null);
        restoredMaster.update(3, null);
        assertSame(first, readChildBlocks(restoredMaster).get(0),
                "signal path must still target the relinked restored first block");
        assertTrue(readBoolean(first, "goSignal"),
                "master signalDisintegrate update must signal the restored child block");
    }

    private record BlockSnapshot(
            int blockIndex,
            int currentX,
            int currentY,
            ObjectRefId objectId) {}

    private static Sonic1FalseFloorInstance findMaster(ObjectManager objectManager) {
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1FalseFloorInstance master && !object.isDestroyed()) {
                return master;
            }
        }
        return null;
    }

    private static List<Sonic1FalseFloorInstance.FalseFloorBlock> falseFloorBlocks(
            ObjectManager objectManager) {
        List<Sonic1FalseFloorInstance.FalseFloorBlock> blocks = new ArrayList<>();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1FalseFloorInstance.FalseFloorBlock block
                    && !object.isDestroyed()) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    @SuppressWarnings("unchecked")
    private static List<Sonic1FalseFloorInstance.FalseFloorBlock> readChildBlocks(
            Sonic1FalseFloorInstance master) throws Exception {
        Field field = findField(Sonic1FalseFloorInstance.class, "childBlocks");
        field.setAccessible(true);
        return new ArrayList<>((List<Sonic1FalseFloorInstance.FalseFloorBlock>) field.get(master));
    }

    private static int readInt(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean readBoolean(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // Walk superclass chain.
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
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
