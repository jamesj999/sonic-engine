package com.openggf.bench;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Renders a set of {@link BenchmarkReport}s — normally the same trace measured
 * under several JVMs — as a Markdown comparison.
 *
 * <p>The first report in the list is the baseline every percentage is quoted
 * against. Sections appear in the order of the heaviest run so the expensive
 * work stays at the top of the table.
 *
 * <p>The determinism section is not decoration. If the trajectory digests
 * disagree, the runtimes did not execute the same work and the timing tables
 * above them are void; the report says so plainly rather than leaving a reader
 * to spot it.
 */
public final class BenchmarkComparison {

    private BenchmarkComparison() {
    }

    public static String render(List<BenchmarkReport> reports) {
        if (reports.isEmpty()) {
            return "No benchmark reports to compare.\n";
        }
        BenchmarkReport baseline = reports.get(0);
        StringBuilder out = new StringBuilder();

        out.append("# JVM benchmark comparison — ").append(baseline.traceLabel())
                .append(" (").append(baseline.mode()).append(" mode)\n\n");
        out.append("- Trace: `").append(baseline.traceLabel()).append("` — ")
                .append(baseline.gameId()).append(" zone ").append(baseline.zone())
                .append(" act ").append(baseline.act()).append('\n');
        out.append("- Window: ").append(baseline.warmupFrames()).append(" warmup frames, then ")
                .append(baseline.measuredFrames()).append(" measured frames\n");
        out.append("- Baseline: **").append(baseline.label()).append("**\n\n");

        appendMismatchWarnings(out, reports);
        appendSteadyStateTable(out, reports, baseline);
        appendWarmupTable(out, reports);
        appendSectionTable(out, reports, baseline);
        appendDeterminismTable(out, reports);
        appendEnvironments(out, reports);
        return out.toString();
    }

    /**
     * Flags anything that makes the tables below incomparable before the reader
     * reaches them: a different trace, a different mode, or a different measured
     * window means these runs are not measuring the same thing at all.
     */
    private static void appendMismatchWarnings(StringBuilder out, List<BenchmarkReport> reports) {
        BenchmarkReport baseline = reports.get(0);
        List<String> problems = new ArrayList<>();
        for (BenchmarkReport report : reports.subList(1, reports.size())) {
            if (!report.traceLabel().equals(baseline.traceLabel())) {
                problems.add(report.label() + " ran a different trace ("
                        + report.traceLabel() + ")");
            }
            if (!report.mode().equals(baseline.mode())) {
                problems.add(report.label() + " ran in " + report.mode() + " mode");
            }
            if (report.measuredFrames() != baseline.measuredFrames()
                    || report.warmupFrames() != baseline.warmupFrames()) {
                problems.add(report.label() + " used a different frame window ("
                        + report.warmupFrames() + "+" + report.measuredFrames() + ")");
            }
        }
        for (BenchmarkReport report : reports) {
            if (report.iterations().stream().anyMatch(BenchmarkReport.Iteration::truncated)) {
                problems.add(report.label()
                        + " outran its timeline capacity; its samples are a truncated prefix");
            }
        }
        if (problems.isEmpty()) {
            return;
        }
        out.append("> **These runs are not directly comparable.**\n>\n");
        for (String problem : problems) {
            out.append("> - ").append(problem).append('\n');
        }
        out.append('\n');
    }

    private static void appendSteadyStateTable(StringBuilder out, List<BenchmarkReport> reports,
                                               BenchmarkReport baseline) {
        out.append("## Steady-state frame time (best warm iteration)\n\n");
        out.append("| Runtime | p50 | p90 | p99 | p99.9 | max | mean | throughput | "
                + "GC count | GC ms | vs baseline p50 |\n");
        out.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");

        long baselineP50 = baseline.representativeIteration()
                .map(i -> i.frameTiming().p50Nanos()).orElse(0L);

        for (BenchmarkReport report : reports) {
            Optional<BenchmarkReport.Iteration> best = report.representativeIteration();
            if (best.isEmpty()) {
                out.append("| ").append(report.label()).append(" | _no iterations_ |")
                        .append(" |".repeat(10)).append('\n');
                continue;
            }
            BenchmarkReport.Iteration iteration = best.get();
            SectionTiming frame = iteration.frameTiming();
            out.append("| ").append(report.label())
                    .append(" | ").append(ms(frame.p50Nanos()))
                    .append(" | ").append(ms(frame.p90Nanos()))
                    .append(" | ").append(ms(frame.p99Nanos()))
                    .append(" | ").append(ms(frame.p999Nanos()))
                    .append(" | ").append(ms(frame.maxNanos()))
                    .append(" | ").append(fmt(frame.meanMillis())).append(" ms")
                    .append(" | ").append(fmt(iteration.throughputFps())).append(" fps")
                    .append(" | ").append(iteration.gc().totalCollections())
                    .append(" | ").append(iteration.gc().totalTimeMs())
                    .append(" | ").append(relative(frame.p50Nanos(), baselineP50))
                    .append(" |\n");
        }
        out.append("\nThroughput is the unpaced rate the measured window sustained — the engine\n")
                .append("is not running at 60Hz here, it is running as fast as it can, which is\n")
                .append("the only way a frame budget this small shows a difference at all.\n\n");
    }

