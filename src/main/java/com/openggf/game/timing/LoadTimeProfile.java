package com.openggf.game.timing;

import java.util.Set;

@FunctionalInterface
public interface LoadTimeProfile {
    LoadTimeProfile IMMEDIATE = (submission, handle) ->
            new LoadTimeDecision(
                    0,
                    Set.of(),
                    LoadTimeDecisionSource.IMMEDIATE,
                    "immediate-v1");

    LoadTimeDecision assign(
            HardwareWorkSubmission submission,
            HardwareWorkHandle handle);
}
