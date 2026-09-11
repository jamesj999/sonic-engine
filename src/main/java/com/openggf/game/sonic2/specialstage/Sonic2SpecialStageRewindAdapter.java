package com.openggf.game.sonic2.specialstage;

import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class Sonic2SpecialStageRewindAdapter
        implements RewindSnapshottable<Sonic2SpecialStageProviderSnapshot> {
    private final Sonic2SpecialStageManager manager;
    private final BooleanSupplier resultsPlcSubmitted;
    private final Consumer<Boolean> restoreResultsPlcSubmitted;

    public Sonic2SpecialStageRewindAdapter(Sonic2SpecialStageManager manager,
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
    public Sonic2SpecialStageProviderSnapshot capture() {
        return new Sonic2SpecialStageProviderSnapshot(
                manager.captureRewindSnapshot(), resultsPlcSubmitted.getAsBoolean());
    }

    @Override
    public void restore(Sonic2SpecialStageProviderSnapshot snapshot) {
        manager.restoreRewindSnapshot(snapshot.managerSnapshot());
        restoreResultsPlcSubmitted.accept(snapshot.resultsPlcSubmitted());
    }
}
