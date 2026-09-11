package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;

import java.util.List;

/**
 * Test-only codec-less {@link AbstractObjectInstance} that implements {@link RewindRecreatable}.
 *
 * <p>Used by {@code TestGenericRecreate} to prove that the production
 * {@code ObjectManager} capture→restore path reaches
 * {@link ObjectRewindDynamicCodecs#genericRecreate} for a class without a registered
 * per-object restore hook. The class is a top-level type (not an inner class) so
 * {@code Class.forName(entry.className())} in {@code genericRecreate} resolves it.
 *
 * <p>It carries a single mutable {@code marker} scalar so the test can assert that
 * the recreated instance is non-null and the correct type after a real restore.
 */
public class RewindRecreatableFixture extends AbstractObjectInstance implements RewindRecreatable {

    /** Mutable scalar captured/restored by the generic field capturer. */
    private int marker;

    /**
     * Probe constructor used by {@code genericRecreate} to obtain an instance on which
     * {@link #recreateForRewind(RewindRecreateContext)} is invoked.
     */
    public RewindRecreatableFixture(ObjectSpawn spawn) {
        super(spawn, "RewindRecreatableFixture");
    }

    public int getMarker() {
        return marker;
    }

    public void setMarker(int marker) {
        this.marker = marker;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        // Fresh instance at the captured spawn; scalar restore reapplies marker afterward.
        return new RewindRecreatableFixture(ctx.spawn());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // no-op
    }
}
