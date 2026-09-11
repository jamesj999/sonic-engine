package com.openggf.level.objects;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.session.EngineContext;
import com.openggf.graphics.GLCommand;
import com.openggf.game.PlayableEntity;
import com.openggf.level.rings.LostRingObjectInstance;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for {@link ObjectManager#rewindSnapshottable()}.
 *
 * <p>Exercises the capture/restore round-trip for placement-managed objects.
 * Uses a minimal {@link ObjectRegistry} that creates simple
 * {@link AbstractObjectInstance} subclasses whose mutable fields can be
 * asserted after restore.
 */
class TestObjectManagerRewindSnapshot {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    // ------------------------------------------------------------------
    // Minimal concrete object class for testing
    // ------------------------------------------------------------------

    /** Minimal subclass with a trackable "moved" position. */
    private static final class TrackableObject extends AbstractObjectInstance implements SolidObjectProvider {
        private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);

        TrackableObject(ObjectSpawn spawn) {
            super(spawn, "TrackableObject");
            updateDynamicSpawn(spawn.x(), spawn.y());
        }

        /** Simulates moving the object to a new position. */
        void moveTo(int x, int y) {
            updateDynamicSpawn(x, y);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }
    }

    private static final class NullSpawnRewindable extends AbstractObjectInstance
            implements RewindRecreatable {
        NullSpawnRewindable() {
            super(null, "NullSpawnRewindable");
        }

        @Override public void appendRenderCommands(List<GLCommand> commands) {}

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext context) {
            return new NullSpawnRewindable();
        }
    }

    private static final class ReservedTouchPublisher extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        ReservedTouchPublisher(ObjectSpawn spawn) {
            super(spawn, "ReservedTouchPublisher");
        }

        @Override public void appendRenderCommands(List<GLCommand> commands) {}
        @Override public int getCollisionFlags() { return 0x47; }
        @Override public int getCollisionProperty() { return 0; }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext context) {
            return new ReservedTouchPublisher(context.spawn());
        }
    }

    // ------------------------------------------------------------------
    // Minimal registry
    // ------------------------------------------------------------------

    private static final class TrackingRegistry implements ObjectRegistry {
        final Map<ObjectSpawn, TrackableObject> instances = new IdentityHashMap<>();
        int createCount;

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            createCount++;
            TrackableObject obj = new TrackableObject(spawn);
            instances.put(spawn, obj);
            return obj;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {}

        @Override
        public String getPrimaryName(int objectId) {
            return "TrackableObject";
        }

        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_2;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ObjectSpawn spawn(int x, int y) {
        return new ObjectSpawn(x, y, 0x01, 0, 0, false, 0);
    }

    private static ObjectManager makeManager(List<ObjectSpawn> spawns, TrackingRegistry registry) {
        return new ObjectManager(spawns, registry, 0, null, null);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void reservedChildTouchPublisherHasIdentityWhenCollisionListIsCaptured() {
        ObjectManager manager = makeManager(List.of(), new TrackingRegistry());
        ObjectSpawn parentSpawn = spawn(100, 200);
        manager.allocateChildSlots(parentSpawn, 1);
        ReservedTouchPublisher child = new ReservedTouchPublisher(spawn(116, 200));

        manager.addDynamicObjectToReservedSlot(child, parentSpawn, 0);
        manager.initialCollisionResponseList().addToCurrentBuild(child);

        assertDoesNotThrow(() -> manager.rewindSnapshottable().capture());
    }

    @Test
    void reservedLostRingTouchPublisherHasIdentityWhenCollisionListIsCaptured() {
        ObjectManager manager = makeManager(List.of(), new TrackingRegistry());
        int slot = manager.allocateDynamicSlot();
        LostRingObjectInstance ring = LostRingObjectInstance.forTest(
                100, 200, 0, 0, 0, 0xFF);

        manager.spawnLostRingObjectAtSlot(ring, slot);
        manager.initialCollisionResponseList().addToCurrentBuild(ring);

        assertDoesNotThrow(() -> manager.rewindSnapshottable().capture());
    }

    @Test
    void activeObjectRepeatedRestoreDoesNotConsumeTheNextDynamicIdentity() {
        ObjectSpawn sp = spawn(100, 200);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = makeManager(List.of(sp), registry);
        manager.reset(0);
        manager.update(0, null, null, 1);
        ObjectManagerSnapshot source = manager.rewindSnapshottable().capture();
        int expectedNextOrdinal = source.dynamicObjectIdCounter();

        manager.rewindSnapshottable().restore(source);
        assertEquals(expectedNextOrdinal,
                manager.rewindSnapshottable().capture().dynamicObjectIdCounter());
        manager.rewindSnapshottable().restore(source);
        assertEquals(expectedNextOrdinal,
                manager.rewindSnapshottable().capture().dynamicObjectIdCounter());

        manager.addDynamicObject(new NullSpawnRewindable());
        ObjectManagerSnapshot afterSpawn = manager.rewindSnapshottable().capture();
        ObjectManagerSnapshot.DynamicObjectEntry next = afterSpawn.dynamicObjects().stream()
                .filter(entry -> entry.className().equals(NullSpawnRewindable.class.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedNextOrdinal, next.objectId().dynamicId());
    }

    @Test
    void captureRestoreRoundTrip_singleObject() {
        ObjectSpawn sp = spawn(100, 200);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = makeManager(List.of(sp), registry);

        // Advance one frame to materialize the spawn
        manager.reset(0);
        manager.update(0, null, null, 1);

        // Verify object was created
        assertEquals(1, registry.createCount);
        TrackableObject original = registry.instances.get(sp);
        assertNotNull(original);

        // Move object and capture snapshot
        original.moveTo(300, 400);
        RewindSnapshottable<ObjectManagerSnapshot> snap = manager.rewindSnapshottable();
        ObjectManagerSnapshot snapshot = snap.capture();

        // Verify snapshot has one slot entry with correct position
        assertEquals(1, snapshot.slots().size());
        ObjectManagerSnapshot.PerSlotEntry entry = snapshot.slots().get(0);
        assertEquals(300, entry.state().dynamicSpawnX());
        assertEquals(400, entry.state().dynamicSpawnY());
        assertTrue(entry.state().hasDynamicSpawn());

        // Advance the object further
        original.moveTo(999, 999);
        assertEquals(999, original.getX());

        // Restore
        snap.restore(snapshot);

        // After restore, find the restored instance
        Collection<ObjectInstance> active = manager.getActiveObjects();
        assertEquals(1, active.size());
        ObjectInstance restored = active.iterator().next();
        assertNotNull(restored);

        // The restored object should be at the captured position (300, 400)
        assertEquals(300, restored.getX());
        assertEquals(400, restored.getY());
    }

    @Test
    void captureRestoreRoundTrip_scalarsPreserved() {
        ObjectSpawn sp = spawn(0, 0);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = makeManager(List.of(sp), registry);

        manager.reset(0);
        // Run 5 frames so counters advance
        for (int i = 1; i <= 5; i++) {
            manager.update(0, null, null, i);
        }

        int fc = manager.getFrameCounter();
        int vc = manager.getVblaCounter();

        RewindSnapshottable<ObjectManagerSnapshot> snap = manager.rewindSnapshottable();
        ObjectManagerSnapshot snapshot = snap.capture();

        assertEquals(fc, snapshot.frameCounter());
        assertEquals(vc, snapshot.vblaCounter());

        // Run more frames to advance counters
        for (int i = 6; i <= 10; i++) {
            manager.update(0, null, null, i);
        }
        assertNotEquals(fc, manager.getFrameCounter());

        // Restore
        snap.restore(snapshot);
        assertEquals(fc, manager.getFrameCounter());
        assertEquals(vc, manager.getVblaCounter());
    }

    @Test
    void captureNoMutateRestoreIsIdempotent() {
        ObjectSpawn sp = spawn(50, 60);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = makeManager(List.of(sp), registry);

        manager.reset(0);
        manager.update(0, null, null, 1);

        RewindSnapshottable<ObjectManagerSnapshot> snap = manager.rewindSnapshottable();
        ObjectManagerSnapshot s1 = snap.capture();
        snap.restore(s1);
        ObjectManagerSnapshot s2 = snap.capture();

        // Scalars should match after no-mutation restore cycle
        assertEquals(s1.frameCounter(), s2.frameCounter());
        assertEquals(s1.vblaCounter(), s2.vblaCounter());
        assertEquals(s1.slots().size(), s2.slots().size());
    }

    @Test
    void restoreRecreatesObjectAtCapturedPosition() {
        ObjectSpawn sp = spawn(10, 20);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = makeManager(List.of(sp), registry);

        manager.reset(0);
        manager.update(0, null, null, 1);

        // Capture while object is at spawn position
        RewindSnapshottable<ObjectManagerSnapshot> snap = manager.rewindSnapshottable();
        ObjectManagerSnapshot snapshotAtSpawn = snap.capture();
        assertEquals(1, snapshotAtSpawn.slots().size());

        // Restore — should recreate the object at spawn position
        snap.restore(snapshotAtSpawn);

        Collection<ObjectInstance> active = manager.getActiveObjects();
        assertEquals(1, active.size(), "Object should be present after restore");
        ObjectInstance restored = active.iterator().next();
        assertEquals(10, restored.getX(), "X should match captured spawn X");
        assertEquals(20, restored.getY(), "Y should match captured spawn Y");
    }

    @Test
    void restoreRebindsRidingStateToRecreatedSolidObject() {
        ObjectSpawn sp = spawn(100, 100);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = makeManager(List.of(sp), registry);

        manager.reset(0);
        manager.update(0, null, null, 1);

        TrackableObject original = registry.instances.get(sp);
        assertNotNull(original);

        Sonic player = new Sonic("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 100);
        int maxTop = original.getSolidParams().groundHalfHeight() + player.getYRadius();
        player.setCentreY((short) (100 - 4 - maxTop + 8));
        player.setAir(true);
        player.setYSpeed((short) 0x100);

        manager.updateSolidContacts(player);
        assertTrue(manager.isRidingObject(player), "precondition: player should be riding before capture");
        assertSame(original, manager.getRidingObject(player));

        RewindSnapshottable<ObjectManagerSnapshot> snap = manager.rewindSnapshottable();
        ObjectManagerSnapshot ridingSnapshot = snap.capture();

        manager.clearRidingObject(player);
        assertFalse(manager.isRidingObject(player), "test must mutate riding state before restore");

        snap.restore(ridingSnapshot);

        assertTrue(manager.isRidingObject(player), "restore must preserve riding state");
        assertNotSame(original, manager.getRidingObject(player),
                "restore recreates object instances, so riding must be rebound to the recreated platform");
        assertEquals(100, manager.getRidingObject(player).getX());
        assertEquals(100, manager.getRidingObject(player).getY());
    }
}
