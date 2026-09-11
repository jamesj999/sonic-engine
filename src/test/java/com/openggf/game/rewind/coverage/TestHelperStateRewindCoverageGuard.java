package com.openggf.game.rewind.coverage;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Report-only-then-fail-on-new-gap guard for the uncaptured-helper-state gap class
 * (mirrors {@link TestRewindCoverageGuard}, restricted to the helper-state lane).
 *
 * <p>A helper-state gap is a {@code final} plain-helper field on a spawnable object
 * whose type has no {@link com.openggf.game.rewind.schema.RewindCodecs} codec yet
 * carries mutable frame-varying scalar state — so a backward rewind seek leaves it
 * stale. The baseline starts EMPTY (the sweep found no such gaps); any future
 * violation fails here. If a real gap appears, fix the helper's coverage (register a
 * codec, implement {@code RewindStateful}, or rename it into a name-matching
 * all-codec plain-state holder) — do not silently add the key to the baseline.
 */
class TestHelperStateRewindCoverageGuard {
    private static final Path BASELINE =
            Path.of("src/test/resources/rewind/helper-state-coverage-baseline.txt");

    private static final String HELPER_STATE_SUFFIX = "#uncaptured-helper-state";

    @Test
    void noNewHelperStateGapsBeyondBaseline() throws Exception {
        // Only this guard's own gap kind is compared here; recreate/finalScalar/objectRef
        // gaps are tracked by TestRewindCoverageGuard against coverage-baseline.txt.
        Set<String> current = new TreeSet<>();
        for (String key : RewindCoverageAnalyzer.analyzeAll(Set.of()).gapKeys()) {
            if (key.endsWith(HELPER_STATE_SUFFIX)) {
                current.add(key);
            }
        }
        Set<String> baseline = new TreeSet<>(Files.readAllLines(BASELINE));
        baseline.removeIf(String::isBlank);

        Set<String> regressions = new TreeSet<>(current);
        regressions.removeAll(baseline);
        assertTrue(regressions.isEmpty(),
                "New uncaptured-helper-state gap keys introduced (not in baseline):\n  "
                        + String.join("\n  ", regressions)
                        + "\nFix the helper's rewind coverage (register a codec, implement "
                        + "RewindStateful, or make it a name-matching all-codec plain-state "
                        + "holder). Do NOT silently baseline it — a real gap is a bug.");

        // Informational: surface gaps closed since baseline so the baseline can be tightened.
        Set<String> closed = new TreeSet<>(baseline);
        closed.removeAll(current);
        if (!closed.isEmpty()) {
            System.out.println("[helper-state-coverage] gaps closed since baseline (tighten baseline):\n  "
                    + String.join("\n  ", closed));
        }
    }
}
