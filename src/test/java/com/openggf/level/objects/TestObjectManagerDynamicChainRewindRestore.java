package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Synthetic 3-level rewind-restore chain, reproducing a gap in
 * {@code ObjectManager.restore()}'s dynamic-object reconciliation that no currently
 * shipped object graph happens to exercise: a single retry pass over parked entries is
 * NOT enough when an intermediate parent entry ITSELF only resolves during that retry
 * pass -- its own children's parked entries burn their one retry attempt BEFORE the
 * parent's retry has (re)populated the reconstruction-child pool with them, silently
 * dropping their captured state exactly like the bug this reconciliation exists to fix.
 *
 * <p>Shape: {@code ChainRoot} is dynamically spawned, its constructor spawns
 * {@code ChainChild} (which spawns {@code ChainGrandchild} in ITS OWN constructor) --
 * so capture order is {@code [Grandchild, Child, Root, Anchor]} (children are inserted
 * into {@code dynamicObjects} during the parent's constructor, before the parent
 * itself). {@code ChainRoot.recreateForRewind()} additionally requires a live
 * {@code ChainAnchor} (an unrelated, independently-spawned object with no dependency of
 * its own) to already be restored -- modeling any real
 * {@code RewindRecreatable#recreateForRewind()} sibling-search relink whose target
 * happens to sort after it in {@code s.dynamicObjects()}. On the FIRST restore pass:
 * Grandchild and Child fail (pool empty -- Root hasn't reconstructed yet), Root fails
 * (Anchor isn't live yet), Anchor succeeds (no dependency). A SINGLE retry pass would
 * then process Grandchild and Child (still failing -- Root hasn't been retried yet
 * within that same pass) BEFORE Root's own retry succeeds and populates the pool --
 * permanently dropping Grandchild/Child's captured state. Only a fixed-point loop
 * (retry until a pass makes zero progress) resolves this: Root resolves on retry pass
 * 1 (Anchor is live from pass 1), which makes retry pass 2 possible, where
 * Grandchild/Child finally adopt from the now-populated pool.
 */
class TestObjectManagerDynamicChainRewindRestore {

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void grandchildAndChildSurviveAThreeLevelChainWhoseRootOnlyResolvesOnRetry() {
        ObjectManager objectManager = createHarness();

        ChainRoot root = objectManager.createDynamicObject(() -> new ChainRoot(0x100, 0x100));
        ChainAnchor anchor = objectManager.createDynamicObject(() -> new ChainAnchor(0x200, 0x100));
        ChainChild child = root.child;
        ChainGrandchild grandchild = child.grandchild;
        child.marker = 0x1234;
        grandchild.marker = 0x5678;

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        registry.restore(snapshot);

        ChainRoot restoredRoot = only(objectManager, ChainRoot.class);
        ChainAnchor restoredAnchor = only(objectManager, ChainAnchor.class);
        ChainChild restoredChild = only(objectManager, ChainChild.class);
        ChainGrandchild restoredGrandchild = only(objectManager, ChainGrandchild.class);

        assertNotNull(restoredAnchor);
        assertEquals(0x1234, restoredChild.marker,
                "Child's captured marker must survive even though Root only resolved on retry");
        assertEquals(0x5678, restoredGrandchild.marker,
                "Grandchild's captured marker must survive a 3-level chain whose root "
                        + "only resolves on retry");
        assertSame(restoredRoot, restoredChild.parentRoot, "restored child must point at the restored root");
        assertSame(restoredChild, restoredGrandchild.parentChild,
                "restored grandchild must point at the restored child");
    }

    private static ObjectManager createHarness() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
        };
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, GraphicsManager.getInstance(), camera, services);
        holder[0] = objectManager;
        objectManager.reset(0);
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        return objectManager;
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass() == type)
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    /** Unrelated, no-dependency object that {@link ChainRoot} requires to be live before it can relink. */
    static class ChainAnchor extends AbstractObjectInstance implements SpawnConstructionContextRewindRecreatable {
        ChainAnchor(int x, int y) {
            super(new ObjectSpawn(x, y, 0x7001, 0, 0, false, 0), "ChainAnchor");
        }

        ChainAnchor(ObjectSpawn spawn) {
            super(spawn, "ChainAnchor");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    /** Dynamically-spawned root whose constructor spawns {@link ChainChild}. */
    static class ChainRoot extends AbstractObjectInstance implements RewindRecreatable {
        final ChainChild child;

        ChainRoot(int x, int y) {
            super(new ObjectSpawn(x, y, 0x7002, 0, 0, false, 0), "ChainRoot");
            this.child = spawnChild(() -> new ChainChild(this, x, y));
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public ChainRoot recreateForRewind(RewindRecreateContext ctx) {
            // Requires a live ChainAnchor to already be restored -- models any real
            // RewindRecreatable sibling-search relink (e.g. nearestLiveBossForRewind)
            // whose target happens to sort after this entry in s.dynamicObjects().
            Optional<ChainAnchor> anchor = RewindRecreateObjectLinks.nearestObject(ctx, ChainAnchor.class, true);
            if (anchor.isEmpty()) {
                return null;
            }
            return ObjectConstructionContext.construct(ctx.objectServices(),
                    () -> new ChainRoot(ctx.spawn().x(), ctx.spawn().y()));
        }
    }

    /** Constructor-spawned child of {@link ChainRoot}; itself spawns {@link ChainGrandchild}. */
    static class ChainChild extends AbstractObjectInstance implements RewindRecreatable {
        final ChainRoot parentRoot;
        final ChainGrandchild grandchild;
        int marker;

        ChainChild(ChainRoot parentRoot, int x, int y) {
            super(new ObjectSpawn(x, y, 0x7003, 0, 0, false, 0), "ChainChild");
            this.parentRoot = parentRoot;
            this.grandchild = spawnChild(() -> new ChainGrandchild(this, x, y));
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            // Never actually reached in this repro: adoptRewindReconstructionChild
            // always intercepts a captured ChainChild entry before this fallback runs,
            // once ChainRoot has (re)populated the pool. Present only so this class
            // satisfies isRewindRecreatableClassName's RewindRecreatable check.
            return null;
        }
    }

    /** Constructor-spawned grandchild of {@link ChainRoot} via {@link ChainChild}. */
    static class ChainGrandchild extends AbstractObjectInstance implements RewindRecreatable {
        final ChainChild parentChild;
        int marker;

        ChainGrandchild(ChainChild parentChild, int x, int y) {
            super(new ObjectSpawn(x, y, 0x7004, 0, 0, false, 0), "ChainGrandchild");
            this.parentChild = parentChild;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            // Never actually reached -- see ChainChild's identical note.
            return null;
        }
    }
}
