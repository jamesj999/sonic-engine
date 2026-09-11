package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance;
import com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance;
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

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kIczEndBossGraphRewind {
    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x4400, 0x0420, Sonic3kObjectIds.ICZ_END_BOSS, 0, 0, false, 70);
    private static final ObjectSpawn EMITTER_SPAWN =
            new ObjectSpawn(0x4380, 0x02F0, Sonic3kObjectIds.ICZ_SNOW_PILE, 0x18, 0, false, 71);
    private static final String SNOWDUST_PARTICLE_CLASS =
            "com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance$SnowdustParticle";
    private static final String DEFEAT_DEBRIS_CLASS =
            "com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance$IczEndBossDefeatDebrisChild";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void iczEndBossAndPlacedSnowdustEmitterRestoreIndependentlyWithBossScalars() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        IczEndBossInstance sourceBoss = objectManager.createDynamicObject(
                () -> new IczEndBossInstance(BOSS_SPAWN));
        IczSnowPileObjectInstance sourceEmitter = objectManager.createDynamicObject(
                () -> new IczSnowPileObjectInstance(EMITTER_SPAWN));
        writeBooleanField(sourceBoss, "arenaGateInitialized", true);
        writeBooleanField(sourceBoss, "arenaGateComplete", true);
        writeIntField(sourceBoss, "routineTimer", 37);
        writeIntField(sourceBoss, "swingTimer", 19);

        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        ObjectRefId emitterId = objectId(objectManager, sourceEmitter);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceEmitter);
        IczSnowPileObjectInstance divergentEmitter = objectManager.createDynamicObject(
                () -> new IczSnowPileObjectInstance(new ObjectSpawn(
                        0x4500, 0x0310, Sonic3kObjectIds.ICZ_SNOW_PILE, 0x18, 0, false, 72)));
        assertEquals(1, liveObjects(objectManager, IczSnowPileObjectInstance.class).size(),
                "diverge step should leave one unrelated snowdust emitter before restore");

        rewindRegistry.restore(snapshot);

        IczEndBossInstance restoredBoss = objectById(objectManager, IczEndBossInstance.class, bossId);
        IczSnowPileObjectInstance restoredEmitter =
                objectById(objectManager, IczSnowPileObjectInstance.class, emitterId);
        assertEquals(1, liveObjects(objectManager, IczEndBossInstance.class).size(),
                "restore must keep exactly one captured ICZ end boss");
        assertEquals(1, liveObjects(objectManager, IczSnowPileObjectInstance.class).size(),
                "restore must keep exactly one captured snowdust emitter");
        assertNotSame(sourceBoss, restoredBoss, "restore must recreate the ICZ end boss");
        assertNotSame(sourceEmitter, restoredEmitter, "restore must recreate the snowdust emitter");
        assertNotSame(divergentEmitter, restoredEmitter, "restore must drop the divergent emitter");
        assertEquals(BOSS_SPAWN.x(), restoredBoss.getX(),
                "spawn-derived boss x coordinate must be rebuilt by recreate");
        assertEquals(BOSS_SPAWN.y(), restoredBoss.getY(),
                "spawn-derived boss y coordinate must be rebuilt by recreate");
        assertTrue(readBooleanField(restoredBoss, "arenaGateInitialized"),
                "arenaGateInitialized flag must restore from compact state");
        assertTrue(readBooleanField(restoredBoss, "arenaGateComplete"),
                "arenaGateComplete flag must restore from compact state");
        assertEquals(37, readIntField(restoredBoss, "routineTimer"),
                "routine timer must restore from compact state");
        assertEquals(19, readIntField(restoredBoss, "swingTimer"),
                "swing timer must restore from compact state");
    }

    @Test
    void iczEndBossRestoresQueuedExtendedSidekickFrostCapture() throws Exception {
        AbstractPlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x4400, (short) 0x0420);
        AbstractPlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x4410, (short) 0x0420);
        AbstractPlayableSprite extension = new TestablePlayableSprite("tails", (short) 0x4420, (short) 0x0420);
        Harness harness = Harness.create(main, List.of(nativeP2, extension));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        IczEndBossInstance sourceBoss = objectManager.createDynamicObject(
                () -> new IczEndBossInstance(BOSS_SPAWN));
        Map<AbstractPlayableSprite, int[]> sourceCaptures = extendedFrostCaptures(sourceBoss);
        sourceCaptures.put(extension, new int[]{2, 0x4410, 4});

        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceBoss);
        objectManager.createDynamicObject(() -> new IczEndBossInstance(new ObjectSpawn(
                0x4500, 0x0420, Sonic3kObjectIds.ICZ_END_BOSS, 0, 0, false, 73)));
        rewindRegistry.restore(snapshot);

        IczEndBossInstance restoredBoss = objectById(objectManager, IczEndBossInstance.class, bossId);
        Map<AbstractPlayableSprite, int[]> restoredCaptures = extendedFrostCaptures(restoredBoss);
        assertEquals(1, restoredCaptures.size());
        assertTrue(restoredCaptures.containsKey(extension),
                "queued capture must relink to the live playable identity");
        assertTrue(java.util.Arrays.equals(new int[]{2, 0x4410, 4}, restoredCaptures.get(extension)),
                "capture phase, source x, and source slot must survive rewind");
    }

    @Test
    void iczEndBossUsesGenericRecreateWithoutExplicitS3kCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczEndBossInstance.class),
                "ICZ end boss must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(IczEndBossInstance.class.getName()),
                "ICZ end boss must not keep an explicit S3K dynamic codec");
    }

    @Test
    void iczSnowdustParticleRestoresFreshAndExpiresAgainstRestoredEmitter() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        IczSnowPileObjectInstance sourceEmitter = objectManager.createDynamicObject(
                () -> new IczSnowPileObjectInstance(EMITTER_SPAWN));
        objectManager.update(harness.services().camera().getX(), null, List.of(), 0);

        Class<?> particleClass = Class.forName(SNOWDUST_PARTICLE_CLASS);
        ObjectInstance sourceParticle = onlyLiveObject(objectManager, particleClass);
        ObjectRefId emitterId = objectId(objectManager, sourceEmitter);
        ObjectRefId particleId = objectId(objectManager, sourceParticle);
        assertEquals(1, readIntField(sourceEmitter, "activeSnowdustCount"),
                "source emitter should count its spawned particle before capture");

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceParticle);
        objectManager.removeDynamicObject(sourceEmitter);
        IczSnowPileObjectInstance divergentEmitter = objectManager.createDynamicObject(
                () -> new IczSnowPileObjectInstance(new ObjectSpawn(
                        0x4500, 0x02F0, Sonic3kObjectIds.ICZ_SNOW_PILE, 0x18, 0, false, 72)));
        objectManager.update(harness.services().camera().getX(), null, List.of(), 1);
        assertEquals(1, liveObjects(objectManager, IczSnowPileObjectInstance.class).size(),
                "diverge step should leave one unrelated emitter before restore");

        rewindRegistry.restore(snapshot);

        IczSnowPileObjectInstance restoredEmitter =
                objectById(objectManager, IczSnowPileObjectInstance.class, emitterId);
        ObjectInstance restoredParticle = objectByIdOfType(objectManager, particleClass, particleId);
        assertEquals(1, liveObjects(objectManager, IczSnowPileObjectInstance.class).size(),
                "restore must keep exactly one captured snowdust emitter");
        assertEquals(1, liveObjectsOfType(objectManager, particleClass).size(),
                "restore must keep exactly one captured snowdust particle");
        assertNotSame(sourceEmitter, restoredEmitter, "restore must recreate the snowdust emitter");
        assertNotSame(sourceParticle, restoredParticle, "restore must recreate the snowdust particle");
        assertNotSame(divergentEmitter, restoredEmitter, "restore must drop the divergent emitter");
        assertSame(restoredEmitter, readObjectField(restoredParticle, "parent"),
                "snowdust particle must call back into the restored emitter");
        assertNotSame(sourceEmitter, readObjectField(restoredParticle, "parent"),
                "snowdust particle must not retain the stale pre-restore emitter");

        writeBooleanField(restoredParticle, "enteredScreen", true);
        writeIntField(readObjectField(restoredParticle, "motion"), "x", -0x1000);
        restoredParticle.update(2, null);
        assertTrue(restoredParticle.isDestroyed(), "offscreen restored particle should expire");
        assertEquals(0, readIntField(restoredEmitter, "activeSnowdustCount"),
                "particle expiry must decrement the restored emitter, not the stale source");
    }

    @Test
    void iczEndBossDefeatDebrisRestoresFreshAndPreservesDebrisState() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        ObjectInstance sourceDebris = objectManager.createDynamicObject(
                () -> constructDefeatDebris(0x4420, 0x0390, 0x0180, -0x0300, 3, true));
        writeIntField(sourceDebris, "gravity", 0x38);
        writeBooleanField(sourceDebris, "visible", false);

        ObjectRefId debrisId = objectId(objectManager, sourceDebris);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceDebris);
        ObjectInstance divergentDebris = objectManager.createDynamicObject(
                () -> constructDefeatDebris(0x4520, 0x0310, 0, 0, 0, false));
        assertEquals(1, liveObjectsOfType(objectManager, Class.forName(DEFEAT_DEBRIS_CLASS)).size(),
                "diverge step should leave one unrelated ICZ defeat debris before restore");

        rewindRegistry.restore(snapshot);

        ObjectInstance restoredDebris =
                objectByIdOfType(objectManager, Class.forName(DEFEAT_DEBRIS_CLASS), debrisId);
        assertEquals(1, liveObjectsOfType(objectManager, Class.forName(DEFEAT_DEBRIS_CLASS)).size(),
                "restore must keep exactly the captured ICZ defeat debris");
        assertNotSame(sourceDebris, restoredDebris, "restore must recreate ICZ defeat debris");
        assertNotSame(divergentDebris, restoredDebris, "restore must drop divergent ICZ defeat debris");
        assertEquals(0x4420, restoredDebris.getSpawn().x());
        assertEquals(0x0390, restoredDebris.getSpawn().y());
        assertEquals(3, readIntField(restoredDebris, "frame"));
        assertTrue(readBooleanField(restoredDebris, "flipX"));
        assertFalse(readBooleanField(restoredDebris, "visible"));
        assertEquals(0x0180, readIntField(readObjectField(restoredDebris, "motionState"), "xVel"));
        assertEquals(-0x0300, readIntField(readObjectField(restoredDebris, "motionState"), "yVel"));
        assertEquals(0x38, readIntField(restoredDebris, "gravity"));
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            return create(null);
        }

        static Harness create(AbstractPlayableSprite focusedPlayer) {
            return create(focusedPlayer, List.of());
        }

        static Harness create(AbstractPlayableSprite focusedPlayer, List<PlayableEntity> sidekicks) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            camera.setFocusedSprite(focusedPlayer);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public List<PlayableEntity> sidekicks() { return sidekicks; }
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
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
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
        return liveObjects(objectManager, type).stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static ObjectInstance objectByIdOfType(
            ObjectManager objectManager,
            Class<?> type,
            ObjectRefId id) {
        return liveObjectsOfType(objectManager, type).stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(ObjectInstance::getX))
                .toList();
    }

    private static List<ObjectInstance> liveObjectsOfType(ObjectManager objectManager, Class<?> type) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(ObjectInstance::getX))
                .toList();
    }

    private static ObjectInstance onlyLiveObject(ObjectManager objectManager, Class<?> type) {
        List<ObjectInstance> objects = liveObjectsOfType(objectManager, type);
        assertEquals(1, objects.size(), "expected exactly one live " + type.getName());
        return objects.getFirst();
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    @SuppressWarnings("unchecked")
    private static Map<AbstractPlayableSprite, int[]> extendedFrostCaptures(IczEndBossInstance boss)
            throws Exception {
        return (Map<AbstractPlayableSprite, int[]>) readObjectField(boss, "extendedFrostCaptures");
    }

    private static ObjectInstance constructDefeatDebris(
            int x, int y, int xVel, int yVel, int frame, boolean flipX) {
        try {
            Class<?> cls = Class.forName(DEFEAT_DEBRIS_CLASS);
            Constructor<?> ctor = cls.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, boolean.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(x, y, xVel, yVel, frame, flipX);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct ICZ defeat debris", e);
        }
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

    private static Camera mockCamera() {
        return new Camera() {
            private AbstractPlayableSprite focusedSprite;

            @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
            @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
            @Override public short getX() { return 0x4380; }
            @Override public short getY() { return 0x02F8; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
