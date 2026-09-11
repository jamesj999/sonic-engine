package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Static-state {@link RewindSnapshottable} adapter for the HCZ water-rush
 * palette-cycle gate held in {@link
 * HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate}. Wraps the static
 * snapshot/restore methods for the rewind registry, exactly like {@link
 * com.openggf.game.rewind.snapshot.OscillationStaticAdapter}.
 *
 * <p>Same bug family as {@link com.openggf.game.sonic3k.Sonic3kLevelTriggerStaticAdapter}
 * -- without this adapter a backward rewind seek would leave the gate stuck
 * active (or inactive) regardless of the rewound frame.
 *
 * <p>Registered via {@link com.openggf.game.sonic3k.Sonic3kLevelEventManager#extraRewindAdapters()}.
 */
public final class HCZWaterRushPaletteCycleStaticAdapter
        implements RewindSnapshottable<HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.Snapshot> {

    @Override
    public String key() {
        return "hcz-water-rush-palette-cycle-gate";
    }

    @Override
    public HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.Snapshot capture() {
        return HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.snapshot();
    }

    @Override
    public void restore(HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.Snapshot snapshot) {
        HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate.restore(snapshot);
    }
}
