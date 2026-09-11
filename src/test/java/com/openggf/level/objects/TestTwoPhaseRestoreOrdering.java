package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameId;
import com.openggf.game.rewind.RewindRoundTripHarness;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * TDD Red→Green test for Task 5: the {@code ObjectManager} rewind restore must be
 * two-phase so an object-reference field resolves regardless of the order in which the
 * referenced object is recreated.
 *
 * <h2>What this proves</h2>
 * Two dynamic objects are captured in entry order [A, B], where A holds a (non-structural,
 * captured) {@link ObjectInstance} reference to B. B is therefore recreated <em>after</em> A
 * in dynamic-entry order — a <strong>forward reference</strong>.
 *
 * <ul>
 *   <li><strong>Single-pass restore (pre-Task-5):</strong> A's field blob is applied in the
 *       same loop iteration that recreates A, before B has been recreated. The restore
 *       identity table is built once from the pre-restore live set, so A's ref resolves to the
 *       <em>old, orphaned</em> B instance (no longer in {@code dynamicObjects}) — never the
 *       restored B. This test would fail.</li>
 *   <li><strong>Two-phase restore (Task 5):</strong> phase 1 recreates and id-registers both
 *       A and B; phase 2 applies every field blob against the fully-populated table, so A's
 *       ref resolves to the <em>restored</em> B instance.</li>
 * </ul>
 *
 * <p>The fixtures implement {@link RewindRecreatable} (codec-less) so they survive restore via
 * the production {@code genericRecreate} fallback wired in Task 4, exercising the real
 * {@code RewindRoundTripHarness} → {@code ObjectManager.restore()} path.
 */
class TestTwoPhaseRestoreOrdering {

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    /**
     * A forward object reference (A → B, B recreated after A) must resolve to the RESTORED B
     * after a real capture→restore round-trip — proving the restore is order-independent.
     */
    @Test
    void forwardObjectRefResolvesToRestoredTarget() {
        RewindRoundTripHarness harness = RewindRoundTripHarness.build(GameId.S2);
        ObjectManager om = harness.objectManager();
        StubObjectServices services = servicesFor(om);

        // B first so it owns the earlier slot, then A — but capture order in the snapshot
        // follows insertion order, and A is captured BEFORE B here, so A's reference to B is a
        // forward reference (B's entry recreated after A's).
        RefTargetFixture targetB = ObjectConstructionContext.construct(
                services, () -> new RefTargetFixture(spawnFor(0x141, 0x80, 1)));
        RefHolderFixture holderA = ObjectConstructionContext.construct(
                services, () -> new RefHolderFixture(spawnFor(0x140, 0x80, 0)));
        holderA.linkedRef = targetB;

        // Insert A before B so the dynamic-entry capture order is [A, B] (forward ref).
        om.addDynamicObject(holderA);
        om.addDynamicObject(targetB);

        RefTargetFixture preRestoreB = (RefTargetFixture) holderA.linkedRef;
        assertSame(targetB, preRestoreB, "precondition: A links to B before round-trip");

        harness.roundTrip();

        RefHolderFixture restoredA = firstOf(om, RefHolderFixture.class);
        RefTargetFixture restoredB = firstOf(om, RefTargetFixture.class);
        assertNotNull(restoredA, "A must survive restore via genericRecreate");
        assertNotNull(restoredB, "B must survive restore via genericRecreate");

        assertNotNull(restoredA.linkedRef,
                "A's forward reference must resolve (not null) after restore");
        assertSame(restoredB, restoredA.linkedRef,
                "A's forward reference must resolve to the RESTORED B (identity), not the "
                        + "pre-restore B that the single-pass path leaves linked");
        assertNotSame(targetB, restoredA.linkedRef,
                "A's reference must point at the recreated B, not the orphaned pre-restore B");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static ObjectSpawn spawnFor(int x, int y, int layoutIndex) {
        // Distinct layoutIndex per object so each mints a distinct ObjectRefId.
        return new ObjectSpawn(x, y, 0x7100 + layoutIndex, 0, 0, false, -1, layoutIndex);
    }

    private static StubObjectServices servicesFor(ObjectManager om) {
        ObjectManager[] holder = new ObjectManager[]{ om };
        return new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public Camera camera() {
                return mockCamera();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends AbstractObjectInstance> T firstOf(ObjectManager om, Class<T> cls) {
        for (ObjectInstance o : om.getActiveObjects()) {
            if (cls.isInstance(o) && !o.isDestroyed()) {
                return (T) o;
            }
        }
        return null;
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    // =========================================================================
    // Fixtures (top-level-resolvable so genericRecreate's Class.forName succeeds)
    // =========================================================================

    /**
     * Holds a captured forward reference to another object. The field {@code linkedRef} is
     * non-static, non-transient, non-final and named to avoid
     * {@code DefaultObjectRewindPolicies.STRUCTURAL_OBJECT_FIELD_NAMES}, so the compact schema
     * captures it as an {@code ObjectRefId} and resolves it on restore.
     */
    public static final class RefHolderFixture extends AbstractObjectInstance
            implements RewindRecreatable {
        /** The forward object reference under test. */
        AbstractObjectInstance linkedRef;

        public RefHolderFixture(ObjectSpawn spawn) {
            super(spawn, "RefHolderFixture");
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new RefHolderFixture(ctx.spawn());
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    /** Reference target — a codec-less identity anchor that survives restore. */
    public static final class RefTargetFixture extends AbstractObjectInstance
            implements RewindRecreatable {
        public RefTargetFixture(ObjectSpawn spawn) {
            super(spawn, "RefTargetFixture");
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new RefTargetFixture(ctx.spawn());
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }
}
