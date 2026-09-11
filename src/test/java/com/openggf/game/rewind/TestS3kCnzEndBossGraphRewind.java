package com.openggf.game.rewind;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.CnzCannonInstance;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
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

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestS3kCnzEndBossGraphRewind {
    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x3E40, 0x0240, Sonic3kObjectIds.CNZ_END_BOSS, 0, 0, false, 60);
    private static final ObjectSpawn CANNON_SPAWN =
            new ObjectSpawn(0x3F10, 0x0250, Sonic3kObjectIds.CNZ_CANNON, 0, 0, false, 61);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cnzEndBossRestoresFreshWithRestoredEndCannonReferenceAndScalars() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CnzEndBossInstance sourceBoss = objectManager.createDynamicObject(
                () -> new CnzEndBossInstance(BOSS_SPAWN));
        CnzCannonInstance sourceCannon = objectManager.createDynamicObject(
                () -> new CnzCannonInstance(CANNON_SPAWN));
        writeObjectField(sourceBoss, "endCannon", sourceCannon);
        writeBooleanField(sourceBoss, "cannonSpawned", true);
        writeBooleanField(sourceBoss, "cannonArmed", true);
        writeIntField(sourceBoss, "cannonLaunchTimer", 23);

        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        ObjectRefId cannonId = objectId(objectManager, sourceCannon);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceCannon);
        CnzCannonInstance divergentCannon = objectManager.createDynamicObject(
                () -> new CnzCannonInstance(new ObjectSpawn(
                        0x4000, 0x0270, Sonic3kObjectIds.CNZ_CANNON, 0, 0, false, 62)));
        assertEquals(1, liveObjects(objectManager, CnzCannonInstance.class).size(),
                "diverge step should leave one unrelated cannon before restore");

        rewindRegistry.restore(snapshot);

        CnzEndBossInstance restoredBoss = objectById(objectManager, CnzEndBossInstance.class, bossId);
        CnzCannonInstance restoredCannon = objectById(objectManager, CnzCannonInstance.class, cannonId);
        assertEquals(1, liveObjects(objectManager, CnzEndBossInstance.class).size(),
                "restore must keep exactly one captured CNZ end boss");
        assertEquals(1, liveObjects(objectManager, CnzCannonInstance.class).size(),
                "restore must keep exactly one captured end cannon");
        assertNotSame(sourceBoss, restoredBoss, "restore must recreate the CNZ end boss");
        assertNotSame(sourceCannon, restoredCannon, "restore must recreate the CNZ cannon");
        assertNotSame(divergentCannon, restoredCannon, "restore must drop the divergent cannon");
        assertSame(restoredCannon, readObjectField(restoredBoss, "endCannon"),
                "boss endCannon must resolve to the restored cannon");
        assertNotSame(sourceCannon, readObjectField(restoredBoss, "endCannon"),
                "boss must not retain the stale pre-restore cannon");
        assertEquals(BOSS_SPAWN.x(), restoredBoss.getCentreX(),
                "spawn-derived ROM x_pos must rebuild as the centre coordinate");
        assertEquals(BOSS_SPAWN.y(), restoredBoss.getCentreY(),
                "spawn-derived ROM y_pos must rebuild as the centre coordinate");
        assertTrue(readBooleanField(restoredBoss, "cannonSpawned"),
                "cannonSpawned flag must restore from compact state");
        assertTrue(readBooleanField(restoredBoss, "cannonArmed"),
                "cannonArmed flag must restore from compact state");
        assertEquals(23, readIntField(restoredBoss, "cannonLaunchTimer"),
                "cannon launch timer must restore from compact state");
    }

    @Test
    void cnzEndBossUsesGenericRecreateWithoutExplicitS3kCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzEndBossInstance.class),
                "CNZ end boss must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(CnzEndBossInstance.class.getName()),
                "CNZ end boss must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureFailsWhenCnzEndBossCannonHasNoRewindIdentity() throws Exception {
        Harness harness = Harness.create();
        CnzEndBossInstance boss = harness.objectManager().createDynamicObject(
                () -> new CnzEndBossInstance(BOSS_SPAWN));
        CnzCannonInstance unmanagedCannon = new CnzCannonInstance(CANNON_SPAWN);
        unmanagedCannon.setServices(harness.services());
        writeObjectField(boss, "endCannon", unmanagedCannon);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> registryFor(harness.objectManager()).capture());
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing CNZ end-cannon identity must fail loudly");
    }

    @Test
    void nativeGraphRestoresExactShipHeadMagnetAndArmIdentities() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CnzEndBossInstance sourceBoss = objectManager.createDynamicObject(
                () -> new CnzEndBossInstance(BOSS_SPAWN));

        writeEnumField(sourceBoss, "routine", "INIT");
        step(harness, 1);
        writeEnumField(sourceBoss, "routine", "TRACK");
        writeIntField(sourceBoss, "routineTimer", 0);
        step(harness, 2);

        ObjectInstance sourceShip = onlyBySimpleName(objectManager, "CnzEndBossRobotnikShipChild");
        ObjectInstance sourceHead = onlyBySimpleName(objectManager, "CnzEndBossRobotnikHeadChild");
        ObjectInstance sourceMagnet = onlyBySimpleName(objectManager, "CnzEndBossMagnetChild");
        List<ObjectInstance> sourceArms = liveBySimpleName(objectManager, "CnzEndBossArmChild");
        assertEquals(4, sourceArms.size(), "native routine must allocate all four magnetic arms");
        assertTrue(readBooleanField(sourceMagnet, "dropping"),
                "production TRACK expiry must put the magnet in its non-default dropping state");

        Map<ObjectInstance, ObjectRefId> ids = idsFor(
                objectManager, sourceShip, sourceHead, sourceMagnet,
                sourceArms.get(0), sourceArms.get(1), sourceArms.get(2), sourceArms.get(3));
        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        Map<ObjectRefId, Integer> slots = slotsFor(objectManager, sourceBoss, ids);
        Map<ObjectRefId, Integer> angles = new LinkedHashMap<>();
        for (ObjectInstance arm : sourceArms) {
            angles.put(ids.get(arm), readIntField(arm, "angle"));
        }
        assertEquals(List.of(0, 64, 128, 192), angles.values().stream().sorted().toList(),
                "native graph must contain each quarter-turn arm role exactly once");
        int shipCentreY = readIntField(sourceShip, "centreY");
        int headAnimationTimer = readIntField(sourceHead, "animationTimer");
        int magnetXVelocity = readIntField(sourceMagnet, "xVelocity");

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        registry.restore(snapshot);

        CnzEndBossInstance restoredBoss = objectById(
                objectManager, CnzEndBossInstance.class, bossId);
        assertSlots(objectManager, slots);
        ObjectInstance restoredShip = objectById(objectManager, ids.get(sourceShip));
        ObjectInstance restoredHead = objectById(objectManager, ids.get(sourceHead));
        ObjectInstance restoredMagnet = objectById(objectManager, ids.get(sourceMagnet));
        List<ObjectInstance> restoredArms = liveBySimpleName(objectManager, "CnzEndBossArmChild");

        assertEquals(1, liveBySimpleName(objectManager, "CnzEndBossRobotnikShipChild").size());
        assertEquals(1, liveBySimpleName(objectManager, "CnzEndBossRobotnikHeadChild").size());
        assertEquals(1, liveBySimpleName(objectManager, "CnzEndBossMagnetChild").size());
        assertEquals(4, restoredArms.size(), "restore must preserve the exact four-arm multiplicity");
        assertFresh(sourceShip, restoredShip);
        assertFresh(sourceHead, restoredHead);
        assertFresh(sourceMagnet, restoredMagnet);
        for (ObjectInstance sourceArm : sourceArms) {
            ObjectInstance restoredArm = objectById(objectManager, ids.get(sourceArm));
            assertFresh(sourceArm, restoredArm);
            assertSame(restoredBoss, readObjectField(restoredArm, "boss"));
            assertEquals(angles.get(ids.get(sourceArm)).intValue(), readIntField(restoredArm, "angle"));
        }
        assertSame(restoredBoss, readObjectField(restoredShip, "boss"));
        assertSame(restoredShip, readObjectField(restoredHead, "ship"));
        assertSame(restoredBoss, readObjectField(restoredMagnet, "boss"));
        assertSame(restoredMagnet, readObjectField(restoredBoss, "magnetChild"));
        assertEquals(shipCentreY, readIntField(restoredShip, "centreY"));
        assertEquals(headAnimationTimer, readIntField(restoredHead, "animationTimer"));
        assertEquals(magnetXVelocity, readIntField(restoredMagnet, "xVelocity"));
    }

    @Test
    void chargeGraphRestoresExactFieldIdentitiesAndOffsets() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CnzEndBossInstance sourceBoss = objectManager.createDynamicObject(
                () -> new CnzEndBossInstance(BOSS_SPAWN));
        writeEnumField(sourceBoss, "routine", "INIT");
        step(harness, 1);
        writeEnumField(sourceBoss, "routine", "CHARGE");
        writeIntField(sourceBoss, "routineTimer", 0);
        step(harness, 2);

        List<ObjectInstance> sourceFields = liveBySimpleName(objectManager, "CnzEndBossFieldChild");
        assertEquals(2, sourceFields.size(), "production CHARGE entry must allocate both field lobes");
        Map<ObjectInstance, ObjectRefId> ids = idsFor(objectManager,
                sourceFields.get(0), sourceFields.get(1));
        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        Map<ObjectRefId, Integer> slots = slotsFor(objectManager, sourceBoss, ids);
        Map<ObjectRefId, Integer> offsets = new LinkedHashMap<>();
        Map<ObjectRefId, Integer> frames = new LinkedHashMap<>();
        for (ObjectInstance field : sourceFields) {
            offsets.put(ids.get(field), readIntField(field, "xOffset"));
            frames.put(ids.get(field), readIntField(field, "frame"));
        }
        assertEquals(List.of(-0x0C, 0x0C), offsets.values().stream().sorted().toList(),
                "field graph must retain the signed ChildObjDat offsets");

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        registry.restore(snapshot);

        CnzEndBossInstance restoredBoss = objectById(
                objectManager, CnzEndBossInstance.class, bossId);
        assertSlots(objectManager, slots);
        for (ObjectInstance sourceField : sourceFields) {
            ObjectRefId id = ids.get(sourceField);
            ObjectInstance restoredField = objectById(objectManager, id);
            assertFresh(sourceField, restoredField);
            assertFalse(restoredField.isDestroyed(), "restored field " + id + " must remain live");
            assertSame(restoredBoss, readObjectField(restoredField, "boss"));
            assertEquals(offsets.get(id).intValue(), readIntField(restoredField, "xOffset"));
            assertEquals(frames.get(id).intValue(), readIntField(restoredField, "frame"));
        }
        assertEquals(2, liveBySimpleName(objectManager, "CnzEndBossFieldChild").size());
    }

    @Test
    void defeatGraphRestoresExactExplosionFlameAndBoundaryControllerIdentities() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CnzEndBossInstance sourceBoss = objectManager.createDynamicObject(
                () -> new CnzEndBossInstance(BOSS_SPAWN));
        writeEnumField(sourceBoss, "routine", "INIT");
        step(harness, 1);
        harness.services().gameState().setCurrentBossId(Sonic3kObjectIds.CNZ_END_BOSS);
        writeIntField(sourceBoss, "hitCount", 1);
        sourceBoss.onPlayerAttack(null, null);
        step(harness, 2);
        writeIntField(sourceBoss, "defeatWaitTimer", 0);
        step(harness, 3);
        writeIntField(sourceBoss, "defeatWaitTimer", 0);
        step(harness, 4);
        step(harness, 5);
        sourceBoss.onCapsuleResultsComplete();
        step(harness, 6);

        ObjectInstance sourceShip = onlyBySimpleName(objectManager, "CnzEndBossRobotnikShipChild");
        ObjectInstance sourceExplosion = onlyBySimpleName(
                objectManager, "CnzEndBossExplosionControllerChild");
        ObjectInstance sourceFlame = onlyBySimpleName(objectManager, "CnzEndBossRobotnikFlameChild");
        List<ObjectInstance> sourceBoundaries = liveBySimpleName(
                objectManager, "CnzEndBossBoundaryController");
        assertEquals(3, sourceBoundaries.size(),
                "defeat and post-capsule handoffs must allocate all three gradual boundary controllers");
        assertEquals(0, liveBySimpleName(objectManager, "CnzEndBossMagnetChild").size(),
                "production defeat scatter must remove the magnet before capture");
        assertNull(readObjectField(sourceBoss, "magnetChild"),
                "production defeat scatter must clear the boss magnet slot before capture");

        Map<ObjectInstance, ObjectRefId> ids = new LinkedHashMap<>();
        ids.putAll(idsFor(objectManager, sourceShip, sourceExplosion, sourceFlame));
        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        for (ObjectInstance boundary : sourceBoundaries) {
            ids.put(boundary, objectId(objectManager, boundary));
        }
        Map<ObjectRefId, Integer> slots = slotsFor(objectManager, sourceBoss, ids);
        Map<ObjectRefId, Object> axes = new LinkedHashMap<>();
        Map<ObjectRefId, Integer> targets = new LinkedHashMap<>();
        Map<ObjectRefId, Integer> accumulators = new LinkedHashMap<>();
        for (ObjectInstance boundary : sourceBoundaries) {
            ObjectRefId id = ids.get(boundary);
            axes.put(id, readObjectField(boundary, "axis"));
            targets.put(id, readIntField(boundary, "target"));
            accumulators.put(id, readIntField(boundary, "accumulator"));
            assertTrue(accumulators.get(id) > 0,
                    "live boundary controller must have meaningful in-progress accumulator state");
        }
        assertEquals(List.of("MAX_X_UP:18672", "MAX_X_UP:19056", "MIN_Y_DOWN:512"),
                sourceBoundaries.stream()
                        .map(boundary -> readUnchecked(boundary, "axis") + ":"
                                + readIntUnchecked(boundary, "target"))
                        .sorted()
                        .toList(),
                "defeat graph must contain each native gradual-boundary role exactly once");
        int explosionInterval = readIntField(sourceExplosion, "interval");
        assertTrue(explosionInterval > 0,
                "ship-owned explosion controller must retain its non-default cadence state");
        boolean flameVisible = readBooleanField(sourceFlame, "visible");
        assertTrue(flameVisible, "production escape must put the Robotnik flame in its visible state");

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        registry.restore(snapshot);

        CnzEndBossInstance restoredBoss = objectById(
                objectManager, CnzEndBossInstance.class, bossId);
        assertSlots(objectManager, slots);
        assertNull(readObjectField(restoredBoss, "magnetChild"),
                "unmatched magnet reconstruction candidate must detach from the restored boss");
        ObjectInstance restoredShip = objectById(objectManager, ids.get(sourceShip));
        ObjectInstance restoredExplosion = objectById(objectManager, ids.get(sourceExplosion));
        ObjectInstance restoredFlame = objectById(objectManager, ids.get(sourceFlame));
        for (ObjectInstance sourceBoundary : sourceBoundaries) {
            ObjectRefId id = ids.get(sourceBoundary);
            ObjectInstance restoredBoundary = objectById(objectManager, id);
            assertFresh(sourceBoundary, restoredBoundary);
            assertFalse(restoredBoundary.isDestroyed(), "restored boundary " + id + " must remain live");
            assertEquals(axes.get(id), readObjectField(restoredBoundary, "axis"));
            assertEquals(targets.get(id).intValue(), readIntField(restoredBoundary, "target"));
            assertEquals(accumulators.get(id).intValue(), readIntField(restoredBoundary, "accumulator"));
        }
        assertEquals(1, liveBySimpleName(objectManager, "CnzEndBossExplosionControllerChild").size());
        assertEquals(1, liveBySimpleName(objectManager, "CnzEndBossRobotnikFlameChild").size());
        assertEquals(sourceBoundaries.size(),
                liveBySimpleName(objectManager, "CnzEndBossBoundaryController").size());
        assertFresh(sourceShip, restoredShip);
        assertFresh(sourceExplosion, restoredExplosion);
        assertFresh(sourceFlame, restoredFlame);
        assertSame(restoredBoss, readObjectField(restoredShip, "boss"));
        assertSame(restoredShip, readObjectField(restoredExplosion, "ship"));
        assertSame(restoredShip, readObjectField(restoredFlame, "ship"));
        assertEquals(explosionInterval, readIntField(restoredExplosion, "interval"));
        assertEquals(flameVisible, readBooleanField(restoredFlame, "visible"));

        CompositeSnapshot secondSnapshot = registry.capture();
        registry.restore(secondSnapshot);
        CnzEndBossInstance twiceRestoredBoss = objectById(
                objectManager, CnzEndBossInstance.class, bossId);
        assertSlots(objectManager, slots);
        assertNull(readObjectField(twiceRestoredBoss, "magnetChild"),
                "a second out-of-place restore must retain the absent magnet without a dangling reference");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            camera.setMinY((short) 0x0240);
            camera.setMaxX((short) 0x47E0);
            GameStateManager gameState = new GameStateManager();
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
            AudioManager audioManager = mock(AudioManager.class);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GameStateManager gameState() { return gameState; }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
                @Override public AudioManager audioManager() { return audioManager; }
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

    private static ObjectInstance objectById(ObjectManager objectManager, ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable().idFor(object)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static Map<ObjectInstance, ObjectRefId> idsFor(
            ObjectManager objectManager, ObjectInstance... objects) {
        Map<ObjectInstance, ObjectRefId> ids = new LinkedHashMap<>();
        for (ObjectInstance object : objects) {
            ids.put(object, objectId(objectManager, object));
        }
        return ids;
    }

    private static Map<ObjectRefId, Integer> slotsFor(
            ObjectManager objectManager,
            ObjectInstance owner,
            Map<ObjectInstance, ObjectRefId> graphIds) {
        Map<ObjectRefId, Integer> slots = new LinkedHashMap<>();
        int ownerSlot = slotOf(owner);
        assertTrue(ownerSlot >= 0, "graph owner must occupy a managed SST slot");
        slots.put(objectId(objectManager, owner), ownerSlot);
        graphIds.forEach((object, id) -> {
            int slot = slotOf(object);
            assertTrue(slot >= 0, "graph object " + id + " must occupy a managed SST slot");
            slots.put(id, slot);
        });
        return slots;
    }

    private static void assertSlots(ObjectManager objectManager, Map<ObjectRefId, Integer> expectedSlots) {
        expectedSlots.forEach((id, expectedSlot) -> assertEquals(
                expectedSlot.intValue(),
                slotOf(objectById(objectManager, id)),
                "restored graph object " + id + " must retain its exact SST slot"));
    }

    private static int slotOf(ObjectInstance object) {
        assertTrue(object instanceof AbstractObjectInstance,
                "graph object must expose its managed SST slot: " + object.getClass().getSimpleName());
        return ((AbstractObjectInstance) object).getSlotIndex();
    }

    private static ObjectInstance onlyBySimpleName(ObjectManager objectManager, String simpleName) {
        List<ObjectInstance> matches = liveBySimpleName(objectManager, simpleName);
        assertEquals(1, matches.size(), "expected exactly one live " + simpleName);
        return matches.getFirst();
    }

    private static List<ObjectInstance> liveBySimpleName(ObjectManager objectManager, String simpleName) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getSimpleName().equals(simpleName))
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(ObjectInstance::getX))
                .toList();
    }

    private static void step(Harness harness, int frame) {
        harness.objectManager().update(harness.services().camera().getX(), null, List.of(), frame, false);
    }

    private static void assertFresh(ObjectInstance source, ObjectInstance restored) {
        assertNotSame(source, restored, source.getClass().getSimpleName() + " must restore as a fresh instance");
    }

    private static Object readUnchecked(Object target, String name) {
        try {
            return readObjectField(target, name);
        } catch (Exception exception) {
            throw new AssertionError("cannot read " + name, exception);
        }
    }

    private static int readIntUnchecked(Object target, String name) {
        try {
            return readIntField(target, name);
        } catch (Exception exception) {
            throw new AssertionError("cannot read " + name, exception);
        }
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(ObjectInstance::getX))
                .toList();
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeEnumField(Object target, String name, String constant) throws Exception {
        Field field = field(target, name);
        field.set(target, Enum.valueOf((Class<? extends Enum>) field.getType(), constant));
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
            @Override public short getX() { return 0x3D80; }
            @Override public short getY() { return 0x0200; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
