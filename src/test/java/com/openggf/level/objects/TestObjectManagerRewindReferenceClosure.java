package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericRewindEligibility;
import com.openggf.game.rewind.RewindStateful;
import com.openggf.game.rewind.schema.RewindSchemaRegistry;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GLCommand;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestObjectManagerRewindReferenceClosure {
    private static final ObjectSpawn DYNAMIC_SPAWN = spawn(0x180, 0x120);

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        RewindSchemaRegistry.clearForTest();
    }

    @AfterEach
    void tearDown() {
        RewindSchemaRegistry.clearForTest();
        SessionManager.clear();
    }

    @Test
    void rejectsUnmanagedReferenceFromPlacedOwner() {
        ObjectSpawn ownerSpawn = spawn(0x100, 0x120);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = makeManager(List.of(ownerSpawn), registry);
        materialize(manager);
        registry.instances.get(ownerSpawn).target = new CompactOwner(DYNAMIC_SPAWN, null);

        assertThrows(IllegalStateException.class, manager::validateRewindReferenceClosure);
    }

    @Test
    void acceptsRegisteredReferencesFromPlacedAndDynamicOwners() {
        ObjectSpawn ownerSpawn = spawn(0x100, 0x120);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = makeManager(List.of(ownerSpawn), registry);
        materialize(manager);
        CompactOwner target = new CompactOwner(DYNAMIC_SPAWN, null);
        manager.addDynamicObject(target);
        registry.instances.get(ownerSpawn).target = target;
        manager.addDynamicObject(new CompactOwner(spawn(0x190, 0x120), target));

        assertDoesNotThrow(manager::validateRewindReferenceClosure);
    }

    @Test
    void ignoresOwnerWithCustomCapture() {
        ObjectManager manager = makeManager(List.of(), new TrackingRegistry());
        manager.addDynamicObject(new CustomCaptureOwner(
                DYNAMIC_SPAWN, new CompactOwner(spawn(0x200, 0x120), null)));

        assertDoesNotThrow(manager::validateRewindReferenceClosure);
    }

    @Test
    void ignoresAuxiliaryOwner() {
        ObjectManager manager = makeManager(List.of(), new TrackingRegistry());
        manager.addAuxiliaryDynamicObject(new CompactOwner(
                DYNAMIC_SPAWN, new CompactOwner(spawn(0x200, 0x120), null)));

        assertDoesNotThrow(manager::validateRewindReferenceClosure);
    }

    @Test
    void rejectsReferenceToAuxiliaryOnlyTarget() {
        ObjectManager manager = makeManager(List.of(), new TrackingRegistry());
        CompactOwner auxiliaryTarget = new CompactOwner(DYNAMIC_SPAWN, null);
        manager.addAuxiliaryDynamicObject(auxiliaryTarget);
        manager.addDynamicObject(new CompactOwner(spawn(0x190, 0x120), auxiliaryTarget));

        assertThrows(IllegalStateException.class, manager::validateRewindReferenceClosure);
    }

    @Test
    void compactUnsupportedOwnerUsesRealGenericSnapshotFallback() {
        ObjectManager manager = makeManager(List.of(), new TrackingRegistry());
        UnsupportedCompactOwner owner = new UnsupportedCompactOwner(DYNAMIC_SPAWN);
        manager.addDynamicObject(owner);

        assertFalse(GenericRewindEligibility.usesCompactDefaultSubclassCapture(owner.getClass()));
        assertDoesNotThrow(manager::validateRewindReferenceClosure);
        ObjectManagerSnapshot snapshot = manager.rewindSnapshottable().capture();
        var state = snapshot.dynamicObjects().get(0).state();
        assertNotNull(state.genericState());
        assertNull(state.compactGenericState());
    }

    @Test
    void sharedPredicateMatchesNormalAndBadnikCompactRoutes() {
        assertTrue(GenericRewindEligibility.usesCompactDefaultSubclassCapture(CompactOwner.class));
        assertTrue(GenericRewindEligibility.usesCompactDefaultSubclassCapture(CompactBadnik.class));
        assertFalse(GenericRewindEligibility.usesCompactDefaultSubclassCapture(CustomCaptureOwner.class));
        assertFalse(GenericRewindEligibility.usesCompactDefaultSubclassCapture(UnsupportedCompactOwner.class));
    }

    @Test
    void validatesDefaultBadnikReferenceClosure() {
        ObjectManager manager = makeManager(List.of(), new TrackingRegistry());
        manager.addDynamicObject(new CompactBadnik(
                DYNAMIC_SPAWN, new CompactOwner(spawn(0x200, 0x120), null)));

        assertThrows(IllegalStateException.class, manager::validateRewindReferenceClosure);
    }

    private static void materialize(ObjectManager manager) {
        manager.reset(0);
        manager.update(0, null, null, 1);
    }

    private static ObjectManager makeManager(List<ObjectSpawn> spawns, TrackingRegistry registry) {
        return new ObjectManager(spawns, registry, 0, null, null);
    }

    private static ObjectSpawn spawn(int x, int y) {
        return new ObjectSpawn(x, y, 0x01, 0, 0, false, 0);
    }

    private static class CompactOwner extends AbstractObjectInstance {
        ObjectInstance target;
        int counter = 3;

        CompactOwner(ObjectSpawn spawn, ObjectInstance target) {
            super(spawn, "CompactOwner");
            this.target = target;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class CustomCaptureOwner extends CompactOwner {
        CustomCaptureOwner(ObjectSpawn spawn, ObjectInstance target) {
            super(spawn, target);
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return super.captureRewindState();
        }
    }

    private static final class UnsupportedCompactOwner extends AbstractObjectInstance {
        MutableFixtureValue state = new MutableFixtureValue();

        UnsupportedCompactOwner(ObjectSpawn spawn) {
            super(spawn, "Unsupported");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class MutableFixtureValue implements RewindStateful<Integer> {
        int value = 9;

        @Override
        public Integer captureRewindStateValue() {
            return value;
        }

        @Override
        public void restoreRewindStateValue(Integer state) {
            value = state;
        }
    }

    private static final class CompactBadnik extends AbstractBadnikInstance {
        ObjectInstance target;
        int counter = 4;

        CompactBadnik(ObjectSpawn spawn, ObjectInstance target) {
            super(spawn, "CompactBadnik");
            this.target = target;
        }

        @Override
        protected void updateMovement(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        protected int getCollisionSizeIndex() {
            return 0;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class TrackingRegistry implements ObjectRegistry {
        final Map<ObjectSpawn, CompactOwner> instances = new IdentityHashMap<>();

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            CompactOwner owner = new CompactOwner(spawn, null);
            instances.put(spawn, owner);
            return owner;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "CompactOwner";
        }

        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_2;
        }
    }
}
