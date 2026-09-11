package com.openggf.game.animation.strategies;

import com.openggf.game.GameServices;
import com.openggf.game.animation.ApplyStrategy;
import com.openggf.game.animation.ChannelContext;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.animation.AniPlcScriptState;

import java.util.Objects;

/**
 * {@link ApplyStrategy} bridge that reuses an existing {@link AniPlcScriptState}.
 *
 * <p>This lets legacy script-driven animated tiles participate in the runtime-owned
 * {@code AnimatedTileChannelGraph} without rewriting their frame-application logic.
 */
public final class ScriptFramesApplyStrategy implements ApplyStrategy {

    private final AniPlcScriptState script;

    /** Creates a graph strategy backed by one parsed AniPLC script. */
    public ScriptFramesApplyStrategy(AniPlcScriptState script) {
        this.script = Objects.requireNonNull(script, "script");
    }

    /**
     * Compatibility constructor kept for callers that already thread a graphics manager through
     * their setup path. The graph resolves graphics through {@link GameServices} at apply time.
     */
    public ScriptFramesApplyStrategy(AniPlcScriptState script, GraphicsManager graphicsManager) {
        this(script);
        Objects.requireNonNull(graphicsManager, "graphicsManager");
    }

    @Override
    public void apply(ChannelContext context) {
        script.tick(context.level(), GameServices.graphics());
    }
}
