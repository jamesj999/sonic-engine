package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.objects.CnzBarberPoleObjectInstance;
import com.openggf.game.sonic3k.objects.CnzGiantWheelInstance;
import com.openggf.game.sonic3k.objects.CnzVacuumTubeInstance;
import com.openggf.game.sonic3k.objects.CnzWireCageObjectInstance;
import com.openggf.game.sonic3k.objects.HCZSpinningColumnObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for CNZ/HCZ per-player rider state that silently failed to
 * ride rewind keyframes. Each of these classes also has plain scalar fields, so
 * {@code supportsDefaultObjectSubclassScalars} already returned {@code true} and
 * the class stayed on the compact path — but the identity-keyed rider map (or the
 * player-referencing rider array) was skipped from the compact captured set with
 * no explicit {@code CAPTURED} policy, so a mid-ride rewind restore recreated the
 * object with empty rider state while the captured player came back
 * control-locked / object-controlled. Each field now carries cross-frame gameplay
 * state (ride position accumulators, capture latches, lift countdowns, twist
 * angles), so the fix is an explicit {@code CAPTURED} policy per field.
 */
class TestS3kCnzHczRiderRewindMaps {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cnzBarberPoleRestoresLatchedRiderTrackState() throws Exception {
        Harness harness = Harness.create(player("sonic"));
        AbstractPlayableSprite player = harness.player();
        CnzBarberPoleObjectInstance source = create(harness,
                () -> new CnzBarberPoleObjectInstance(new ObjectSpawn(0x1400, 0x0400, 0x4D, 0, 0, false, 0, 91)));

        Object rider = newInstance("com.openggf.game.sonic3k.objects.CnzBarberPoleObjectInstance$RiderState");
        setField(rider, "latched", true);
        setField(rider, "trackFixed", 0x00A5_1234L);
        setField(rider, "innerTrack", true);
        setField(rider, "lastBranch", "normal");
        setField(rider, "lastTrackPosition", 0xA5);
        putRider(source, "riders", player, rider);
        Map<String, Object> expected = scalars(rider,
                "latched", "trackFixed", "innerTrack", "lastBranch", "lastTrackPosition");

        CnzBarberPoleObjectInstance restored = captureDivergeRestore(harness, source,
                () -> new CnzBarberPoleObjectInstance(new ObjectSpawn(0x1500, 0x0500, 0x4D, 0, 0, false, 0, 92)),
                CnzBarberPoleObjectInstance.class);

        Map<AbstractPlayableSprite, Object> riders = mapField(restored, "riders");
        assertEquals(1, riders.size(), "restored pole must carry the captured rider");
        Object restoredRider = riders.get(player);
        assertTrue(restoredRider != null, "captured rider must relink to the live player key");
        assertEquals(expected, scalars(restoredRider,
                "latched", "trackFixed", "innerTrack", "lastBranch", "lastTrackPosition"),
                "restored rider must replay the latch + track accumulator state");
    }

    @Test
    void cnzGiantWheelRestoresAttachedPlayerLatch() throws Exception {
        Harness harness = Harness.create(player("sonic"));
        AbstractPlayableSprite player = harness.player();
        CnzGiantWheelInstance source = create(harness,
                () -> new CnzGiantWheelInstance(new ObjectSpawn(0x1400, 0x0400, 0x49, 0, 0, false, 0, 93)));

        mapField(source, "attachedPlayers").put(player, Boolean.TRUE);

        CnzGiantWheelInstance restored = captureDivergeRestore(harness, source,
                () -> new CnzGiantWheelInstance(new ObjectSpawn(0x1500, 0x0500, 0x49, 0, 0, false, 0, 94)),
                CnzGiantWheelInstance.class);

        Map<AbstractPlayableSprite, Object> attached = mapField(restored, "attachedPlayers");
        assertEquals(1, attached.size(), "restored wheel must carry the attach latch");
        assertEquals(Boolean.TRUE, attached.get(player),
                "attach latch must survive so the one-time attach setup is not re-run after restore");
    }

