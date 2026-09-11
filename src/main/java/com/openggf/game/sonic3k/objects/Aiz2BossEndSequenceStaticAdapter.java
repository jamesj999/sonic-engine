package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.RewindSnapshottable;

/**
 * Static-state {@link RewindSnapshottable} adapter for the AIZ2 post-boss
 * bridge / button / Knuckles cutscene latches held in
 * {@link Aiz2BossEndSequenceState}. Wraps the static snapshot/restore methods
 * for the rewind registry, exactly like the shared OscillationStaticAdapter.
 *
 * <p>These latches ratchet forward and are not otherwise rewound, so without
 * this adapter a backward seek desyncs them against the rewound boss/cutscene
 * instance flags (bridge re-drop, cutscene-override object deletion).
 *
 * <p>Lives in the S3K game package (same package as {@link Aiz2BossEndSequenceState})
 * so shared rewind infrastructure does not depend on game-specific types.
 * Registered via {@link com.openggf.game.sonic3k.Sonic3kLevelEventManager#extraRewindAdapters()}.
 */
public final class Aiz2BossEndSequenceStaticAdapter
        implements RewindSnapshottable<Aiz2BossEndSequenceState.Snapshot> {

    @Override
    public String key() {
        return "aiz2-boss-end-sequence";
    }

    @Override
    public Aiz2BossEndSequenceState.Snapshot capture() {
        return Aiz2BossEndSequenceState.snapshot();
    }

    @Override
    public void restore(Aiz2BossEndSequenceState.Snapshot snapshot) {
        Aiz2BossEndSequenceState.restore(snapshot);
    }
}
