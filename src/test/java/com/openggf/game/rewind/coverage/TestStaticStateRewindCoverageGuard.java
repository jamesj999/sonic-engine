package com.openggf.game.rewind.coverage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural guard for the "level trigger" rewind-desync bug class: a
 * global static state manager (mutable {@code private static} fields +
 * {@code public static void reset()}) consumed by gameplay objects across
 * frames but never registered with the rewind registry.
 *
 * <p>This is exactly the family of bug behind the MGZ dash-trigger /
 * trigger-platform desync ({@code Sonic3kLevelTriggerManager}) and its S2
 * analog ({@code ButtonVineTriggerManager}): the manager ratchets forward
 * outside any {@code RewindSnapshottable} adapter, so a backward rewind seek
 * leaves it set while the consuming object's own instance fields correctly
 * roll back -- a rewind-recreated instance then re-reads the still-set
 * manager state in its constructor and snaps to its post-trigger position
 * regardless of the rewound frame.
 *
 * <p>Same baseline-diff shape as {@link TestRewindCoverageGuard}: fails only
 * on a <em>new</em> gap key not already accepted in the committed baseline.
 * Pre-existing gaps are baselined with a rationale rather than silently
 * ignored, so they stay visible for follow-up work.
 */
class TestStaticStateRewindCoverageGuard {
    private static final Path BASELINE =
            Path.of("src/test/resources/rewind/static-state-coverage-baseline.txt");

    @Test
    void noNewStaticStateCoverageGapsBeyondBaseline() throws Exception {
        Set<String> current = new TreeSet<>(StaticStateRewindCoverageAnalyzer.analyzeGapKeys());
        Set<String> baseline = new TreeSet<>();
        for (String line : Files.readAllLines(BASELINE)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            baseline.add(trimmed);
        }

        Set<String> regressions = new TreeSet<>(current);
        regressions.removeAll(baseline);
        assertTrue(regressions.isEmpty(),
                "New static-state rewind-coverage gaps introduced (not in baseline):\n  "
                        + String.join("\n  ", regressions)
                        + "\nAdd a RewindSnapshottable adapter for the manager (see "
                        + "Sonic3kLevelTriggerStaticAdapter / ButtonVineTriggerStaticAdapter "
                        + "for the pattern) and register it via the owning "
                        + "AbstractLevelEventManager#extraRewindAdapters(), or (only if the "
                        + "state is genuinely rewind-exempt, e.g. boot-time-only config) add "
                        + "the key to " + BASELINE + " with a comment explaining why.");

        Set<String> closed = new TreeSet<>(baseline);
        closed.removeAll(current);
        if (!closed.isEmpty()) {
            System.out.println("[static-state-rewind-coverage] gaps closed since baseline (tighten baseline):\n  "
                    + String.join("\n  ", closed));
        }
    }
}