    @Test
    void cnzWireCageRestoresLatchedRiderState() throws Exception {
        Harness harness = Harness.create(player("sonic"));
        AbstractPlayableSprite player = harness.player();
        CnzWireCageObjectInstance source = create(harness,
                () -> new CnzWireCageObjectInstance(new ObjectSpawn(0x1400, 0x0400, 0x4A, 0, 0, false, 0, 95)));

        Object cage = newInstance("com.openggf.game.sonic3k.objects.CnzWireCageObjectInstance$CageState");
        setField(cage, "latched", true);
        setField(cage, "phase", 2);
        setField(cage, "rideAngle", 0x40);
        setField(cage, "cooldown", 5);
        setField(cage, "standingBit", 3);
        putRider(source, "riders", player, cage);
        Map<String, Object> expected = scalars(cage,
                "latched", "phase", "rideAngle", "cooldown", "standingBit");

        CnzWireCageObjectInstance restored = captureDivergeRestore(harness, source,
                () -> new CnzWireCageObjectInstance(new ObjectSpawn(0x1500, 0x0500, 0x4A, 0, 0, false, 0, 96)),
                CnzWireCageObjectInstance.class);

        Map<AbstractPlayableSprite, Object> riders = mapField(restored, "riders");
        assertEquals(1, riders.size(), "restored cage must carry the captured rider");
        Object restoredCage = riders.get(player);
        assertTrue(restoredCage != null, "captured cage rider must relink to the live player key");
        assertEquals(expected, scalars(restoredCage,
                "latched", "phase", "rideAngle", "cooldown", "standingBit"),
                "restored cage rider must replay latch/phase/cooldown state");
    }

    @Test
    void cnzVacuumTubeRestoresLiftCountdown() throws Exception {
        Harness harness = Harness.create(player("sonic"));
        AbstractPlayableSprite player = harness.player();
        CnzVacuumTubeInstance source = create(harness,
                () -> new CnzVacuumTubeInstance(new ObjectSpawn(0x1400, 0x0400, 0x46, 3, 0, false, 0, 97)));

        mapField(source, "activeLiftFrames").put(player, 9);

        CnzVacuumTubeInstance restored = captureDivergeRestore(harness, source,
                () -> new CnzVacuumTubeInstance(new ObjectSpawn(0x1500, 0x0500, 0x46, 3, 0, false, 0, 98)),
                CnzVacuumTubeInstance.class);

        Map<AbstractPlayableSprite, Object> lift = mapField(restored, "activeLiftFrames");
        assertEquals(1, lift.size(), "restored tube must carry the lift countdown");
        assertEquals(9, lift.get(player),
                "lift-frame countdown must replay the captured value, not reset the lift");
    }

    @Test
    void hczSpinningColumnRestoresRiderArrayAndPlayerReference() throws Exception {
        Harness harness = Harness.create(player("sonic"));
        AbstractPlayableSprite player = harness.player();
        HCZSpinningColumnObjectInstance source = create(harness,
                () -> new HCZSpinningColumnObjectInstance(new ObjectSpawn(0x1400, 0x0400, 0x00, 0, 0, false, 0, 99)));

        Object rider0 = Array.get(readValue(source, "riders"), 0);
        setField(rider0, "player", player);
        setField(rider0, "active", true);
        setField(rider0, "swingAngle", 0x30);
        setField(rider0, "horizontalDistance", 0x20);
        setField(rider0, "standingLastFrame", true);

        HCZSpinningColumnObjectInstance restored = captureDivergeRestore(harness, source,
                () -> new HCZSpinningColumnObjectInstance(new ObjectSpawn(0x1500, 0x0500, 0x00, 0, 0, false, 0, 100)),
                HCZSpinningColumnObjectInstance.class);

        Object restoredRiders = readValue(restored, "riders");
        assertEquals(2, Array.getLength(restoredRiders), "column keeps its fixed two-slot rider array");
        Object restoredRider0 = Array.get(restoredRiders, 0);
        assertSame(player, readValue(restoredRider0, "player"),
                "captured rider's player reference must resolve back to the live player instance");
        assertEquals(true, readValue(restoredRider0, "active"), "captured rider must stay active after restore");
        assertEquals(0x30, readValue(restoredRider0, "swingAngle"), "twist angle must survive the rewind");
        assertEquals(0x20, readValue(restoredRider0, "horizontalDistance"),
                "horizontal swing distance must survive the rewind");
    }

