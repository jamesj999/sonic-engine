package com.openggf.tests.playback;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TestPlaybackDebugLiveTracePolicy {

    @Test
    void gameLoopDoesNotBulkDrainSuppressedTraceFrames() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));

        assertFalse(source.contains("skipSuppressedFramesBeforeGameplay("),
                "Live playback must not silently jump over suppressed trace frames; "
                        + "phase policy should gate only the current frame.");
    }
}
