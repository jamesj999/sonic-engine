package com.openggf.game.rewind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Debug-only live rewind determinism audit.
 *
 * <p>Callers re-simulate a completed keyframe segment and pass the original
 * live keyframe plus the replayed capture here. The audit restores the live
 * keyframe after replay, but {@link RewindRegistry#restore(CompositeSnapshot)}
 * only restores registered snapshot entries. If replay mutated
 * out-of-snapshot state, the audit may perturb live play; therefore callers
 * disarm after the first divergence.
 */
public final class RewindDeterminismAuditor {

    private static final Logger LOGGER =
            Logger.getLogger(RewindDeterminismAuditor.class.getName());
    private static final int MAX_REPORT_LINES = 20;
    private static final int MAX_DIFF_LINES = MAX_REPORT_LINES - 2;

    private final Consumer<String> reporter;
    private int divergentSegmentCount;

    public RewindDeterminismAuditor() {
        this(LOGGER::warning);
    }

    public RewindDeterminismAuditor(Consumer<String> reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    public int divergentSegmentCount() {
        return divergentSegmentCount;
    }

    public boolean report(int fromFrame, int toFrame,
                          CompositeSnapshot live,
                          CompositeSnapshot replayed) {
        List<String> diffs = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(live.entries().keySet());
        keys.addAll(replayed.entries().keySet());
        for (String key : keys) {
            if (!live.containsKey(key)) {
                diffs.add(key + ": missing in live snapshot; replayed=" + replayed.get(key));
                if (diffs.size() >= MAX_DIFF_LINES) {
                    break;
                }
                continue;
            }
            if (!replayed.containsKey(key)) {
                diffs.add(key + ": missing in replayed snapshot; live=" + live.get(key));
                if (diffs.size() >= MAX_DIFF_LINES) {
                    break;
                }
                continue;
            }
            List<String> keyDiffs = RewindSnapshotDiff.diffKey(
                    key, live.get(key), replayed.get(key));
            for (String diff : keyDiffs) {
                if (diffs.size() >= MAX_DIFF_LINES) {
                    break;
                }
                diffs.add(diff);
            }
            if (diffs.size() >= MAX_DIFF_LINES) {
                break;
            }
        }
        if (diffs.isEmpty()) {
            return false;
        }

        divergentSegmentCount++;
        StringBuilder report = new StringBuilder();
        report.append("Live rewind determinism divergence in segment ")
                .append(fromFrame)
                .append("..")
                .append(toFrame)
                .append(System.lineSeparator());
        for (String diff : diffs) {
            report.append("  ")
                    .append(diff)
                    .append(System.lineSeparator());
        }
        report.append("Disarming live rewind determinism audit after first divergence: ")
                .append("replayed out-of-snapshot state cannot be rolled back safely.");
        reporter.accept(report.toString());
        return true;
    }
}
