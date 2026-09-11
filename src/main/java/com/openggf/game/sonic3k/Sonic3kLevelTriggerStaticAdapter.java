package com.openggf.game.sonic3k;

import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Static-state {@link RewindSnapshottable} adapter for the S3K
 * {@code Level_trigger_array} held in {@link Sonic3kLevelTriggerManager}.
 * Wraps the static snapshot/restore methods for the rewind registry, exactly
 * like {@link com.openggf.game.rewind.snapshot.OscillationStaticAdapter}.
 *
 * <p>Button/dash-trigger objects (e.g. MGZDashTrigger, MGZTriggerPlatform,
 * LBZ trigger bridge) communicate through this array rather than direct
 * object references. Without rewind coverage the array desyncs against the
 * rewound object instance fields on a backward seek: a triggered platform's
 * own position/timer fields restore correctly, but a rewind-recreated
 * instance re-reads this still-set array in its constructor and immediately
 * fast-forwards to its post-trigger state regardless of the rewound frame.
 *
 * <p>Registered via {@link Sonic3kLevelEventManager#extraRewindAdapters()}.
 */
public final class Sonic3kLevelTriggerStaticAdapter
        implements RewindSnapshottable<Sonic3kLevelTriggerManager.Snapshot> {

    @Override
    public String key() {
        return "s3k-level-trigger-array";
    }

    @Override
    public Sonic3kLevelTriggerManager.Snapshot capture() {
        return Sonic3kLevelTriggerManager.snapshot();
    }

    @Override
    public void restore(Sonic3kLevelTriggerManager.Snapshot snapshot) {
        Sonic3kLevelTriggerManager.restore(snapshot);
    }
}
