package com.openggf.game.rewind.coverage;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TestRewindCoverageGuard {
    private static final Path BASELINE =
            Path.of("src/test/resources/rewind/coverage-baseline.txt");

    @Test
    void noNewCoverageGapsBeyondBaseline() throws Exception {
        // Compare stable gap KEYS, not class names: class#recreate,
        // class#finalScalar#field, class#objectRef#field.
        Set<String> current = new TreeSet<>(RewindCoverageAnalyzer.analyzeAll(Set.of()).gapKeys());
        Set<String> baseline = new TreeSet<>(Files.readAllLines(BASELINE));
        baseline.removeIf(String::isBlank);

        Set<String> regressions = new TreeSet<>(current);
        regressions.removeAll(baseline);
        assertTrue(regressions.isEmpty(),
                "New rewind-coverage gap keys introduced (not in baseline):\n  "
                        + String.join("\n  ", regressions)
                        + "\nFix the object's rewind coverage, or (only if intentional) "
                        + "add the key(s) to " + BASELINE + ".");

        // Informational: surface gaps closed since baseline so the baseline can be tightened.
        Set<String> closed = new TreeSet<>(baseline);
        closed.removeAll(current);
        if (!closed.isEmpty()) {
            System.out.println("[rewind-coverage] gaps closed since baseline (tighten baseline):\n  "
                    + String.join("\n  ", closed));
        }
    }
}
