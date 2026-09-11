package com.openggf.game.sonic2.specialstage;

/** Test-only bridge to the package-owned semantic replay boundary. */
public final class Sonic2SpecialStageReplayTestBridge {
    private Sonic2SpecialStageReplayTestBridge() {
    }

    public static void completeTerminalPreStartPassWithoutVint(
            Sonic2SpecialStageManager manager) {
        manager.completeTerminalPreStartPassWithoutVint();
    }
}
