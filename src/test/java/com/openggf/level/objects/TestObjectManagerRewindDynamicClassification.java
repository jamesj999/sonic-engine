package com.openggf.level.objects;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.sonic2.objects.CheckpointDongleInstance;
import com.openggf.game.sonic2.objects.CheckpointObjectInstance;
import com.openggf.game.sonic2.objects.CheckpointStarInstance;
import com.openggf.game.sonic2.objects.ConveyorObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.badniks.BadnikProjectileInstance;
import com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance;
import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestObjectManagerRewindDynamicClassification {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        GenericRewindEligibility.clearForTest();
        SessionManager.clear();
    }

    @Test
    void rewindRecreatableDynamicObjectCapturesAndRecreatesThroughGenericPath() {
        GenericRewindEligibility.registerForTestOrMigration(TestDynamicObject.class);

        ObjectSpawn spawn = new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0);
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        TestDynamicObject object = new TestDynamicObject(spawn);
        object.phase = 7;
        object.moveTo(0x120, 0x1A0);
        manager.addDynamicObject(object);

        var snapshottable = manager.rewindSnapshottable();
        ObjectManagerSnapshot snapshot = snapshottable.capture();

        assertEquals(1, snapshot.dynamicObjects().size());

        object.phase = 2;
        object.moveTo(0x300, 0x400);
        snapshottable.restore(snapshot);

        TestDynamicObject restored = manager.getActiveObjects().stream()
                .filter(TestDynamicObject.class::isInstance)
                .map(TestDynamicObject.class::cast)
                .findFirst()
                .orElse(null);

        assertNotNull(restored);
        assertEquals(7, restored.phase);
        assertEquals(0x120, restored.getX());
        assertEquals(0x1A0, restored.getY());
    }

    @Test
    void rewindPreservesExplicitAuxiliaryClassificationForSlotlessDynamics() {
        GenericRewindEligibility.registerForTestOrMigration(TestDynamicObject.class);
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        TestDynamicObject auxiliary = new TestDynamicObject(
                new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0));
        manager.addRewindableAuxiliaryDynamicObject(auxiliary);

        ObjectManagerSnapshot snapshot = manager.rewindSnapshottable().capture();
        assertTrue(snapshot.dynamicObjects().getFirst().rewindableAuxiliary());

        manager.rewindSnapshottable().restore(snapshot);

        ObjectManagerSnapshot restored = manager.rewindSnapshottable().capture();
        assertTrue(restored.dynamicObjects().getFirst().rewindableAuxiliary());
    }

    @Test
    void rewindDoesNotReclassifyLogicalSlotlessDynamicAsAuxiliary() {
        GenericRewindEligibility.registerForTestOrMigration(TestDynamicObject.class);
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        manager.spawnLogicalLostRingOverflow(new TestDynamicObject(
                new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0)));

        ObjectManagerSnapshot snapshot = manager.rewindSnapshottable().capture();
        assertFalse(snapshot.dynamicObjects().getFirst().rewindableAuxiliary());

        manager.rewindSnapshottable().restore(snapshot);

        ObjectManagerSnapshot restored = manager.rewindSnapshottable().capture();
        assertFalse(restored.dynamicObjects().getFirst().rewindableAuxiliary());
    }

    @Test
    void destroyedAuxiliaryIsRemovedFromClassificationSet() {
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        TestDynamicObject auxiliary = new TestDynamicObject(
                new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0));
        manager.addRewindableAuxiliaryDynamicObject(auxiliary);
        auxiliary.setDestroyed(true);

        manager.cleanupDestroyedDynamicObjects();

        assertTrue(manager.getActiveObjects().isEmpty());
        assertTrue(manager.rewindSnapshottable().capture().dynamicObjects().isEmpty());
    }

    @Test
    void auxiliaryHonorsNextFrameSpawnContract() {
        ObjectManager[] holder = new ObjectManager[1];
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null, GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;
        DeferredAuxiliarySpawner spawner = new DeferredAuxiliarySpawner(manager);
        manager.addDynamicObject(spawner);

        manager.update(0, null, List.of(), 1, false);
        assertEquals(0, spawner.child.updates);

        manager.update(0, null, List.of(), 2, false);
        assertEquals(1, spawner.child.updates);
    }

    @Test
    void checkpointDynamicChildrenAreRewindRestorable() {
        ObjectSpawn spawn = new ObjectSpawn(0x100, 0x180, 0x79, 0, 0, false, 0);
        CheckpointObjectInstance parent = new CheckpointObjectInstance(spawn, "Checkpoint");
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();

        assertTrue(ObjectManager.isRewindRestorableDynamicObject(new CheckpointDongleInstance(parent), registry));
        assertTrue(ObjectManager.isRewindRestorableDynamicObject(new CheckpointStarInstance(parent, 0x40), registry));
    }

    @Test
    void sonic2BadnikProjectileRestoresThroughGenericRecreate() {
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        ObjectManager manager = new ObjectManager(List.of(), registry, 0, null, null);
        ObjectSpawn spawn = new ObjectSpawn(0x100, 0x180, 0x98, 0, 0, false, 0);
        BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.BUZZER_STINGER,
                0x120,
                0x1A0,
                0x180,
                0x180,
                false,
                false);
        manager.addDynamicObject(projectile);

        ObjectManagerSnapshot snapshot = manager.rewindSnapshottable().capture();
        manager.removeDynamicObject(projectile);
        manager.rewindSnapshottable().restore(snapshot);

        BadnikProjectileInstance restored = manager.getActiveObjects().stream()
                .filter(BadnikProjectileInstance.class::isInstance)
                .map(BadnikProjectileInstance.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(restored);
        assertEquals(0x120, restored.getX());
        assertEquals(0x1A0, restored.getY());
    }

    @Test
    void sonic2BuzzerFlameChildIsClassifiedAsRewindRestorable() throws Exception {
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        ObjectSpawn spawn = new ObjectSpawn(0x100, 0x180, 0x4B, 0, 0, false, 0);
        BuzzerBadnikInstance parent = new BuzzerBadnikInstance(spawn);
        Class<?> childClass = Class.forName(
                "com.openggf.game.sonic2.objects.badniks.BuzzerBadnikInstance$BuzzerFlameChild");
        Constructor<?> ctor = childClass.getDeclaredConstructor(ObjectSpawn.class, BuzzerBadnikInstance.class);
        ctor.setAccessible(true);
        ObjectInstance child = (ObjectInstance) ctor.newInstance(spawn, parent);

        assertTrue(ObjectManager.isRewindRestorableDynamicObject(child, registry));
    }

    @Test
    void sonic2ConveyorCohortRestoresCapturedPlatformPositions() {
        GameServices.camera().resetState();
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        ObjectSpawn parentSpawn = new ObjectSpawn(
                0x0200, 0x0400,
                Sonic2ObjectIds.CONVEYOR,
                0x80,
                0,
                false,
                0x0400);

        final ObjectManager[] managerRef = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return managerRef[0];
            }

            @Override
            public com.openggf.camera.Camera camera() {
                return GameServices.camera();
            }
        };
        ObjectManager manager = new ObjectManager(
                List.of(parentSpawn), registry, 0,
                null, null, null,
                GameServices.camera(),
                services);
        managerRef[0] = manager;
        manager.enableExecThenLoadPlacement();

        manager.update(0, null, List.of(), 1, false);
        for (int frame = 2; frame < 12; frame++) {
            manager.update(0, null, List.of(), frame, false);
        }
        List<ConveyorState> before = conveyorStates(manager);
        assertEquals(8, before.size(), "MTZ conveyor parent should own exactly eight platform children");

        ObjectManagerSnapshot snapshot = manager.rewindSnapshottable().capture();
        for (int frame = 12; frame < 24; frame++) {
            manager.update(0, null, List.of(), frame, false);
        }

        manager.rewindSnapshottable().restore(snapshot);

        assertEquals(before, conveyorStates(manager),
                "rewind restore must preserve every conveyor child platform's captured position");
    }

    @Test
    void dynamicSnapshotHonorsLegacyNoArgSubclassCaptureOverride() {
        ObjectSpawn spawn = new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0);
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        LegacyOverrideDynamicObject object = new LegacyOverrideDynamicObject(spawn);
        object.phase = 9;
        manager.addDynamicObject(object);

        ObjectManagerSnapshot snapshot = manager.rewindSnapshottable().capture();

        assertEquals(1, snapshot.dynamicObjects().size());
        LegacyOverrideExtra extra = assertInstanceOf(
                LegacyOverrideExtra.class,
                snapshot.dynamicObjects().get(0).state().objectSubclassExtra());
        assertEquals(9, extra.phase());
    }

    @Test
    void dynamicRestoreHonorsLegacySingleArgumentSubclassRestoreOverride() {
        ObjectSpawn spawn = new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0);
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        LegacyOverrideDynamicObject object = new LegacyOverrideDynamicObject(spawn);
        object.phase = 9;
        manager.addDynamicObject(object);
        ObjectManagerSnapshot snapshot = manager.rewindSnapshottable().capture();

        object.phase = 2;
        manager.rewindSnapshottable().restore(snapshot);

        LegacyOverrideDynamicObject restored = manager.getActiveObjects().stream()
                .filter(LegacyOverrideDynamicObject.class::isInstance)
                .map(LegacyOverrideDynamicObject.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(restored);
        assertEquals(9, restored.phase);
    }

    @Test
    void dynamicSnapshotHonorsInheritedLegacyBadnikCaptureOverride() {
        ObjectSpawn spawn = new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0);
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        InheritedLegacyBadnik badnik = new InheritedLegacyBadnik(spawn);
        badnik.currentX = 0x140;
        badnik.currentY = 0x1C0;
        manager.addDynamicObject(badnik);

        ObjectManagerSnapshot snapshot = manager.rewindSnapshottable().capture();

        assertEquals(1, snapshot.dynamicObjects().size());
        PerObjectRewindSnapshot.BadnikRewindExtra extra =
                snapshot.dynamicObjects().get(0).state().badnikExtra();
        assertNotNull(extra);
        assertEquals(0x140, extra.currentX());
        assertEquals(0x1C0, extra.currentY());
    }

    @Test
    void shieldDynamicSnapshotTreatsFinalAnimationConfigAsStructural() {
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        manager.addDynamicObject(new ShieldObjectInstance(null));

        assertDoesNotThrow(() -> manager.rewindSnapshottable().capture());
    }

    @Test
    void monitorDynamicSnapshotUsesInheritedMonitorRewindExtraInsteadOfGenericEffectTargetCapture() {
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        manager.addDynamicObject(new InheritedLegacyMonitor(new ObjectSpawn(0x100, 0x180, 0x26, 0, 0, false, 0)));

        ObjectManagerSnapshot snapshot = assertDoesNotThrow(() -> manager.rewindSnapshottable().capture());

        assertEquals(1, snapshot.dynamicObjects().size());
        assertInstanceOf(PerObjectRewindSnapshot.MonitorRewindExtra.class,
                snapshot.dynamicObjects().get(0).state().objectSubclassExtra());
    }

    @Test
    void defaultObjectSnapshotTreatsFinalPrimitiveConstructorConfigAsStructural() {
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        manager.addDynamicObject(new BoxObjectInstance(
                new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0),
                "Box", 8, 8, 1.0f, 0.5f, 0.25f, false));

        assertDoesNotThrow(() -> manager.rewindSnapshottable().capture());
    }

    @Test
    void defaultObjectSnapshotTreatsFinalObjectReferenceCollectionsAsStructural() {
        ObjectManager manager = new ObjectManager(List.of(), null, 0, null, null);
        ObjectSpawn parentSpawn = new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0);
        ObjectSpawn childSpawn = new ObjectSpawn(0x120, 0x180, 0x02, 0, 0, false, 0);
        ObjectReferenceCollectionOwner parent = new ObjectReferenceCollectionOwner(parentSpawn);
        parent.children.add(new TestDynamicObject(childSpawn));
        manager.addDynamicObject(parent);

        assertDoesNotThrow(() -> manager.rewindSnapshottable().capture());
    }

    @Test
    void skidDustDynamicObjectIsRewindRestorable() {
        assertTrue(ObjectManager.isRewindRestorableDynamicObject(
                new SkidDustObjectInstance(0x120, 0x1A0, null, true)));
    }

    private static List<ConveyorState> conveyorStates(ObjectManager manager) {
        return manager.getActiveObjects().stream()
                .filter(ConveyorObjectInstance.class::isInstance)
                .map(ConveyorObjectInstance.class::cast)
                .map(object -> new ConveyorState(
                        object.getSlotIndex(),
                        object.getSpawn().subtype(),
                        object.getX(),
                        object.getY()))
                .sorted()
                .toList();
    }

    private record ConveyorState(int slotIndex, int subtype, int x, int y)
            implements Comparable<ConveyorState> {
        @Override
        public int compareTo(ConveyorState other) {
            int slotCompare = Integer.compare(slotIndex, other.slotIndex);
            if (slotCompare != 0) {
                return slotCompare;
            }
            return Integer.compare(subtype, other.subtype);
        }
    }

    private static final class TestDynamicObject extends AbstractObjectInstance
            implements SpawnRewindRecreatable {
        private int phase;

        TestDynamicObject(ObjectSpawn spawn) {
            super(spawn, "TestDynamicObject");
        }

        void moveTo(int x, int y) {
            updateDynamicSpawn(x, y);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class DeferredAuxiliarySpawner extends AbstractObjectInstance {
        private final ObjectManager manager;
        private DeferredAuxiliaryChild child;

        private DeferredAuxiliarySpawner(ObjectManager manager) {
            super(new ObjectSpawn(0x100, 0x180, 0x01, 0, 0, false, 0), "AuxiliarySpawner");
            this.manager = manager;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (child == null) {
                child = new DeferredAuxiliaryChild();
                manager.addRewindableAuxiliaryDynamicObject(child);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class DeferredAuxiliaryChild extends AbstractObjectInstance {
        private int updates;

        private DeferredAuxiliaryChild() {
            super(new ObjectSpawn(0x100, 0x180, 0x02, 0, 0, false, 0), "AuxiliaryChild");
        }

        @Override
        protected boolean skipsSameFrameUpdateAfterSpawn() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            updates++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class LegacyOverrideDynamicObject extends AbstractObjectInstance
            implements SpawnRewindRecreatable {
        private int phase;

        LegacyOverrideDynamicObject(ObjectSpawn spawn) {
            super(spawn, "LegacyOverrideDynamicObject");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return super.captureRewindState().withObjectSubclassExtra(
                    new LegacyOverrideExtra(phase));
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
            super.restoreRewindState(snapshot);
            if (snapshot.objectSubclassExtra() instanceof LegacyOverrideExtra extra) {
                phase = extra.phase();
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private record LegacyOverrideExtra(int phase)
            implements PerObjectRewindSnapshot.ObjectSubclassRewindExtra {}

    private static final class InheritedLegacyBadnik extends AbstractBadnikInstance {
        InheritedLegacyBadnik(ObjectSpawn spawn) {
            super(spawn, "InheritedLegacyBadnik");
        }

        @Override
        protected void updateMovement(int vIntRunCount, PlayableEntity player) {
            // no-op
        }

        @Override
        protected int getCollisionSizeIndex() {
            return 0;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class InheritedLegacyMonitor extends AbstractMonitorObjectInstance {
        InheritedLegacyMonitor(ObjectSpawn spawn) {
            super(spawn, "InheritedLegacyMonitor");
        }

        @Override
        protected void applyPowerup(PlayableEntity player) {
            // no-op
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class ObjectReferenceCollectionOwner extends AbstractObjectInstance {
        private final ArrayList<TestDynamicObject> children = new ArrayList<>();

        ObjectReferenceCollectionOwner(ObjectSpawn spawn) {
            super(spawn, "ObjectReferenceCollectionOwner");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }
}
