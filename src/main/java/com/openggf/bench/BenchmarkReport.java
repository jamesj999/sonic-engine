package com.openggf.bench;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One JVM's benchmark result for one trace: the run's parameters, the runtime it
 * ran on, and a per-iteration breakdown.
 *
 * <p>Iterations are all retained rather than collapsed to a single figure.
 * Iteration 0 pays the cold-start cost and is the one that carries the
 * frames-to-steady-state measurement; later iterations run warm in the same JVM
 * and are what the steady-state comparison uses. Folding them together up front
 * would destroy the distinction the harness exists to measure.
 *
 * @param label          run label, defaulting to the runtime's short label
 * @param traceLabel     trace directory name
 * @param gameId         game the trace belongs to
 * @param zone           zone index
 * @param act            act index
 * @param mode           {@code update} (no GL) or {@code full} (with rendering)
 * @param warmupFrames   frames driven before measurement began
 * @param measuredFrames frames the measurement window requested
 * @param environment    the runtime this ran on
 * @param iterations     per-iteration results, iteration 0 first
 */
public record BenchmarkReport(String label, String traceLabel, String gameId, int zone, int act,
                              String mode, int warmupFrames, int measuredFrames,
                              JvmEnvironment environment, List<Iteration> iterations) {

    /**
     * A single measured pass over the trace window.
     *
     * @param index                0-based; index 0 is the cold pass
     * @param cold                 true for the pass that paid JIT/class-load warmup
     * @param frames               frames actually measured
     * @param wallNanos            wall time of the measured window
     * @param trajectoryDigest     {@link TrajectoryDigest} of the compared frames
     * @param framesToSteadyState  frames until timings settled, or -1 if not measured
     * @param frameTiming          whole-frame work-time distribution
     * @param sections             per-section distributions, heaviest total first
     * @param gc                   collections caused by this iteration
     * @param heapUsedBytes        heap in use at the end of the iteration
     * @param truncated            true if the run outran the timeline capacity
     */
    public record Iteration(int index, boolean cold, int frames, long wallNanos,
                            String trajectoryDigest, int framesToSteadyState,
                            SectionTiming frameTiming, List<SectionTiming> sections,
                            GcSnapshot gc, long heapUsedBytes, boolean truncated) {

        /** Frames per second the measured window sustained, pacing excluded. */
        public double throughputFps() {
            return wallNanos > 0 ? frames * 1_000_000_000.0 / wallNanos : 0;
        }

        public Optional<SectionTiming> section(String name) {
            return sections.stream().filter(s -> s.name().equals(name)).findFirst();
        }
    }

    /**
     * The iteration a comparison should quote: the fastest warm pass by median
     * frame time, falling back to the cold pass when only one was run.
     *
     * <p>Best-of-warm rather than mean-of-all. Benchmark noise on a desktop is
     * almost entirely additive — a scheduler preemption or a background process
     * can only make a pass slower, never faster — so the minimum across repeats
     * is the closest estimate of the runtime's actual cost.
     */
    public Optional<Iteration> representativeIteration() {
        List<Iteration> warm = iterations.stream().filter(i -> !i.cold()).toList();
        return (warm.isEmpty() ? iterations : warm).stream()
                .min(Comparator.comparingLong(i -> i.frameTiming().p50Nanos()));
    }

    /** The cold pass, which carries the frames-to-steady-state figure. */
    public Optional<Iteration> coldIteration() {
        return iterations.stream().filter(Iteration::cold).findFirst();
    }
}
