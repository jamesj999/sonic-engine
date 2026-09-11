package com.openggf.level.objects;

import com.openggf.game.GameId;
import com.openggf.game.rewind.RewindRoundTripHarness;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.HtzGroundFireObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ObjectRewindDynamicCodecs#genericRecreate(DynamicObjectEntry, DynamicObjectRecreateContext)}
 * and its wiring into the production {@code ObjectManager} restore path.
 *
 * <p>Covers:
 * <ol>
 *   <li><strong>{@link RewindRecreatable} path (direct):</strong> a class implementing
 *       {@link RewindRecreatable} recreates via
 *       {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)}, including spawn
 *       propagation to the hook.</li>
 *   <li><strong>Registry path (direct):</strong> a class without {@link RewindRecreatable}
 *       falls through to {@code registry.create(spawn)}.</li>
 *   <li><strong>Production restore wiring:</strong> a codec-less {@link RewindRecreatable}
 *       fixture survives a REAL {@code ObjectManager} capture→restore round-trip, proving
 *       {@code genericRecreate} is reached via {@code recreateDynamicObject} (not only by
 *       direct call).</li>
 * </ol>
 */
class TestGenericRecreate {

    private ObjectManager objectManager;
    private DynamicObjectRecreateContext ctx;

    @BeforeEach
    void setUp() {
        GraphicsManager.getInstance().initHeadless();
        ObjectManager[] managerHolder = new ObjectManager[1];

        StubObjectServices stub = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return managerHolder[0];
            }
        };

        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        objectManager = new ObjectManager(
                List.of(), registry,
                0, null, null,
                GraphicsManager.getInstance(),
                mockCamera(),
                stub);
        managerHolder[0] = objectManager;
        objectManager.reset(0);

        ctx = new DynamicObjectRecreateContext(objectManager);
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    // =========================================================================
    // Path 1 — RewindRecreatable hook (direct genericRecreate call)
    // =========================================================================

    /**
     * A non-spawn-constructible object that implements {@link RewindRecreatable} must
     * recreate successfully via {@code genericRecreate} without a per-object codec registered.
     */
    @Test
    void rewindRecreatableObjectRecreatesViaHook() {
        DynamicObjectEntry entry = capturedEntryFor(
                new HtzGroundFireObjectInstance(100, 200, 1, 3));

        ObjectInstance inst = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);

        assertNotNull(inst, "genericRecreate must return a non-null instance for RewindRecreatable classes");
        assertEquals(HtzGroundFireObjectInstance.class, inst.getClass(),
                "genericRecreate must return an instance of the captured class");
    }

    /**
     * The captured spawn coordinates must propagate through {@code genericRecreate} into the
     * {@link RewindRecreateContext} and onto the recreated instance.
     */
    @Test
    void rewindRecreatableHookReceivesSpawnFromEntry() {
        ObjectSpawn spawn = new ObjectSpawn(42, 77, Sonic2ObjectIds.LAVA_BUBBLE, 0, 0, false, 0);
        HtzGroundFireObjectInstance original = new HtzGroundFireObjectInstance(42, 77, -1, 2);
        DynamicObjectEntry entry = capturedEntryFor(original, spawn);

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);

        assertNotNull(result);
        assertEquals(HtzGroundFireObjectInstance.class, result.getClass());
        // SpawnRewindRecreatable rebuilds HtzGroundFireObjectInstance from ctx.spawn().
        // getX()/getY() return the constructor-supplied position, so x==42 proves the
        // captured spawn coordinate actually propagated through the hook.
        assertEquals(42, result.getX(),
                "recreated instance X must reflect the captured spawn X (42)");
        assertEquals(77, result.getY(),
                "recreated instance Y must reflect the captured spawn Y (77)");
    }

    @Test
    void rewindRecreatableProbeSupportsObjectSpawnIntConstructor() {
        ObjectSpawn capturedSpawn = new ObjectSpawn(0x120, 0x90, 0x7002, 0, 0, false, -1);
        DynamicObjectEntry entry = new DynamicObjectEntry(
                SpawnIntRewindRecreatableObject.class.getName(),
                capturedSpawn,
                -1,
                null);

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);

        SpawnIntRewindRecreatableObject recreated =
                assertInstanceOf(SpawnIntRewindRecreatableObject.class, result,
                        "genericRecreate must reach the RewindRecreatable hook for (ObjectSpawn, int) classes");
        assertSame(capturedSpawn, recreated.getSpawn(),
                "recreateForRewind must receive and use the captured spawn, not the probe spawn");
        assertEquals(0xCAFE, recreated.constructorMarker(),
                "fixture hook must create the final restored instance, proving the hook ran");
    }

    @Test
    void rewindRecreatableProbeSupportsPrimitiveIntIntIntConstructor() {
        ObjectSpawn capturedSpawn = new ObjectSpawn(0x220, 0x190, 0x7003, 0, 0, false, -1);
        DynamicObjectEntry entry = new DynamicObjectEntry(
                IntIntIntRewindRecreatableObject.class.getName(),
                capturedSpawn,
                -1,
                null);

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);

        IntIntIntRewindRecreatableObject recreated =
                assertInstanceOf(IntIntIntRewindRecreatableObject.class, result,
                        "genericRecreate must reach the RewindRecreatable hook for (int,int,int) classes");
        assertEquals(capturedSpawn.x(), recreated.getX(),
                "recreated fixture must use captured spawn X from the hook context");
        assertEquals(capturedSpawn.y(), recreated.getY(),
                "recreated fixture must use captured spawn Y from the hook context");
        assertEquals(0xCAFE, recreated.constructorMarker(),
                "fixture hook marker proves recreateForRewind ran after primitive probe construction");
    }

    @Test
    void rewindRecreatableProbeSupportsPrimitiveIntIntIntIntConstructor() {
        ObjectSpawn capturedSpawn = new ObjectSpawn(0x230, 0x1A0, 0x7004, 0, 0, false, -1);
        DynamicObjectEntry entry = new DynamicObjectEntry(
                IntIntIntIntRewindRecreatableObject.class.getName(),
                capturedSpawn,
                -1,
                null);

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);

        IntIntIntIntRewindRecreatableObject recreated =
                assertInstanceOf(IntIntIntIntRewindRecreatableObject.class, result,
                        "genericRecreate must reach the RewindRecreatable hook for (int,int,int,int) classes");
        assertEquals(capturedSpawn.x(), recreated.getX(),
                "recreated fixture must use captured spawn X from the hook context");
        assertEquals(capturedSpawn.y(), recreated.getY(),
                "recreated fixture must use captured spawn Y from the hook context");
        assertEquals(0xCAFE, recreated.constructorMarker(),
                "fixture hook marker proves recreateForRewind ran after primitive probe construction");
    }

    @Test
    void rewindRecreatableProbeSupportsPrimitiveIntIntIntBooleanConstructor() {
        ObjectSpawn capturedSpawn = new ObjectSpawn(0x240, 0x1B0, 0x7005, 0x0A, 0, false, -1);
        DynamicObjectEntry entry = new DynamicObjectEntry(
                IntIntIntBooleanRewindRecreatableObject.class.getName(),
                capturedSpawn,
                -1,
                null);

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);

        IntIntIntBooleanRewindRecreatableObject recreated =
                assertInstanceOf(IntIntIntBooleanRewindRecreatableObject.class, result,
                        "genericRecreate must reach the RewindRecreatable hook for (int,int,int,boolean) classes");
        assertEquals(capturedSpawn.x(), recreated.getX(),
                "recreated fixture must use captured spawn X from the hook context");
        assertEquals(capturedSpawn.y(), recreated.getY(),
                "recreated fixture must use captured spawn Y from the hook context");
        assertEquals(capturedSpawn.subtype(), recreated.subtypeMarker(),
                "fixture hook must be able to preserve the captured spawn subtype");
        assertEquals(0xCAFE, recreated.constructorMarker(),
                "fixture hook marker proves recreateForRewind ran after primitive probe construction");
    }

    @Test
    void spawnIntIntParentProbeSkipsConstructorWhenLiveParentIsMissing() {
        ObjectSpawn capturedSpawn = new ObjectSpawn(0x260, 0x1D0, 0x7006, 0, 0, false, -1);
        DynamicObjectEntry entry = new DynamicObjectEntry(
                SpawnIntIntParentRewindRecreatableObject.class.getName(),
                capturedSpawn,
                -1,
                null);

        ObjectInstance result = assertDoesNotThrow(
                () -> ObjectRewindDynamicCodecs.genericRecreate(entry, ctx),
                "missing live parent should make the parent probe unavailable, not call the constructor with null");

        assertNull(result,
                "without a live parent or alternate probe constructor, genericRecreate should report no recreate path");
    }

    // =========================================================================
    // Path 2 — Registry path (direct genericRecreate call)
    // =========================================================================

    /**
     * A class WITHOUT {@link RewindRecreatable} falls through to the registry path. With no
     * factory registered for the artificial spawn's objectId, {@code registry.create(spawn)}
     * yields a placeholder (never null), and {@code genericRecreate} returns it unchanged —
     * documenting that the registry path is taken (not the RewindRecreatable hook).
     */
    @Test
    void spawnConstructibleObjectFallsThroughToRegistry() {
        // objectId=0xFFFF has no registered factory → AbstractObjectRegistry.defaultFactory
        // returns a PlaceholderObjectInstance. The key signal: genericRecreate returns a
        // non-null instance of the REGISTRY's product type, NOT MinimalRegistryObject.
        ObjectSpawn spawn = new ObjectSpawn(50, 50, 0xFFFF, 0, 0, false, 0);
        DynamicObjectEntry entry = new DynamicObjectEntry(
                MinimalRegistryObject.class.getName(),
                spawn, -1, null);

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(entry, ctx);

        assertNotNull(result, "registry path must produce the registry's product for an unmapped id");
        assertEquals(PlaceholderObjectInstance.class, result.getClass(),
                "unmapped objectId must yield the registry's PlaceholderObjectInstance, "
                        + "proving the registry path (not the RewindRecreatable hook) was taken");
    }

    // =========================================================================
    // Path 3 — Production restore wiring (REAL ObjectManager capture→restore)
    // =========================================================================

    /**
     * A codec-less {@link RewindRecreatable} fixture must survive a REAL
     * {@code ObjectManager} capture→restore round-trip. This proves {@code genericRecreate}
     * is reached via the production {@code recreateDynamicObject} fallback — not only by a
     * direct test call. Without the wiring, the fixture would be dropped on restore (codec
     * lookup returns null) and the post-restore count would be 0.
     */
    @Test
    void codecLessRewindRecreatableSurvivesProductionRestore() {
        RewindRoundTripHarness harness = RewindRoundTripHarness.build(GameId.S2);
        ObjectManager om = harness.objectManager();

        // Add a codec-less RewindRecreatable fixture as a dynamic object with a captured marker.
        ObjectSpawn spawn = new ObjectSpawn(0x140, 0x80, 0x7000, 0, 0, false, -1);
        RewindRecreatableFixture fixture = ObjectConstructionContext.construct(
                servicesFor(om), () -> new RewindRecreatableFixture(spawn));
        fixture.setMarker(99);
        om.addDynamicObject(fixture);

        int liveBefore = countFixtures(om);
        assertEquals(1, liveBefore, "precondition: exactly one fixture is live before round-trip");

        // Real production capture→restore through RewindRegistry + ObjectManager.restore().
        harness.roundTrip();

        int liveAfter = countFixtures(om);
        assertEquals(1, liveAfter,
                "codec-less RewindRecreatable fixture must survive production restore via "
                        + "genericRecreate (count 1→1, not dropped to 0)");

        RewindRecreatableFixture restored = firstFixture(om);
        assertNotNull(restored, "a restored fixture instance must exist after round-trip");
        assertEquals(99, restored.getMarker(),
                "scalar state (marker=99) must be restored exactly on the recreated fixture");
    }

    // =========================================================================
    // RewindRecreatable interface contract
    // =========================================================================

    /**
     * HtzGroundFireObjectInstance must implement {@link RewindRecreatable} for the generic path.
     */
    @Test
    void htzGroundFireImplementsRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(HtzGroundFireObjectInstance.class),
                "HtzGroundFireObjectInstance must implement RewindRecreatable");
    }

    @Test
    void dynamicObjectRecreateContextRequiresObjectManager() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new DynamicObjectRecreateContext(null));

        assertTrue(thrown.getMessage().contains("objectManager"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DynamicObjectEntry capturedEntryFor(AbstractObjectInstance instance) {
        return capturedEntryFor(instance, instance.getSpawn());
    }

    private DynamicObjectEntry capturedEntryFor(AbstractObjectInstance instance, ObjectSpawn spawn) {
        return new DynamicObjectEntry(
                instance.getClass().getName(),
                spawn,
                -1,
                null // state; scalar restore is exercised by the production-restore test
        );
    }

    private static int countFixtures(ObjectManager om) {
        int n = 0;
        for (ObjectInstance o : om.getActiveObjects()) {
            if (o instanceof RewindRecreatableFixture && !o.isDestroyed()) {
                n++;
            }
        }
        return n;
    }

    private static RewindRecreatableFixture firstFixture(ObjectManager om) {
        for (ObjectInstance o : om.getActiveObjects()) {
            if (o instanceof RewindRecreatableFixture f && !o.isDestroyed()) {
                return f;
            }
        }
        return null;
    }

    private static StubObjectServices servicesFor(ObjectManager om) {
        ObjectManager[] holder = new ObjectManager[]{ om };
        return new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
    }

    private static com.openggf.camera.Camera mockCamera() {
        return new com.openggf.camera.Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    /**
     * Minimal spawn-constructible object for the registry-path test. Deliberately NOT
     * implementing {@link RewindRecreatable} so {@code genericRecreate} takes the registry path.
     */
    private static final class MinimalRegistryObject extends AbstractObjectInstance {
        MinimalRegistryObject(ObjectSpawn spawn) {
            super(spawn, "MinimalRegistryObject");
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {
            // no-op
        }
    }

    private static final class SpawnIntRewindRecreatableObject
            extends AbstractObjectInstance implements RewindRecreatable {
        private final int constructorMarker;

        SpawnIntRewindRecreatableObject(ObjectSpawn spawn, int constructorMarker) {
            super(spawn, "SpawnIntRewindRecreatableObject");
            this.constructorMarker = constructorMarker;
        }

        int constructorMarker() {
            return constructorMarker;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new SpawnIntRewindRecreatableObject(ctx.spawn(), 0xCAFE);
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {
            // no-op
        }
    }

    private static final class IntIntIntRewindRecreatableObject
            extends AbstractObjectInstance implements RewindRecreatable {
        private final int constructorMarker;

        IntIntIntRewindRecreatableObject(int x, int y, int constructorMarker) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "IntIntIntRewindRecreatableObject");
            this.constructorMarker = constructorMarker;
        }

        int constructorMarker() {
            return constructorMarker;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new IntIntIntRewindRecreatableObject(ctx.spawn().x(), ctx.spawn().y(), 0xCAFE);
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {
            // no-op
        }
    }

    private static final class IntIntIntIntRewindRecreatableObject
            extends AbstractObjectInstance implements RewindRecreatable {
        private final int constructorMarker;

        IntIntIntIntRewindRecreatableObject(int x, int y, int ignored, int constructorMarker) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "IntIntIntIntRewindRecreatableObject");
            this.constructorMarker = constructorMarker;
        }

        int constructorMarker() {
            return constructorMarker;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new IntIntIntIntRewindRecreatableObject(ctx.spawn().x(), ctx.spawn().y(), 0, 0xCAFE);
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {
            // no-op
        }
    }

    private static final class IntIntIntBooleanRewindRecreatableObject
            extends AbstractObjectInstance implements RewindRecreatable {
        private final int subtypeMarker;
        private final int constructorMarker;

        IntIntIntBooleanRewindRecreatableObject(int x, int y, int subtypeMarker, boolean hookMarker) {
            super(new ObjectSpawn(x, y, 0, subtypeMarker, 0, false, 0),
                    "IntIntIntBooleanRewindRecreatableObject");
            this.subtypeMarker = subtypeMarker;
            this.constructorMarker = hookMarker ? 0xCAFE : 0;
        }

        int subtypeMarker() {
            return subtypeMarker;
        }

        int constructorMarker() {
            return constructorMarker;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new IntIntIntBooleanRewindRecreatableObject(
                    ctx.spawn().x(), ctx.spawn().y(), ctx.spawn().subtype(), true);
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {
            // no-op
        }
    }

    private static final class SpawnIntIntParentRewindRecreatableObject
            extends AbstractObjectInstance implements RewindRecreatable {
        SpawnIntIntParentRewindRecreatableObject(
                ObjectSpawn spawn,
                int xOffset,
                int yOffset,
                ParentProbeObject parent) {
            super(new ObjectSpawn(spawn.x() + xOffset, spawn.y() + yOffset, 0, 0, 0, false, 0),
                    "SpawnIntIntParentRewindRecreatableObject");
            if (parent == null) {
                throw new IllegalArgumentException("parent is required");
            }
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new SpawnIntIntParentRewindRecreatableObject(ctx.spawn(), 0, 0, new ParentProbeObject());
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {
            // no-op
        }
    }

    private static final class ParentProbeObject extends AbstractObjectInstance {
        ParentProbeObject() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "ParentProbeObject");
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {
            // no-op
        }
    }
}
