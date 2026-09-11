package com.openggf.game.recording;

import java.util.List;

public record RecordingLaunchContext(
        String gameId,
        int zone,
        int act,
        String mainCharacter,
        List<String> sidekickCharacters,
        boolean debugToolsEnabled,
        String launchRoute
) {
    public RecordingLaunchContext {
        sidekickCharacters = sidekickCharacters == null ? List.of() : List.copyOf(sidekickCharacters);
    }
}
