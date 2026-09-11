package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMhzEndBossVisualRewind {
    private static final int ARENA_ANCHORED_FLAG_OFFSET = 0x38;
    private static final int ARENA_ANCHORED_FLAG = 0x08;
    private static final int CHILD_DRAW_SPRITE2_DELETE_FLAG = 0x10;

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void visualRestoresThroughGenericRecreateWithLiveMhzEndBossParent() throws Exception {
        ObjectManager objectManager = installObjectManager();
        ObjectSpawn capturedParentSpawn = new ObjectSpawn(
                0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41);
        MhzEndBossInstance capturedParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(capturedParentSpawn));
        MhzEndBossVisualChild capturedVisual = objectManager.createDynamicObject(
                () -> new MhzEndBossVisualChild(capturedParent, 7, 3, true));
        writeInt(capturedVisual, "x", 0x4460);
        writeInt(capturedVisual, "y", 0x02A0);
        writeBoolean(capturedVisual, "highPriority", true);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "precondition: exactly one captured MHZ end boss parent is live before snapshot");
        assertEquals(1, liveObjects(objectManager, MhzEndBossVisualChild.class).size(),
                "precondition: exactly one captured visual child is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedParentId = captureTable.idFor(capturedParent);
        ObjectRefId capturedVisualId = captureTable.idFor(capturedVisual);
        assertNotNull(capturedParentId, "ObjectManager capture identity table must register the live boss");
        assertNotNull(capturedVisualId, "ObjectManager capture identity table must register the visual child");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(capturedVisual);
        objectManager.removeDynamicObject(capturedParent);
        MhzEndBossInstance divergentParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3D00, 0x340, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 42)));
        MhzEndBossVisualChild divergentVisual = objectManager.createDynamicObject(
                () -> new MhzEndBossVisualChild(divergentParent, 2, 6, false));
        writeInt(divergentVisual, "x", 0x1111);
        writeInt(divergentVisual, "y", 0x0222);
        writeBoolean(divergentVisual, "highPriority", false);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "diverge step should leave one unrelated live parent before restore");
        assertEquals(1, liveObjects(objectManager, MhzEndBossVisualChild.class).size(),
                "diverge step should leave one unrelated live visual child before restore");

        registry.restore(snapshot);

        MhzEndBossInstance restoredParent = singleLiveObject(objectManager, MhzEndBossInstance.class);
        MhzEndBossVisualChild restoredVisual =
                singleLiveObject(objectManager, MhzEndBossVisualChild.class);
        assertFalse(restoredParent == capturedParent,
                "restore should not retain the removed captured parent instance");
        assertFalse(restoredParent == divergentParent,
                "restore should replace divergent live parents with the captured parent snapshot entry");
        assertFalse(restoredVisual == capturedVisual,
                "restore should not retain the removed captured visual instance");
        assertFalse(restoredVisual == divergentVisual,
                "restore should replace divergent live visuals with the captured visual snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedParentId, restoredTable.idFor(restoredParent),
                "restored boss must retain the captured ObjectManager rewind identity");
        assertEquals(capturedVisualId, restoredTable.idFor(restoredVisual),
                "restored visual must retain the captured ObjectManager rewind identity");
        assertSame(restoredParent, readParent(restoredVisual),
                "restored visual must relink to the restored MHZ end boss parent");

        assertEquals(7, readInt(restoredVisual, "mappingFrame"),
                "restored visual must retain captured mapping frame");
        assertEquals(3, readInt(restoredVisual, "priorityBucket"),
                "restored visual must retain captured priority bucket");
        assertTrue(readBoolean(restoredVisual, "promoteOnArenaAnchor"),
                "restored visual must retain captured arena-anchor promotion flag");
        assertEquals(0x4460, readInt(restoredVisual, "x"),
                "restored visual must retain captured X before the next update");
        assertEquals(0x02A0, readInt(restoredVisual, "y"),
                "restored visual must retain captured Y before the next update");
        assertTrue(restoredVisual.isHighPriority(),
                "restored visual must retain captured high-priority state");

        restoredParent.getState().x = 0x4120;
        restoredParent.getState().y = 0x0380;
        restoredVisual.update(0, null);
        assertEquals(restoredParent.getX(), restoredVisual.getX(),
                "restored visual X must continue deriving from the restored parent");
        assertEquals(restoredParent.getY(), restoredVisual.getY(),
                "restored visual Y must continue deriving from the restored parent");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossVisualChild.class.getName()),
                "MhzEndBossVisualChild must restore through RewindRecreatable genericRecreate, "
                        + "not a handwritten S3K dynamic codec");
    }

    @Test
    void visualPriorityPromotionAndDeleteFlagsFollowParentFlags() {
        MhzEndBossInstance parent = new MhzEndBossInstance(new ObjectSpawn(
                0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41));
        MhzEndBossVisualChild promotingVisual = new MhzEndBossVisualChild(parent, 1, 5, true);
        MhzEndBossVisualChild regularVisual = new MhzEndBossVisualChild(parent, 2, 6, false);

        parent.setCustomFlag(ARENA_ANCHORED_FLAG_OFFSET, ARENA_ANCHORED_FLAG);
        promotingVisual.update(0, null);
        regularVisual.update(0, null);
        assertTrue(promotingVisual.isHighPriority(),
                "parent flag $38 bit $08 must promote visuals marked promoteOnArenaAnchor");
        assertFalse(regularVisual.isHighPriority(),
                "parent flag $38 bit $08 must not promote regular visual layers");

        parent.setCustomFlag(ARENA_ANCHORED_FLAG_OFFSET, CHILD_DRAW_SPRITE2_DELETE_FLAG);
        promotingVisual.update(1, null);
        regularVisual.update(1, null);
        assertTrue(promotingVisual.isDestroyed(),
                "parent flag $38 bit $10 must destroy promoteOnArenaAnchor visual layers");
        assertTrue(regularVisual.isDestroyed(),
                "parent flag $38 bit $10 must destroy regular visual layers");
    }

    private static ObjectManager installObjectManager() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        StubObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
        };
        ObjectManager objectManager = new ObjectManager(
                List.of(),
                new Sonic3kObjectRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);
        return objectManager;
    }

    private static MhzEndBossInstance readParent(MhzEndBossVisualChild visual)
            throws ReflectiveOperationException {
        Field field = MhzEndBossVisualChild.class.getDeclaredField("parent");
        field.setAccessible(true);
        return (MhzEndBossInstance) field.get(visual);
    }

    private static int readInt(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void writeInt(Object target, String fieldName, int value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static boolean readBoolean(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void writeBoolean(Object target, String fieldName, boolean value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static <T> T singleLiveObject(ObjectManager objectManager, Class<T> type) {
        List<T> live = liveObjects(objectManager, type);
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
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
