package com.openggf.game.sonic1.specialstage;

/** Rewind state jointly owned by the S1 special-stage manager and provider. */
record Sonic1SpecialStageProviderSnapshot(
        Sonic1SpecialStageSnapshot managerSnapshot,
        boolean resultsPlcSubmitted) {
}