    // --- shared capture/diverge/restore harness -----------------------------

    private static <T extends ObjectInstance> T create(Harness harness, Supplier<T> factory) {
        return harness.objectManager().createDynamicObject(factory);
    }

    private static <T extends ObjectInstance> T captureDivergeRestore(
            Harness harness, T source, Supplier<T> divergentFactory, Class<T> type) {
        ObjectManager objectManager = harness.objectManager();
        ObjectRefId sourceId = objectId(objectManager, source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        T divergent = objectManager.createDynamicObject(divergentFactory);
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent instance must not alias captured identity");

        registry.restore(snapshot);

        T restored = only(objectManager, type);
        assertNotSame(source, restored, "restore must recreate the removed instance");
        assertNotSame(divergent, restored, "restore must drop the unrelated post-snapshot instance");
        assertEquals(sourceId, objectId(objectManager, restored), "dynamic identity must be preserved");
        return restored;
    }

    @SuppressWarnings("unchecked")
    private static void putRider(Object owner, String fieldName, AbstractPlayableSprite player, Object state) {
        ((Map<AbstractPlayableSprite, Object>) readValue(owner, fieldName)).put(player, state);
    }

    @SuppressWarnings("unchecked")
    private static Map<AbstractPlayableSprite, Object> mapField(Object owner, String fieldName) {
        return (Map<AbstractPlayableSprite, Object>) readValue(owner, fieldName);
    }

    private static Map<String, Object> scalars(Object target, String... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : fields) {
            values.put(field, readValue(target, field));
        }
        return values;
    }

    private static Object newInstance(String className) throws Exception {
        Constructor<?> constructor = Class.forName(className).getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertTrue(id != null, "ObjectManager identity table must register " + object.getClass().getName());
        return id;
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static TestablePlayableSprite player(String code) {
        return new TestablePlayableSprite(code, (short) 0x1200, (short) 0x0400);
    }

    private static Object readValue(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
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

    private static final class Harness {
        private final ObjectManager objectManager;
        private final AbstractPlayableSprite player;

        private Harness(ObjectManager objectManager, AbstractPlayableSprite player) {
            this.objectManager = objectManager;
            this.player = player;
        }

        static Harness create(AbstractPlayableSprite main) {
            TestCamera camera = new TestCamera();
            camera.setFocusedSprite(main);
            MutableServices services = new MutableServices(camera);
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
            objectManager.setRewindInPlaceRestoreEnabledForTest(false);
            return new Harness(objectManager, main);
        }

        ObjectManager objectManager() {
            return objectManager;
        }

        AbstractPlayableSprite player() {
            return player;
        }
    }

    private static final class MutableServices extends StubObjectServices {
        private ObjectManager objectManager;
        private final Camera camera;

        private MutableServices(Camera camera) {
            this.camera = camera;
        }

        @Override public ObjectManager objectManager() { return objectManager; }
        @Override public Camera camera() { return camera; }
        @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
    }

    private static final class TestCamera extends Camera {
        private AbstractPlayableSprite focusedSprite;

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return 0x1000; }
        @Override public short getY() { return 0x0300; }
        @Override public short getWidth() { return 320; }
        @Override public short getHeight() { return 224; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }
}
