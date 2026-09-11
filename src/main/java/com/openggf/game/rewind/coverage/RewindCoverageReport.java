package com.openggf.game.rewind.coverage;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Aggregated rewind coverage report across all enumerated object classes
 * for a game (or all games).
 *
 * <p>{@link #gapKeys()} returns the union of every object's gap keys — the
 * stable sorted set compared by the CI guard against a baseline file.
 *
 * <p>{@link #render()} produces a stable, sorted-by-className multi-line
 * text representation showing each class with its gap keys.
 */
public record RewindCoverageReport(List<ObjectCoverage> objects) {

    /**
     * Returns the union of every object's {@link ObjectCoverage#gapKeys()},
     * sorted ascending. This is what the guard and baseline compare.
     */
    public SortedSet<String> gapKeys() {
        SortedSet<String> keys = new TreeSet<>();
        for (ObjectCoverage obj : objects) {
            keys.addAll(obj.gapKeys());
        }
        return keys;
    }

    /**
     * Renders a stable, human-readable report sorted by class name.
     * Each class appears with its gap keys (or "OK" when fully covered).
     *
     * <p>The {@code objects} list is already sorted by class name when the report
     * is constructed (sorted in {@code RewindCoverageAnalyzer.buildReport}), so no
     * additional sort is performed here.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (ObjectCoverage obj : objects) {
            sb.append(obj.className());
            List<String> gaps = obj.gapKeys();
            if (gaps.isEmpty()) {
                sb.append(": OK");
            } else {
                for (String key : gaps) {
                    sb.append("\n  GAP: ").append(key);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
