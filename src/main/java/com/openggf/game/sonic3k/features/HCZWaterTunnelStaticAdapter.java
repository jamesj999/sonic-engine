package com.openggf.game.sonic3k.features;

import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Static-state {@link RewindSnapshottable} adapter for the per-player HCZ
 * wind-tunnel state held in {@link HCZWaterTunnelHandler}. Wraps the static
 * snapshot/restore methods for the rewind registry, exactly like {@link
 * com.openggf.game.rewind.snapshot.OscillationStaticAdapter}.
 *
 * <p>Same bug family as {@link com.openggf.game.sonic3k.Sonic3kLevelTriggerStaticAdapter}:
 * without this adapter a backward rewind seek would leave a mid-tunnel
 * player's wind-tunnel flag / influence / exit-animation timer stuck at
 * their pre-seek values.
 *
 * <p>Registered via {@link com.openggf.game.sonic3k.Sonic3kLevelEventManager#extraRewindAdapters()}.
 */
public final class HCZWaterTunnelStaticAdapter
        implements RewindSnapshottable<HCZWaterTunnelHandler.Snapshot> {

    @Override
    public String key() {
        return "hcz-water-tunnel";
    }

    @Override
    public HCZWaterTunnelHandler.Snapshot capture() {
        return HCZWaterTunnelHandler.snapshot();
    }

    @Override
    public void restore(HCZWaterTunnelHandler.Snapshot snapshot) {
        HCZWaterTunnelHandler.restore(snapshot);
    }
}
