package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczFreezerObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kIczFreezerGraphRewind {
    private static final ObjectSpawn FREEZER_SPAWN =
            new ObjectSpawn(0x2400, 0x0300, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0x70);
    private static final ObjectSpawn DIVERGENT_FREEZER_SPAWN =
            new ObjectSpawn(0x2480, 0x0300, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0x71);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void iczFreezerGraphRestoresWithoutDropsDoublesOrStaleReferences() {
        TestablePlayableSprite capturedPlayer = player("old-sonic", 0x2410, 0x0340);
        Harness harness = Harness.create(capturedPlayer);
        ObjectManager objectManager = harness.objectManager();
        IczFreezerObjectInstance sourceFreezer = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance(FREEZER_SPAWN));
        IczFreezerObjectInstance.CaptureCloud sourceCloud = objectManager.createDynamicObject(
                () -> sourceFreezer.createCaptureCloudForTesting(0x2400, 0x0330, false));
        IczFreezerObjectInstance.FrozenPlayerBlock sourceBlock = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance.FrozenPlayerBlock(
                        capturedPlayer, 0x2410, 0x0340, sourceFreezer.getX(), false));
        wireGraph(sourceFreezer, sourceCloud, sourceBlock);
        writeBooleanField(sourceFreezer, "frostCycleActive", true);
        writeBooleanField(sourceFreezer, "freezeJetActive", true);
        writeIntField(sourceFreezer, "phaseTimer", 19);
        writeIntField(sourceCloud, "captureDelay", 7);
        writeBooleanField(sourceCloud, "offPhase", true);
        writeIntField(sourceBlock, "breakTimer", 42);
        writeBooleanField(sourceBlock, "landedOnTerrain", true);

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId freezerId = requireId(captureTable, sourceFreezer);
        ObjectRefId cloudId = requireId(captureTable, sourceCloud);
        ObjectRefId blockId = requireId(captureTable, sourceBlock);
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceBlock);
        objectManager.removeDynamicObject(sourceCloud);
        objectManager.removeDynamicObject(sourceFreezer);
        TestablePlayableSprite divergentPlayer = player("divergent-sonic", 0x2480, 0x0340);
        IczFreezerObjectInstance divergentFreezer = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance(DIVERGENT_FREEZER_SPAWN));
        IczFreezerObjectInstance.CaptureCloud divergentCloud = objectManager.createDynamicObject(
                () -> divergentFreezer.createCaptureCloudForTesting(0x2480, 0x0330, false));
        IczFreezerObjectInstance.FrozenPlayerBlock divergentBlock = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance.FrozenPlayerBlock(
                        divergentPlayer, 0x2480, 0x0340, divergentFreezer.getX(), false));
        wireGraph(divergentFreezer, divergentCloud, divergentBlock);
        TestablePlayableSprite restoredPlayer = player("new-sonic", 0x2418, 0x0348);
        harness.setPlayer(restoredPlayer);

        registry.restore(snapshot);

        assertEquals(1, countLive(objectManager, IczFreezerObjectInstance.class),
                "restore must leave exactly one live ICZ freezer");
        assertEquals(1, countLive(objectManager, IczFreezerObjectInstance.CaptureCloud.class),
                "restore must leave exactly one live ICZ capture cloud");
        assertEquals(1, countLive(objectManager, IczFreezerObjectInstance.FrozenPlayerBlock.class),
                "restore must leave exactly one live ICZ frozen-player block");
        IczFreezerObjectInstance restoredFreezer =
                objectById(objectManager, IczFreezerObjectInstance.class, freezerId);
        IczFreezerObjectInstance.CaptureCloud restoredCloud =
                objectById(objectManager, IczFreezerObjectInstance.CaptureCloud.class, cloudId);
        IczFreezerObjectInstance.FrozenPlayerBlock restoredBlock =
                objectById(objectManager, IczFreezerObjectInstance.FrozenPlayerBlock.class, blockId);

        assertNotSame(sourceFreezer, restoredFreezer, "freezer must be recreated, not reused stale");
        assertNotSame(sourceCloud, restoredCloud, "capture cloud must be recreated, not reused stale");
        assertNotSame(sourceBlock, restoredBlock, "frozen block must be recreated, not reused stale");
        assertNotSame(divergentFreezer, restoredFreezer, "restore must drop divergent freezer");
        assertNotSame(divergentCloud, restoredCloud, "restore must drop divergent capture cloud");
        assertNotSame(divergentBlock, restoredBlock, "restore must drop divergent frozen block");
        assertNull(readObjectField(restoredFreezer, "lastCaptureCloud"),
                "transient freezer diagnostic handle must not retain a stale cloud identity");
        assertSame(restoredFreezer, readObjectField(restoredCloud, "parent"),
                "capture cloud parent must relink to the restored freezer");
        assertSame(restoredBlock, readObjectField(restoredCloud, "frozenBlock"),
                "capture cloud frozenBlock must relink to the restored block");
        assertSame(restoredPlayer, readObjectField(restoredBlock, "capturedPlayer"),
                "frozen block capturedPlayer must resolve through the current live player identity");
        assertTrue(readBooleanField(restoredFreezer, "frostCycleActive"),
                "freezer frost cycle scalar must restore exactly");
        assertTrue(readBooleanField(restoredFreezer, "freezeJetActive"),
                "freezer jet scalar must restore exactly");
        assertEquals(19, readIntField(restoredFreezer, "phaseTimer"),
                "freezer phase timer must restore exactly");
        assertEquals(7, readIntField(restoredCloud, "captureDelay"),
                "capture cloud delay must restore exactly");
        assertTrue(readBooleanField(restoredCloud, "offPhase"),
                "capture cloud off-phase scalar must restore exactly");
        assertEquals(42, readIntField(restoredBlock, "breakTimer"),
                "frozen block break timer must restore exactly");
        assertTrue(readBooleanField(restoredBlock, "landedOnTerrain"),
                "frozen block terrain scalar must restore exactly");
    }

    @Test
    void iczFreezerGraphUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczFreezerObjectInstance.class),
                "ICZ freezer must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczFreezerObjectInstance.CaptureCloud.class),
                "ICZ capture cloud must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczFreezerObjectInstance.FrozenPlayerBlock.class),
                "ICZ frozen-player block must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(IczFreezerObjectInstance.class.getName()),
                "ICZ freezer must not keep an explicit dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        IczFreezerObjectInstance.CaptureCloud.class.getName()),
                "ICZ capture cloud must not keep an explicit dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        IczFreezerObjectInstance.FrozenPlayerBlock.class.getName()),
                "ICZ frozen-player block must not keep an explicit dynamic codec");
    }

    @Test
    void slotlessExtendedPlayerBlockSurvivesRewindWithPlayerLink() {
        TestablePlayableSprite main = player("sonic", 0x2400, 0x0340);
        TestablePlayableSprite nativeP2 = player("sonic_p2", 0x2408, 0x0340);
        TestablePlayableSprite capturedPlayer = player("sonic_p3", 0x2410, 0x0340);
        capturedPlayer.setCpuControlled(true);
        Harness harness = Harness.create(main, List.of(nativeP2, capturedPlayer));
        ObjectManager objectManager = harness.objectManager();
        IczFreezerObjectInstance.FrozenPlayerBlock sourceBlock =
                new IczFreezerObjectInstance.FrozenPlayerBlock(
                        capturedPlayer, 0x2410, 0x0340, 0x2400, false, false, true);
        objectManager.addRewindableAuxiliaryDynamicObject(sourceBlock);

        assertEquals(-1, sourceBlock.getSlotIndex(),
                "extra-player ice must not consume a native SST slot");
        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId blockId = requireId(captureTable, sourceBlock);
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceBlock);
        registry.restore(snapshot);

        IczFreezerObjectInstance.FrozenPlayerBlock restoredBlock = objectById(
                objectManager, IczFreezerObjectInstance.FrozenPlayerBlock.class, blockId);
        assertEquals(-1, restoredBlock.getSlotIndex());
        assertSame(capturedPlayer, restoredBlock.capturedPlayerForTesting());
        assertTrue(readBooleanField(restoredBlock, "engineSidekickExtension"));

        writeBooleanField(restoredBlock, "landedOnTerrain", true);
        writeIntField(restoredBlock, "breakTimer", 0);
        restoredBlock.update(23822, main);

        List<ObjectInstance> restoredDebris = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getSimpleName().equals("IceDebris"))
                .toList();
        assertEquals(12, restoredDebris.size());
        assertTrue(restoredDebris.stream().allMatch(object ->
                object instanceof AbstractObjectInstance instance && instance.getSlotIndex() == -1));
        assertEquals(12, objectManager.rewindSnapshottable().capture().dynamicObjects().stream()
                .filter(entry -> entry.className().endsWith("$IceDebris"))
                .filter(ObjectManagerSnapshot.DynamicObjectEntry::rewindableAuxiliary)
                .count());
        assertDoesNotThrow(objectManager::validateRewindReferenceClosure);
    }

    @Test
    void capturedIczFreezerGameplayRefsFailLoudlyWhenTargetHasNoRewindIdentity() {
        assertMissingReferenceFails(() -> {
            TestablePlayableSprite capturedPlayer = player("old-sonic", 0x2410, 0x0340);
            Harness harness = Harness.create(capturedPlayer);
            IczFreezerObjectInstance freezer = harness.objectManager().createDynamicObject(
                    () -> new IczFreezerObjectInstance(FREEZER_SPAWN));
            IczFreezerObjectInstance.CaptureCloud cloud = harness.objectManager().createDynamicObject(
                    () -> freezer.createCaptureCloudForTesting(0x2400, 0x0330, false));
            IczFreezerObjectInstance.FrozenPlayerBlock unmanagedBlock =
                    new IczFreezerObjectInstance.FrozenPlayerBlock(
                            capturedPlayer, 0x2410, 0x0340, freezer.getX(), false);
            writeObjectField(cloud, "frozenBlock", unmanagedBlock);
            return harness.objectManager();
        });
    }

    @Test
    void capturedIczFrozenBlockFailsLoudlyWhenPlayerMissingOnRestore() {
        TestablePlayableSprite capturedPlayer = player("old-sonic", 0x2410, 0x0340);
        Harness harness = Harness.create(capturedPlayer);
        ObjectManager objectManager = harness.objectManager();
        IczFreezerObjectInstance sourceFreezer = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance(FREEZER_SPAWN));
        IczFreezerObjectInstance.CaptureCloud sourceCloud = objectManager.createDynamicObject(
                () -> sourceFreezer.createCaptureCloudForTesting(0x2400, 0x0330, false));
        IczFreezerObjectInstance.FrozenPlayerBlock sourceBlock = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance.FrozenPlayerBlock(
                        capturedPlayer, 0x2410, 0x0340, sourceFreezer.getX(), false));
        wireGraph(sourceFreezer, sourceCloud, sourceBlock);
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        harness.setPlayer(null);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> registry.restore(snapshot));
        assertTrue(thrown.getMessage().contains("Missing required player reference"),
                "missing live player identity must fail loudly");
    }

    @Test
    void terminalCaptureCloudRemovalDetachesParentReferenceBeforeClosureValidation() {
        TestablePlayableSprite player = player("sonic", 0x2600, 0x0340);
        Harness harness = Harness.create(player);
        ObjectManager objectManager = harness.objectManager();
        IczFreezerObjectInstance freezer = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance(FREEZER_SPAWN));
        IczFreezerObjectInstance.CaptureCloud cloud = objectManager.createDynamicObject(
                () -> freezer.createCaptureCloudForTesting(0x2400, 0x0330, false));
        writeObjectField(freezer, "lastCaptureCloud", cloud);
        writeBooleanField(cloud, "offPhase", true);
        writeIntField(cloud, "offPhaseDelay", -1);

        stepManager(harness, 1);
        assertTrue(cloud.isDestroyed(), "terminal off-phase must mark the cloud destroyed during update");
        stepManager(harness, 2);

        assertFalse(objectManager.getActiveObjects().contains(cloud),
                "the following ObjectManager pass must remove the destroyed cloud");
        assertNull(freezer.lastCaptureCloudForTesting(),
                "cloud onUnload must detach the parent's captured reference");
        assertDoesNotThrow(objectManager::validateRewindReferenceClosure);
    }

    @Test
    void nullCaptureCloudSnapshotDropsDivergentCloudAndRestoresNullLink() {
        Harness harness = Harness.create(player("sonic", 0x2600, 0x0340));
        ObjectManager objectManager = harness.objectManager();
        IczFreezerObjectInstance freezer = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance(FREEZER_SPAWN));
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        IczFreezerObjectInstance.CaptureCloud divergentCloud = objectManager.createDynamicObject(
                () -> freezer.createCaptureCloudForTesting(0x2400, 0x0330, false));
        writeObjectField(freezer, "lastCaptureCloud", divergentCloud);

        registry.restore(snapshot);

        assertEquals(0, countLive(objectManager, IczFreezerObjectInstance.CaptureCloud.class),
                "restore must remove a cloud absent from the captured graph");
        IczFreezerObjectInstance restoredFreezer = objectManager.getActiveObjects().stream()
                .filter(IczFreezerObjectInstance.class::isInstance)
                .map(IczFreezerObjectInstance.class::cast)
                .findFirst()
                .orElseThrow();
        assertNull(restoredFreezer.lastCaptureCloudForTesting(),
                "captured null graph edge must replace the divergent live edge");
        assertDoesNotThrow(objectManager::validateRewindReferenceClosure);
    }

    @Test
    void unloadingOldCloudCannotDetachNewerParentLink() {
        TestablePlayableSprite player = player("sonic", 0x2410, 0x0340);
        Harness harness = Harness.create(player);
        ObjectManager objectManager = harness.objectManager();
        IczFreezerObjectInstance freezer = objectManager.createDynamicObject(
                () -> new IczFreezerObjectInstance(FREEZER_SPAWN));
        IczFreezerObjectInstance.CaptureCloud oldCloud = objectManager.createDynamicObject(
                () -> freezer.createCaptureCloudForTesting(0x2400, 0x0330, false));
        IczFreezerObjectInstance.CaptureCloud newerCloud = objectManager.createDynamicObject(
                () -> freezer.createCaptureCloudForTesting(0x2400, 0x0330, false));
        writeObjectField(freezer, "lastCaptureCloud", newerCloud);
        writeBooleanField(freezer, "frostCycleActive", true);
        writeBooleanField(freezer, "freezeJetActive", true);
        writeIntField(freezer, "phaseTimer", 10);
        oldCloud.setDestroyed(true);

        stepManager(harness, 1);

        assertFalse(objectManager.getActiveObjects().contains(oldCloud));
        assertSame(newerCloud, freezer.lastCaptureCloudForTesting(),
                "an obsolete child's unload must not clear its replacement edge");
        assertDoesNotThrow(objectManager::validateRewindReferenceClosure);
    }

    private record Harness(ObjectManager objectManager, TestCamera camera, StubObjectServices services) {
        static Harness create(AbstractPlayableSprite focusedPlayer) {
            return create(focusedPlayer, List.of());
        }

        static Harness create(AbstractPlayableSprite focusedPlayer, List<PlayableEntity> sidekicks) {
            ObjectManager[] holder = new ObjectManager[1];
            TestCamera camera = new TestCamera();
            camera.setFocusedSprite(focusedPlayer);
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(camera::getFocusedSprite, () -> sidekicks);
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
                @Override public List<PlayableEntity> sidekicks() { return sidekicks; }
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
            objectManager.setRewindInPlaceRestoreEnabledForTest(false);
            return new Harness(objectManager, camera, services);
        }

        void setPlayer(AbstractPlayableSprite player) {
            camera.setFocusedSprite(player);
        }
    }

    private static final class TestCamera extends Camera {
        private AbstractPlayableSprite focusedSprite;

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return 0x2300; }
        @Override public short getY() { return 0x0280; }
        @Override public short getWidth() { return 320; }
        @Override public short getHeight() { return 224; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }

    @FunctionalInterface
    private interface ObjectManagerFactory {
        ObjectManager create();
    }

    private static void assertMissingReferenceFails(ObjectManagerFactory factory) {
        ObjectManager objectManager = factory.create();
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, registry::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "capturing an unmanaged freezer graph reference must fail loudly");
    }

    private static void wireGraph(
            IczFreezerObjectInstance freezer,
            IczFreezerObjectInstance.CaptureCloud cloud,
            IczFreezerObjectInstance.FrozenPlayerBlock block) {
        writeObjectField(freezer, "lastCaptureCloud", cloud);
        writeObjectField(cloud, "frozenBlock", block);
    }

    private static int countLive(ObjectManager objectManager, Class<?> type) {
        return (int) objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .count();
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object.getClass() == type && id.equals(table.idFor(object))) {
                return type.cast(object);
            }
        }
        throw new AssertionError("No live " + type.getName() + " with id " + id);
    }

    private static ObjectRefId requireId(RewindIdentityTable table, ObjectInstance object) {
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass().getName());
        return id;
    }

    private static TestablePlayableSprite player(String code, int x, int y) {
        return new TestablePlayableSprite(code, (short) x, (short) y);
    }

    private static void stepManager(Harness harness, int frameCounter) {
        harness.objectManager().update(
                harness.camera().getX(),
                harness.camera().getFocusedSprite(),
                List.of(),
                frameCounter,
                false);
    }

    private static Object readObjectField(Object target, String name) {
        try {
            return field(target, name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int readIntField(Object target, String name) {
        try {
            return field(target, name).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean readBooleanField(Object target, String name) {
        try {
            return field(target, name).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeObjectField(Object target, String name, Object value) {
        try {
            field(target, name).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeIntField(Object target, String name, int value) {
        try {
            field(target, name).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeBooleanField(Object target, String name, boolean value) {
        try {
            field(target, name).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
