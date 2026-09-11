package com.openggf.trace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSkippedPresentationPlcTraceIsolationGuard {
    private static final List<Path> PRODUCTION_OWNERS = List.of(
            Path.of("src/main/java/com/openggf/game/LevelInitProfile.java"),
            Path.of("src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java"),
            Path.of("src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java"),
            Path.of("src/main/java/com/openggf/level/LevelManager.java"));

    @Test
    void productionSkipContractHasNoTraceInputs() throws IOException {
        for (Path owner : PRODUCTION_OWNERS) {
            String source = Files.readString(owner);
            assertFalse(source.contains("com.openggf.trace"),
                    owner + " must not import trace state");
            assertFalse(source.matches("(?s).*completeInitialPresentationPlcs\\s*\\([^)]*"
                            + "(Trace|Metadata|Fixture|Bk2|Route|Frame)[^)]*\\).*"),
                    owner + " must not accept trace, route, fixture, or frame inputs");
        }
    }

    @Test
    void replayConsumesOnlyTheProductionLevelTransition() throws IOException {
        String driver = Files.readString(Path.of(
                "src/main/java/com/openggf/trace/replay/TraceReplayDriver.java"));
        assertTrue(driver.contains("skipPendingInitialTitleCardPresentation()"),
                "live trace replay must select the production presentation-omitted transition");
        assertFalse(driver.contains("completeInitialPresentationPlcs("));
        assertFalse(driver.contains("Sonic1PlcService"));
        assertFalse(driver.contains("Sonic2PlcService"));

        String bootstrap = Files.readString(Path.of(
                "src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java"));
        assertFalse(bootstrap.contains("completeInitialPresentationPlcs("));
        assertFalse(bootstrap.contains("Sonic1PlcService"));
        assertFalse(bootstrap.contains("Sonic2PlcService"));
    }
}
