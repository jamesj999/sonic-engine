package com.openggf.game.sonic2.specialstage;

/** Rewind state jointly owned by the S2 special-stage manager and provider. */
record Sonic2SpecialStageProviderSnapshot(
        Sonic2SpecialStageSnapshot managerSnapshot,
        boolean resultsPlcSubmitted) {
}
