package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.CnzCannonInstance;
import com.openggf.game.sonic3k.objects.CnzCylinderInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kCnzTraversalPlayerReferenceRewind {
    private static final ObjectSpawn CANNON_SPAWN =
            new ObjectSpawn(0x1600, 0x0680, Sonic3kObjectIds.CNZ_CANNON, 0, 0, false, 90);
    private static final ObjectSpawn CYLINDER_SPAWN =
            new ObjectSpawn(0x1E00, 0x0600, Sonic3kObjectIds.CNZ_CYLINDER, 0x42, 0, false, 91);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cnzCannonReleasedPlayerRestoresToCurrentLivePlayerReference() throws Exception {
        TestablePlayableSprite capturedPlayer = player("old-sonic");
        Harness harness = Harness.create(capturedPlayer);
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CnzCannonInstance source = objectManager.createDynamicObject(
                () -> new CnzCannonInstance(CANNON_SPAWN));
        writeObjectField(source, "releasedPlayer", capturedPlayer);
        writeIntField(source, "releasedPlayerPriorityTimer", 6);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        TestablePlayableSprite restoredPlayer = player("new-sonic");
        harness.camera().setFocusedSprite(restoredPlayer);
        rewindRegistry.restore(snapshot);

        CnzCannonInstance restored = onlyLive(objectManager, CnzCannonInstance.class);
        assertNotSame(source, restored, "restore must recreate the CNZ cannon");
        assertSame(restoredPlayer, readObjectField(restored, "releasedPlayer"),
                "releasedPlayer must resolve through the current restore identity table");
        assertEquals(6, readIntField(restored, "releasedPlayerPriorityTimer"),
                "released-player priority timer must restore with the player ref");
    }

    @Test
    void cnzCylinderReleasedJumpSolidSkipPlayerRestoresToCurrentLivePlayerReference() throws Exception {
        TestablePlayableSprite capturedPlayer = player("old-sonic");
        Harness harness = Harness.create(capturedPlayer);
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CnzCylinderInstance source = objectManager.createDynamicObject(
                () -> new CnzCylinderInstance(CYLINDER_SPAWN));
        writeObjectField(source, "releasedJumpSolidSkipPlayer", capturedPlayer);
        writeIntField(source, "standingMask", 1);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        TestablePlayableSprite restoredPlayer = player("new-sonic");
        harness.camera().setFocusedSprite(restoredPlayer);
        rewindRegistry.restore(snapshot);

        CnzCylinderInstance restored = onlyLive(objectManager, CnzCylinderInstance.class);
        assertNotSame(source, restored, "restore must recreate the CNZ cylinder");
        assertSame(restoredPlayer, readObjectField(restored, "releasedJumpSolidSkipPlayer"),
                "releasedJumpSolidSkipPlayer must resolve through the current restore identity table");
        assertEquals(1, readIntField(restored, "standingMask"),
                "nearby traversal scalar state must still compact-restore with the player ref");
    }

    @Test
    void missingCnzTraversalPlayerReferencesStillFailLoudlyOnRestore() throws Exception {
        TestablePlayableSprite capturedPlayer = player("old-sonic");
        Harness harness = Harness.create(capturedPlayer);
        CnzCannonInstance cannon = harness.objectManager().createDynamicObject(
                () -> new CnzCannonInstance(CANNON_SPAWN));
        CnzCylinderInstance cylinder = harness.objectManager().createDynamicObject(
                () -> new CnzCylinderInstance(CYLINDER_SPAWN));
        writeObjectField(cannon, "releasedPlayer", capturedPlayer);
        writeObjectField(cylinder, "releasedJumpSolidSkipPlayer", capturedPlayer);
        RewindRegistry rewindRegistry = registryFor(harness.objectManager());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        harness.camera().setFocusedSprite(null);
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> rewindRegistry.restore(snapshot));
        assertTrue(thrown.getMessage().contains("Missing required player reference"),
                "missing CNZ traversal player identity must fail loudly");
    }

    private record Harness(ObjectManager objectManager, TestCamera camera, ObjectServices services) {
        static Harness create(AbstractPlayableSprite focusedPlayer) {
            ObjectManager[] holder = new ObjectManager[1];
            TestCamera camera = new TestCamera();
            camera.setFocusedSprite(focusedPlayer);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    null,
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(camera.getX());
            return new Harness(objectManager, camera, services);
        }
    }

    private static final class TestCamera extends Camera {
        private AbstractPlayableSprite focusedSprite;

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return 0x1400; }
        @Override public short getY() { return 0x0500; }
        @Override public short getWidth() { return 320; }
        @Override public short getHeight() { return 224; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static <T extends ObjectInstance> T onlyLive(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
        assertEquals(1, matches.size(), "restore must leave exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static TestablePlayableSprite player(String code) {
        return new TestablePlayableSprite(code, (short) 0x1600, (short) 0x0680);
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        return field(target, name).getInt(target);
    }

    private static void writeObjectField(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }

    private static void writeIntField(Object target, String name, int value) throws Exception {
        field(target, name).setInt(target, value);
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
