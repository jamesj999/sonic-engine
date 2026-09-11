package com.openggf.game.sonic1.specialstage;

import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class Sonic1SpecialStageRewindAdapter
        implements RewindSnapshottable<Sonic1SpecialStageProviderSnapshot> {
    private final Sonic1SpecialStageManager manager;
    private final BooleanSupplier resultsPlcSubmitted;
    private final Consumer<Boolean> restoreResultsPlcSubmitted;

    Sonic1SpecialStageRewindAdapter(Sonic1SpecialStageManager manager,
                                    BooleanSupplier resultsPlcSubmitted,
                                    Consumer<Boolean> restoreResultsPlcSubmitted) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.resultsPlcSubmitted = Objects.requireNonNull(resultsPlcSubmitted, "resultsPlcSubmitted");
        this.restoreResultsPlcSubmitted = Objects.requireNonNull(
                restoreResultsPlcSubmitted, "restoreResultsPlcSubmitted");
    }

    @Override
    public String key() {
        return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
    }

    @Override
    public Sonic1SpecialStageProviderSnapshot capture() {
        return new Sonic1SpecialStageProviderSnapshot(
                manager.captureRewindSnapshot(), resultsPlcSubmitted.getAsBoolean());
    }

    @Override
    public void restore(Sonic1SpecialStageProviderSnapshot snapshot) {
        manager.restoreRewindSnapshot(snapshot.managerSnapshot());
        restoreResultsPlcSubmitted.accept(snapshot.resultsPlcSubmitted());
    }
}
