package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.badniks.GrabberBadnikInstance;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.MhzMushroomParachuteObjectInstance;
import com.openggf.game.sonic3k.objects.MhzStickyVineObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPlayerReferenceGraphRewind {
    private static final String MADMOLE_SIDE_DRILL_CLASS =
            "com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance$SideDrillChild";
    private static final ObjectSpawn GRABBER_SPAWN =
            new ObjectSpawn(0x1200, 0x0420, Sonic2ObjectIds.GRABBER, 0, 0, false, 80);
    private static final ObjectSpawn PARACHUTE_SPAWN =
            new ObjectSpawn(0x3200, 0x0340, Sonic3kObjectIds.MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 81);
    private static final ObjectSpawn STICKY_VINE_SPAWN =
            new ObjectSpawn(0x3300, 0x0380, Sonic3kObjectIds.MHZ_STICKY_VINE, 0, 0, false, 82);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s2GrabberPendingGrabPlayerRestoresToCurrentLivePlayerReference() throws Exception {
        TestablePlayableSprite capturedPlayer = player("old-sonic");
        Harness harness = Harness.create(capturedPlayer, List.of());
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        GrabberBadnikInstance source = objectManager.createDynamicObject(
                () -> new GrabberBadnikInstance(GRABBER_SPAWN));
        writeObjectField(source, "pendingGrabPlayer", capturedPlayer);
        writeIntField(source, "diveTimer", 21);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        TestablePlayableSprite restoredPlayer = player("new-sonic");
        harness.setPlayers(restoredPlayer, List.of());
        rewindRegistry.restore(snapshot);

        GrabberBadnikInstance restored = onlyLive(objectManager, GrabberBadnikInstance.class);
        assertNotSame(source, restored, "restore must recreate the S2 Grabber");
        assertSame(restoredPlayer, readObjectField(restored, "pendingGrabPlayer"),
                "pendingGrabPlayer must resolve through the current restore identity table");
        assertEquals(21, readIntField(restored, "diveTimer"),
                "nearby Grabber scalar state must still compact-restore with the player ref");
    }

    @Test
    void mhzMushroomParachuteGrabbedPlayersRestoreToCurrentLivePlayerReferences() throws Exception {
        TestablePlayableSprite capturedPlayer = player("old-sonic");
        TestablePlayableSprite capturedSidekick = player("old-tails");
        Harness harness = Harness.create(capturedPlayer, List.of(capturedSidekick));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        MhzMushroomParachuteObjectInstance source = objectManager.createDynamicObject(
                () -> new MhzMushroomParachuteObjectInstance(PARACHUTE_SPAWN));
        writeBooleanField(source, "grabbed", true);
        writeBooleanField(source, "nativeP2Grabbed", true);
        writeObjectField(source, "grabbedPlayer", capturedPlayer);
        writeObjectField(source, "nativeP2GrabbedPlayer", capturedSidekick);
        writeIntField(source, "releaseCooldown", 17);
        writeIntField(source, "nativeP2ReleaseCooldown", 23);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        TestablePlayableSprite restoredPlayer = player("new-sonic");
        TestablePlayableSprite restoredSidekick = player("new-tails");
        harness.setPlayers(restoredPlayer, List.of(restoredSidekick));
        rewindRegistry.restore(snapshot);

        MhzMushroomParachuteObjectInstance restored =
                onlyLive(objectManager, MhzMushroomParachuteObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the MHZ mushroom parachute");
        assertSame(restoredPlayer, readObjectField(restored, "grabbedPlayer"),
                "grabbedPlayer must resolve to the current live main player");
        assertSame(restoredSidekick, readObjectField(restored, "nativeP2GrabbedPlayer"),
                "nativeP2GrabbedPlayer must resolve to the current live sidekick");
        assertEquals(17, readIntField(restored, "releaseCooldown"),
                "main-player release cooldown must restore with the player ref");
        assertEquals(23, readIntField(restored, "nativeP2ReleaseCooldown"),
                "native-P2 release cooldown must restore with the player ref");
    }

    @Test
    void mhzStickyVineCapturedPlayerRestoresToCurrentLivePlayerReference() throws Exception {
        TestablePlayableSprite capturedPlayer = player("old-sonic");
        Harness harness = Harness.create(capturedPlayer, List.of());
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        MhzStickyVineObjectInstance source = objectManager.createDynamicObject(
                () -> new MhzStickyVineObjectInstance(STICKY_VINE_SPAWN));
        writeBooleanField(source, "active", true);
        writeObjectField(source, "capturedPlayer", capturedPlayer);
        writeBooleanField(source, "spindashReleaseArmed", true);
        writeIntField(source, "spindashReleaseTimer", 9);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        TestablePlayableSprite restoredPlayer = player("new-sonic");
        harness.setPlayers(restoredPlayer, List.of());
        rewindRegistry.restore(snapshot);

        MhzStickyVineObjectInstance restored = onlyLive(objectManager, MhzStickyVineObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the MHZ sticky vine");
        assertSame(restoredPlayer, readObjectField(restored, "capturedPlayer"),
                "capturedPlayer must resolve through the current restore identity table");
        assertTrue(readBooleanField(restored, "spindashReleaseArmed"),
                "spindash release state must restore with the player ref");
        assertEquals(9, readIntField(restored, "spindashReleaseTimer"),
                "spindash release timer must restore with the player ref");
    }

    @Test
    void madmoleSideDrillCapturedPlayerRestoresToCurrentLivePlayerReference() throws Exception {
        TestablePlayableSprite capturedPlayer = player("old-sonic");
        Harness harness = Harness.create(capturedPlayer, List.of());
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        ObjectInstance source = objectManager.createDynamicObject(
                () -> instantiateMadmoleSideDrill(0x3300, 0x0380, true));
        writeBooleanField(source, "initialized", true);
        writeBooleanField(source, "arcing", true);
        writeObjectField(source, "capturedPlayer", capturedPlayer);
        writeIntField(source, "xVelocity", 0x380);
        writeIntField(source, "mappingFrame", 9);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        TestablePlayableSprite restoredPlayer = player("new-sonic");
        harness.setPlayers(restoredPlayer, List.of());
        rewindRegistry.restore(snapshot);

        ObjectInstance restored = onlyLive(objectManager, loadMadmoleSideDrillClass());
        assertNotSame(source, restored, "restore must recreate the Madmole side drill");
        assertSame(restoredPlayer, readObjectField(restored, "capturedPlayer"),
                "capturedPlayer must resolve through the current restore identity table");
        assertEquals(0x380, readIntField(restored, "xVelocity"),
                "side-drill motion must restore with the player ref");
        assertEquals(9, readIntField(restored, "mappingFrame"),
                "side-drill animation state must restore with the player ref");
    }

    @Test
    void playerReferenceObjectsUseGenericRecreateWithoutExplicitDynamicCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(GrabberBadnikInstance.class),
                "S2 Grabber must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(MhzMushroomParachuteObjectInstance.class),
                "MHZ mushroom parachute must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(MhzStickyVineObjectInstance.class),
                "MHZ sticky vine must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(loadMadmoleSideDrillClass()),
                "Madmole side drill must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(GrabberBadnikInstance.class.getName()),
                "S2 Grabber must not keep an explicit dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        MhzMushroomParachuteObjectInstance.class.getName()),
                "MHZ mushroom parachute must not keep an explicit dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzStickyVineObjectInstance.class.getName()),
                "MHZ sticky vine must not keep an explicit dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MADMOLE_SIDE_DRILL_CLASS),
                "Madmole side drill must not keep an explicit dynamic codec");
    }

    @Test
    void missingPlayerReferencesStillFailLoudlyOnRestore() throws Exception {
        TestablePlayableSprite capturedPlayer = player("old-sonic");
        Harness harness = Harness.create(capturedPlayer, List.of());
        GrabberBadnikInstance grabber = harness.objectManager().createDynamicObject(
                () -> new GrabberBadnikInstance(GRABBER_SPAWN));
        MhzStickyVineObjectInstance stickyVine = harness.objectManager().createDynamicObject(
                () -> new MhzStickyVineObjectInstance(STICKY_VINE_SPAWN));
        writeObjectField(grabber, "pendingGrabPlayer", capturedPlayer);
        writeObjectField(stickyVine, "capturedPlayer", capturedPlayer);
        RewindRegistry rewindRegistry = registryFor(harness.objectManager());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        harness.setPlayers(null, List.of());
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> rewindRegistry.restore(snapshot));
        assertTrue(thrown.getMessage().contains("Missing required player reference"),
                "missing live player identity must fail loudly");
    }

    private static final class Harness {
        private final ObjectManager objectManager;
        private final TestCamera camera;
        private final MutableServices services;

        private Harness(ObjectManager objectManager, TestCamera camera, MutableServices services) {
            this.objectManager = objectManager;
            this.camera = camera;
            this.services = services;
        }

        static Harness create(AbstractPlayableSprite focusedPlayer, List<? extends PlayableEntity> sidekicks) {
            TestCamera camera = new TestCamera();
            camera.setFocusedSprite(focusedPlayer);
            MutableServices services = new MutableServices(camera, List.copyOf(sidekicks));
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    null,
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            services.objectManager = objectManager;
            objectManager.reset(camera.getX());
            return new Harness(objectManager, camera, services);
        }

        ObjectManager objectManager() {
            return objectManager;
        }

        void setPlayers(AbstractPlayableSprite focusedPlayer, List<? extends PlayableEntity> sidekicks) {
            camera.setFocusedSprite(focusedPlayer);
            services.sidekicks = List.copyOf(sidekicks);
        }
    }

    private static final class MutableServices extends StubObjectServices {
        private ObjectManager objectManager;
        private final Camera camera;
        private List<? extends PlayableEntity> sidekicks;

        private MutableServices(Camera camera, List<? extends PlayableEntity> sidekicks) {
            this.camera = camera;
            this.sidekicks = sidekicks;
        }

        @Override public ObjectManager objectManager() { return objectManager; }
        @Override public Camera camera() { return camera; }
        @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
        @Override public List<PlayableEntity> sidekicks() { return List.copyOf(sidekicks); }
    }

    private static final class TestCamera extends Camera {
        private AbstractPlayableSprite focusedSprite;

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return 0x3000; }
        @Override public short getY() { return 0x0300; }
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
        return new TestablePlayableSprite(code, (short) 0x3200, (short) 0x0340);
    }

    private static ObjectInstance instantiateMadmoleSideDrill(int x, int y, boolean facingLeft) {
        try {
            Class<? extends ObjectInstance> type = loadMadmoleSideDrillClass();
            Constructor<?> ctor = type.getDeclaredConstructor(int.class, int.class, boolean.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(x, y, facingLeft);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct Madmole side-drill child", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends ObjectInstance> loadMadmoleSideDrillClass() {
        try {
            return (Class<? extends ObjectInstance>) Class.forName(MADMOLE_SIDE_DRILL_CLASS);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        return field(target, name).getInt(target);
    }

    private static boolean readBooleanField(Object target, String name) throws Exception {
        return field(target, name).getBoolean(target);
    }

    private static void writeObjectField(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }

    private static void writeIntField(Object target, String name, int value) throws Exception {
        field(target, name).setInt(target, value);
    }

    private static void writeBooleanField(Object target, String name, boolean value) throws Exception {
        field(target, name).setBoolean(target, value);
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
