package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Objects;

public final class Sonic3kSpecialStageRewindAdapter
        implements RewindSnapshottable<Sonic3kSpecialStageSnapshot> {
    private final Sonic3kSpecialStageManager manager;

    public Sonic3kSpecialStageRewindAdapter(Sonic3kSpecialStageManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public String key() {
        return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
    }

    @Override
    public Sonic3kSpecialStageSnapshot capture() {
        return manager.captureRewindSnapshot();
    }

    @Override
    public void restore(Sonic3kSpecialStageSnapshot snapshot) {
        manager.restoreRewindSnapshot(snapshot);
    }
}
