package com.openggf.tests.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestTraceFixtureRootOverride {
    @AfterEach
    void clearOverride() {
        System.clearProperty("openggf.trace.fixtureRoot");
    }

    @Test
    void absentOverrideKeepsTheInstalledFixturePath() {
        Path requested = Path.of("src/test/resources/traces/s1/credits_00_ghz1");
        assertEquals(requested, TraceFixtureRoot.resolve(requested));
    }

    @Test
    void overrideMapsOnlyTheRelativePathBelowTheCanonicalTraceRoot() {
        System.setProperty("openggf.trace.fixtureRoot", "/scratch/v5-traces");
        assertEquals(Path.of("/scratch/v5-traces/s1/credits_00_ghz1"),
                TraceFixtureRoot.resolve(
                        Path.of("src/test/resources/traces/s1/credits_00_ghz1")));
        assertThrows(IllegalArgumentException.class,
                () -> TraceFixtureRoot.resolve(Path.of("some/other/data")));
    }

    @Test
    void authorityGuardConfinesOverrideLiteralToTheTestFixtureResolver() throws Exception {
        assertEquals(java.util.List.of(
                        Path.of("src/test/java/com/openggf/tests/trace/TestTraceFixtureRootOverride.java"),
                        Path.of("src/test/java/com/openggf/tests/trace/TraceFixtureRoot.java")),
                java.nio.file.Files.walk(Path.of("src"))
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> {
                            try {
                                return java.nio.file.Files.readString(path)
                                        .contains("openggf.trace.fixtureRoot");
                            } catch (java.io.IOException exception) {
                                throw new java.io.UncheckedIOException(exception);
                            }
                        })
                        .map(Path::normalize)
                        .sorted()
                        .toList());

        String resolver = java.nio.file.Files.readString(Path.of(
                "src/test/java/com/openggf/tests/trace/TraceFixtureRoot.java"));
        for (String forbidden : java.util.List.of(
                "com.openggf.game", "com.openggf.level", "com.openggf.sprites",
                "com.openggf.physics", "TraceData", "TraceEvent", "Files.write",
                "Files.create", "setGame", "hydrate", "reflect")) {
            assertFalse(resolver.contains(forbidden),
                    "candidate-root resolver gained gameplay, parser, write, or reflective authority: "
                            + forbidden);
        }
    }
}