    private static void appendWarmupTable(StringBuilder out, List<BenchmarkReport> reports) {
        out.append("## Warm-up\n\n");
        out.append("| Runtime | frames to steady state | cold p50 | warm p50 | cold penalty |\n");
        out.append("|---|--:|--:|--:|--:|\n");
        for (BenchmarkReport report : reports) {
            Optional<BenchmarkReport.Iteration> cold = report.coldIteration();
            Optional<BenchmarkReport.Iteration> warm = report.representativeIteration();
            long coldP50 = cold.map(i -> i.frameTiming().p50Nanos()).orElse(0L);
            long warmP50 = warm.map(i -> i.frameTiming().p50Nanos()).orElse(0L);
            int settleFrames = cold.map(BenchmarkReport.Iteration::framesToSteadyState).orElse(-1);
            out.append("| ").append(report.label())
                    .append(" | ").append(settleFrames < 0 ? "n/a" : String.valueOf(settleFrames))
                    .append(" | ").append(ms(coldP50))
                    .append(" | ").append(ms(warmP50))
                    .append(" | ").append(relative(coldP50, warmP50))
                    .append(" |\n");
        }
        out.append("\n\"Frames to steady state\" counts from the first driven frame of the cold\n")
                .append("pass, warmup frames included.\n\n");
    }

    private static void appendSectionTable(StringBuilder out, List<BenchmarkReport> reports,
                                           BenchmarkReport baseline) {
        Set<String> sections = new LinkedHashSet<>();
        for (BenchmarkReport report : reports) {
            report.representativeIteration().ifPresent(iteration -> {
                for (SectionTiming timing : iteration.sections()) {
                    sections.add(timing.name());
                }
            });
        }
        if (sections.isEmpty()) {
            return;
        }

        out.append("## Per-section median (ms per frame)\n\n");
        out.append("| Section |");
        for (BenchmarkReport report : reports) {
            out.append(' ').append(report.label()).append(" |");
        }
        out.append("\n|---|").append("--:|".repeat(reports.size())).append('\n');

        Optional<BenchmarkReport.Iteration> baselineIteration = baseline.representativeIteration();
        for (String section : sections) {
            out.append("| `").append(section).append("` |");
            for (BenchmarkReport report : reports) {
                Optional<SectionTiming> timing = report.representativeIteration()
                        .flatMap(i -> i.section(section));
                if (timing.isEmpty()) {
                    out.append(" — |");
                    continue;
                }
                out.append(' ').append(fmt(timing.get().p50Millis()));
                if (report != baseline) {
                    long base = baselineIteration.flatMap(i -> i.section(section))
                            .map(SectionTiming::p50Nanos).orElse(0L);
                    out.append(" (").append(relative(timing.get().p50Nanos(), base)).append(')');
                }
                out.append(" |");
            }
            out.append('\n');
        }
        out.append("\nSections are exclusive of one another and sum to roughly the frame total.\n")
                .append("In `full` mode the `render.*` rows are dominated by the graphics driver\n")
                .append("rather than the JVM — read them as a sanity check, not as a ranking.\n\n");
    }

    private static void appendDeterminismTable(StringBuilder out, List<BenchmarkReport> reports) {
        out.append("## Determinism\n\n");
        Set<String> digests = new LinkedHashSet<>();
        for (BenchmarkReport report : reports) {
            report.representativeIteration()
                    .ifPresent(iteration -> digests.add(iteration.trajectoryDigest()));
        }
        out.append("| Runtime | trajectory digest |\n|---|---|\n");
        for (BenchmarkReport report : reports) {
            out.append("| ").append(report.label()).append(" | `")
                    .append(report.representativeIteration()
                            .map(BenchmarkReport.Iteration::trajectoryDigest).orElse("—"))
                    .append("` |\n");
        }
        if (digests.size() > 1) {
            out.append("\n**The runtimes diverged.** They did not simulate the same trajectory, so\n")
                    .append("the timings above are measurements of different work and must not be\n")
                    .append("compared. Find the divergence before drawing any conclusion from this\n")
                    .append("run — a trace that diverges under one JVM is an engine bug, not a\n")
                    .append("benchmarking artefact.\n\n");
        } else {
            out.append("\nAll runtimes simulated an identical trajectory, so the timings compare\n")
                    .append("like for like.\n\n");
        }
    }

    private static void appendEnvironments(StringBuilder out, List<BenchmarkReport> reports) {
        out.append("## Runtimes\n\n");
        for (BenchmarkReport report : reports) {
            JvmEnvironment env = report.environment();
            out.append("### ").append(report.label()).append("\n\n");
            out.append("- ").append(env.vmName()).append(' ').append(env.vmVersion()).append('\n');
            out.append("- Vendor: ").append(env.vmVendor())
                    .append(" — Java ").append(env.javaVersion()).append('\n');
            out.append("- Collectors: ").append(String.join(", ", env.garbageCollectors()))
                    .append('\n');
            out.append("- Max heap: ").append(env.maxHeapBytes() / (1024 * 1024)).append(" MB")
                    .append(" — ").append(env.availableProcessors()).append(" processors\n");
            out.append("- Flags: ")
                    .append(env.inputArguments().isEmpty()
                            ? "_(none)_" : "`" + String.join(" ", env.inputArguments()) + "`")
                    .append("\n\n");
        }
    }

    private static String ms(long nanos) {
        return fmt(nanos / 1_000_000.0) + " ms";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Signed percentage of {@code value} against {@code baseline}; "—" if undefined. */
    private static String relative(long value, long baseline) {
        if (baseline <= 0) {
            return "—";
        }
        double percent = (value - baseline) * 100.0 / baseline;
        return String.format(Locale.ROOT, "%+.1f%%", percent);
    }
}
