package com.openggf.game.sonic2;

import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Static-state {@link RewindSnapshottable} adapter for the S2
 * {@code ButtonVine_Trigger} array held in {@link ButtonVineTriggerManager}.
 * Wraps the static snapshot/restore methods for the rewind registry, exactly
 * like {@link com.openggf.game.rewind.snapshot.OscillationStaticAdapter}.
 *
 * <p>Button/vine/drawbridge objects (MCZDrawbridge, MCZBridge, VineSwitch,
 * MovingVine, Button) communicate through this array rather than direct
 * object references. Without rewind coverage the array desyncs against the
 * rewound object instance fields on a backward seek — see the S3K analog,
 * {@link com.openggf.game.sonic3k.Sonic3kLevelTriggerStaticAdapter}, for the
 * exact failure mode.
 *
 * <p>Registered via {@link Sonic2LevelEventManager#extraRewindAdapters()}.
 */
public final class ButtonVineTriggerStaticAdapter
        implements RewindSnapshottable<ButtonVineTriggerManager.Snapshot> {

    @Override
    public String key() {
        return "s2-button-vine-trigger-array";
    }

    @Override
    public ButtonVineTriggerManager.Snapshot capture() {
        return ButtonVineTriggerManager.snapshot();
    }

    @Override
    public void restore(ButtonVineTriggerManager.Snapshot snapshot) {
        ButtonVineTriggerManager.restore(snapshot);
    }
}
