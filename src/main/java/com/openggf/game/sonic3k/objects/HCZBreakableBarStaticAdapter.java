package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Static-state {@link RewindSnapshottable} adapter for the HCZ breakable-bar
 * cross-object player-bit latch held in {@link
 * HCZWaterRushObjectInstance.HCZBreakableBarState}. Wraps the static
 * snapshot/restore methods for the rewind registry, exactly like {@link
 * com.openggf.game.rewind.snapshot.OscillationStaticAdapter}.
 *
 * <p>{@code HCZBreakableBarObjectInstance}, {@code HCZWaterWallObjectInstance},
 * {@code HCZLargeFanObjectInstance}, and {@code HczEndBossGeyserCutscene}
 * communicate through this latch rather than direct object references. Same
 * bug family as {@link com.openggf.game.sonic3k.Sonic3kLevelTriggerStaticAdapter}
 * -- without this adapter a backward rewind seek would leave the latch
 * desynced against the rewound object instance state.
 *
 * <p>Registered via {@link com.openggf.game.sonic3k.Sonic3kLevelEventManager#extraRewindAdapters()}.
 */
public final class HCZBreakableBarStaticAdapter
        implements RewindSnapshottable<HCZWaterRushObjectInstance.HCZBreakableBarState.Snapshot> {

    @Override
    public String key() {
        return "hcz-breakable-bar-state";
    }

    @Override
    public HCZWaterRushObjectInstance.HCZBreakableBarState.Snapshot capture() {
        return HCZWaterRushObjectInstance.HCZBreakableBarState.snapshot();
    }

    @Override
    public void restore(HCZWaterRushObjectInstance.HCZBreakableBarState.Snapshot snapshot) {
        HCZWaterRushObjectInstance.HCZBreakableBarState.restore(snapshot);
    }
}
