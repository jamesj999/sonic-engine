package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindStateful;
import com.openggf.util.AnimationTimer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the default {@link AbstractObjectInstance#captureRewindState()} /
 * {@link AbstractObjectInstance#restoreRewindState(PerObjectRewindSnapshot)} round-trip.
 *
 * <p>Uses a minimal concrete subclass ({@code TestObject}) with no-op overrides.
 * The constructor does NOT go through {@link ObjectManager}, so
 * {@code services()} is intentionally unused here — only the base-class field
 * surface is exercised.
 */
class TestAbstractObjectInstanceRewindCapture {

    // ------------------------------------------------------------------
    // Minimal concrete subclass
    // ------------------------------------------------------------------

    private static final class TestObject extends AbstractObjectInstance {

        TestObject(ObjectSpawn spawn) {
            super(spawn, "TestObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ObjectSpawn spawn(int x, int y) {
        return new ObjectSpawn(x, y, 0x01, 0, 0, false, 0);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void roundTrip_defaultFieldValues() {
        TestObject obj = new TestObject(spawn(100, 200));

        PerObjectRewindSnapshot snap = obj.captureRewindState();

        // Default state immediately after construction
        assertFalse(snap.destroyed());
        assertFalse(snap.destroyedRespawnable());
        assertFalse(snap.hasDynamicSpawn());
        assertFalse(snap.preUpdateValid());
        assertEquals(-1, snap.preUpdateCollisionFlags());
        assertFalse(snap.skipTouchThisFrame());
        assertTrue(snap.solidContactFirstFrame(), "solidContactFirstFrame starts true");
        assertEquals(-1, snap.slotIndex(), "slotIndex starts -1 when not through ObjectManager");
        assertEquals(-1, snap.respawnStateIndex());
    }

    @Test
    void roundTrip_destroyedFlag() {
        TestObject obj = new TestObject(spawn(0, 0));
        obj.setDestroyed(true);

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertTrue(snap.destroyed());

        // mutate further
        obj.setDestroyed(false);
        assertFalse(obj.isDestroyed());

        // restore
        obj.restoreRewindState(snap);
        assertTrue(obj.isDestroyed());
    }

    @Test
    void roundTrip_destroyedRespawnableFlag() {
        TestObject obj = new TestObject(spawn(0, 0));
        obj.setDestroyedByOffscreen();

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertTrue(snap.destroyed());
        assertTrue(snap.destroyedRespawnable());

        obj.setDestroyed(false); // clears both
        assertFalse(obj.isDestroyedRespawnable());

        obj.restoreRewindState(snap);
        assertTrue(obj.isDestroyed());
        assertTrue(obj.isDestroyedRespawnable());
    }

    @Test
    void roundTrip_dynamicSpawnPosition() {
        TestObject obj = new TestObject(spawn(50, 60));
        // updateDynamicSpawn is protected; exercise it via a small local subclass that
        // exposes it, or access via reflective call. Use reflection to keep test lean.
        // Actually updateDynamicSpawn is protected — create a helper subclass.
        // We instead use a positionable subclass.
        TestObjectWithPosition posObj = new TestObjectWithPosition(spawn(50, 60));
        posObj.moveTo(300, 400);

        PerObjectRewindSnapshot snap = posObj.captureRewindState();
        assertTrue(snap.hasDynamicSpawn());
        assertEquals(300, snap.dynamicSpawnX());
        assertEquals(400, snap.dynamicSpawnY());

        posObj.moveTo(999, 999);
        assertEquals(999, posObj.getX());

        posObj.restoreRewindState(snap);
        assertEquals(300, posObj.getX());
        assertEquals(400, posObj.getY());
    }

    @Test
    void roundTrip_slotAndRespawnIndex() {
        TestObject obj = new TestObject(spawn(0, 0));
        obj.setSlotIndex(42);
        obj.setRespawnStateIndex(7);

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertEquals(42, snap.slotIndex());
        assertEquals(7, snap.respawnStateIndex());

        obj.setSlotIndex(99);
        obj.setRespawnStateIndex(99);

        obj.restoreRewindState(snap);
        assertEquals(42, obj.getSlotIndex());
        assertEquals(7, obj.getRespawnStateIndex());
    }

    @Test
    void roundTrip_skipTouchAndSolidContactFlags() {
        TestObject obj = new TestObject(spawn(0, 0));
        obj.setSkipTouchThisFrame(true);

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertTrue(snap.skipTouchThisFrame());
        // solidContactFirstFrame is true by default at construction
        assertTrue(snap.solidContactFirstFrame());

        // Simulate a frame passing — clears both flags
        obj.snapshotPreUpdatePosition();
        assertFalse(obj.isSkipTouchThisFrame());
        assertFalse(obj.isSkipSolidContactThisFrame());

        obj.restoreRewindState(snap);
        assertTrue(obj.isSkipTouchThisFrame());
        assertTrue(obj.isSkipSolidContactThisFrame());
    }

    @Test
    void captureAndRestoreIsSelfConsistentWithNoMutation() {
        TestObject obj = new TestObject(spawn(10, 20));
        obj.setSlotIndex(5);

        PerObjectRewindSnapshot snap1 = obj.captureRewindState();
        obj.restoreRewindState(snap1);
        PerObjectRewindSnapshot snap2 = obj.captureRewindState();

        // Both snapshots should agree on every field
        assertEquals(snap1.destroyed(), snap2.destroyed());
        assertEquals(snap1.hasDynamicSpawn(), snap2.hasDynamicSpawn());
        assertEquals(snap1.slotIndex(), snap2.slotIndex());
        assertEquals(snap1.solidContactFirstFrame(), snap2.solidContactFirstFrame());
    }

    // ------------------------------------------------------------------
    // Helper subclass that exposes updateDynamicSpawn
    // ------------------------------------------------------------------

    private static final class TestObjectWithPosition extends AbstractObjectInstance {

        TestObjectWithPosition(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithPosition");
        }

        void moveTo(int x, int y) {
            updateDynamicSpawn(x, y);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TestObjectWithGenericState extends AbstractObjectInstance {
        private int phase;
        private boolean armed;
        private Mode mode = Mode.IDLE;
        @com.openggf.game.rewind.RewindDeferred(reason = "test reference requires explicit identity handling")
        private Object deferredReference = new Object();

        TestObjectWithGenericState(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithGenericState");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TestObjectWithAnimationState extends AbstractObjectInstance {
        private ObjectAnimationState animationState = new ObjectAnimationState(null, 3, 12);

        TestObjectWithAnimationState(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithAnimationState");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TestObjectWithCustomRewindOverride extends AbstractObjectInstance {
        private int phase;

        TestObjectWithCustomRewindOverride(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithCustomRewindOverride");
        }

        @Override
        public PerObjectRewindSnapshot captureRewindState() {
            return super.captureRewindState();
        }

        @Override
        public void restoreRewindState(PerObjectRewindSnapshot s) {
            super.restoreRewindState(s);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TestObjectWithArrayState extends AbstractObjectInstance {
        private final int[] finalOffsets = {1, 2, 3};
        private byte[] slopeData = {4, 5};
        private int[][] waypoints = {{6, 7}, {8, 9}};

        TestObjectWithArrayState(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithArrayState");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private record TestConfig(int halfWidth, String artKey, int[] delays) {}

    private static final class TestObjectWithRecordState extends AbstractObjectInstance {
        private TestConfig config = new TestConfig(32, "platform", new int[] {7, 11});

        TestObjectWithRecordState(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithRecordState");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private record HelperState(int x, int timer) {}

    private static final class TestStatefulHelper implements RewindStateful<HelperState> {
        private int x = 3;
        private int timer = 5;

        @Override
        public HelperState captureRewindStateValue() {
            return new HelperState(x, timer);
        }

        @Override
        public void restoreRewindStateValue(HelperState state) {
            x = state.x();
            timer = state.timer();
        }
    }

    private static final class TestObjectWithStatefulHelpers extends AbstractObjectInstance {
        private final TestStatefulHelper child = new TestStatefulHelper();
        private TestStatefulHelper[] children = {new TestStatefulHelper(), new TestStatefulHelper()};
        private List<TestStatefulHelper> childList = new ArrayList<>(
                List.of(new TestStatefulHelper(), new TestStatefulHelper()));

        TestObjectWithStatefulHelpers(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithStatefulHelpers");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TestObjectWithSharedHelpers extends AbstractObjectInstance {
        private final PlatformBobHelper bob = new PlatformBobHelper();
        private final AnimationTimer timer = new AnimationTimer(3, 2);

        TestObjectWithSharedHelpers(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithSharedHelpers");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TestObjectWithValueCollections extends AbstractObjectInstance {
        private final List<Integer> counters = new ArrayList<>(List.of(1, 2, 3));
        private final Set<Mode> modes = new LinkedHashSet<>(List.of(Mode.IDLE, Mode.ACTIVE));
        private final Map<String, Integer> values = new LinkedHashMap<>();

        TestObjectWithValueCollections(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithValueCollections");
            values.put("left", 7);
            values.put("right", 9);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TestObjectWithFallbackValueMap extends AbstractObjectInstance {
        private byte[] rasterCache = {1, 2, 3};
        private final Map<Integer, Integer> customMemory = new LinkedHashMap<>();
        // No-arg-constructor-less RewindStateful holder in a non-final array
        // (same shape as IczMinibossInstance$OrbState): legitimately
        // unsupported by the compact schema (a fresh array's elements cannot
        // be allocated on restore), forcing the generic fallback path this
        // test covers. Non-final primitive arrays alone no longer force the
        // fallback.
        private SegmentState[] segments = {new SegmentState(1)};

        TestObjectWithFallbackValueMap(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithFallbackValueMap");
            customMemory.put(0x2E, 7);
            customMemory.put(0x46, 1);
        }

        private static final class SegmentState
                implements com.openggf.game.rewind.RewindStateful<Integer> {
            int phase;

            SegmentState(int phase) {
                this.phase = phase;
            }

            @Override
            public Integer captureRewindStateValue() {
                return phase;
            }

            @Override
            public void restoreRewindStateValue(Integer state) {
                this.phase = state;
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TestObjectWithStateHolder extends AbstractObjectInstance {
        private final TraversalState traversal = new TraversalState();

        TestObjectWithStateHolder(ObjectSpawn spawn) {
            super(spawn, "TestObjectWithStateHolder");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class TraversalState {
        private int phase = 2;
        private boolean active = true;
        private int[] path = {3, 5, 8};
        private TraversalPath route = new TraversalPath("main", new RoutePoint(11, 13));
    }

    private record TraversalPath(String label, RoutePoint exit) {}

    private record RoutePoint(int x, int y) {}

    private enum Mode {
        IDLE,
        ACTIVE
    }

    private static final class TestBadnikWithGenericState extends AbstractBadnikInstance {
        private int phase;

        TestBadnikWithGenericState(ObjectSpawn spawn) {
            super(spawn, "TestBadnikWithGenericState");
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

    @Test
    void defaultClassCapturesAndRestoresScalarCompactSidecar() {
        TestObjectWithGenericState obj = new TestObjectWithGenericState(spawn(0, 0));
        obj.phase = 7;
        obj.armed = true;
        obj.mode = Mode.ACTIVE;
        Object originalReference = obj.deferredReference;

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNotNull(snap.compactGenericState());
        assertNull(snap.genericState());
        assertEquals(TestObjectWithGenericState.class, snap.compactGenericState().type());

        obj.phase = 2;
        obj.armed = false;
        obj.mode = Mode.IDLE;
        obj.deferredReference = new Object();
        obj.restoreRewindState(snap);

        assertEquals(7, obj.phase);
        assertTrue(obj.armed);
        assertEquals(Mode.ACTIVE, obj.mode);
        assertNotSame(originalReference, obj.deferredReference);
    }

    @Test
    void defaultClassCompactCapturesNonFinalAnimationState() {
        // A non-final ObjectAnimationState field no longer forces the generic-sidecar
        // fallback: the codec rebuilds a fresh instance from the captured animationSet
        // reference on restore, so the whole class stays on the compact schema path.
        TestObjectWithAnimationState obj = new TestObjectWithAnimationState(spawn(0, 0));

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNotNull(snap.compactGenericState());
        assertNull(snap.genericState());

        obj.animationState = null;
        obj.restoreRewindState(snap);

        assertNotNull(obj.animationState);
        assertEquals(3, obj.animationState.getAnimId());
        assertEquals(12, obj.animationState.getMappingFrame());
    }

    @Test
    void customRewindOverridesAreNotRoutedThroughCompactSidecar() {
        TestObjectWithCustomRewindOverride obj = new TestObjectWithCustomRewindOverride(spawn(0, 0));
        obj.phase = 7;

        PerObjectRewindSnapshot snap = obj.captureRewindState();

        assertNull(snap.compactGenericState());
        assertNull(snap.genericState());
    }

    @Test
    void defaultClassCapturesAndRestoresCompactArrayGenericSidecar() {
        TestObjectWithArrayState obj = new TestObjectWithArrayState(spawn(0, 0));
        int[] originalFinalOffsets = obj.finalOffsets;

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNull(snap.compactGenericState());
        assertNotNull(snap.genericState());

        obj.finalOffsets[0] = 99;
        obj.slopeData[1] = 99;
        obj.waypoints[1][0] = 99;
        obj.restoreRewindState(snap);

        assertSame(originalFinalOffsets, obj.finalOffsets);
        assertArrayEquals(new int[] {1, 2, 3}, obj.finalOffsets);
        assertArrayEquals(new byte[] {4, 5}, obj.slopeData);
        assertArrayEquals(new int[] {6, 7}, obj.waypoints[0]);
        assertArrayEquals(new int[] {8, 9}, obj.waypoints[1]);
    }

    @Test
    void defaultClassCapturesAndRestoresSupportedRecordGenericSidecar() {
        TestObjectWithRecordState obj = new TestObjectWithRecordState(spawn(0, 0));

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNull(snap.compactGenericState());
        assertNotNull(snap.genericState());

        obj.config.delays()[0] = 99;
        obj.config = new TestConfig(64, "changed", new int[] {1});
        obj.restoreRewindState(snap);

        assertEquals(32, obj.config.halfWidth());
        assertEquals("platform", obj.config.artKey());
        assertArrayEquals(new int[] {7, 11}, obj.config.delays());
    }

    @Test
    void defaultClassCapturesAndRestoresStatefulHelperGenericSidecar() {
        TestObjectWithStatefulHelpers obj = new TestObjectWithStatefulHelpers(spawn(0, 0));
        TestStatefulHelper originalChild = obj.child;
        TestStatefulHelper[] originalChildren = obj.children;

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNull(snap.compactGenericState());
        assertNotNull(snap.genericState());

        obj.child.x = 99;
        obj.child.timer = 99;
        obj.children[0].x = 88;
        obj.children[1].timer = 77;
        obj.childList.get(0).timer = 66;
        obj.childList.get(1).x = 55;
        obj.restoreRewindState(snap);

        assertSame(originalChild, obj.child);
        assertSame(originalChildren, obj.children);
        assertEquals(3, obj.child.x);
        assertEquals(5, obj.child.timer);
        assertEquals(3, obj.children[0].x);
        assertEquals(5, obj.children[1].timer);
        assertEquals(5, obj.childList.get(0).timer);
        assertEquals(3, obj.childList.get(1).x);
    }

    @Test
    void defaultClassCapturesAndRestoresSharedHelperCompactSidecar() {
        TestObjectWithSharedHelpers obj = new TestObjectWithSharedHelpers(spawn(0, 0));
        obj.bob.update(true);
        obj.bob.update(true);
        obj.timer.tick();
        obj.timer.tick();

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNotNull(snap.compactGenericState());
        assertNull(snap.genericState());

        for (int i = 0; i < 20; i++) {
            obj.bob.update(true);
        }
        obj.timer.tick();
        obj.timer.tick();
        obj.restoreRewindState(snap);

        assertEquals(8, obj.bob.getAngle());
        assertEquals(0, obj.timer.getFrame());
        obj.timer.tick();
        assertEquals(1, obj.timer.getFrame());
    }

    @Test
    void defaultClassCapturesAndRestoresFinalValueCollectionsCompactSidecar() {
        TestObjectWithValueCollections obj = new TestObjectWithValueCollections(spawn(0, 0));
        List<Integer> originalCounters = obj.counters;
        Set<Mode> originalModes = obj.modes;
        Map<String, Integer> originalValues = obj.values;

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNotNull(snap.compactGenericState());
        assertNull(snap.genericState());

        obj.counters.clear();
        obj.counters.add(99);
        obj.modes.clear();
        obj.values.clear();
        obj.values.put("mutated", -1);
        obj.restoreRewindState(snap);

        assertSame(originalCounters, obj.counters);
        assertSame(originalModes, obj.modes);
        assertSame(originalValues, obj.values);
        assertEquals(List.of(1, 2, 3), obj.counters);
        assertEquals(List.of(Mode.IDLE, Mode.ACTIVE), new ArrayList<>(obj.modes));
        assertEquals(List.of("left", "right"), new ArrayList<>(obj.values.keySet()));
        assertEquals(7, obj.values.get("left"));
        assertEquals(9, obj.values.get("right"));
    }

    @Test
    void defaultClassCapturesAndRestoresFinalValueMapWhenCompactFallsBackToGenericSidecar() {
        TestObjectWithFallbackValueMap obj = new TestObjectWithFallbackValueMap(spawn(0, 0));
        Map<Integer, Integer> originalMap = obj.customMemory;

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNull(snap.compactGenericState());
        assertNotNull(snap.genericState());

        obj.rasterCache = new byte[] {9};
        obj.customMemory.clear();
        obj.customMemory.put(0x2E, 99);
        obj.restoreRewindState(snap);

        assertArrayEquals(new byte[] {1, 2, 3}, obj.rasterCache);
        assertSame(originalMap, obj.customMemory);
        assertEquals(List.of(0x2E, 0x46), new ArrayList<>(obj.customMemory.keySet()));
        assertEquals(7, obj.customMemory.get(0x2E));
        assertEquals(1, obj.customMemory.get(0x46));
    }

    @Test
    void defaultClassCapturesAndRestoresFinalStateHolderCompactSidecar() {
        TestObjectWithStateHolder obj = new TestObjectWithStateHolder(spawn(0, 0));
        TraversalState originalState = obj.traversal;
        int[] originalPath = obj.traversal.path;

        PerObjectRewindSnapshot snap = obj.captureRewindState();
        assertNotNull(snap.compactGenericState());
        assertNull(snap.genericState());

        obj.traversal.phase = 7;
        obj.traversal.active = false;
        obj.traversal.path = new int[] {99};
        obj.traversal.route = new TraversalPath("mutated", new RoutePoint(1, 1));
        obj.restoreRewindState(snap);

        assertSame(originalState, obj.traversal);
        assertArrayEquals(new int[] {3, 5, 8}, obj.traversal.path);
        assertNotSame(originalPath, obj.traversal.path);
        assertEquals(2, obj.traversal.phase);
        assertTrue(obj.traversal.active);
        assertEquals(new TraversalPath("main", new RoutePoint(11, 13)), obj.traversal.route);
    }

    @Test
    void defaultBadnikClassPreservesCompactSidecarWhenAddingBadnikExtra() {
        TestBadnikWithGenericState obj = new TestBadnikWithGenericState(spawn(0, 0));
        obj.phase = 7;

        PerObjectRewindSnapshot snap = obj.captureRewindState();

        assertNotNull(snap.badnikExtra());
        assertNotNull(snap.compactGenericState());
        assertNull(snap.genericState());
    }
}
