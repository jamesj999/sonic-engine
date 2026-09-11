package com.openggf.game.sonic3k.features;

import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Static-state {@link RewindSnapshottable} adapter for the per-player HCZ
 * water-skim state held in {@link HCZWaterSkimHandler}. Wraps the static
 * snapshot/restore methods for the rewind registry, exactly like {@link
 * com.openggf.game.rewind.snapshot.OscillationStaticAdapter}.
 *
 * <p>Same bug family as {@link com.openggf.game.sonic3k.Sonic3kLevelTriggerStaticAdapter}:
 * skim-active flags and splash animation counters ratchet forward every
 * frame outside the object-instance graph, so without this adapter a
 * backward rewind seek would leave a mid-skim player's splash animation
 * phase (or the skim-active flag itself) stuck at its pre-seek value.
 *
 * <p>Registered via {@link com.openggf.game.sonic3k.Sonic3kLevelEventManager#extraRewindAdapters()}.
 */
public final class HCZWaterSkimStaticAdapter
        implements RewindSnapshottable<HCZWaterSkimHandler.Snapshot> {

    @Override
    public String key() {
        return "hcz-water-skim";
    }

    @Override
    public HCZWaterSkimHandler.Snapshot capture() {
        return HCZWaterSkimHandler.snapshot();
    }

    @Override
    public void restore(HCZWaterSkimHandler.Snapshot snapshot) {
        HCZWaterSkimHandler.restore(snapshot);
    }
}
